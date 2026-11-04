#!/usr/bin/env python3
r"""
arke_decoders.py -- ported Flipper automotive RAW demodulators (feed() state
machines) built on arke_core. Each class turns a RAW pulse capture into frames
with serial/button/counter and (where the frame carries one) a validated CRC.

Registry ALL_DECODERS is consumed by the master runner. Add a class + append it.
"""
from arke_core import (Const, ProtoDecoder, is_short, is_long, crc8, diff,
                       u64_to_bytes_be, bytes_to_u64_be, rev8,
                       Manchester, manchester_event, M_RESET_EV)

_KIA_BTN = {0x1: "Lock", 0x2: "Unlock", 0x3: "Trunk", 0x4: "Panic"}


# ============================================================ KIA/HYU V0
# ts=250 tl=500 delta=100, 61-bit KIA (also 64-bit Suzuki, 72-bit Honda).
class KiaV0(ProtoDecoder):
    NAME = "KIA/HYU V0"
    CONST = Const(250, 500, 100, 61)
    KIA_BITS, SUZ_BITS, HON_BITS = 61, 64, 72

    def _kia_gap(self, d):
        return d >= 700 and (d - 700) <= 1000

    def _suz_gap(self, d):
        return d >= 2000 and (d - 2000) <= 500

    def _crc_poly(self, data):
        cb = [(data >> s) & 0xFF for s in (48, 40, 32, 24, 16, 8)]
        return crc8(cb, 0x7F, 0)

    def _verify_kia(self, data):
        return self._crc_poly(data) == (data & 0xFF)

    def _honda_transform(self, data):
        bs = [rev8(b) for b in u64_to_bytes_be(data)]
        return bytes_to_u64_be(bs)

    def _honda_valid(self, key):
        return ((key >> 60) == 0x0F and ((key >> 56) & 0x0F) == 0
                and ((key >> 8) & 0x0F) == 0x0A)

    # -- field parse --
    def _emit_kia(self, data):
        serial = (data >> 12) & 0x0FFFFFFF
        btn = (data >> 8) & 0x0F
        cnt = (data >> 40) & 0xFFFF
        crc = data & 0xFF
        self.emit({"variant": "KIA", "data": data, "serial": serial, "btn": btn,
                   "btn_name": _KIA_BTN.get(btn, "0x%X" % btn), "cnt": cnt,
                   "crc": crc, "crc_ok": self._verify_kia(data)})

    def _emit_honda(self, key):
        bs = u64_to_bytes_be(key)
        serial = (bs[3] << 16) | (bs[4] << 8) | bs[5]
        cnt = (rev8(bs[1]) << 8) | rev8(bs[2])
        btn = bs[6] >> 5
        self.emit({"variant": "Honda", "data": key, "serial": serial, "btn": btn,
                   "btn_name": "0x%X" % btn, "cnt": cnt, "crc": bs[7], "crc_ok": True})

    def _try_honda(self):
        d = self.d
        if d.decode_count_bit != self.KIA_BITS:
            return False
        if self._verify_kia(d.decode_data):
            return False
        raw = d.decode_data & 0x0FFFFFFFFFFFFFFF
        key = self._honda_transform(raw)
        if not self._honda_valid(key):
            return False
        self._emit_honda(key)
        d.reset()
        return True

    def _finish_at_gap(self):
        d = self.d
        if d.decode_count_bit != self.KIA_BITS:
            d.reset()
            return
        data = d.decode_data
        if self._verify_kia(data):
            self._emit_kia(data)
            d.reset()
            return
        raw = data & 0x0FFFFFFFFFFFFFFF
        key = self._honda_transform(raw)
        if self._honda_valid(key):
            self._emit_honda(key)
        d.reset()

    def feed(self, level, duration):
        d = self.d
        c = self.CONST
        st = d.parser_step
        if st == 0:  # Reset
            d.reset()
            if not level:
                return
            if not is_short(duration, c):
                return
            d.parser_step = 1
            d.te_last = duration
        elif st == 1:  # Preamble
            if level:
                if is_short(duration, c) or is_long(duration, c):
                    d.te_last = duration
                else:
                    d.reset()
            elif is_short(duration, c) and is_short(d.te_last, c):
                # preamble_pairs tracked in head_64 (spare field)
                d.head_64 += 1
            elif is_long(duration, c) and is_long(d.te_last, c):
                if d.head_64 > 14:
                    d.decode_data = 0
                    d.decode_count_bit = 0
                    d.add_bit(1)
                    d.add_bit(1)
                    d.parser_step = 2
                else:
                    d.reset()
            else:
                d.reset()
        elif st == 2:  # SaveDuration
            if not level:
                d.reset()
                return
            if self._kia_gap(duration):
                self._finish_at_gap()
                return
            if self._suz_gap(duration) and self._suz_gap(d.te_last):
                d.reset()  # Suzuki variant not needed for this collection
                return
            if is_short(duration, c) or is_long(duration, c):
                d.te_last = duration
                d.parser_step = 3
            else:
                d.reset()
        elif st == 3:  # CheckDuration
            if level:
                d.reset()
                return
            if is_short(d.te_last, c) and is_short(duration, c):
                d.add_bit(0)
                if not self._try_honda():
                    d.parser_step = 2
            elif is_long(d.te_last, c) and is_long(duration, c):
                d.add_bit(1)
                if not self._try_honda():
                    d.parser_step = 2
            elif self._suz_gap(duration):
                d.te_last = duration
                d.parser_step = 2
            else:
                if not self._try_honda():
                    d.reset()


