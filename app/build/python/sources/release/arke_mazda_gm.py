#!/usr/bin/env python3
r"""
arke_mazda_gm.py -- ported Flipper Sub-GHz automotive RAW demodulators for the
Mazda and General-Motors families, built on arke_core. Each class is a faithful
port of the firmware feed()/process state machine in
C:\Users\stone\momentum-tembed\lib\subghz\protocols\ :

  MazdaV0        <- mazda_v0.c        (Manchester 250/500, 64b, sum-checksum gate)
  MazdaSiemens   <- mazda_siemens.c   (pair-coded 250/500, 64b, sum-checksum gate)
  MazdaV1        <- mazda_v1.c        (half-bit Manchester 505/1010, 71b, structural)
  MazdaV2        <- mazda_v2.c        (half-bit Manchester 500/1000, EC+63b, structural)
  GmRke          <- gm_rke.c          (OOK-PPM 300/500, 112b, double-checksum gate)
  ChevroletV0    <- chevrolet_v0.c    (Manchester 120/240, 96b, structural btn gate)

DECODER path only (feed + parse + validation). ENABLE_EMULATE_FEATURE ignored.

crc_ok semantics for the report:
  True  -> the frame's own checksum/CRC validated (mazda_v0, mazda_siemens, gm_rke)
  None  -> no in-frame CRC; "structural" gate only (mazda_v1/v2, chevrolet_v0).
           Confidence = a single stable serial across all presses in the file.
"""
from arke_core import (Const, ProtoDecoder, MASK64, diff, parity,
                       u64_to_bytes_be, bytes_to_u64_be,
                       Manchester, manchester_event, M_RESET_EV)


# ============================================================ MAZDA V0
# Manchester, ts=250 tl=500. get_event tolerance = te_delta(100)+20 = 120.
# Preamble = >=16 Manchester ones then the 0xD7 sync byte breaks the run; then
# 64 Manchester data bits. generic.data = ~decode_data. Gate = sum checksum.
_MZ0_BTN = {0x01: "Lock", 0x02: "Unlock", 0x04: "Trunk", 0x08: "Remote"}


