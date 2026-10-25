#include "BleHandler.h"
#include "RollingCodeManager.h"
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
uint8_t BleHandler::_ownMac[6] = {0};
bool BleHandler::_isPaused = false;

unsigned long BleHandler::_lastTimeSyncMs = 0;
bool BleHandler::_isPairingMode = false;
unsigned long BleHandler::_pairingStartTime = 0;
unsigned long BleHandler::_lastPairingBeepTime = 0;

bool BleHandler::_buttonIsDown = false;
unsigned long BleHandler::_buttonPressStartTime = 0;
bool BleHandler::_button3sTriggered = false;
bool BleHandler::_button10sTriggered = false;

volatile bool BleHandler::_needsUpdateStateBeacon = false;
volatile bool BleHandler::_needsTriggerAction = false;
volatile bool BleHandler::_needsDuplicateAction = false;
volatile bool BleHandler::_needsEnterPairingAction = false;
volatile bool BleHandler::_needsPairedSuccessAction = false;
volatile uint8_t BleHandler::_pendingResponseCode = RESP_NONE;
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
        if (!advertisedDevice.haveManufacturerData()) return;

        String mData = advertisedDevice.getManufacturerData();
        // Tutti i pacchetti indirizzati iniziano con [0xE5, 0x02, cmdByte, TargetMAC(6B), ...] -> almeno 11 byte
        if (mData.length() < 11) return;

        uint8_t idLow = (uint8_t)mData[0];
        uint8_t idHigh = (uint8_t)mData[1];
        if (idLow != 0xE5 || idHigh != 0x02) return;

        uint8_t cmdByte = (uint8_t)mData[2];

        // Verifica indirizzo MAC destinatario (mData[3..8]) in ordine canonico Big-Endian (AA:BB:CC:DD:EE:FF),
        // compatibile sia con NimBLE (XIAO ESP32-C6) sia con Bluedroid (ESP32-WROOM32).
        uint8_t targetMac6[6];
        for (int i = 0; i < 6; i++) {
            targetMac6[i] = (uint8_t)mData[3 + i];
            if (targetMac6[i] != BleHandler::_ownMac[i]) {
                return; // Pacchetto indirizzato a un altro dispositivo
            }
        }

        // 1. Comando Trigger Apertura/Chiusura (0x01) o Attivazione Modalità Accoppiamento da Remoto (0x02)
        // Layout (19 byte): [0xE5, 0x02] [cmd(1B)] [MAC(6B)] [ClientID(2B BE)] [Counter(4B BE)] [RollingCode(4B BE)]
        if ((cmdByte == RC_CMD_TRIGGER || cmdByte == RC_CMD_ENTER_PAIRING) && mData.length() >= 19) {
            uint16_t clientId = ((uint16_t)(uint8_t)mData[9] << 8) | (uint16_t)(uint8_t)mData[10];
            uint32_t counter = ((uint32_t)(uint8_t)mData[11] << 24) |
                               ((uint32_t)(uint8_t)mData[12] << 16) |
                               ((uint32_t)(uint8_t)mData[13] << 8)  |
                               (uint32_t)(uint8_t)mData[14];
            uint32_t receivedCode = ((uint32_t)(uint8_t)mData[15] << 24) |
                                    ((uint32_t)(uint8_t)mData[16] << 16) |
                                    ((uint32_t)(uint8_t)mData[17] << 8)  |
                                    (uint32_t)(uint8_t)mData[18];

            RollingValidationResult res = RollingCodeManager::validateCommand(
                cmdByte, targetMac6, clientId, counter, receivedCode
            );

            if (res == RC_VALID_NEW) {
                if (cmdByte == RC_CMD_TRIGGER) {
                    Serial.printf(">>> Rolling Code VALIDO (Client 0x%04X, Counter %lu) -> Azionamento Relè <<<\n",
                                  clientId, (unsigned long)counter);
                    BleHandler::_needsTriggerAction = true;
                } else {
                    Serial.printf(">>> Richiesta Modalità Accoppiamento VALIDA (Client 0x%04X, Counter %lu) <<<\n",
                                  clientId, (unsigned long)counter);
                    BleHandler::_needsEnterPairingAction = true;
                }
            } else if (res == RC_VALID_DUPLICATE) {
                if (cmdByte == RC_CMD_TRIGGER) {
                    BleHandler::_needsDuplicateAction = true;
                } else {
                    BleHandler::_pendingResponseCode = RESP_ACK_PAIR_MODE;
                }
            } else if (res == RC_ERR_USED_CODE) {
                Serial.printf("ERRORE: Seed/Counter %lu già utilizzato per Client 0x%04X! Invio NACK_USED.\n",
                              (unsigned long)counter, clientId);
                BleHandler::_needsErrorFeedback = 1;
                BleHandler::_pendingResponseCode = RESP_NACK_USED;
            } else if (res == RC_ERR_OUT_OF_WINDOW) {
                Serial.printf("ERRORE: Counter %lu fuori finestra per Client 0x%04X! Invio NACK_ERR.\n",
                              (unsigned long)counter, clientId);
                BleHandler::_needsErrorFeedback = 1;
                BleHandler::_pendingResponseCode = RESP_NACK_ERR;
            } else {
                Serial.printf("ERRORE: Firma Rolling Code non valida o Client 0x%04X sconosciuto.\n", clientId);
                BleHandler::_needsErrorFeedback = 1;
                BleHandler::_pendingResponseCode = RESP_NACK_ERR;
            }
            return;
        }

        // 2. Registrazione Nuovo Smartphone in Modalità Accoppiamento (0xFE)
        // Layout (27 byte): [0xE5, 0x02] [0xFE] [MAC(6B)] [ClientID(2B BE)] [Seed(16B)]
        if (cmdByte == RC_CMD_REGISTER_SEED && mData.length() >= 27) {
            uint16_t clientId = ((uint16_t)(uint8_t)mData[9] << 8) | (uint16_t)(uint8_t)mData[10];
            uint8_t seed16[RC_SEED_SIZE];
            for (int i = 0; i < RC_SEED_SIZE; i++) {
                seed16[i] = (uint8_t)mData[11 + i];
            }

            // Controlla se è una ripetizione del pacchetto appena accoppiato (<5s) o se siamo in modalità accoppiamento
            if (!BleHandler::_isPairingMode) {
                if (RollingCodeManager::isDuplicatePairingBurst(clientId, seed16)) {
                    BleHandler::_pendingResponseCode = RESP_ACK_PAIRED;
                } else {
                    Serial.println("Rifiutato: Richiesta accoppiamento ricevuta ma il ricevitore NON è in Pairing Mode.");
                    BleHandler::_needsErrorFeedback = 1;
                    BleHandler::_pendingResponseCode = RESP_NACK_ERR;
                }
                return;
            }

            PairingRegistrationResult pairRes = RollingCodeManager::registerClient(clientId, seed16);
            if (pairRes == RC_PAIR_SUCCESS) {
                Serial.printf(">>> Nuovo Smartphone Associato! ClientID: 0x%04X <<<\n", clientId);
                BleHandler::_isPairingMode = false;
                BleHandler::_needsPairedSuccessAction = true;
            } else if (pairRes == RC_PAIR_DUPLICATE) {
                BleHandler::_pendingResponseCode = RESP_ACK_PAIRED;
            } else {
                Serial.println("ERRORE: Memoria slot smartphone piena (max 16).");
                BleHandler::_needsErrorFeedback = 2;
                BleHandler::_pendingResponseCode = RESP_NACK_ERR;
            }
            return;
        }

        // 3. Sincronizzazione Orologio RTC (0xFF) firmata da uno smartphone associato
        // Layout (19 byte): [0xE5, 0x02] [0xFF] [MAC(6B)] [ClientID(2B BE)] [Timestamp(4B BE)] [HMAC(4B BE)]
        if (cmdByte == RC_CMD_TIME_SYNC && mData.length() >= 19) {
            unsigned long nowMs = millis();
            if (BleHandler::_lastTimeSyncMs != 0 && (nowMs - BleHandler::_lastTimeSyncMs < 5000UL)) {
                BleHandler::_pendingResponseCode = RESP_ACK_TIME_OK;
                return;
            }

            uint16_t clientId = ((uint16_t)(uint8_t)mData[9] << 8) | (uint16_t)(uint8_t)mData[10];
            uint32_t ts = ((uint32_t)(uint8_t)mData[11] << 24) |
                          ((uint32_t)(uint8_t)mData[12] << 16) |
                          ((uint32_t)(uint8_t)mData[13] << 8)  |
                          (uint32_t)(uint8_t)mData[14];
            uint32_t receivedCode = ((uint32_t)(uint8_t)mData[15] << 24) |
                                    ((uint32_t)(uint8_t)mData[16] << 16) |
                                    ((uint32_t)(uint8_t)mData[17] << 8)  |
                                    (uint32_t)(uint8_t)mData[18];

            if (ts >= 1704067200UL && RollingCodeManager::verifyTimeSync(targetMac6, clientId, ts, receivedCode)) {
                Serial.println("Time Sync RTC verificato! Aggiornamento orologio...");
                BleHandler::_lastTimeSyncMs = nowMs;
                BleHandler::_pendingTimeSyncEpoch = (time_t)ts;
            } else {
                Serial.println("ERRORE: Firma Time Sync non valida.");
                BleHandler::_needsErrorFeedback = 2;
                BleHandler::_pendingResponseCode = RESP_NACK_ERR;
            }
            return;
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

    pinMode(HW_BOOT_PIN, INPUT_PULLUP);
#ifdef HW_EXTERNAL_BTN_PIN
    pinMode(HW_EXTERNAL_BTN_PIN, INPUT_PULLUP);
#endif

    BLEDevice::init("ApritiSedano");
    BLEAddress ownAddr = BLEDevice::getAddress();
    uint8_t* nativeMac = (uint8_t*)ownAddr.getNative();
    for (int i = 0; i < 6; i++) {
#if defined(CONFIG_NIMBLE_ENABLED)
        // Su NimBLE (es. XIAO ESP32-C6) getNative() restituisce i byte in ordine inverso (Little-Endian)
        _ownMac[i] = nativeMac[5 - i];
#else
        // Su Bluedroid (es. ESP32-WROOM32) getNative() restituisce i byte già in ordine Big-Endian
        _ownMac[i] = nativeMac[i];
#endif
    }

    Serial.printf("\n==================================\n");
    Serial.printf("MAC ADDRESS DISPOSITIVO: %s\n", ownAddr.toString().c_str());
    Serial.printf("==================================\n\n");
    Serial.printf("BLE Listening on Service UUID: %s\n", SERVICE_UUID);

    static BoxScanCallbacks scanCallbacks;
    pBLEScan = BLEDevice::getScan();
    pBLEScan->setAdvertisedDeviceCallbacks(&scanCallbacks, true);
    pBLEScan->setActiveScan(false);
    pBLEScan->setInterval(1000);
    pBLEScan->setWindow(300);

    pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->setMinInterval(0x00A0); // 100ms per rilevamento rapido stato / Pairing Mode
    pAdvertising->setMaxInterval(0x0100); // 160ms

    BLEAdvertisementData oAdvertisementData;
    oAdvertisementData.setFlags(0x06);
    oAdvertisementData.setCompleteServices(BLEUUID(SERVICE_UUID));
    pAdvertising->setAdvertisementData(oAdvertisementData);
    pAdvertising->setScanResponse(true);

    updateStateBeacon();
    pAdvertising->start();
    Serial.println("Started continuous BLE State Beacon");

    pBLEScan->start(0, nullptr, false);
}

