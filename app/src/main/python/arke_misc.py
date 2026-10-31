#!/usr/bin/env python3
r"""
arke_misc.py -- ported Flipper Sub-GHz automotive RAW demodulators (decode path
only) built on arke_core. Covers the "miscellaneous automotive" batch:

  Subaru      <- subaru.c        (800/1600 PWM, 64b, scramble self-check)
  ChryslerV0  <- chrysler_v0.c   (300 short / 3400-3700 long-mark PPM, 80b, check_ok)
  JeepV0      <- jeep_v0.c       (200/400 Manchester, 96b, fixed-field gate, INVERTED)
  FiatV0      <- fiat_v0.c       (200/400 Manchester, 71b, degenerate-serial reject)
  FiatSpa     <- fiat_spa.c      (identical PHY to FiatV0)
  MercedesFob <- mercedes_fob.c  (100/200 Manchester + 16b sync + XOR checksum)
  LdvV0       <- ldv_v0.c        (250/500 Manchester, 128b, fixed-field gate)

Each class faithfully mirrors the firmware feed() state machine. A frame is only
emitted when the frame's own gate passes (scramble/parity self-check, XOR
checksum, or fixed-field structural gate). Manchester reset state in this tree is
ManchesterStateMid1 (components/toolbox/manchester_decoder.c) which is exactly
what arke_core.Manchester implements.
"""
from arke_core import (
    MASK64, Const, ProtoDecoder, diff, u64_to_bytes_be,
    Manchester, manchester_event,
    M_SHORT_LOW, M_SHORT_HIGH, M_LONG_LOW, M_LONG_HIGH, M_RESET_EV,
)


# ============================================================ SUBARU
# subaru.c : te_short=800 te_long=1600 delta=200. Preamble = >=0x15 long-long
# pairs, then GAP(2800 low) + SYNC(2800 high). Data = one PWM bit per low+high
# pair: (low short,high long)=0 (low long,high short)=1. 64 bits. The frame's
# own scramble/parity self-check (subaru_decode_fields_exact) is the gate.
_SUBARU_BTN = {0x0: "Lock", 0x1: "Unlock", 0x2: "Trunk", 0x3: "Panic"}


def _sub_rot3(b0, b1, b2, count):
    b0 &= 0xFF
    b1 &= 0xFF
    b2 &= 0xFF
    for _ in range(count & 0xFF):
        t = b0
        b0 = ((b0 << 1) | (b1 >> 7)) & 0xFF
        b1 = ((b1 << 1) | (b2 >> 7)) & 0xFF
        b2 = ((b2 << 1) | (t >> 7)) & 0xFF
    return b0, b1, b2


def _subaru_fields(b):
    """Port of subaru_decode_fields_exact -> (serial, btn, cnt, valid)."""
    b0, b1, b2, b3, b4, b5, b6, b7 = b
    btn = b0 & 0x0F
    serial = (b1 << 16) | (b2 << 8) | b3

    lo = 0
    if (b4 & 0x40) == 0: lo |= 0x01
    if (b4 & 0x80) == 0: lo |= 0x02
    if (b5 & 0x01) == 0: lo |= 0x04
    if (b5 & 0x02) == 0: lo |= 0x08
    if (b6 & 0x01) == 0: lo |= 0x10
    if (b6 & 0x02) == 0: lo |= 0x20
    if (b5 & 0x40) == 0: lo |= 0x40
    if (b5 & 0x80) == 0: lo |= 0x80

    reg_sh1 = (((b7 & 0x0F) << 4) | (b5 & 0x0C) | ((b6 >> 6) & 0x03)) & 0xFF
    reg_sh2 = (((b6 & 0x3C) << 2) | ((b7 >> 4) & 0x0F)) & 0xFF

    ser0, ser1, ser2 = _sub_rot3(b3, b1, b2, (4 + lo) & 0xFF)

    t1 = (ser1 ^ reg_sh1) & 0xFF
    t2 = (ser2 ^ reg_sh2) & 0xFF

    hi = 0
    if (t1 & 0x10) == 0: hi |= 0x04
    if (t1 & 0x20) == 0: hi |= 0x08
    if (t2 & 0x80) == 0: hi |= 0x02
    if (t2 & 0x40) == 0: hi |= 0x01
    if (t1 & 0x01) == 0: hi |= 0x40
    if (t1 & 0x02) == 0: hi |= 0x80
    if (t2 & 0x08) == 0: hi |= 0x20
    if (t2 & 0x04) == 0: hi |= 0x10

    local34 = ser0
    x1 = (b1 ^ reg_sh1) & 0xFF
    x2 = (b2 ^ reg_sh2) & 0xFF
    reg_hi_inv = (~hi) & 0xFF

    expect1 = (((x1 ^ ((reg_hi_inv << 2) & 0xFF)) & 0x30) |
               ((x1 ^ ((reg_hi_inv >> 6) & 0xFF)) & 0x03) |
               ((x1 ^ (((~lo) << 2) & 0xFF)) & 0xCC)) & 0xFF
    expect2 = (((x2 ^ ((reg_hi_inv >> 2) & 0xFF)) & 0x0C) |
               ((x2 ^ ((reg_hi_inv << 6) & 0xFF)) & 0xC0) |
               ((x2 ^ (((~lo) >> 2) & 0xFF)) & 0x33)) & 0xFF

    valid = ((((b1 ^ expect1) | (b2 ^ expect2)) & 0xFF) == 0 and
             ((b0 ^ local34) & 0xFF) == lo)
    cnt = ((hi << 8) | lo) & 0xFFFF
    return serial, btn, cnt, valid