class MazdaV0(ProtoDecoder):
    NAME = "Mazda V0"
    CONST = Const(250, 500, 120, 64)
    SYNC = 0xD7
    PREAMBLE_ONES = 16

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self.preamble_count = 0
        self.preamble_pattern = 0

    def reset(self):
        super().reset()
        self.man.reset()
        self.preamble_count = 0
        self.preamble_pattern = 0

    @staticmethod
    def _decode_key(data64):
        b = u64_to_bytes_be(data64)
        par = parity(b[7], 8) != 0
        limit = 6 if par else 5
        mask = b[limit]
        for i in range(limit):
            b[i] ^= mask
        if not par:
            b[6] ^= mask
        counter_lo = (b[5] & 0x55) | (b[6] & 0xAA)
        counter_mid = (b[6] & 0x55) | (b[5] & 0xAA)
        serial = (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]
        btn = (b[4] >> 4) & 0x0F
        cnt = ((b[4] & 0x0F) << 16) | (counter_mid << 8) | counter_lo
        return serial, btn, cnt

    @staticmethod
    def _checksum(serial, btn, counter):
        counter &= 0xFFFFF
        return (((serial >> 24) & 0xFF) + ((serial >> 16) & 0xFF) +
                ((serial >> 8) & 0xFF) + (serial & 0xFF) +
                ((counter >> 8) & 0xFF) + (counter & 0xFF) +
                ((((counter >> 16) & 0x0F) | ((btn & 0x0F) << 4)) & 0xFF)) & 0xFF

    def feed(self, level, duration):
        d = self.d
        c = self.CONST
        st = d.parser_step
        if st == 0:  # Reset
            if level and diff(duration, c.te_short) < c.te_delta:
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 5
                self.man.reset()  # ManchesterStateMid1
                self.preamble_count = 0
                self.preamble_pattern = 0
        elif st == 5:  # Preamble
            ev = manchester_event(duration, level, c)
            if ev == M_RESET_EV:
                d.parser_step = 0
                return
            bit = self.man.advance(ev)
            if bit is not None:
                data = 1 if bit else 0
                self.preamble_pattern = ((self.preamble_pattern << 1) | data) & 0xFF
                do_check = True
                if data:
                    self.preamble_count += 1
                elif self.preamble_count <= self.PREAMBLE_ONES - 1:
                    self.preamble_count = 0
                    self.preamble_pattern = 0
                    do_check = False
                if (do_check and self.preamble_pattern == self.SYNC
                        and self.preamble_count > self.PREAMBLE_ONES - 1):
                    d.decode_data = 0
                    d.decode_count_bit = 0
                    d.parser_step = 6
        elif st == 6:  # Data
            ev = manchester_event(duration, level, c)
            if ev == M_RESET_EV:
                d.parser_step = 0
                return
            bit = self.man.advance(ev)
            if bit is not None:
                d.add_bit(1 if bit else 0)
                if d.decode_count_bit == c.min_bits:
                    data = (~d.decode_data) & MASK64
                    serial, btn, cnt = self._decode_key(data)
                    crc_raw = data & 0xFF
                    crc_calc = self._checksum(serial, btn, cnt)
                    if crc_calc == crc_raw:
                        self.emit({"data": data, "serial": serial, "btn": btn,
                                   "btn_name": _MZ0_BTN.get(btn, "0x%X" % btn),
                                   "cnt": cnt & 0xFFFFF, "crc": crc_raw,
                                   "crc_ok": True})
                    d.decode_data = 0
                    d.decode_count_bit = 0
                    self.preamble_count = 0
                    self.preamble_pattern = 0
                    self.man.reset()
                    d.te_last = 0
                    d.parser_step = 0


# ============================================================ MAZDA SIEMENS
# Pair-coded (level-agnostic), ts=250 tl=500 delta=100. Preamble >=13 short
# pairs then a short->long transition starts data. Pairs -> state bits, buffered
# inverted. 8-byte payload deobfuscated; gate = additive checksum == data[7].
_MZS_BTN = {0x01: "Lock", 0x02: "Unlock", 0x04: "Trunk", 0x08: "Remote",
            0x11: "Lock", 0x22: "Unlock"}