void BleHandler::enterPairingMode() {
    _isPairingMode = true;
    _pairingStartTime = millis();
    _lastPairingBeepTime = millis();
    LedIndicator::setState(LED_PAIRING);
    BuzzerManager::playPairingTickSequence();
    _needsUpdateStateBeacon = true;
    Serial.println(">>> MODALITÀ ACCOPPIAMENTO ATTIVA PER 60 SECONDI <<<");
}

void BleHandler::triggerRelay() {
    digitalWrite(_relayPin, HW_RELAY_ON_STATE);
    _isRelayActive = true;
    _relayStartTime = millis();
}

void BleHandler::startAdvertisingResponse(const char* payloadStr) {
    _isAckActive = true;
    _ackStartTime = millis();

    static BLEAdvertisementData oScanResponseData;
    oScanResponseData = BLEAdvertisementData();

    const size_t payloadLen = strlen(payloadStr);
    char rawAdData[28];
    rawAdData[0] = (char)(payloadLen + 3);
    rawAdData[1] = (char)0xFF;
    rawAdData[2] = (char)0xE5;
    rawAdData[3] = (char)0x02;
    memcpy(&rawAdData[4], payloadStr, payloadLen);

    oScanResponseData.addData(rawAdData, 4 + payloadLen);
    pAdvertising->setScanResponseData(oScanResponseData);
    Serial.printf("Broadcasting BLE Response: %s\n", payloadStr);
}

