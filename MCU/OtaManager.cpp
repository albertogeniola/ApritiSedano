#include "OtaManager.h"
#include "RollingCodeManager.h"
#include "BleHandler.h"
#include "LedIndicator.h"
#include "BuzzerManager.h"
#include "Logger.h"
#include <Update.h>
#include <esp_ota_ops.h>
#include <string.h>

#if defined(CONFIG_BLUEDROID_ENABLED)
#include <BLE2902.h>
#endif

uint8_t OtaManager::_ownMac[6] = {0};
BLEServer* OtaManager::_pServer = nullptr;
BLEService* OtaManager::_pService = nullptr;
BLECharacteristic* OtaManager::_pCtrlChar = nullptr;
BLECharacteristic* OtaManager::_pDataChar = nullptr;

volatile bool OtaManager::_otaModeActive = false;
volatile bool OtaManager::_clientConnected = false;
volatile bool OtaManager::_clientDisconnectedEvent = false;
volatile bool OtaManager::_transferStarted = false;
volatile bool OtaManager::_protocolOverflow = false;
bool OtaManager::_rebootPending = false;
unsigned long OtaManager::_rebootScheduledMs = 0;

uint32_t OtaManager::_authorizedCounter = 0;
unsigned long OtaManager::_sessionStartMs = 0;
volatile unsigned long OtaManager::_lastActivityMs = 0;

uint8_t OtaManager::_ctrlCmdBuf[80] = {0};
volatile size_t OtaManager::_ctrlCmdLen = 0;
volatile bool OtaManager::_hasPendingCtrlCmd = false;

uint8_t* OtaManager::_windowBuf = nullptr;
volatile size_t OtaManager::_rxWriteIdx = 0;
size_t OtaManager::_rxReadIdx = 0;
size_t OtaManager::_expectedFwSize = 0;
size_t OtaManager::_totalFlushed = 0;
size_t OtaManager::_windowTargetOffset = 0;
uint8_t OtaManager::_expectedSha256[32] = {0};

mbedtls_md_context_t OtaManager::_shaCtx;
bool OtaManager::_shaCtxInitialized = false;
portMUX_TYPE OtaManager::_mux = portMUX_INITIALIZER_UNLOCKED;

static const unsigned long OTA_CONNECT_TIMEOUT_MS  = 60000UL; // 60s max per connettersi via GATT
static const unsigned long OTA_ACTIVITY_TIMEOUT_MS = 30000UL; // 30s max di inattività durante il trasferimento

class OtaServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) override {
        if (!OtaManager::_otaModeActive) {
            // Difesa in profondità: durante il funzionamento normale l'advertising è già non-connectable,
            // ma se mai dovesse arrivare una connessione non autorizzata la chiudiamo istantaneamente.
            pServer->disconnect(pServer->getConnId());
            return;
        }
        OtaManager::_clientConnected = true;
        OtaManager::_lastActivityMs = millis();
        Serial.println("OTA: Smartphone Master connesso via BLE GATT.");
    }

    void onDisconnect(BLEServer* pServer) override {
        (void)pServer;
        OtaManager::_clientConnected = false;
        if (OtaManager::_otaModeActive && !OtaManager::_rebootPending) {
            OtaManager::_clientDisconnectedEvent = true;
        }
    }
};

class OtaCtrlCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pCharacteristic) override {
        if (!OtaManager::_otaModeActive || OtaManager::_rebootPending) return;

        size_t len = pCharacteristic->getLength();
        uint8_t* data = pCharacteristic->getData();
        if (data == nullptr || len == 0 || len > sizeof(OtaManager::_ctrlCmdBuf)) {
            return;
        }

        portENTER_CRITICAL(&OtaManager::_mux);
        if (!OtaManager::_hasPendingCtrlCmd) {
            memcpy(OtaManager::_ctrlCmdBuf, data, len);
            OtaManager::_ctrlCmdLen = len;
            OtaManager::_hasPendingCtrlCmd = true;
            OtaManager::_lastActivityMs = millis();
        }
        portEXIT_CRITICAL(&OtaManager::_mux);
    }
};

class OtaDataCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pCharacteristic) override {
        if (!OtaManager::_otaModeActive || !OtaManager::_transferStarted || OtaManager::_rebootPending) {
            return;
        }

        size_t len = pCharacteristic->getLength();
        uint8_t* data = pCharacteristic->getData();
        if (data == nullptr || len == 0) return;

