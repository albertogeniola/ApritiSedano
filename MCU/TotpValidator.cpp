#include "TotpValidator.h"
#include <mbedtls/md.h>
#include <string.h>

uint8_t TotpValidator::_secret[64] = {0};
size_t TotpValidator::_secretLen = 0;

void TotpValidator::init(const uint8_t* secret, size_t secretLen) {
    _secretLen = secretLen > 64 ? 64 : secretLen;
    memcpy(_secret, secret, _secretLen);
}

uint32_t TotpValidator::generateForTime(time_t unixTime) {
    if (_secretLen == 0) return 0;

    uint64_t timeStep = unixTime / 30;
    
    // Time step must be big-endian 8 bytes
    uint8_t timeBytes[8];
    for (int i = 7; i >= 0; i--) {
        timeBytes[i] = (uint8_t)(timeStep & 0xFF);
        timeStep >>= 8;
    }

    uint8_t hash[20]; // SHA1 is 20 bytes
    
    mbedtls_md_context_t ctx;
    mbedtls_md_init(&ctx);
    mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA1), 1);
    mbedtls_md_hmac_starts(&ctx, _secret, _secretLen);
    mbedtls_md_hmac_update(&ctx, timeBytes, 8);
    mbedtls_md_hmac_finish(&ctx, hash);
    mbedtls_md_free(&ctx);

    int offset = hash[19] & 0x0F;
    uint32_t truncatedHash = 0;
    for (int i = 0; i < 4; ++i) {
        truncatedHash <<= 8;
        truncatedHash |= hash[offset + i];
    }

    truncatedHash &= 0x7FFFFFFF;
    return truncatedHash % 1000000; // 6 digit TOTP
}

bool TotpValidator::validate(uint32_t code, int toleranceSteps) {
    if (_secretLen == 0) return false;

    time_t now;
    time(&now);

    // Check current window and +/- tolerance
    for (int i = -toleranceSteps; i <= toleranceSteps; i++) {
        time_t checkTime = now + (i * 30);
        uint32_t expectedCode = generateForTime(checkTime);
        if (expectedCode == code) {
            return true;
        }
    }

    return false;
}

void TotpValidator::signStateBeacon(uint8_t state, uint32_t timestamp, uint8_t* outHmac4) {
    if (_secretLen == 0) {
        memset(outHmac4, 0, 4);
        return;
    }
    
    uint8_t payload[5];
    payload[0] = state;
    // Little-endian serialization of timestamp to match Android ByteBuffer logic if configured so, 
    // but Android ByteBuffer putInt is Big-Endian by default. We will use Big-Endian to match standard network order.
    payload[1] = (uint8_t)((timestamp >> 24) & 0xFF);
    payload[2] = (uint8_t)((timestamp >> 16) & 0xFF);
    payload[3] = (uint8_t)((timestamp >> 8) & 0xFF);
    payload[4] = (uint8_t)(timestamp & 0xFF);

    uint8_t hash[20];
    mbedtls_md_context_t ctx;
    mbedtls_md_init(&ctx);
    mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA1), 1);
    mbedtls_md_hmac_starts(&ctx, _secret, _secretLen);
    mbedtls_md_hmac_update(&ctx, payload, 5);
    mbedtls_md_hmac_finish(&ctx, hash);
    mbedtls_md_free(&ctx);

    // Truncate to first 4 bytes
    memcpy(outHmac4, hash, 4);
}

bool TotpValidator::verifyTimeSync(uint32_t timestamp, uint8_t* receivedHmac) {
    if (_secretLen == 0) return false;

    // Payload: [0xFF] [Timestamp (4 bytes Big-Endian)]
    uint8_t payload[5];
    payload[0] = 0xFF;
    payload[1] = (timestamp >> 24) & 0xFF;
    payload[2] = (timestamp >> 16) & 0xFF;
    payload[3] = (timestamp >> 8) & 0xFF;
    payload[4] = timestamp & 0xFF;

    mbedtls_md_context_t ctx;
    mbedtls_md_type_t md_type = MBEDTLS_MD_SHA1;
    mbedtls_md_init(&ctx);
    mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(md_type), 1);
    mbedtls_md_hmac_starts(&ctx, _secret, _secretLen);
    mbedtls_md_hmac_update(&ctx, payload, 5);
    
    uint8_t result[20];
    mbedtls_md_hmac_finish(&ctx, result);
    mbedtls_md_free(&ctx);

    // Compare first 4 bytes
    for (int i = 0; i < 4; i++) {
        if (result[i] != receivedHmac[i]) {
            return false;
        }
    }
    return true;
}
