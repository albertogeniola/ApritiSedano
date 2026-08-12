#include "MelodyPlayer.h"

// Frequenze per le note musicali (Ottave alte per risuonare meglio sul buzzer piezoelettrico)
#define NOTE_G5  784
#define NOTE_B5  988
#define NOTE_C6  1047
#define NOTE_D6  1175
#define NOTE_E6  1319
#define NOTE_F6  1397
#define NOTE_G6  1568
#define NOTE_A6  1760
#define NOTE_B6  1976
#define NOTE_C7  2093
#define NOTE_CS7 2217
#define NOTE_D7  2349
#define NOTE_E7  2637
#define NOTE_F7  2794
#define NOTE_FS7 2960
#define NOTE_G7  3136

// Rest note (0 Hz)
#define REST     0

int MelodyPlayer::_pin = -1;
const int* MelodyPlayer::_currentMelodyNotes = nullptr;
const int* MelodyPlayer::_currentMelodyDurations = nullptr;
int MelodyPlayer::_currentMelodyLength = 0;
int MelodyPlayer::_noteIndex = 0;
unsigned long MelodyPlayer::_nextNoteTime = 0;
bool MelodyPlayer::_isPlaying = false;
bool MelodyPlayer::_isNoteOn = false;

// Super Mario Level Complete (Apertura) completa
const int openNotes[] = { NOTE_G5, NOTE_C6, NOTE_E6, NOTE_G6, NOTE_C7, NOTE_E7, NOTE_G7, NOTE_E7 };
const int openDurations[] = { 130, 130, 130, 130, 130, 130, 400, 400 };

// Super Mario Death/Game Over (Chiusura) completa
const int closeNotes[] = { NOTE_B6, NOTE_F7, REST, NOTE_F7, NOTE_F7, NOTE_E7, NOTE_D7, NOTE_C7, NOTE_E6, NOTE_C6 };
const int closeDurations[] = { 150, 150, 200, 150, 150, 150, 150, 250, 250, 400 };

const int errorDesyncNotes[] = { NOTE_C7, REST, NOTE_C7 };
const int errorDesyncDurations[] = { 600, 200, 600 };

const int errorWrongCodeNotes[] = { NOTE_B5, NOTE_G6, REST, NOTE_B5, NOTE_G6 };
const int errorWrongCodeDurations[] = { 200, 300, 150, 200, 300 };

const int successNotes[] = { NOTE_C6, NOTE_C7 };
const int successDurations[] = { 200, 400 };

void MelodyPlayer::init(int pin) {
    _pin = pin;
    if (_pin != -1) {
        pinMode(_pin, OUTPUT);
        digitalWrite(_pin, LOW);
    }
}

void MelodyPlayer::play(MelodyType type) {
    if (_pin == -1) return;
    
    switch (type) {
        case MelodyType::OPEN:
            _currentMelodyNotes = openNotes;
            _currentMelodyDurations = openDurations;
            _currentMelodyLength = sizeof(openNotes) / sizeof(openNotes[0]);
            break;
        case MelodyType::CLOSE:
            _currentMelodyNotes = closeNotes;
            _currentMelodyDurations = closeDurations;
            _currentMelodyLength = sizeof(closeNotes) / sizeof(closeNotes[0]);
            break;
        case MelodyType::ERROR_DESYNC:
            _currentMelodyNotes = errorDesyncNotes;
            _currentMelodyDurations = errorDesyncDurations;
            _currentMelodyLength = sizeof(errorDesyncNotes) / sizeof(errorDesyncNotes[0]);
            break;
        case MelodyType::ERROR_WRONG_CODE:
            _currentMelodyNotes = errorWrongCodeNotes;
            _currentMelodyDurations = errorWrongCodeDurations;
            _currentMelodyLength = sizeof(errorWrongCodeNotes) / sizeof(errorWrongCodeNotes[0]);
            break;
        case MelodyType::SUCCESS:
            _currentMelodyNotes = successNotes;
            _currentMelodyDurations = successDurations;
            _currentMelodyLength = sizeof(successNotes) / sizeof(successNotes[0]);
            break;
        default:
            return;
    }
    
    _noteIndex = 0;
    _isPlaying = true;
    _isNoteOn = false;
    _nextNoteTime = millis();
}

void MelodyPlayer::loop() {
    if (!_isPlaying || _pin == -1) return;
    
    if (millis() >= _nextNoteTime) {
        if (!_isNoteOn) {
            // Start playing the current note
            int note = _currentMelodyNotes[_noteIndex];
            int duration = _currentMelodyDurations[_noteIndex];
            
            if (note != REST) {
                tone(_pin, note, duration);
            } else {
                noTone(_pin);
            }
            
            _isNoteOn = true;
            // The note lasts for the specified duration. We'll start the next note after this one finishes
            // (plus a tiny gap if we wanted, but tone() handles its own duration, we just track when to move to the next).
            // tone() is non-blocking on ESP32, it just stops after duration.
            // But we must wait for it to finish before starting the next.
            _nextNoteTime = millis() + duration + 10; // 10ms gap between notes
        } else {
            // Note finished, move to next
            _isNoteOn = false;
            _noteIndex++;
            
            if (_noteIndex >= _currentMelodyLength) {
                // Melody finished
                _isPlaying = false;
                noTone(_pin);
            }
        }
    }
}
