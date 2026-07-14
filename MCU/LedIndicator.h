#pragma once
#include <Arduino.h>

enum LedState {
    LED_IDLE,
    LED_SUCCESS,
    LED_ERROR,
    LED_CONFIG
};

class LedIndicator {
public:
    static void init(int pin);
    static void setState(LedState state);
    static void loop();
private:
    static int _pin;
    static LedState _currentState;
    static unsigned long _stateStartTime;
    static unsigned long _lastToggleTime;
    static bool _ledOn;
    static int _blinkCount;
    static void setLed(bool on);
};
