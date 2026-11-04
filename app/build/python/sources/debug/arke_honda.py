#!/usr/bin/env python3
r"""
arke_honda.py -- Honda / Acura Sub-GHz RAW decoders ported from the Flipper
firmware (momentum-tembed lib/subghz/protocols/honda_*.c) to pure Python on top
of arke_core, so RAW (.sub) pulse captures can be demodulated on the PC exactly
as the firmware does on-device.

Each class is a subclass of ProtoDecoder with a feed(level, duration) state
machine that mirrors the corresponding C decoder's feed() + process/parse +
integrity check.  A frame is only emitted when its own integrity check passes:

  crc_ok = True   the frame carries a real CRC / checksum and it validated
                  (V3 CRC-16, V4 inverse-byte invariant, V5 CRC-8, V7 XOR,
                   V8 KeeLoq key+discriminant match, V9 XOR, honda_static XOR)
  crc_ok = None   the protocol has no CRC; emission is structural/plausibility
                  gated only (V2 sync-pattern, V6 skeleton/plausibility,
                  V10 flags==0x7).  Reported but NOT counted as a CRC success.

honda_static is a FIXED / replayable code (button+serial+counter+8-bit XOR
checksum); its checksum passing is a genuine integrity gate so it is reported
with crc_ok=True and flagged fixed=True.

ALL (bottom) lists every class implemented, for arke_core.run_decoders().
"""
from arke_core import (
    Const, ProtoDecoder, diff, crc8, crc16, rev8, reverse_key,
    u64_to_bytes_be, bytes_to_u64_be, Manchester,
)

MASK64 = 0xFFFFFFFFFFFFFFFF

# Manchester event ints (toolbox / arke_core convention)
_M_SHORT_LOW, _M_SHORT_HIGH, _M_LONG_LOW, _M_LONG_HIGH, _M_RESET = 0, 2, 4, 6, 8


# =====================================================================
# Honda custom Manchester (LUT {0x01,0x91,0x9B,0xFB}) used by V2/V3 & V5.
# NOTE: this differs from arke_core.Manchester on the "no transition" case:
# the firmware LUT decode leaves state UNCHANGED (does not reset) when
# new_state == state, so it must be ported verbatim rather than reused.
# =====================================================================
_HONDA_MAN_LUT = (0x01, 0x91, 0x9B, 0xFB)


def _honda_man_step(state, timing_type):
    """Mirror honda_manchester_decode(). Returns (state, produced, bit)."""
    if timing_type == 8:
        return 1, False, 0
    if state >= 4:
        return 1, False, 0
    entry = _HONDA_MAN_LUT[state]
    new_state = (entry >> timing_type) & 0x3
    if state == new_state:
        return state, False, 0
    state = new_state
    if new_state == 2:
        return state, True, 0
    if new_state == 1:
        return state, True, 1
    return state, False, 0


def _honda_tt(duration, level, ts, tl, delta):
    """timing_type for the honda custom manchester: short high->0 low->2,
    long high->4 low->6, invalid->8.  Uses <= delta like the firmware."""
    if diff(duration, ts) <= delta:
        return (0 if level else 2), True
    if diff(duration, tl) <= delta:
        return (4 if level else 6), True
    return 8, False


def _crc8_reflected(data, poly, init=0):
    crc = init & 0xFF
    for b in data:
        crc ^= b & 0xFF
        for _ in range(8):
            crc = ((crc >> 1) ^ poly) & 0xFF if (crc & 1) else (crc >> 1) & 0xFF
    return crc


def _bits_to_bytes_msb(bits, nbytes):
    out = [0] * nbytes
    for i, b in enumerate(bits):
        bi = i >> 3
        if bi >= nbytes:
            break
        if b:
            out[bi] |= 1 << (7 - (i & 7))
    return out


_HONDA_KEELOQ_BTN = {0x0: "Panic", 0x1: "Lock", 0x2: "Trunk",
                     0x4: "Remote Start", 0x8: "Unlock", 0x9: "Unlock x2"}


# =====================================================================
# HONDA V2 & V3  (honda_v2_v3.c) -- Manchester, dual variant auto-detect
#   V2: te 96/192 d50, 148b, NO CRC (6-byte sync-pattern gated)  -> crc_ok None
#   V3: te 250/500 d80, 120b, CRC-16 poly 0x2627 + parity-mask   -> crc_ok True
# =====================================================================
_HV2_SYNC = (0x0E, 0x1E, 0x1E, 0x1E, 0x0D, 0x6C)
_HV2_BTN = {0x20: "Lock", 0x40: "Unlock", 0x60: "Trunk"}
_HV3_BTN = {0x00: "Enc", 0x8A: "Lock", 0x8C: "Unlock", 0x8E: "Unlock", 0x80: "Unlock"}
_HV3_CRC_MASKS = (0x8181, 0x4141, 0x2121, 0x80F2, 0xAF15, 0x0606, 0x0202, 0x0000)


def _hv3_crc(frame):
    # CRC-16 MSB poly 0x2627 over frame[1..13] (13 bytes), then 8-bit parity extract
    crc = crc16(frame[1:14], 0x2627, 0)
    result = 0
    const = 0x1B
    for b in range(8):
        m = crc & _HV3_CRC_MASKS[b]
        m ^= m >> 8
        m ^= m >> 4
        m ^= m >> 2
        m ^= m >> 1
        p = m & 1
        result |= (p ^ ((const >> (7 - b)) & 1)) << (7 - b)
    return result & 0xFF