        portENTER_CRITICAL(&OtaManager::_mux);
        if (OtaManager::_windowBuf != nullptr && (OtaManager::_rxWriteIdx + len <= OTA_WINDOW_SIZE)) {
            memcpy(&OtaManager::_windowBuf[OtaManager::_rxWriteIdx], data, len);
            OtaManager::_rxWriteIdx += len;
            OtaManager::_lastActivityMs = millis();
        } else {
            OtaManager::_protocolOverflow = true;
        }
        portEXIT_CRITICAL(&OtaManager::_mux);
    }
};

void OtaManager::init(const uint8_t* ownMac6) {
    if (ownMac6 != nullptr) {
        memcpy(_ownMac, ownMac6, 6);
    }

    // Consente pacchetti GATT fino a 517 byte (chunk dati da ~480 byte) su NimBLE e Bluedroid
    BLEDevice::setMTU(517);

    static OtaServerCallbacks serverCallbacks;
    static OtaCtrlCallbacks ctrlCallbacks;
    static OtaDataCallbacks dataCallbacks;

    _pServer = BLEDevice::createServer();
    _pServer->setCallbacks(&serverCallbacks);

    _pService = _pServer->createService(OTA_SERVICE_UUID);

    _pCtrlChar = _pService->createCharacteristic(
        OTA_CTRL_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_NOTIFY
    );
#if defined(CONFIG_BLUEDROID_ENABLED)
    _pCtrlChar->addDescriptor(new BLE2902());
#endif
    _pCtrlChar->setCallbacks(&ctrlCallbacks);

    _pDataChar = _pService->createCharacteristic(
        OTA_DATA_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
    );
    _pDataChar->setCallbacks(&dataCallbacks);

    _pService->start();
#if defined(CONFIG_NIMBLE_ENABLED)
    _pServer->start();
#endif
}

bool OtaManager::beginOtaSession(uint32_t authorizedCounter) {
    if (_otaModeActive) {
        return true;
    }

    const esp_partition_t* nextPart = esp_ota_get_next_update_partition(NULL);
    if (nextPart == nullptr) {
        Serial.println("OTA ERRORE: Nessuna partizione OTA secondaria trovata (tabella partizioni non OTA)!");
        Logger::logOperation("OTA_ERR_NO_PART");
        return false;
    }

    cleanupSessionResources();

    _windowBuf = (uint8_t*)malloc(OTA_WINDOW_SIZE);
    if (_windowBuf == nullptr) {
        Serial.println("OTA ERRORE: Memoria RAM insufficiente per il buffer OTA!");
        Logger::logOperation("OTA_ERR_MEM");
        return false;
    }

    _authorizedCounter = authorizedCounter;
    _sessionStartMs = millis();
    _lastActivityMs = _sessionStartMs;
    _clientConnected = false;
    _clientDisconnectedEvent = false;
    _transferStarted = false;
    _protocolOverflow = false;
    _rebootPending = false;
    _hasPendingCtrlCmd = false;
    _rxWriteIdx = 0;
    _rxReadIdx = 0;
    _expectedFwSize = 0;
    _totalFlushed = 0;
    _windowTargetOffset = 0;

    _otaModeActive = true;
    LedIndicator::setState(LED_OTA);
    BuzzerManager::playSuccessSequence();
    Serial.printf(">>> MODALITA' OTA ATTIVA (Partizione target: %s, Max %lu B, Timeout connessione: 60s) <<<\n",
                  nextPart->label, (unsigned long)nextPart->size);
    return true;
}

uint32_t OtaManager::getRemainingWaitSeconds() {
    if (!_otaModeActive || _clientConnected) return 0;
    unsigned long elapsed = millis() - _sessionStartMs;
    if (elapsed >= OTA_CONNECT_TIMEOUT_MS) return 0;
    return (uint32_t)((OTA_CONNECT_TIMEOUT_MS - elapsed + 999UL) / 1000UL);
}

void OtaManager::notifyControl(const char* msg) {
    if (_pCtrlChar == nullptr || !_clientConnected || msg == nullptr) return;
    _pCtrlChar->setValue((const uint8_t*)msg, strlen(msg));
    _pCtrlChar->notify();
}

