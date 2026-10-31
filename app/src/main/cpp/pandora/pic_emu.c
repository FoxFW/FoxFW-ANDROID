/* pic_emu.c - headless DXL-5000 (PIC18) keyfob emulator.
 * Driver logic ported verbatim from applications/system/PandoraPIC/main.c,
 * stripped of all Furi/gui. See pic_emu.h. */
#include "pic_emu.h"
#include "ihex.h"
#include <stdlib.h>
#include <string.h>

/* ---- SFR / tick constants (verbatim from PandoraPIC/main.c) ---- */
#define SFR_INTCON       0xFF2u
#define SFR_T0CON        0xFD5u
#define SFR_TMR0H        0xFD7u
#define SFR_TMR0L        0xFD6u
#define INTCON_TMR0IF    0x04u
#define TICK_ISR_VECTOR  0x000008u
#define TICK_ENABLE_REG  0xEC6u
#define TICK_PENDING_REG 0xECEu
#define TICK_BIT         0x02u
#define BTN_REG          0xF8Eu
#define SFR_PORTB        0xF81u
#define SFR_LATB         0xF8Bu

/* keyfob button bits in register 0xF8E (active-low) */
#define BTN_B1_BIT 1 /* up / + */
#define BTN_B2_BIT 2 /* OK / confirm */
#define BTN_B3_BIT 4 /* menu / down */
#define BTN_B5_BIT 6 /* left */
#define BTN_B6_BIT 7 /* right */
#define BTN_ALL_MASK ((1 << 1) | (1 << 2) | (1 << 4) | (1 << 6) | (1 << 7))

/* PIC cycles->us: internal osc, 4MHz => 1 instr cycle = 1 us base. */
#define PIC_CYCLE_US_NUM 1u
#define PIC_CYCLE_US_DEN 1u

const PicProfile PIC_PROFILE_FULL = {
    .name = "mariofull",
    .send_byte_pc = 0x00174Cu,
    .byte_reg = 0x4FEu,
    .dc_flag_a = 0x1F6u,
    .dc_flag_b = 0x1F7u,
    .has_pin = true,
    .boot_mode = 0,
    .cinit_wait_pc = 0x001712u,
    .cinit_table = 0,
    .cinit_table_end = 0,
    .cinit_entry = 0,
    .cinit_exit = 0,
    .portb1_handshake = true,
    .pin_validate = 0x018D76u,
    .pin_accept = 0x018DA8u,
    .pin_reject = 0x018DE2u,
    .pin_slot_lo = {0x1D9u, 0x1DBu, 0x1DDu, 0x1DFu},
    .pin_slot_hi = {0x1DAu, 0x1DCu, 0x1DEu, 0x1E0u},
    .pin_unlock_lo = 0x1D7u,
    .pin_unlock_hi = 0x1D8u,
    .ook_tx_lat = 0xF8Bu,
    .ook_tx_bit = 0u,
    .dc_mode = 1u,
    .dc_caller_below = 0x4000u,
};
const PicProfile PIC_PROFILE_MARIO2 = {
    .name = "mario2",
    .send_byte_pc = 0x001758u,
    .byte_reg = 0x4EFu,
    .dc_flag_a = 0x1F7u,
    .dc_flag_b = 0x1F8u,
    .has_pin = false,
    .boot_mode = 1,
    .cinit_wait_pc = 0,
    .cinit_table = 0x017530u,
    .cinit_table_end = 0x0175F8u,
    .cinit_entry = 0x0175FAu,
    .cinit_exit = 0x017636u,
    .portb1_handshake = false,
    .pin_validate = 0,
    .pin_accept = 0,
    .pin_reject = 0,
    .pin_slot_lo = {0, 0, 0, 0},
    .pin_slot_hi = {0, 0, 0, 0},
    .pin_unlock_lo = 0,
    .pin_unlock_hi = 0,
    .ook_tx_lat = 0xF8Bu,
    .ook_tx_bit = 1u,
    .dc_mode = 1u,
    .dc_caller_below = 0x4000u,
};

static bool pic_name_has(const char* hay, const char* needle) {
    if(!hay) return false;
    for(const char* p = hay; *p; p++) {
        const char* a = p;
        const char* b = needle;
        while(*a && *b && ((*a | 0x20) == (*b | 0x20))) { a++; b++; }
        if(!*b) return true;
    }
    return false;
}

