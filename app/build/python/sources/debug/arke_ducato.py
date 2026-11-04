#!/usr/bin/env python3
r"""arke_ducato.py -- ported from firmware ducato_v0.c (Fiat Ducato V0).

Reverse-engineered from the Magneti Marelli NBC250C.00 body computer (D70F3631
V850ES). Key fob = NXP PCF7946A (HITAG2), RX = Atmel ATA5742P2 @ 433.92 MHz ASK,
Manchester encoding (same chip family / same state machine as Fiat V0).

Frame = 71 bits: 64-bit key (Hop:32 | Fix:32) + 7-bit CRC.
  Fix = Serial(24) | Button(8)
  Hop = encrypted rolling code (PCF7946A HITAG2) -- counter NOT extractable.

CRC (firmware FUN_ffe0e2ee): masked XOR over the big-endian key bytes with masks
{0xB3,0x80,0x83,0x22,0x73} (note: byte[5], not byte[4]), then a nibble-parity
lookup; the frame is valid when (crc & 1) == that parity. This is a genuine
firmware checksum but only 1 bit wide, so crc_ok reflects it honestly (see
report). Hardening: exact 71-bit length gate + >=120-pulse preamble + full
Manchester validity + degenerate-serial rejection carry the anti-false-positive
weight; crc_ok is NEVER set True on a bare length/preamble match.
"""
from arke_core import Const, ProtoDecoder, diff, u64_to_bytes_be, MASK64

# ---- constants transcribed from ducato_v0.c ----
DUCATO_TE_SYNC = 800
DUCATO_PREAMBLE_COUNT = 120
DUCATO_DATA_BITS = 71

# Manchester table from PCF7946A (ducato_manchester_table)
_MANCHESTER_TABLE = (0x01, 0x91, 0x9B, 0xFB)
# Nibble parity table from firmware @ 0xFFE3CD38 (ducato_parity_table)
_PARITY_TABLE = (0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0)
# CRC masks from firmware FUN_ffe0e2ee (ducato_crc_masks)
_CRC_MASKS = (0xB3, 0x80, 0x83, 0x22, 0x73)

# Button names (ducato_v0_get_button_name)
_BTN_NAMES = {0x01: "LOCK", 0x02: "UNLOCK", 0x06: "TRUNK", 0x09: "PANIC"}

# Degenerate serials rejected outright (24-bit space)
_DEGENERATE = frozenset((0x000000, 0xFFFFFF, 0x555555, 0xAAAAAA))


def _ducato_check_crc(key64, crc7):
    """Mirror of firmware ducato_v0_check_crc(): masked-XOR + nibble parity.

    Returns True only when the 1-bit firmware parity check validates.
    """
    b = u64_to_bytes_be(key64)  # b[0]=MSB ... b[7]=LSB (big-endian, matches C)
    acc = 0
    acc ^= b[0] & _CRC_MASKS[0]
    acc ^= b[1] & _CRC_MASKS[1]
    acc ^= b[2] & _CRC_MASKS[2]
    acc ^= b[3] & _CRC_MASKS[3]
    acc ^= b[5] & _CRC_MASKS[4]  # byte[5], not byte[4] (offset +2 in firmware)
    parity = _PARITY_TABLE[acc >> 4] ^ _PARITY_TABLE[acc & 0x0F]
    return (crc7 & 0x01) == parity


class DucatoV0(ProtoDecoder):
    NAME = "Fiat Ducato V0"
    CONST = Const(200, 400, 99, DUCATO_DATA_BITS)

    # parser steps (DucatoDecoderStep)
    _RESET = 0
    _PREAMBLE = 1
    _DATA = 2

    def __init__(self):
        super().__init__()
        self.step = self._RESET
        self.te_last = 0
        self.preamble_count = 0
        self.man_state = 1

    def reset(self):
        super().reset()
        self.step = self._RESET
        self.te_last = 0
        self.preamble_count = 0
        self.man_state = 1

    # ---- Manchester (ducato_manchester_decode), inlined to be bit-exact ----
    # NOTE vs arke_core.Manchester: the firmware does NOT reset state when
    # state == new_state (it just yields no bit), so we cannot reuse that class.
    def _man_decode(self, timing_type):
        """Returns (produced, bit)."""
        s = self.man_state
        if timing_type == 8:  # reset event (never reached via feed(); kept faithful)
            self.man_state = 1
            return (False, 0)
        if s >= 4:
            self.man_state = 1
            return (False, 0)
        ns = (_MANCHESTER_TABLE[s] >> timing_type) & 0x3
        if s == ns:
            return (False, 0)  # firmware keeps state unchanged, no bit
        self.man_state = ns
        if ns == 2:
            return (True, 0)
        if ns == 1:
            return (True, 1)
        return (False, 0)

    def _finish(self):
        # Full 71-bit accumulator: high 64 bits = key, low 7 bits = CRC.
        full = self.d.wide()
        key = (full >> 7) & MASK64
        crc = full & 0x7F

        # reset decoder state back to Reset (mirror firmware post-callback reset)
        self.step = self._RESET
        self.preamble_count = 0
        self.man_state = 1
        self.d.reset()

        fix = key & 0xFFFFFFFF
        hop = (key >> 32) & 0xFFFFFFFF
        serial = (fix >> 8) & 0x00FFFFFF
        btn = fix & 0xFF

        # hardening: reject degenerate serials with no emit
        if serial in _DEGENERATE:
            return

        crc_ok = _ducato_check_crc(key, crc)
        btn_name = _BTN_NAMES.get(btn, "0x%02X" % btn)
        self.emit({
            "data": key,          # raw 64-bit frame word (Hop | Fix)
            "serial": serial,     # 24-bit
            "btn": btn,
            "btn_name": btn_name,
            "cnt": None,          # counter is encrypted in Hop -> not extractable
            "crc_ok": crc_ok,     # genuine (1-bit) firmware checksum result
            "hop": hop,
            "crc": crc,
        })

    def feed(self, level, duration):
        c = self.CONST
        d = c.te_delta

        if self.step == self._RESET:
            if level and diff(duration, c.te_short) <= d:
                self.step = self._PREAMBLE
                self.te_last = duration
                self.preamble_count = 1
                self.d.reset()
                self.man_state = 1

        elif self.step == self._PREAMBLE:
            if diff(duration, c.te_short) <= d:
                if diff(self.te_last, c.te_short) <= d:
                    self.preamble_count += 1
                else:
                    self.step = self._RESET
                    self.preamble_count = 0
                self.te_last = duration
            elif diff(duration, DUCATO_TE_SYNC) <= d:
                if self.preamble_count >= DUCATO_PREAMBLE_COUNT:
                    self.step = self._DATA
                    self.preamble_count = 0
                    self.man_state = 1
                    self.d.reset()
                else:
                    self.step = self._RESET
                    self.preamble_count = 0
            else:
                self.step = self._RESET
                self.preamble_count = 0

        elif self.step == self._DATA:
            timing_type = None
            if diff(duration, c.te_short) <= d:
                timing_type = 0 if level else 2
            elif diff(duration, c.te_long) <= d:
                timing_type = 4 if level else 6
            # invalid timing: mirror firmware -> ignore pulse, do not reset
            if timing_type is not None:
                produced, bit = self._man_decode(timing_type)
                if produced:
                    self.d.add_to_128(bit)
                    # exact-length gate: only complete at exactly 71 bits
                    if self.d.decode_count_bit == DUCATO_DATA_BITS:
                        self._finish()


