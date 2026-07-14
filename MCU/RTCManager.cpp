#include "RTCManager.h"
#include <Wire.h>
#include <time.h>
#include <sys/time.h>

#define DS3231_ADDR 0x68

static uint8_t decToBcd(uint8_t val) { return ((val / 10 * 16) + (val % 10)); }
static uint8_t bcdToDec(uint8_t val) { return ((val / 16 * 10) + (val % 16)); }

bool RTCManager::init(int sdaPin, int sclPin) {
    Wire.begin(sdaPin, sclPin);
    
    // Test connessione
    Wire.beginTransmission(DS3231_ADDR);
    if (Wire.endTransmission() == 0) {
        Serial.println("DS3231 RTC Module detected on I2C bus.");
        return true;
    }
    Serial.println("ERROR: DS3231 RTC Module not found!");
    return false;
}

bool RTCManager::syncToESP32() {
    Wire.beginTransmission(DS3231_ADDR);
    Wire.write(0x00); // Punta al registro 0 (Secondi)
    if (Wire.endTransmission() != 0) return false;
    
    Wire.requestFrom((uint16_t)DS3231_ADDR, (uint8_t)7, true);
    if (Wire.available() < 7) return false;
    
    struct tm t;
    t.tm_sec  = bcdToDec(Wire.read() & 0x7F);
    t.tm_min  = bcdToDec(Wire.read());
    t.tm_hour = bcdToDec(Wire.read() & 0x3F);
    Wire.read(); // Salta day of week
    t.tm_mday = bcdToDec(Wire.read());
    t.tm_mon  = bcdToDec(Wire.read() & 0x1F) - 1; // tm_mon va da 0 a 11
    t.tm_year = bcdToDec(Wire.read()) + 100;      // Anni dal 1900. DS3231 memorizza 00-99. 100 = 2000
    t.tm_isdst = -1;
    
    // Per TOTP ci serve rigorosamente l'ora in formato UTC
    setenv("TZ", "UTC", 1);
    tzset();
    
    time_t epoch = mktime(&t);
    
    // Imposta l'orologio interno dell'ESP32
    struct timeval tv;
    tv.tv_sec = epoch;
    tv.tv_usec = 0;
    settimeofday(&tv, NULL);
    
    Serial.printf("System time synchronized with DS3231 RTC: %lu\n", (unsigned long)epoch);
    return true;
}

bool RTCManager::setRTC(time_t epoch) {
    // Forza il calcolo in UTC
    setenv("TZ", "UTC", 1);
    tzset();
    
    struct tm t;
    gmtime_r(&epoch, &t); // Usa gmtime_r per estrarre le componenti esatte in UTC
    
    Wire.beginTransmission(DS3231_ADDR);
    Wire.write(0x00); // Parti dal registro dei secondi
    Wire.write(decToBcd(t.tm_sec));
    Wire.write(decToBcd(t.tm_min));
    Wire.write(decToBcd(t.tm_hour));
    Wire.write(0x01); // Day of week (non lo usiamo per il calcolo)
    Wire.write(decToBcd(t.tm_mday));
    Wire.write(decToBcd(t.tm_mon + 1));
    Wire.write(decToBcd(t.tm_year - 100));
    
    if (Wire.endTransmission() == 0) {
        Serial.println("DS3231 RTC Time successfully updated.");
        return true;
    }
    Serial.println("ERROR: Failed to update DS3231 RTC Time.");
    return false;
}