const PicProfile* pic_pick_profile(const char* name, const Pic18Cpu* cpu, uint32_t codesz) {
    if(cpu) {
        for(uint16_t i = 0; i < cpu->config_count; i++) {
            if(cpu->config_addr[i] == 0x300004u)
                return (cpu->config_val[i] == 0xA2u) ? &PIC_PROFILE_FULL : &PIC_PROFILE_MARIO2;
        }
    }
    if(pic_name_has(name, "full")) return &PIC_PROFILE_FULL;
    if(pic_name_has(name, "mario2") || pic_name_has(name, "v2")) return &PIC_PROFILE_MARIO2;
    return (codesz >= 0x19000u) ? &PIC_PROFILE_FULL : &PIC_PROFILE_MARIO2;
}

/* ---- GDDRAM reconstruction (best-effort optional screen) ---- */
static void gddram_commit(PicEmu* e) {
    bool any = false;
    for(int i = 0; i < PIC_GDDRAM_SIZE; i++)
        if(e->gddram_work[i]) { any = true; break; }
    if(any) memcpy(e->gddram, e->gddram_work, PIC_GDDRAM_SIZE);
}

static void gddram_feed(PicEmu* e, uint8_t b, bool is_cmd) {
    if(is_cmd) {
        if(e->gd_cmd_arg_left > 0) { e->gd_cmd_arg_left--; return; }
        if(b == 0xD5) { gddram_commit(e); e->gd_cmd_arg_left = 1; return; }
        if(b >= 0xB0 && b <= 0xB7) {
            e->gd_page = b - 0xB0;
            if(e->disp_colreset) e->gd_col = 0;
        } else if(b <= 0x0F) {
            e->gd_col = (e->gd_col & 0xF0) | b;
        } else if(b >= 0x10 && b <= 0x1F) {
            e->gd_col = (e->gd_col & 0x0F) | ((b & 0x0F) << 4);
        } else if(b == 0x20) {
            e->gd_cmd_arg_left = 1;
        } else if(b == 0x21) {
            e->gd_cmd_arg_left = 2; e->gd_col = 0;
        } else if(b == 0x22) {
            e->gd_cmd_arg_left = 2; e->gd_page = 0;
        } else if(b == 0x81 || b == 0xA8 || b == 0xD3 || b == 0xDA || b == 0xD9 ||
                  b == 0xDB || b == 0x8D || b == 0xAD) {
            e->gd_cmd_arg_left = 1;
        }
    } else {
        if(e->gd_page < PIC_GDDRAM_PAGES && e->gd_col < PIC_GDDRAM_COLS)
            e->gddram_work[e->gd_page * PIC_GDDRAM_COLS + e->gd_col] = b;
        e->gd_col++;
        if(e->gd_col >= PIC_GDDRAM_COLS) {
            e->gd_col = 0;
            e->gd_page = (e->gd_page + 1) % PIC_GDDRAM_PAGES;
        }
    }
}

static void code_hook(Pic18Cpu* cpu, uint32_t pc, void* ctx) {
    PicEmu* e = (PicEmu*)ctx;
    if(pc != e->prof->send_byte_pc) return;
    uint8_t b = pic18_read_ram(cpu, e->prof->byte_reg);
    bool is_cmd;
    if(e->disp_dc == 0u) {
        uint32_t caller = (cpu->stkptr > 1) ? cpu->stack[cpu->stkptr - 2] : 0;
        is_cmd = (caller < e->prof->dc_caller_below);
    } else {
        uint8_t fa = pic18_read_ram(cpu, e->prof->dc_flag_a);
        uint8_t fb = pic18_read_ram(cpu, e->prof->dc_flag_b);
        is_cmd = (fa != 0) || (fb != 0);
    }
    gddram_feed(e, b, is_cmd);
}

