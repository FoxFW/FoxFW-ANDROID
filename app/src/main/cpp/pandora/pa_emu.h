/* =====================================================================
 * pa_emu.h - portable Pandora D-605 keyfob emulator core.
 *
 * Framework-free C (no Furi / FreeRTOS / ESP-IDF). Wraps the verbatim
 * thumb_core with the emulated keyfob hardware the firmware talks to:
 *   - EFM32 MMIO: CMU clocks, GPIO (buttons/LED/OLED-DC/radio pins),
 *     USART0 SPI, TIMER0..3, ADC
 *   - Si4432 radio register model (SPI, CS-gated against the OLED)
 *   - UC16xx/ST7528 OLED framebuffer capture (92x16 pages, 2bpp)
 *   - NVIC timer-IRQ injection, the knock-code unlock FSM, the run loop
 *
 * Ported from the Flipper PandoraARM main.c. The one architectural change:
 * the whole firmware image is a DIRECT memory region (ESP32-S3 PSRAM),
 * so the Flipper's paged FlashCache is gone - flash is just a pointer.
 *
 * Device-specific sinks (indicator LED, CC1101 follow-the-firmware RF,
 * TX bit-bang) are optional callbacks in PaEmuOps; pass NULLs on host.
 * ===================================================================== */
#ifndef PA_EMU_H
#define PA_EMU_H

#include <stdint.h>
#include <stdbool.h>
#include "thumb_core.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ---- emulated-hardware geometry ---- */
#define PA_FLASH_BASE 0x00000000u
#define PA_FLASH_SIZE 0x00042000u /* 264 KB address space */
#define PA_RAM_BASE   0x20000000u
#define PA_RAM_SIZE   0x00008000u /* 32 KB */
#define PA_OLED_COLS  92u
#define PA_OLED_VISIBLE_COLS 88u
#define PA_OLED_PAGES 16u
#define PA_OLED_FB_SIZE (PA_OLED_COLS * PA_OLED_PAGES) /* 1472 */
#define PA_SPI_RX_QUEUE_LEN 64u

/* logical button indices */
enum { PA_B_UP = 0, PA_B_DOWN, PA_B_OK, PA_B_BACK, PA_B5, PA_B6, PA_BTN_COUNT };

/* ---- Si4432 ---- */
typedef struct {
    uint8_t regs[0x80];
    uint8_t mode; /* 0 idle, 1 ready, 2 rx, 3 tx (from REG[0x07]) */
} PaSi4432;

/* ---- HW profile (per firmware variant) ---- */
typedef struct {
    const char* name;
    uint32_t flash_used; /* bytes of real code (used by the WFI scan) */
    uint32_t fb_addr;    /* framebuffer address in RAM (diagnostic) */
    uint8_t up_port, up_pin;
    uint8_t down_port, down_pin;
    uint8_t ok_port, ok_pin;
    uint8_t back_port, back_pin;
    uint8_t b5_port, b5_pin;
    uint8_t b6_port, b6_pin;
    uint8_t knock[8];
    uint8_t knock_len;
    uint8_t gate; /* 2nd-gate button index */
    uint32_t scan_head;      /* PC head of the knock scanner */
    uint32_t spin_heads[8];  /* PC heads of the wait-release spins */
    uint8_t spin_count;
    uint32_t unlock_pc;      /* PC that marks unlock complete */
    uint32_t nav_reader;     /* read_gpio_pin used post-unlock */
    uint32_t nav_readers[6]; /* all identical copies, 0-terminated */
    uint8_t led_port, led_pin;
    /* radio pins */
    uint8_t data_port, data_pin;       /* RX-data (PB0) */
    uint8_t tx_data_port, tx_data_pin; /* TX-data (PB3) */
    uint8_t rf_cs_port, rf_cs_pin;     /* Si4432 CS (PE9, active-high) */
    uint16_t rf_tx_cell_us;
    /* native-RX demod injection (Track A): feed CC1101 edges into the firmware */
    uint32_t demod_isr;       /* firmware SW-demod ISR entry */
    uint32_t timer1_cnt;      /* TIMER1_CNT MMIO = pulse-width timer */
    uint32_t rx_struct;       /* base of the firmware RX struct */
    uint32_t rx_gate;         /* active-receiver gate byte (!=0xFF) */
    uint32_t rx_carrier_gate; /* decoder edge gate (follows data level) */
    uint32_t rx_mode;         /* decoder selector */
    uint32_t rx_pending;      /* frame-ready flag (0xFF = frame decoded) */
    uint32_t vag_dec_reset;   /* VAG decoder bitcount/state/sync (cleared on long gap) */
    uint8_t  rx_clear_len;    /* transient RAM prefix cleared when arming RX */
} PaProfile;

