#include "Logger.h"
#include <LittleFS.h>
#include <time.h>

#define MAX_LOG_SIZE 50000 // 50 KB

void Logger::init() {
    // Mount LittleFS. If it fails, format and mount.
    if (!LittleFS.begin(true)) {
        Serial.println("LittleFS Mount Failed. Formatted successfully.");
    } else {
        Serial.println("LittleFS Mounted successfully.");
    }
}

void Logger::logOperation(const char* operation) {
    // Check file size and rotate if needed
    File file = LittleFS.open("/logs.txt", "a");
    if (!file) {
        Serial.println("Failed to open file for appending");
        return;
    }
    
    if (file.size() > MAX_LOG_SIZE) {
        file.close();
        Serial.println("Log file reached max size, rotating...");
        LittleFS.remove("/logs.bak");
        LittleFS.rename("/logs.txt", "/logs.bak");
        
        file = LittleFS.open("/logs.txt", "a");
        if (!file) {
            Serial.println("Failed to open new log file");
            return;
        }
    }
    
    // Get current time
    time_t now;
    struct tm timeinfo;
    time(&now);
    char timeStr[64];
    
    if (getLocalTime(&timeinfo, 100)) {
        strftime(timeStr, sizeof(timeStr), "[%Y-%m-%d %H:%M:%S]", &timeinfo);
    } else {
        strcpy(timeStr, "[No Time Sync]");
    }
    
    // Write log entry
    file.printf("%s Operazione: %s\n", timeStr, operation);
    file.close();
    Serial.printf("Logged: %s Operazione: %s\n", timeStr, operation);
}

void Logger::printLogs() {
    Serial.println("--- INIZIO LOGS ---");
    
    // Print backup logs first if they exist
    if (LittleFS.exists("/logs.bak")) {
        File fileBak = LittleFS.open("/logs.bak", "r");
        if (fileBak) {
            while (fileBak.available()) {
                Serial.write(fileBak.read());
            }
            fileBak.close();
        }
    }
    
    // Print current logs
    if (LittleFS.exists("/logs.txt")) {
        File fileTxt = LittleFS.open("/logs.txt", "r");
        if (fileTxt) {
            while (fileTxt.available()) {
                Serial.write(fileTxt.read());
            }
            fileTxt.close();
        }
    } else {
        Serial.println("(Nessun log presente)");
    }
    
    Serial.println("--- FINE LOGS ---");
}

void Logger::clearLogs() {
    LittleFS.remove("/logs.txt");
    LittleFS.remove("/logs.bak");
    Serial.println("Log files cleared successfully.");
}

void Logger::printSysInfo() {
    size_t totalBytes = LittleFS.totalBytes();
    size_t usedBytes = LittleFS.usedBytes();
    
    Serial.println("--- SYSTEM INFO ---");
    Serial.printf("Flash Chip Size: %d Bytes\n", ESP.getFlashChipSize());
    Serial.printf("LittleFS Total Space: %d Bytes\n", totalBytes);
    Serial.printf("LittleFS Used Space: %d Bytes\n", usedBytes);
    Serial.printf("LittleFS Free Space: %d Bytes\n", totalBytes - usedBytes);
    Serial.println("-------------------");
}
