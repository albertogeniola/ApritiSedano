#pragma once
#include <Arduino.h>

enum LedState {
    LED_IDLE,
    LED_SUCCESS,
    LED_ERROR,
    LED_CONFIG,
    LED_UNCONFIGURED,
    LED_OUT_OF_SYNC
};

class LedIndicator {
public:
    static void init(int pin);
    static void setState(LedState state);
    static LedState getState();
    static void loop();
private:
    static int _pin;
    static LedState _currentState;
    static unsigned long _stateStartTime;
    static unsigned long _lastToggleTime;
    static bool _ledOn;
    static int _blinkCount;
    static void setRGB(uint8_t r, uint8_t g, uint8_t b);
};