class MazdaSiemens(ProtoDecoder):
    NAME = "Mazda Siemens"
    CONST = Const(250, 500, 100, 64)
    PREAMBLE_MIN = 13
    COMPLETION_MIN = 80
    COMPLETION_MAX = 105
    BUFFER_SIZE = 14

    def __init__(self):
        super().__init__()
        self.te_last = 0
        self.preamble_count = 0
        self.bit_counter = 0
        self.prev_state = 0
        self.buf = bytearray(self.BUFFER_SIZE)

    def reset(self):
        super().reset()
        self.te_last = 0
        self.preamble_count = 0
        self.bit_counter = 0
        self.prev_state = 0
        self.buf = bytearray(self.BUFFER_SIZE)

    def _is_short(self, dur):
        return diff(dur, self.CONST.te_short) < self.CONST.te_delta

    def _is_long(self, dur):
        return diff(dur, self.CONST.te_long) < self.CONST.te_delta

    def _collect_bit(self, state_bit):
        bi = self.bit_counter >> 3
        if bi < self.BUFFER_SIZE:
            v = (self.buf[bi] << 1) & 0xFF
            if state_bit == 0:
                v |= 1
            self.buf[bi] = v
        self.bit_counter += 1

    def _process_pair(self, first, second):
        fs = self._is_short(first)
        fl = self._is_long(first)
        ss = self._is_short(second)
        sl = self._is_long(second)
        if fl and ss:
            self._collect_bit(0)
            self._collect_bit(1)
            self.prev_state = 1
            return True
        if fs and sl:
            self._collect_bit(1)
            self.prev_state = 0
            return True
        if fs and ss:
            self._collect_bit(self.prev_state)
            return True
        if fl and sl:
            self._collect_bit(0)
            self._collect_bit(1)
            self.prev_state = 0
            return True
        return False

    @staticmethod
    def _byte_parity(v):
        v ^= v >> 4
        v ^= v >> 2
        v ^= v >> 1
        return v & 1

    def _deobfuscate(self, data):
        par = self._byte_parity(data[7])
        if par:
            mask = data[6]
            for i in range(6):
                data[i] ^= mask
        else:
            mask = data[5]
            for i in range(5):
                data[i] ^= mask
            data[6] ^= mask
        old5, old6 = data[5], data[6]
        data[5] = (old5 & 0xAA) | (old6 & 0x55)
        data[6] = (old5 & 0x55) | (old6 & 0xAA)

    def _check_completion(self):
        if self.bit_counter < self.COMPLETION_MIN or self.bit_counter > self.COMPLETION_MAX:
            return
        data = bytearray(self.buf[i + 1] for i in range(8))
        self._deobfuscate(data)
        checksum = sum(data[i] for i in range(7)) & 0xFF
        if checksum != data[7]:
            return
        packed = bytes_to_u64_be(list(data))
        serial = (packed >> 32) & 0xFFFFFFFF
        btn = (packed >> 24) & 0xFF
        cnt = (packed >> 8) & 0xFFFF
        self.emit({"data": packed, "serial": serial, "btn": btn,
                   "btn_name": _MZS_BTN.get(btn, "0x%X" % btn),
                   "cnt": cnt, "crc": data[7], "crc_ok": True})

    def feed(self, level, duration):
        d = self.d
        st = d.parser_step
        if st == 0:  # Reset
            if self._is_short(duration):
                self.te_last = duration
                self.preamble_count = 0
                d.parser_step = 2
        elif st == 1:  # PreambleSave
            self.te_last = duration
            d.parser_step = 2
        elif st == 2:  # PreambleCheck
            if self._is_short(self.te_last) and self._is_short(duration):
                self.preamble_count += 1
                d.parser_step = 1
            elif (self._is_short(self.te_last) and self._is_long(duration)
                  and self.preamble_count >= self.PREAMBLE_MIN):
                self.buf = bytearray(self.BUFFER_SIZE)
                self.bit_counter = 0
                self._collect_bit(1)
                self.prev_state = 0
                d.parser_step = 3
            else:
                d.parser_step = 0
        elif st == 3:  # DataSave
            self.te_last = duration
            d.parser_step = 4
        elif st == 4:  # DataCheck
            if self._process_pair(self.te_last, duration):
                d.parser_step = 3
            else:
                self._check_completion()
                d.parser_step = 0


# ============================================================ MAZDA V1
# Half-bit Manchester, ts=505 tl=1010 delta=200 (<=). 71-bit frames sent twice
# back-to-back. Sync 0xEC, known buttons, bit70=1 marker, v1/v3 format markers.
# No in-frame CRC -> structural. Serial is a 6-bit per-remote constant.
_MZ1_SYNC = (1, 1, 1, 0, 1, 1, 0, 0)  # EC
_MZ1_BTN = {0x06: "Lock", 0x0A: "Unlock", 0x0E: "Trunk", 0x26: "Btn5/Trunk"}
_MZ1_BITS = 71


