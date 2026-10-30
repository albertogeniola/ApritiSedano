#ifndef OTA_MANAGER_H
#define OTA_MANAGER_H

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <mbedtls/md.h>

// BLE GATT Service & Characteristics dedicated to Master-Authenticated OTA
#define OTA_SERVICE_UUID   "12345678-1234-5678-1234-56789abcdef1"
#define OTA_CTRL_CHAR_UUID "12345678-1234-5678-1234-56789abcdef2"
#define OTA_DATA_CHAR_UUID "12345678-1234-5678-1234-56789abcdef3"

// Control Characteristic Opcodes
#define OTA_OP_BEGIN 0x01
#define OTA_OP_END   0x02
#define OTA_OP_ABORT 0x03

// Flow-control window size (16 KiB)
#define OTA_WINDOW_SIZE 16384

class OtaManager {
public:
    static void init(const uint8_t* ownMac6);
    static void loop();

    // Activates a 60s OTA GATT window bound to the Master App's rolling-code counter
    static bool beginOtaSession(uint32_t authorizedCounter);
    static void abortOtaSession(const char* reasonLog = nullptr);

    static bool isActive() { return _otaModeActive; }
    static bool isClientConnected() { return _clientConnected; }
    static uint32_t getRemainingWaitSeconds();

private:
    static void notifyControl(const char* msg);
    static void cleanupSessionResources();
    static bool verifyBeginHmac(
        const uint8_t* masterSeed16,
        uint32_t fwSize,
        uint32_t counter,
        const uint8_t* sha256_32,
        const uint8_t* receivedHmac20
    );

    static uint8_t _ownMac[6];
    static BLEServer* _pServer;
    static BLEService* _pService;
    static BLECharacteristic* _pCtrlChar;
    static BLECharacteristic* _pDataChar;

    static volatile bool _otaModeActive;
    static volatile bool _clientConnected;
    static volatile bool _clientDisconnectedEvent;
    static volatile bool _transferStarted;
    static volatile bool _protocolOverflow;
    static bool _rebootPending;
    static unsigned long _rebootScheduledMs;

    static uint32_t _authorizedCounter;
    static unsigned long _sessionStartMs;
    static volatile unsigned long _lastActivityMs;

    // Control packet staging buffer (written on BLE callback, processed on loopTask)
    static uint8_t _ctrlCmdBuf[80];
    static volatile size_t _ctrlCmdLen;
    static volatile bool _hasPendingCtrlCmd;

    // 16 KiB flow-controlled staging buffer (allocated only while OTA is active)
    static uint8_t* _windowBuf;
    static volatile size_t _rxWriteIdx;
    static size_t _rxReadIdx;
    static size_t _expectedFwSize;
    static size_t _totalFlushed;
    static size_t _windowTargetOffset;
    static uint8_t _expectedSha256[32];

    static mbedtls_md_context_t _shaCtx;
    static bool _shaCtxInitialized;
    static portMUX_TYPE _mux;

    friend class OtaServerCallbacks;
    friend class OtaCtrlCallbacks;
    friend class OtaDataCallbacks;
};

#endif
