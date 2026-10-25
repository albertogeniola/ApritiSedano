#ifndef ROLLING_CODE_MANAGER_H
#define ROLLING_CODE_MANAGER_H

#include <Arduino.h>
#include <stdint.h>
#include <stddef.h>

#define RC_MAX_CLIENTS 16
#define RC_SEED_SIZE   16
#define RC_WINDOW_SIZE 50

// Command bytes in Manufacturer Data payload after [0xE5, 0x02]
#define RC_CMD_TRIGGER       0x01
#define RC_CMD_ENTER_PAIRING 0x02
#define RC_CMD_REGISTER_SEED 0xFE
#define RC_CMD_TIME_SYNC     0xFF

struct PairedClient {
    uint8_t active;
    uint8_t reserved;
    uint16_t clientId;
    uint32_t lastCounter;
    uint8_t seed[RC_SEED_SIZE];
};

enum RollingValidationResult {
    RC_VALID_NEW = 0,        // Code is authentic and within [lastCounter + 1, lastCounter + RC_WINDOW_SIZE]
    RC_VALID_DUPLICATE = 1,  // Code is a duplicate packet from the currently active 5s burst
    RC_ERR_USED_CODE = 2,    // Code is authentic for this client, but counter <= lastCounter (already used)
    RC_ERR_OUT_OF_WINDOW = 3,// Code is authentic for this client, but counter > lastCounter + RC_WINDOW_SIZE
    RC_ERR_INVALID = 4       // Unknown clientId or invalid HMAC signature
};

enum PairingRegistrationResult {
    RC_PAIR_SUCCESS = 0,
    RC_PAIR_DUPLICATE = 1,
    RC_PAIR_FULL = 2
};

class RollingCodeManager {
public:
    static void init();
    static void commitPendingNVS();

    static bool hasAnyClient();
    static int getActiveClientCount();

    // Validates a rolling-code command (0x01 = Trigger, 0x02 = Enter Pairing Mode)
    static RollingValidationResult validateCommand(
        uint8_t cmdByte,
        const uint8_t* targetMac6,
        uint16_t clientId,
        uint32_t counter,
        uint32_t receivedCode
    );

    // Validates a signed RTC Time Sync packet (0xFF) from an enrolled client
    static bool verifyTimeSync(
        const uint8_t* targetMac6,
        uint16_t clientId,
        uint32_t timestamp,
        uint32_t receivedCode
    );

    // Registers a new smartphone seed during Pairing Mode
    static PairingRegistrationResult registerClient(uint16_t clientId, const uint8_t* seed16);

    // Checks if a registration packet is a duplicate of a client paired within the last 5s
    static bool isDuplicatePairingBurst(uint16_t clientId, const uint8_t* seed16);

    // CLI management helpers
    static void printClients();
    static bool removeClientBySlot(int slotIndex);
    static void clearAllClients();

    static uint32_t computeRollingCode(
        const uint8_t* seed16,
        uint8_t cmdByte,
        const uint8_t* targetMac6,
        uint16_t clientId,
        uint32_t value32
    );

private:
    static PairedClient _clients[RC_MAX_CLIENTS];
    static volatile bool _slotDirty[RC_MAX_CLIENTS];

    // Burst deduplication state for commands
    static uint16_t _lastBurstClientId;
    static uint32_t _lastBurstCounter;
    static uint8_t  _lastBurstCmd;
    static unsigned long _lastBurstTimeMs;

    // Burst deduplication state for pairing registration
    static uint16_t _lastPairedClientId;
    static uint8_t  _lastPairedSeed[RC_SEED_SIZE];
    static unsigned long _lastPairedTimeMs;

    static int findClientSlot(uint16_t clientId);
    static void saveSlotToNVS(int slot);
};

#endif
