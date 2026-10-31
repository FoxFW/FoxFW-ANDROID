#pragma once
// Portable (host/NDK) copy of Flipper-ARF hitag2_seed.h — Renault V1 "classic Hitag2
// + 4-byte SEED" model, ported bit-for-bit from ProtoPirate renault_v1.c.
// Only change vs the firmware header: dropped `#include <furi.h>` (the code uses
// just stdint/stdbool/stddef). Public API is unchanged.
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define HITAG2_SEED_RECOVERED_NO      0U
#define HITAG2_SEED_RECOVERED_YES     1U
#define HITAG2_SEED_RECOVERED_BF_MISS 2U

// 18-bit seed search space (matches ProtoPirate HITAG2_BF_CANDIDATES = 0x40000).
#define HITAG2_SEED_BF_CANDIDATES 0x40000U
#define HITAG2_SEED_BF_YIELD_STEP 0x1000U

void hitag2_seed_serial_permute(const uint8_t serial[4], uint8_t perm[6]);
uint8_t hitag2_seed_filter(const uint8_t state[6]);
uint8_t hitag2_seed_feedback(const uint8_t state[6]);
void hitag2_seed_shift_state(uint8_t state[6], uint8_t input);
void hitag2_seed_shift_u32(uint8_t buf[4], uint8_t inject);
void hitag2_seed_clock_cipher(uint8_t state[6], uint8_t iv_work[4], uint8_t iv_orig[4]);
uint8_t hitag2_seed_frame_xor(const uint8_t raw[11]);

void hitag2_seed_build_iv(uint32_t cnt, uint8_t btn, uint32_t seed, uint8_t iv[4]);
uint32_t hitag2_seed_from_iv(const uint8_t iv[4]);
void hitag2_seed_encrypt_from_iv(const uint8_t serial_be[4], const uint8_t iv[4], uint8_t out[11]);
void hitag2_seed_encrypt_frame(
    uint32_t serial, uint32_t cnt, uint8_t btn, uint32_t seed, uint8_t out[11], uint8_t iv[4]);

typedef bool (*Hitag2SeedProgressCallback)(uint8_t progress, uint32_t cand_tested, void* context);

bool hitag2_seed_recover(const uint8_t frame[11], uint8_t iv_out[4]);
bool hitag2_seed_recover_ex(
    const uint8_t frame[11], uint8_t iv_out[4],
    Hitag2SeedProgressCallback progress_cb, void* progress_ctx);

#ifdef __cplusplus
}
#endif
