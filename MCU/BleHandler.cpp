#include "BleHandler.h"
#include "TotpValidator.h"
#include "LedIndicator.h"
#include "BuzzerManager.h"
#include "Logger.h"
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>
#include "HardwareConfig.h"
#include "RTCManager.h"
#include "AntennaManager.h"

int BleHandler::_relayPin = -1;
int BleHandler::_sensorPin = -1;
int BleHandler::_lastSensorState = -1;
uint8_t BleHandler::_lastTriggeredTargetState = 1;
bool BleHandler::_isPaused = false;
uint32_t BleHandler::_lastValidCode = 0;
uint64_t BleHandler::_lastAcceptedStep = 0;
unsigned long BleHandler::_lastValidTime = 0;
unsigned long BleHandler::_lastTimeSyncMs = 0;

bool BleHandler::_isTimeInvalid = false;
bool BleHandler::_isTimeSyncMode = false;
unsigned long BleHandler::_timeSyncStartTime = 0;
bool BleHandler::_buttonIsDown = false;
unsigned long BleHandler::_buttonPressStartTime = 0;
bool BleHandler::_button10sTriggered = false;
volatile bool BleHandler::_buttonPressedFlag = false;
volatile bool BleHandler::_needsUpdateStateBeacon = false;
volatile bool BleHandler::_needsStartAdvertisingACK = false;
volatile bool BleHandler::_needsStartAdvertisingNACK = false;
volatile bool BleHandler::_needsTriggerAction = false;
volatile bool BleHandler::_needsDuplicateAction = false;
volatile uint8_t BleHandler::_needsErrorFeedback = 0;
volatile time_t BleHandler::_pendingTimeSyncEpoch = 0;

bool BleHandler::_isAckActive = false;
unsigned long BleHandler::_ackStartTime = 0;
unsigned long BleHandler::_lastBeaconTime = 0;
bool BleHandler::_isRelayActive = false;
unsigned long BleHandler::_relayStartTime = 0;

const char* BleHandler::SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0";

BLEScan* pBLEScan = nullptr;
BLEAdvertising* pAdvertising = nullptr;