/* ---- tick ISR bridge ---- */
static int32_t pic_irq_hook(Pic18Cpu* cpu, void* ctx) {
    (void)ctx;
    uint8_t intcon = pic18_read_ram(cpu, SFR_INTCON);
    if(intcon & INTCON_TMR0IF) {
        pic18_write_ram(cpu, SFR_INTCON, (uint8_t)(intcon & ~INTCON_TMR0IF));
        pic18_write_ram(cpu, TICK_PENDING_REG,
                        (uint8_t)(pic18_read_ram(cpu, TICK_PENDING_REG) | TICK_BIT));
    }
    if((pic18_read_ram(cpu, TICK_ENABLE_REG) & TICK_BIT) &&
       (pic18_read_ram(cpu, TICK_PENDING_REG) & TICK_BIT))
        return (int32_t)TICK_ISR_VECTOR;
    return -1;
}

/* ---- buttons + RF input ---- */
static int port_read_hook(Pic18Cpu* cpu, uint16_t addr, void* ctx) {
    PicEmu* e = (PicEmu*)ctx;
    if(addr == BTN_REG) return (int)(0xFFu & ~e->btn_mask);
    if(addr == SFR_PORTB && e->prof && e->prof->portb1_handshake) {
        uint8_t latb = pic18_read_ram(cpu, SFR_LATB);
        uint8_t v = (uint8_t)(latb & (uint8_t)~0x02u); /* bit1 = 0 (ACK) */
        return (int)v;
    }
    return -1;
}

/* ---- OOK TX edge capture off the LAT pin ---- */
static void lat_write_hook(Pic18Cpu* cpu, uint16_t addr, uint8_t val, void* ctx) {
    PicEmu* e = (PicEmu*)ctx;
    if(!e->rf_tx_enabled) return;
    if(addr != e->prof->ook_tx_lat) return;
    bool level = (val >> e->prof->ook_tx_bit) & 1u;
    if(level == e->tx_last_level) return;
    uint64_t now = cpu->cycles;
    uint64_t dcyc = now - e->tx_last_cycles;
    e->tx_last_cycles = now;
    uint32_t dur_us = (uint32_t)((dcyc * PIC_CYCLE_US_NUM) / PIC_CYCLE_US_DEN);
    if(dur_us == 0) dur_us = 1;
    if(e->tx_n < PIC_TX_MAX) {
        e->tx[e->tx_n].level = e->tx_last_level;
        e->tx[e->tx_n].duration = dur_us;
        e->tx_n++;
    }
    e->tx_last_level = level;
}

static void set_button_bit(PicEmu* e, uint8_t bit, bool pressed) {
    if(pressed) e->btn_mask |= (uint8_t)(1u << bit);
    else e->btn_mask &= (uint8_t)~(1u << bit);
    e->btn_mask &= BTN_ALL_MASK;
}

void pic_emu_set_button(PicEmu* e, int kbit, bool pressed) {
    switch(kbit) {
    case PIC_KBIT_UP: set_button_bit(e, BTN_B1_BIT, pressed); break;
    case PIC_KBIT_DOWN: set_button_bit(e, BTN_B3_BIT, pressed); break;
    case PIC_KBIT_OK: set_button_bit(e, BTN_B2_BIT, pressed); break;
    case PIC_KBIT_LEFT: set_button_bit(e, BTN_B5_BIT, pressed); break;
    case PIC_KBIT_RIGHT: set_button_bit(e, BTN_B6_BIT, pressed); break;
    default: break;
    }
}

/* ---- cinit (mario2 XC8 runtime) ---- */
static void apply_cinit_records(PicEmu* e) {
    Pic18Cpu* cpu = e->cpu;
    uint32_t tp = e->prof->cinit_table;
    uint32_t end = e->prof->cinit_table_end;
    uint16_t fsr0 = 0x000;
    int guard = 0;
    while(tp < end && guard++ < 512) {
        uint8_t n = (tp < cpu->prog_size) ? cpu->prog[tp] : 0;
        tp++;
        if(n == 0) break;
        uint8_t fd = (tp < cpu->prog_size) ? cpu->prog[tp] : 0;
        tp++;
        if(fd & 0x80) {
            uint8_t lo = (tp < cpu->prog_size) ? cpu->prog[tp] : 0;
            tp++;
            fsr0 = (uint16_t)(((fd & 0x0F) << 8) | lo);
        }
        if(!(fd & 0x40)) tp++;
        if(tp + n > end + 2) break;
        for(uint8_t k = 0; k < n; k++) {
            uint8_t b = (tp < cpu->prog_size) ? cpu->prog[tp] : 0;
            tp++;
            if(fsr0 < 0xF80u) cpu->ram[fsr0 & 0xFFF] = b;
            fsr0 = (uint16_t)((fsr0 + 1) & 0xFFF);
        }
    }
}

