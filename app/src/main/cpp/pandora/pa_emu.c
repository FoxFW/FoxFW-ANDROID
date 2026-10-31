/* =====================================================================
 * pa_emu.c - portable Pandora D-605 keyfob emulator core (see pa_emu.h).
 * Faithful port of the Flipper PandoraARM main.c emulation layer, with the
 * paged FlashCache replaced by a direct PSRAM-style image pointer, and the
 * Flipper HAL (LED/buzzer/speaker/RF) reduced to optional PaEmuOps sinks.
 * ===================================================================== */
#include "pa_emu.h"
#include "pa_rf.h"
#include <string.h>

/* boot-sequence budgets (verbatim from main.c) */
#define KNOCK_SETTLE_INSN 250000ull
#define KNOCK_STEP_BUDGET 1500000ull
#define KNOCK_RELEASE_AFTER 4u

/* nav (post-unlock input) budgets (verbatim from main.c) */
#define NAV_HOLD_READS 40u
#define NAV_LONG_READS 120u
#define NAV_BUDGET_INSN 1000000ull
/* inactive-button fast path (see PandoraARM main.c): bail when the screen never
 * re-reads our pin instead of spinning the full budget (~14s freeze). */
#define NAV_INACTIVE_INSN 80000ull
#define NAV_SETTLE_INSN 30000ull
#define NAV_SETTLE_MIN_INSN 10000ull
#define NAV_PRESS_BURST 5000u
#define NAV_SETTLE_BURST 5000u

/* OLED DC pin: port0 bit9 (A9). HIGH=command, LOW=pixel data. */
#define OLED_DC_PORT 0u
#define OLED_DC_PIN 9u

/* ------------------------------------------------------------ profiles */

const PaProfile PA_PROFILE_PANDORA = {
    .name = "pandora",
    .flash_used = 0x13800u,
    .fb_addr = 0x20001152u,
    .up_port = 5, .up_pin = 7,
    .down_port = 5, .down_pin = 5,
    .ok_port = 2, .ok_pin = 9,
    .back_port = 0, .back_pin = 15,
    .b5_port = 0, .b5_pin = 1,
    .b6_port = 0, .b6_pin = 6,
    .knock = {PA_B_UP, PA_B_OK, PA_B_OK, PA_B_UP, PA_B_UP, PA_B_DOWN, 0xFF, 0xFF},
    .knock_len = 6,
    .gate = PA_B5,
    .scan_head = 0xFDFAu,
    .spin_heads = {0xFE5E, 0xFE82, 0xFE9A, 0xFEB2, 0xFECC, 0xFEF4, 0xFF12, 0},
    .spin_count = 7,
    .unlock_pc = 0xD108u,
    .nav_reader = 0x97ACu,
    .nav_readers = {0x9794u, 0x97ACu, 0x97C4u, 0x97DCu, 0u},
    .led_port = 3, .led_pin = 14,
    .data_port = 1, .data_pin = 0,
    .tx_data_port = 1, .tx_data_pin = 3, /* OOK bit-banged on PB3 (0x93E0->0x9908 port1/pin3); was wrongly PA9 */
    .rf_cs_port = 4, .rf_cs_pin = 9,
    .rf_tx_cell_us = 400,
    .demod_isr = 0x09554u, .timer1_cnt = 0x40010424u,
    .rx_struct = 0x20000000u, .rx_gate = 0x2000000Fu, .rx_carrier_gate = 0x20000009u,
    .rx_mode = 0x200005BCu, .rx_pending = 0x200005C0u, .vag_dec_reset = 0x20001897u,
    .rx_clear_len = 0x40u,
};

const PaProfile PA_PROFILE_PANDORAMAX = {
    .name = "pandoramax",
    .flash_used = 0x15400u,
    .fb_addr = 0x200005DAu,
    .up_port = 5, .up_pin = 7,
    .down_port = 5, .down_pin = 5,
    .ok_port = 2, .ok_pin = 9,
    .back_port = 0, .back_pin = 15,
    .b5_port = 0, .b5_pin = 1,
    .b6_port = 0, .b6_pin = 6,
    .knock = {PA_B_UP, PA_B_OK, PA_B_DOWN, PA_B_OK, PA_B_UP, PA_B_DOWN, 0xFF, 0xFF},
    .knock_len = 6,
    .gate = PA_B5,
    .scan_head = 0x112E4u,
    .spin_heads = {0x1135A, 0x11382, 0x1139A, 0x113B2, 0x113CA, 0x113E2, 0x1144C, 0x11464},
    .spin_count = 8,
    .unlock_pc = 0xD6E4u,
    .nav_reader = 0x96ECu,
    .nav_readers = {0x96D4u, 0x96ECu, 0x9704u, 0x971Cu, 0u},
    .led_port = 3, .led_pin = 14,
    .data_port = 1, .data_pin = 0,
    .tx_data_port = 1, .tx_data_pin = 3,
    .rf_cs_port = 4, .rf_cs_pin = 9,
    .rf_tx_cell_us = 500,
    .demod_isr = 0x0945Cu, .timer1_cnt = 0x40010424u,
    .rx_struct = 0x20000000u, .rx_carrier_gate = 0x2000000Au, .rx_pending = 0x2000003Du,
};

const PaProfile* pa_pick_profile(const char* path) {
    if(path) {
        if(strstr(path, "MAX") || strstr(path, "max") || strstr(path, "PANDORA_MAX"))
            return &PA_PROFILE_PANDORAMAX;
        if(strstr(path, "2_5253") || strstr(path, "pandora")) return &PA_PROFILE_PANDORA;
    }
    return &PA_PROFILE_PANDORA;
}

/* ------------------------------------------------------------ helpers */