extern const PaProfile PA_PROFILE_PANDORA;
extern const PaProfile PA_PROFILE_PANDORAMAX;

/* Pick a profile from the firmware path/name (filename heuristic, as upstream). */
const PaProfile* pa_pick_profile(const char* path);

/* ---- device sinks (optional; NULL = no-op, e.g. host test) ---- */
typedef struct {
    void (*led_set)(void* ud, bool on);                 /* indicator LED */
    void (*radio_follow_freq)(void* ud, uint32_t hz);   /* firmware retuned carrier */
    void (*radio_follow_mode)(void* ud, int nm);        /* 3=TX 2=RX 1=READY 0=IDLE */
    /* TX-data bit-bang edge. `emu_insn` is the emulated instruction count at the
     * edge (monotonic) so the sink can reconstruct per-symbol DURATIONS from the
     * firmware's timer-paced spacing, not just the level sequence (D1). */
    void (*tx_edge)(void* ud, bool level, uint64_t emu_insn);
    void (*radio_follow_mod)(void* ud, int modtyp, unsigned int devi_hz); /* 1=OOK 2=FSK 3=GFSK */
    /* Real-time clock (microseconds). When set, the TX-timer (TIMER0) CNT is
     * driven from REAL time during transmit instead of instruction count, so the
     * firmware's own delay loops block for real microseconds -> faithful TX pulse
     * widths (the interpreter can't reproduce bit timing from instruction count). */
    uint32_t (*now_us)(void* ud);
    void* ud;
} PaEmuOps;

/* ---- emulator state ---- */
typedef struct {
    ThumbCore* cpu;
    const PaProfile* prof;
    uint8_t* flash;       /* direct image (>= PA_FLASH_SIZE, PSRAM on device) */
    uint32_t flash_size;  /* bytes actually loaded */
    uint8_t* ram;         /* direct 32 KB */
    PaEmuOps ops;

    PaSi4432 si;
    uint8_t si_modtyp, si_dtmod;
    uint32_t si_devi_hz;
    bool rf_follow;

    /* USART0 SPI (shared OLED + Si4432) */
    uint8_t spi_rx[PA_SPI_RX_QUEUE_LEN];
    uint8_t spi_rx_head, spi_rx_tail;
    int spi_phase;   /* -1 idle, 0 pending-after-addr */
    uint8_t spi_kind; /* 'w'/'r' */
    uint8_t spi_reg;

    /* GPIO */
    uint16_t gpio_din[16];  /* bit=1 released, 0 pressed */
    uint16_t gpio_dout[16];
    uint64_t gpio_rd_insn[16]; /* emu_insn at last DIN read per port -> which buttons
                                * the current screen is actually polling (input router) */
    bool led_on, led_logged;
    bool tx_capturing; /* Phase 4: true while capturing the TX-data bit-bang */
    uint32_t tx_t0_us;       /* real-time (now_us) at TX start, for the real-clock TX timer */
    uint32_t cnt_t0_us[4];   /* real-time at the last TIMER CNT write (reset) during TX, so a
                              * "write CNT=0; wait CNT>=N" delay loop counts cleanly from 0 */
    uint32_t timer0_tx_mult; /* TIMER0 ticks per real microsecond during TX (calibrated) */

    /* OLED capture (double-buffered) */
    uint8_t oled_fb[PA_OLED_FB_SIZE];   /* visible */
    uint8_t oled_work[PA_OLED_FB_SIZE]; /* work */
    uint8_t oled_page;
    uint16_t oled_col;
    uint8_t oled_work_dirty;
    uint32_t oled_frames; /* visible non-blank commits */

    /* timers (free-running model) */
    uint64_t timer_t0[4];
    uint32_t timer_top[4];
    uint32_t timer_rate[4];

    /* RX feed (Phase 4 + Track A native demod injection) */
    bool rx_feeding;
    uint32_t rx_pulse_ticks;
    bool rx_prev_level;
    uint32_t rx_injected;

    /* run/IRQ bookkeeping */
    uint64_t ninsn;
    uint32_t next_pc;
    uint32_t pc;
    int in_irq;
    int irq_rr;
    uint32_t tick_count;
    uint64_t last_tick_insn;
    uint32_t wfi_pc_hint;

    /* unlock + knock FSM */
    int unlocked;
    int max_step;
    int knock_active;
    const uint8_t* knock_seq;
    uint8_t knock_len;
    uint8_t knock_idx;
    uint8_t knock_sub;
    uint8_t knock_cur;
    uint32_t knock_spins;
    uint8_t knock_user[8];
    uint8_t knock_user_len;

    /* nav FSM (Phase 3 input) - post-unlock button injection synced to the
     * firmware's read_gpio_pin; inert during boot (nav_active stays 0). */
    int nav_active;
    uint8_t nav_btn, nav_port, nav_pin;
    uint8_t nav_phase; /* 0 press, 1 hold, 2 done */
    uint32_t nav_reads, nav_reads_target;

    uint8_t btn_pressed[PA_BTN_COUNT];

    /* diagnostics (free; help bring-up) */
    uint32_t dbg_spi_oled, dbg_spi_si;
    uint32_t dbg_oled_cmd, dbg_oled_data, dbg_oled_af;
    uint32_t dbg_oled_commit_blank, dbg_oled_commit_unchanged;
    uint32_t dbg_cs_low, dbg_cs_high;
    uint32_t dbg_oled_data_nz, dbg_oled_data_dropped;
    uint16_t dbg_max_page, dbg_max_col;
} PaEmu;