void pic_emu_init(PicEmu* e, Pic18Cpu* cpu, const PicProfile* prof) {
    memset(e, 0, sizeof(*e));
    e->cpu = cpu;
    e->prof = prof;
    e->disp_colreset = 1;
    e->disp_dc = 0; /* both bundled profiles use caller-based D/C */
    cpu->on_code = code_hook;
    cpu->code_ctx = e;
    cpu->on_port_read = port_read_hook;
    cpu->port_read_ctx = e;
    cpu->on_lat_write = lat_write_hook;
    cpu->lat_write_ctx = e;
}

bool pic_emu_boot(PicEmu* e) {
    Pic18Cpu* cpu = e->cpu;
    pic18_reset(cpu);
    if(e->prof->boot_mode == 1) {
        uint64_t budget = 2000000;
        while(cpu->pc != e->prof->cinit_entry && budget > 0) {
            pic18_step(cpu);
            budget--;
            if(cpu->halted) break;
        }
        if(cpu->pc != e->prof->cinit_entry) return false;
        apply_cinit_records(e);
        cpu->pc = e->prof->cinit_exit;
        return true;
    } else {
        uint64_t budget = 3000000;
        while(cpu->pc != e->prof->cinit_wait_pc && budget > 0) {
            pic18_step(cpu);
            budget--;
            if(cpu->halted) break;
        }
        return cpu->pc == e->prof->cinit_wait_pc;
    }
}

void pic_emu_arm_tick(PicEmu* e) {
    Pic18Cpu* cpu = e->cpu;
    pic18_write_data(cpu, SFR_T0CON, 0x80);
    pic18_write_data(cpu, SFR_TMR0H, 0x00);
    pic18_write_data(cpu, SFR_TMR0L, 0x00);
    pic18_write_ram(cpu, TICK_ENABLE_REG,
                    (uint8_t)(pic18_read_ram(cpu, TICK_ENABLE_REG) | TICK_BIT));
    cpu->on_irq = pic_irq_hook;
    cpu->irq_ctx = e;
}

void pic_emu_run(PicEmu* e, uint64_t steps) {
    Pic18Cpu* cpu = e->cpu;
    for(uint64_t i = 0; i < steps && !cpu->halted; i++) pic18_step(cpu);
}

uint8_t* pic_load_image(const uint8_t* fw, uint32_t len, Pic18Cpu* cpu, uint32_t* codesz) {
    uint8_t* prog = (uint8_t*)malloc(PIC18_PROG_SIZE);
    if(!prog) return NULL;
    memset(prog, 0xFF, PIC18_PROG_SIZE);
    pic18_init(cpu);
    pic18_set_prog(cpu, prog, PIC18_PROG_SIZE);
    int rc;
    if(len > 0 && fw[0] == (uint8_t)':') {
        rc = pic18_load_hex_mem(cpu, fw, len);
        if(codesz) *codesz = 0x1A180u; /* full prog span; config words set by loader */
    } else {
        rc = pic18_load_prog(cpu, fw, len, 0);
        if(codesz) *codesz = len;
    }
    if(rc != 0) { free(prog); return NULL; }
    return prog;
}

/* ---- PIN entry (shared with the cracker) ---- */
#define PIN_RET_SENTINEL 0x01FFFEu
typedef struct { const PicProfile* prof; int accept, reject, done; } PinRun;

static void pin_code_hook(Pic18Cpu* cpu, uint32_t pc, void* ctx) {
    PinRun* r = (PinRun*)ctx;
    if(pc == r->prof->pin_accept) { r->accept = 1; r->done = 1; }
    else if(pc == r->prof->pin_reject) { r->reject = 1; r->done = 1; }
    else if(pc == PIN_RET_SENTINEL) { cpu->halted = 1; r->done = 1; }
}