static uint64_t emu_insn_now(const PaEmu* e) {
    if(!e->cpu) return e->ninsn;
    return e->cpu->cycles + 1u;
}

static void btn_port_pin(const PaProfile* p, int btn, uint8_t* port, uint8_t* pin) {
    switch(btn) {
    case PA_B_UP: *port = p->up_port; *pin = p->up_pin; break;
    case PA_B_DOWN: *port = p->down_port; *pin = p->down_pin; break;
    case PA_B_OK: *port = p->ok_port; *pin = p->ok_pin; break;
    case PA_B_BACK: *port = p->back_port; *pin = p->back_pin; break;
    case PA_B5: *port = p->b5_port; *pin = p->b5_pin; break;
    case PA_B6: *port = p->b6_port; *pin = p->b6_pin; break;
    default: *port = 0; *pin = 0; break;
    }
}

void pa_emu_set_button(PaEmu* e, int btn, bool pressed) {
    if(btn < 0 || btn >= PA_BTN_COUNT) return;
    uint8_t port, pin;
    btn_port_pin(e->prof, btn, &port, &pin);
    if(pressed)
        e->gpio_din[port] &= (uint16_t)~(1u << pin); /* 0 = pressed */
    else
        e->gpio_din[port] |= (uint16_t)(1u << pin); /* 1 = released */
    e->btn_pressed[btn] = pressed ? 1 : 0;
}

/* ------------------------------------------------------------ Si4432 */

static void si4432_init(PaSi4432* s) {
    memset(s->regs, 0, sizeof(s->regs));
    s->regs[0x00] = 0x08;
    s->regs[0x01] = 0x06;
    s->regs[0x26] = 0x60;
    s->mode = 0;
}

static uint8_t si4432_read(PaSi4432* s, uint8_t reg) {
    reg &= 0x7F;
    uint8_t v = s->regs[reg];
    if(reg == 0x03 || reg == 0x04) s->regs[reg] = 0; /* INT status clears on read */
    return v;
}

/* returns new mode if REG[0x07] changed it, else -1 */
void (*pa_dbg_si_write)(unsigned char reg, unsigned char val) = NULL;
void (*pa_dbg_si_read)(unsigned char reg, unsigned char val) = NULL;
void (*pa_dbg_mmio_write)(unsigned int addr, unsigned int val) = NULL;
void (*pa_dbg_mmio_read)(unsigned int addr) = NULL;
void (*pa_dbg_pc)(unsigned int pc) = NULL;
int pa_dbg_force_hook = 0;

static int si4432_write(PaSi4432* s, uint8_t reg, uint8_t val) {
    reg &= 0x7F;
    s->regs[reg] = val;
    if(reg == 0x07) {
        uint8_t nm = s->mode;
        if(val & 0x08) nm = 3;
        else if(val & 0x04) nm = 2;
        else if(val & 0x01) nm = 1;
        else nm = 0;
        if(nm != s->mode) { s->mode = nm; return (int)nm; }
    }
    return -1;
}

/* ------------------------------------------------------------ OLED capture */

static int oled_dc_level(PaEmu* e) {
    return (e->gpio_dout[OLED_DC_PORT] >> OLED_DC_PIN) & 1u;
}

static void oled_commit(PaEmu* e) {
    if(!e->oled_work_dirty) return;
    bool any = false;
    for(int i = 0; i < (int)PA_OLED_FB_SIZE; i++) {
        if(e->oled_work[i]) { any = true; break; }
    }
    if(!any) { e->dbg_oled_commit_blank++; return; }
    if(memcmp(e->oled_fb, e->oled_work, PA_OLED_FB_SIZE) == 0) {
        e->dbg_oled_commit_unchanged++;
        e->oled_work_dirty = 0; /* unchanged status frame */
        return;
    }
    memcpy(e->oled_fb, e->oled_work, PA_OLED_FB_SIZE);
    e->oled_work_dirty = 0;
    e->oled_frames++;
}

static void oled_cmd(PaEmu* e, uint8_t b) {
    e->dbg_oled_cmd++;
    if(b == 0xAF) e->dbg_oled_af++;
    if(b < PA_OLED_PAGES) { /* 0x00..0x0F = raw page number */
        e->oled_page = b;
        e->oled_col = 0;
    } else if(b == 0x60 || b == 0x70) {
        e->oled_col = 0;
    } else if(b == 0xAF) { /* display ON = end of flush -> commit */
        oled_commit(e);
    }
}

static void oled_data(PaEmu* e, uint8_t b) {
    e->dbg_oled_data++;
    if(b) e->dbg_oled_data_nz++;
    if(e->oled_page > e->dbg_max_page) e->dbg_max_page = e->oled_page;
    if(e->oled_col > e->dbg_max_col) e->dbg_max_col = e->oled_col;
    if(e->oled_page < PA_OLED_PAGES && e->oled_col < PA_OLED_COLS) {
        uint32_t idx = (uint32_t)e->oled_page * PA_OLED_COLS + e->oled_col;
        if(idx < PA_OLED_FB_SIZE) {
            e->oled_work[idx] = b;
            e->oled_work_dirty = 1;
        }
    } else {
        e->dbg_oled_data_dropped++;
    }
    if(e->oled_col < 0xFF) e->oled_col++;
}

/* ------------------------------------------------------------ SPI routing */

