/* JNI for the Pandora code-grabber (PandoraCracker.kt). Part of libpandora. */
#include <jni.h>
#include <stdlib.h>
#include "cracker.h"

static uint8_t* get_bytes(JNIEnv* env, jbyteArray a, jsize* n) {
    *n = (*env)->GetArrayLength(env, a);
    uint8_t* b = (uint8_t*)malloc((size_t)(*n > 0 ? *n : 1));
    if(b && *n > 0) (*env)->GetByteArrayRegion(env, a, 0, *n, (jbyte*)b);
    return b;
}

JNIEXPORT jint JNICALL
Java_com_foxfw_companion_PandoraCracker_detect(JNIEnv* env, jobject thiz, jbyteArray fw) {
    (void)thiz;
    jsize n = 0;
    uint8_t* b = get_bytes(env, fw, &n);
    int v = pa_detect_variant(b, (uint32_t)n);
    free(b);
    return v;
}

JNIEXPORT jintArray JNICALL
Java_com_foxfw_companion_PandoraCracker_knock(JNIEnv* env, jobject thiz, jbyteArray fw) {
    (void)thiz;
    jsize n = 0;
    uint8_t* b = get_bytes(env, fw, &n);
    uint8_t seq[16];
    int c = pa_crack_knock(b, (uint32_t)n, seq, 16);
    free(b);
    if(c <= 0) return NULL;
    jint tmp[16];
    for(int i = 0; i < c; i++) tmp[i] = seq[i];
    jintArray res = (*env)->NewIntArray(env, c);
    (*env)->SetIntArrayRegion(env, res, 0, c, tmp);
    return res;
}

JNIEXPORT jint JNICALL
Java_com_foxfw_companion_PandoraCracker_pin(JNIEnv* env, jobject thiz, jbyteArray fw, jstring name) {
    (void)thiz;
    jsize n = 0;
    uint8_t* b = get_bytes(env, fw, &n);
    const char* nm = name ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
    int pin = pic_crack_pin(b, (uint32_t)n, nm);
    if(nm) (*env)->ReleaseStringUTFChars(env, name, nm);
    free(b);
    return pin;
}