class HondaV2V3(ProtoDecoder):
    NAME = "Honda V2&V3"
    _V2 = dict(ts=96, tl=192, delta=50, min_gap=500, max_bits=200, min_found=100)
    _V3 = dict(ts=250, tl=500, delta=80, min_gap=1500, max_bits=200, min_found=100)

    def __init__(self):
        super().__init__()
        self.step = 0
        self.variant = None
        self.man_state = 1
        self.bits = []

    def _p(self):
        return self._V2 if self.variant == "V2" else self._V3

    def _decode_timing(self, level, duration):
        p = self._p()
        tt, valid = _honda_tt(duration, level, p["ts"], p["tl"], p["delta"])
        if valid:
            self.man_state, produced, bit = _honda_man_step(self.man_state, tt)
            if produced:
                if len(self.bits) < p["max_bits"]:
                    self.bits.append(bit)
                if len(self.bits) >= p["max_bits"]:
                    self._process()
                    self.step = 0
                    self.bits = []
                    self.man_state = 1
        else:
            if len(self.bits) >= p["min_found"]:
                self._process()
            self.step = 0
            self.bits = []
            self.man_state = 1

    def _process(self):
        nbytes = (len(self.bits) + 7) // 8
        raw = _bits_to_bytes_msb(self.bits, max(nbytes, 32))
        if self.variant == "V2":
            self._process_v2(raw, nbytes)
        else:
            self._process_v3(raw, nbytes)

    def _process_v2(self, raw, byte_count):
        SYNC, DATA = 6, 13
        if byte_count < SYNC + DATA:
            return
        sync_off = -1
        for off in range(0, byte_count - SYNC - DATA + 1):
            if all(raw[off + s] == _HV2_SYNC[s] for s in range(SYNC)):
                sync_off = off
                break
        if sync_off < 0:
            return
        d = raw[sync_off + SYNC:]
        serial = (d[0] << 16) | (d[1] << 8) | d[2]
        btn1, btn2 = d[3], d[4]
        counter = d[7]
        rolling = (d[8] << 24) | (d[9] << 16) | (d[10] << 8) | d[11]
        # validate button pair
        pairs = {(0x40, 0x20), (0x41, 0x40), (0x41, 0x20), (0x40, 0x60)}
        if (btn1, btn2) not in pairs:
            return
        self.emit({"variant": "V2", "serial": serial, "btn": btn2,
                   "btn_name": _HV2_BTN.get(btn2, "0x%X" % btn2),
                   "cnt": counter, "rolling": rolling, "crc_ok": None,
                   "note": "sync-pattern gated, no CRC"})

    def _process_v3(self, raw, byte_count):
        TOTAL = 15
        while byte_count > TOTAL and raw[byte_count - 1] == 0x55:
            byte_count -= 1
        if byte_count < TOTAL:
            return
        sync_idx = -1
        for i in range(0, byte_count - TOTAL + 1):
            if raw[i] == 0x6A:
                sync_idx = i
                break
        if sync_idx < 0:
            return
        d = raw[sync_idx:]
        counter = d[1]
        crc = d[14]
        if crc != _hv3_crc(d):
            return
        raw_btn = d[4]
        flags = d[5]
        is_fixed = (raw_btn & 0xF0) == 0x80 and flags in (0x82, 0x83)
        serial = ((d[2] << 8) | d[3]) if is_fixed else 0
        btn = raw_btn if is_fixed else 0
        self.emit({"variant": "V3", "serial": serial, "btn": btn,
                   "btn_name": _HV3_BTN.get(btn, "0x%X" % btn) if is_fixed else "Hop",
                   "cnt": counter >> 2, "crc": crc, "crc_ok": True,
                   "fixed": is_fixed})

    def feed(self, level, duration):
        if self.step == 0:
            best, best_diff = None, 1 << 30
            for name, p in (("V2", self._V2), ("V3", self._V3)):
                dd = diff(duration, p["ts"])
                if dd <= p["delta"] and dd < best_diff:
                    best, best_diff = name, dd
            if best is None:
                for name, p in (("V2", self._V2), ("V3", self._V3)):
                    dd = diff(duration, p["tl"])
                    if dd <= p["delta"] and dd < best_diff:
                        best, best_diff = name, dd
            if best is not None:
                self.variant = best
                self.step = 1
                self.man_state = 1
                self.bits = []
                self._decode_timing(level, duration)
        elif self.step == 1:
            p = self._p()
            if duration > p["min_gap"]:
                if len(self.bits) >= p["min_found"]:
                    self._process()
                self.step = 0
                self.bits = []
                self.man_state = 1
                return
            self._decode_timing(level, duration)


# =====================================================================
# HONDA V4 (honda_v4.c) -- PWM, te 750/1100 d200, 51 bits.
# Integrity: byte[23:16] == 0xFF - button[31:24].  crc_ok = invariant pass.
# =====================================================================
_HV4_BTN = {0x11: "Lock", 0x12: "Unlock"}


class HondaV4(ProtoDecoder):
    NAME = "Honda V4"
    CONST = Const(750, 1100, 200, 48)
    _PRE = 1500
    _PRE_D = 300
    _BITS = 51

    def __init__(self):
        super().__init__()
        self.step = 0
        self.pre = 0
        self.te_last = 0
        self.data = 0
        self.cnt = 0

    def _validate(self, data, nbits):
        if nbits < 48:
            return False
        btn = (data >> 24) & 0xFF
        inv = (data >> 16) & 0xFF
        return inv == ((0xFF - btn) & 0xFF)

    def _emit(self, data, nbits):
        if not self._validate(data, nbits):
            return
        serial = (data >> 32) & 0x7FFFF
        btn = (data >> 24) & 0xFF
        cnt = data & 0xFFFF
        self.emit({"serial": serial, "btn": btn,
                   "btn_name": _HV4_BTN.get(btn, "0x%X" % btn),
                   "cnt": cnt, "data": data, "crc_ok": True,
                   "note": "inverse-byte invariant"})

    def feed(self, level, duration):
        c = self.CONST
        is_pre = diff(duration, self._PRE) < self._PRE_D
        is_s = diff(duration, c.te_short) < c.te_delta
        is_l = diff(duration, c.te_long) < c.te_delta
        st = self.step
        if st == 0:  # Reset
            if (not level) and is_pre:
                self.pre = 0
                self.data = 0
                self.cnt = 0
                self.step = 1
        elif st == 1:  # PreambleHigh
            if level and is_pre:
                self.pre += 1
                self.step = 2
            else:
                self.step = 0
        elif st == 2:  # PreambleOrDataLow
            if not level:
                if is_pre:
                    self.step = 1
                elif self.pre >= 3 and (is_s or is_l):
                    self.te_last = duration
                    self.data = 0
                    self.cnt = 0
                    self.step = 3
                else:
                    self.step = 0
            else:
                self.step = 0
        elif st == 3:  # DataHigh
            if level:
                bit = 1 if diff(self.te_last, c.te_long) < c.te_delta else 0
                self.data = ((self.data << 1) | bit) & MASK64
                self.cnt += 1
                if self.cnt >= self._BITS:
                    self._emit(self.data, self.cnt)
                    self.step = 0
                else:
                    self.step = 4
            else:
                self.step = 0
        elif st == 4:  # DataLow
            if not level:
                if is_s or is_l:
                    self.te_last = duration
                    self.step = 3
                elif is_pre:
                    self._emit(self.data, self.cnt)
                    self.pre = 0
                    self.data = 0
                    self.cnt = 0
                    self.step = 1
                else:
                    self._emit(self.data, self.cnt)
                    self.step = 0
            else:
                self.step = 0


