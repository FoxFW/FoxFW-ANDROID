/* =====================================================================
 * pa_rf.c - portable RF-bridge logic (see pa_rf.h).
 * Ported verbatim-in-behaviour from PandoraARM main.c
 * (si4432_regs_to_hz, cc1101_band_ok, sg_worker_thread gate).
 * ===================================================================== */
#include "pa_rf.h"

uint32_t pa_si4432_regs_to_hz(uint8_t reg75, uint8_t reg76, uint8_t reg77) {
    uint32_t fb = (uint32_t)(reg75 & 0x1F);
    uint32_t hbsel = (uint32_t)((reg75 >> 5) & 1u);
    uint32_t fc = ((uint32_t)reg76 << 8) | (uint32_t)reg77;
    uint64_t base = (uint64_t)(fb + 24u) * 10000000ull;      /* (fb+24)*10MHz   */
    uint64_t fcterm = ((uint64_t)fc * 15625ull) / 100ull;    /* fc*156.25 Hz    */
    uint64_t hz = (base + fcterm) * (uint64_t)(hbsel + 1u);
    return (uint32_t)hz;
}

bool pa_cc1101_band_ok(uint32_t hz) {
    return (hz >= 300000000u && hz <= 348000000u) ||
           (hz >= 387000000u && hz <= 464000000u) ||
           (hz >= 779000000u && hz <= 928000000u);
}

void pa_pgate_reset(PaPulseGate* g) {
    g->passthru = false;
    g->f_level = false;
    g->f_duration = 0;
    g->have_level = false;
    g->open = false;
    g->sig_run = 0;
    g->noise_run = 0;
    g->cur_run = 0;
    g->max_run = 0;
    g->fed = 0;
}

bool pa_pgate_edge(
    PaPulseGate* g,
    bool level,
    uint32_t duration,
    bool* out_feed,
    bool* out_level,
    uint32_t* out_duration) {
    *out_feed = false;

    /* Seed the accumulator on the first edge so we never emit a bogus
     * zero-width pulse (equivalent to the original, whose gate was closed
     * on the first edge anyway). */
    if(!g->have_level) {
        g->have_level = true;
        g->f_level = level;
        g->f_duration = duration;
        return false;
    }

    /* Glitch filter: merge sub-threshold edges and same-level runs. */
    if((duration < PA_SG_FILTER_US) || (g->f_level == level)) {
        g->f_duration += duration;
        return false;
    }

    /* A filtered pulse (g->f_level, g->f_duration) just completed. */
    uint32_t d = g->f_duration;

    /* Passthru (general Sub-GHz RX): emit every glitch-filtered pulse; let the
     * protocol decoders judge timing. Skips the VAG-tuned signal-run gate. */
    if(g->passthru) {
        *out_feed = true;
        *out_level = g->f_level;
        *out_duration = g->f_duration;
        g->fed++;
        g->f_duration = duration;
        g->f_level = level;
        return true;
    }

    bool is_noise = (d < PA_PG_SIG_MIN_US);
    bool is_signal = (d >= PA_PG_SIG_MIN_US && d <= PA_PG_SIG_MAX_US);

    if(is_signal) {
        g->cur_run++;
        if(g->cur_run > g->max_run) g->max_run = g->cur_run;
    } else {
        g->cur_run = 0;
    }

    if(!g->open) {
        if(is_signal) {
            if(++g->sig_run >= PA_PG_OPEN_RUN) {
                g->open = true;
                g->noise_run = 0;
            }
        } else if(is_noise) {
            g->sig_run = 0; /* noise breaks the clean run; long gap is neutral */
        }
    } else {
        if(is_noise) {
            if(++g->noise_run >= PA_PG_CLOSE_RUN) {
                g->open = false;
                g->sig_run = 0;
            }
        } else {
            g->noise_run = 0; /* signal or long gap keeps it open */
        }
    }

    if(g->open) {
        *out_feed = true;
        *out_level = g->f_level;
        *out_duration = g->f_duration;
        g->fed++;
    }

    /* Start the next run with the incoming edge. */
    g->f_duration = duration;
    g->f_level = level;
    return true;
}