static void spi_byte(PaEmu* e, uint8_t b) {
    uint8_t si_cs = (uint8_t)((e->gpio_dout[e->prof->rf_cs_port] >> e->prof->rf_cs_pin) & 1u);

    if(!si_cs) { /* CS inactive -> OLED byte (DC selects cmd vs data) */
        e->dbg_cs_low++; e->dbg_spi_oled++;
        e->spi_phase = -1;
        if(oled_dc_level(e)) oled_cmd(e, b);
        else oled_data(e, b);
        return;
    }
    e->dbg_cs_high++; e->dbg_spi_si++;

    /* Si4432 transaction (CS high) */
    if(e->spi_phase < 0) {
        e->spi_kind = (b & 0x80) ? 'w' : 'r';
        e->spi_reg = b & 0x7F;
        e->spi_phase = 0;
        e->spi_rx[e->spi_rx_tail] = 0; /* junk echo on the address byte */
        e->spi_rx_tail = (uint8_t)((e->spi_rx_tail + 1) % PA_SPI_RX_QUEUE_LEN);
    } else {
        if(e->spi_kind == 'w') {
            uint8_t wreg = e->spi_reg;
            int nm = si4432_write(&e->si, wreg, b);
            if(pa_dbg_si_write) pa_dbg_si_write(wreg, b);
            if(e->rf_follow) {
                if(wreg == 0x75 || wreg == 0x76 || wreg == 0x77) {
                    uint32_t hz = pa_si4432_regs_to_hz(
                        e->si.regs[0x75], e->si.regs[0x76], e->si.regs[0x77]);
                    if(pa_cc1101_band_ok(hz) && e->ops.radio_follow_freq)
                        e->ops.radio_follow_freq(e->ops.ud, hz);
                } else if(wreg == 0x71 || wreg == 0x72) {
                    e->si_modtyp = e->si.regs[0x71] & 0x03;
                    e->si_dtmod = (e->si.regs[0x71] >> 6) & 0x03;
                    uint32_t fd = (uint32_t)e->si.regs[0x72] |
                                  (((uint32_t)(e->si.regs[0x71] >> 2) & 1u) << 8);
                    e->si_devi_hz = fd * 625u;
                    if(e->ops.radio_follow_mod)
                        e->ops.radio_follow_mod(e->ops.ud, e->si_modtyp, e->si_devi_hz);
                }
            }
            if(nm == 3) {
                if(!e->tx_capturing) {
                    e->tx_t0_us = e->ops.now_us ? e->ops.now_us(e->ops.ud) : 0;
                    for(int ti = 0; ti < 4; ti++) e->cnt_t0_us[ti] = e->tx_t0_us;
                }
                e->tx_capturing = 1;
            } else if(nm >= 0) e->tx_capturing = 0;
            if(nm >= 0 && e->ops.radio_follow_mode) e->ops.radio_follow_mode(e->ops.ud, nm);
        } else {
            uint8_t rv = si4432_read(&e->si, e->spi_reg);
            if(pa_dbg_si_read) pa_dbg_si_read(e->spi_reg, rv);
            e->spi_rx[e->spi_rx_tail] = rv;
            e->spi_rx_tail = (uint8_t)((e->spi_rx_tail + 1) % PA_SPI_RX_QUEUE_LEN);
        }
        e->spi_phase = -1;
    }
}

/* ------------------------------------------------------------ LED mirror */

static void led_mirror(PaEmu* e, uint32_t port) {
    if(port != e->prof->led_port) return;
    bool on = (e->gpio_dout[port] >> e->prof->led_pin) & 1u;
    if(on == e->led_on) return;
    e->led_on = on;
    if(e->ops.led_set) e->ops.led_set(e->ops.ud, on);
}

/* ------------------------------------------------------------ MMIO */