class MazdaV1(ProtoDecoder):
    NAME = "Mazda V1"
    CONST = Const(505, 1010, 200, _MZ1_BITS)
    CAP = 400

    def __init__(self):
        super().__init__()
        self.all_bits = []
        self.half_bit = False
        self.half_bit_level = False
        self.last_pos = -32767

    def reset(self):
        super().reset()
        self.all_bits = []
        self.half_bit = False
        self.last_pos = -32767

    def _diff_s(self, dur):
        return diff(dur, self.CONST.te_short) <= self.CONST.te_delta

    def _diff_l(self, dur):
        return diff(dur, self.CONST.te_long) <= self.CONST.te_delta

    @staticmethod
    def _extract_cnt(data):
        fb53 = (data >> (63 - 53)) & 1
        fb52 = (data >> (63 - 52)) & 1
        fb51 = (data >> (63 - 51)) & 1
        fb50 = (data >> (63 - 50)) & 1
        fb49 = (data >> (63 - 49)) & 1
        b4 = fb53 ^ fb49
        return (b4 << 4) | (fb50 << 3) | (fb51 << 2) | (fb52 << 1) | fb53

    def _try_emit_at(self, sync_pos, polarity):
        ab = self.all_bits
        n = len(ab)
        if sync_pos < 0 or sync_pos + _MZ1_BITS > n:
            return False
        for j in range(8):
            b = ab[sync_pos + j] ^ polarity
            if b != _MZ1_SYNC[j]:
                return False
        btn = 0
        for j in range(8):
            b = ab[sync_pos + 8 + j] ^ polarity
            btn = (btn << 1) | b
        if (btn & 0x7F) not in (0x06, 0x0A, 0x0E, 0x26):
            return False
        bit70 = ab[sync_pos + 70] ^ polarity
        if bit70 != 1:
            return False
        b18 = ab[sync_pos + 18] ^ polarity
        b19 = ab[sync_pos + 19] ^ polarity
        b20 = ab[sync_pos + 20] ^ polarity
        b21 = ab[sync_pos + 21] ^ polarity
        b37 = ab[sync_pos + 37] ^ polarity
        v1_marker = (b18 == 1 and b19 == 0 and b20 == 0 and b21 == 0 and b37 == 0)
        v3_marker = not (b18 == 1 and b19 == 0 and b20 == 0 and b21 == 0)
        if not v1_marker and not v3_marker:
            return False
        if polarity == 1:
            for k in range(sync_pos, sync_pos + _MZ1_BITS):
                ab[k] ^= 1
        data = 0
        for i in range(64):
            data = (data << 1) | ab[sync_pos + i]
        btn = (data >> 48) & 0xFF
        b2 = (data >> 40) & 0xFF
        serial = (b2 >> 2) & 0x3F
        cnt = self._extract_cnt(data)
        self.emit({"data": data, "serial": serial, "btn": btn,
                   "btn_name": _MZ1_BTN.get(btn & 0x7F, "0x%X" % btn),
                   "cnt": cnt, "crc_ok": None, "note": "structural (cnt=low5)"})
        return True

    def _streaming_check(self):
        n = len(self.all_bits)
        if n < _MZ1_BITS:
            return
        sync_pos = n - _MZ1_BITS
        if self.last_pos >= 0 and sync_pos < self.last_pos + 2 * _MZ1_BITS:
            return
        for polarity in (0, 1):
            if self._try_emit_at(sync_pos, polarity):
                self.last_pos = sync_pos
                return

    def _process_frame(self):
        n = len(self.all_bits)
        if n < _MZ1_BITS:
            return
        search_end = n - _MZ1_BITS
        for i in range(search_end + 1):
            if self.last_pos >= 0 and i < self.last_pos + 2 * _MZ1_BITS:
                continue
            for polarity in (0, 1):
                if self._try_emit_at(i, polarity):
                    self.last_pos = i
                    break

    def feed(self, level, duration):
        d = self.d
        st = d.parser_step
        if st == 0:  # Reset
            if self._diff_s(duration):
                d.parser_step = 1
                self.all_bits = []
                self.last_pos = -32767
                self.half_bit = True
                self.half_bit_level = level
        elif st == 1:  # Collect
            if len(self.all_bits) >= 380:
                self._process_frame()
                d.parser_step = 0
                return
            is_short = self._diff_s(duration)
            is_long = self._diff_l(duration)
            bit_added = False
            if is_long:
                if len(self.all_bits) < self.CAP:
                    self.all_bits.append(1 if level else 0)
                    bit_added = True
                self.half_bit = False
            elif is_short:
                if self.half_bit:
                    if len(self.all_bits) < self.CAP:
                        self.all_bits.append(1 if level else 0)
                        bit_added = True
                    self.half_bit = False
                else:
                    self.half_bit = True
                    self.half_bit_level = level
            else:
                self._process_frame()
                d.parser_step = 0
            if bit_added:
                self._streaming_check()


