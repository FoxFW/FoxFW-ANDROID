/* =====================================================================
 * pic_emu.h - portable, headless DXL-5000 (PIC18) keyfob emulator.
 *
 * Pure C (no Furi / FreeRTOS / gui). The driver logic is extracted verbatim
 * from the Flipper app applications/system/PandoraPIC/main.c the same way
 * pa_emu.c / pa_rf.c were extracted from PandoraARM/main.c:
 *   - HW profiles (mariofull / mario2): SW-SPI serializer hook, OOK bit-bang
 *     LAT pin, PIN-validation PCs, boot/cinit, PORTB.1 handshake.
 *   - boot-to-main-loop (both XC8 runtime variants),
 *   - the periodic keyfob tick (TMR0 -> firmware software ISR),
 *   - button injection (register 0xF8E, active-low),
 *   - OOK TX edge capture off the firmware's LAT pin,
 *   - SSD1306 GDDRAM reconstruction (best-effort, for the optional screen).
 *
 * Runs the REAL firmware image on the phone; the Flipper is only the radio.
 * ===================================================================== */
#ifndef PIC_EMU_H
#define PIC_EMU_H

#include <stdint.h>
#include <stdbool.h>
#include "pic18_core.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Flipper-key bits used by pic_emu_set_button() (mirror PandoraPIC KBIT_*). */
enum {
    PIC_KBIT_UP = 1 << 0,
    PIC_KBIT_DOWN = 1 << 1,
    PIC_KBIT_LEFT = 1 << 2,
    PIC_KBIT_RIGHT = 1 << 3,
    PIC_KBIT_OK = 1 << 4,
};

/* Reconstructed SSD1306 GDDRAM: 128 cols x 4 pages (128x32 px), 1 byte = 8
 * vertical px (bit0 top), bit=1 => lit. */
#define PIC_GDDRAM_COLS 128
#define PIC_GDDRAM_PAGES 4
#define PIC_GDDRAM_SIZE (PIC_GDDRAM_COLS * PIC_GDDRAM_PAGES)

/* HW profile (verbatim subset of PandoraPIC PicProfile; carries the pieces the
 * headless emulator + the cracker need). */
typedef struct {
    const char* name;
    uint32_t send_byte_pc;
    uint16_t byte_reg;
    uint16_t dc_flag_a, dc_flag_b;
    bool has_pin;
    uint8_t boot_mode; /* 0 run-to (PORTB.1 handshake); 1 cinit records */
    uint32_t cinit_wait_pc;
    uint32_t cinit_table, cinit_table_end, cinit_entry, cinit_exit;
    bool portb1_handshake;
    uint32_t pin_validate, pin_accept, pin_reject;
    uint16_t pin_slot_lo[4], pin_slot_hi[4];
    uint16_t pin_unlock_lo, pin_unlock_hi;
    uint16_t ook_tx_lat;
    uint8_t ook_tx_bit;
    uint8_t dc_mode;
    uint32_t dc_caller_below;
} PicProfile;

extern const PicProfile PIC_PROFILE_FULL;   /* mariofull / DXL-5000 (has PIN) */
extern const PicProfile PIC_PROFILE_MARIO2; /* mario2 / DXL-5000 v2 */

typedef struct {
    bool level;
    uint32_t duration; /* microseconds (PIC: 1 instr cycle = 1 us base) */
} PicEdge;

#define PIC_TX_MAX 1024u

typedef struct PicEmu {
    Pic18Cpu* cpu;
    const PicProfile* prof;

    /* SSD1306 GDDRAM reconstruction (best-effort; optional screen) */
    uint8_t gddram[PIC_GDDRAM_SIZE];
    uint8_t gddram_work[PIC_GDDRAM_SIZE];
    uint16_t gd_page, gd_col;
    int gd_cmd_arg_left;
    uint8_t disp_colreset; /* 1: page set resets column */
    uint8_t disp_dc;       /* 0 => caller-based D/C (both bundled profiles) */

    /* buttons: BTN_*_BIT bits currently pressed (register 0xF8E, active-low) */
    uint8_t btn_mask;

    /* OOK TX capture off the firmware's LAT pin */
    bool rf_tx_enabled;
    bool tx_last_level;
    uint64_t tx_last_cycles;
    PicEdge tx[PIC_TX_MAX];
    uint32_t tx_n;
} PicEmu;

/* Select the profile: config word CONFIG3L (0xA2 => full), else name hints,
 * else code size (>= 0x19000 => full). Mirrors PandoraPIC pick_profile. */
const PicProfile* pic_pick_profile(const char* name, const Pic18Cpu* cpu, uint32_t codesz);

/* Wire cpu<->emu (cpu must already have prog assigned + image loaded). */
void pic_emu_init(PicEmu* e, Pic18Cpu* cpu, const PicProfile* prof);

/* Reset + run the XC8 runtime to the firmware main loop. True on success. */
bool pic_emu_boot(PicEmu* e);

/* Arm the keyfob periodic tick (must be called once after boot before run). */
void pic_emu_arm_tick(PicEmu* e);

/* Run up to `steps` instructions (tick ISR bridged via on_irq). */
void pic_emu_run(PicEmu* e, uint64_t steps);

/* Press/release a Flipper key (mapped to the keyfob button register). */
void pic_emu_set_button(PicEmu* e, int kbit, bool pressed);

static inline const uint8_t* pic_emu_gddram(const PicEmu* e) { return e->gddram; }

/* Variant codes (shared with the cracker). */
enum { PIC_VAR_UNKNOWN = 0, PIC_VAR_FULL = 1, PIC_VAR_MARIO2 = 2 };

/* PIN verdict (shared with the cracker). */
enum { PIC_PIN_NA = 0, PIC_PIN_ACCEPT = 1, PIC_PIN_REJECT = 2, PIC_PIN_NOVERDICT = 3 };

/* Inject a numeric PIN and run the firmware's REAL validation routine (with a
 * return sentinel + accept/reject hook, restoring the main-loop context). The
 * DXL-5000 (mariofull) only transmits its OOK service code after ACCEPT.
 * Returns a PIC_PIN_* code. No-PIN profiles (mario2) return PIC_PIN_NA. */
int pic_enter_pin(PicEmu* e, int pin);

/* Load a raw firmware buffer (.bin, or Intel-HEX if it begins with ':') into a
 * freshly-allocated prog image. Returns malloc'd PIC18_PROG_SIZE buffer (caller
 * frees) and sets *codesz; NULL on failure. */
uint8_t* pic_load_image(const uint8_t* fw, uint32_t len, Pic18Cpu* cpu, uint32_t* codesz);

/* One-shot clone/forge: boot the image, enter the unlock `pin` (ignored for
 * no-PIN variants), let the firmware auto-transmit its OOK service code, and
 * capture it as signed-microsecond pulses (+carrier-on / -carrier-off) into
 * out_us. Returns pulse count (0 = none). */
int pic_forge(const uint8_t* fw, uint32_t len, const char* name, int pin, int32_t* out_us, int max);

#ifdef __cplusplus
}
#endif
#endif /* PIC_EMU_H */
