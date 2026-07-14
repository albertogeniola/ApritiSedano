#include "ConfigCLI.h"
#include "TotpValidator.h"
#include <WiFi.h>
#include <Preferences.h>
#include <mbedtls/base64.h>
#include <sys/time.h>
#include "RTCManager.h"
#include "esp_system.h"
#if CONFIG_IDF_TARGET_ESP32C6
#include "soc/lp_aon_reg.h"
#else
#include "soc/rtc_cntl_reg.h"
#endif

#include "Logger.h"

Preferences preferences;

void ConfigCLI::init() {
    preferences.begin("apritisedano", false);
    loadSecret();
}

static const char* b32chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

static int decodeBase32Char(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a';
    if (c >= '2' && c <= '7') return c - '2' + 26;
    return -1;
}

static size_t decodeBase32(const char* input, uint8_t* output) {
    int buffer = 0;
    int bitsLeft = 0;
    size_t count = 0;
    for (const char* ptr = input; *ptr; ++ptr) {
        if (*ptr == '=' || *ptr == ' ' || *ptr == '-') continue;
        int val = decodeBase32Char(*ptr);
        if (val < 0) return 0; // error
        buffer = (buffer << 5) | val;
        bitsLeft += 5;
        if (bitsLeft >= 8) {
            output[count++] = (uint8_t)(buffer >> (bitsLeft - 8));
            bitsLeft -= 8;
        }
    }
    return count;
}

static String encodeBase32(const uint8_t* data, size_t length) {
    String out = "";
    int buffer = 0;
    int bitsLeft = 0;
    for (size_t i = 0; i < length; i++) {
        buffer = (buffer << 8) | data[i];
        bitsLeft += 8;
        while (bitsLeft >= 5) {
            out += b32chars[(buffer >> (bitsLeft - 5)) & 0x1F];
            bitsLeft -= 5;
        }
    }
    if (bitsLeft > 0) {
        out += b32chars[(buffer << (5 - bitsLeft)) & 0x1F];
    }
    while (out.length() % 8 != 0) {
        out += '=';
    }
    return out;
}

void ConfigCLI::loadSecret() {
    size_t len = preferences.getBytesLength("totp_sec");
    if (len > 0) {
        uint8_t* buffer = new uint8_t[len];
        preferences.getBytes("totp_sec", buffer, len);
        TotpValidator::init(buffer, len);
        
        // Codifica i byte caricati in Base32 per la stampa a video
        String b32Str = encodeBase32(buffer, len);
        Serial.printf("Secret loaded from NVS (Base32): %s\n", b32Str.c_str());
        
        delete[] buffer;
    } else {
        Serial.println("No TOTP secret found in NVS.");
    }
}

String ConfigCLI::readString() {
    String input = "";
    while (true) {
        if (Serial.available()) {
            char c = Serial.read();
            if (c == '\n' || c == '\r') {
                if (input.length() > 0) {
                    break;
                }
            } else {
                input += c;
            }
        }
        delay(10);
    }
    return input;
}

void ConfigCLI::showMenu() {
    Serial.println("\n--- MENU DI CONFIGURAZIONE ---");
    Serial.println("1. Sincronizza orologio via Wi-Fi (NTP)");
    Serial.println("2. Imposta PassPhrase/Secret TOTP (Base32)");
    Serial.println("3. Aggiorna orologio di sistema manualmente (Timestamp)");
    Serial.println("4. Esci dal menu e avvia sistema");
    Serial.println("5. Riavvia in modalità BOOTLOADER (per flash firmware)");
    Serial.println("6. Leggi lo storico delle operazioni (Logs)");
    Serial.println("7. Svuota lo storico delle operazioni (Clear Logs)");
    Serial.println("8. Mostra informazioni di sistema e memoria (SysInfo)");
    Serial.print("Scelta: ");
}