# =====================================================================
# HONDA V5 (honda_v5.c) -- Manchester te 63/126 d20.
#   Standard: 135b, header 0x6C0F62, CRC-8 poly 0x2F init 0x03 over bytes 0-15.
#   Alt:      168b, header 0x6428,  CRC-8 reflected poly 0x4D over bytes 0-19.
# =====================================================================
_HV5_BTN = {0x21: "Lock", 0x22: "Unlock"}


class HondaV5(ProtoDecoder):
    NAME = "Honda V5"
    CONST = Const(63, 126, 20, 130)
    _MAXB = 400

    def __init__(self):
        super().__init__()
        self.step = 0
        self.man_state = 1
        self.bits = []

    def _decode_timing(self, level, duration):
        c = self.CONST
        tt, valid = _honda_tt(duration, level, c.te_short, c.te_long, c.te_delta)
        if valid:
            self.man_state, produced, bit = _honda_man_step(self.man_state, tt)
            if produced:
                if len(self.bits) < self._MAXB:
                    self.bits.append(bit)
                if len(self.bits) >= self._MAXB:
                    self._process()
                    self.step = 0
                    self.bits = []
                    self.man_state = 1
        else:
            if len(self.bits) >= c.min_bits:
                self._process()
            self.step = 0
            self.bits = []
            self.man_state = 1

    def _process(self):
        bits = self.bits
        n = len(bits)
        start = 0
        while start < n and bits[start] == 1:
            start += 1
        data_len = n - start

        # ---- alternate 168-bit variant ----
        if data_len >= 168:
            raw = [0] * 22
            for i in range(168):
                if start + i >= n:
                    break
                bi = i // 8
                if bi < 21 and bits[start + i]:
                    raw[bi] |= 1 << (7 - (i % 8))
            alt_header = (raw[0] << 8) | raw[1]
            if alt_header == 0x6428:
                computed = _crc8_reflected(raw[0:20], 0x4D, 0)
                frame_crc = raw[20]
                serial = (raw[13] << 24) | (raw[14] << 16) | (raw[15] << 8) | raw[16]
                counter = raw[14]
                button = raw[17]
                if computed == frame_crc:
                    self.emit({"variant": "168", "serial": serial, "btn": button,
                               "btn_name": _HV5_BTN.get(button, "0x%X" % button),
                               "cnt": counter, "crc": computed, "crc_ok": True})
                return

        # ---- standard 135-bit ----
        # prepend up to 2 missing leading 1-bits from preamble
        bits = list(bits)
        if data_len < 135:
            missing = 135 - data_len
            if 0 < missing <= 2 and start >= missing:
                for _ in range(missing):
                    start -= 1
                    bits[start] = 1
                data_len = 135
        if data_len < 135:
            return
        raw = [0] * 17
        for i in range(135):
            if start + i >= len(bits):
                break
            pos = i + 1
            bi = pos // 8
            if bi < 17:
                raw[bi] |= bits[start + i] << (7 - (pos % 8))
        header = (raw[0] << 16) | (raw[1] << 8) | raw[2]
        if header != 0x6C0F62:
            return
        retransmit = raw[3]
        serial = (raw[4] << 24) | (raw[5] << 16) | (raw[6] << 8) | raw[7]
        button = raw[8]
        counter = (raw[10] << 8) | raw[11]
        rolling = (raw[12] << 24) | (raw[13] << 16) | (raw[14] << 8) | raw[15]
        tail = raw[16]
        computed = crc8(raw[0:16], 0x2F, 0x03)
        if computed != tail:
            return
        self.emit({"variant": "135", "serial": serial, "btn": button,
                   "btn_name": _HV5_BTN.get(button, "0x%X" % button),
                   "cnt": counter, "rolling": rolling, "crc": computed,
                   "rt": "RTX" if retransmit == 0x0A else "TX", "crc_ok": True})

    def feed(self, level, duration):
        c = self.CONST
        if self.step == 0:
            if diff(duration, c.te_short) <= c.te_delta:
                self.step = 1
                self.man_state = 1
                self.bits = []
                self._decode_timing(level, duration)
        elif self.step == 1:
            if duration > (c.te_long + c.te_delta * 3):
                if len(self.bits) >= c.min_bits:
                    self._process()
                self.step = 0
                self.bits = []
                self.man_state = 1
                return
            self._decode_timing(level, duration)


# =====================================================================
# HONDA V6 (honda_v6.c) -- FSK Manchester 100/200 (434) + 313 3-level.
# No true CRC: stock 45b gated on serial!=0; pandora on 40-bit skeleton.
# crc_ok = None throughout (plausibility / skeleton, not a data CRC).
# =====================================================================
_HV6_BTN = {0x01: "Lock", 0x02: "Unlock", 0x03: "Trunk", 0x10: "BCM"}


