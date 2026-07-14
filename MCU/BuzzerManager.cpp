#include "BuzzerManager.h"

int BuzzerManager::_pin = -1;
int BuzzerManager::_beepsRemaining = 0;
bool BuzzerManager::_isBeeping = false;
unsigned long BuzzerManager::_nextToggleTime = 0;
int BuzzerManager::_currentBeepDurationMs = 100;
int BuzzerManager::_currentSilenceDurationMs = 100;

void BuzzerManager::init(int pin) {
    _pin = pin;
    if (_pin != -1) {
        pinMode(_pin, OUTPUT);
        digitalWrite(_pin, LOW); // Assumiamo active HIGH per il buzzer semplice
    }
}

void BuzzerManager::playOpenSequence() {
    if (_beepsRemaining > 0) return;
    _currentBeepDurationMs = BEEP_DURATION_MS;
    _currentSilenceDurationMs = SILENCE_DURATION_MS;
    _beepsRemaining = 2;
    _isBeeping = true;
    _nextToggleTime = millis() + _currentBeepDurationMs;
    if (_pin != -1) digitalWrite(_pin, HIGH);
}

void BuzzerManager::playCloseSequence() {
    if (_beepsRemaining > 0) return;
    _currentBeepDurationMs = BEEP_DURATION_MS;
    _currentSilenceDurationMs = SILENCE_DURATION_MS;
    _beepsRemaining = 3;
    _isBeeping = true;
    _nextToggleTime = millis() + _currentBeepDurationMs;
    if (_pin != -1) digitalWrite(_pin, HIGH);
}

void BuzzerManager::playErrorSequence() {
    if (_beepsRemaining > 0) return;
    _currentBeepDurationMs = 2000;
    _currentSilenceDurationMs = 500;
    _beepsRemaining = 2;
    _isBeeping = true;
    _nextToggleTime = millis() + _currentBeepDurationMs;
    if (_pin != -1) digitalWrite(_pin, HIGH);
}

void BuzzerManager::playSuccessSequence() {
    if (_beepsRemaining > 0) return;
    _currentBeepDurationMs = 2000;
    _currentSilenceDurationMs = 500;
    _beepsRemaining = 1;
    _isBeeping = true;
    _nextToggleTime = millis() + _currentBeepDurationMs;
    if (_pin != -1) digitalWrite(_pin, HIGH);
}

void BuzzerManager::loop() {
    if (_pin == -1 || _beepsRemaining <= 0) return;

    if (millis() >= _nextToggleTime) {
        if (_isBeeping) {
            // Fine del beep, inizia il silenzio
            digitalWrite(_pin, LOW);
            _isBeeping = false;
            _beepsRemaining--;
            
            if (_beepsRemaining > 0) {
                _nextToggleTime = millis() + _currentSilenceDurationMs;
            }
        } else {
            // Fine del silenzio, inizia il nuovo beep
            digitalWrite(_pin, HIGH);
            _isBeeping = true;
            _nextToggleTime = millis() + _currentBeepDurationMs;
        }
    }
}