static uint32_t mmio_read(void* ctx, uint32_t addr, int size) {
    if(pa_dbg_mmio_read) pa_dbg_mmio_read(addr);
    PaEmu* e = (PaEmu*)ctx;
    uint32_t base = addr & 0xFFFFF000u;
    uint32_t off = addr & 0xFFFu;
    uint32_t v = 0;
    (void)size;

#ifndef PA_NO_BITBAND
    /* Cortex-M bit-band: read the aliased peripheral/SRAM bit (the D-605 fw sets
     * peripheral control bits this way; unmodelled = features silently no-op). */
    if(addr >= 0x42000000u && addr < 0x44000000u) {
        uint32_t t = 0x40000000u + ((addr - 0x42000000u) >> 5);
        uint32_t bit = ((addr - 0x42000000u) >> 2) & 7u;
        return (mmio_read(ctx, t, 1) >> bit) & 1u;
    }
    if(addr >= 0x22000000u && addr < 0x24000000u) {
        uint32_t t = 0x20000000u + ((addr - 0x22000000u) >> 5);
        uint32_t bit = ((addr - 0x22000000u) >> 2) & 7u, o = t - 0x20000000u;
        return (o < PA_RAM_SIZE) ? ((e->ram[o] >> bit) & 1u) : 0u;
    }
#endif

    /* flash (addr < 0x42000) is a DIRECT region now; it never reaches here. */

    if(base == 0x4000C000u) { /* USART0 (SPI Si4432/OLED) */
        if(off == 0x10) v = (1u << 6) | (1u << 5); /* TXBL + RXDATAV ready */
        else if(off == 0x1C) { /* RXDATA */
            if(e->spi_rx_head != e->spi_rx_tail) {
                v = e->spi_rx[e->spi_rx_head];
                e->spi_rx_head = (uint8_t)((e->spi_rx_head + 1) % PA_SPI_RX_QUEUE_LEN);
            }
        }
    } else if(base == 0x400C4000u) { /* 2nd USART (EEPROM/config) */
        if(off == 0x10) v = (1u << 6) | (1u << 5);
#ifdef PA_EEPROM_FF
        else if(off == 0x1C) v = 0xFF; /* DIAGNOSTIC: config bytes all-set */
#endif
    } else if(addr >= 0x40006000u && addr < 0x40006000u + 36u * 16u) { /* GPIO */
        uint32_t port = (addr - 0x40006000u) / 36u;
        uint32_t poff = (addr - 0x40006000u) - port * 36u;
        if(poff == 0x1C && port < 16) { v = e->gpio_din[port];     /* DIN */
            e->gpio_rd_insn[port] = emu_insn_now(e); }             /* track who polls */
        else if(poff == 0x0C && port < 16) v = e->gpio_dout[port]; /* DOUT (bit-band read-back) */
    } else if(base == 0x40002000u) { /* ADC0 (pmax battery) */
        if(off == 0x08) v = (1u << 16);   /* SINGLEDV ready */
        else if(off == 0x24) v = 0x0C00;  /* data ~3.9V */
    } else if(base == 0x400C8000u) { /* CMU #2: clock-ready bits 3/7/9 */
        v = (off == 0x2Cu) ? ((1u << 3) | (1u << 7) | (1u << 9)) : 0u;
    } else if(base == 0x40080000u) { /* CMU #1 */
        v = 0;
    } else if(base == 0x40010000u) { /* TIMER0..3 */
        uint32_t tn = off >> 10;
        uint32_t sub = off & 0x3FFu;
        if(sub == 0x24 && tn < 4) { /* CNT */
            if(tn == 1 && e->rx_feeding) {
                v = e->rx_pulse_ticks & 0xFFFFu;
            } else if(e->tx_capturing) {
                /* TX / brute-force delay loops ("write CNT=0; wait CNT>=N"): model the
                 * physical 16-bit up-counter -- climbs monotonically 0..0xFFFF after
                 * each CNT=0 reset and saturates at 0xFFFF. The threshold is capped at
                 * the REGISTER WIDTH (0xFFFF), NOT timer_top[]: the fob's delay waits
                 * (Func1 CNT>=48000@0xEBC6, Func2 CNT>=64000@0xEBF6) assume a free 16-bit
                 * timer, but the firmware also programs a SMALL TIMER0 TOP for TX pacing;
                 * capping at that small TOP made CNT unable to ever reach 48000/64000 ->
                 * the barrier brute forcer spun forever. 48000,64000 < 0xFFFF so both
                 * thresholds are now reachable and the delay exits. */
                uint64_t elapsed =
                    (emu_insn_now(e) - e->timer_t0[tn]) * (uint64_t)e->timer_rate[tn];
                v = (elapsed >= 0xFFFFu) ? 0xFFFFu : (uint32_t)elapsed;
            } else {
                uint32_t top = e->timer_top[tn] ? e->timer_top[tn] : 0xFFFFu;
                uint64_t elapsed =
                    (emu_insn_now(e) - e->timer_t0[tn]) * (uint64_t)e->timer_rate[tn];
                v = (uint32_t)(elapsed % ((uint64_t)top + 1u));
            }
        } else if(sub == 0x3C && tn < 4) { /* overflow proxy */
            uint32_t top = e->timer_top[tn] ? e->timer_top[tn] : 0xFFFFu;
            uint64_t elapsed =
                (emu_insn_now(e) - e->timer_t0[tn]) * (uint64_t)e->timer_rate[tn];
            v = (elapsed >= top) ? 1u : 0u;
        }
    }
    return v;
}

static void mmio_write(void* ctx, uint32_t addr, uint32_t val, int size) {
    PaEmu* e = (PaEmu*)ctx;
    uint32_t base = addr & 0xFFFFF000u;
    uint32_t off = addr & 0xFFFu;
    (void)size;
    if(pa_dbg_mmio_write) pa_dbg_mmio_write(addr, val);

#ifndef PA_NO_BITBAND
    /* Cortex-M bit-band write: set/clear the aliased peripheral/SRAM bit. */
    if(addr >= 0x42000000u && addr < 0x44000000u) {
        uint32_t t = 0x40000000u + ((addr - 0x42000000u) >> 5);
        uint32_t bit = ((addr - 0x42000000u) >> 2) & 7u;
        uint32_t cur = mmio_read(ctx, t, 1);
        if(val & 1u) cur |= (1u << bit); else cur &= ~(1u << bit);
        mmio_write(ctx, t, cur, 1);
        return;
    }
    if(addr >= 0x22000000u && addr < 0x24000000u) {
        uint32_t t = 0x20000000u + ((addr - 0x22000000u) >> 5);
        uint32_t bit = ((addr - 0x22000000u) >> 2) & 7u, o = t - 0x20000000u;
        if(o < PA_RAM_SIZE) { if(val & 1u) e->ram[o] |= (1u << bit); else e->ram[o] &= ~(1u << bit); }
        return;
    }
#endif

    if(base == 0x4000C000u && off == 0x34) { /* USART0 TXDATA */
        spi_byte(e, (uint8_t)(val & 0xFF));
    } else if(addr >= 0x40006000u && addr < 0x40006000u + 36u * 16u) { /* GPIO DOUT* */
        uint32_t port = (addr - 0x40006000u) / 36u;
        uint32_t poff = (addr - 0x40006000u) - port * 36u;
        if(port < 16) {
            if(poff == 0x10) e->gpio_dout[port] &= (uint16_t)~val;      /* DOUTCLR */
            else if(poff == 0x14) e->gpio_dout[port] |= (uint16_t)val;  /* DOUTSET */
            else if(poff == 0x0C) e->gpio_dout[port] = (uint16_t)val;   /* DOUT    */
            if(e->tx_capturing && port == e->prof->tx_data_port && e->ops.tx_edge) {
                bool level = (e->gpio_dout[port] >> e->prof->tx_data_pin) & 1u;
                e->ops.tx_edge(e->ops.ud, level, emu_insn_now(e));
            }
            led_mirror(e, port);
        }
    } else if(base == 0x40010000u) { /* TIMER0..3: CNT reset + TOP */
        uint32_t tn = off >> 10;
        uint32_t sub = off & 0x3FFu;
        if(tn < 4) {
            if(sub == 0x24) {
                e->timer_t0[tn] = emu_insn_now(e);
                /* also reset the real-clock origin so TX delay loops count from here */
                if(e->tx_capturing && e->ops.now_us) e->cnt_t0_us[tn] = e->ops.now_us(e->ops.ud);
            }
            else if(sub == 0x3C) e->timer_top[tn] = val ? val : 0xFFFFu;
        }
    }
}

