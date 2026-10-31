// Verstegen hitag2hell bitslice state recovery, as a callable function.
// Enumerates every post-init Hitag2 state consistent with a 32-bit keystream (ks),
// invoking ctx->check(user, state48) for each; check returns 1 to stop the search.
#ifndef H2BITSLICE_H
#define H2BITSLICE_H
#include <stdint.h>
#include <pthread.h>

typedef struct {
    void *user;
    int (*check)(void *user, uint64_t state48); // return 1 on solve -> stops the run
    volatile int *cancel;                        // set non-zero to abort early
    unsigned long long *scanned;                 // candidate states checked (progress)
    pthread_mutex_t lock;                        // guards found + check() + scanned
    int found;
} h2_ctx_t;

// Run the bitslice search for keystream `ks` using `nthreads` worker threads.
void h2_run(uint32_t ks, int nthreads, h2_ctx_t *ctx);

#endif
