#pragma once
#include <Arduino.h>

enum class MelodyType {
    OPEN,
    CLOSE,
    ERROR_DESYNC,
    ERROR_WRONG_CODE,
    SUCCESS,
    ANTENNA_INTERNAL,
    ANTENNA_EXTERNAL,
    ANTENNA_ERROR
};

class MelodyPlayer {
public:
    static void init(int pin);
    static void loop();
    static void play(MelodyType type);

private:
    static int _pin;
    static const int* _currentMelodyNotes;
    static const int* _currentMelodyDurations;
    static int _currentMelodyLength;
    static int _noteIndex;
    static unsigned long _nextNoteTime;
    static bool _isPlaying;
    static bool _isNoteOn;
};