class BoxScanCallbacks : public BLEAdvertisedDeviceCallbacks {
    void onResult(BLEAdvertisedDevice advertisedDevice) {
        // Guard RAII per svuotare m_scanResults sul thread NimBLE ed evitare il memory leak
        // di BLEAdvertisedDevice::m_payload (realloc ad ogni pacchetto duplicato) e della mappa dispositivi.
        struct ScanResultCleaner {
            ~ScanResultCleaner() {
                if (pBLEScan != nullptr && pBLEScan->getResults()->getCount() > 0) {
                    pBLEScan->clearResults();
                }
            }
        } cleaner;

        if (BleHandler::_isPaused) return;

        if (advertisedDevice.haveManufacturerData()) {
            String mData = advertisedDevice.getManufacturerData();
            // L'app Android invia Manufacturer Data con ID 0x02E5 e payload ASCII (es. "123456")
            if (mData.length() >= 8) { 
                uint8_t idLow = (uint8_t)mData[0];
                uint8_t idHigh = (uint8_t)mData[1];
                if (idLow == 0xE5 && idHigh == 0x02) {
                    if (mData.length() >= 11 && (uint8_t)mData[2] == 0xFF) {
                        unsigned long nowMs = millis();
                        // Ignora i pacchetti ripetuti dello stesso burst di sincronizzazione (entro 5s)
                        if (BleHandler::_lastTimeSyncMs != 0 && (nowMs - BleHandler::_lastTimeSyncMs < 5000)) {
                            return;
                        }

                        Serial.println("---- TIME SYNC PAYLOAD RICEVUTO ----");
                        if (!BleHandler::_isTimeSyncMode && !BleHandler::_isTimeInvalid) {
                            Serial.println("Rifiutato: Sistema non in TIME_SYNC_MODE né in stato INVALID.");
                            return;
                        }
                        
                        uint32_t ts = ((uint32_t)(uint8_t)mData[3] << 24) |
                                      ((uint32_t)(uint8_t)mData[4] << 16) |
                                      ((uint32_t)(uint8_t)mData[5] << 8)  |
                                      (uint32_t)(uint8_t)mData[6];
                        if (ts < 1704067200UL) { // Rifiuta timestamp antecedenti al 1 Gen 2024
                            Serial.println("Rifiutato: Timestamp Time Sync non valido (< 2024).");
                            BleHandler::_needsErrorFeedback = 3;
                            return;
                        }

                        uint8_t hmac[4];
                        hmac[0] = (uint8_t)mData[7];
                        hmac[1] = (uint8_t)mData[8];
                        hmac[2] = (uint8_t)mData[9];
                        hmac[3] = (uint8_t)mData[10];

                        if (TotpValidator::verifyTimeSync(ts, hmac)) {
                            Serial.println("Time Sync verificato! Programmazione aggiornamento orologio...");
                            BleHandler::_lastTimeSyncMs = nowMs;
                            BleHandler::_pendingTimeSyncEpoch = (time_t)ts;
                        } else {
                            Serial.println("Firma Time Sync non valida.");
                            BleHandler::_needsErrorFeedback = 3;
                        }
                    } else if (mData.length() >= 8) {
                        String totpStr;
                        if (mData.length() >= 14) {
                            // Nuovo formato multi-dispositivo: [0xE5 0x02] [MAC 6-bytes] [TOTP 6-bytes]
                            uint8_t* myMac = (uint8_t*)BLEDevice::getAddress().getNative();
                            bool macMatch = true;
                            // L'indirizzo MAC BLE viene trasmesso in Little Endian over-the-air, quindi Android lo legge rovesciato rispetto al getNative() dell'ESP32.
                            for (int i = 0; i < 6; i++) {
                                if ((uint8_t)mData[2 + i] != myMac[5 - i]) {
                                    macMatch = false;
                                    break;
                                }
                            }
                            
                            if (!macMatch) {
                                Serial.printf("MAC mismatch. Received: %02X:%02X:%02X:%02X:%02X:%02X, Expected(Native Reversed): %02X:%02X:%02X:%02X:%02X:%02X\n",
                                    (uint8_t)mData[2], (uint8_t)mData[3], (uint8_t)mData[4], (uint8_t)mData[5], (uint8_t)mData[6], (uint8_t)mData[7],
                                    myMac[5], myMac[4], myMac[3], myMac[2], myMac[1], myMac[0]);
                                return;
                            }
                            Serial.println("---- PACCHETTO BLE INDIRIZZATO A QUESTO DEVICE RICEVUTO ----");
                            totpStr = mData.substring(8, 14);
                        } else {
                            // Vecchio formato broadcast (retrocompatibilità per l'app attuale non ancora aggiornata)
                            Serial.println("---- PACCHETTO BLE BROADCAST (VECCHIO FORMATO) RICEVUTO ----");
                            totpStr = mData.substring(2, 8);
                        }
                        
                        if (BleHandler::_isTimeInvalid && !BleHandler::_isTimeSyncMode) {
                            Serial.println("ERRORE: Orario non valido (RTC scarico). Operazione bloccata.");
                            BleHandler::_needsErrorFeedback = 2;
                            BleHandler::_needsStartAdvertisingNACK = true;
                            return;
                        }

                        // Verifica che il TOTP contenga esattamente 6 cifre numeriche ASCII
                        if (totpStr.length() != 6) {
                            return;
                        }
                        for (int i = 0; i < 6; i++) {
                            if (totpStr[i] < '0' || totpStr[i] > '9') {
                                Serial.println("ERRORE: Payload TOTP non numerico ignorato.");
                                return;
                            }
                        }
                        
                        uint32_t totpCode = (uint32_t)totpStr.toInt();
                        Serial.printf("TOTP estratto dal pacchetto: %06u\n", totpCode);

                        uint64_t matchedStep = 0;
                        if (TotpValidator::validate(totpCode, 2, &matchedStep)) {
                            unsigned long nowMs = millis();
                            
                            // Check idempotency per i pacchetti ripetuti dello stesso burst (stesso step entro 60s)
                            if (matchedStep == BleHandler::_lastAcceptedStep &&
                                totpCode == BleHandler::_lastValidCode &&
                                (nowMs - BleHandler::_lastValidTime < 60000)) {
                                Serial.println("Valid TOTP received, but it's a duplicate. Resetting ACK timer.");
                                BleHandler::_needsDuplicateAction = true;
                                return;
                            }

                            // Anti-replay: rifiuta codici appartenenti a finestre temporali già consumate o precedenti
                            if (BleHandler::_lastAcceptedStep > 0 && matchedStep <= BleHandler::_lastAcceptedStep) {
                                Serial.println("ERRORE: Rilevato replay di un codice TOTP già utilizzato.");
                                BleHandler::_needsErrorFeedback = 1;
                                return;
                            }

                            Serial.println(">>> Valid NEW TOTP received! Triggering Relay. <<<");
                            BleHandler::_lastAcceptedStep = matchedStep;
                            BleHandler::_lastValidCode = totpCode;
                            BleHandler::_lastValidTime = nowMs;
                            BleHandler::_needsTriggerAction = true;
                        } else {
                            BleHandler::_needsErrorFeedback = 1;
                            Serial.println("ERRORE: Validazione TOTP fallita per questo pacchetto.");
                        }
                        Serial.println("--------------------------------");
                    }
                }
            }
        }
    }
};

