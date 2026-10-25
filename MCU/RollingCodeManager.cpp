#include "RollingCodeManager.h"
#include <Preferences.h>
#include <mbedtls/md.h>
#include <string.h>

PairedClient RollingCodeManager::_clients[RC_MAX_CLIENTS];
volatile bool RollingCodeManager::_slotDirty[RC_MAX_CLIENTS] = {false};

uint16_t RollingCodeManager::_lastBurstClientId = 0;
uint32_t RollingCodeManager::_lastBurstCounter = 0;
uint8_t  RollingCodeManager::_lastBurstCmd = 0;
unsigned long RollingCodeManager::_lastBurstTimeMs = 0;

uint16_t RollingCodeManager::_lastPairedClientId = 0;
uint8_t  RollingCodeManager::_lastPairedSeed[RC_SEED_SIZE] = {0};
unsigned long RollingCodeManager::_lastPairedTimeMs = 0;

void RollingCodeManager::init() {
    memset(_clients, 0, sizeof(_clients));
    for (int i = 0; i < RC_MAX_CLIENTS; i++) {
        _slotDirty[i] = false;
    }

    Preferences prefs;
    if (!prefs.begin("apritisedano", true)) {
        Serial.println("RollingCodeManager: NVS namespace not initialized yet (0 clients).");
        return;
    }

    int count = 0;
    char key[12];
    for (int i = 0; i < RC_MAX_CLIENTS; i++) {
        snprintf(key, sizeof(key), "rc_slot_%d", i);
        if (prefs.getBytesLength(key) == sizeof(PairedClient)) {
            prefs.getBytes(key, &_clients[i], sizeof(PairedClient));
            if (_clients[i].active) {
                count++;
            }
        }
    }
    prefs.end();
    Serial.printf("RollingCodeManager: Loaded %d paired smartphone(s) from NVS.\n", count);
}

void RollingCodeManager::saveSlotToNVS(int slot) {
    if (slot < 0 || slot >= RC_MAX_CLIENTS) return;
    Preferences prefs;
    if (prefs.begin("apritisedano", false)) {
        char key[12];
        snprintf(key, sizeof(key), "rc_slot_%d", slot);
        prefs.putBytes(key, &_clients[slot], sizeof(PairedClient));
        prefs.end();
    }
}

void RollingCodeManager::commitPendingNVS() {
    for (int i = 0; i < RC_MAX_CLIENTS; i++) {
        if (_slotDirty[i]) {
            _slotDirty[i] = false;
            saveSlotToNVS(i);
        }
    }
}

bool RollingCodeManager::hasAnyClient() {
    for (int i = 0; i < RC_MAX_CLIENTS; i++) {
        if (_clients[i].active) return true;
    }
    return false;
}

int RollingCodeManager::getActiveClientCount() {
    int count = 0;
    for (int i = 0; i < RC_MAX_CLIENTS; i++) {
        if (_clients[i].active) count++;
    }
    return count;
}

int RollingCodeManager::findClientSlot(uint16_t clientId) {
    if (clientId == 0) return -1;
    for (int i = 0; i < RC_MAX_CLIENTS; i++) {
        if (_clients[i].active && _clients[i].clientId == clientId) {
            return i;
        }
    }
    return -1;
}

uint32_t RollingCodeManager::computeRollingCode(
    const uint8_t* seed16,
    uint8_t cmdByte,
    const uint8_t* targetMac6,
    uint16_t clientId,
    uint32_t value32
) {
    // Message layout (13 bytes):
    // [0]: cmdByte (1B)
    // [1..6]: targetMac6 (6B)
    // [7..8]: clientId (2B Big-Endian)
    // [9..12]: value32 (4B Big-Endian, counter or timestamp)
    uint8_t msg[13];
    msg[0] = cmdByte;
    memcpy(&msg[1], targetMac6, 6);
    msg[7] = (uint8_t)((clientId >> 8) & 0xFF);
    msg[8] = (uint8_t)(clientId & 0xFF);
    msg[9]  = (uint8_t)((value32 >> 24) & 0xFF);
    msg[10] = (uint8_t)((value32 >> 16) & 0xFF);
    msg[11] = (uint8_t)((value32 >> 8) & 0xFF);
    msg[12] = (uint8_t)(value32 & 0xFF);

    uint8_t hash[20];
    mbedtls_md_context_t ctx;
    mbedtls_md_init(&ctx);
    mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA1), 1);
    mbedtls_md_hmac_starts(&ctx, seed16, RC_SEED_SIZE);
    mbedtls_md_hmac_update(&ctx, msg, sizeof(msg));
    mbedtls_md_hmac_finish(&ctx, hash);
    mbedtls_md_free(&ctx);

    // Dynamic truncation (RFC 4226)
    int offset = hash[19] & 0x0F;
    uint32_t truncated = ((uint32_t)(hash[offset] & 0x7F) << 24) |
                         ((uint32_t)hash[offset + 1] << 16) |
                         ((uint32_t)hash[offset + 2] << 8) |
                         ((uint32_t)hash[offset + 3]);
    return truncated;
}

