#include "BleHandler.h"
#include "RollingCodeManager.h"
#include "OtaManager.h"
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
volatile bool BleHandler::_identifyOnlyMode = false;

unsigned long BleHandler::_lastTimeSyncMs = 0;
bool BleHandler::_isPairingMode = false;
unsigned long BleHandler::_pairingStartTime = 0;
unsigned long BleHandler::_lastPairingBeepTime = 0;

bool BleHandler::_buttonIsDown = false;
bool BleHandler::_buttonReleasePending = false;
uint8_t BleHandler::_buttonPinMask = 0;
uint8_t BleHandler::_buttonLastProgressSec = 0;
uint16_t BleHandler::_buttonDropoutCount = 0;
unsigned long BleHandler::_buttonPressStartTime = 0;
unsigned long BleHandler::_buttonLastLowTime = 0;
unsigned long BleHandler::_buttonLastSampleMs = 0;
unsigned long BleHandler::_buttonAccumulatedLowMs = 0;
unsigned long BleHandler::_buttonReleaseStartTime = 0;
unsigned long BleHandler::_buttonRecoveryStartMs = 0;
bool BleHandler::_button3sTriggered = false;
bool BleHandler::_button10sTriggered = false;

volatile bool BleHandler::_needsUpdateStateBeacon = false;
volatile bool BleHandler::_needsTriggerAction = false;
volatile bool BleHandler::_needsDuplicateAction = false;
volatile bool BleHandler::_needsEnterPairingAction = false;
volatile bool BleHandler::_needsEnterOtaAction = false;
volatile uint32_t BleHandler::_pendingOtaCounter = 0;
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

bool BleHandler::isBusy() {
    return _isRelayActive || _isAckActive || _isPairingMode || _buttonIsDown ||
           _needsTriggerAction || _needsDuplicateAction || _needsEnterPairingAction ||
           _needsEnterOtaAction || _needsPairedSuccessAction || (_pendingTimeSyncEpoch != 0) ||
           OtaManager::isActive();
}