void BleHandler::init(int relayPin, int sensorPin) {
    _relayPin = relayPin;
    _sensorPin = sensorPin;

    pinMode(_relayPin, OUTPUT);
    digitalWrite(_relayPin, HW_RELAY_OFF_STATE);
    
    pinMode(_sensorPin, HW_SENSOR_PIN_MODE);
    _lastSensorState = digitalRead(_sensorPin);

    // Configura pulsante BOOT
    pinMode(HW_BOOT_PIN, INPUT_PULLUP);

#ifdef HW_EXTERNAL_BTN_PIN
    // Configura pulsante esterno sul PCB
    pinMode(HW_EXTERNAL_BTN_PIN, INPUT_PULLUP);
#endif

    // Controllo validità orario
    time_t now;
    time(&now);
    if (now < 1704067200) { // Gen 1 2024
        _isTimeInvalid = true;
        Serial.println("ATTENZIONE: Orario non valido (RTC scarico). Operazioni inibite.");
    }

    BLEDevice::init("ApritiSedano");
    Serial.printf("\n==================================\n");
    Serial.printf("MAC ADDRESS DISPOSITIVO: %s\n", BLEDevice::getAddress().toString().c_str());
    Serial.printf("==================================\n\n");
    
    Serial.printf("BLE Listening on Service UUID: %s\n", SERVICE_UUID);

    // Setup Scanner (istanza statica per evitare allocazione su heap)
    static BoxScanCallbacks scanCallbacks;
    pBLEScan = BLEDevice::getScan();
    pBLEScan->setAdvertisedDeviceCallbacks(&scanCallbacks, true); // true = wants duplicates
    pBLEScan->setActiveScan(false); // Passive scan is enough
    
    // OTTIMIZZAZIONE ENERGETICA: Il duty cycle della radio al 99% la surriscalda. 
    // Un duty cycle del 30% (300ms attivi ogni 1000ms) o 50% (500/1000) è eccellente. 
    // Visto che l'app Android spara pacchetti per ben 3 secondi di fila, 
    // un duty cycle di 300ms Window e 1000ms Interval garantisce ricezione al 100% abbattendo calore e consumi.
    pBLEScan->setInterval(1000); 
    pBLEScan->setWindow(300);

    // Setup Advertising
    pAdvertising = BLEDevice::getAdvertising();
    
    // OTTIMIZZAZIONE ENERGETICA: Evitiamo che l'advertising spari pacchetti in continuazione.
    // Impostiamo l'intervallo a circa 320ms (0x0200 * 0.625ms = 320ms).
    pAdvertising->setMinInterval(0x0200);
    pAdvertising->setMaxInterval(0x0200);
    
    // Set Flags and Service UUID once for the primary advertisement data
    BLEAdvertisementData oAdvertisementData;
    oAdvertisementData.setFlags(0x06); // General Discoverable, BR/EDR Not Supported
    oAdvertisementData.setCompleteServices(BLEUUID(SERVICE_UUID));
    pAdvertising->setAdvertisementData(oAdvertisementData);
    pAdvertising->setScanResponse(true);
    
    // Initial Beacon update
    updateStateBeacon();
    
    // Start advertising continuously
    pAdvertising->start();
    Serial.println("Started continuous BLE State Beacon");
    
    pBLEScan->start(0, nullptr, false); // 0 = continuous scan
}

