#pragma once
#include <Arduino.h>

class BuzzerManager {
public:
    static void init(int pin);
    static void loop();
    
    // Suona 2 beep ravvicinati per l'apertura
    static void playOpenSequence();
    
    // Suona 3 beep ravvicinati per la chiusura
    static void playCloseSequence();
    
    // Suona 2 beep lunghi (2s ciascuno) per errore (orario non sincronizzato)
    static void playErrorSequence();
    
    // Suona 1 beep lungo (2s) per successo (sincronizzazione avvenuta)
    static void playSuccessSequence();

private:
    static int _pin;
    static int _beepsRemaining;
    static bool _isBeeping;
    static unsigned long _nextToggleTime;
    static int _currentBeepDurationMs;
    static int _currentSilenceDurationMs;
    
    static const int BEEP_DURATION_MS = 100;
    static const int SILENCE_DURATION_MS = 100;
};