void BleHandler::setAdvertisingConnectable(bool connectable) {
    if (!pAdvertising) return;
    pAdvertising->stop();
#if defined(CONFIG_NIMBLE_ENABLED)
    // Su NimBLE: BLE_GAP_CONN_MODE_NON = scannable ma rigorosamente NON-connectable
    pAdvertising->setAdvertisementType(connectable ? BLE_GAP_CONN_MODE_UND : BLE_GAP_CONN_MODE_NON);
#else
    // Su Bluedroid: ADV_TYPE_SCAN_IND = scannable ma rigorosamente NON-connectable
    pAdvertising->setAdvertisementType(connectable ? ADV_TYPE_IND : ADV_TYPE_SCAN_IND);
#endif
}

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

        if (BleHandler::_isPaused || OtaManager::isActive()) return;
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

        // 1. Comandi Rolling-Code autenticati:
        //    - 0x01: Trigger Apertura/Chiusura
        //    - 0x02: Attivazione Modalità Accoppiamento da Remoto
        //    - 0x03: Attivazione Modalità Aggiornamento OTA (riservato esclusivamente al Master)
        // Layout (19 byte): [0xE5, 0x02] [cmd(1B)] [MAC(6B)] [ClientID(2B BE)] [Counter(4B BE)] [RollingCode(4B BE)]
        if ((cmdByte == RC_CMD_TRIGGER || cmdByte == RC_CMD_ENTER_PAIRING || cmdByte == RC_CMD_ENTER_OTA) && mData.length() >= 19) {
            uint16_t clientId = ((uint16_t)(uint8_t)mData[9] << 8) | (uint16_t)(uint8_t)mData[10];
            uint32_t counter = ((uint32_t)(uint8_t)mData[11] << 24) |
                               ((uint32_t)(uint8_t)mData[12] << 16) |
                               ((uint32_t)(uint8_t)mData[13] << 8)  |
                               (uint32_t)(uint8_t)mData[14];
            uint32_t receivedCode = ((uint32_t)(uint8_t)mData[15] << 24) |
                                    ((uint32_t)(uint8_t)mData[16] << 16) |
                                    ((uint32_t)(uint8_t)mData[17] << 8)  |
                                    (uint32_t)(uint8_t)mData[18];

            // In modalità identificazione Serial CLI consentiamo solo 0x01 (con relè bloccato)
            if (BleHandler::_identifyOnlyMode && cmdByte != RC_CMD_TRIGGER) {
                BleHandler::_pendingResponseCode = RESP_NACK_ERR;
                return;
            }

            RollingValidationResult res = RollingCodeManager::validateCommand(
                cmdByte, targetMac6, clientId, counter, receivedCode
            );

            if (res == RC_VALID_NEW) {
                if (cmdByte == RC_CMD_TRIGGER) {
                    if (BleHandler::_identifyOnlyMode) {
                        RollingCodeManager::recordClientEvent(clientId, "TEST_ID (Rele OFF)");
                    } else {
                        Serial.printf(">>> Rolling Code VALIDO (Client 0x%04X, Counter %lu) -> Azionamento Relè <<<\n",
                                      clientId, (unsigned long)counter);
                        RollingCodeManager::recordClientEvent(clientId, "APRI/CHIUDI");
                    }
                    BleHandler::_needsTriggerAction = true;
                } else if (cmdByte == RC_CMD_ENTER_PAIRING) {
                    Serial.printf(">>> Richiesta Modalità Accoppiamento VALIDA (Client 0x%04X, Counter %lu) <<<\n",
                                  clientId, (unsigned long)counter);
                    RollingCodeManager::recordClientEvent(clientId, "PAIR_MODE");
                    BleHandler::_needsEnterPairingAction = true;
                } else if (cmdByte == RC_CMD_ENTER_OTA) {
                    if (RollingCodeManager::isMasterClient(clientId)) {
                        Serial.printf(">>> Richiesta OTA AUTORIZZATA da MASTER (Client 0x%04X, Counter %lu) <<<\n",
                                      clientId, (unsigned long)counter);
                        RollingCodeManager::recordClientEvent(clientId, "AVVIO_OTA");
                        BleHandler::_pendingOtaCounter = counter;
                        BleHandler::_needsEnterOtaAction = true;
                    } else {
                        Serial.printf("ERRORE SICUREZZA: Client 0x%04X ha richiesto OTA ma NON è configurato come MASTER!\n",
                                      clientId);
                        RollingCodeManager::recordClientEvent(clientId, "OTA_NEGATO");
                        BleHandler::_needsErrorFeedback = 2;
                        BleHandler::_pendingResponseCode = RESP_NACK_NOT_MASTER;
                    }
                }
            } else if (res == RC_VALID_DUPLICATE) {
                if (cmdByte == RC_CMD_TRIGGER) {
                    BleHandler::_needsDuplicateAction = true;
                } else if (cmdByte == RC_CMD_ENTER_PAIRING) {
                    BleHandler::_pendingResponseCode = RESP_ACK_PAIR_MODE;
                } else if (cmdByte == RC_CMD_ENTER_OTA) {
                    if (RollingCodeManager::isMasterClient(clientId)) {
                        BleHandler::_pendingResponseCode = RESP_ACK_OTA_READY;
                    } else {
                        BleHandler::_pendingResponseCode = RESP_NACK_NOT_MASTER;
                    }
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
            if (BleHandler::_identifyOnlyMode) {
                BleHandler::_pendingResponseCode = RESP_NACK_ERR;
                return;
            }

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
                if (!BleHandler::_identifyOnlyMode) {
                    Serial.println("Time Sync RTC verificato! Aggiornamento orologio...");
                }
                RollingCodeManager::recordClientEvent(clientId, "SYNC_RTC");
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

    // Inizializza la tabella GATT per OTA (inattiva e irraggiungibile finché l'advertising resta non-connectable)
    OtaManager::init(_ownMac);

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
    setAdvertisingConnectable(false);     // Funzionamento normale 100% connectionless!

    BLEAdvertisementData oAdvertisementData;
    oAdvertisementData.setFlags(0x06);
    oAdvertisementData.setCompleteServices(BLEUUID(SERVICE_UUID));
    pAdvertising->setAdvertisementData(oAdvertisementData);
    pAdvertising->setScanResponse(true);

    updateStateBeacon();
    pAdvertising->start();
    Serial.println("Started continuous BLE State Beacon (Non-Connectable)");

    pBLEScan->start(0, nullptr, false);
}

void BleHandler::enterPairingMode() {
    if (OtaManager::isActive()) return;
    _isPairingMode = true;
    unsigned long startMs = millis();
    _pairingStartTime = startMs;
    _lastPairingBeepTime = startMs;
    LedIndicator::setState(LED_PAIRING);
    BuzzerManager::playPairingTickSequence();
    _needsUpdateStateBeacon = true;
    Serial.println(">>> MODALITÀ ACCOPPIAMENTO ATTIVA PER 60 SECONDI <<<");
}

void BleHandler::onOtaSessionEnded() {
    _isAckActive = false;
    setAdvertisingConnectable(false);

    if (!_isPaused) {
        BLEAdvertisementData oAdvertisementData;
        oAdvertisementData.setFlags(0x06);
        oAdvertisementData.setCompleteServices(BLEUUID(SERVICE_UUID));
        pAdvertising->setAdvertisementData(oAdvertisementData);
        pAdvertising->setScanResponse(true);

        updateStateBeacon();
        pAdvertising->start();
        if (pBLEScan) {
            pBLEScan->start(0, nullptr, false);
        }
        Serial.println("BLE: Ripristinata modalità connectionless normale dopo sessione OTA.");
    }
}

void BleHandler::triggerRelay() {
    // Interblocco di sicurezza hardware/software: il relè non può MAI scattare in modalità identificazione CLI
    if (_identifyOnlyMode) {
        return;
    }
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
    if (!_identifyOnlyMode) {
        Serial.printf("Broadcasting BLE Response: %s\n", payloadStr);
    }
}

void BleHandler::stopAdvertisingACK() {
    if (_isAckActive) {
        _isAckActive = false;
        if (!OtaManager::isClientConnected()) {
            updateStateBeacon();
        }
    }
}

void BleHandler::updateStateBeacon() {
    if (_isAckActive || _isPaused || OtaManager::isClientConnected()) return;

    static BLEAdvertisementData oScanResponseData;
    oScanResponseData = BLEAdvertisementData();

    // 0x05 = OTA Waiting Mode, 0x04 = Pairing Mode, 0x01 = Normal State
    uint8_t beaconType = OtaManager::isActive() ? 0x05 : (_isPairingMode ? 0x04 : 0x01);
    uint8_t doorState = (digitalRead(_sensorPin) == HW_SENSOR_CLOSED_STATE) ? 0x00 : 0x01;

    time_t now;
    time(&now);
    uint32_t ts = (uint32_t)now;
    if (OtaManager::isActive()) {
        ts = OtaManager::getRemainingWaitSeconds();
    } else if (_isPairingMode) {
        unsigned long elapsedMs = millis() - _pairingStartTime;
        ts = (elapsedMs < 60000UL) ? (uint32_t)((60000UL - elapsedMs + 999UL) / 1000UL) : 0;
    }

    // Tag crittografico a 32 bit per consentire solo alla Master App di mostrare la voce OTA nel menu
    uint32_t masterTag = RollingCodeManager::computeMasterTag(_ownMac);

    // Stato Antenna attuale (0 = Interna, 1 = Esterna, 2 = Non supportata)
    uint8_t antByte = (AntennaManager::getState() == ANTENNA_EXTERNAL) ? 0x01 :
                      ((AntennaManager::getState() == ANTENNA_INTERNAL) ? 0x00 : 0x02);

    // Payload su stack (15 byte totali <= 31B limite BLE Scan Response):
    // [Len: 1B] [0xFF] [0xE5 0x02] [Type: 1B] [State: 1B] [Timestamp/RemSec: 4B BE] [MasterTag: 4B BE] [Antenna: 1B]
    char rawAdData[15];
    rawAdData[0]  = (char)14;   // Length = 1 (0xFF) + 2 (Company ID) + 11 (payload)
    rawAdData[1]  = (char)0xFF; // Manufacturer Specific Data
    rawAdData[2]  = (char)0xE5; // Company ID Low
    rawAdData[3]  = (char)0x02; // Company ID High
    rawAdData[4]  = (char)beaconType;
    rawAdData[5]  = (char)doorState;
    rawAdData[6]  = (char)((ts >> 24) & 0xFF);
    rawAdData[7]  = (char)((ts >> 16) & 0xFF);
    rawAdData[8]  = (char)((ts >> 8) & 0xFF);
    rawAdData[9]  = (char)(ts & 0xFF);
    rawAdData[10] = (char)((masterTag >> 24) & 0xFF);
    rawAdData[11] = (char)((masterTag >> 16) & 0xFF);
    rawAdData[12] = (char)((masterTag >> 8) & 0xFF);
    rawAdData[13] = (char)(masterTag & 0xFF);
    rawAdData[14] = (char)antByte;

    oScanResponseData.addData(rawAdData, sizeof(rawAdData));
    pAdvertising->setScanResponseData(oScanResponseData);
}

// Campionamento multiplo rapido (~80 us) con identificazione separata dei pin BOOT ed EXTERNAL.
// Bit 0 (0x01) = HW_BOOT_PIN premuto (LOW), Bit 1 (0x02) = HW_EXTERNAL_BTN_PIN premuto (LOW).
static uint8_t readPhysicalButtonMask() {
    int bootLow = 0;
    int extLow = 0;
    for (int i = 0; i < 5; i++) {
        if (digitalRead(HW_BOOT_PIN) == LOW) bootLow++;
#ifdef HW_EXTERNAL_BTN_PIN
        if (digitalRead(HW_EXTERNAL_BTN_PIN) == LOW) extLow++;
#endif
        if (i < 4) {
            delayMicroseconds(20);
        }
    }
    uint8_t mask = 0;
    if (bootLow >= 3) mask |= 0x01;
    if (extLow >= 3)  mask |= 0x02;
    return mask;
}

static void formatButtonPinLabel(uint8_t mask, char* outBuf, size_t outSize) {
#ifdef HW_EXTERNAL_BTN_PIN
    if ((mask & 0x03) == 0x03) {
        snprintf(outBuf, outSize, "BOOT GPIO %d + EXT GPIO %d", (int)HW_BOOT_PIN, (int)HW_EXTERNAL_BTN_PIN);
    } else if (mask & 0x02) {
        snprintf(outBuf, outSize, "EXT GPIO %d", (int)HW_EXTERNAL_BTN_PIN);
    } else {
        snprintf(outBuf, outSize, "BOOT GPIO %d", (int)HW_BOOT_PIN);
    }
#else
    (void)mask;
    snprintf(outBuf, outSize, "BOOT GPIO %d", (int)HW_BOOT_PIN);
#endif
}

void BleHandler::loop() {
    if (_isPaused) return;

    // Salva in NVS eventuali contatori o nuovi slot aggiornati
    RollingCodeManager::commitPendingNVS();

    // Gestisce eventuale sessione BLE GATT OTA attiva
    if (OtaManager::isActive()) {
        OtaManager::loop();
        // Finché il client non si è ancora connesso via GATT, manteniamo attivo il timeout dell'ACK "ACK_OTA_READY"
        // e il beacon 0x05; una volta connesso, non tocchiamo più l'advertising.
        if (OtaManager::isActive() && !OtaManager::isClientConnected()) {
            if (_isAckActive) {
                if (millis() - _ackStartTime >= 3000UL) {
                    stopAdvertisingACK();
                }
            } else if (millis() - _lastBeaconTime >= 1000UL) {
                _lastBeaconTime = millis();
                updateStateBeacon();
            }
        }
        return;
    }

    unsigned long now = millis();

    // Gestione Pulsante Fisico (disabilitata durante la modalità identificazione CLI).
    // Tollerante ai micro-distacchi meccanici fino a 950 ms tipici dei microswitch SMD/breadboard
    // ossidati o con contatto intermittente durante la pressione prolungata (3s pairing / 10s antenna).
    if (!_identifyOnlyMode) {
        static const unsigned long BTN_PRESS_MIN_MS          = 50UL;
        static const unsigned long BTN_RECOVERY_MIN_MS       = 20UL;
        static const unsigned long BTN_RELEASE_DEBOUNCE_MS   = 950UL;
        static const unsigned long BTN_SHORT_PRESS_MAX_MS    = 1200UL;
        static const unsigned long BTN_HOLD_3S_WALL_MS       = 3000UL;
        static const unsigned long BTN_HOLD_3S_MIN_LOW_MS    = 750UL;
        static const unsigned long BTN_HOLD_10S_WALL_MS      = 10000UL;
        static const unsigned long BTN_HOLD_10S_MIN_LOW_MS   = 2500UL;
        static const unsigned long BTN_PAIR_CANCEL_GUARD_MS  = 2500UL;

        uint8_t btnMask = readPhysicalButtonMask();

        if (!_buttonIsDown) {
            _buttonLastSampleMs = now;
            if (btnMask != 0) {
                _buttonIsDown = true;
                _buttonPinMask = btnMask;
                _buttonPressStartTime = now;
                _buttonLastLowTime = now;
                _buttonAccumulatedLowMs = 10UL;
                _buttonReleasePending = false;
                _buttonReleaseStartTime = 0;
                _buttonRecoveryStartMs = 0;
                _buttonDropoutCount = 0;
                _buttonLastProgressSec = 0;
                _button3sTriggered = false;
                _button10sTriggered = false;
            }
        } else {
            unsigned long dt = now - _buttonLastSampleMs;
            if (dt > 100UL) dt = 100UL;
            _buttonLastSampleMs = now;

            if (btnMask != 0) {
                _buttonPinMask |= btnMask;
                if (_buttonReleasePending) {
                    if (_buttonRecoveryStartMs == 0) {
                        _buttonRecoveryStartMs = now;
                    } else if (now - _buttonRecoveryStartMs >= BTN_RECOVERY_MIN_MS) {
                        _buttonReleasePending = false;
                        _buttonAccumulatedLowMs += (now - _buttonRecoveryStartMs);
                        _buttonRecoveryStartMs = 0;
                        _buttonLastLowTime = now;
                        _buttonDropoutCount++;
                    }
                } else {
                    _buttonAccumulatedLowMs += dt;
                    _buttonLastLowTime = now;
                }

                if (!_buttonReleasePending) {
                    unsigned long wallHeldMs = now - _buttonPressStartTime;

                    if (!_button3sTriggered && _buttonAccumulatedLowMs >= 250UL) {
                        if (_buttonLastProgressSec < 1 && wallHeldMs >= 1000UL && wallHeldMs < BTN_HOLD_3S_WALL_MS) {
                            _buttonLastProgressSec = 1;
                            char pinLabel[40];
                            formatButtonPinLabel(_buttonPinMask, pinLabel, sizeof(pinLabel));
                            Serial.printf("[Pulsante %s] Pressione mantenuta: 1s / 3s (contatto LOW: %lu ms, micro-distacchi: %u)...\n",
                                          pinLabel, _buttonAccumulatedLowMs, (unsigned int)_buttonDropoutCount);
                        } else if (_buttonLastProgressSec < 2 && wallHeldMs >= 2000UL && wallHeldMs < BTN_HOLD_3S_WALL_MS) {
                            _buttonLastProgressSec = 2;
                            char pinLabel[40];
                            formatButtonPinLabel(_buttonPinMask, pinLabel, sizeof(pinLabel));
                            Serial.printf("[Pulsante %s] Pressione mantenuta: 2s / 3s (contatto LOW: %lu ms, micro-distacchi: %u)...\n",
                                          pinLabel, _buttonAccumulatedLowMs, (unsigned int)_buttonDropoutCount);
                        }
                    }

                    if (!_button3sTriggered &&
                        wallHeldMs >= BTN_HOLD_3S_WALL_MS &&
                        _buttonAccumulatedLowMs >= BTN_HOLD_3S_MIN_LOW_MS) {
                        _button3sTriggered = true;
                        char pinLabel[40];
                        formatButtonPinLabel(_buttonPinMask, pinLabel, sizeof(pinLabel));
                        Serial.printf("[Pulsante %s] Soglia 3s raggiunta (durata: %lu ms, contatto LOW: %lu ms, micro-distacchi: %u).\n",
                                      pinLabel, wallHeldMs, _buttonAccumulatedLowMs, (unsigned int)_buttonDropoutCount);
                        enterPairingMode();
                        Logger::logOperation("PAIR_MODE_BTN");
                    }

                    if (!_button10sTriggered &&
                        wallHeldMs >= BTN_HOLD_10S_WALL_MS &&
                        _buttonAccumulatedLowMs >= BTN_HOLD_10S_MIN_LOW_MS) {
                        _button10sTriggered = true;
                        if (AntennaManager::isSupported()) {
                            // Su schede con switch RF (es. XIAO ESP32-C6), 10s annulla il pairing e commuta l'antenna
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
                            }
                        } else {
                            Serial.println("Pulsante tenuto premuto per 10 secondi: antenna esterna non supportata su questa scheda (Pairing Mode mantenuto attivo).");
                            BuzzerManager::playAntennaErrorSequence();
                            Logger::logOperation("ANT_ERR_UNSUPPORTED");
                        }
                    }
                }
            } else {
                _buttonRecoveryStartMs = 0;
                if (!_buttonReleasePending) {
                    _buttonReleasePending = true;
                    _buttonReleaseStartTime = now;
                } else if (now - _buttonReleaseStartTime >= BTN_RELEASE_DEBOUNCE_MS) {
                    unsigned long effectiveSpanMs = (_buttonLastLowTime >= _buttonPressStartTime)
                                                    ? (_buttonLastLowTime - _buttonPressStartTime)
                                                    : 0UL;
                    unsigned long totalLowMs = _buttonAccumulatedLowMs;
                    uint8_t usedMask = _buttonPinMask;
                    uint16_t dropouts = _buttonDropoutCount;
                    bool was3s = _button3sTriggered;
                    bool was10s = _button10sTriggered;

                    _buttonIsDown = false;
                    _buttonReleasePending = false;
                    _button3sTriggered = false;
                    _button10sTriggered = false;

                    if (!was3s && !was10s && totalLowMs >= BTN_PRESS_MIN_MS) {
                        char pinLabel[40];
                        formatButtonPinLabel(usedMask, pinLabel, sizeof(pinLabel));

                        if (effectiveSpanMs <= BTN_SHORT_PRESS_MAX_MS) {
                            if (_isPairingMode) {
                                unsigned long pairNow = millis();
                                if (pairNow >= _pairingStartTime && (pairNow - _pairingStartTime >= BTN_PAIR_CANCEL_GUARD_MS)) {
                                    _isPairingMode = false;
                                    LedIndicator::setState(LED_IDLE);
                                    _needsUpdateStateBeacon = true;
                                    Serial.printf("Modalità accoppiamento annullata tramite pressione breve [%s].\n", pinLabel);
                                }
                            } else if (RollingCodeManager::hasAnyClient()) {
                                LedIndicator::setState(LED_HEARTBEAT);
                                Serial.printf("Pulsante [%s] premuto (%lu ms): Heartbeat di diagnostica attivato.\n",
                                              pinLabel, totalLowMs);
                            }
                        } else {
                            Serial.printf("Pulsante [%s] rilasciato dopo %lu ms (contatto LOW: %lu ms, micro-distacchi: %u) - tieni premuto per 3s per attivare l'accoppiamento.\n",
                                          pinLabel, effectiveSpanMs, totalLowMs, (unsigned int)dropouts);
                        }
                    }
                }
            }
        }
    }

    // Gestione Modalità Accoppiamento: doppio beep ogni secondo e timeout a 60 secondi.
    // Usa millis() aggiornato (e non `now` letto a inizio loop) per evitare underflow unsigned
    // nello stesso ciclo in cui enterPairingMode() imposta _pairingStartTime = millis().
    if (_isPairingMode) {
        unsigned long pairNow = millis();
        if (pairNow >= _pairingStartTime && (pairNow - _pairingStartTime >= 60000UL)) {
            _isPairingMode = false;
            LedIndicator::setState(LED_IDLE);
            Serial.println("Timeout modalità Accoppiamento (60s) scaduto.");
            updateStateBeacon();
        } else if (pairNow >= _lastPairingBeepTime && (pairNow - _lastPairingBeepTime >= 1000UL)) {
            _lastPairingBeepTime = pairNow;
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

        if (_identifyOnlyMode) {
            // MODALITÀ IDENTIFICAZIONE CLI: Nessun azionamento relè!
            _lastTriggeredTargetState = (digitalRead(_sensorPin) == HW_SENSOR_CLOSED_STATE) ? 1 : 0;
            BuzzerManager::playSuccessSequence();
            startAdvertisingResponse((_lastTriggeredTargetState == 1) ? "ACK_OK_OPEN" : "ACK_OK_CLOSED");
        } else {
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

    if (_needsEnterOtaAction) {
        _needsEnterOtaAction = false;
        uint32_t otaCounter = _pendingOtaCounter;
        _isPairingMode = false;

        if (OtaManager::beginOtaSession(otaCounter)) {
            Logger::logOperation("OTA_AVVIATO");
            if (pBLEScan) {
                pBLEScan->stop();
                pBLEScan->clearResults();
            }
            // Abilita temporaneamente connessioni BLE GATT solo per la sessione OTA
            setAdvertisingConnectable(true);
            BLEAdvertisementData oAdvertisementData;
            oAdvertisementData.setFlags(0x06);
            oAdvertisementData.setCompleteServices(BLEUUID(SERVICE_UUID));
            pAdvertising->setAdvertisementData(oAdvertisementData);
            pAdvertising->setScanResponse(true);

            startAdvertisingResponse("ACK_OTA_READY");
            pAdvertising->start();
            return;
        } else {
            LedIndicator::setState(LED_ERROR);
            BuzzerManager::playErrorSequence();
            startAdvertisingResponse("NACK_ERR");
        }
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
            case RESP_ACK_OTA_READY: startAdvertisingResponse("ACK_OTA_READY"); break;
            case RESP_NACK_NOT_MASTER:
                Logger::logOperation("OTA_NEGATO");
                startAdvertisingResponse("NACK_NOT_MASTER");
                break;
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
            if (!_identifyOnlyMode) {
                if (currentSensorState == HW_SENSOR_CLOSED_STATE) {
                    Serial.println(">>> [DEBUG] SENSOR STATE CHANGED: CLOSED");
                } else {
                    Serial.println(">>> [DEBUG] SENSOR STATE CHANGED: OPEN");
                }
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
    _buttonIsDown = false;
    _buttonReleasePending = false;
    _button3sTriggered = false;
    _button10sTriggered = false;
    if (OtaManager::isActive()) {
        OtaManager::abortOtaSession("OTA_INTERROTTO_CLI");
    }
    if (_isAckActive) {
        _isAckActive = false;
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
    // Se BLE è già attivo (es. menu Master che aggiorna il MasterTag), aggiorna solo il beacon:
    // richiamare BLEScan::start() su una scansione continua già in corso bloccherebbe per sempre
    // il chiamante (Bluedroid attende il semaforo di fine scansione, che non arriva mai).
    if (!_isPaused) {
        updateStateBeacon();
        return;
    }
    _isPaused = false;
    setAdvertisingConnectable(false);
    BLEAdvertisementData oAdvertisementData;
    oAdvertisementData.setFlags(0x06);
    oAdvertisementData.setCompleteServices(BLEUUID(SERVICE_UUID));
    pAdvertising->setAdvertisementData(oAdvertisementData);
    pAdvertising->setScanResponse(true);

    updateStateBeacon();
    if (pBLEScan) {
        pBLEScan->start(0, nullptr, false);
    }
    if (pAdvertising) {
        pAdvertising->start();
    }
}