void BleHandler::onTimeUpdated() {
    time_t now;
    time(&now);
    _isTimeInvalid = (now < 1704067200);
    _isTimeSyncMode = false;
    _lastAcceptedStep = 0;
    _needsUpdateStateBeacon = true;
}

void IRAM_ATTR BleHandler::handleButtonPress() {
    _buttonPressedFlag = true;
}

void BleHandler::triggerRelay() {
    digitalWrite(_relayPin, HW_RELAY_ON_STATE);  // Attiva il relè secondo la configurazione in HardwareConfig.h
    _isRelayActive = true;
    _relayStartTime = millis();
}

void BleHandler::startAdvertisingNACK() {
    _isAckActive = true;
    _ackStartTime = millis();
    
    static BLEAdvertisementData oScanResponseData;
    oScanResponseData = BLEAdvertisementData();

    const char ackPayload[] = "NACK_TIME_ERR";
    const size_t payloadLen = sizeof(ackPayload) - 1;
    
    char rawAdData[4 + sizeof(ackPayload)];
    rawAdData[0] = (char)(payloadLen + 3);
    rawAdData[1] = (char)0xFF;
    rawAdData[2] = (char)0xE5;
    rawAdData[3] = (char)0x02;
    memcpy(&rawAdData[4], ackPayload, payloadLen);
    
    oScanResponseData.addData(rawAdData, 4 + payloadLen);

    pAdvertising->setScanResponseData(oScanResponseData);
    Serial.println("Temporarily broadcasting NACK payload");
}

void BleHandler::startAdvertisingACK() {
    _isAckActive = true;
    _ackStartTime = millis();
    
    static BLEAdvertisementData oScanResponseData;
    oScanResponseData = BLEAdvertisementData();

    // Usa lo stato target dell'operazione appena innescata (1 = apertura -> ACK_OK_OPEN, 0 = chiusura -> ACK_OK_CLOSED)
    // poiché nei primi millisecondi dall'impulso del relè la basculante non si è ancora fisicamente mossa dal sensore.
    const char* ackPayload = (_lastTriggeredTargetState == 1) ? "ACK_OK_OPEN" : "ACK_OK_CLOSED";
    const size_t payloadLen = strlen(ackPayload);
    
    char rawAdData[20];
    rawAdData[0] = (char)(payloadLen + 3); // 1 byte per la Lunghezza totale
    rawAdData[1] = (char)0xFF;             // 1 byte per il Tipo: Manufacturer Specific Data
    rawAdData[2] = (char)0xE5;             // 1 byte ID Company Low
    rawAdData[3] = (char)0x02;             // 1 byte ID Company High
    memcpy(&rawAdData[4], ackPayload, payloadLen);
    
    oScanResponseData.addData(rawAdData, 4 + payloadLen);

    pAdvertising->setScanResponseData(oScanResponseData);
    Serial.println("Temporarily broadcasting ACK payload");
}

void BleHandler::stopAdvertisingACK() {
    if (_isAckActive) {
        _isAckActive = false;
        Serial.println("ACK finished, returning to State Beacon");
        updateStateBeacon();
    }
}