# ============================================================ KIA/HYU V1
# ts=800 tl=1600 delta=200. Manchester, 57 bits. Preamble >70 long-long pairs,
# then a short after a long => start bit 1, then Manchester payload.
class KiaV1(ProtoDecoder):
    NAME = "KIA/HYU V1"
    CONST = Const(800, 1600, 200, 57)
    _BTN = {0x1: "Close", 0x2: "Open", 0x3: "Boot"}

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self.header = 0

    def reset(self):
        super().reset()
        self.man.reset()
        self.header = 0

    def _crc4(self, bs, off):
        crc = 0
        for b in bs:
            crc ^= (b & 0x0F) ^ (b >> 4)
        return (crc + off) & 0x0F

    def _check(self, data):
        serial = data >> 24
        btn = (data >> 16) & 0xFF
        cnt = (((data >> 4) & 0xF) << 8) | ((data >> 8) & 0xFF)
        cnt_high = (cnt >> 8) & 0xF
        cd = [(serial >> 24) & 0xFF, (serial >> 16) & 0xFF, (serial >> 8) & 0xFF,
              serial & 0xFF, btn, cnt & 0xFF, cnt_high]
        crc = self._crc4(cd, 1)
        return serial, btn, cnt, crc, (crc == (data & 0xF))

    def feed(self, level, duration):
        d = self.d
        c = self.CONST
        st = d.parser_step
        if st == 0:
            if level and diff(duration, c.te_long) < c.te_delta:
                d.parser_step = 1
                d.te_last = duration
                self.header = 0
                d.decode_data = 0
                d.decode_count_bit = 0
                self.man.reset()
        elif st == 1:
            if not level:
                if (diff(duration, c.te_long) < c.te_delta
                        and diff(d.te_last, c.te_long) < c.te_delta):
                    self.header += 1
                    d.te_last = duration
                else:
                    d.parser_step = 0
            if self.header > 70:
                if (not level and diff(duration, c.te_short) < c.te_delta
                        and diff(d.te_last, c.te_long) < c.te_delta):
                    d.decode_count_bit = 1
                    d.decode_data = (d.decode_data << 1) | 1
                    self.header = 0
                    d.parser_step = 2
        elif st == 2:
            ev = manchester_event(duration, level, c)
            if ev != M_RESET_EV:
                bit = self.man.advance(ev)
                if bit is not None:
                    d.decode_data = (d.decode_data << 1) | (1 if bit else 0)
                    d.decode_count_bit += 1
            if d.decode_count_bit == c.min_bits:
                data = d.decode_data
                serial, btn, cnt, crc, ok = self._check(data)
                if ok:
                    # 4-bit CRC (FP ~1/16) is too weak for a single-frame
                    # "verified" claim; structural -> needs >=2 same-serial frames.
                    self.emit({"data": data, "serial": serial, "btn": btn,
                               "btn_name": self._BTN.get(btn, "0x%X" % btn),
                               "cnt": cnt, "crc": crc, "crc_ok": None})
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 0


ALL_DECODERS = [KiaV0, KiaV1]
