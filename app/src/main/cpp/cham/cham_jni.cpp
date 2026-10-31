// JNI bridge to the vendored onyx MIFARE-Classic crypto: mfkey32 (2-nonce) + darkside recovery.
// The solvers are the proven, board-independent onyx sources (crapto1 + darkside + mfkey32_lowmem).
#include <jni.h>
#include <cstdint>
#include <cstring>
#include "signal/mfkey32_lowmem.h"
#include "signal/darkside.h"

extern "C" {

// mfkey32: two reader auths on the same sector key -> 48-bit key, or -1 if none.
JNIEXPORT jlong JNICALL
Java_com_foxfw_companion_ChamCrypto_mfkey32(JNIEnv*, jobject,
        jint uid, jint nt0, jint nr0, jint ar0, jint nt1, jint nr1, jint ar1) {
    uint64_t key = 0;
    bool ok = onyx::mfkey32_recover_lowmem(
        (uint32_t)uid, (uint32_t)nt0, (uint32_t)nr0, (uint32_t)ar0,
        (uint32_t)nt1, (uint32_t)nr1, (uint32_t)ar1, &key, nullptr);
    return ok ? (jlong)key : (jlong)-1;
}

// darkside: nrounds of acquire data -> up to 32 candidate 48-bit keys (caller validates each).
// par/ks are each nrounds*8 bytes (row-major: round r, nt_diff d at index r*8+d).
JNIEXPORT jlongArray JNICALL
Java_com_foxfw_companion_ChamCrypto_darkside(JNIEnv* env, jobject,
        jintArray juid, jintArray jnt, jintArray jnr, jintArray jar,
        jbyteArray jpar, jbyteArray jks, jint nrounds) {
    if (nrounds <= 0 || nrounds > 16) return nullptr;
    jint *uid = env->GetIntArrayElements(juid, nullptr);
    jint *nt  = env->GetIntArrayElements(jnt, nullptr);
    jint *nr  = env->GetIntArrayElements(jnr, nullptr);
    jint *ar  = env->GetIntArrayElements(jar, nullptr);
    jbyte *par = env->GetByteArrayElements(jpar, nullptr);
    jbyte *ks  = env->GetByteArrayElements(jks, nullptr);

    onyx::ChDarksideRound rounds[16];
    for (int r = 0; r < nrounds; ++r) {
        rounds[r].uid = (uint32_t)uid[r];
        rounds[r].nt  = (uint32_t)nt[r];
        rounds[r].nr  = (uint32_t)nr[r];
        rounds[r].ar  = (uint32_t)ar[r];
        for (int d = 0; d < 8; ++d) {
            rounds[r].par[d] = (uint8_t)par[r * 8 + d];
            rounds[r].ks[d]  = (uint8_t)ks[r * 8 + d];
        }
    }
    uint64_t keys[32];
    int n = onyx::darkside_recover(rounds, nrounds, keys, 32);

    env->ReleaseIntArrayElements(juid, uid, JNI_ABORT);
    env->ReleaseIntArrayElements(jnt, nt, JNI_ABORT);
    env->ReleaseIntArrayElements(jnr, nr, JNI_ABORT);
    env->ReleaseIntArrayElements(jar, ar, JNI_ABORT);
    env->ReleaseByteArrayElements(jpar, par, JNI_ABORT);
    env->ReleaseByteArrayElements(jks, ks, JNI_ABORT);

    if (n <= 0) return env->NewLongArray(0);
    jlongArray out = env->NewLongArray(n);
    jlong tmp[32];
    for (int i = 0; i < n && i < 32; ++i) tmp[i] = (jlong)keys[i];
    env->SetLongArrayRegion(out, 0, n, tmp);
    return out;
}

} // extern "C"
