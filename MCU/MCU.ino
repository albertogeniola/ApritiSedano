#include <Arduino.h>
#include <WiFi.h>
#include "BleHandler.h"
#include "ConfigCLI.h"
#include "LedIndicator.h"
#include "HardwareConfig.h"
#include "RTCManager.h"
#include "BuzzerManager.h"
#include "Logger.h"

void setup() {
    Serial.begin(115200);
    
    // OTTIMIZZAZIONE ENERGETICA: Spegniamo il modulo WiFi dato che il sistema è 100% offline
    WiFi.mode(WIFI_OFF);
    
    // OTTIMIZZAZIONE ENERGETICA: Riduciamo la frequenza della CPU a 80MHz per abbattere i consumi (da ~130mA a ~90mA)
    setCpuFrequencyMhz(80);
    
    // Inizializza feedback visivo usando le costanti definite in HardwareConfig.h
    LedIndicator::init(HW_LED_PIN);
    LedIndicator::setState(LED_IDLE);

    // Inizializza Modulo RTC DS3231 e sincronizza l'orologio interno dell'ESP32
    if (RTCManager::init(HW_I2C_SDA, HW_I2C_SCL)) {
        RTCManager::syncToESP32();
    }

    // Initialize Preferences and load TOTP secret
    ConfigCLI::init();
    
    // Initialize BLE handler with the pins from HardwareConfig.h
    BleHandler::init(HW_RELAY_PIN, HW_SENSOR_PIN);
    
    // Inizializzazione Buzzer
    BuzzerManager::init(HW_BUZZER_PIN);
    
    // Inizializza Sistema di Logging Locale
    Logger::init();
    
    Serial.println("ApritiSedano System Booted. Waiting for BLE TOTP.");
    
    struct tm timeinfo;
    if (getLocalTime(&timeinfo, 100)) {
        Serial.println(&timeinfo, "Current Time: %A, %B %d %Y %H:%M:%S");
    } else {
        Serial.println("Current Time: Not synchronized (use 'conf' to sync via NTP).");
    }
    
    Serial.println("Type 'conf' and press Enter to access configuration menu.");
}

void loop() {
    // LED Asynchronous Update
    LedIndicator::loop();

    // Buzzer Asynchronous Update
    BuzzerManager::loop();

    // BLE background tasks (es. timeout dell'ACK)
    BleHandler::loop();
    
    // Check Serial for 'conf' command
    static String serialInput = "";
    while (Serial.available()) {
        char c = Serial.read();
        if (c == '\n' || c == '\r') {
            if (serialInput == "conf") {
                Serial.println("Configuration Mode Requested.");
                LedIndicator::setState(LED_CONFIG); // Fast blinking for config
                BleHandler::pause();
                ConfigCLI::handle();
                BleHandler::resume();
                LedIndicator::setState(LED_IDLE);   // Back to idle
            }
            serialInput = ""; // Clear input after processing
        } else {
            serialInput += c;
        }
    }
    
    // OTTIMIZZAZIONE ENERGETICA: Evitiamo che il loop occupi il 100% della CPU, lasciando respirare RTOS
    delay(10);
}
