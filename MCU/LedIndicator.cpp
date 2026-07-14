#include "LedIndicator.h"
#include "HardwareConfig.h"

int LedIndicator::_pin = -1;
LedState LedIndicator::_currentState = LED_IDLE;
unsigned long LedIndicator::_stateStartTime = 0;
unsigned long LedIndicator::_lastToggleTime = 0;
bool LedIndicator::_ledOn = false;
int LedIndicator::_blinkCount = 0;

void LedIndicator::init(int pin) {
    _pin = pin;
    pinMode(_pin, OUTPUT);
    setLed(false); // Inizia spento
}

void LedIndicator::setLed(bool on) {
    _ledOn = on;
    // Usa gli stati predefiniti in HardwareConfig.h (risolve la differenza tra Active Low e Active High)
    digitalWrite(_pin, on ? HW_LED_ON_STATE : HW_LED_OFF_STATE); 
}

void LedIndicator::setState(LedState state) {
    _currentState = state;
    _stateStartTime = millis();
    _lastToggleTime = millis();
    _blinkCount = 0;
    
    if (state == LED_SUCCESS) {
        setLed(true);
    } else if (state == LED_ERROR || state == LED_CONFIG) {
        setLed(true);
    } else {
        setLed(false);
    }
}

void LedIndicator::loop() {
    if (_pin == -1) return;
    
    unsigned long now = millis();
    
    switch (_currentState) {
        case LED_IDLE:
            // Micro-lampeggio: 50ms acceso, 2950ms spento (Ciclo di 3 secondi)
            if (_ledOn && (now - _lastToggleTime >= 50)) {
                setLed(false);
                _lastToggleTime = now;
            } else if (!_ledOn && (now - _lastToggleTime >= 2950)) {
                setLed(true);
                _lastToggleTime = now;
            }
            break;
            
        case LED_SUCCESS:
            // Acceso fisso per 1000ms
            if (now - _stateStartTime >= 1000) {
                setState(LED_IDLE);
            }
            break;
            
        case LED_ERROR:
            // 3 lampeggi veloci nervosi (50ms ON, 100ms OFF)
            if (_ledOn && (now - _lastToggleTime >= 50)) {
                setLed(false);
                _lastToggleTime = now;
            } else if (!_ledOn && (now - _lastToggleTime >= 100)) {
                _blinkCount++;
                if (_blinkCount >= 3) {
                    setState(LED_IDLE);
                } else {
                    setLed(true);
                    _lastToggleTime = now;
                }
            }
            break;
            
        case LED_CONFIG:
            // Lampeggio continuo molto rapido (100ms ON, 100ms OFF)
            if (now - _lastToggleTime >= 100) {
                setLed(!_ledOn);
                _lastToggleTime = now;
            }
            break;
    }
}
