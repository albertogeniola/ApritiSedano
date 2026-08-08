#pragma once

// ==========================================
// SELEZIONE SCHEDA HARDWARE
// Rimuovi il commento (//) SOLO sulla scheda che stai per programmare.
// ==========================================

#define BOARD_XIAO_ESP32C6
//#define BOARD_ESP32_WROOM32

// ==========================================
// CONFIGURAZIONE PIN AUTOMATICA
// ==========================================

#if defined(BOARD_XIAO_ESP32C6)
    // Usa le macro D0, D1, D2 ecc. definite dal BSP Arduino per XIAO ESP32-C6
    #define HW_RELAY_PIN        D1          // Morsetto D1 -> GPIO 1
    
    // Stato del segnale Relè:
    // - Per moduli relè commerciali separati (Active-LOW, senza transistor sul PCB): ON=LOW, OFF=HIGH
    // - Per PCB definitivo con transistor NPN Q1 (Active-HIGH): ON=HIGH, OFF=LOW
    #define HW_RELAY_ON_STATE   LOW   
    #define HW_RELAY_OFF_STATE  HIGH

    #define HW_SENSOR_PIN       D2          // Morsetto D2 -> GPIO 2
    #define HW_LED_PIN          LED_BUILTIN // LED integrato sulla scheda -> GPIO 15
    #define HW_LED_ON_STATE     LOW   
    #define HW_LED_OFF_STATE    HIGH
    #define HW_BUZZER_PIN       D3          // Morsetto D3 -> GPIO 21
    // I2C Pins per DS3231 RTC
    #define HW_I2C_SDA          D4          // Morsetto D4 -> GPIO 22 (I2C SDA hardware)
    #define HW_I2C_SCL          D5          // Morsetto D5 -> GPIO 23 (I2C SCL hardware)
    #define HW_BOOT_PIN         9           // Pulsante BOOT sulla scheda -> GPIO 9

#elif defined(BOARD_ESP32_WROOM32)
    // Sostituisci questi pin GPIO con quelli che sceglierai di usare sulla tua dev board ESP32
    #define HW_RELAY_PIN        D4    // Esempio: GPIO 4
    #define HW_RELAY_ON_STATE   HIGH  
    #define HW_RELAY_OFF_STATE  LOW
    #define HW_SENSOR_PIN       D5    // Esempio: GPIO 5
    #define HW_LED_PIN          D2    // Il LED blu integrato sulla maggior parte delle ESP32-WROOM-32 è sul pin D2 (GPIO 2)
    #define HW_LED_ON_STATE     HIGH  // L'ESP32 standard di solito ha il LED "Active High"
    #define HW_LED_OFF_STATE    LOW
    #define HW_BUZZER_PIN       D18   // Esempio: GPIO 18 (pin solitamente libero)
    // I2C Pins for DS3231 RTC
    #define HW_I2C_SDA          D21   // Default I2C SDA (GPIO 21)
    #define HW_I2C_SCL          D22   // Default I2C SCL (GPIO 22)
    #define HW_BOOT_PIN         D0    // BOOT button on standard WROOM32 dev boards (GPIO 0)

#else
    #error "Nessuna scheda hardware selezionata in HardwareConfig.h! De-commenta una delle schede in alto."
#endif