RollingValidationResult RollingCodeManager::validateCommand(
    uint8_t cmdByte,
    const uint8_t* targetMac6,
    uint16_t clientId,
    uint32_t counter,
    uint32_t receivedCode
) {
    int slot = findClientSlot(clientId);
    if (slot < 0) {
        return RC_ERR_INVALID;
    }

    uint32_t expectedCode = computeRollingCode(_clients[slot].seed, cmdByte, targetMac6, clientId, counter);
    if (expectedCode != receivedCode) {
        return RC_ERR_INVALID;
    }

    unsigned long nowMs = millis();

    // Check if this is a repeated BLE advertisement packet from the same 5-second command burst
    if (_lastBurstTimeMs != 0 &&
        clientId == _lastBurstClientId &&
        counter == _lastBurstCounter &&
        cmdByte == _lastBurstCmd &&
        (nowMs - _lastBurstTimeMs < 5000UL)) {
        return RC_VALID_DUPLICATE;
    }

    uint32_t lastCounter = _clients[slot].lastCounter;

    // Check if the code has already been used (counter <= lastCounter outside the burst)
    if (counter <= lastCounter) {
        return RC_ERR_USED_CODE;
    }

    // Check if the code is within the look-ahead window [lastCounter + 1, lastCounter + RC_WINDOW_SIZE]
    uint64_t maxAllowed = (uint64_t)lastCounter + (uint64_t)RC_WINDOW_SIZE;
    if ((uint64_t)counter > maxAllowed) {
        return RC_ERR_OUT_OF_WINDOW;
    }

    // Valid new rolling code! Advance stored counter and record burst
    _clients[slot].lastCounter = counter;
    _slotDirty[slot] = true;

    _lastBurstClientId = clientId;
    _lastBurstCounter = counter;
    _lastBurstCmd = cmdByte;
    _lastBurstTimeMs = nowMs;

    return RC_VALID_NEW;
}

bool RollingCodeManager::verifyTimeSync(
    const uint8_t* targetMac6,
    uint16_t clientId,
    uint32_t timestamp,
    uint32_t receivedCode
) {
    int slot = findClientSlot(clientId);
    if (slot < 0) return false;

    uint32_t expectedCode = computeRollingCode(_clients[slot].seed, RC_CMD_TIME_SYNC, targetMac6, clientId, timestamp);
    return (expectedCode == receivedCode);
}

bool RollingCodeManager::isDuplicatePairingBurst(uint16_t clientId, const uint8_t* seed16) {
    if (clientId == 0 || _lastPairedTimeMs == 0) return false;
    unsigned long nowMs = millis();
    return (_lastPairedClientId == clientId &&
            memcmp(_lastPairedSeed, seed16, RC_SEED_SIZE) == 0 &&
            (nowMs - _lastPairedTimeMs < 5000UL));
}

PairingRegistrationResult RollingCodeManager::registerClient(uint16_t clientId, const uint8_t* seed16) {
    if (clientId == 0) return RC_PAIR_FULL;

    if (isDuplicatePairingBurst(clientId, seed16)) {
        return RC_PAIR_DUPLICATE;
    }

    unsigned long nowMs = millis();

    // If clientId is already present, update its slot; otherwise pick first inactive slot
    int slot = findClientSlot(clientId);
    if (slot < 0) {
        for (int i = 0; i < RC_MAX_CLIENTS; i++) {
            if (!_clients[i].active) {
                slot = i;
                break;
            }
        }
    }

    if (slot < 0) {
        return RC_PAIR_FULL;
    }

    _clients[slot].active = 1;
    _clients[slot].reserved = 0;
    _clients[slot].clientId = clientId;
    _clients[slot].lastCounter = 0;
    memcpy(_clients[slot].seed, seed16, RC_SEED_SIZE);
    _slotDirty[slot] = true;

    _lastPairedClientId = clientId;
    memcpy(_lastPairedSeed, seed16, RC_SEED_SIZE);
    _lastPairedTimeMs = nowMs;

    return RC_PAIR_SUCCESS;
}

void RollingCodeManager::printClients() {
    Serial.println("\n--- SMARTPHONE ASSOCIATI (ROLLING CODE) ---");
    int count = 0;
    for (int i = 0; i < RC_MAX_CLIENTS; i++) {
        if (_clients[i].active) {
            Serial.printf("Slot %2d | ClientID: 0x%04X (%5u) | Last Counter: %lu\n",
                          i, _clients[i].clientId, _clients[i].clientId, (unsigned long)_clients[i].lastCounter);
            count++;
        }
    }
    if (count == 0) {
        Serial.println("Nessuno smartphone associato.");
    } else {
        Serial.printf("Totale: %d/%d slot occupati (Finestra accettazione: +%d codici).\n",
                      count, RC_MAX_CLIENTS, RC_WINDOW_SIZE);
    }
}

bool RollingCodeManager::removeClientBySlot(int slotIndex) {
    if (slotIndex < 0 || slotIndex >= RC_MAX_CLIENTS) return false;
    if (!_clients[slotIndex].active) return false;

    memset(&_clients[slotIndex], 0, sizeof(PairedClient));
    saveSlotToNVS(slotIndex);
    return true;
}

void RollingCodeManager::clearAllClients() {
    memset(_clients, 0, sizeof(_clients));
    for (int i = 0; i < RC_MAX_CLIENTS; i++) {
        _slotDirty[i] = false;
        saveSlotToNVS(i);
    }
    Serial.println("Tutti gli smartphone associati sono stati rimossi dalla memoria NVS.");
}