class HondaV6(ProtoDecoder):
    NAME = "Honda V6"
    CONST = Const(100, 200, 45, 43)
    _MIN_PRE = 20
    # 313 constants
    _T313_S, _T313_M, _T313_L, _T313_D = 800, 1200, 1600, 250
    _T313_MINPRE, _T313_MINTRIT, _T313_MAXTRIT = 15, 30, 60
    _PAN_MIN, _PAN_MAX = 118, 140
    _PAN_HALFA_MIN, _PAN_HALFA_MAX, _PAN_HALFB = 45, 65, 78
    _PAN_MIDGAP_MIN, _PAN_MIDGAP_MAX = 1000, 2000

    def __init__(self):
        super().__init__()
        self.step = 0  # 0 reset, 1 checkpre, 2 collect, 3 pre313, 4 data313
        self.header = 0
        self.man = Manchester()
        self.lo = 0
        self.hi = 0
        self.bit_count = 0
        self.midgap = False
        self.halfa = 0
        self.trit_count = 0
        self.d2 = 0
        self.d1 = 0

    def _man_reset(self):
        self.man.reset()

    def _man_adv(self, level, duration):
        c = self.CONST
        if diff(duration, c.te_short) <= c.te_delta:
            ev = _M_SHORT_HIGH if level else _M_SHORT_LOW
        elif diff(duration, c.te_long) <= c.te_delta:
            ev = _M_LONG_HIGH if level else _M_LONG_LOW
        else:
            return None
        return self.man.advance(ev)

    def _append(self, bit):
        if self.bit_count < 128:
            self.hi = ((self.hi << 1) | (self.lo >> 63)) & MASK64
            self.lo = ((self.lo << 1) | (1 if bit else 0)) & MASK64
        self.bit_count += 1

    @staticmethod
    def _skeleton_ok(hi, lo):
        return ((lo >> 40) & 0xFFFFFF) == 0x1E1E1F and (lo & 0xFF) == 0x51 and (hi & 0xFF) == 0xFE

    def _undrift(self, hi, lo, bit_count):
        if bit_count >= 135 or bit_count < self._PAN_MIN:
            return None
        shift = 135 - bit_count
        if shift > 16:
            return None
        new_lo = ((lo >> shift) | (hi << (64 - shift))) & MASK64
        new_hi = (hi >> shift) & MASK64
        if self._skeleton_ok(new_hi, new_lo):
            return new_hi, new_lo
        return None

    @staticmethod
    def _identify_btn(hop):
        b34 = (hop >> 34) & 1
        b31 = (hop >> 31) & 1
        b29 = (hop >> 29) & 1
        if b34:
            return 0x02
        if b29:
            return 0x01
        if b31:
            return 0x10
        return 0x03

    def _pan_cnt_width(self, bc):
        if bc < 119:
            return 0
        w = bc - 119
        return 16 if w > 16 else w

    def _pan_counter(self, hi, bc):
        w = self._pan_cnt_width(bc)
        if w == 0:
            return 0
        n = 128 if bc >= 128 else bc
        shift = n - 64 - w
        return (hi >> shift) & ((1 << w) - 1)

    def _process_fsk(self):
        bc = self.bit_count
        is_stock = self.CONST.min_bits <= bc <= 48
        is_pan = self._PAN_MIN <= bc <= self._PAN_MAX
        if not is_stock and not is_pan:
            return
        hi, lo = self.hi, self.lo
        if is_pan:
            if bc < 135:
                r = self._undrift(hi, lo, bc)
                if r is None:
                    return
                hi, lo = r
                bc = 135
            elif not self._skeleton_ok(hi, lo):
                return
        if is_stock:
            norm = lo & 0x1FFFFFFFFFFF
            cnt = (norm >> 41) & 0xF
            hop = norm & 0x1FFFFFFFFFF
            btn = self._identify_btn(hop)
            serial = hop >> 9
            if serial == 0:
                return
            self.emit({"variant": "stock45", "serial": serial, "btn": btn,
                       "btn_name": _HV6_BTN.get(btn, "0x%X" % btn), "cnt": cnt,
                       "crc_ok": None, "note": "434 FSK, serial!=0 gated (no CRC)"})
        else:
            serial = (lo >> 32) & 0xFFFFFFFF
            cnt = self._pan_counter(hi, bc)
            self.emit({"variant": "pandora", "serial": serial, "btn": 0,
                       "btn_name": "?", "cnt": cnt, "data": (hi << 64) | lo,
                       "crc_ok": None, "note": "Pandora TS 40-bit skeleton gated (no CRC)"})

    # -- 313 --
    def _add_trit(self, trit):
        if self.trit_count >= self._T313_MAXTRIT:
            return
        idx = self.trit_count
        if idx < 32:
            self.d2 |= (trit & 3) << (62 - idx * 2)
        elif idx < 64:
            self.d1 |= (trit & 3) << (62 - (idx - 32) * 2)
        self.trit_count += 1

    def _classify313(self, duration):
        if diff(duration, self._T313_S) <= self._T313_D:
            return 0
        if diff(duration, self._T313_M) <= self._T313_D:
            return 1
        if diff(duration, self._T313_L) <= self._T313_D:
            return 2
        return 3

    def _process313(self):
        if self.trit_count < self._T313_MINTRIT:
            return
        serial = (self.d2 >> 32) & 0xFFFFFFFF
        if serial == 0:
            return
        self.emit({"variant": "313", "serial": serial, "btn": 0, "btn_name": "?",
                   "cnt": self.trit_count, "crc_ok": None,
                   "note": "313MHz 3-level, serial!=0 gated (no CRC)"})

    def feed(self, level, duration):
        c = self.CONST
        is_s434 = diff(duration, c.te_short) <= c.te_delta
        is_l434 = diff(duration, c.te_long) <= c.te_delta
        is_s313 = diff(duration, self._T313_S) <= self._T313_D
        trit313 = self._classify313(duration)
        st = self.step
        if st == 0:
            if level and is_s434:
                self.step = 1
                self.header = 0
                self.lo = self.hi = 0
                self.bit_count = 0
                self.midgap = False
                self._man_reset()
            if is_s313 and not is_s434:
                self.step = 3
                self.header = 1
        elif st == 1:  # CheckPreamble (434)
            if is_s434:
                self.header += 1
            elif is_l434 and self.header >= self._MIN_PRE:
                self.step = 2
                self.lo = self.hi = 0
                self.bit_count = 0
                self.midgap = False
                self.halfa = 0
                self._man_reset()
                ev = _M_LONG_HIGH if level else _M_LONG_LOW
                bit = self.man.advance(ev)
                if bit is not None:
                    self._append(bit)
            elif not is_s434:
                self.step = 0
        elif st == 2:  # CollectData (434)
            if is_s434 or is_l434:
                bit = self._man_adv(level, duration)
                if bit is not None:
                    self._append(bit)
                    if (self.midgap and self.halfa > 0 and
                            self.bit_count >= self.halfa + self._PAN_HALFB):
                        self._process_fsk()
                        self.step = 0
            else:
                is_midgap = ((not level) and self._PAN_MIDGAP_MIN <= duration <= self._PAN_MIDGAP_MAX)
                if (is_midgap and not self.midgap and
                        self._PAN_HALFA_MIN <= self.bit_count <= self._PAN_HALFA_MAX):
                    self.midgap = True
                    self.halfa = self.bit_count
                    self._man_reset()
                    return
                if not level:
                    bit = self.man.advance(_M_LONG_LOW)
                    if bit is not None:
                        self._append(bit)
                self._process_fsk()
                self.step = 0
        elif st == 3:  # 313 preamble
            if is_s313:
                self.header += 1
            elif self.header >= self._T313_MINPRE and trit313 <= 2:
                self.step = 4
                self.trit_count = 0
                self.d1 = self.d2 = 0
                self._add_trit(trit313)
            else:
                self.step = 0
        elif st == 4:  # 313 data
            if trit313 <= 2:
                self._add_trit(trit313)
            else:
                self._process313()
                self.step = 0


