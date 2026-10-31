/* =====================================================================
 * pa_rf.h - portable RF-bridge logic extracted from PandoraARM main.c
 *
 * Pure C, no FreeRTOS / Furi / ESP-IDF dependencies. These are the
 * app-side pieces of the Flipper PandoraARM RF bridge that port verbatim
 * to the T-Embed CC1101+:
 *   - Si4432 synth registers -> carrier Hz (the firmware programs REG 0x75-77)
 *   - CC1101 supported-band check
 *   - the RSSI-independent glitch-filter + pulse-pattern gate state machine
 *     (the key noise-rejection win; opens the decode feed only on a clean
 *      run of VAG-width pulses, closes on a run of sub-250us noise).
 *
 * On the Flipper this lived inside sg_worker_thread with a FuriStreamBuffer
 * ring; here it is a plain state machine the ESP32 GDO0 edge-capture ISR ->
 * worker feeds one edge at a time, and host tests can drive directly.
 * ===================================================================== */
#ifndef PA_RF_H
#define PA_RF_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Si4432 synthesiser ---- */

/* REG[0x75]=band/fb+hbsel, REG[0x76..0x77]=fc -> carrier frequency in Hz.
 * Verbatim from main.c si4432_regs_to_hz. */
uint32_t pa_si4432_regs_to_hz(uint8_t reg75, uint8_t reg76, uint8_t reg77);

/* True if hz falls in a CC1101 band the radio supports
 * (300-348, 387-464, 779-928 MHz). */
bool pa_cc1101_band_ok(uint32_t hz);

/* ---- glitch filter + pulse-pattern gate ---- */

#define PA_SG_FILTER_US 100u   /* edges shorter than this merge into the run  */
#define PA_PG_SIG_MIN_US 250u  /* >= this (and <= MAX) counts as a VAG pulse  */
#define PA_PG_SIG_MAX_US 1200u /* <= this; longer = neutral (gap/carrier)     */
#define PA_PG_OPEN_RUN 4u      /* consecutive VAG pulses to OPEN the feed      */
#define PA_PG_CLOSE_RUN 3u     /* consecutive noise pulses to CLOSE the feed   */

typedef struct {
    /* passthru: skip the VAG-specific signal-run open/close gating and feed
     * every glitch-filtered pulse straight through. Used by the GENERAL Sub-GHz
     * RX suite (onyx_sig_decode does its own per-protocol timing validation and
     * was validated on raw pulses); the VAG keyfob path leaves this false to keep
     * its tuned 250-1200us noise gate. */
    bool passthru;
    /* glitch-filter accumulator */
    bool f_level;
    uint32_t f_duration;
    bool have_level; /* false until the first edge seeds f_level */
    /* pulse-pattern gate */
    bool open;         /* decode feed currently open */
    uint8_t sig_run;   /* consecutive VAG-width pulses (toward open) */
    uint8_t noise_run; /* consecutive noise pulses (toward close) */
    /* diagnostics */
    uint32_t cur_run;  /* current contiguous VAG-width run */
    uint32_t max_run;  /* longest VAG-width run seen */
    uint32_t fed;      /* pulses emitted through the open gate */
} PaPulseGate;

void pa_pgate_reset(PaPulseGate* g);

/* Push one raw captured edge (level + duration in us). When a filtered pulse
 * is completed AND the gate is open, *out_feed is set true and
 * *out_level / *out_duration carry the pulse to hand to the decoder
 * (subghz_receiver_decode on device). Otherwise *out_feed is false.
 * Returns true if a filtered pulse boundary was produced this call. */
bool pa_pgate_edge(
    PaPulseGate* g,
    bool level,
    uint32_t duration,
    bool* out_feed,
    bool* out_level,
    uint32_t* out_duration);

#ifdef __cplusplus
}
#endif
#endif /* PA_RF_H */