# ============================================================ MAZDA V2
# Half-bit Manchester, ts=500 tl=1000 delta=150. Noise filter <250us. EC(8) sync
# then 63 data bits. Structural gate: hop0 top-2 bits == 01, hop3 bit2 == 0,
# known button. Serial = 8-bit per-remote constant. No in-frame CRC.
_MZ2_SYNC = (1, 1, 1, 0, 1, 1, 0, 0)  # EC
_MZ2_BTN = {0x06: "Lock", 0x0A: "Unlock", 0x0E: "Trunk"}
_MZ2_BITS = 63


class MazdaV2(ProtoDecoder):
    NAME = "Mazda V2"
    CONST = Const(500, 1000, 150, _MZ2_BITS)
    CAP = 400

    def __init__(self):
        super().__init__()
        self.all_bits = []
        self.half_bit = False

    def reset(self):
        super().reset()
        self.all_bits = []
        self.half_bit = False

    def _diff_s(self, dur):
        return diff(dur, self.CONST.te_short) <= self.CONST.te_delta

    def _diff_l(self, dur):
        return diff(dur, self.CONST.te_long) <= self.CONST.te_delta

    def _process_frame(self):
        ab = self.all_bits
        n = len(ab)
        if n < (8 + _MZ2_BITS):
            return
        sync_pos = -1
        search_end = n - 8 - _MZ2_BITS
        for i in range(search_end + 1):
            if all(ab[i + j] == _MZ2_SYNC[j] for j in range(8)):
                sync_pos = i
                break
        if sync_pos < 0:
            return
        data_start = sync_pos + 8
        if n - data_start < _MZ2_BITS:
            return
        data = 0
        for i in range(_MZ2_BITS):
            data = (data << 1) | ab[data_start + i]
        data = (data << 1) & MASK64  # pad LSB with 0 to fill 64 bits
        hop0 = (data >> 40) & 0xFF
        if (hop0 & 0xC0) != 0x40:
            return
        hop3 = (data >> 16) & 0xFF
        if (hop3 & 0x04) != 0:
            return
        btn = (data >> 56) & 0xFF
        if (btn & 0x7F) not in (0x06, 0x0A, 0x0E):
            return
        serial = (data >> 48) & 0xFF
        hop2 = (data >> 24) & 0xFF
        cnt_lo = hop3 >> 3
        cnt_hi = hop2 & 0x07
        cnt = (cnt_hi << 5) | cnt_lo
        self.emit({"data": data, "serial": serial, "btn": btn,
                   "btn_name": _MZ2_BTN.get(btn & 0x7F, "0x%X" % btn),
                   "cnt": cnt, "crc_ok": None, "note": "structural"})

    def feed(self, level, duration):
        if duration < 250:
            return
        d = self.d
        st = d.parser_step
        if st == 0:  # Reset
            if self._diff_s(duration):
                d.parser_step = 1
                self.all_bits = []
                self.half_bit = True
        elif st == 1:  # Collect
            if len(self.all_bits) >= 380:
                self._process_frame()
                d.parser_step = 0
                return
            is_short = self._diff_s(duration)
            is_long = self._diff_l(duration)
            if is_long:
                if len(self.all_bits) < self.CAP:
                    self.all_bits.append(1 if level else 0)
                self.half_bit = False
            elif is_short:
                if self.half_bit:
                    if len(self.all_bits) < self.CAP:
                        self.all_bits.append(1 if level else 0)
                    self.half_bit = False
                else:
                    self.half_bit = True
            else:
                self._process_frame()
                d.parser_step = 0