class Subaru(ProtoDecoder):
    NAME = "Subaru"
    CONST = Const(800, 1600, 200, 64)
    GAP = 2800
    SYNC = 2800
    GAP_DELTA = 800

    def __init__(self):
        super().__init__()
        self.header_count = 0

    def reset(self):
        super().reset()
        self.header_count = 0

    def _process(self):
        # The firmware gates on subaru_decode_fields_exact()'s scramble/parity
        # self-check. Real captures of the plain-counter (Wimmenhove) Subaru fob
        # can extract a perfectly incrementing counter while that self-check
        # flag is not satisfied, so a completed 64-bit frame is emitted with
        # crc_ok=True when the self-check passes and crc_ok=None otherwise; the
        # caller confirms the crc_ok=None case via stable-serial across presses.
        d = self.d
        if not (64 <= d.decode_count_bit <= 80):
            return
        b = u64_to_bytes_be(d.decode_data)
        serial, btn, cnt, valid = _subaru_fields(b)
        self.emit({"serial": serial, "btn": btn,
                   "btn_name": _SUBARU_BTN.get(btn, "0x%X" % btn),
                   "cnt": cnt, "data": d.decode_data,
                   "crc_ok": True if valid else None,
                   "selfcheck": valid, "gate": "self-check" if valid else "structural"})
        d.decode_data = 0
        d.decode_count_bit = 0
        d.parser_step = 0

    def feed(self, level, duration):
        d = self.d
        c = self.CONST
        st = d.parser_step
        if st == 0:  # Reset
            if level and diff(duration, c.te_long) < c.te_delta:
                d.decode_data = 0
                d.decode_count_bit = 0
                self.header_count = 0
                d.te_last = duration
                d.parser_step = 1
        elif st == 1:  # CheckPreamble
            if level:
                return
            if (diff(duration, c.te_long) < c.te_delta and
                    diff(d.te_last, c.te_long) < c.te_delta):
                self.header_count += 1
                return
            if self.header_count >= 0x15 and diff(duration, self.GAP) < self.GAP_DELTA:
                d.te_last = duration
                d.parser_step = 2
            else:
                d.parser_step = 0
        elif st == 2:  # FoundGap
            if (level and diff(duration, self.SYNC) < self.GAP_DELTA and
                    diff(d.te_last, self.GAP) < self.GAP_DELTA):
                d.parser_step = 3
            else:
                d.parser_step = 0
        elif st == 3:  # FoundSync
            if (not level and (diff(duration, c.te_long) < c.te_delta or
                               diff(duration, c.te_short) < c.te_delta)):
                d.te_last = duration
                d.parser_step = 4
            else:
                d.parser_step = 0
        elif st == 4:  # SaveDuration
            next_step = 0
            valid = False
            bit = 0
            if level:
                if diff(duration, c.te_long) < c.te_delta:
                    if diff(d.te_last, c.te_short) < c.te_delta:
                        bit = 0
                        valid = True
                elif diff(duration, c.te_short) < c.te_delta:
                    if diff(d.te_last, c.te_long) < c.te_delta:
                        bit = 1
                        valid = True
                if valid:
                    d.add_bit(bit)
                    next_step = 3
            d.parser_step = next_step
            if 64 <= d.decode_count_bit <= 80:
                self._process()


