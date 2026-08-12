#include "MelodyPlayer.h"

// Frequenze concentrate attorno ai 2048Hz per massimizzare il volume
#define NOTE_L1  1850
#define NOTE_L2  1950
#define NOTE_RES 2048 // Massima risonanza
#define NOTE_H1  2150
#define NOTE_H2  2250

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

// Melodies definitions - Aumentata la durata (ms) per renderle più percettibili
const int openNotes[] = { NOTE_L2, NOTE_RES, NOTE_H1, NOTE_H2 };
const int openDurations[] = { 150, 150, 150, 300 };

const int closeNotes[] = { NOTE_H2, NOTE_H1, NOTE_RES };
const int closeDurations[] = { 150, 150, 300 };

const int errorDesyncNotes[] = { NOTE_RES, REST, NOTE_RES };
const int errorDesyncDurations[] = { 600, 200, 600 };

const int errorWrongCodeNotes[] = { NOTE_L1, NOTE_H2, REST, NOTE_L1, NOTE_H2 };
const int errorWrongCodeDurations[] = { 200, 300, 150, 200, 300 };

const int successNotes[] = { NOTE_L2, NOTE_RES };
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
