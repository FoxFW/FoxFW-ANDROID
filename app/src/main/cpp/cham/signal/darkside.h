// On-device MIFARE Classic DARKSIDE key-recovery solver (host-testable).
//
// Consumes ALREADY-PARSED darkside acquire data (the BLE / Chameleon wire parsing is done
// elsewhere) and produces candidate 48-bit sector keys. Board-independent: no Arduino, no
// BLE, nothing but the vendored crapto1 at src/signal/crapto1. Compiles with g++ -std=c++17.
//
// One "round" mirrors the onyx ChDarkside acquire result (CH_MF1_DARKSIDE_ACQUIRE / 0x2004):
// the reader varies the three low significant bits of its nonce (nt_diff 0..7) and, for each
// variation that the tag answers with an (encrypted) NACK, records the 4-bit NACK keystream
// nibble (ks[nt_diff]) together with the 8 over-the-air parity bits packed into one byte
// (par[nt_diff]). uid/nt are the card UID and tag nonce; nr/ar are the reader nonce/response
// actually used (the classic darkside uses nr = 0).
//
// The recovered keys are only *candidates*: the caller must validate each one against the card
// by attempting an authentication. A single full-parity round usually narrows to a handful.

#ifndef ONYX_SIGNAL_DARKSIDE_H
#define ONYX_SIGNAL_DARKSIDE_H

#include <stdint.h>

namespace onyx {

struct ChDarksideRound {
  uint32_t uid;     // card UID (4 bytes)
  uint32_t nt;      // tag nonce for this acquire session
  uint32_t nr;      // reader nonce used (classic darkside: 0)
  uint32_t ar;      // reader response used (classic darkside: 0)
  uint8_t  par[8];  // par[nt_diff] = 8 over-the-air parity bits, bit j at position j
  uint8_t  ks[8];   // ks[nt_diff]  = 4-bit NACK keystream nibble (low nibble)
};

// Recover candidate 48-bit keys from one or more accumulated darkside rounds.
// With more than one round the candidate sets are intersected (which is required for the
// parity-is-all-zero variant, and tightens the full-parity variant). Returns the number of
// distinct candidates written to keys_out (never more than max_keys). The caller validates each.
int darkside_recover(const ChDarksideRound* rounds, int nrounds, uint64_t* keys_out, int max_keys);

}  // namespace onyx

#endif  // ONYX_SIGNAL_DARKSIDE_H
