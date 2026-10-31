// Low-memory (~113KB peak, ~49KB largest alloc) MSB-partitioned mfkey32 recovery ported
// from noproto/FlipperMfkey; for on-device use where the PC crapto1 (mfkey32.cpp) can't fit.
// Given two captured reader authentications against the SAME sector key, recover the 48-bit
// key. nr/ar are the over-the-air (encrypted) values a sniff sees. For authorized testing.
#ifndef ONYX_SIGNAL_MFKEY32_LOWMEM_H
#define ONYX_SIGNAL_MFKEY32_LOWMEM_H

#include <cstdint>

namespace onyx {

// Optional heartbeat: called once at the start of each of the 16 MSB rounds (msb_round 0..15)
// and every 32768 semi-states inside the hot inner loop, so an on-device caller can feed the
// task watchdog / update the UI during the ~1-2 min crack. Defaults to nullptr (host KAT passes
// nothing and is unaffected). Must be NULL-safe and free of any RTOS/Flipper deps.
bool mfkey32_recover_lowmem(uint32_t uid, uint32_t nt0, uint32_t nr0, uint32_t ar0,
                            uint32_t nt1, uint32_t nr1, uint32_t ar1, uint64_t* key_out,
                            void (*heartbeat)(int msb_round) = nullptr);

} // namespace onyx

#endif // ONYX_SIGNAL_MFKEY32_LOWMEM_H