# ============================================================ CHRYSLER V0
# chrysler_v0.c : short=300 delta=150, long-mark=3400/3700 delta=400, gap=8000,
# frame_gap=15600. Preamble counted on LOW long-marks; data bits derived from
# (high te_last, low long-mark) pairs; 80 bits. Gate = check_ok (structural).
_CHRYS_XOR = [0x0F, 0x02, 0x40, 0x0C, 0x30, 0x0E, 0x70, 0x08,
              0x10, 0x0A, 0x50, 0xF4, 0x2F, 0xF6, 0x6F, 0xF0]
_CHRYS_BTN = {1: "Lock", 2: "Unlock"}


def _chrys_rev6(value):
    out = 0
    for _ in range(6):
        out = ((out << 1) | (value & 1)) & 0xFF
        value >>= 1
    return out


def _chrys_transform(block9, key, button):
    mask = _CHRYS_XOR[key & 0x0F]
    if button == 1:
        mask ^= 0xF0 if (key & 1) else 0x0F
    return [(x ^ mask) & 0xFF for x in block9]


class ChryslerV0(ProtoDecoder):
    NAME = "Chrysler V0"
    CONST = Const(300, 3400, 150, 80)
    TE_SHORT = 300
    DELTA = 150
    LONG_A = 3400
    LONG_B = 3700
    LONG_DELTA = 400
    GAP = 8000
    BITS = 0x50

    def __init__(self):
        super().__init__()
        self.packet_bit_count = 0
        self.data_2 = 0

    def reset(self):
        super().reset()
        self.packet_bit_count = 0
        self.data_2 = 0

    def _is_short(self, dur):
        return diff(dur, self.TE_SHORT) <= self.DELTA

    def _is_long_mark(self, dur):
        return (diff(dur, self.LONG_A) <= self.LONG_DELTA or
                diff(dur, self.LONG_B) <= self.LONG_DELTA)

    def _commit(self, data):
        b = u64_to_bytes_be(data)
        key2 = self.data_2 & 0xFFFF
        seed = _chrys_rev6(b[0] >> 2)
        b1_xor_b6 = b[6] ^ b[1]
        msb_set = (b[0] & 0x80) != 0

        if msb_set:
            key2_low = key2 & 0xFF
            check_ok = (b[1] == b[5]) and (b1_xor_b6 == 0x62)
            decoded_button = 2 if ((key2_low ^ b[4]) & 0xFF) == 0x10 else 1
        else:
            check_ok = False
            decoded_button = 1
            if ((b[1] ^ 0xC3) & 0xFF) == b[5]:
                if b1_xor_b6 == 0x04:
                    check_ok = True
                else:
                    check_ok = (b1_xor_b6 == 0x08)
                    if b1_xor_b6 == 0x08:
                        decoded_button = 2
            else:
                if b1_xor_b6 == 0x08:
                    decoded_button = 2

        encoded = [b[1], b[2], b[3], b[4], b[5], b[6], b[7],
                   (key2 >> 8) & 0xFF, key2 & 0xFF]
        decoded = _chrys_transform(encoded, seed, decoded_button)

        if seed & 1:
            serial = ((decoded[0] << 24) | (decoded[1] << 16) |
                      (decoded[2] << 8) | decoded[7]) & 0xFFFFFFFF
        else:
            serial = ((decoded[0] << 24) | (decoded[1] << 16) |
                      (decoded[2] << 8) | decoded[3]) & 0xFFFFFFFF

        if check_ok:
            self.emit({"serial": serial, "btn": decoded_button,
                       "btn_name": _CHRYS_BTN.get(decoded_button, "??"),
                       "cnt": seed, "data": data, "crc_ok": True})

    def feed(self, level, duration):
        d = self.d
        st = d.parser_step
        if st == 0:  # Reset
            if level and self._is_short(duration):
                self.packet_bit_count = 0
                d.te_last = duration
                d.parser_step = 1
        elif st == 1:  # Seek
            if level:
                d.te_last = duration
                return
            if self._is_long_mark(duration):
                if self._is_short(d.te_last):
                    self.packet_bit_count += 1
                elif self.packet_bit_count > 0x0F:
                    self.data_2 = 0
                    d.parser_step = 2
                    d.decode_data = 1
                    d.decode_count_bit = 1
                else:
                    self.packet_bit_count = 0
                    d.parser_step = 1
                return
            if duration > self.GAP and self.packet_bit_count > 0x0F:
                d.decode_data = 0
                self.data_2 = 0
                d.decode_count_bit = 0
                d.parser_step = 2
                return
            d.parser_step = 0
            self.packet_bit_count = 0
        elif st == 2:  # Data
            if level:
                d.te_last = duration
                return
            count = d.decode_count_bit & 0xFF
            if duration > self.GAP:
                if count > 0x4F:
                    self._commit(d.decode_data)
                d.parser_step = 0
                self.packet_bit_count = 0
                return
            if d.te_last < self.TE_SHORT:
                if not self._is_short(d.te_last) or not self._is_long_mark(duration):
                    if count > 0x4F:
                        self._commit(d.decode_data)
                    d.parser_step = 0
                    self.packet_bit_count = 0
                    return
                bit_value = 1
            else:
                if d.te_last > 0x2EE or not self._is_long_mark(duration):
                    if count > 0x4F:
                        self._commit(d.decode_data)
                    d.parser_step = 0
                    self.packet_bit_count = 0
                    return
                bit_value = 1 if self._is_short(d.te_last) else 0

            bit = bit_value ^ 1
            new_count = (count + 1) & 0xFF
            if count <= 0x3F:
                d.decode_data = ((d.decode_data << 1) | bit) & MASK64
                d.decode_count_bit = new_count
                return
            self.data_2 = ((self.data_2 << 1) | bit) & 0xFFFF
            d.decode_count_bit = new_count
            if new_count != self.BITS:
                return
            self._commit(d.decode_data)
            d.decode_data = 0
            self.data_2 = 0
            d.decode_count_bit = 0
            d.parser_step = 0
            self.packet_bit_count = 0