/* ------------------------------------------------------------ NVIC (inert for
 * pandora: VTOR reads back 0 through the callback, so app_running is false and
 * no timer IRQ is injected; kept faithful for variants that relocate VTOR). */

static uint32_t cur_vtor(PaEmu* e) { return thumb_read_mem(e->cpu, 0xE000ED08u, 4); }

static uint32_t irq_handler_addr(PaEmu* e, int irqn) {
    uint32_t vt = cur_vtor(e);
    return thumb_read_mem(e->cpu, vt + 0x40u + (uint32_t)irqn * 4u, 4);
}

static const int TIMER_IRQS[] = {12, 13, 14, 1};

static void nvic_tick(PaEmu* e) {
    if(e->in_irq) return;
    uint32_t vtor = cur_vtor(e);
    if(vtor < 0x8000u) return; /* table not installed in RAM */
    int irqn = TIMER_IRQS[e->irq_rr % (int)(sizeof(TIMER_IRQS) / sizeof(int))];
    e->irq_rr++;
    uint32_t handler = irq_handler_addr(e, irqn);
    if(handler == 0 || handler == 0x82DBu) return;
    e->tick_count++;
    e->in_irq = 1;
    thumb_enter_exception(e->cpu, handler, THUMB_EXC_RETURN_MSP_THREAD, (uint32_t)(irqn + 16));
    e->next_pc = thumb_get_reg(e->cpu, 15);
}

/* ------------------------------------------------------------ WFI scan */

static void find_wfi(PaEmu* e) {
    uint32_t lim = e->flash_size < 0x20000u ? e->flash_size : 0x20000u;
    for(uint32_t off = 0x8100u; off + 1 < lim; off += 2) {
        if(e->flash[off] == 0x30u && e->flash[off + 1] == 0xBFu) {
            e->wfi_pc_hint = off;
            return;
        }
    }
    e->wfi_pc_hint = 0xFFFFFFFFu;
}

/* ------------------------------------------------------------ knock FSM */

static void knock_step(PaEmu* e, uint32_t a) {
    const PaProfile* p = e->prof;
    if(!e->knock_active) return;
    if(e->knock_sub == 0 && a == p->scan_head) {
        if(e->knock_idx < e->knock_len) {
            e->knock_cur = e->knock_seq[e->knock_idx];
            pa_emu_set_button(e, e->knock_cur, true);
            e->knock_sub = 1;
            e->knock_spins = 0;
        } else {
            e->knock_active = 0;
        }
    } else if(e->knock_sub == 1) {
        for(int i = 0; i < p->spin_count; i++) {
            if(a == p->spin_heads[i]) {
                e->knock_spins++;
                if(e->knock_spins >= KNOCK_RELEASE_AFTER) {
                    pa_emu_set_button(e, e->knock_cur, false);
                    e->knock_idx++;
                    e->knock_sub = 0;
                }
                break;
            }
        }
    }
}

/* ------------------------------------------------------------ hook + run loop */

/* ------------------------------------------------------------ nav FSM */

static int is_nav_reader(const PaProfile* p, uint32_t a) {
    if(p->nav_readers[0] == 0u) return a == p->nav_reader;
    for(int i = 0; i < (int)(sizeof(p->nav_readers) / sizeof(p->nav_readers[0])); i++) {
        if(p->nav_readers[i] == 0u) break;
        if(a == p->nav_readers[i]) return 1;
    }
    return 0;
}

static void nav_step(PaEmu* e, uint32_t a) {
    if(!e->nav_active) return;
    if(!is_nav_reader(e->prof, a)) return;
    uint32_t r0 = thumb_get_reg(e->cpu, 0); /* port */
    uint32_t r1 = thumb_get_reg(e->cpu, 1); /* pin  */
    int is_our_pin = (r0 == e->nav_port && r1 == e->nav_pin);
    if(e->nav_phase == 0) { /* press */
        e->gpio_din[e->nav_port] &= (uint16_t)~(1u << e->nav_pin); /* 0 = pressed */
        e->btn_pressed[e->nav_btn] = 1;
        e->nav_phase = 1;
        e->nav_reads = 0;
    } else if(e->nav_phase == 1) { /* hold: count re-reads of OUR pin */
        if(is_our_pin) {
            e->nav_reads++;
            if(e->nav_reads >= e->nav_reads_target) {
                e->gpio_din[e->nav_port] |= (uint16_t)(1u << e->nav_pin); /* release */
                e->btn_pressed[e->nav_btn] = 0;
                e->nav_phase = 2; /* done */
            }
        }
    }
}

static uint32_t fb_hash(PaEmu* e) {
    uint32_t h = 2166136261u;
    for(uint32_t i = 0; i < PA_OLED_FB_SIZE; i++) { h ^= e->oled_fb[i]; h *= 16777619u; }
    return h;
}