/* Wire the core + regions + profile. flash points at the loaded image
 * (padded/backed to PA_FLASH_SIZE), ram at a 32 KB buffer. ops may be NULL. */
void pa_emu_init(
    PaEmu* e,
    ThumbCore* cpu,
    uint8_t* flash,
    uint32_t flash_size,
    uint8_t* ram,
    const PaProfile* prof,
    const PaEmuOps* ops);

/* Reset from the image vector table and init all peripherals. */
void pa_emu_reset(PaEmu* e);

/* Full boot: reset + auto knock-unlock + UI settle (blocking, ~ms of emu). */
bool pa_emu_boot(PaEmu* e);

/* Run one UI frame worth of firmware (steps instructions, ticks timers). */
void pa_emu_run_frame(PaEmu* e, uint32_t insns);

/* --- native RX (Track A): feed a demodulated CC1101 edge into the emulated
 * firmware's own demod ISR so the firmware decodes it (OLED shows the result).
 * Call pa_emu_rx_prime() once when arming RX, then pa_emu_rx_feed() per edge.
 * Returns 1 once the firmware flags a completed frame (rx_pending==0xFF). */
void pa_emu_rx_prime(PaEmu* e, bool init_level);
int  pa_emu_rx_feed(PaEmu* e, bool level, uint32_t dur_us);

/* Inject a physical button (pressed/released) by driving its GPIO DIN pin. */
void pa_emu_set_button(PaEmu* e, int btn, bool pressed);

/* Optional debug/forward hook: called on every Si4432 SPI register write
 * (reg,val). NULL by default (no effect). Captures the TX FIFO stream. */
extern void (*pa_dbg_si_write)(unsigned char reg, unsigned char val);
extern void (*pa_dbg_si_read)(unsigned char reg, unsigned char val);
/* Debug/forward hook on EVERY mmio write (addr,val). NULL default. */
extern void (*pa_dbg_mmio_write)(unsigned int addr, unsigned int val);
extern void (*pa_dbg_mmio_read)(unsigned int addr);
extern void (*pa_dbg_pc)(unsigned int pc);
extern int pa_dbg_force_hook;

/* Post-unlock navigation: inject one keyfob button (PA_B_*), synced to the
 * firmware's read_gpio_pin scanner. hold=true = long press. Returns true if
 * the firmware dispatched the press (release observed). Runs the emulator
 * internally (blocking) and settles until the UI redraws. */
bool pa_emu_nav_button(PaEmu* e, int btn, bool hold);
/* pick UP/DOWN vs B5/B6 for an encoder turn, by what the current screen polls */
int pa_emu_scroll_button(PaEmu* e, int dir);

/* Accessors */
static inline const uint8_t* pa_emu_framebuffer(const PaEmu* e) { return e->oled_fb; }
static inline uint32_t pa_emu_frame_count(const PaEmu* e) { return e->oled_frames; }
static inline bool pa_emu_unlocked(const PaEmu* e) { return e->unlocked != 0; }

#ifdef __cplusplus
}
#endif
#endif /* PA_EMU_H */
