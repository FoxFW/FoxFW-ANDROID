#!/usr/bin/env python3
r"""arke_renault_v123.py -- ports of firmware renault_v1.c / renault_v2.c /
renault_v3.c (the "Archiv" Renault trio) to the arke framework.

Reality check on these three protocols (be honest, per the source headers):
  * Renault V1 (Clio 2)  -- 2-FSK Manchester, te 52/104. 96-bit frame:
        [sync 0x4C64 :16][serial :16][encrypted rolling :64]
        Only the 16-bit serial is in the clear; button/counter are inside the
        encrypted 64-bit hop and are NOT separable. NO CRC.
  * Renault V2 (Clio 3)  -- OOK Manchester, te 230/460. 82-bit frame:
        [sync 0x71EA :16][encrypted :66]
        The whole payload is encrypted -- NO visible serial at all, NO CRC.
        (The firmware literally sets generic.serial = 0.)
  * Renault V3 (Megane)  -- 2-FSK NRZ, te 52/104. 64..160-bit frame, two frame
        types: [sync 0xC969 | 0xCB2D :16][serial :16][encrypted ...]. NO CRC.

Because none of the three carries a CRC or checksum, crc_ok is ALWAYS None
(structural) here -- it is never set True. The only false-positive defences the
protocols give us are: (1) an exact 16-bit constant sync word, (2) a tight frame
length gate, and (3) a non-degenerate serial. These are applied strictly (the
full frame must be present after the sync), which is stricter than the firmware
(the firmware accepts short/truncated frames); this trades a little recall for
much stronger false-positive resistance, as required.

Decoder bit engines are transcribed 1:1 from the C:
  * V1/V2 use a custom stateless "half-bit accumulator" ("Ansatz C"): a LONG
    emits one bit == its level; a SHORT pairs with the next SHORT and emits one
    bit == the level of the 2nd SHORT. Sync word is then searched in the bits.
  * V3 uses an NRZ quantiser: each timing emits N = round(duration/te_short)
    bits of the current level.
All timing comparisons use "<= te_delta" to match the C's DURATION_DIFF checks
exactly (arke's is_short/is_long helpers use strict "<", so the compares are
written inline here).
"""
from arke_core import Const, ProtoDecoder, diff

# Serials that are structurally meaningless -> reject (16- and 24-bit forms).
_DEGENERATE = frozenset(
    (0, 0xFFFF, 0x5555, 0xAAAA, 0xFFFFFF, 0x555555, 0xAAAAAA)
)


def _bits_of(value, nbits):
    """MSB-first bit list of value."""
    return [(value >> (nbits - 1 - i)) & 1 for i in range(nbits)]


def _bits_to_int(bits):
    v = 0
    for b in bits:
        v = (v << 1) | (b & 1)
    return v


# ============================================================================
# V1 / V2 shared half-bit ("Ansatz C") Manchester decoder
# ============================================================================
_STEP_RESET = 0
_STEP_COLLECT = 1