static void code_hook(void* ctx, uint32_t pc) {
    PaEmu* e = (PaEmu*)ctx;
    if(pa_dbg_pc) pa_dbg_pc(pc);
    e->ninsn = emu_insn_now(e);
    e->pc = pc;
    if(!e->unlocked && pc == e->prof->unlock_pc) e->unlocked = 1;
    if(e->knock_active) knock_step(e, pc);
    if(e->nav_active) nav_step(e, pc);
}

static void run_burst(PaEmu* e, uint32_t count) {
    ThumbCore* cpu = e->cpu;
    ThumbHook saved_hook = cpu->hook;
    void* saved_hook_ctx = cpu->hook_ctx;
    bool skip_hook = e->unlocked && !e->nav_active && !e->knock_active && !pa_dbg_force_hook;
    uint64_t now_insn = emu_insn_now(e);
    bool app_running = cur_vtor(e) >= 0x8000u;
    bool stuck_wfi = app_running && (e->next_pc & ~1u) == (e->wfi_pc_hint & ~1u);
    bool want_tick = app_running && (stuck_wfi || (now_insn - e->last_tick_insn) > 20000ull);
    if(want_tick && !e->in_irq) {
        e->last_tick_insn = now_insn;
        nvic_tick(e);
    }
    if(skip_hook) thumb_set_hook(cpu, NULL, NULL);
    thumb_set_reg(cpu, 15, e->next_pc);

    if(skip_hook && !e->in_irq) {
        (void)thumb_run(cpu, count);
        thumb_set_hook(cpu, saved_hook, saved_hook_ctx);
        e->next_pc = cpu->r[15];
        e->ninsn = emu_insn_now(e);
        e->pc = e->next_pc & ~1u;
        return;
    }
    for(uint32_t i = 0; i < count; i++) {
        int rc = thumb_step(cpu);
        if(rc < 0) break;
        uint32_t pc = thumb_get_reg(cpu, 15);
        if((pc & 0xFFFFFFF0u) == 0xFFFFFFF0u) { /* EXC_RETURN */
            thumb_exc_return(cpu, pc);
            e->in_irq = 0;
            e->next_pc = thumb_get_reg(cpu, 15);
            if(skip_hook) thumb_set_hook(cpu, saved_hook, saved_hook_ctx);
            e->ninsn = emu_insn_now(e);
            e->pc = e->next_pc & ~1u;
            return;
        }
    }
    e->next_pc = thumb_get_reg(cpu, 15);
    if(skip_hook) thumb_set_hook(cpu, saved_hook, saved_hook_ctx);
    e->ninsn = emu_insn_now(e);
    e->pc = e->next_pc & ~1u;
}

void pa_emu_run_frame(PaEmu* e, uint32_t insns) { run_burst(e, insns); }

/* ---- native RX: drive the firmware's own SW-demod ISR (Track A) ----
 * Ported from the Flipper reference (call_subroutine + rx_feed_edge). */
#define PA_CALL_SENTINEL   0x001FFFF0u
#define PA_CALL_SCRATCH_SP 0x20007000u
#define PA_RX_ISR_SAMPLES  6u   /* settle the firmware's 5-tap majority filter */

static void call_subroutine(PaEmu* e, uint32_t addr, uint32_t max_insn) {
    ThumbCore* cpu = e->cpu;
    uint32_t s_pc = e->next_pc;
    uint32_t s_sp = thumb_get_reg(cpu, 13);
    uint32_t s_lr = thumb_get_reg(cpu, 14);
    uint32_t s_r0 = thumb_get_reg(cpu, 0);
    uint32_t s_r1 = thumb_get_reg(cpu, 1);
    uint8_t s_halted = cpu->halted;
    ThumbHook s_hook = cpu->hook;
    void* s_hook_ctx = cpu->hook_ctx;
    bool no_hook = e->unlocked && !e->nav_active && !e->knock_active;
    if(no_hook) thumb_set_hook(cpu, NULL, NULL);
    thumb_set_reg(cpu, 13, PA_CALL_SCRATCH_SP);
    thumb_set_reg(cpu, 14, PA_CALL_SENTINEL | 1u);
    thumb_set_reg(cpu, 15, addr | 1u);
    cpu->halted = 0;
    for(uint32_t i = 0; i < max_insn; i++) {
        if((cpu->r[15] & ~1u) == (PA_CALL_SENTINEL & ~1u)) break;
        if(thumb_step(cpu) < 0) break;
    }
    thumb_set_hook(cpu, s_hook, s_hook_ctx);
    thumb_set_reg(cpu, 0, s_r0);
    thumb_set_reg(cpu, 1, s_r1);
    thumb_set_reg(cpu, 13, s_sp);
    thumb_set_reg(cpu, 14, s_lr);
    cpu->halted = s_halted;
    thumb_set_reg(cpu, 15, s_pc);
    e->next_pc = s_pc;
}

static void pa_rx_wr8(PaEmu* e, uint32_t addr, uint8_t v) { thumb_write_mem(e->cpu, addr, v, 1); }

void pa_emu_rx_prime(PaEmu* e, bool init_level) {
    const PaProfile* p = e->prof;
    uint8_t decoder_mode = (uint8_t)thumb_read_mem(e->cpu, p->rx_mode, 1);
    for(uint32_t o = 0; o < p->rx_clear_len; o++) pa_rx_wr8(e, p->rx_struct + o, 0);
    if(p->rx_mode) pa_rx_wr8(e, p->rx_mode, decoder_mode);
    if(p->rx_gate) pa_rx_wr8(e, p->rx_gate, 1); /* active receiver gate (!=0xFF) */
    if(init_level) e->gpio_din[p->data_port] |= (uint16_t)(1u << p->data_pin);
    else e->gpio_din[p->data_port] &= (uint16_t)~(1u << p->data_pin);
    e->rx_prev_level = init_level;
}