void ConfigCLI::syncNTP() {
    Serial.println("\nScanning Wi-Fi networks...");
    WiFi.mode(WIFI_STA);
    WiFi.disconnect();
    int n = WiFi.scanNetworks();
    if (n == 0) {
        Serial.println("No networks found.");
        return;
    }
    for (int i = 0; i < n; ++i) {
        Serial.printf("%d: %s (%d dBm)\n", i, WiFi.SSID(i).c_str(), WiFi.RSSI(i));
    }
    Serial.print("Select network index: ");
    String idxStr = readString();
    int idx = idxStr.toInt();
    if (idx < 0 || idx >= n) {
        Serial.println("Invalid index.");
        return;
    }
    Serial.print("Enter password (leave empty if open): ");
    String pwd = readString();
    
    Serial.printf("Connecting to %s...\n", WiFi.SSID(idx).c_str());
    if (pwd.length() > 0) {
        WiFi.begin(WiFi.SSID(idx).c_str(), pwd.c_str());
    } else {
        WiFi.begin(WiFi.SSID(idx).c_str());
    }
    
    int retries = 0;
    while (WiFi.status() != WL_CONNECTED && retries < 20) {
        delay(500);
        Serial.print(".");
        retries++;
    }
    if (WiFi.status() != WL_CONNECTED) {
        Serial.println("\nFailed to connect.");
        return;
    }
    Serial.println("\nConnected! Syncing NTP...");
    configTime(0, 0, "pool.ntp.org", "time.nist.gov");
    
    struct tm timeinfo;
    if(!getLocalTime(&timeinfo, 10000)){
        Serial.println("Failed to obtain time.");
    } else {
        Serial.println("Time synchronized successfully!");
        Serial.println(&timeinfo, "%A, %B %d %Y %H:%M:%S");
        
        // Salva nel modulo RTC
        time_t now;
        time(&now);
        RTCManager::setRTC(now);
    }
    WiFi.disconnect(true);
    WiFi.mode(WIFI_OFF);
    Serial.println("Wi-Fi disabled.");
}

void ConfigCLI::setSecret() {
    Serial.print("\nEnter TOTP Secret in Base32: ");
    String b32 = readString();
    b32.toUpperCase(); // Ensure it's uppercase for decoding
    
    // Max size of decoded is roughly length * 5 / 8
    size_t maxLen = (b32.length() * 5) / 8 + 1;
    uint8_t* decoded = new uint8_t[maxLen];
    
    size_t actualLen = decodeBase32(b32.c_str(), decoded);
    
    if (actualLen > 0) {
        preferences.putBytes("totp_sec", decoded, actualLen);
        TotpValidator::init(decoded, actualLen);
        Serial.println("Secret successfully saved and loaded.");
    } else {
        Serial.println("Failed to decode Base32. Invalid string.");
    }
    delete[] decoded;
}

void ConfigCLI::handle() {
    bool exitMenu = false;
    while (!exitMenu) {
        showMenu();
        String choice = readString();
        Serial.println(choice);
        if (choice == "1") {
            syncNTP();
        } else if (choice == "2") {
            setSecret();
        } else if (choice == "3") {
            Serial.print("Enter Unix Timestamp: ");
            String tsStr = readString();
            time_t epoch = (time_t) tsStr.toInt();
            if (epoch > 0) {
                struct timeval tv;
                tv.tv_sec = epoch;
                tv.tv_usec = 0;
                settimeofday(&tv, NULL);
                RTCManager::setRTC(epoch);
                Serial.println("Manual Time and RTC updated successfully.");
            } else {
                Serial.println("Invalid timestamp.");
            }
        } else if (choice == "4") {
            exitMenu = true;
        } else if (choice == "5") {
            Serial.println("\nRiavvio in BOOTLOADER (Download Mode) in corso...");
            Serial.println("Dovrai premere RESET o spegnere/riaccendere la scheda per uscire da questa modalità.");
            Serial.flush();
            delay(500);

            // Scrittura del flag nel registro hardware per forzare il Download Mode al prossimo riavvio
#if CONFIG_IDF_TARGET_ESP32C6
            REG_WRITE(LP_AON_SYS_CFG_REG, LP_AON_FORCE_DOWNLOAD_BOOT);
#elif defined(RTC_CNTL_OPTION1_REG) && defined(RTC_CNTL_FORCE_DOWNLOAD_BOOT)
            REG_WRITE(RTC_CNTL_OPTION1_REG, RTC_CNTL_FORCE_DOWNLOAD_BOOT);
#endif
            // Riavvio
            esp_restart();
        } else if (choice == "6") {
            Logger::printLogs();
        } else if (choice == "7") {
            Logger::clearLogs();
        } else if (choice == "8") {
            Logger::printSysInfo();
        } else {
            Serial.println("Invalid choice.");
        }
    }
    Serial.println("Exiting configuration menu. Resuming normal operations.");
}