# ============================================================ GM RKE
# OOK-PPM ~314.9 MHz. Fixed short mark; the GAP after it carries the bit
# (short<400us=0, long>=400us=1). Align to last 113 bits, slice 14 bytes.
# Gate = bytes[0]==0xFF AND button-nibble checksum AND full additive checksum.
_GM_BTN = {0x1: "Unlock", 0x2: "Lock", 0x3: "Trunk", 0x4: "Panic"}


class GmRke(ProtoDecoder):
    NAME = "GM RKE"
    CONST = Const(300, 500, 150, 112)
    MARK_MIN, MARK_MAX = 100, 1000
    SPACE_MIN, SPACE_MAX = 120, 900
    BIT_THRESHOLD = 400
    FRAME_BITS = 113
    PAYLOAD_BYTES = 14
    MAX_BITS = 256
    KEEP_BITS = 160

    def __init__(self):
        super().__init__()
        self.bits = []

    def reset(self):
        super().reset()
        self.bits = []

    def _add_bit(self, bit):
        if len(self.bits) >= self.MAX_BITS:
            self.bits = self.bits[len(self.bits) - self.KEEP_BITS:]
        self.bits.append(bit & 1)

    def _process_frame(self):
        n = len(self.bits)
        if n < self.FRAME_BITS:
            return
        off = n - self.FRAME_BITS
        by = []
        for i in range(self.PAYLOAD_BYTES):
            b = 0
            for j in range(8):
                b = (b << 1) | (self.bits[off + i * 8 + j] & 1)
            by.append(b)
        if by[0] != 0xFF:
            return
        btn_cksum = (by[2] >> 4) + (by[2] & 0x0F)
        if btn_cksum == 0 or (btn_cksum & 0x0F) != 0:
            return
        full = sum(by[1:self.PAYLOAD_BYTES])
        if full == 0 or (full & 0xFF) != 0:
            return
        button = by[2] & 0x7
        idv = (by[3] << 24) | (by[4] << 16) | (by[5] << 8) | by[6]
        sequence = (by[7] << 16) | (by[8] << 8) | by[9]
        encrypted = (by[10] << 16) | (by[11] << 8) | by[12]
        self.emit({"serial": idv, "btn": button,
                   "btn_name": _GM_BTN.get(button, "0x%X" % button),
                   "cnt": sequence, "prefix": by[1], "enc": encrypted,
                   "crc": by[13], "crc_ok": True})

    def feed(self, level, duration):
        d = self.d
        st = d.parser_step
        if st == 0:  # Reset
            if level and self.MARK_MIN <= duration <= self.MARK_MAX:
                self.bits = []
                d.parser_step = 1
        elif st == 1:  # Data
            if level:
                if duration < self.MARK_MIN or duration > self.MARK_MAX:
                    self._process_frame()
                    self.bits = []
                    d.parser_step = 0
            else:
                if duration > self.SPACE_MAX:
                    self._process_frame()
                    self.bits = []
                    d.parser_step = 0
                elif duration >= self.SPACE_MIN:
                    self._add_bit(1 if duration >= self.BIT_THRESHOLD else 0)
                else:
                    self._process_frame()
                    self.bits = []
                    d.parser_step = 0


# ============================================================ CHEVROLET V0
# Standard Manchester, ts=120 tl=240 (<=70 tol). Preamble register locks on
# 0xAAAAAAAB, then 96 data bits collected; data bit inverted (IEEE 802.3).
# No plaintext counter (hop fully encrypted). Structural gate: btn in {A,B}
# and non-degenerate payload. Serial = 24-bit per-remote constant.
_CHV_BTN = {0xA: "Open", 0xB: "Close"}