int pa_emu_rx_feed(PaEmu* e, bool level, uint32_t dur_us) {
    const PaProfile* p = e->prof;
    if(!p->demod_isr) return 0;
    /* long pulse (> 2.5ms) = preamble/inter-frame gap: clear the VAG decoder's
     * accumulated sync so a fresh frame's window isn't spoiled by preroll. */
    if(p->vag_dec_reset && dur_us > 2500u) {
        uint32_t off = p->vag_dec_reset - PA_RAM_BASE;
        if(off + 3u <= PA_RAM_SIZE) { e->ram[off] = 0; e->ram[off + 1] = 0; e->ram[off + 2] = 0; }
    }
    e->rx_pulse_ticks = (dur_us * 6u) & 0xFFFFu;        /* TIMER1_CNT width */
    if(level) e->gpio_din[p->data_port] |= (uint16_t)(1u << p->data_pin);
    else e->gpio_din[p->data_port] &= (uint16_t)~(1u << p->data_pin);
    if(p->rx_carrier_gate) pa_rx_wr8(e, p->rx_carrier_gate, level ? 1u : 0u);
    e->rx_feeding = true;
    int done = 0;
    for(uint32_t s = 0; s < PA_RX_ISR_SAMPLES; s++) {
        call_subroutine(e, p->demod_isr, 200000);
        if(p->rx_pending) {
            uint32_t po = p->rx_pending - PA_RAM_BASE;
            if(po < PA_RAM_SIZE && e->ram[po] == 0xFF) { done = 1; break; }
        }
    }
    e->rx_feeding = false;
    e->rx_prev_level = level;
    e->rx_injected++;
    return done;
}

/* Route an encoder turn to the button the CURRENT screen is actually polling.
 * dir>0 = CW (next/down), dir<0 = CCW (prev/up). Most D-605 screens ignore
 * UP/DOWN (port5) and scroll with B5/B6 (port0); a few use UP/DOWN. We pick by
 * which port the firmware read most recently (its idle input-poll loop reads
 * only the pins the screen listens for), so ONE encoder scrolls every screen
 * and no screen is a dead-end for the T-Embed's limited input. */
int pa_emu_scroll_button(PaEmu* e, int dir) {
    uint64_t now = emu_insn_now(e);
    const uint64_t WIN = 40000; /* a few frames of polling */
    uint8_t up_p = e->prof->up_port;   /* UP/DOWN share this port (5) */
    uint8_t b5_p = e->prof->b5_port;   /* B5/B6 share this port (0)  */
    bool updown = (now - e->gpio_rd_insn[up_p]) < WIN;
    bool b56    = (now - e->gpio_rd_insn[b5_p]) < WIN;
    if(updown)   return dir > 0 ? PA_B_DOWN : PA_B_UP;  /* screen watches UP/DOWN */
    if(b56)      return dir > 0 ? PA_B6     : PA_B5;    /* screen watches B5/B6   */
    return dir > 0 ? PA_B_DOWN : PA_B_UP;               /* default */
}

bool pa_emu_nav_button(PaEmu* e, int btn, bool hold) {
    if(btn < 0 || btn >= PA_BTN_COUNT) return false;
    uint8_t port, pin;
    btn_port_pin(e->prof, btn, &port, &pin);
    e->nav_btn = (uint8_t)btn;
    e->nav_port = port;
    e->nav_pin = pin;
    e->nav_phase = 0;
    e->nav_reads = 0;
    e->nav_reads_target = hold ? NAV_LONG_READS : NAV_HOLD_READS;
    e->nav_active = 1;

    uint64_t done = 0;
    while(e->nav_phase != 2 && done < NAV_BUDGET_INSN) {
        run_burst(e, NAV_PRESS_BURST);
        done += NAV_PRESS_BURST;
        /* Give up FAST if the firmware never reads this button's pin. On many fob
         * sections (status/info screens, radio screens) the active loop polls a
         * DIFFERENT button than the one this nav press targets, so nav_reads stays
         * 0 and nav_phase never reaches 1. The old check required phase==1, so
         * those presses ran the whole 1M-insn budget each -> the UI stalled for
         * ~1s per encoder detent and felt hard-frozen. Dropping the phase gate
         * lets an un-read press bail after NAV_INACTIVE_INSN instead, keeping the
         * device responsive so the user can hold Back to exit (no reboot). A
         * working screen reads the pin well within NAV_INACTIVE_INSN, so real
         * navigation is unaffected. */
        if(done >= NAV_INACTIVE_INSN && e->nav_phase != 2 && e->nav_reads == 0)
            break;
    }
    e->nav_active = 0;
    /* guarantee release if the budget ran out mid-hold */
    e->gpio_din[port] |= (uint16_t)(1u << pin);
    e->btn_pressed[btn] = 0;
    bool completed = (e->nav_phase == 2);

    /* settle until the UI redraws (bounded) */
    uint32_t prev = fb_hash(e);
    done = 0;
    while(done < NAV_SETTLE_INSN) {
        run_burst(e, NAV_SETTLE_BURST);
        done += NAV_SETTLE_BURST;
        uint32_t cur = fb_hash(e);
        if(cur != prev && done >= NAV_SETTLE_MIN_INSN) break;
        prev = cur;
    }
    return completed;
}

/* ------------------------------------------------------------ boot */