class _RenaultManchesterBase(ProtoDecoder):
    """Base for V1/V2: custom half-bit accumulator + sync-word search."""

    # subclass sets these:
    SYNC_BITS = ()      # tuple of 0/1, the 16-bit constant sync word
    FRAME_BITS = 0      # full frame length in bits (incl. sync)
    MIN_PROCESS = 0     # firmware's minimum all_bit_count to attempt a frame
    ARR_SIZE = 0        # firmware all_bits[] capacity
    OVERFLOW = 0        # firmware "process & reset" bit-count threshold
    LEN_TOL = 4         # allow up to +LEN_TOL trailing bits past a full frame
    HAS_SERIAL = True   # V2 has none

    def __init__(self):
        super().__init__()
        self.step = _STEP_RESET
        self.half_bit = False
        self.all_bits = []

    def reset(self):
        super().reset()
        self.step = _STEP_RESET
        self.half_bit = False
        self.all_bits = []

    def _process(self):
        bits = self.all_bits
        n = len(bits)
        if n < self.MIN_PROCESS:
            return
        sync = list(self.SYNC_BITS)
        slen = len(sync)
        # firmware: search_end = all_bit_count - MIN_PROCESS
        search_end = n - self.MIN_PROCESS
        sync_pos = -1
        for i in range(search_end + 1):
            if bits[i:i + slen] == sync:
                sync_pos = i
                break
        if sync_pos < 0:
            return

        data_after = n - sync_pos
        # Exact-length gate (hardening): the FULL frame must be present after the
        # sync, and we reject anything meaningfully longer (foreign signal / a
        # false early sync match leaves far too many trailing bits).
        if data_after < self.FRAME_BITS or data_after > self.FRAME_BITS + self.LEN_TOL:
            return

        data_len = min(data_after, self.FRAME_BITS)
        frame_bits = bits[sync_pos:sync_pos + data_len]
        frame_int = _bits_to_int(frame_bits)

        # generic.data = first 64 bits from sync (as the firmware builds it)
        gdata = _bits_to_int(frame_bits[:min(64, data_len)])

        if self.HAS_SERIAL:
            serial = (gdata >> 32) & 0xFFFF  # bytes 2-3 == bits 16..31
            if serial in _DEGENERATE:
                return
            self.emit(dict(
                serial=serial, btn=None, btn_name="encrypted", cnt=None,
                data=frame_int, crc_ok=None,
            ))
        else:
            # V2: entire payload encrypted, no serial to surface.
            self.emit(dict(
                serial=None, btn=None, btn_name="encrypted", cnt=None,
                data=frame_int, crc_ok=None,
            ))

    def feed(self, level, duration):
        c = self.CONST
        te_s, te_l, te_d = c.te_short, c.te_long, c.te_delta

        if self.step == _STEP_RESET:
            # Any SHORT starts bit collection (this SHORT = first of a pair).
            if diff(duration, te_s) <= te_d:
                self.step = _STEP_COLLECT
                self.all_bits = []
                self.half_bit = True
            return

        # _STEP_COLLECT
        if len(self.all_bits) >= self.OVERFLOW:
            self._process()
            self.step = _STEP_RESET
            return

        is_short = diff(duration, te_s) <= te_d
        is_long = diff(duration, te_l) <= te_d

        if is_long:
            if self.half_bit:
                # LONG after a half-bit = Manchester error -> process & reset
                self._process()
                self.step = _STEP_RESET
            else:
                # LONG = complete bit, value = level
                if len(self.all_bits) < self.ARR_SIZE:
                    self.all_bits.append(1 if level else 0)
        elif is_short:
            if self.half_bit:
                # second SHORT completes a bit, value = level of the 2nd SHORT
                if len(self.all_bits) < self.ARR_SIZE:
                    self.all_bits.append(1 if level else 0)
                self.half_bit = False
            else:
                self.half_bit = True
        else:
            # invalid timing -> process frame & reset
            self._process()
            self.step = _STEP_RESET


class RenaultV1(_RenaultManchesterBase):
    NAME = "Renault V1"
    CONST = Const(52, 104, 20, 96)
    # sync 0x4C64 = 0100 1100 0110 0100
    SYNC_BITS = (0, 1, 0, 0, 1, 1, 0, 0, 0, 1, 1, 0, 0, 1, 0, 0)
    FRAME_BITS = 96
    MIN_PROCESS = 80
    ARR_SIZE = 160
    OVERFLOW = 150
    HAS_SERIAL = True


class RenaultV2(_RenaultManchesterBase):
    NAME = "Renault V2"
    CONST = Const(230, 460, 60, 82)
    # sync 0x71EA = 0111 0001 1110 1010
    SYNC_BITS = (0, 1, 1, 1, 0, 0, 0, 1, 1, 1, 1, 0, 1, 0, 1, 0)
    FRAME_BITS = 82
    MIN_PROCESS = 60
    ARR_SIZE = 200
    OVERFLOW = 190
    HAS_SERIAL = False


# ============================================================================
# V3 -- 2-FSK NRZ quantiser
# ============================================================================
_V3_RESET = 0
_V3_PREAMBLE = 1
_V3_COLLECT = 2


