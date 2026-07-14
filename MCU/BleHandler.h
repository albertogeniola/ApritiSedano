#ifndef BLE_HANDLER_H
#define BLE_HANDLER_H

#include <Arduino.h>

class BleHandler {
public:
    static void init(int relayPin, int sensorPin);
    static void loop();
    static void pause();
    static void resume();

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
    static bool _isPaused;
    
    static uint32_t _lastValidCode;
    static unsigned long _lastValidTime;
    
    static bool _isTimeInvalid;
    static bool _isTimeSyncMode;
    static unsigned long _timeSyncEndTime;

    static bool _isAckActive;
    static unsigned long _ackEndTime;
    static unsigned long _lastBeaconTime;
    
    static bool _isRelayActive;
    static unsigned long _relayEndTime;
    
    static const char* SERVICE_UUID;

    friend class BoxScanCallbacks;
};

#endif
