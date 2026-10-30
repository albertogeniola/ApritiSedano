#include "ConfigCLI.h"
#include "RollingCodeManager.h"
#include "BleHandler.h"
#include "LedIndicator.h"
#include "BuzzerManager.h"
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
    Serial.println("1. Configura Master App per OTA (Identificazione Live - Relè Disabilitato)");
    Serial.println("2. Attiva Modalità Accoppiamento BLE (60s per nuovo smartphone)");
    Serial.println("3. Aggiorna orologio RTC manualmente (Timestamp)");
    Serial.println("4. Esci dal menu e avvia sistema");
    Serial.println("5. Riavvia in modalità BOOTLOADER (per flash firmware via USB)");
    Serial.println("6. Leggi lo storico delle operazioni (Logs)");
    Serial.println("7. Svuota lo storico delle operazioni (Clear Logs)");
    Serial.println("8. Mostra informazioni di sistema, partizioni OTA e memoria (SysInfo)");
    Serial.println("9. Commuta Antenna (Interna / Esterna)");
    Serial.println("10. Gestisci Smartphone Associati (Lista / Rimuovi / Reset)");
    Serial.print("Scelta: ");
}

void ConfigCLI::configureMasterClient() {
    Serial.println("\n==========================================================================");
    Serial.println(" CONFIGURAZIONE MASTER APP PER OTA (MODALITA' IDENTIFICAZIONE LIVE)");
    Serial.println("==========================================================================");
    Serial.println("[SICUREZZA - RELE' DISABILITATO]");
    Serial.println("Finché ti trovi in questo sottomenu, l'azionamento fisico del relè è");
    Serial.println("COMPLETAMENTE DISABILITATO: premere APRI o CHIUDI sull'app NON azionerà");
    Serial.println("mai il relè del cancello/porta!");
    Serial.println("In questa modalità l'azione APRI / CHIUDI viene usata esclusivamente per");
    Serial.println("aggiornare l'ultimo evento osservato e identificare il seed dell'app.");
    Serial.println("");
    Serial.println("ISTRUZIONI:");
    Serial.println(" - Premi APRI / CHIUDI sull'app per vedere quale slot si aggiorna qui sotto.");
    Serial.println(" - Digita [0..15] + Invio per impostare quello slot come MASTER APP (OTA).");
    Serial.println(" - Digita [NONE]  + Invio per revocare il ruolo Master da tutti gli slot.");
    Serial.println(" - Digita [L]     + Invio per ristampare la tabella aggiornata.");
    Serial.println(" - Digita [Q]     + Invio per uscire e tornare al menu principale.");
    Serial.println("==========================================================================");

    RollingCodeManager::consumeCliEventUpdate();
    RollingCodeManager::printClientsWithEvents();

    // Attiva ascolto BLE con interblocco di sicurezza sul relè
    BleHandler::setIdentifyOnlyMode(true);
    BleHandler::resume();

    String input = "";
    bool exitSubmenu = false;
    Serial.print("\nComando Master ([0..15] / NONE / L / Q): ");

    while (!exitSubmenu) {
        BleHandler::loop();
        LedIndicator::loop();
        BuzzerManager::loop();

        if (RollingCodeManager::consumeCliEventUpdate()) {
            Serial.println("\n\n>>> [EVENTO RILEVATO DALL'APP - RELE' NON AZIONATO] <<<");
            RollingCodeManager::printClientsWithEvents();
            Serial.print("\nComando Master ([0..15] / NONE / L / Q): ");
            if (input.length() > 0) {
                Serial.print(input);
            }
        }

        while (Serial.available()) {
            char c = Serial.read();
            if (c == '\n' || c == '\r') {
                if (input.length() > 0) {
                    String cmd = input;
                    input = "";
                    cmd.trim();
                    cmd.toUpperCase();
                    Serial.println(cmd);

                    if (cmd == "Q") {
                        exitSubmenu = true;
                        break;
                    } else if (cmd == "L") {
                        RollingCodeManager::printClientsWithEvents();
                    } else if (cmd == "NONE") {
                        RollingCodeManager::setMasterSlot(-1);
                        Logger::logOperation("MASTER_OTA_REVOCATO");
                        Serial.println(">>> Ruolo Master OTA revocato da tutti gli slot. <<<");
                        // Aggiorna subito il beacon BLE così l'app nasconde la voce OTA
                        BleHandler::resume();
                        RollingCodeManager::printClientsWithEvents();
                    } else {
                        char* endPtr = nullptr;
                        long slot = strtol(cmd.c_str(), &endPtr, 10);
                        if (endPtr != cmd.c_str() && *endPtr == '\0' && slot >= 0 && slot < RC_MAX_CLIENTS) {
                            if (RollingCodeManager::setMasterSlot((int)slot)) {
                                char logMsg[32];
                                snprintf(logMsg, sizeof(logMsg), "MASTER_OTA_SLOT_%ld", slot);
                                Logger::logOperation(logMsg);
                                Serial.printf(">>> Slot %ld configurato con successo come MASTER APP per OTA! <<<\n", slot);
                                // Aggiorna subito il MasterTag nel beacon BLE
                                BleHandler::resume();
                                RollingCodeManager::printClientsWithEvents();
                            } else {
                                Serial.printf("Errore: Lo slot %ld è vuoto. Seleziona uno slot attivo.\n", slot);
                            }
                        } else {
                            Serial.println("Comando non valido. Usa [0..15], NONE, L oppure Q.");
                        }
                    }

                    if (!exitSubmenu) {
                        Serial.print("\nComando Master ([0..15] / NONE / L / Q): ");
                    }
                }
            } else if (c == '\b' || c == 127) {
                if (input.length() > 0) {
                    input.remove(input.length() - 1);
                }
            } else if (input.length() < 32) {
                input += c;
            }
        }

        delay(10);
    }

    // Ripristina stato di pausa BLE del menu CLI e rimuove il flag di identificazione
    BleHandler::pause();
    BleHandler::setIdentifyOnlyMode(false);
    LedIndicator::setState(LED_CONFIG);
    Serial.println("Uscita dalla modalità identificazione Master.");
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
            configureMasterClient();
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