# =====================================================================
# HONDA V7 (honda_v7.c) -- OOK PWM te 250/480 d100, 88b LSB-first, XOR checksum.
# =====================================================================
class HondaV7(ProtoDecoder):
    NAME = "Honda V7"
    _TS, _TL, _TD = 250, 480, 100
    _FRAME_BITS, _MIN_BITS, _MIN_PRE = 88, 64, 20

    def __init__(self):
        super().__init__()
        self.step = 0
        self.pre = 0
        self.bit_count = 0
        self.last_high = 0
        self.buf = [0] * 16

    def _classify(self, dur):
        if dur + self._TD >= self._TS and dur <= self._TS + self._TD:
            return 1
        if dur + self._TD >= self._TL and dur <= self._TL + self._TD:
            return 2
        return 0

    def _reset(self):
        self.step = 0
        self.pre = 0
        self.bit_count = 0
        self.last_high = 0
        self.buf = [0] * 16

    def _validate_emit(self):
        if self.bit_count < self._FRAME_BITS:
            return
        natural = [rev8(self.buf[i]) for i in range(11)]
        button = (natural[0] >> 4) & 0x0F
        serial = ((natural[0] & 0x0F) << 24) | (natural[1] << 16) | (natural[2] << 8) | natural[3]
        counter = (natural[4] << 16) | (natural[5] << 8) | natural[6]
        mode = (natural[7] >> 4) & 0x0F
        checksum = natural[8]
        if button == 0 or button > 0x9:
            return
        if serial == 0 or serial == 0x0FFFFFFF:
            return
        xchk = 0
        for i in range(8):
            xchk ^= natural[i]
        if xchk != checksum:
            return
        self.emit({"serial": serial, "btn": button,
                   "btn_name": _HONDA_KEELOQ_BTN.get(button, "0x%X" % button),
                   "cnt": counter, "mode": mode, "crc": checksum, "crc_ok": True})

    def feed(self, level, duration):
        c = self._classify(duration)
        st = self.step
        if st == 0:
            if level and c == 1:
                self.last_high = duration
                self.pre = 0
                self.step = 1
        elif st == 1:  # preamble
            if not level:
                if self._classify(self.last_high) == 1 and c == 1:
                    self.pre += 1
                elif c == 0:
                    if self.pre >= self._MIN_PRE:
                        self.bit_count = 0
                        self.buf = [0] * 16
                        self.step = 2
                    else:
                        self._reset()
                else:
                    if (self.pre >= self._MIN_PRE and
                            self._classify(self.last_high) == 2 and c == 2):
                        self.bit_count = 0
                        self.buf = [0] * 16
                        self.buf[0] |= 0x01
                        self.bit_count = 1
                        self.step = 2
                    else:
                        self._reset()
            else:
                self.last_high = duration
        elif st == 2:  # data
            if level:
                self.last_high = duration
            else:
                hc = self._classify(self.last_high)
                if hc == 1 and c == 1:
                    if self.bit_count < self._FRAME_BITS:
                        self.bit_count += 1
                elif hc == 2 and c == 2:
                    if self.bit_count < self._FRAME_BITS:
                        bi = self.bit_count // 8
                        bit_i = self.bit_count % 8
                        if bi < 16:
                            self.buf[bi] |= 1 << bit_i
                        self.bit_count += 1
                else:
                    if self.bit_count >= self._MIN_BITS:
                        self._validate_emit()
                    self._reset()
                    return
                if self.bit_count >= self._FRAME_BITS:
                    self._validate_emit()
                    self._reset()


# =====================================================================
# HONDA V8 (honda_v8.c) -- KeeLoq inverted PWM, base 400us, 66b LSB-first.
# Validated by KeeLoq decrypt (528 rounds) + discriminant + rolling window.
# =====================================================================
_HV8_NLF = 0x3A5C742E
_HV8_ROUNDS = 528
_HV8_KEYS = [
    (0xA8F5DFFC, 0x8DAA5CDB), (0xABBA94D4, 0x10C1F940), (0xDCF40FCD, 0x460114B7),
    (0x56EB02D8, 0xE9B977B4), (0xA8507BA5, 0x3F26FA21), (0x6857C1C5, 0xA4C5C542),
    (0x54E900DA, 0xEBBB753E), (0x5683FC9B, 0x6A67C523), (0x53EE07DD, 0xECBC7263),
    (0x5D973077, 0xE1CA6024), (0x52EF06DC, 0xEDBD737C), (0x36714027, 0x6011AC25),
    (0x51EC05DF, 0xEEBE7059), (0x1F4DAA96, 0x9BB87A26), (0x50ED04DE, 0xEFBF713C),
    (0xDC8DC8EA, 0xF39FD627), (0x30850A82, 0x610A14EB), (0xD5204919, 0x1719FF41),
    (0x558EA3B0, 0x779F14DA), (0x54454441, 0x47454DE2), (0x40802014, 0x0514F348),
]  # (low, high)
_HV8_PROBE = [14, 1, 0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15, 16, 17, 18, 19, 20]


