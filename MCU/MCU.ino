#include <Arduino.h>
#include <WiFi.h>
#include "BleHandler.h"
#include "ConfigCLI.h"
#include "LedIndicator.h"
#include "HardwareConfig.h"
#include "RTCManager.h"
#include "BuzzerManager.h"
#include "Logger.h"
#include "RollingCodeManager.h"
#include "AntennaManager.h"
#include <time.h>

// Calcola se una data/ora UTC cade nell'ora legale europea (CEST, UTC+2) o solare (CET, UTC+1)
static bool isItalySummerTime(const struct tm& utc) {
    if (utc.tm_mon < 2 || utc.tm_mon > 9) return false; // Gen, Feb, Nov, Dic -> CET (UTC+1)
    if (utc.tm_mon > 2 && utc.tm_mon < 9) return true;  // Apr..Set -> CEST (UTC+2)

    // Marzo (tm_mon == 2) e Ottobre (tm_mon == 9) hanno 31 giorni.
    // L'ultima domenica cade tra il giorno 25 e il 31: (tm_mday - tm_wday >= 25) indica che
    // siamo nell'ultima domenica o nei giorni successivi.
    int lastSundayCheck = utc.tm_mday - utc.tm_wday;
    if (utc.tm_mon == 2) { // Marzo: cambio alle 01:00 UTC dell'ultima domenica
        if (lastSundayCheck < 25) return false;
        if (utc.tm_wday == 0) return utc.tm_hour >= 1;
        return true;
    } else { // Ottobre: cambio alle 01:00 UTC dell'ultima domenica
        if (lastSundayCheck < 25) return true;
        if (utc.tm_wday == 0) return utc.tm_hour < 1;
        return false;
    }
}

static void getItalyLocalTime(time_t utcNow, struct tm* outItalyTm) {
    struct tm utcTm;
    gmtime_r(&utcNow, &utcTm);
    time_t italyEpoch = utcNow + (isItalySummerTime(utcTm) ? 7200 : 3600);
    gmtime_r(&italyEpoch, outItalyTm);
}

void setup() {
    Serial.begin(115200);

    // Inizializza controllo antenna (interna/esterna)
    AntennaManager::init();

    // OTTIMIZZAZIONE ENERGETICA: Spegniamo il modulo WiFi dato che il sistema è 100% offline
    WiFi.mode(WIFI_OFF);

    // OTTIMIZZAZIONE ENERGETICA: Riduciamo la frequenza della CPU a 80MHz per abbattere i consumi (da ~130mA a ~90mA)
    setCpuFrequencyMhz(80);

    // Inizializza feedback visivo usando le costanti definite in HardwareConfig.h
    LedIndicator::init(HW_LED_PIN);
    LedIndicator::setState(LED_IDLE);

    // Inizializza Modulo RTC DS3231 e sincronizza l'orologio interno dell'ESP32 (per log e riavvio delle 03:00)
    if (RTCManager::init(HW_I2C_SDA, HW_I2C_SCL)) {
        RTCManager::syncToESP32();
    }

    // Carica gli smartphone associati (Rolling Code) da NVS
    ConfigCLI::init();

    // Initialize BLE handler with the pins from HardwareConfig.h
    BleHandler::init(HW_RELAY_PIN, HW_SENSOR_PIN);

    // Inizializzazione Buzzer
    BuzzerManager::init(HW_BUZZER_PIN);

    // Inizializza Sistema di Logging Locale
    Logger::init();

    Serial.println("ApritiSedano System Booted. Waiting for BLE Rolling Code.");

    struct tm timeinfo;
    if (getLocalTime(&timeinfo, 100)) {
        Serial.println(&timeinfo, "Current Time (UTC): %A, %B %d %Y %H:%M:%S");
    } else {
        Serial.println("Current Time: Not synchronized (use 'conf' or App to sync RTC for logs/daily reboot).");
    }

    Serial.println("Hold button for 3s to enter BLE Pairing Mode, or type 'conf' and press Enter for CLI menu.");
}

void loop() {
    // LED Asynchronous Update
    LedIndicator::loop();

    // Buzzer Asynchronous Update
    BuzzerManager::loop();

    // BLE background tasks
    BleHandler::loop();

    // Aggiornamento stato base del LED
    LedState currentState = LedIndicator::getState();
    if (currentState == LED_IDLE || currentState == LED_UNCONFIGURED || currentState == LED_PAIRING) {
        if (BleHandler::isPairingMode()) {
            LedIndicator::setState(LED_PAIRING);
        } else if (!RollingCodeManager::hasAnyClient()) {
            LedIndicator::setState(LED_UNCONFIGURED);
        } else {
            LedIndicator::setState(LED_IDLE);
        }
    }

    // Riavvio giornaliero programmato alle 03:00 (ora italiana CET/CEST) per prevenire accumuli di memoria.
    // Richiede almeno 10 minuti di uptime (600.000 ms) per evitare loop di riavvio nella finestra 03:00-03:04.
    static unsigned long lastRebootCheckMs = 0;
    unsigned long nowMs = millis();
    if (nowMs >= 600000UL && (nowMs - lastRebootCheckMs >= 1000UL)) {
        lastRebootCheckMs = nowMs;
        if (!BleHandler::isBusy()) {
            time_t utcNow = time(nullptr);
            if (utcNow >= 1704067200) {
                struct tm italyTm;
                getItalyLocalTime(utcNow, &italyTm);
                if (italyTm.tm_hour == 3 && italyTm.tm_min < 5) {
                    Serial.println("Scheduled daily 03:00 AM (Italy time) maintenance reboot...");
                    Serial.flush();
                    ESP.restart();
                }
            }
        }
    }

    // Check Serial for 'conf' command
    static String serialInput = "";
    while (Serial.available()) {
        char c = Serial.read();
        if (c == '\n' || c == '\r') {
            if (serialInput == "conf") {
                Serial.println("Configuration Mode Requested.");
                LedIndicator::setState(LED_CONFIG);
                BleHandler::pause();
                ConfigCLI::handle();
                BleHandler::resume();
                if (!BleHandler::isPairingMode()) {
                    LedIndicator::setState(LED_IDLE);
                }
            }
            serialInput = "";
        } else if (serialInput.length() < 32) {
            serialInput += c;
        }
    }

    // OTTIMIZZAZIONE ENERGETICA: Evitiamo che il loop occupi il 100% della CPU, lasciando respirare RTOS
    delay(10);
}
