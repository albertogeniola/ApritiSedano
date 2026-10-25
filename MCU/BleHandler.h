#ifndef BLE_HANDLER_H
#define BLE_HANDLER_H

#include <Arduino.h>

enum BleResponseType : uint8_t {
    RESP_NONE = 0,
    RESP_ACK_OPEN = 1,
    RESP_ACK_CLOSED = 2,
    RESP_ACK_PAIRED = 3,
    RESP_ACK_PAIR_MODE = 4,
    RESP_ACK_TIME_OK = 5,
    RESP_NACK_USED = 6,
    RESP_NACK_ERR = 7
};

class BleHandler {
public:
    static void init(int relayPin, int sensorPin);
    static void loop();
    static void pause();
    static void resume();
    static void enterPairingMode();
    static bool isPairingMode() { return _isPairingMode; }
    static bool isBusy() {
        return _isRelayActive || _isAckActive || _isPairingMode || _buttonIsDown ||
               _needsTriggerAction || _needsDuplicateAction || _needsEnterPairingAction ||
               _needsPairedSuccessAction || (_pendingTimeSyncEpoch != 0);
    }

private:
    static void startAdvertisingResponse(const char* payloadStr);
    static void stopAdvertisingACK();
    static void updateStateBeacon();
    static void triggerRelay();

    static int _relayPin;
    static int _sensorPin;
    static int _lastSensorState;
    static uint8_t _lastTriggeredTargetState;
    static uint8_t _ownMac[6];
    static bool _isPaused;

    static unsigned long _lastTimeSyncMs;
    static bool _isPairingMode;
    static unsigned long _pairingStartTime;
    static unsigned long _lastPairingBeepTime;

    static bool _buttonIsDown;
    static unsigned long _buttonPressStartTime;
    static bool _button3sTriggered;
    static bool _button10sTriggered;

    static volatile bool _needsUpdateStateBeacon;
    static volatile bool _needsTriggerAction;
    static volatile bool _needsDuplicateAction;
    static volatile bool _needsEnterPairingAction;
    static volatile bool _needsPairedSuccessAction;
    static volatile uint8_t _pendingResponseCode;
    static volatile uint8_t _needsErrorFeedback;
    static volatile time_t _pendingTimeSyncEpoch;

    static bool _isAckActive;
    static unsigned long _ackStartTime;
    static unsigned long _lastBeaconTime;

    static bool _isRelayActive;
    static unsigned long _relayStartTime;

    static const char* SERVICE_UUID;

    friend class BoxScanCallbacks;
};

#endif