def _hv8_nlf(x):
    idx = (((x >> 1) & 1) | (((x >> 9) & 1) << 1) | (((x >> 20) & 1) << 2) |
           (((x >> 26) & 1) << 3) | (((x >> 31) & 1) << 4))
    return (_HV8_NLF >> idx) & 1


def _hv8_decrypt(ct, key):
    x = ct & 0xFFFFFFFF
    for i in range(_HV8_ROUNDS):
        kb = (key >> ((15 - i) & 0x3F)) & 1
        nlf = _hv8_nlf(x)
        msb = x & 1
        b15 = (x >> 15) & 1
        fb = msb ^ nlf ^ b15 ^ kb
        x = (x >> 1) | (fb << 31)
    return x & 0xFFFFFFFF


class HondaV8(ProtoDecoder):
    NAME = "Honda V8"
    _TE_BASE, _TE_DELTA, _SYNC_MIN = 400, 150, 2500
    _FRAME_BITS, _ROLL_WIN = 66, 128

    def __init__(self):
        super().__init__()
        self.step = 0
        self.data = 0
        self.count = 0
        self.last_rc = None

    def _find_key(self, enc, serial, btn):
        exp_disc = (serial >> 20) & 0xFF
        for i in _HV8_PROBE:
            lo, hi = _HV8_KEYS[i]
            k = (hi << 32) | lo
            dec = _hv8_decrypt(enc, k)
            if (dec & 0x0000000F) != btn:
                continue
            disc = (dec & 0x00000FF0) >> 4
            if disc != exp_disc:
                continue
            rc = (dec & 0xFFFF0000) >> 16
            if rc == 0 and disc == 0:
                continue
            return i, dec, rc
        return None

    def _process(self):
        # reverse 64 bits of decode_data
        raw = 0
        tmp = self.data
        for _ in range(64):
            raw = (raw << 1) | (tmp & 1)
            tmp >>= 1
        enc = raw & 0xFFFFFFFF
        serial = (raw >> 32) & 0x0FFFFFFF
        btn = (raw >> 60) & 0x0F
        if btn not in _HONDA_KEELOQ_BTN:
            return
        if serial == 0 or serial == 0x0FFFFFFF:
            return
        found = self._find_key(enc, serial, btn)
        if found is None:
            return
        ki, dec, rc = found
        if self.last_rc is not None:
            d = (rc - self.last_rc) & 0xFFFF
            if d == 0 or d > self._ROLL_WIN:
                return
        self.last_rc = rc
        self.emit({"serial": serial, "btn": btn,
                   "btn_name": _HONDA_KEELOQ_BTN.get(btn, "0x%X" % btn),
                   "cnt": rc, "key_idx": ki, "enc": enc, "crc_ok": True,
                   "note": "KeeLoq key[%d] match" % ki})

    def feed(self, level, duration):
        st = self.step
        if st == 0:
            if (not level) and duration >= self._SYNC_MIN:
                self.data = 0
                self.count = 0
                self.step = 1
        elif st == 1:
            if not level:
                if duration >= self._SYNC_MIN:
                    if self.count == self._FRAME_BITS or self.count > 60:
                        self._process()
                    self.data = 0
                    self.count = 0
                    return
                if duration + self._TE_DELTA >= self._TE_BASE and duration <= self._TE_BASE + self._TE_DELTA:
                    bit_val = 0
                else:
                    bit_val = 1
                if self.count < 64:
                    self.data >>= 1
                    if bit_val:
                        self.data |= (1 << 63)
                self.count += 1
                if self.count >= self._FRAME_BITS:
                    self._process()
                    self.data = 0
                    self.count = 0
                    self.step = 0


