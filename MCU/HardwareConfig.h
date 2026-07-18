#pragma once

// ==========================================
// SELEZIONE SCHEDA HARDWARE
// Rimuovi il commento (//) SOLO sulla scheda che stai per programmare.
// ==========================================

//#define BOARD_XIAO_ESP32C6
#define BOARD_ESP32_WROOM32

// ==========================================
// CONFIGURAZIONE PIN AUTOMATICA
// ==========================================

#if defined(BOARD_XIAO_ESP32C6)
    #define HW_RELAY_PIN     1     // Pin D1 fisico
    #define HW_SENSOR_PIN    2     // Pin D2 fisico
    #define HW_LED_PIN       15    // LED giallo integrato
    #define HW_LED_ON_STATE  LOW   // Il LED dello XIAO si accende portando il pin a massa
    #define HW_LED_OFF_STATE HIGH
    #define HW_BUZZER_PIN    3     // Pin D3 fisico (GPIO 3)
    // I2C Pins for DS3231 RTC
    #define HW_I2C_SDA       6     // Pin D4 fisico (GPIO 6)
    #define HW_I2C_SCL       7     // Pin D5 fisico (GPIO 7)
    #define HW_BOOT_PIN      9     // BOOT button for XIAO ESP32C6

#elif defined(BOARD_ESP32_WROOM32)
    // Sostituisci questi pin GPIO con quelli che sceglierai di usare sulla tua dev board ESP32
    #define HW_RELAY_PIN     4     // Esempio: GPIO 4
    #define HW_SENSOR_PIN    5     // Esempio: GPIO 5
    #define HW_LED_PIN       2     // Il LED blu integrato sulla maggior parte delle ESP32-WROOM-32 è sul pin 2
    #define HW_LED_ON_STATE  HIGH  // L'ESP32 standard di solito ha il LED "Active High"
    #define HW_LED_OFF_STATE LOW
    #define HW_BUZZER_PIN    18    // Esempio: GPIO 18 (pin solitamente libero)
    // I2C Pins for DS3231 RTC
    #define HW_I2C_SDA       21    // Default I2C SDA
    #define HW_I2C_SCL       22    // Default I2C SCL
    #define HW_BOOT_PIN      0     // BOOT button on standard WROOM32 dev boards

#else
    #error "Nessuna scheda hardware selezionata in HardwareConfig.h! De-commenta una delle schede in alto."
#endif
