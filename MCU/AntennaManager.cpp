#include "AntennaManager.h"
#include "HardwareConfig.h"
#include <Preferences.h>

bool AntennaManager::_isExternal = false;

void AntennaManager::init() {
#if HW_SUPPORTS_EXTERNAL_ANTENNA
    // Configura il pin di selezione dello switch RF
    pinMode(HW_ANT_SELECT_PIN, OUTPUT);

    // Carica la configurazione salvata da NVS
    Preferences prefs;
    prefs.begin("apritisedano", false);
    _isExternal = prefs.getBool("ext_ant", false);
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