void BleHandler::updateStateBeacon() {
    if (_isAckActive || _isPaused) return;

    static BLEAdvertisementData oScanResponseData;
    oScanResponseData = BLEAdvertisementData();

    uint8_t beaconType;
    if (_isTimeSyncMode) {
        beaconType = 0x03;
    } else if (_isTimeInvalid) {
        beaconType = 0x02;
    } else {
        beaconType = 0x01;
    }
    
    // Stato Sensore (0x00 = Closed, 0x01 = Open)
    uint8_t doorState = (digitalRead(_sensorPin) == HW_SENSOR_CLOSED_STATE) ? 0x00 : 0x01;
    
    time_t now;
    time(&now);
    uint32_t ts = (uint32_t)now;

    uint8_t hmac[4];
    TotpValidator::signStateBeacon(doorState, ts, hmac);

    // Payload su stack: [Len: 1B] [0xFF] [0xE5 0x02] [Type: 1B] [State: 1B] [Timestamp: 4B BE] [HMAC: 4B] = 14 bytes
    char rawAdData[14];
    rawAdData[0] = (char)13; // Length = 1 + 2 + 10 = 13 bytes
    rawAdData[1] = (char)0xFF; // Manufacturer Specific Data
    rawAdData[2] = (char)0xE5; // Company ID Low
    rawAdData[3] = (char)0x02; // Company ID High
    rawAdData[4] = (char)beaconType; // Payload Type (0x01 = State, 0x02 = Invalid, 0x03 = Sync)
    rawAdData[5] = (char)doorState;  // State
    rawAdData[6] = (char)((ts >> 24) & 0xFF);
    rawAdData[7] = (char)((ts >> 16) & 0xFF);
    rawAdData[8] = (char)((ts >> 8) & 0xFF);
    rawAdData[9] = (char)(ts & 0xFF);
    rawAdData[10] = (char)hmac[0];
    rawAdData[11] = (char)hmac[1];
    rawAdData[12] = (char)hmac[2];
    rawAdData[13] = (char)hmac[3];

    oScanResponseData.addData(rawAdData, sizeof(rawAdData));
    pAdvertising->setScanResponseData(oScanResponseData);
}

