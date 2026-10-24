#ifndef BLE_HANDLER_H
#define BLE_HANDLER_H

#include <Arduino.h>

class BleHandler {
public:
    static void init(int relayPin, int sensorPin);
    static void loop();
    static void pause();
    static void resume();
    static void onTimeUpdated();
    static bool isTimeInvalid() { return _isTimeInvalid; }
    static bool isBusy() {
        return _isRelayActive || _isAckActive || _isTimeSyncMode || _buttonIsDown ||
               _needsTriggerAction || _needsDuplicateAction || (_pendingTimeSyncEpoch != 0);
    }

private:
    static void startAdvertisingACK();
    static void startAdvertisingNACK();
    static void stopAdvertisingACK();
    static void updateStateBeacon();
    static void triggerRelay();
    static void IRAM_ATTR handleButtonPress();

    static int _relayPin;
    static int _sensorPin;
    static int _lastSensorState;
    static uint8_t _lastTriggeredTargetState;
    static bool _isPaused;
    
    static uint32_t _lastValidCode;
    static uint64_t _lastAcceptedStep;
    static unsigned long _lastValidTime;
    static unsigned long _lastTimeSyncMs;
    
    static bool _isTimeInvalid;
    static bool _isTimeSyncMode;
    static unsigned long _timeSyncStartTime;
    static bool _buttonIsDown;
    static unsigned long _buttonPressStartTime;
    static bool _button10sTriggered;
    static volatile bool _buttonPressedFlag;
    static volatile bool _needsUpdateStateBeacon;
    static volatile bool _needsStartAdvertisingACK;
    static volatile bool _needsStartAdvertisingNACK;
    static volatile bool _needsTriggerAction;
    static volatile bool _needsDuplicateAction;
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