ALL = [DucatoV0]


# ---------------------------------------------------------------------------
# Self-test: build a synthetic frame with the firmware encoder logic, feed it,
# and confirm construct + feed() run + a clean decode round-trips.
# ---------------------------------------------------------------------------
def _encode_bit(state, bit, ts, tl):
    """Mirror of firmware ducato_encode_bit(). Returns (pulses, new_state)."""
    p = []
    if state == 1:
        if bit == 0:
            p.append((False, tl)); state = 2
        else:
            p.append((False, ts)); p.append((True, ts)); state = 1
    elif state == 2:
        if bit == 0:
            p.append((True, ts)); p.append((False, ts)); state = 2
        else:
            p.append((True, tl)); state = 1
    elif state == 0:
        if bit == 1:
            p.append((True, ts)); state = 1
        else:
            p.append((True, ts)); p.append((False, tl)); state = 2
    elif state == 3:
        if bit == 0:
            p.append((False, ts)); state = 2
        else:
            p.append((False, ts)); p.append((True, tl)); state = 1
    else:
        state = 1
    return p, state


def _build_pulses(key, crc, pairs=130):
    ts, tl = 200, 400
    out = []
    for _ in range(pairs):  # preamble: SHORT high/low pairs
        out.append((True, ts)); out.append((False, ts))
    out.append((False, DUCATO_TE_SYNC))  # sync
    out.append((True, ts))               # initial short high -> establishes state 1
    state = 1
    for b in range(63, -1, -1):          # 64-bit key, MSB first
        p, state = _encode_bit(state, (key >> b) & 1, ts, tl)
        out.extend(p)
    for b in range(6, -1, -1):           # 7-bit CRC, MSB first
        p, state = _encode_bit(state, (crc >> b) & 1, ts, tl)
        out.extend(p)
    out.append((True, 6000))             # end gap
    return out


def _expected_parity(key):
    b = u64_to_bytes_be(key)
    acc = ((b[0] & 0xB3) ^ (b[1] & 0x80) ^ (b[2] & 0x83)
           ^ (b[3] & 0x22) ^ (b[5] & 0x73))
    return _PARITY_TABLE[acc >> 4] ^ _PARITY_TABLE[acc & 0x0F]


def _selftest():
    # 1) no-exception smoke test on arbitrary/garbage pulses
    dec = DucatoV0()
    for i in range(200):
        dec.feed(bool(i & 1), 150 + (i * 37) % 900)

    # 2) full encode -> decode round-trip with a valid (crc_ok) frame
    serial, btn = 0x0A1B2C, 0x01  # LOCK
    key = (0xDEADBEEF << 32) | (serial << 8) | btn
    crc = _expected_parity(key)   # set LSB so the firmware parity check passes
    dec = DucatoV0()
    for lvl, dur in _build_pulses(key, crc):
        dec.feed(lvl, dur)
    assert len(dec.results) == 1, "expected exactly one decode, got %d" % len(dec.results)
    r = dec.results[0]
    assert r["serial"] == serial, "serial %06X != %06X" % (r["serial"], serial)
    assert r["btn"] == btn and r["btn_name"] == "LOCK", r
    assert r["cnt"] is None, "counter must be None (encrypted)"
    assert r["data"] == key, "data %016X != %016X" % (r["data"], key)
    assert r["crc_ok"] is True, "crc_ok should validate for a well-formed frame"

    # 3) degenerate serial (0) must NOT emit
    dkey = (0xDEADBEEF << 32) | (0x000000 << 8) | 0x00
    dcrc = _expected_parity(dkey)
    ddec = DucatoV0()
    for lvl, dur in _build_pulses(dkey, dcrc):
        ddec.feed(lvl, dur)
    assert ddec.results == [], "degenerate serial 0 must be rejected"

    print("OK", DucatoV0.NAME)


if __name__ == "__main__":
    _selftest()