# =====================================================================
# HONDA V9 (honda_v9.c) -- FSK Manchester (OOK-captured) te 63/126 d25,
# 112b MSB-first, XOR checksum bytes[0..7] vs byte[8] (dist<=1 tolerant).
# =====================================================================
class HondaV9(ProtoDecoder):
    NAME = "Honda V9"
    _TS, _TL, _TD = 63, 126, 25
    _MIN_PRE, _PRE_CYC, _FRAME_BITS, _MIN_BITS = 20, 55, 112, 64
    _FRAME_BYTES = 14

    def __init__(self):
        super().__init__()
        self.hb = []

    def _classify(self, dur):
        if dur + self._TD >= self._TS and dur <= self._TS + self._TD:
            return 1
        if dur + self._TD >= self._TL and dur <= self._TL + self._TD:
            return 2
        return 0

    def _man_decode(self, invert):
        hb = self.hb
        n = len(hb)
        decoded = [0] * (self._FRAME_BYTES + 2)
        alt_end = 1
        while alt_end < n and hb[alt_end] != hb[alt_end - 1]:
            alt_end += 1
        if alt_end == n or alt_end < self._MIN_PRE:
            return None
        if alt_end >= self._PRE_CYC * 2:
            i = self._PRE_CYC * 2
        else:
            i = alt_end
            while i + 1 < n and hb[i] == hb[i + 1]:
                i += 1
        max_bits = self._FRAME_BITS
        bit_count = 0
        while i + 1 < n and bit_count < max_bits:
            h0, h1 = hb[i], hb[i + 1]
            if h0 == h1:
                break
            if not invert:
                bit_val = 1 if (h0 == 0 and h1 == 1) else 0
            else:
                bit_val = 1 if (h0 == 1 and h1 == 0) else 0
            if bit_val:
                decoded[bit_count // 8] |= 1 << (7 - (bit_count % 8))
            bit_count += 1
            i += 2
        if bit_count < self._MIN_BITS:
            return None
        return decoded, bit_count

    def _try_polarity(self, invert):
        r = self._man_decode(invert)
        if r is None:
            return False
        decoded, bit_count = r
        if bit_count < self._FRAME_BITS:
            return False
        hdr = (decoded[0] >> 4) & 0x0F
        button = decoded[0] & 0x0F
        serial = (decoded[1] << 12) | (decoded[2] << 4) | ((decoded[3] >> 4) & 0x0F)
        flags = decoded[3] & 0x0F
        fingerprint = (decoded[4] << 24) | (decoded[5] << 16) | (decoded[6] << 8) | decoded[7]
        checksum = decoded[8]
        counter = (decoded[10] << 8) | decoded[11]
        xchk = 0
        for i in range(8):
            xchk ^= decoded[i]
        dist = bin(xchk ^ checksum).count("1")
        if button > 0x9:
            return False
        if serial == 0 or serial == 0x000FFFFF:
            return False
        if fingerprint == 0 or fingerprint == 0xFFFFFFFF:
            return False
        if xchk != checksum and dist > 1:
            return False
        self.emit({"serial": serial, "btn": button,
                   "btn_name": _HONDA_KEELOQ_BTN.get(button, "0x%X" % button),
                   "cnt": counter, "hdr": hdr, "flags": flags,
                   "fingerprint": fingerprint, "crc": checksum,
                   "crc_ok": (xchk == checksum),
                   "pol": "INV" if invert else "NOR"})
        return True

    def _try_decode(self):
        if len(self.hb) < (self._MIN_PRE + 16):
            return
        if self._try_polarity(False):
            return
        self._try_polarity(True)

    def feed(self, level, duration):
        lvl = 1 if level else 0
        dc = self._classify(duration)
        if dc == 1:
            if len(self.hb) < 512:
                self.hb.append(lvl)
        elif dc == 2:
            if len(self.hb) + 2 <= 512:
                self.hb.append(lvl)
                self.hb.append(lvl)
        else:
            if len(self.hb) >= (self._MIN_PRE + 16):
                self._try_decode()
            self.hb = []


# =====================================================================
# HONDA V10 (honda_v10.c) -- V2H4 native FSK Manchester te 104/208 d35,
# 96b MSB-first.  No CRC: gated on flags==0x7 + known button + serial valid.
# =====================================================================
_HV10_BTN = {0xF: "Lock", 0xE: "Unlock"}


class HondaV10(ProtoDecoder):
    NAME = "Honda V10"
    _TS, _TL, _TD = 104, 208, 35
    _MIN_PRE, _FRAME_BITS, _MIN_BITS = 20, 96, 88
    _FRAME_BYTES = 12

    def __init__(self):
        super().__init__()
        self.hb = []

    def _classify(self, dur):
        if dur + self._TD >= self._TS and dur <= self._TS + self._TD:
            return 1
        if dur + self._TD >= self._TL and dur <= self._TL + self._TD:
            return 2
        return 0

    def _man_decode(self, start_ofs, invert):
        hb = self.hb
        n = len(hb)
        decoded = [0] * (self._FRAME_BYTES + 1)
        if start_ofs + 1 >= n:
            return None, start_ofs
        alt_start = start_ofs
        while alt_start + 1 < n:
            alt_end = alt_start + 1
            while alt_end < n and hb[alt_end] != hb[alt_end - 1]:
                alt_end += 1
            run_len = alt_end - alt_start
            if run_len >= self._MIN_PRE and alt_end < n:
                i = alt_end
                while i + 1 < n and hb[i] == hb[i + 1]:
                    i += 1
                max_bits = self._FRAME_BITS
                bit_count = 0
                decoded = [0] * (self._FRAME_BYTES + 1)
                while i + 1 < n and bit_count < max_bits:
                    h0, h1 = hb[i], hb[i + 1]
                    if h0 == h1:
                        break
                    if not invert:
                        bit_val = 1 if (h0 == 0 and h1 == 1) else 0
                    else:
                        bit_val = 1 if (h0 == 1 and h1 == 0) else 0
                    if bit_val:
                        decoded[bit_count // 8] |= 1 << (7 - (bit_count % 8))
                    bit_count += 1
                    i += 2
                if bit_count >= self._MIN_BITS:
                    return (decoded, bit_count), i
                return None, i
            alt_start = alt_end
        return None, n

    def _try_polarity_at(self, invert, start_ofs):
        r, consumed = self._man_decode(start_ofs, invert)
        if r is None:
            return False, len(self.hb)
        decoded, bit_count = r
        if bit_count < self._MIN_BITS:
            return False, consumed
        serial = (decoded[0] << 16) | (decoded[1] << 8) | decoded[2]
        button = (decoded[3] >> 4) & 0x0F
        flags = decoded[3] & 0x0F
        counter = 0x00FFFFFF ^ ((decoded[4] << 16) | (decoded[5] << 8) | decoded[6])
        status = decoded[7]
        tail = (decoded[8] << 24) | (decoded[9] << 16) | (decoded[10] << 8) | decoded[11]
        if button not in (0xF, 0xE):
            return False, consumed
        if serial == 0 or serial == 0x00FFFFFF:
            return False, consumed
        if flags != 0x7:
            return False, consumed
        self.emit({"serial": serial, "btn": button,
                   "btn_name": _HV10_BTN.get(button, "0x%X" % button),
                   "cnt": counter, "status": status, "tail": tail,
                   "typ": "A" if (status & 0x80) else "B",
                   "pol": "INV" if invert else "NOR", "crc_ok": None,
                   # The 32-bit tail is a per-counter encrypted authenticator
                   # (verified: 1 distinct pseudo-random tail per counter value).
                   # Counter is plaintext/visible but NOT sufficient to clone --
                   # the car checks the tail, which needs the fob secret.
                   "auth_encrypted": True,
                   "note": "flags==0x7 gated (no CRC); tail=encrypted MAC -> replay-only"})
        return True, consumed

    def _try_decode(self):
        n = len(self.hb)
        if n < (self._MIN_PRE + 16):
            return
        ofs = 0
        while ofs + self._MIN_PRE + 16 <= n:
            ok_nor, nxt_nor = self._try_polarity_at(False, ofs)
            ok_inv, nxt_inv = (False, n)
            if not ok_nor:
                ok_inv, nxt_inv = self._try_polarity_at(True, ofs)
            if ok_nor or ok_inv:
                nxt = nxt_nor if ok_nor else nxt_inv
                ofs = nxt if nxt > ofs else ofs + 1
                continue
            skip = ofs + 1
            while skip < n and self.hb[skip] != self.hb[skip - 1]:
                skip += 1
            ofs = skip if skip > ofs else ofs + 1

    def feed(self, level, duration):
        lvl = 1 if level else 0
        dc = self._classify(duration)
        if dc == 1:
            if len(self.hb) < 2048:
                self.hb.append(lvl)
        elif dc == 2:
            if len(self.hb) + 2 <= 2048:
                self.hb.append(lvl)
                self.hb.append(lvl)
        else:
            if len(self.hb) >= (self._MIN_PRE + 16):
                self._try_decode()
            self.hb = []


# =====================================================================
# HONDA STATIC (honda_static.c) -- FIXED / replayable code.
# Symbols: short [28..98]->1 sym, long [61..191]->2 syms, else gap->parse.
# Manchester-pack 64b, fields: btn(4) serial(28) counter(24) xor-checksum(8).
# The 8-bit XOR checksum is a genuine integrity gate -> crc_ok=True on pass.
# =====================================================================
_HSTATIC_BTN = ["Lock", "Unlock", "Unknown", "Trunk", "Remote Start",
                "Unknown", "Unknown", "Panic", "Lock x2"]


class HondaStatic(ProtoDecoder):
    NAME = "Honda Static"
    _BITS = 64
    _MIN_SYM = 36
    _SHORT_BASE, _SHORT_SPAN = 28, 70
    _LONG_BASE, _LONG_SPAN = 61, 130
    _PRE_MAX_TRANS = 19
    _SYM_CAP = 512

    def __init__(self):
        super().__init__()
        self.symbols = []

    @staticmethod
    def _get_bits(data, start, count):
        value = 0
        for i in range(count):
            bit_index = start + i
            byte = data[bit_index >> 3]
            shift = (~bit_index) & 7
            value = (value << 1) | ((byte >> shift) & 1)
        return value

    @staticmethod
    def _valid_button(button):
        if button > 9:
            return False
        return ((0x336 >> button) & 1) != 0

    @staticmethod
    def _valid_serial(serial):
        return serial != 0 and serial != 0x0FFFFFFF

    def _pack64(self, start_pos, inverted):
        sym = self.symbols
        n = len(sym)
        packet = [0] * 9
        pos = start_pos
        bit_count = 0
        while pos + 1 < n:
            if bit_count >= self._BITS:
                break
            a = sym[pos]
            b = sym[pos + 1]
            if a == b:
                pos += 1
                continue
            if inverted:
                bit = (a == 0 and b == 1)
            else:
                bit = (a == 1 and b == 0)
            if bit:
                packet[bit_count >> 3] |= 1 << ((~bit_count) & 7)
            bit_count += 1
            pos += 2
        return packet, bit_count

    def _validate_forward(self, packet):
        button = self._get_bits(packet, 0, 4)
        serial = self._get_bits(packet, 4, 28)
        counter = self._get_bits(packet, 32, 24)
        checksum = self._get_bits(packet, 56, 8)
        calc = 0
        for i in range(7):
            calc ^= packet[i]
        if checksum != calc:
            return None
        if not self._valid_button(button):
            return None
        if not self._valid_serial(serial):
            return None
        return button, serial, counter, checksum

    def _validate_reverse(self, packet):
        reversed_bytes = [rev8(packet[i]) for i in range(9)]
        return self._validate_forward(reversed_bytes)

    def _parse(self, inverted):
        sym = self.symbols
        n = len(sym)
        index = 1
        transitions = 0
        while index < n:
            if sym[index] != sym[index - 1]:
                transitions += 1
            else:
                if transitions > self._PRE_MAX_TRANS:
                    break
                transitions = 0
            index += 1
        if index >= n:
            return False
        while index + 1 < n and sym[index] == sym[index + 1]:
            index += 1
        data_start = index
        packet, bit_count = self._pack64(data_start, inverted)
        if bit_count < self._BITS:
            return False
        fields = self._validate_forward(packet)
        if fields is not None:
            self._commit(fields)
            return True
        if inverted:
            return False
        fields = self._validate_reverse(packet)
        if fields is not None:
            self._commit(fields)
            return True
        return False

    def _commit(self, fields):
        button, serial, counter, checksum = fields
        name = _HSTATIC_BTN[button - 1] if 1 <= button <= len(_HSTATIC_BTN) else "Unknown"
        self.emit({"serial": serial, "btn": button, "btn_name": name,
                   "cnt": counter, "crc": checksum, "crc_ok": True,
                   "fixed": True, "note": "fixed/replayable code, 8-bit XOR checksum"})

    def feed(self, level, duration):
        s = 1 if level else 0
        if duration >= self._SHORT_BASE and (duration - self._SHORT_BASE) <= self._SHORT_SPAN:
            if len(self.symbols) < self._SYM_CAP:
                self.symbols.append(s)
            return
        if duration >= self._LONG_BASE and (duration - self._LONG_BASE) <= self._LONG_SPAN:
            if len(self.symbols) + 2 <= self._SYM_CAP:
                self.symbols.append(s)
                self.symbols.append(s)
            return
        if len(self.symbols) >= self._MIN_SYM:
            if not self._parse(True):
                self._parse(False)
        self.symbols = []


ALL = [HondaV2V3, HondaV4, HondaV5, HondaV6, HondaV7, HondaV8,
       HondaV9, HondaV10, HondaStatic]