void OtaManager::cleanupSessionResources() {
    if (Update.isRunning()) {
        Update.abort();
    }
    if (_shaCtxInitialized) {
        mbedtls_md_free(&_shaCtx);
        _shaCtxInitialized = false;
    }
    portENTER_CRITICAL(&_mux);
    uint8_t* bufToFree = _windowBuf;
    _windowBuf = nullptr;
    _rxWriteIdx = 0;
    _rxReadIdx = 0;
    _hasPendingCtrlCmd = false;
    portEXIT_CRITICAL(&_mux);

    if (bufToFree != nullptr) {
        free(bufToFree);
    }
}

void OtaManager::abortOtaSession(const char* reasonLog) {
    if (!_otaModeActive) return;

    Serial.printf("OTA: Sessione terminata/annullata (%s).\n", reasonLog ? reasonLog : "abort");
    if (reasonLog != nullptr) {
        Logger::logOperation(reasonLog);
    }

    if (_pServer != nullptr && _clientConnected) {
        _pServer->disconnect(_pServer->getConnId());
    }

    _otaModeActive = false;
    _clientConnected = false;
    _clientDisconnectedEvent = false;
    _transferStarted = false;
    _protocolOverflow = false;
    _rebootPending = false;

    cleanupSessionResources();

    LedIndicator::setState(LED_ERROR);
    BuzzerManager::playErrorSequence();
    BleHandler::onOtaSessionEnded();
}

bool OtaManager::verifyBeginHmac(
    const uint8_t* masterSeed16,
    uint32_t fwSize,
    uint32_t counter,
    const uint8_t* sha256_32,
    const uint8_t* receivedHmac20
) {
    // Layout messaggio autenticato (52 byte):
    // [0..5]:   "OTA_V1" (6B ASCII)
    // [6..11]:  _ownMac (6B Big-Endian)
    // [12..15]: fwSize (4B Big-Endian)
    // [16..19]: counter (4B Big-Endian)
    // [20..51]: sha256_32 (32B)
    uint8_t msg[52];
    memcpy(&msg[0], "OTA_V1", 6);
    memcpy(&msg[6], _ownMac, 6);
    msg[12] = (uint8_t)((fwSize >> 24) & 0xFF);
    msg[13] = (uint8_t)((fwSize >> 16) & 0xFF);
    msg[14] = (uint8_t)((fwSize >> 8) & 0xFF);
    msg[15] = (uint8_t)(fwSize & 0xFF);
    msg[16] = (uint8_t)((counter >> 24) & 0xFF);
    msg[17] = (uint8_t)((counter >> 16) & 0xFF);
    msg[18] = (uint8_t)((counter >> 8) & 0xFF);
    msg[19] = (uint8_t)(counter & 0xFF);
    memcpy(&msg[20], sha256_32, 32);

    uint8_t expectedHmac[20];
    mbedtls_md_context_t ctx;
    mbedtls_md_init(&ctx);
    mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA1), 1);
    mbedtls_md_hmac_starts(&ctx, masterSeed16, RC_SEED_SIZE);
    mbedtls_md_hmac_update(&ctx, msg, sizeof(msg));
    mbedtls_md_hmac_finish(&ctx, expectedHmac);
    mbedtls_md_free(&ctx);

    // Confronto in tempo costante per prevenire timing side-channels
    uint8_t diff = 0;
    for (int i = 0; i < 20; i++) {
        diff |= (expectedHmac[i] ^ receivedHmac20[i]);
    }
    return (diff == 0);
}