static bool auto_unlock(PaEmu* e) {
    e->unlocked = 0;
    e->max_step = -1;

    /* 1) settle until the scanner is active */
    uint64_t done = 0;
    while(done < KNOCK_SETTLE_INSN) { run_burst(e, 100000); done += 100000; }

    /* 2) inject the knock sequence, synced to the scanner */
    if(e->knock_user_len > 0) {
        e->knock_seq = e->knock_user;
        e->knock_len = e->knock_user_len;
    } else {
        e->knock_seq = e->prof->knock;
        e->knock_len = e->prof->knock_len;
    }
    e->knock_idx = 0;
    e->knock_sub = 0;
    e->knock_cur = 0;
    e->knock_active = 1;
    uint64_t budget = KNOCK_STEP_BUDGET * (e->knock_len ? e->knock_len : 1);
    done = 0;
    while(e->knock_active && !e->unlocked && done < budget) {
        run_burst(e, 40000);
        done += 40000;
        if(e->knock_idx >= e->knock_len) e->knock_active = 0;
    }
    e->knock_active = 0;

    /* 3) final gate: hold B5 */
    pa_emu_set_button(e, e->prof->gate, true);
    done = 0;
    while(done < 600000) { run_burst(e, 50000); done += 50000; }
    pa_emu_set_button(e, e->prof->gate, false);

    /* 4) let the UI redraw */
    done = 0;
    while(done < 600000) { run_burst(e, 100000); done += 100000; }

    /* 5) keep the UI loop turning until the first content frame commits. The
     * firmware clears the framebuffer (a blank 0xAF we skip) and then draws the
     * real screen, whose own 0xAF can land a little later; the device runs this
     * loop continuously, so mirror that here (bounded). */
    uint64_t cap = 0;
    while(e->oled_frames == 0 && cap < 3000000) { run_burst(e, 100000); cap += 100000; }

    return e->unlocked != 0;
}

void pa_emu_reset(PaEmu* e) {
    uint32_t sp0 = e->flash[0] | (e->flash[1] << 8) | (e->flash[2] << 16) |
                   ((uint32_t)e->flash[3] << 24);
    uint32_t rst = e->flash[4] | (e->flash[5] << 8) | (e->flash[6] << 16) |
                   ((uint32_t)e->flash[7] << 24);
    memset(e->ram, 0, PA_RAM_SIZE);
    thumb_reset(e->cpu, sp0, rst);
    e->next_pc = rst | 1u;
    e->ninsn = 0;
    e->in_irq = 0;
    e->irq_rr = 0;
    e->tick_count = 0;
    e->last_tick_insn = 0;
    e->unlocked = 0;
    e->max_step = -1;
    e->knock_active = 0;
    e->nav_active = 0;
    e->spi_phase = -1;
    e->spi_rx_head = e->spi_rx_tail = 0;
    for(int i = 0; i < 4; i++) {
        e->timer_t0[i] = 0;
        e->timer_top[i] = 0xFFFF;
        /* ODD rate -> coprime to the 2^16 (TOP+1) modulus, so the free-running CNT
         * (elapsed*rate % (TOP+1)) sweeps ALL residues instead of a coarse power-of-2
         * lattice. This guarantees any firmware "write CNT=0; wait CNT>=N" delay spin
         * crosses its threshold and exits -> fixes the keyfob-menu freezes (menus run
         * these timed delay loops; even rate=3072 vs 65536 let the sampled CNT skip the
         * threshold window forever). 0.03% timing change, wrap semantics preserved. */
        e->timer_rate[i] = (i == 0) ? 0xC01u : 0x401u;
    }
    e->tx_t0_us = 0;
    if(e->timer0_tx_mult == 0) e->timer0_tx_mult = 1; /* real-clock TX scale (ticks/us); calibrated */
    memset(e->oled_fb, 0, PA_OLED_FB_SIZE);
    memset(e->oled_work, 0, PA_OLED_FB_SIZE);
    e->oled_page = 0;
    e->oled_col = 0;
    e->oled_work_dirty = 0;
    e->oled_frames = 0;
    e->led_on = false;
    e->led_logged = false;
    e->tx_capturing = false;
    for(int i = 0; i < 16; i++) e->gpio_din[i] = 0xFFFF; /* all released */
    for(int b = 0; b < PA_BTN_COUNT; b++) e->btn_pressed[b] = 0;
    si4432_init(&e->si);
    e->si_dtmod = 0; /* direct-GPIO */
    e->si_modtyp = 0;
    e->si_devi_hz = 0;
}

void pa_emu_init(
    PaEmu* e,
    ThumbCore* cpu,
    uint8_t* flash,
    uint32_t flash_size,
    uint8_t* ram,
    const PaProfile* prof,
    const PaEmuOps* ops) {
    memset(e, 0, sizeof(*e));
    e->cpu = cpu;
    e->flash = flash;
    e->flash_size = flash_size;
    e->ram = ram;
    e->prof = prof ? prof : &PA_PROFILE_PANDORA;
    if(ops) e->ops = *ops;
    e->rf_follow = true;
    e->wfi_pc_hint = 0xFFFFFFFFu;

    thumb_init(cpu);
    /* whole image is a DIRECT region: flash reads inside it never hit the callback.
     * Use the caller's flash_size (T-Embed = full PA_FLASH_SIZE in PSRAM; Cardputer
     * ADV = a smaller internal-SRAM buffer sized to the real code). Reads above it
     * fall through to mmio_read (returns 0), which the firmware never does -- its
     * code/rodata ends well below the buffer and the 0xFF padding is unaddressed. */
    thumb_set_regions(cpu, flash, PA_FLASH_BASE, flash_size, ram, PA_RAM_BASE, PA_RAM_SIZE);
    thumb_set_mem_cb(cpu, mmio_read, mmio_write, e);
    thumb_set_hook(cpu, code_hook, e);
    find_wfi(e);
}

bool pa_emu_boot(PaEmu* e) {
    pa_emu_reset(e);
    return auto_unlock(e);
}
