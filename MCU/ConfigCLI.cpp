#include "ConfigCLI.h"
#include "RollingCodeManager.h"
#include "BleHandler.h"
#include <WiFi.h>
#include <sys/time.h>
#include "RTCManager.h"
#include "esp_system.h"
#if CONFIG_IDF_TARGET_ESP32C6
#include "soc/lp_aon_reg.h"
#else
#include "soc/rtc_cntl_reg.h"
#endif

#include "Logger.h"
#include "AntennaManager.h"

void ConfigCLI::init() {
    RollingCodeManager::init();
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
            } else if (input.length() < 128) {
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
    Serial.println("2. Attiva Modalità Accoppiamento BLE (60s per nuovo smartphone)");
    Serial.println("3. Aggiorna orologio RTC manualmente (Timestamp)");
    Serial.println("4. Esci dal menu e avvia sistema");
    Serial.println("5. Riavvia in modalità BOOTLOADER (per flash firmware)");
    Serial.println("6. Leggi lo storico delle operazioni (Logs)");
    Serial.println("7. Svuota lo storico delle operazioni (Clear Logs)");
    Serial.println("8. Mostra informazioni di sistema e memoria (SysInfo)");
    Serial.println("9. Commuta Antenna (Interna / Esterna)");
    Serial.println("10. Gestisci Smartphone Associati (Lista / Rimuovi / Reset)");
    Serial.print("Scelta: ");
}

static void shutdownWiFi() {
    WiFi.scanDelete();
    WiFi.disconnect(true);
    WiFi.mode(WIFI_OFF);
    Serial.println("Wi-Fi disabled.");
}

void ConfigCLI::syncNTP() {
    Serial.println("\nScanning Wi-Fi networks...");
    WiFi.mode(WIFI_STA);
    WiFi.disconnect();
    int n = WiFi.scanNetworks();
    if (n <= 0) {
        Serial.println("No networks found.");
        shutdownWiFi();
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
        shutdownWiFi();
        return;
    }
    String selectedSSID = WiFi.SSID(idx);
    WiFi.scanDelete();

    Serial.print("Enter password (leave empty if open): ");
    String pwd = readString();

    Serial.printf("Connecting to %s...\n", selectedSSID.c_str());
    if (pwd.length() > 0) {
        WiFi.begin(selectedSSID.c_str(), pwd.c_str());
    } else {
        WiFi.begin(selectedSSID.c_str());
    }

    int retries = 0;
    while (WiFi.status() != WL_CONNECTED && retries < 20) {
        delay(500);
        Serial.print(".");
        retries++;
    }
    if (WiFi.status() != WL_CONNECTED) {
        Serial.println("\nFailed to connect.");
        shutdownWiFi();
        return;
    }
    Serial.println("\nConnected! Syncing NTP...");
    configTime(0, 0, "pool.ntp.org", "time.nist.gov");

    struct tm timeinfo;
    if (!getLocalTime(&timeinfo, 10000)) {
        Serial.println("Failed to obtain time.");
    } else {
        Serial.println("Time synchronized successfully!");
        Serial.println(&timeinfo, "%A, %B %d %Y %H:%M:%S");

        time_t now;
        time(&now);
        RTCManager::setRTC(now);
    }
    shutdownWiFi();
}

void ConfigCLI::managePairedClients() {
    RollingCodeManager::printClients();
    Serial.println("\nOpzioni gestione smartphone:");
    Serial.println("  [0..15] Rimuovi lo smartphone nello slot specificato");
    Serial.println("  [ALL]   Cancella TUTTI gli smartphone associati");
    Serial.println("  [Q]     Torna al menu principale");
    Serial.print("Scelta: ");
    String cmd = readString();
    cmd.trim();
    cmd.toUpperCase();
    if (cmd == "Q") {
        return;
    } else if (cmd == "ALL") {
        RollingCodeManager::clearAllClients();
    } else {
        char* endPtr = nullptr;
        long slot = strtol(cmd.c_str(), &endPtr, 10);
        if (endPtr != cmd.c_str() && *endPtr == '\0' && slot >= 0 && slot < RC_MAX_CLIENTS) {
            if (RollingCodeManager::removeClientBySlot((int)slot)) {
                Serial.printf("Slot %ld rimosso con successo.\n", slot);
            } else {
                Serial.printf("Slot %ld è già vuoto.\n", slot);
            }
        } else {
            Serial.println("Scelta non valida.");
        }
    }
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
            Serial.println("Uscita dal menu e avvio Modalità Accoppiamento BLE per 60 secondi...");
            BleHandler::enterPairingMode();
            exitMenu = true;
        } else if (choice == "3") {
            Serial.print("Enter Unix Timestamp: ");
            String tsStr = readString();
            time_t epoch = (time_t)strtoull(tsStr.c_str(), nullptr, 10);
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

#if CONFIG_IDF_TARGET_ESP32C6
            REG_WRITE(LP_AON_SYS_CFG_REG, LP_AON_FORCE_DOWNLOAD_BOOT);
#elif defined(RTC_CNTL_OPTION1_REG) && defined(RTC_CNTL_FORCE_DOWNLOAD_BOOT)
            REG_WRITE(RTC_CNTL_OPTION1_REG, RTC_CNTL_FORCE_DOWNLOAD_BOOT);
#endif
            esp_restart();
        } else if (choice == "6") {
            Logger::printLogs();
        } else if (choice == "7") {
            Logger::clearLogs();
        } else if (choice == "8") {
            Logger::printSysInfo();
        } else if (choice == "9") {
            if (AntennaManager::isSupported()) {
                AntennaState res = AntennaManager::toggle();
                Serial.printf("Nuova configurazione antenna: %s\n", res == ANTENNA_EXTERNAL ? "ESTERNA (U.FL)" : "INTERNA (Ceramica)");
            } else {
                Serial.println("Questa scheda non supporta la commutazione dell'antenna.");
            }
        } else if (choice == "10") {
            managePairedClients();
        } else {
            Serial.println("Invalid choice.");
        }
    }
    Serial.println("Exiting configuration menu. Resuming normal operations.");
}
