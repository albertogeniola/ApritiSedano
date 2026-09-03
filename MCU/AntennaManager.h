#pragma once
#include <Arduino.h>

enum AntennaState {
    ANTENNA_INTERNAL = 0,
    ANTENNA_EXTERNAL = 1,
    ANTENNA_NOT_SUPPORTED = -1
};

class AntennaManager {
public:
    static void init();
    static bool isSupported();
    static bool isExternal();
    static AntennaState getState();
    static AntennaState toggle();
    static bool setExternal(bool external);

private:
    static bool _isExternal;
    static void applyHardwareState();
};
