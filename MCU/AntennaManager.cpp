#include "AntennaManager.h"
#include "HardwareConfig.h"
#include <Preferences.h>

bool AntennaManager::_isExternal = false;

void AntennaManager::init() {
#if HW_SUPPORTS_EXTERNAL_ANTENNA
    #ifdef HW_ANT_ENABLE_PIN
    pinMode(HW_ANT_ENABLE_PIN, OUTPUT);
    digitalWrite(HW_ANT_ENABLE_PIN, LOW); // Attiva lo switch RF integrato (WIFI_ENABLE su XIAO ESP32-C6)
    #endif

    // Configura il pin di selezione dello switch RF
    pinMode(HW_ANT_SELECT_PIN, OUTPUT);

    // Carica (o forza) la configurazione antenna
    Preferences prefs;
    prefs.begin("apritisedano", false);
    #if defined(HW_FORCE_INTERNAL_ANTENNA) && HW_FORCE_INTERNAL_ANTENNA
    _isExternal = false;
    prefs.putBool("ext_ant", false);
    #else
    _isExternal = prefs.getBool("ext_ant", false);
    #endif
    prefs.end();

    applyHardwareState();
    Serial.printf("AntennaManager inizializzato. Modalità attuale: %s\n", _isExternal ? "ESTERNA (U.FL)" : "INTERNA (Ceramica)");
#else
    _isExternal = false;
    Serial.println("AntennaManager: Hardware attuale non supporta la commutazione dell'antenna.");
#endif
}

bool AntennaManager::isSupported() {
#if HW_SUPPORTS_EXTERNAL_ANTENNA
    return true;
#else
    return false;
#endif
}

bool AntennaManager::isExternal() {
    return _isExternal;
}

AntennaState AntennaManager::getState() {
    if (!isSupported()) {
        return ANTENNA_NOT_SUPPORTED;
    }
    return _isExternal ? ANTENNA_EXTERNAL : ANTENNA_INTERNAL;
}

void AntennaManager::applyHardwareState() {
#if HW_SUPPORTS_EXTERNAL_ANTENNA
    #ifdef HW_ANT_ENABLE_PIN
    digitalWrite(HW_ANT_ENABLE_PIN, LOW);
    #endif
    digitalWrite(HW_ANT_SELECT_PIN, _isExternal ? HIGH : LOW);
#endif
}

AntennaState AntennaManager::toggle() {
    if (!isSupported()) {
        Serial.println("AntennaManager::toggle: Operazione fallita - Hardware non supportato.");
        return ANTENNA_NOT_SUPPORTED;
    }

    _isExternal = !_isExternal;
    applyHardwareState();

    Preferences prefs;
    prefs.begin("apritisedano", false);
    prefs.putBool("ext_ant", _isExternal);
    prefs.end();

    Serial.printf("AntennaManager: Switch eseguito con successo -> Antenna %s\n", _isExternal ? "ESTERNA" : "INTERNA");
    return _isExternal ? ANTENNA_EXTERNAL : ANTENNA_INTERNAL;
}

bool AntennaManager::setExternal(bool external) {
    if (!isSupported()) {
        return false;
    }

    _isExternal = external;
    applyHardwareState();

    Preferences prefs;
    prefs.begin("apritisedano", false);
    prefs.putBool("ext_ant", _isExternal);
    prefs.end();

    Serial.printf("AntennaManager: Modalità impostata -> Antenna %s\n", _isExternal ? "ESTERNA" : "INTERNA");
    return true;
}