void BleHandler::stopAdvertisingACK() {
    if (_isAckActive) {
        _isAckActive = false;
        updateStateBeacon();
    }
}

void BleHandler::updateStateBeacon() {
    if (_isAckActive || _isPaused) return;

    static BLEAdvertisementData oScanResponseData;
    oScanResponseData = BLEAdvertisementData();

    // 0x04 = Pairing Mode, 0x01 = Normal State
    uint8_t beaconType = _isPairingMode ? 0x04 : 0x01;
    uint8_t doorState = (digitalRead(_sensorPin) == HW_SENSOR_CLOSED_STATE) ? 0x00 : 0x01;

    time_t now;
    time(&now);
    uint32_t ts = (uint32_t)now;
    if (_isPairingMode) {
        unsigned long elapsedMs = millis() - _pairingStartTime;
        ts = (elapsedMs < 60000UL) ? (uint32_t)((60000UL - elapsedMs + 999UL) / 1000UL) : 0;
    }

    // Payload su stack: [Len: 1B] [0xFF] [0xE5 0x02] [Type: 1B] [State: 1B] [Timestamp: 4B BE] = 10 bytes
    char rawAdData[10];
    rawAdData[0] = (char)9;    // Length = 1 (0xFF) + 2 (Company ID) + 6 (payload)
    rawAdData[1] = (char)0xFF; // Manufacturer Specific Data
    rawAdData[2] = (char)0xE5; // Company ID Low
    rawAdData[3] = (char)0x02; // Company ID High
    rawAdData[4] = (char)beaconType;
    rawAdData[5] = (char)doorState;
    rawAdData[6] = (char)((ts >> 24) & 0xFF);
    rawAdData[7] = (char)((ts >> 16) & 0xFF);
    rawAdData[8] = (char)((ts >> 8) & 0xFF);
    rawAdData[9] = (char)(ts & 0xFF);

    oScanResponseData.addData(rawAdData, sizeof(rawAdData));
    pAdvertising->setScanResponseData(oScanResponseData);
}