class ChevroletV0(ProtoDecoder):
    NAME = "Chevrolet V0"
    CONST = Const(120, 240, 71, 96)  # <71 == C's <=70
    DATA_BITS = 96
    # Faithful chevrolet_v0.c gate: button nibble must be Open(0xA)/Close(0xB).
    BTN_GATE = frozenset((0xA, 0xB))

    def _gate_ok(self, btn, gdata):
        return btn in self.BTN_GATE

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self.data_header = 0
        self.bit_count = 0
        self.data_count = 0

    def reset(self):
        super().reset()
        self.man.reset()
        self.data_header = 0
        self.bit_count = 0
        self.data_count = 0

    def feed(self, level, duration):
        d = self.d
        c = self.CONST
        ev = manchester_event(duration, level, c)
        st = d.parser_step

        if st == 0:  # Reset
            if ev == M_RESET_EV:
                return
            self.man.reset()
            self.data_header = 0
            self.bit_count = 0
            d.parser_step = 1
            st = 1  # fallthrough

        if st == 1:  # Preamble
            if ev == M_RESET_EV:
                d.parser_step = 0
                return
            bit = self.man.advance(ev)
            if bit is not None:
                data = 0 if bit else 1  # invert (IEEE 802.3)
                self.data_header = ((self.data_header << 1) | data) & 0xFFFFFFFF
                self.bit_count += 1
                if self.bit_count >= 32 and self.data_header == 0xAAAAAAAB:
                    self.data_header = 0
                    d.decode_data = 0
                    self.data_count = 0
                    d.parser_step = 2
        elif st == 2:  # CollectData
            if ev == M_RESET_EV:
                d.parser_step = 0
                return
            bit = self.man.advance(ev)
            if bit is not None:
                data = 0 if bit else 1  # invert
                self.data_header = (((self.data_header << 1) |
                                     ((d.decode_data >> 63) & 1)) & 0xFFFFFFFF)
                d.decode_data = ((d.decode_data << 1) | data) & MASK64
                self.data_count += 1
                if self.data_count >= self.DATA_BITS:
                    gdata = ((self.data_header << 32) |
                             (d.decode_data >> 32)) & MASK64
                    seed = d.decode_data & 0xFFFFFFFF
                    serial = (gdata >> 40) & 0xFFFFFF
                    btn = (gdata >> 36) & 0x0F
                    if gdata == 0 or gdata == MASK64 or not self._gate_ok(btn, gdata):
                        d.parser_step = 0
                        return
                    hop = gdata & 0xFFFFFFFFF  # byte3.lo + bytes4-7 (36 bits)
                    self.emit({"data": gdata, "seed": seed, "serial": serial,
                               "btn": btn, "btn_name": _CHV_BTN.get(btn, "0x%X" % btn),
                               "cnt": 0, "hop": hop, "crc_ok": None,
                               "note": "structural (hop encrypted)"})
                    d.parser_step = 0


# ============================================================ CHEVROLET V0 (GM family)
# Same physical layer / framing as chevrolet_v0.c (Manchester 120/240, exact
# 32-bit 0xAAAAAAAB preamble, 96-bit payload) but generalized to the whole GM
# 434 MHz remote family (Buick / Cadillac / GMC / newer Chevrolet). Those fobs
# carry a per-remote type/toggle nibble (not Open/Close) with the real button
# encrypted in the hop, so the strict btn in {A,B} filter of chevrolet_v0.c
# rejects them. The 32-bit exact preamble + non-degenerate 96-bit payload is a
# strong structural gate on its own; success is confirmed by a single STABLE
# 24-bit serial across every press in a capture (task's chevrolet success rule).
class ChevroletV0Gm(ChevroletV0):
    NAME = "Chevrolet V0 (GM family)"

    def _gate_ok(self, btn, gdata):
        # accept any non-degenerate frame after the exact 0xAAAAAAAB preamble
        return True


ALL = [MazdaV0, MazdaSiemens, MazdaV1, MazdaV2, GmRke, ChevroletV0, ChevroletV0Gm]