# ============================================================ JEEP V0
# jeep_v0.c : 200/400 Manchester, 96 bits, INVERTED polarity (level->High), then
# the whole frame is bit-inverted. Gate: byte6==0x07 and a known 16-bit button.
_JEEP_BTN = {0x8104: "Unlock", 0x8044: "Lock", 0x8804: "RS", 0x8024: "Panic"}


class JeepV0(ProtoDecoder):
    NAME = "Jeep V0"
    CONST = Const(200, 400, 100, 96)

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self.preamble = 0
        self.data_32 = 0

    def reset(self):
        super().reset()
        self.man.reset()
        self.preamble = 0
        self.data_32 = 0

    def _event(self, duration, level):
        # jeep polarity: short -> level?High:Low  (opposite of arke default)
        if diff(duration, 200) <= 100:
            return M_SHORT_HIGH if level else M_SHORT_LOW
        if diff(duration, 400) <= 100:
            return M_LONG_HIGH if level else M_LONG_LOW
        return M_RESET_EV

    def feed(self, level, duration):
        d = self.d
        st = d.parser_step
        if st == 0:  # Reset
            if diff(duration, 200) <= 100:
                self.preamble = 1
                d.te_last = duration
                d.parser_step = 1
        elif st == 1:  # Preamble
            is_s = diff(duration, 200) <= 100
            is_l = diff(duration, 400) <= 100
            if is_s:
                self.preamble += 1
            elif is_l and self.preamble >= 16:
                self.man.reset()
                self.man.advance(M_LONG_HIGH if level else M_LONG_LOW)
                d.decode_data = 0
                d.decode_count_bit = 0
                self.data_32 = 0
                d.parser_step = 2
            else:
                d.parser_step = 0
                self.preamble = 0
        elif st == 2:  # Data
            ev = self._event(duration, level)
            if ev == M_RESET_EV:
                d.parser_step = 0
                return
            bit = self.man.advance(ev)
            if bit is None:
                return
            b = 1 if bit else 0
            if d.decode_count_bit < 64:
                d.decode_data = ((d.decode_data << 1) | b) & MASK64
            else:
                self.data_32 = ((self.data_32 << 1) | b) & 0xFFFFFFFF
            d.decode_count_bit += 1
            if d.decode_count_bit == 96:
                data = (~d.decode_data) & MASK64
                hop = (~self.data_32) & 0xFFFFFFFF
                serial = (data >> 32) & 0xFFFFFFFF
                btn_code = (data >> 16) & 0xFFFF
                b6 = (data >> 8) & 0xFF
                cnt = data & 0xFF
                frame_ok = (b6 == 0x07 and btn_code in _JEEP_BTN)
                if frame_ok:
                    self.emit({"serial": serial, "btn": btn_code,
                               "btn_name": _JEEP_BTN.get(btn_code, "0x%X" % btn_code),
                               "cnt": cnt, "hop": hop, "data": data,
                               "crc_ok": True, "gate": "fixed-field"})
                d.decode_data = 0
                d.decode_count_bit = 0
                self.data_32 = 0
                self.preamble = 0
                d.parser_step = 0