void BleHandler::loop() {
    if (_isPaused) return;

    // Gestione Pulsanti (HW_EXTERNAL_BTN_PIN prioritario con fallback su HW_BOOT_PIN)
    bool isBtnPressed = false;
#ifdef HW_EXTERNAL_BTN_PIN
    if (digitalRead(HW_EXTERNAL_BTN_PIN) == LOW) isBtnPressed = true;
#endif
    if (!isBtnPressed && digitalRead(HW_BOOT_PIN) == LOW) isBtnPressed = true;

    unsigned long now = millis();

    if (isBtnPressed) {
        if (!_buttonIsDown) {
            _buttonIsDown = true;
            _buttonPressStartTime = now;
            _button10sTriggered = false;
        } else if (!_button10sTriggered && (now - _buttonPressStartTime >= 10000)) {
            _button10sTriggered = true;
            Serial.println("Pulsante tenuto premuto per 10 secondi: Toggle Antenna!");
            AntennaState result = AntennaManager::toggle();
            if (result == ANTENNA_INTERNAL) {
                BuzzerManager::playAntennaInternalSequence(); // 1 beep lungo
                Logger::logOperation("ANT_INTERNA");
            } else if (result == ANTENNA_EXTERNAL) {
                BuzzerManager::playAntennaExternalSequence(); // 2 beep lunghi
                Logger::logOperation("ANT_ESTERNA");
            } else {
                BuzzerManager::playAntennaErrorSequence();    // 3 beep lunghi
                Logger::logOperation("ANT_ERR_UNSUPPORTED");
            }
        }
    } else {
        if (_buttonIsDown) {
            unsigned long pressDuration = now - _buttonPressStartTime;
            _buttonIsDown = false;
            
            // Pressione breve (< 10s con debounce > 50ms)
            if (!_button10sTriggered && pressDuration > 50) {
                if (_isTimeInvalid && !_isTimeSyncMode) {
                    _isTimeSyncMode = true;
                    _timeSyncStartTime = now;
                    Serial.println("Pulsante premuto. Entro in modalità sincronizzazione orario per 5 minuti.");
                    _needsUpdateStateBeacon = true;
                } else if (!_isTimeInvalid && TotpValidator::hasSecret()) {
                    // Check di Stato / Heartbeat se il sistema è sano e pronto
                    LedIndicator::setState(LED_HEARTBEAT);
                    Serial.println("Pulsante premuto: Heartbeat di diagnostica attivato.");
                }
            }
            _button10sTriggered = false;
        }
    }

    // Esecuzione sicura su loopTask degli eventi ricevuti dal callback BLE
    if (_pendingTimeSyncEpoch != 0) {
        time_t syncEpoch = _pendingTimeSyncEpoch;
        _pendingTimeSyncEpoch = 0;

        RTCManager::setRTC(syncEpoch);
        struct timeval tv;
        tv.tv_sec = syncEpoch;
        tv.tv_usec = 0;
        settimeofday(&tv, NULL);

        _isTimeInvalid = false;
        _isTimeSyncMode = false;
        _lastAcceptedStep = 0;
        LedIndicator::setState(LED_SUCCESS);
        BuzzerManager::playSuccessSequence();
        Logger::logOperation("TIME_SYNC");
        _needsUpdateStateBeacon = true;
    }

    if (_needsTriggerAction) {
        _needsTriggerAction = false;
        LedIndicator::setState(LED_SUCCESS);

        // Determina la direzione in base allo stato del sensore PRIMA dell'azionamento del relè
        if (digitalRead(_sensorPin) == HW_SENSOR_CLOSED_STATE) {
            _lastTriggeredTargetState = 1; // La porta era chiusa -> si sta aprendo
            BuzzerManager::playOpenSequence();
            Logger::logOperation("APERTO");
        } else {
            _lastTriggeredTargetState = 0; // La porta era aperta -> si sta chiudendo
            BuzzerManager::playCloseSequence();
            Logger::logOperation("CHIUSO");
        }

        triggerRelay();
        startAdvertisingACK();
    }

    if (_needsDuplicateAction) {
        _needsDuplicateAction = false;
        LedIndicator::setState(LED_DUPLICATE);
        startAdvertisingACK();
    }

    if (_needsErrorFeedback != 0) {
        uint8_t errType = _needsErrorFeedback;
        _needsErrorFeedback = 0;
        LedIndicator::setState(LED_ERROR);
        if (errType == 2 || errType == 3) {
            BuzzerManager::playErrorSequence();
        }
    }

    if (_needsUpdateStateBeacon) {
        _needsUpdateStateBeacon = false;
        updateStateBeacon();
    }
    if (_needsStartAdvertisingACK) {
        _needsStartAdvertisingACK = false;
        startAdvertisingACK();
    }
    if (_needsStartAdvertisingNACK) {
        _needsStartAdvertisingNACK = false;
        startAdvertisingNACK();
    }

    if (_isTimeSyncMode && (millis() - _timeSyncStartTime >= 300000UL)) {
        _isTimeSyncMode = false;
        Serial.println("Timeout modalità Time Sync scaduto.");
        updateStateBeacon();
    }

    // --- DEBUG: Sensor state change detection ---
    if (_sensorPin != -1) {
        int currentSensorState = digitalRead(_sensorPin);
        if (currentSensorState != _lastSensorState) {
            _lastSensorState = currentSensorState;
            
            if (currentSensorState == HW_SENSOR_CLOSED_STATE) {
                // CLOSED
                Serial.println(">>> [DEBUG] SENSOR STATE CHANGED: CLOSED");
            } else {
                // OPEN
                Serial.println(">>> [DEBUG] SENSOR STATE CHANGED: OPEN");
            }
            // Aggiorna subito il beacon quando cambia lo stato, a meno che non siamo in ACK
            updateStateBeacon();
        }
    }
    // --------------------------------------------

    if (_isRelayActive) {
        if (millis() - _relayStartTime >= 1000UL) {
            digitalWrite(_relayPin, HW_RELAY_OFF_STATE); // Torna a stato disattivato
            _isRelayActive = false;
        }
    }

    if (_isAckActive) {
        if (millis() - _ackStartTime >= 3000UL) {
            stopAdvertisingACK();
        }
    } else {
        // Update beacon every second
        if (millis() - _lastBeaconTime >= 1000UL) {
            _lastBeaconTime = millis();
            updateStateBeacon();
        }
    }
}

void BleHandler::pause() {
    _isPaused = true;
    if (_isAckActive) {
        stopAdvertisingACK();
    }
    if (pBLEScan) {
        pBLEScan->stop();
        pBLEScan->clearResults();
    }
    if (pAdvertising) {
        pAdvertising->stop();
    }
}

void BleHandler::resume() {
    _isPaused = false;
    onTimeUpdated();
    if (pBLEScan) {
        pBLEScan->start(0, nullptr, false);
    }
    if (pAdvertising) {
        pAdvertising->start();
    }
}