void OtaManager::loop() {
    if (!_otaModeActive) return;

    unsigned long now = millis();

    // 1. Se l'aggiornamento è completato, attendiamo 1.2s per far partire la notifica BLE e la melodia prima del reboot
    if (_rebootPending) {
        if (now - _rebootScheduledMs >= 1200UL) {
            Serial.println("OTA: Riavvio del sistema sul nuovo firmware!");
            Serial.flush();
            if (_pServer != nullptr && _clientConnected) {
                _pServer->disconnect(_pServer->getConnId());
                delay(100);
            }
            ESP.restart();
        }
        return;
    }

    // 2. Gestione disconnessione imprevista del client GATT
    if (_clientDisconnectedEvent) {
        _clientDisconnectedEvent = false;
        abortOtaSession("OTA_DISCONNESSO");
        return;
    }

    // 3. Gestione overflow finestra dati
    if (_protocolOverflow) {
        notifyControl("ERR:OVERFLOW");
        delay(50);
        abortOtaSession("OTA_ERR_OVERFLOW");
        return;
    }

    // 4. Watchdog timeout (60s in attesa di connessione GATT, 30s di inattività durante trasferimento)
    if (!_clientConnected) {
        if (now - _sessionStartMs >= OTA_CONNECT_TIMEOUT_MS) {
            abortOtaSession("OTA_TIMEOUT_CONN");
            return;
        }
    } else {
        if (now - _lastActivityMs >= OTA_ACTIVITY_TIMEOUT_MS) {
            notifyControl("ERR:TIMEOUT");
            delay(50);
            abortOtaSession("OTA_TIMEOUT_DATA");
            return;
        }
    }

    // 5. Drenaggio chunk ricevuti nel buffer di finestra (16 KB) verso partizione flash OTA + SHA-256
    if (_transferStarted && _windowBuf != nullptr) {
        size_t writeSnap = 0;
        portENTER_CRITICAL(&_mux);
        writeSnap = _rxWriteIdx;
        portEXIT_CRITICAL(&_mux);

        if (writeSnap > _rxReadIdx) {
            size_t avail = writeSnap - _rxReadIdx;

            if (_totalFlushed + avail > _expectedFwSize) {
                notifyControl("ERR:SIZE");
                delay(50);
                abortOtaSession("OTA_ERR_SIZE");
                return;
            }

            size_t written = Update.write(&_windowBuf[_rxReadIdx], avail);
            if (written != avail || Update.hasError()) {
                Serial.printf("OTA ERRORE Update.write: %s\n", Update.errorString());
                notifyControl("ERR:FLASH");
                delay(50);
                abortOtaSession("OTA_ERR_FLASH");
                return;
            }

            mbedtls_md_update(&_shaCtx, &_windowBuf[_rxReadIdx], avail);
            _rxReadIdx += avail;
            _totalFlushed += avail;

            // Se abbiamo completato la finestra corrente da 16 KB (o l'ultimo blocco del file),
            // azzeriamo gli indici della finestra e inviamo l'ACK di controllo flusso all'App.
            if (_totalFlushed >= _windowTargetOffset) {
                portENTER_CRITICAL(&_mux);
                _rxWriteIdx = 0;
                _rxReadIdx = 0;
                portEXIT_CRITICAL(&_mux);

                size_t remaining = _expectedFwSize - _totalFlushed;
                size_t nextStep = (remaining > OTA_WINDOW_SIZE) ? OTA_WINDOW_SIZE : remaining;
                _windowTargetOffset = _totalFlushed + nextStep;

                char ackMsg[24];
                snprintf(ackMsg, sizeof(ackMsg), "ACK:%lu", (unsigned long)_totalFlushed);
                notifyControl(ackMsg);

                uint32_t pct = (uint32_t)(((uint64_t)_totalFlushed * 100ULL) / (uint64_t)_expectedFwSize);
                Serial.printf("OTA Progresso: %lu / %lu byte (%lu%%)\n",
                              (unsigned long)_totalFlushed, (unsigned long)_expectedFwSize, (unsigned long)pct);
            }
        }
    }

    // 6. Elaborazione comandi sul canale di controllo (OP_BEGIN / OP_END / OP_ABORT)
    uint8_t cmdCopy[80];
    size_t cmdLen = 0;
    bool hasCmd = false;

    portENTER_CRITICAL(&_mux);
    if (_hasPendingCtrlCmd) {
        cmdLen = _ctrlCmdLen;
        memcpy(cmdCopy, _ctrlCmdBuf, cmdLen);
        _hasPendingCtrlCmd = false;
        hasCmd = true;
    }
    portEXIT_CRITICAL(&_mux);

    if (!hasCmd || cmdLen == 0) return;

    uint8_t opcode = cmdCopy[0];

    if (opcode == OTA_OP_ABORT) {
        abortOtaSession("OTA_ANNULLATO_APP");
        return;
    }

    if (opcode == OTA_OP_BEGIN) {
        // Layout OP_BEGIN (61 byte): [0x01] [FwSize(4B BE)] [Counter(4B BE)] [SHA256(32B)] [HMAC_SHA1(20B)]
        if (_transferStarted || cmdLen < 61) {
            notifyControl("ERR:PROTO");
            delay(50);
            abortOtaSession("OTA_ERR_PROTO");
            return;
        }

        uint32_t fwSize = ((uint32_t)cmdCopy[1] << 24) |
                          ((uint32_t)cmdCopy[2] << 16) |
                          ((uint32_t)cmdCopy[3] << 8)  |
                          (uint32_t)cmdCopy[4];
        uint32_t counter = ((uint32_t)cmdCopy[5] << 24) |
                           ((uint32_t)cmdCopy[6] << 16) |
                           ((uint32_t)cmdCopy[7] << 8)  |
                           (uint32_t)cmdCopy[8];
        const uint8_t* sha256Ptr = &cmdCopy[9];
        const uint8_t* hmacPtr   = &cmdCopy[41];

        uint8_t masterSeed[RC_SEED_SIZE];
        if (!RollingCodeManager::getMasterSeed(masterSeed) ||
            counter != _authorizedCounter ||
            !verifyBeginHmac(masterSeed, fwSize, counter, sha256Ptr, hmacPtr)) {
            memset(masterSeed, 0, sizeof(masterSeed));
            Serial.println("OTA ERRORE: Autenticazione HMAC OP_BEGIN fallita!");
            notifyControl("ERR:AUTH");
            delay(50);
            abortOtaSession("OTA_ERR_AUTH");
            return;
        }
        memset(masterSeed, 0, sizeof(masterSeed));

        const esp_partition_t* nextPart = esp_ota_get_next_update_partition(NULL);
        if (nextPart == nullptr || fwSize < 65536UL || fwSize > nextPart->size) {
            Serial.printf("OTA ERRORE: Dimensione firmware non valida (%lu B).\n", (unsigned long)fwSize);
            notifyControl("ERR:SPACE");
            delay(50);
            abortOtaSession("OTA_ERR_SPACE");
            return;
        }

        if (!Update.begin(fwSize, U_FLASH)) {
            Serial.printf("OTA ERRORE Update.begin: %s\n", Update.errorString());
            notifyControl("ERR:BEGIN");
            delay(50);
            abortOtaSession("OTA_ERR_BEGIN");
            return;
        }

        if (_shaCtxInitialized) {
            mbedtls_md_free(&_shaCtx);
        }
        mbedtls_md_init(&_shaCtx);
        mbedtls_md_setup(&_shaCtx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 0);
        mbedtls_md_starts(&_shaCtx);
        _shaCtxInitialized = true;

        memcpy(_expectedSha256, sha256Ptr, 32);
        _expectedFwSize = (size_t)fwSize;
        _totalFlushed = 0;
        _windowTargetOffset = (_expectedFwSize > OTA_WINDOW_SIZE) ? OTA_WINDOW_SIZE : _expectedFwSize;

        portENTER_CRITICAL(&_mux);
        _rxWriteIdx = 0;
        _rxReadIdx = 0;
        portEXIT_CRITICAL(&_mux);

        _transferStarted = true;
        Serial.printf("OTA: Sessione autenticata! Inizio ricezione firmware (%lu byte)...\n", (unsigned long)fwSize);
        notifyControl("OTA_READY");
        return;
    }

    if (opcode == OTA_OP_END) {
        if (!_transferStarted || _totalFlushed != _expectedFwSize) {
            Serial.printf("OTA ERRORE OP_END: ricevuti %lu byte su %lu attesi.\n",
                          (unsigned long)_totalFlushed, (unsigned long)_expectedFwSize);
            notifyControl("ERR:INCOMPLETE");
            delay(50);
            abortOtaSession("OTA_ERR_INCOMPLETE");
            return;
        }

        uint8_t computedSha256[32];
        mbedtls_md_finish(&_shaCtx, computedSha256);
        mbedtls_md_free(&_shaCtx);
        _shaCtxInitialized = false;

        if (memcmp(computedSha256, _expectedSha256, 32) != 0) {
            Serial.println("OTA ERRORE CRITICO: SHA-256 del firmware ricevuto NON corrisponde!");
            notifyControl("ERR:HASH");
            delay(50);
            abortOtaSession("OTA_ERR_HASH");
            return;
        }

        if (!Update.end(false) || !Update.isFinished()) {
            Serial.printf("OTA ERRORE Update.end: %s\n", Update.errorString());
            notifyControl("ERR:FINALIZE");
            delay(50);
            abortOtaSession("OTA_ERR_FINALIZE");
            return;
        }

        Serial.println(">>> OTA COMPLETATO CON SUCCESSO! Verifica SHA-256 superata. <<<");
        Logger::logOperation("OTA_COMPLETATO");
        LedIndicator::setState(LED_SUCCESS);
        BuzzerManager::playPairingCompleteSequence();
        notifyControl("OTA_OK");

        _rebootPending = true;
        _rebootScheduledMs = millis();
        return;
    }
}