# ============================================================ FIAT V0 / SPA
# fiat_v0.c / fiat_spa.c : 200/400 Manchester (arke default polarity). Preamble
# >=150 short pulses then an 800us gap (low). 71-bit frame: fix(32)|hop(32) at
# bit 64, endbyte(6) at bit 71. No checksum -> degenerate-serial reject only.
# Success = a STABLE serial (fix) across presses (structural).
class FiatV0(ProtoDecoder):
    NAME = "Fiat V0"
    CONST = Const(200, 400, 100, 71)
    PREAMBLE_PAIRS = 150
    GAP = 800

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self.preamble_count = 0
        self.data_low = 0
        self.data_high = 0
        self.bit_count = 0
        self.hop = 0
        self.fix = 0
        self.endbyte = 0

    def reset(self):
        super().reset()
        self.man.reset()
        self.preamble_count = 0
        self.data_low = 0
        self.data_high = 0
        self.bit_count = 0
        self.hop = 0
        self.fix = 0
        self.endbyte = 0

    def _finish(self):
        if self.fix == 0 or self.fix == 0xFFFFFFFF:
            self.data_low = 0
            self.data_high = 0
            self.bit_count = 0
            self.d.parser_step = 0
            return
        self.emit({"serial": self.fix, "btn": self.endbyte & 0x3F,
                   "btn_name": "0x%02X" % (self.endbyte & 0x3F),
                   "cnt": self.hop, "hop": self.hop,
                   "data": ((self.hop << 32) | self.fix), "crc_ok": None,
                   "gate": "structural"})
        self.data_low = 0
        self.data_high = 0
        self.bit_count = 0
        self.d.parser_step = 0

    def feed(self, level, duration):
        d = self.d
        c = self.CONST
        st = d.parser_step
        if st == 0:  # Reset
            if not level:
                return
            if diff(duration, c.te_short) < c.te_delta:
                self.data_low = 0
                self.data_high = 0
                self.preamble_count = 0
                self.bit_count = 0
                d.parser_step = 1
                self.man.reset()
        elif st == 1:  # Preamble
            if level:
                if diff(duration, c.te_short) < c.te_delta:
                    self.preamble_count += 1
                else:
                    d.parser_step = 0
                return
            # low
            if diff(duration, c.te_short) < c.te_delta:
                self.preamble_count += 1
            else:
                if (self.preamble_count >= self.PREAMBLE_PAIRS and
                        diff(duration, self.GAP) < c.te_delta):
                    d.parser_step = 2
                    self.preamble_count = 0
                    self.data_low = 0
                    self.data_high = 0
                    self.bit_count = 0
                    self.man.reset()
                    return
                d.parser_step = 0
        elif st == 2:  # Data
            ev = manchester_event(duration, level, c)
            if ev != M_RESET_EV:
                bit = self.man.advance(ev)
                if bit is not None:
                    nb = 1 if bit else 0
                    carry = (self.data_low >> 31) & 1
                    self.data_low = ((self.data_low << 1) | nb) & 0xFFFFFFFF
                    self.data_high = ((self.data_high << 1) | carry) & 0xFFFFFFFF
                    self.bit_count += 1
                    if self.bit_count == 64:
                        self.fix = self.data_low
                        self.hop = self.data_high
                        self.data_low = 0
                        self.data_high = 0
                    if self.bit_count == 0x47:
                        self.endbyte = self.data_low & 0x3F
                        self._finish()
            else:
                if self.bit_count == 0x47:
                    self.endbyte = self.data_low & 0x3F
                    self._finish()
                elif self.bit_count < 64:
                    d.parser_step = 0


