/* Pandora offload JNI: runs the real D-605 / MAX keyfob firmware in the headless
 * emulator on the phone to FORGE command frames, and DECODEs captured frames to
 * recover serial + counter + command. Ported from pandora-tembed/codec (host-validated). */
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include "pa_emu.h"
#include "thumb_core.h"

#define SENT 0x0BADF00Du

/* per-model driving constants (host-RE'd):
 *  model 0 = D-605 : frame buf 0x20001714, build PC 0xf40A, byte-sender 0x92A0, skip 2 (AA AA)
 *  model 1 = MAX   : frame buf 0x20000B9C, build PC 0x107D0, byte-sender 0xA680, sync 0xD7 */
typedef struct { uint32_t frame_off, build_pc, byte_sender, state_ptr; int find_d7; } ModelCfg;
static const ModelCfg MODELS[2] = {
    { 0x1714, 0xf40A,  0x92A0, 0x20001714u, 0 },
    { 0x0B9C, 0x107D0, 0xA680, 0x20000B9Cu, 1 },
};

static uint8_t  g_fw[PA_FLASH_SIZE];
static uint8_t  g_ram[PA_RAM_SIZE];
static PaEmu    g_e;
static ThumbCore g_cpu;
static uint8_t  g_sent[256];
static int      g_nb;
static uint32_t g_bspc;

static void bytehook(void* ctx, uint32_t pc) {
    (void)ctx;
    if ((pc & ~1u) == g_bspc && g_nb < 256)
        g_sent[g_nb++] = (uint8_t)thumb_get_reg(&g_cpu, 0);
}

JNIEXPORT jbyteArray JNICALL
Java_com_foxfw_companion_PandoraNative_forge(JNIEnv* env, jobject thiz,
    jint model, jbyteArray jfw, jbyteArray jserial, jint counter, jint cmd) {
    (void)thiz;
    if (model < 0 || model > 1) return NULL;
    const ModelCfg* M = &MODELS[model];

    jsize fwlen = (*env)->GetArrayLength(env, jfw);
    if (fwlen > PA_FLASH_SIZE) fwlen = PA_FLASH_SIZE;
    memset(g_fw, 0xFF, sizeof g_fw);
    (*env)->GetByteArrayRegion(env, jfw, 0, fwlen, (jbyte*)g_fw);

    jbyte ser[4]; (*env)->GetByteArrayRegion(env, jserial, 0, 4, ser);

    const PaProfile* prof = (model == 1) ? &PA_PROFILE_PANDORAMAX : &PA_PROFILE_PANDORA;
    PaEmuOps ops; memset(&ops, 0, sizeof ops);
    pa_emu_init(&g_e, &g_cpu, g_fw, (uint32_t)fwlen, g_ram, prof, &ops);
    pa_emu_boot(&g_e);
    for (int i = 0; i < 60; i++) pa_emu_run_frame(&g_e, 50000);

    uint32_t fo = M->frame_off;
    uint32_t ctr_pre = (uint32_t)((counter - 1) & 0xFFFF);  /* build does ctr++ */
    for (int i = 0; i < 4; i++) g_e.ram[fo + i] = (uint8_t)ser[i];
    g_e.ram[fo + 5] = (uint8_t)(ctr_pre >> 8);
    g_e.ram[fo + 6] = (uint8_t)(ctr_pre & 0xFF);
    if (model == 0) for (int i = 0; i < 4; i++) g_e.ram[0x1973 + i] = (uint8_t)ser[i];

    g_nb = 0; g_bspc = M->byte_sender;
    thumb_set_hook(&g_cpu, bytehook, 0);
    thumb_set_reg(&g_cpu, 13, 0x20007800);
    thumb_set_reg(&g_cpu, 14, (SENT | 1u));
    thumb_set_reg(&g_cpu, 0, (uint32_t)cmd);
    thumb_set_reg(&g_cpu, 4, (uint32_t)cmd);
    thumb_set_reg(&g_cpu, 5, M->state_ptr);
    if (model == 1) { thumb_set_reg(&g_cpu, 2, 0x10); thumb_set_reg(&g_cpu, 10, 0x10); }
    thumb_set_reg(&g_cpu, 15, M->build_pc | 1u);
    g_cpu.halted = 0;
    for (uint32_t s = 0; s < 30000000u; s++) {
        if ((g_cpu.r[15] & ~1u) == (SENT & ~1u)) break;
        if (thumb_step(&g_cpu) < 0) break;
    }

    int start = -1;
    if (M->find_d7) { for (int i = 0; i + 9 <= g_nb; i++) if (g_sent[i] == 0xD7) { start = i + 1; break; } }
    else            { start = 2; }  /* D-605: skip AA AA */
    if (start < 0 || start + 9 > g_nb) return NULL;

    jbyteArray res = (*env)->NewByteArray(env, 9);
    (*env)->SetByteArrayRegion(env, res, 0, 9, (const jbyte*)(g_sent + start));
    return res;
}

static int parity8(int x) { int c = 0; x &= 0xFF; while (x) { c ^= 1; x &= x - 1; } return c; }

/* decode on-air 9 bytes -> int[8] {s0,s1,s2,s3,cmdCode,ctrHi,ctrLo,cksumOk}. Cross-model. */
JNIEXPORT jintArray JNICALL
Java_com_foxfw_companion_PandoraNative_decode(JNIEnv* env, jobject thiz, jbyteArray jon) {
    (void)thiz;
    jbyte on[9]; (*env)->GetByteArrayRegion(env, jon, 0, 9, on);
    int B[9]; for (int i = 0; i < 9; i++) B[i] = (0xFF - (on[i] & 0xFF)) & 0xFF;
    int cs = B[7], r1;
    if (parity8(cs)) { r1 = B[6]; for (int i = 0; i < 5; i++) B[i] ^= r1; B[5] ^= B[6]; }
    else             { r1 = B[5]; for (int i = 0; i < 5; i++) B[i] ^= r1; B[6] ^= B[5]; }
    int a = B[5], b = B[6];
    B[5] = (a & 0xAA) | (b & 0x55);
    B[6] = (b & 0xAA) | (a & 0x55);
    int ctr = (B[5] << 8) | B[6];
    int cks_ok = ((B[0] + B[1] + B[2] + B[3] + B[4] + B[5] + B[6]) & 0xFF) == cs;
    jint out[8] = { B[0], B[1], B[2], B[3], B[4], (ctr >> 8) & 0xFF, ctr & 0xFF, cks_ok };
    jintArray res = (*env)->NewIntArray(env, 8);
    (*env)->SetIntArrayRegion(env, res, 0, 8, out);
    return res;
}
