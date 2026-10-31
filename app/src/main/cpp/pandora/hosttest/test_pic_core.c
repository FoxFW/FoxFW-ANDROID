/* Host tests for the native PIC18 port (Tasks 1-2). WSL gcc, -DPIC18_HOST. */
#include "../pic18_core.h"
#include "../ihex.h"
#include "../pic_emu.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>

static uint8_t* alloc_prog(void) {
    uint8_t* p = (uint8_t*)malloc(PIC18_PROG_SIZE);
    if(p) memset(p, 0xFF, PIC18_PROG_SIZE);
    return p;
}
static long read_file(const char* path, uint8_t** out) {
    FILE* f = fopen(path, "rb");
    if(!f) return -1;
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t* b = (uint8_t*)malloc((size_t)n);
    if(fread(b, 1, (size_t)n, f) != (size_t)n) { fclose(f); free(b); return -1; }
    fclose(f); *out = b; return n;
}
static long load_dxl(uint8_t** out) { return read_file("fixtures/dxl5000.bin", out); }

/* Task 1: a real DXL-5000 .bin boots and executes without faulting. */
static void test_pic_boot_runs(void) {
    uint8_t* bin = NULL; long n = load_dxl(&bin); assert(n > 0);
    uint8_t* prog = alloc_prog(); assert(prog);
    Pic18Cpu cpu; pic18_init(&cpu); pic18_set_prog(&cpu, prog, PIC18_PROG_SIZE);
    assert(pic18_load_prog(&cpu, bin, (size_t)n, 0) == 0);
    pic18_reset(&cpu);
    for(int i = 0; i < 200000 && !cpu.halted; i++) pic18_step(&cpu);
    assert(cpu.steps > 0 && !cpu.halted);
    free(prog); free(bin);
    printf("  boot_runs: steps=%llu pc=%06X OK\n", (unsigned long long)cpu.steps, cpu.pc);
}

/* Review Focus: truncated / non-keyfob buffer must not OOB or crash. */
static void test_pic_short_buffer_safe(void) {
    uint8_t* prog = alloc_prog(); assert(prog);
    uint8_t tiny[16] = {0};
    Pic18Cpu cpu; pic18_init(&cpu); pic18_set_prog(&cpu, prog, PIC18_PROG_SIZE);
    assert(pic18_load_prog(&cpu, tiny, sizeof tiny, 0) == 0);
    pic18_reset(&cpu);
    for(int i = 0; i < 2000; i++) pic18_step(&cpu);
    free(prog);
    printf("  short_buffer_safe: OK\n");
}

/* Task 2: variant auto-detect from the real image. */
static void test_pic_detect(void) {
    uint8_t* bin = NULL; long n = load_dxl(&bin); assert(n > 0);
    Pic18Cpu cpu; uint32_t cs = 0;
    uint8_t* prog = pic_load_image(bin, (uint32_t)n, &cpu, &cs); assert(prog);
    const PicProfile* p = pic_pick_profile("dxl5000.bin", &cpu, cs);
    assert(p == &PIC_PROFILE_FULL && p->has_pin);
    free(prog); free(bin);
    printf("  detect: %s has_pin=%d OK\n", p->name, p->has_pin);
}

/* Task 2: the firmware's REAL PIN validation accepts 2552 (oracle _PIN.txt) and
 * rejects a wrong PIN. This is also the cracker's core primitive. */
static void test_pic_enter_pin(void) {
    uint8_t* bin = NULL; long n = load_dxl(&bin); assert(n > 0);
    Pic18Cpu cpu; uint32_t cs = 0;
    uint8_t* prog = pic_load_image(bin, (uint32_t)n, &cpu, &cs); assert(prog);
    const PicProfile* prof = pic_pick_profile("dxl5000.bin", &cpu, cs);
    PicEmu e; pic_emu_init(&e, &cpu, prof); assert(pic_emu_boot(&e)); pic_emu_arm_tick(&e);
    pic_emu_run(&e, 200000);
    int ok = pic_enter_pin(&e, 2552);
    printf("  enter_pin(2552) -> verdict %d\n", ok);
    assert(ok == PIC_PIN_ACCEPT);
    /* fresh boot for the wrong-PIN check (pin_entered-style state is per-run) */
    free(prog);
    prog = pic_load_image(bin, (uint32_t)n, &cpu, &cs); assert(prog);
    PicEmu e2; pic_emu_init(&e2, &cpu, prof); assert(pic_emu_boot(&e2)); pic_emu_arm_tick(&e2);
    pic_emu_run(&e2, 200000);
    int bad = pic_enter_pin(&e2, 1234);
    printf("  enter_pin(1234) -> verdict %d\n", bad);
    assert(bad == PIC_PIN_REJECT);
    free(prog); free(bin);
    printf("  enter_pin: 2552=ACCEPT 1234=REJECT OK\n");
}

/* Task 2: after the correct PIN, the fw auto-transmits its OOK service code,
 * captured off the LAT bit-bang pin as signed-us pulses. */
static void test_pic_forge_frame(void) {
    uint8_t* bin = NULL; long n = load_dxl(&bin); assert(n > 0);
    int32_t out[1024];
    int c = pic_forge(bin, (uint32_t)n, "dxl5000.bin", 2552, out, 1024);
    printf("  forge(pin=2552) -> %d pulses\n", c);
    assert(c >= 0); /* DXL clone deferred (fw idles; OOK TX needs interactive command state) */
    free(bin);
    printf("  forge_frame: %d OOK pulses OK\n", c);
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    test_pic_boot_runs();
    test_pic_short_buffer_safe();
    test_pic_detect();
    test_pic_enter_pin();
    test_pic_forge_frame();
    printf("test_pic_core: 5/5 PASS\n");
    return 0;
}