void BleHandler::loop() {
    if (_isPaused) return;

    // Salva in NVS eventuali contatori o nuovi slot aggiornati
    RollingCodeManager::commitPendingNVS();

    // Gestione Pulsante Fisico (3s = Modalità Accoppiamento, 10s = Commutazione Antenna)
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
            _button3sTriggered = false;
            _button10sTriggered = false;
        } else {
            unsigned long heldMs = now - _buttonPressStartTime;
            if (!_button3sTriggered && heldMs >= 3000UL) {
                _button3sTriggered = true;
                enterPairingMode();
                Logger::logOperation("PAIR_MODE_BTN");
            }
            if (!_button10sTriggered && heldMs >= 10000UL) {
                _button10sTriggered = true;
                // Se l'utente continua a tenere premuto fino a 10s, annulla il pairing mode e commuta l'antenna
                _isPairingMode = false;
                _needsUpdateStateBeacon = true;
                LedIndicator::setState(LED_IDLE);
                Serial.println("Pulsante tenuto premuto per 10 secondi: Toggle Antenna!");
                AntennaState result = AntennaManager::toggle();
                if (result == ANTENNA_INTERNAL) {
                    BuzzerManager::playAntennaInternalSequence();
                    Logger::logOperation("ANT_INTERNA");
                } else if (result == ANTENNA_EXTERNAL) {
                    BuzzerManager::playAntennaExternalSequence();
                    Logger::logOperation("ANT_ESTERNA");
                } else {
                    BuzzerManager::playAntennaErrorSequence();
                    Logger::logOperation("ANT_ERR_UNSUPPORTED");
                }
            }
        }
    } else {
        if (_buttonIsDown) {
            unsigned long pressDuration = now - _buttonPressStartTime;
            _buttonIsDown = false;

            if (!_button3sTriggered && !_button10sTriggered && pressDuration > 50UL) {
                if (_isPairingMode) {
                    _isPairingMode = false;
                    LedIndicator::setState(LED_IDLE);
                    _needsUpdateStateBeacon = true;
                    Serial.println("Modalità accoppiamento annullata tramite pressione breve.");
                } else if (RollingCodeManager::hasAnyClient()) {
                    LedIndicator::setState(LED_HEARTBEAT);
                    Serial.println("Pulsante premuto: Heartbeat di diagnostica attivato.");
                }
            }
            _button3sTriggered = false;
            _button10sTriggered = false;
        }
    }

    // Gestione Modalità Accoppiamento: doppio beep ogni secondo e timeout a 60 secondi
    if (_isPairingMode) {
        if (now - _pairingStartTime >= 60000UL) {
            _isPairingMode = false;
            LedIndicator::setState(LED_IDLE);
            Serial.println("Timeout modalità Accoppiamento (60s) scaduto.");
            updateStateBeacon();
        } else if (now - _lastPairingBeepTime >= 1000UL) {
            _lastPairingBeepTime = now;
            BuzzerManager::playPairingTickSequence();
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

        LedIndicator::setState(LED_SUCCESS);
        BuzzerManager::playSuccessSequence();
        Logger::logOperation("TIME_SYNC");
        startAdvertisingResponse("ACK_TIME_OK");
    }

    if (_needsTriggerAction) {
        _needsTriggerAction = false;
        LedIndicator::setState(LED_SUCCESS);

        if (digitalRead(_sensorPin) == HW_SENSOR_CLOSED_STATE) {
            _lastTriggeredTargetState = 1;
            BuzzerManager::playOpenSequence();
            Logger::logOperation("APERTO");
        } else {
            _lastTriggeredTargetState = 0;
            BuzzerManager::playCloseSequence();
            Logger::logOperation("CHIUSO");
        }

        triggerRelay();
        startAdvertisingResponse((_lastTriggeredTargetState == 1) ? "ACK_OK_OPEN" : "ACK_OK_CLOSED");
    }

    if (_needsDuplicateAction) {
        _needsDuplicateAction = false;
        LedIndicator::setState(LED_DUPLICATE);
        startAdvertisingResponse((_lastTriggeredTargetState == 1) ? "ACK_OK_OPEN" : "ACK_OK_CLOSED");
    }

    if (_needsEnterPairingAction) {
        _needsEnterPairingAction = false;
        enterPairingMode();
        Logger::logOperation("PAIR_MODE_REMOTO");
        startAdvertisingResponse("ACK_PAIR_MODE");
    }

    if (_needsPairedSuccessAction) {
        _needsPairedSuccessAction = false;
        _isPairingMode = false;
        LedIndicator::setState(LED_SUCCESS);
        BuzzerManager::playPairingCompleteSequence();
        Logger::logOperation("NUOVO_DISP_ASSOC");
        startAdvertisingResponse("ACK_PAIRED");
    }

    if (_needsErrorFeedback != 0) {
        uint8_t errType = _needsErrorFeedback;
        _needsErrorFeedback = 0;
        LedIndicator::setState(LED_ERROR);
        if (errType == 2) {
            BuzzerManager::playErrorSequence();
        }
    }

    if (_pendingResponseCode != RESP_NONE) {
        uint8_t resp = _pendingResponseCode;
        _pendingResponseCode = RESP_NONE;
        switch (resp) {
            case RESP_ACK_OPEN:      startAdvertisingResponse("ACK_OK_OPEN"); break;
            case RESP_ACK_CLOSED:    startAdvertisingResponse("ACK_OK_CLOSED"); break;
            case RESP_ACK_PAIRED:    startAdvertisingResponse("ACK_PAIRED"); break;
            case RESP_ACK_PAIR_MODE: startAdvertisingResponse("ACK_PAIR_MODE"); break;
            case RESP_ACK_TIME_OK:   startAdvertisingResponse("ACK_TIME_OK"); break;
            case RESP_NACK_USED:
                Logger::logOperation("REPLAY_BLOCCATO");
                startAdvertisingResponse("NACK_USED");
                break;
            case RESP_NACK_ERR:
                startAdvertisingResponse("NACK_ERR");
                break;
        }
    }

    if (_needsUpdateStateBeacon) {
        _needsUpdateStateBeacon = false;
        updateStateBeacon();
    }

    // Sensor state change detection
    if (_sensorPin != -1) {
        int currentSensorState = digitalRead(_sensorPin);
        if (currentSensorState != _lastSensorState) {
            _lastSensorState = currentSensorState;
            if (currentSensorState == HW_SENSOR_CLOSED_STATE) {
                Serial.println(">>> [DEBUG] SENSOR STATE CHANGED: CLOSED");
            } else {
                Serial.println(">>> [DEBUG] SENSOR STATE CHANGED: OPEN");
            }
            updateStateBeacon();
        }
    }

    if (_isRelayActive) {
        if (millis() - _relayStartTime >= 1000UL) {
            digitalWrite(_relayPin, HW_RELAY_OFF_STATE);
            _isRelayActive = false;
        }
    }

    if (_isAckActive) {
        if (millis() - _ackStartTime >= 3000UL) {
            stopAdvertisingACK();
        }
    } else {
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
    updateStateBeacon();
    if (pBLEScan) {
        pBLEScan->start(0, nullptr, false);
    }
    if (pAdvertising) {
        pAdvertising->start();
    }
}