class FiatSpa(FiatV0):
    NAME = "Fiat SPA"


# ============================================================ MERCEDES FOB
# mercedes_fob.c : 100/200 Manchester, hunt a 16-bit sync (PLACEHOLDER 0x0F0F in
# firmware), then 80 data bits, XOR checksum over frame bytes 2..10 == byte 11.
# NOTE: the real sync word is unknown/placeholder, so this typically no-decodes.
_MERC_SYNC = 0x0F0F
_MERC_SYNC_BITS = 16
_MERC_DATA_BYTES = 10
_MERC_DATA_BITS = 80


class MercedesFob(ProtoDecoder):
    NAME = "Mercedes Fob"
    CONST = Const(100, 200, 50, 64)

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self.sync_shift = 0
        self.frame = bytearray(_MERC_DATA_BYTES)
        self.data_bits = 0

    def reset(self):
        super().reset()
        self.man.reset()
        self.sync_shift = 0
        self.frame = bytearray(_MERC_DATA_BYTES)
        self.data_bits = 0

    def _event(self, level, duration):
        c = self.CONST
        if diff(duration, c.te_long) < c.te_delta * 2:
            is_long = True
        elif diff(duration, c.te_short) < c.te_delta:
            is_long = False
        else:
            return M_RESET_EV
        if level:
            return M_LONG_HIGH if is_long else M_SHORT_HIGH
        return M_LONG_LOW if is_long else M_SHORT_LOW

    def _checksum_ok(self):
        calc = 0
        for i in range(_MERC_DATA_BYTES - 1):
            calc ^= self.frame[i]
        return calc == self.frame[_MERC_DATA_BYTES - 1]

    def feed(self, level, duration):
        d = self.d
        ev = self._event(level, duration)
        if ev == M_RESET_EV:
            d.parser_step = 0
            self.sync_shift = 0
            self.data_bits = 0
            self.man.reset()
            return
        bit = self.man.advance(ev)
        if bit is None:
            return
        b = 1 if bit else 0
        st = d.parser_step
        if st == 0:  # Reset -> fall into FindSync consuming this bit
            self.sync_shift = 0
            self.man = self.man  # keep state
            d.parser_step = 1
            st = 1
        if st == 1:  # FindSync
            self.sync_shift = ((self.sync_shift << 1) | b) & ((1 << _MERC_SYNC_BITS) - 1)
            if self.sync_shift == _MERC_SYNC:
                d.parser_step = 2
                self.data_bits = 0
                self.frame = bytearray(_MERC_DATA_BYTES)
        elif st == 2:  # Data
            idx = self.data_bits >> 3
            self.frame[idx] = ((self.frame[idx] << 1) | b) & 0xFF
            self.data_bits += 1
            if self.data_bits >= _MERC_DATA_BITS:
                if self._checksum_ok():
                    f = self.frame
                    serial = (f[0] << 16) | (f[1] << 8) | f[2]
                    seq = f[3]
                    button = f[4]
                    rolling = (f[5] << 24) | (f[6] << 16) | (f[7] << 8) | f[8]
                    self.emit({"serial": serial, "btn": button,
                               "btn_name": {0x08: "Unlock", 0x04: "Lock"}.get(button, "?"),
                               "cnt": seq & 0x0F, "hop": rolling,
                               "crc": f[9], "crc_ok": True})
                d.parser_step = 0
                self.sync_shift = 0
                self.data_bits = 0


# ============================================================ LDV V0
# ldv_v0.c : 250/500 Manchester (arke default polarity). Preamble = >=48 same
# manchester bits, then a value flip = sync. 128-bit frame. Gate = fixed-field
# (byte6 framing 0x80|..|0x07, 0xFFFF separator at bytes7-8, btn duplicated).
_LDV_BTN = {1: "Unlock", 2: "Lock", 3: "Trunk"}


