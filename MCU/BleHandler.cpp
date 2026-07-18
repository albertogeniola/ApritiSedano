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

int BleHandler::_relayPin = -1;
int BleHandler::_sensorPin = -1;
int BleHandler::_lastSensorState = -1;
bool BleHandler::_isPaused = false;
uint32_t BleHandler::_lastValidCode = 0;
unsigned long BleHandler::_lastValidTime = 0;

bool BleHandler::_isTimeInvalid = false;
bool BleHandler::_isTimeSyncMode = false;
unsigned long BleHandler::_timeSyncEndTime = 0;

bool BleHandler::_isAckActive = false;
unsigned long BleHandler::_ackEndTime = 0;
unsigned long BleHandler::_lastBeaconTime = 0;
bool BleHandler::_isRelayActive = false;
unsigned long BleHandler::_relayEndTime = 0;

const char* BleHandler::SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0";

BLEScan* pBLEScan = nullptr;
BLEAdvertising* pAdvertising = nullptr;

class BoxScanCallbacks : public BLEAdvertisedDeviceCallbacks {
    void onResult(BLEAdvertisedDevice advertisedDevice) {
        if (BleHandler::_isPaused) return;

        if (advertisedDevice.haveManufacturerData() && 
            advertisedDevice.isAdvertisingService(BLEUUID(BleHandler::SERVICE_UUID))) {
            String mData = advertisedDevice.getManufacturerData();
            // L'app Android invia Manufacturer Data con ID 0x02E5 e payload ASCII (es. "123456")
            if (mData.length() >= 8) { 
                uint8_t idLow = (uint8_t)mData[0];
                uint8_t idHigh = (uint8_t)mData[1];
                if (idLow == 0xE5 && idHigh == 0x02) {
                    if (mData.length() >= 11 && (uint8_t)mData[2] == 0xFF) {
                        Serial.println("---- TIME SYNC PAYLOAD RICEVUTO ----");
                        if (!BleHandler::_isTimeSyncMode) {
                            Serial.println("Rifiutato: Sistema non in TIME_SYNC_MODE.");
                            return;
                        }
                        
                        uint32_t ts = ((uint8_t)mData[3] << 24) | ((uint8_t)mData[4] << 16) | ((uint8_t)mData[5] << 8) | (uint8_t)mData[6];
                        uint8_t hmac[4];
                        hmac[0] = (uint8_t)mData[7];
                        hmac[1] = (uint8_t)mData[8];
                        hmac[2] = (uint8_t)mData[9];
                        hmac[3] = (uint8_t)mData[10];

                        if (TotpValidator::verifyTimeSync(ts, hmac)) {
                            Serial.println("Time Sync verificato! Aggiorno orologio...");
                            RTCManager::setRTC((time_t)ts);
                            struct timeval tv;
                            tv.tv_sec = ts;
                            tv.tv_usec = 0;
                            settimeofday(&tv, NULL);
                            
                            BleHandler::_isTimeInvalid = false;
                            BleHandler::_isTimeSyncMode = false;
                            BuzzerManager::playSuccessSequence();
                            BleHandler::updateStateBeacon();
                        } else {
                            Serial.println("Firma Time Sync non valida.");
                            BuzzerManager::playErrorSequence();
                        }
                    } else if (mData.length() >= 8) {
                        Serial.println("---- PACCHETTO BLE (Manufacturer Data 0x02E5) RICEVUTO ----");
                        if (BleHandler::_isTimeInvalid && !BleHandler::_isTimeSyncMode) {
                            Serial.println("ERRORE: Orario non valido (RTC scarico). Operazione bloccata.");
                            BuzzerManager::playErrorSequence();
                            BleHandler::startAdvertisingNACK();
                            return;
                        }
                        
                        // Estrarre la stringa ASCII che rappresenta il TOTP
                        String totpStr = mData.substring(2, 8); // Prende i successivi 6 caratteri
                        uint32_t totpCode = totpStr.toInt();
                        Serial.printf("TOTP estratto dal pacchetto: %u (String: %s)\n", totpCode, totpStr.c_str());

                        if (TotpValidator::validate(totpCode, 2)) {
                            unsigned long nowMs = millis();
                            
                            // Check idempotency (ignore if same code within 60 seconds)
                            if (totpCode == BleHandler::_lastValidCode && (nowMs - BleHandler::_lastValidTime < 60000)) {
                                Serial.println("Valid TOTP received, but it's a duplicate. Resetting ACK timer.");
                                BleHandler::startAdvertisingACK();
                                return;
                            }

                            Serial.println(">>> Valid NEW TOTP received! Triggering Relay. <<<");
                            LedIndicator::setState(LED_SUCCESS); // Segnale Visivo LED Acceso fisso
                            BleHandler::_lastValidCode = totpCode;
                            BleHandler::_lastValidTime = nowMs;
                            
                            // Decide which beep sequence to play based on the current state BEFORE the relay triggers
                            if (digitalRead(BleHandler::_sensorPin) == LOW) {
                                BuzzerManager::playOpenSequence();
                                Logger::logOperation("APERTO");
                            } else {
                                BuzzerManager::playCloseSequence();
                                Logger::logOperation("CHIUSO");
                            }

                            BleHandler::triggerRelay();
                            BleHandler::startAdvertisingACK();
                        } else {
                            LedIndicator::setState(LED_ERROR); // Segnale Visivo LED errore
                            Serial.println("ERRORE: Validazione TOTP fallita per questo pacchetto.");
                            time_t now;
                            time(&now);
                            Serial.printf("TOTP Atteso attualmente dal sistema: %u\n", TotpValidator::generateForTime(now));
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
    digitalWrite(_relayPin, LOW);
    
    pinMode(_sensorPin, INPUT_PULLUP);
    _lastSensorState = digitalRead(_sensorPin);

    // Configura il pulsante BOOT con interrupt
    pinMode(HW_BOOT_PIN, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(HW_BOOT_PIN), BleHandler::handleButtonPress, FALLING);

    // Controllo validità orario
    time_t now;
    time(&now);
    if (now < 1704067200) { // Gen 1 2024
        _isTimeInvalid = true;
        Serial.println("ATTENZIONE: Orario non valido (RTC scarico). Operazioni inibite.");
    }

    BLEDevice::init("ApritiSedano");
    
    Serial.printf("BLE Listening on Service UUID: %s\n", SERVICE_UUID);

    // Setup Scanner
    pBLEScan = BLEDevice::getScan();
    pBLEScan->setAdvertisedDeviceCallbacks(new BoxScanCallbacks(), true); // true = wants duplicates
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

void BleHandler::handleButtonPress() {
    if (_isTimeInvalid && !_isTimeSyncMode) {
        _isTimeSyncMode = true;
        _timeSyncEndTime = millis() + 300000; // 5 minuti
        Serial.println("Pulsante premuto. Entro in modalità sincronizzazione orario per 5 minuti.");
        updateStateBeacon();
    }
}

void BleHandler::triggerRelay() {
    digitalWrite(_relayPin, HIGH);  // HIGH attiva il transistor -> porta a GND l'IN del relè -> scatta
    _isRelayActive = true;
    _relayEndTime = millis() + 500; // 500ms impulse
}

void BleHandler::startAdvertisingNACK() {
    _isAckActive = true;
    _ackEndTime = millis() + 3000; // 3 seconds NACK
    
    static BLEAdvertisementData oScanResponseData;
    oScanResponseData = BLEAdvertisementData();

    String ackPayload = "NACK_TIME_ERR";
    
    std::string rawAdData = "";
    rawAdData += (char)(ackPayload.length() + 3);
    rawAdData += (char)0xFF;
    rawAdData += (char)0xE5;
    rawAdData += (char)0x02;
    rawAdData += ackPayload.c_str();
    
    oScanResponseData.addData((char*)rawAdData.data(), rawAdData.length());

    pAdvertising->setScanResponseData(oScanResponseData);
    Serial.println("Temporarily broadcasting NACK payload");
}

void BleHandler::startAdvertisingACK() {
    _isAckActive = true;
    _ackEndTime = millis() + 3000; // 3 seconds ACK
    
    // Read sensor state
    uint8_t doorState = digitalRead(_sensorPin) == LOW ? 0 : 1; // 0 closed, 1 open

    static BLEAdvertisementData oScanResponseData;
    oScanResponseData = BLEAdvertisementData();

    String ackPayload = (doorState == 1) ? "ACK_OK_OPEN" : "ACK_OK_CLOSED";
    
    std::string rawAdData = "";
    rawAdData += (char)(ackPayload.length() + 3); // 1 byte per la Lunghezza totale
    rawAdData += (char)0xFF;                      // 1 byte per il Tipo: Manufacturer Specific Data
    rawAdData += (char)0xE5;                      // 1 byte ID Company Low
    rawAdData += (char)0x02;                      // 1 byte ID Company High
    rawAdData += ackPayload.c_str();              // Payload
    
    oScanResponseData.addData((char*)rawAdData.data(), rawAdData.length());

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

    uint8_t payload[10];
    
    // Byte 0: Identificatore Tipo Beacon
    if (_isTimeSyncMode) {
        payload[0] = 0x03;
    } else if (_isTimeInvalid) {
        payload[0] = 0x02;
    } else {
        payload[0] = 0x01;
    }
    
    // Byte 1: Stato Sensore
    payload[1] = (digitalRead(_sensorPin) == LOW) ? 0x00 : 0x01;
    
    time_t now;
    time(&now);
    uint32_t ts = (uint32_t)now;

    uint8_t hmac[4];
    TotpValidator::signStateBeacon(payload[1], ts, hmac);

    // Payload: [Type: 1 byte] [State: 1 byte] [Timestamp: 4 bytes BE] [HMAC: 4 bytes] = 10 bytes
    std::string rawAdData = "";
    rawAdData += (char)13; // Length = 1 + 2 + 10 = 13 bytes
    rawAdData += (char)0xFF; // Manufacturer Specific Data
    rawAdData += (char)0xE5; // Company ID Low
    rawAdData += (char)0x02; // Company ID High
    rawAdData += (char)payload[0]; // Payload Type (0x01 = State, 0x02 = Invalid, 0x03 = Sync)
    rawAdData += (char)payload[1]; // State
    rawAdData += (char)((ts >> 24) & 0xFF);
    rawAdData += (char)((ts >> 16) & 0xFF);
    rawAdData += (char)((ts >> 8) & 0xFF);
    rawAdData += (char)(ts & 0xFF);
    rawAdData += (char)hmac[0];
    rawAdData += (char)hmac[1];
    rawAdData += (char)hmac[2];
    rawAdData += (char)hmac[3];

    oScanResponseData.addData((char*)rawAdData.data(), rawAdData.length());
    pAdvertising->setScanResponseData(oScanResponseData);
}

void BleHandler::loop() {
    if (_isPaused) return;

    if (_isTimeSyncMode && millis() > _timeSyncEndTime) {
        _isTimeSyncMode = false;
        Serial.println("Timeout modalità Time Sync scaduto.");
        updateStateBeacon();
    }

    // --- DEBUG: Sensor state change detection ---
    if (_sensorPin != -1) {
        int currentSensorState = digitalRead(_sensorPin);
        if (currentSensorState != _lastSensorState) {
            _lastSensorState = currentSensorState;
            
            if (currentSensorState == LOW) {
                // CLOSED
                Serial.println(">>> [DEBUG] SENSOR STATE CHANGED: CLOSED (LOW)");
            } else {
                // OPEN
                Serial.println(">>> [DEBUG] SENSOR STATE CHANGED: OPEN (HIGH)");
            }
            // Aggiorna subito il beacon quando cambia lo stato, a meno che non siamo in ACK
            updateStateBeacon();
        }
    }
    // --------------------------------------------

    if (_isRelayActive) {
        if (millis() > _relayEndTime) {
            digitalWrite(_relayPin, LOW); // Torna LOW per spegnere il transistor e il relè
            _isRelayActive = false;
        }
    }

    if (_isAckActive) {
        if (millis() > _ackEndTime) {
            stopAdvertisingACK();
        }
    } else {
        // Update beacon every second
        if (millis() - _lastBeaconTime > 1000) {
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
    }
    if (pAdvertising) {
        pAdvertising->stop();
    }
}

void BleHandler::resume() {
    _isPaused = false;
    if (pBLEScan) {
        pBLEScan->start(0, nullptr, false);
    }
    if (pAdvertising) {
        pAdvertising->start();
    }
}