int pic_enter_pin(PicEmu* e, int pin) {
    const PicProfile* prof = e->prof;
    Pic18Cpu* cpu = e->cpu;
    if(!prof->has_pin) return PIC_PIN_NA;

    int32_t v = pin < 0 ? 0 : pin;
    int digits[4] = {(v / 1000) % 10, (v / 100) % 10, (v / 10) % 10, v % 10};
    for(int i = 0; i < 4; i++) {
        pic18_write_ram(cpu, prof->pin_slot_lo[i], (uint8_t)(digits[i] & 0xFF));
        pic18_write_ram(cpu, prof->pin_slot_hi[i], (uint8_t)((digits[i] >> 8) & 0xFF));
    }
    pic18_write_ram(cpu, prof->pin_unlock_lo, 0);
    pic18_write_ram(cpu, prof->pin_unlock_hi, 0);

    uint32_t saved_pc = cpu->pc;
    uint8_t saved_bsr = cpu->ram[PIC18_SFR_BSR];
    uint8_t saved_stkptr = cpu->stkptr;
    uint8_t saved_halted = cpu->halted;
    Pic18CodeHook saved_hook = cpu->on_code;
    void* saved_ctx = cpu->code_ctx;
    /* The validation runs on a throwaway stack frame (stkptr=0 + sentinel push),
     * which clobbers stack[0..]. Snapshot the return stack so the caller's main
     * loop can be resumed intact (matters for pic_forge resuming after unlock). */
    uint32_t saved_stack[PIC18_STACK_LEVELS];
    memcpy(saved_stack, cpu->stack, sizeof(saved_stack));

    PinRun run = {.prof = prof, .accept = 0, .reject = 0, .done = 0};
    cpu->on_code = pin_code_hook;
    cpu->code_ctx = &run;
    cpu->halted = 0;
    cpu->stkptr = 0;
    cpu->ram[PIC18_SFR_BSR] = 0;
    pic18_push(cpu, PIN_RET_SENTINEL);
    cpu->pc = prof->pin_validate;

    uint64_t budget = 4000000;
    while(budget-- > 0 && !cpu->halted && !run.done) pic18_step(cpu);

    cpu->on_code = saved_hook;
    cpu->code_ctx = saved_ctx;
    cpu->pc = saved_pc;
    cpu->ram[PIC18_SFR_BSR] = saved_bsr;
    cpu->stkptr = saved_stkptr;
    cpu->halted = saved_halted;
    memcpy(cpu->stack, saved_stack, sizeof(saved_stack));

    if(run.accept) return PIC_PIN_ACCEPT;
    if(run.reject) return PIC_PIN_REJECT;
    return PIC_PIN_NOVERDICT;
}

int pic_forge(const uint8_t* fw, uint32_t len, const char* name, int pin, int32_t* out_us, int max) {
    if(!fw || len == 0 || !out_us || max <= 0) return 0;
    Pic18Cpu cpu;
    uint32_t codesz = 0;
    uint8_t* prog = pic_load_image(fw, len, &cpu, &codesz);
    if(!prog) return 0;
    const PicProfile* prof = pic_pick_profile(name, &cpu, codesz);

    PicEmu e;
    pic_emu_init(&e, &cpu, prof);
    if(!pic_emu_boot(&e)) { free(prog); return 0; }
    pic_emu_arm_tick(&e);

    /* Settle the main loop, then arm TX capture before unlocking. */
    pic_emu_run(&e, 400000);
    e.rf_tx_enabled = true;
    e.tx_last_level = ((pic18_read_ram(&cpu, prof->ook_tx_lat) >> prof->ook_tx_bit) & 1u);
    e.tx_last_cycles = cpu.cycles;
    e.tx_n = 0;

    /* The DXL-5000 (mariofull) only transmits its OOK service code after the
     * correct PIN is accepted; mario2 transmits from its own flow. */
    if(prof->has_pin) pic_enter_pin(&e, pin);
    pic_emu_run(&e, 6000000);

    int n = 0;
    for(uint32_t i = 0; i < e.tx_n && n < max; i++) {
        int32_t d = (int32_t)e.tx[i].duration;
        out_us[n++] = e.tx[i].level ? d : -d;
    }
    free(prog);
    return n;
}