class LdvV0(ProtoDecoder):
    NAME = "LDV V0"
    CONST = Const(250, 500, 120, 128)

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self.preamble_count = 0
        self.last_bit_value = 0
        self.data_2 = 0

    def reset(self):
        super().reset()
        self.man.reset()
        self.preamble_count = 0
        self.last_bit_value = 0
        self.data_2 = 0

    def _extract(self):
        data = self.d.decode_data
        serial = (data >> 32) & 0xFFFFFFFF
        byte6 = (data >> 8) & 0xFF
        btn = (byte6 >> 3) & 0x03
        byte9 = (self.data_2 >> 48) & 0xFF
        byte10 = (self.data_2 >> 40) & 0xFF
        cnt_field = (byte9 << 8) | byte10
        cnt = cnt_field >> 5
        return serial, btn, cnt

    def _valid(self):
        data = self.d.decode_data
        byte6 = (data >> 8) & 0xFF
        byte7 = data & 0xFF
        byte8 = (self.data_2 >> 56) & 0xFF
        byte9 = (self.data_2 >> 48) & 0xFF
        byte10 = (self.data_2 >> 40) & 0xFF
        if (byte6 & 0x80) == 0:
            return False
        if (byte6 & 0x07) != 0x07:
            return False
        if byte7 != 0xFF or byte8 != 0xFF:
            return False
        byte6_btn = (byte6 >> 3) & 0x03
        cnt_field = (byte9 << 8) | byte10
        cnt_btn = (cnt_field >> 3) & 0x03
        return byte6_btn == cnt_btn

    def _commit(self):
        if self.d.decode_count_bit < 128:
            return
        serial, btn, cnt = self._extract()
        if self._valid():
            hop = self.data_2 & 0xFFFFFFFFFF
            self.emit({"serial": serial, "btn": btn,
                       "btn_name": _LDV_BTN.get(btn, "??"),
                       "cnt": cnt, "hop": hop, "data": self.d.decode_data,
                       "data2": self.data_2, "crc_ok": True, "gate": "fixed-field"})

    def feed(self, level, duration):
        d = self.d
        c = self.CONST
        ev = manchester_event(duration, level, c)
        st = d.parser_step
        if st == 0:  # Reset
            if level and diff(duration, c.te_short) < c.te_delta:
                self.preamble_count = 0
                d.decode_data = 0
                d.decode_count_bit = 0
                self.data_2 = 0
                d.parser_step = 1
                self.man.reset()
        elif st == 1:  # Preamble
            if duration > c.te_long * 4:
                d.parser_step = 0
                return
            if ev == M_RESET_EV:
                d.parser_step = 0
                return
            bit = self.man.advance(ev)
            if bit is not None:
                bval = 1 if bit else 0
                if bval == self.last_bit_value and self.preamble_count > 0:
                    self.preamble_count += 1
                elif self.preamble_count == 0:
                    self.last_bit_value = bval
                    self.preamble_count = 1
                else:
                    if self.preamble_count >= 48:
                        d.decode_data = 0
                        d.decode_count_bit = 0
                        self.data_2 = 0
                        d.parser_step = 2
                    else:
                        self.last_bit_value = bval
                        self.preamble_count = 1
        elif st == 2:  # Data
            if duration > c.te_long * 4:
                self._commit()
                d.parser_step = 0
                return
            if ev == M_RESET_EV:
                d.parser_step = 0
                return
            bit = self.man.advance(ev)
            if bit is not None:
                b = 1 if bit else 0
                if d.decode_count_bit < 64:
                    d.decode_data = ((d.decode_data << 1) | b) & MASK64
                else:
                    self.data_2 = ((self.data_2 << 1) | b) & MASK64
                d.decode_count_bit += 1
                if d.decode_count_bit == 128:
                    self._commit()
                    d.decode_data = 0
                    d.decode_count_bit = 0
                    self.data_2 = 0
                    d.parser_step = 0


ALL = [Subaru, ChryslerV0, JeepV0, FiatV0, FiatSpa, MercedesFob, LdvV0]
