#pragma once
#include <Arduino.h>

class RTCManager {
public:
    // Inizializza il bus I2C e verifica la presenza del modulo DS3231 (indirizzo 0x68)
    static bool init(int sdaPin, int sclPin);
    
    // Legge l'orario dal DS3231 e lo imposta nell'orologio di sistema (RTOS) dell'ESP32
    static bool syncToESP32();
    
    // Scrive un UNIX timestamp (secondi dal 1970) nel modulo DS3231
    static bool setRTC(time_t epoch);
};
