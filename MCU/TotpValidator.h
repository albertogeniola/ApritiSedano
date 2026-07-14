#ifndef TOTP_VALIDATOR_H
#define TOTP_VALIDATOR_H

#include <Arduino.h>
#include <stdint.h>
#include <time.h>

class TotpValidator {
public:
    // Inizializza il validatore con il segreto binario
    static void init(const uint8_t* secret, size_t secretLen);

    // Valida un codice TOTP rispetto all'ora di sistema attuale
    // toleranceSteps: tolleranza in intervalli da 30 secondi (es. 1 = +/- 30s)
    static bool validate(uint32_t code, int toleranceSteps = 1);

    // Genera un TOTP per uno specifico timestamp Unix
    static uint32_t generateForTime(time_t unixTime);

    // Generates a 4-byte HMAC signature for the given state and timestamp
    static void signStateBeacon(byte state, uint32_t timestamp, byte* outHmac);
    
    // Nuovo metodo: Verifica la firma HMAC di un comando Time Sync
    static bool verifyTimeSync(uint32_t timestamp, byte* receivedHmac);

private:
    static uint8_t _secret[64];
    static size_t _secretLen;
};

#endif
