#include "LedIndicator.h"
#include "HardwareConfig.h"

// Luminosità ridotta per il Neopixel (0-255). 30 è già molto visibile.
#define BRIGHTNESS 30 

int LedIndicator::_pin = -1;
LedState LedIndicator::_currentState = LED_IDLE;
unsigned long LedIndicator::_stateStartTime = 0;
unsigned long LedIndicator::_lastToggleTime = 0;
bool LedIndicator::_ledOn = false;
int LedIndicator::_blinkCount = 0;

void LedIndicator::init(int pin) {
    _pin = pin;
    setRGB(0, 0, 0); // Spento
}

void LedIndicator::setRGB(uint8_t r, uint8_t g, uint8_t b) {
    if (_pin == -1) return;
    // Scala i valori in base alla luminosità (max 255)
    uint8_t scaledR = (r * BRIGHTNESS) / 255;
    uint8_t scaledG = (g * BRIGHTNESS) / 255;
    uint8_t scaledB = (b * BRIGHTNESS) / 255;
    
#if defined(ESP32)
    // Utilizza la funzione built-in del core ESP32 (3.x) per i LED WS2812
    neopixelWrite(_pin, scaledR, scaledG, scaledB);
#endif
}

void LedIndicator::setState(LedState state) {
    if (_currentState == state) return;
    _currentState = state;
    _stateStartTime = millis();
    _lastToggleTime = millis();
    _blinkCount = 0;
    _ledOn = true;
    
    switch (state) {
        case LED_IDLE:
            setRGB(0, 0, 0); // Nessuna luce
            break;
        case LED_OUT_OF_SYNC:
            setRGB(255, 128, 0); // Arancio acceso iniziale
            break;
        case LED_UNCONFIGURED:
            setRGB(255, 255, 0); // Giallo fisso
            break;
        case LED_SUCCESS:
            setRGB(0, 255, 0); // Verde acceso iniziale
            break;
        case LED_ERROR:
            setRGB(255, 0, 0); // Rosso acceso iniziale
            break;
        case LED_CONFIG:
            setRGB(255, 255, 255); // Bianco acceso iniziale
            break;
    }
}

LedState LedIndicator::getState() {
    return _currentState;
}

void LedIndicator::loop() {
    if (_pin == -1) return;
    unsigned long now = millis();
    
    switch (_currentState) {
        case LED_IDLE:
        case LED_UNCONFIGURED:
            // Nessuna animazione richiesta. IDLE è spento, UNCONFIGURED è giallo fisso.
            break;
            
        case LED_OUT_OF_SYNC:
            // Lampeggio arancione (es. 500ms ON / 500ms OFF)
            if (now - _lastToggleTime >= 500) {
                _ledOn = !_ledOn;
                if (_ledOn) setRGB(255, 128, 0);
                else setRGB(0, 0, 0);
                _lastToggleTime = now;
            }
            break;

        case LED_SUCCESS:
            // Verde lampeggiante: 2 blink da 500ms (500 ON, 500 OFF)
            if (now - _lastToggleTime >= 500) {
                _ledOn = !_ledOn;
                if (_ledOn) {
                    setRGB(0, 255, 0);
                } else {
                    setRGB(0, 0, 0);
                    _blinkCount++;
                }
                _lastToggleTime = now;
                
                // Dopo 2 blink completi (2 spegnimenti)
                if (_blinkCount >= 2) {
                    setState(LED_IDLE); // Torna in idle (spento)
                }
            }
            break;
            
        case LED_ERROR:
            // Rosso lampeggiante: 3 blink da 300ms (300 ON, 300 OFF)
            if (now - _lastToggleTime >= 300) {
                _ledOn = !_ledOn;
                if (_ledOn) {
                    setRGB(255, 0, 0);
                } else {
                    setRGB(0, 0, 0);
                    _blinkCount++;
                }
                _lastToggleTime = now;
                
                // Dopo 3 blink completi (3 spegnimenti)
                if (_blinkCount >= 3) {
                    setState(LED_IDLE); // Torna in idle (spento)
                }
            }
            break;
            
        case LED_CONFIG:
            // Lampeggio bianco rapido (200ms)
            if (now - _lastToggleTime >= 200) {
                _ledOn = !_ledOn;
                if (_ledOn) setRGB(255, 255, 255);
                else setRGB(0, 0, 0);
                _lastToggleTime = now;
            }
            break;
    }
}