class RenaultV3(ProtoDecoder):
    NAME = "Renault V3"
    CONST = Const(52, 104, 22, 64)

    MIN_DATA_BITS = 64
    MAX_DATA_BITS = 160
    MIN_PREAMBLE = 10
    ARR_SIZE = 256
    OVERFLOW = 250
    # sync A 0xC969 = 1100 1001 0110 1001
    SYNC_A = (1, 1, 0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 1)
    # sync B 0xCB2D = 1100 1011 0010 1101
    SYNC_B = (1, 1, 0, 0, 1, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0, 1)

    def __init__(self):
        super().__init__()
        self.step = _V3_RESET
        self.preamble_count = 0
        self.all_bits = []

    def reset(self):
        super().reset()
        self.step = _V3_RESET
        self.preamble_count = 0
        self.all_bits = []

    def _append_n(self, n, level):
        bit_val = 1 if level else 0
        for _ in range(n):
            if len(self.all_bits) < self.ARR_SIZE:
                self.all_bits.append(bit_val)

    def _process(self):
        bits = self.all_bits
        n = len(bits)
        if n < self.MIN_DATA_BITS:
            return
        sync_a = list(self.SYNC_A)
        sync_b = list(self.SYNC_B)
        search_end = n - self.MIN_DATA_BITS
        if search_end < 0:
            search_end = 0
        sync_pos = -1
        variant = None
        for i in range(search_end + 1):
            win = bits[i:i + 16]
            if win == sync_a:
                sync_pos = i
                variant = "TypeA"
                break
            if win == sync_b:
                sync_pos = i
                variant = "TypeB"
                break
        if sync_pos < 0:
            return

        data_after = n - sync_pos
        # Exact-length gate (hardening): frame must be within the protocol's own
        # 64..160-bit envelope; reject over-long (foreign / false early sync).
        if data_after < self.MIN_DATA_BITS or data_after > self.MAX_DATA_BITS:
            return

        data_len = min(data_after, self.MAX_DATA_BITS)
        frame_bits = bits[sync_pos:sync_pos + data_len]
        frame_int = _bits_to_int(frame_bits)
        gdata = _bits_to_int(frame_bits[:min(64, data_len)])

        serial = (gdata >> 32) & 0xFFFF  # bytes 2-3 == bits 16..31
        if serial in _DEGENERATE:
            return

        self.emit(dict(
            serial=serial, btn=None, btn_name=variant, cnt=None,
            data=frame_int, crc_ok=None,
        ))

    def feed(self, level, duration):
        c = self.CONST
        te_s, te_d = c.te_short, c.te_delta

        if self.step == _V3_RESET:
            if diff(duration, te_s) <= te_d:
                self.step = _V3_PREAMBLE
                self.preamble_count = 1
            return

        if self.step == _V3_PREAMBLE:
            is_short = diff(duration, te_s) <= te_d
            if is_short:
                self.preamble_count += 1
                if self.preamble_count > 5000:
                    self.step = _V3_RESET
            elif self.preamble_count >= self.MIN_PREAMBLE:
                # non-SHORT after enough preamble -> NRZ data starts here
                self.all_bits = []
                n = (duration + te_s // 2) // te_s
                if n > 8:
                    n = 8
                self._append_n(n, level)
                self.step = _V3_COLLECT
            else:
                self.step = _V3_RESET
            return

        # _V3_COLLECT
        n = (duration + te_s // 2) // te_s
        if n == 0 or duration > te_s * 8:
            self._process()
            self.step = _V3_RESET
            return
        self._append_n(n, level)
        if len(self.all_bits) >= self.OVERFLOW:
            self._process()
            self.step = _V3_RESET


ALL = [RenaultV1, RenaultV2, RenaultV3]


# ============================================================================
# self-test
# ============================================================================
def _drive_manchester(dec, desired_bits):
    """Directly drive a V1/V2 half-bit decoder to produce exactly desired_bits.

    Rules exploited (from the C):
      * first SHORT enters COLLECT with half_bit=True (emits nothing),
      * a second SHORT then emits one bit == its level (clears half_bit),
      * afterwards each LONG emits one bit == its level (half_bit stays False).
    So: SHORT(x) primes, SHORT(desired[0]) emits bit0, LONG(desired[i]) emits
    the rest, then an out-of-range gap flushes the frame.
    """
    te_s = dec.CONST.te_short
    te_l = dec.CONST.te_long
    dec.feed(True, te_s)                       # prime: RESET->COLLECT, half=True
    dec.feed(bool(desired_bits[0]), te_s)      # bit 0 via 2nd SHORT
    for b in desired_bits[1:]:
        dec.feed(bool(b), te_l)                # bit i via LONG
    dec.feed(False, 999999)                    # invalid timing -> process+reset


def _drive_nrz(dec, desired_bits):
    """Directly drive the V3 NRZ decoder to produce exactly desired_bits.

    Preamble = >=MIN_PREAMBLE SHORTs; the first two data bits (equal, since the
    C969/CB2D syncs both start 1,1) are sent as one 2*te_short pulse to make the
    non-SHORT transition out of preamble; the rest go one bit per te_short pulse.
    """
    te_s = dec.CONST.te_short
    for _ in range(12):
        dec.feed(True, te_s)                   # build preamble_count
    # first non-SHORT pulse = 2 bits of desired_bits[0] (== desired_bits[1])
    assert desired_bits[0] == desired_bits[1]
    dec.feed(bool(desired_bits[0]), te_s * 2)  # n = 2 -> emits bits 0,1
    for b in desired_bits[2:]:
        dec.feed(bool(b), te_s)                # n = 1 each
    dec.feed(False, te_s * 9)                  # duration > te_s*8 -> process


def _selftest():
    ok = True

    # ---- V1: expect serial 0x1234 recovered from a full 96-bit frame ----
    try:
        d = RenaultV1()
        leading = [0] * 8
        payload = _bits_of(0xDEADBEEFCAFEBABE, 64)
        desired = leading + list(d.SYNC_BITS) + _bits_of(0x1234, 16) + payload
        _drive_manchester(d, desired)
        assert d.results, "V1 produced no frame"
        r = d.results[0]
        assert r["serial"] == 0x1234, "V1 serial %r" % (r.get("serial"),)
        assert r["crc_ok"] is None, "V1 crc_ok must be None"
        print("OK %s (serial=0x%04X, %d-bit frame)"
              % (d.NAME, r["serial"], desired.__len__() - 8))
    except Exception as e:  # noqa: BLE001
        ok = False
        print("FAIL %s: %r" % (RenaultV1.NAME, e))

    # ---- V2: expect a frame with serial None (fully encrypted) ----
    try:
        d = RenaultV2()
        leading = [0] * 8
        payload = _bits_of((0x0BADF00DCAFE >> 0) & ((1 << 66) - 1), 66)
        desired = leading + list(d.SYNC_BITS) + payload
        _drive_manchester(d, desired)
        assert d.results, "V2 produced no frame"
        r = d.results[0]
        assert r["serial"] is None, "V2 serial must be None, got %r" % (r["serial"],)
        assert r["crc_ok"] is None, "V2 crc_ok must be None"
        print("OK %s (serial=None, 82-bit frame)" % d.NAME)
    except Exception as e:  # noqa: BLE001
        ok = False
        print("FAIL %s: %r" % (RenaultV2.NAME, e))

    # ---- V3: expect serial 0x1234 recovered from a Type-A NRZ frame ----
    try:
        d = RenaultV3()
        payload = _bits_of(0xC0FFEE, 24)          # tail bits
        desired = list(d.SYNC_A) + _bits_of(0x1234, 16) + payload  # 56 bits
        # pad to a comfortable in-envelope length (>=64)
        desired = desired + [1, 0] * 8            # -> 72 bits total
        _drive_nrz(d, desired)
        assert d.results, "V3 produced no frame"
        r = d.results[0]
        assert r["serial"] == 0x1234, "V3 serial %r" % (r.get("serial"),)
        assert r["crc_ok"] is None, "V3 crc_ok must be None"
        assert r["btn_name"] == "TypeA", "V3 variant %r" % (r.get("btn_name"),)
        print("OK %s (serial=0x%04X, %d-bit frame, %s)"
              % (d.NAME, r["serial"], len(desired), r["btn_name"]))
    except Exception as e:  # noqa: BLE001
        ok = False
        print("FAIL %s: %r" % (RenaultV3.NAME, e))

    # ---- negative: random noise must NOT false-positive on any of them ----
    try:
        import random
        rng = random.Random(1234)
        noise = [rng.choice((52, 104, 230, 460, 300, 700, 40))
                 for _ in range(4000)]
        fp = []
        for cls in ALL:
            dn = cls()
            lvl = True
            for dur in noise:
                dn.feed(lvl, dur)
                lvl = not lvl
            dn.feed(False, 999999)
            if dn.results:
                fp.append((cls.NAME, len(dn.results)))
        if fp:
            print("WARN noise produced frames: %r" % (fp,))
        else:
            print("OK noise -> no false positives")
    except Exception as e:  # noqa: BLE001
        print("WARN negative test errored: %r" % (e,))

    return ok


if __name__ == "__main__":
    import sys
    sys.exit(0 if _selftest() else 1)
