#!/usr/bin/env python3
r"""
arke_ford.py -- Ford Sub-GHz automotive RAW demodulators ported from the Flipper
C decoders (lib/subghz/protocols/ford_v0..v3.c) onto arke_core.

Faithful DECODER-path ports (feed state machine + parse + integrity check). The
encoder / ENABLE_EMULATE_FEATURE paths are intentionally not ported.

Integrity model (honest reporting):
  * FordV0 -- 7-bit GF(2) matrix CRC. emit() is CRC-gated -> crc_ok is real.
  * FordV1 -- CRC16/CCITT (0x1021) over bytes 3..14. emit() is CRC-gated ->
              crc_ok is real. (This protocol is FM/2-FSK.)
  * FordV2 -- NO CRC in the protocol. Validated structurally (16-bit Manchester
              sync 0x7FA7 + button whitelist + counter redundancy). Emitted with
              crc_ok=False, struct_ok=True.
  * FordV3 -- NO CRC. Very weak gate (non-constant, serial!=0). Emitted with
              crc_ok=False, struct_ok=True; treat as UNVERIFIED.

A "verified" Ford decode = a FordV0 or FordV1 frame whose CRC validated.
"""
from arke_core import (Const, ProtoDecoder, is_short, is_long, diff, crc16,
                       MASK64)

# ---------------------------------------------------------------- Manchester
# Faithful port of toolbox/manchester_decoder.c (reset -> Start1, unlike the
# arke_core Manchester helper which resets to Mid1). Kept local so every Ford
# decoder can drive the state machine exactly as the firmware does, including
# the explicit Mid0/Mid1 seeding at sync points.
EV_SHORT_LOW, EV_SHORT_HIGH, EV_LONG_LOW, EV_LONG_HIGH, EV_RESET = 0, 2, 4, 6, 8
ST_START1, ST_MID1, ST_MID0, ST_START0 = 0, 1, 2, 3
_MTAB = (0b00000001, 0b10010001, 0b10011011, 0b11111011)


def m_adv(state, event):
    """Returns (result, new_state, data_bit). data_bit valid only when result."""
    if event == EV_RESET:
        return (False, ST_START1, False)
    ns = (_MTAB[state] >> event) & 0x03
    if ns == state:
        return (False, ST_START1, False)
    if ns == ST_MID0:
        return (True, ns, False)
    if ns == ST_MID1:
        return (True, ns, True)
    return (False, ns, False)


def _byte_parity(b):
    b &= 0xFF
    p = 0
    while b:
        p ^= (b & 1)
        b >>= 1
    return p


# ============================================================ FORD V0
# ts=250 tl=500 delta=100. OOK. 80-bit Manchester payload: first 64 bits ->
# key1 (bit-inverted), next 16 -> key2 (bit-inverted) = [checksum:crc]. The
# frame carries a 7-bit matrix CRC; emit is gated on it.
class FordV0(ProtoDecoder):
    NAME = "Ford V0"
    CONST = Const(250, 500, 100, 64)
    _BTN = {0x01: "Panic", 0x02: "Lock", 0x04: "Unlock", 0x08: "Boot"}
    _CRC_MATRIX = (
        0xDA, 0xB5, 0x55, 0x6A, 0xAA, 0xAA, 0xAA, 0xD5, 0xB6, 0x6C, 0xCC, 0xD9,
        0x99, 0x99, 0x99, 0xB3, 0x71, 0xE3, 0xC3, 0xC7, 0x87, 0x87, 0x87, 0x8F,
        0x0F, 0xE0, 0x3F, 0xC0, 0x7F, 0x80, 0x7F, 0x80, 0x00, 0x1F, 0xFF, 0xC0,
        0x00, 0x7F, 0xFF, 0x80, 0x00, 0x00, 0x00, 0x3F, 0xFF, 0xFF, 0xFF, 0x80,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7F, 0x23, 0x12, 0x94, 0x84,
        0x35, 0xF4, 0x55, 0x84,
    )

    def __init__(self):
        super().__init__()
        self.mstate = ST_START1
        self.data_low = 0
        self.data_high = 0
        self.bit_count = 0
        self.header_count = 0
        self.key1 = 0
        self.key2 = 0

    # -- integrity + descramble --
    def _calc_crc(self, buf):
        crc = 0
        for row in range(8):
            xs = 0
            base = row * 8
            for col in range(8):
                xs ^= self._CRC_MATRIX[base + col] & buf[col + 1]
            if _byte_parity(xs):
                crc |= (1 << row)
        return crc

    def _verify_crc(self, key1, key2):
        buf = [(key1 >> (56 - i * 8)) & 0xFF for i in range(8)]
        buf.append((key2 >> 8) & 0xFF)  # buf[8] = checksum byte
        calc = self._calc_crc(buf)
        recv = ((key2 & 0xFF) ^ 0x80) & 0xFF
        return (calc & 0x7F) == (recv & 0x7F)

    def _decode(self, key1, key2):
        buf = [(key1 >> (56 - i * 8)) & 0xFF for i in range(8)] + [0, 0, 0, 0, 0]
        buf[8] = (key2 >> 8) & 0xFF
        buf[9] = key2 & 0xFF
        tmp = buf[8]
        parity = 0
        parity_any = 1 if tmp != 0 else 0
        while tmp:
            parity ^= (tmp & 1)
            tmp >>= 1
        buf[11] = parity if parity_any else 0
        if buf[11]:
            xor_byte, limit = buf[7], 7
        else:
            xor_byte, limit = buf[6], 6
        for idx in range(1, limit):
            buf[idx] ^= xor_byte
        if buf[11] == 0:
            buf[7] ^= xor_byte
        orig_b7 = buf[7]
        buf[7] = (orig_b7 & 0xAA) | (buf[6] & 0x55)
        mixed = (buf[6] & 0xAA) | (orig_b7 & 0x55)
        buf[12] = mixed
        buf[6] = mixed
        serial_le = buf[1] | (buf[2] << 8) | (buf[3] << 16) | (buf[4] << 24)
        serial = (((serial_le & 0xFF) << 24) | (((serial_le >> 8) & 0xFF) << 16)
                  | (((serial_le >> 16) & 0xFF) << 8) | ((serial_le >> 24) & 0xFF))
        # plain[5] = (btn & 0xF) << 4 | ((cnt >> 16) & 0xF)  [ford_v0.c encoder]:
        # button is the TOP nibble, counter is 20 bits.
        button = (buf[5] >> 4) & 0x0F
        count = ((buf[5] & 0x0F) << 16) | (buf[6] << 8) | buf[7]
        return serial, button, count

    # -- bit register (blocks add_bit twin) --
    def _add_bit(self, bit):
        low = self.data_low & 0xFFFFFFFF
        self.data_low = ((self.data_low << 1) | (1 if bit else 0)) & MASK64
        self.data_high = ((self.data_high << 1) | ((low >> 31) & 1)) & MASK64
        self.bit_count += 1

    def _process(self):
        if self.bit_count == 64:
            combined = ((self.data_high << 32) | self.data_low) & MASK64
            self.key1 = (~combined) & MASK64
            self.data_low = 0
            self.data_high = 0
            return False
        if self.bit_count == 80:
            key2 = (~(self.data_low & 0xFFFF)) & 0xFFFF
            if not self._verify_crc(self.key1, key2):
                return False
            self.key2 = key2
            ser, btn, cnt = self._decode(self.key1, key2)
            self.emit({"data": self.key1, "serial": ser, "btn": btn,
                       "btn_name": self._BTN.get(btn, "0x%X" % btn),
                       "cnt": cnt, "crc": key2 & 0xFF, "crc_ok": True})
            return True
        return False

    def _pp_event(self, dur, level, c):
        # pp_manchester_event convention: level True -> Low event.
        if diff(dur, c.te_short) < c.te_delta:
            return EV_SHORT_LOW if level else EV_SHORT_HIGH
        if diff(dur, c.te_long) < c.te_delta:
            return EV_LONG_LOW if level else EV_LONG_HIGH
        return EV_RESET

    def feed(self, level, duration):
        c = self.CONST
        st = self.d.parser_step
        if st == 0:  # Reset
            if level and diff(duration, c.te_short) < c.te_delta:
                self.data_low = 0
                self.data_high = 0
                self.d.parser_step = 1
                self.d.te_last = duration
                self.header_count = 0
                self.bit_count = 0
                self.mstate = ST_START1
        elif st == 1:  # Preamble (low pulses only)
            if not level:
                if diff(duration, c.te_long) < c.te_delta:
                    self.d.te_last = duration
                    self.d.parser_step = 2
                else:
                    self.d.parser_step = 0
        elif st == 2:  # PreambleCheck (high pulses only)
            if level:
                if diff(duration, c.te_long) < c.te_delta:
                    self.header_count += 1
                    self.d.te_last = duration
                    self.d.parser_step = 1
                elif diff(duration, c.te_short) < c.te_delta:
                    self.d.parser_step = 3
                else:
                    self.d.parser_step = 0
        elif st == 3:  # Gap (~3500us low -> forced start bit 1)
            if (not level) and diff(duration, 3500) < 250:
                self.data_low = 1
                self.data_high = 0
                self.bit_count = 1
                self.d.parser_step = 4
            elif (not level) and duration > 3500 + 250:
                self.d.parser_step = 0
        elif st == 4:  # Data
            ev = self._pp_event(duration, level, c)
            if ev == EV_RESET:
                self.d.parser_step = 0
            else:
                res, self.mstate, bit = m_adv(self.mstate, ev)
                if res:
                    self._add_bit(bit)
                    if self._process():
                        self.data_low = 0
                        self.data_high = 0
                        self.bit_count = 0
                        self.d.parser_step = 0
            self.d.te_last = duration


# ============================================================ FORD V1
# ts=65 tl=130 delta=39. FM. 17-byte / 136-bit Manchester frame. CRC16/0x1021
# over bytes 3..14 (retry bit-inverted). Descramble recovers serial/btn/cnt.
class FordV1(ProtoDecoder):
    NAME = "Ford V1"
    CONST = Const(65, 130, 39, 136)
    DELTA_LONG = 40
    DELTA_DS = 39
    PREAMBLE_MIN = 50
    _BTN = {0: "Sync", 1: "Lock", 2: "Unlock", 4: "Trunk", 8: "Panic"}

    def __init__(self):
        super().__init__()
        self.mstate = ST_START1
        self.preamble_count = 0
        self.sync_idx = 0
        self.sync_cnt = 0
        self.sync_events = [0] * 8
        self.raw = [0] * 18
        self.byte_count = 0
        self.dcount = 0
        self.ddata = 0

    def _reset(self):
        self.d.parser_step = 0
        self.ddata = 0
        self.dcount = 0
        self.byte_count = 0
        self.raw = [0] * 18
        self.preamble_count = 0
        self.sync_idx = 0
        self.sync_cnt = 0
        self.sync_events = [0] * 8
        self.mstate = ST_START1

    # -- descramble helpers (twins of decoders.py _ford_*) --
    @staticmethod
    def _decode_flag(air9, flag):
        b = list(air9)
        if flag:
            x = b[7]
            for i in range(1, 7):
                b[i] ^= x
        else:
            x = b[6]
            for i in range(1, 6):
                b[i] ^= x
            b[7] ^= x
        b6, b7 = b[6], b[7]
        b[6] = (b6 & 0xAA) | (b7 & 0x55)
        b[7] = (b7 & 0xAA) | (b6 & 0x55)
        return b

    @staticmethod
    def _fields_from_plain(p):
        serial = (p[1] << 24) | (p[2] << 16) | (p[3] << 8) | p[0]
        btn = (p[5] >> 4) & 0x0F
        cnt = ((p[5] & 0x0F) << 16) | (p[6] << 8) | p[7]
        return serial, btn, cnt

    def _process(self):
        raw = self.raw
        orig = raw[:17]
        calc = crc16(orig[3:15], 0x1021, 0)
        recv = (orig[15] << 8) | orig[16]
        used = orig
        if recv != calc:
            inv = [(~b) & 0xFF for b in orig]
            calc2 = crc16(inv[3:15], 0x1021, 0)
            recv2 = (inv[15] << 8) | inv[16]
            if recv2 == calc2:
                used, calc, recv = inv, calc2, recv2
            else:
                return False
        # CRC OK from here.
        air9 = used[6:15]
        b0 = self._decode_flag(air9, 0)
        b1 = self._decode_flag(air9, 1)
        if b0[3] == used[5] and b0[4] == used[6]:
            ser, btn, cnt = self._fields_from_plain(b0)
            enc = True
        elif b1[3] == used[5] and b1[4] == used[6]:
            ser, btn, cnt = self._fields_from_plain(b1)
            enc = True
        else:
            ser = (used[3] << 24) | (used[4] << 16) | (used[5] << 8) | used[6]
            btn, cnt, enc = 0, 0, False
        key1 = 0
        for i in range(7):
            key1 = (key1 << 8) | used[i]
        self.emit({"data": key1, "serial": ser, "btn": btn,
                   "btn_name": (self._BTN.get(btn, "0x%X" % btn) if enc
                                else "header-only(enc?)"),
                   "cnt": cnt, "crc": recv, "crc_ok": True,
                   "fields_plain": enc})
        return True

    def _try_last_byte_variants(self):
        if self.byte_count != 16:
            self._reset()
            return
        if ((self.dcount + 0x7A) & 0xFF) > 1:  # accept only 134/135 bits
            self._reset()
            return
        shift = (136 - self.dcount) & 0xFF
        variants = 1 << shift
        saved = self.raw[:16]
        partial = self.ddata & 0xFFFFFFFF
        for variant in range(variants):
            self.raw[:16] = saved
            self.raw[16] = ((partial << shift) | variant) & 0xFF
            if self._process():
                self._reset()
                return
        self._reset()

    def _push_bit(self, bit):
        self.ddata = ((self.ddata << 1) | (1 if bit else 0)) & 0xFFFFFFFF
        self.dcount += 1
        if (self.dcount & 7) == 0:
            bv = self.ddata & 0xFF
            if self.byte_count < 17:
                self.raw[self.byte_count] = bv
                self.byte_count += 1
            self.ddata = 0
            return True
        return False

    def feed(self, level, duration):
        c = self.CONST
        ts, tl = c.te_short, c.te_long
        st = self.d.parser_step
        if st == 0:  # Reset
            if (not level) and diff(duration, tl) < self.DELTA_LONG:
                self.d.parser_step = 1
                self.preamble_count = 1
                self.d.te_last = duration
        elif st == 1:  # Preamble
            if diff(duration, tl) < self.DELTA_LONG:
                self.preamble_count += 1
                self.d.te_last = duration
            elif diff(duration, ts) < self.DELTA_DS:
                if self.preamble_count >= self.PREAMBLE_MIN:
                    self.sync_idx = 0
                    self.sync_cnt = 1
                    self.sync_events[0] = EV_SHORT_HIGH if level else EV_SHORT_LOW
                    self.d.parser_step = 2
                else:
                    self.d.parser_step = 0
            else:
                if self.preamble_count < self.PREAMBLE_MIN:
                    self.d.parser_step = 0
        elif st == 2:  # Sync
            is_sh = False
            if diff(duration, ts) < self.DELTA_DS:
                ev = EV_SHORT_HIGH if level else EV_SHORT_LOW
                is_sh = True
            elif diff(duration, tl) < self.DELTA_DS:
                ev = EV_LONG_HIGH if level else EV_LONG_LOW
            else:
                self.d.parser_step = 1
                return
            self.sync_idx += 1
            if is_sh:
                self.sync_cnt += 1
            if self.sync_idx < 8:
                self.sync_events[self.sync_idx] = ev
            if self.sync_cnt > 2:
                self.ddata = 0
                self.dcount = 0
                self.byte_count = 0
                self.raw = [0] * 18
                self.mstate = ST_START1
                if self.sync_events[0] == EV_SHORT_LOW:
                    self.mstate = ST_MID0
                self.d.parser_step = 3
                for i in range(min(self.sync_idx + 1, 8)):
                    res, self.mstate, bit = m_adv(self.mstate, self.sync_events[i])
                    if res:
                        self._push_bit(bit)
                return
            if self.sync_idx >= 7:
                self.d.parser_step = 1
        elif st == 3:  # Data
            if diff(duration, ts) < self.DELTA_DS:
                ev = EV_SHORT_HIGH if level else EV_SHORT_LOW
            elif diff(duration, tl) < self.DELTA_DS:
                ev = EV_LONG_HIGH if level else EV_LONG_LOW
            else:
                self._try_last_byte_variants()
                return
            res, self.mstate, bit = m_adv(self.mstate, ev)
            if res:
                if self._push_bit(bit) and self.byte_count > 16:
                    self._process()
                    self._reset()
            self.d.te_last = duration


# ============================================================ FORD V2
# ts=200 tl=400 delta=260. FM. 104-bit Manchester, data bits inverted, 16-bit
# sync 0x7FA7. NO CRC -- structural validation only (sync + button whitelist +
# counter redundancy). Reported crc_ok=False, struct_ok=True.
class FordV2(ProtoDecoder):
    NAME = "Ford V2"
    CONST = Const(200, 400, 260, 104)
    SYNC0, SYNC1 = 0x7F, 0xA7
    SYNC16_INV = (~((0x7F << 8) | 0xA7)) & 0xFFFF  # 0x8058
    PREAMBLE_MIN = 64
    INTER_BURST_GAP = 15000
    DATA_BYTES = 13
    _BTN = {0x10: "Lock", 0x11: "Unlock", 0x13: "Trunk", 0x14: "Panic",
            0x15: "RemoteStart"}

    def __init__(self):
        super().__init__()
        self.mstate = ST_START1
        self.preamble_count = 0
        self.raw = [0] * 13
        self.byte_count = 0
        self.sync_shift = 0
        self.sync_bits = 0
        self.ddata = 0
        self.dcount = 0
        self.counter16 = 0
        self.tail31 = 0
        self.structure_ok = False

    def _reset(self):
        self.d.parser_step = 0
        self.ddata = 0
        self.dcount = 0
        self.d.te_last = 0
        self.byte_count = 0
        self.sync_shift = 0
        self.sync_bits = 0
        self.preamble_count = 0
        self.counter16 = 0
        self.tail31 = 0
        self.structure_ok = False
        self.raw = [0] * 13
        self.mstate = ST_START1

    @staticmethod
    def _is_short(d):
        return diff(d, 200) < 260

    @staticmethod
    def _is_long(d):
        return diff(d, 400) < 260

    def _extract(self):
        k = self.raw
        serial = (k[2] << 24) | (k[3] << 16) | (k[4] << 8) | k[5]
        btn = k[6]
        cnt = (((k[7] & 0x7F) << 9) | (k[8] << 1) | (k[9] >> 7)) & 0xFFFF
        tail31 = (((k[9] & 0x7F) << 24) | (k[10] << 16) | (k[11] << 8) | k[12])
        ok = True
        if k[0] != self.SYNC0:
            ok = False
        if k[1] != self.SYNC1:
            ok = False
        if btn not in self._BTN:
            ok = False
        if (k[7] & 0x7F) != ((cnt >> 9) & 0x7F):
            ok = False
        if k[8] != ((cnt >> 1) & 0xFF):
            ok = False
        if ((k[9] >> 7) & 1) != (cnt & 1):
            ok = False
        self.serial = serial
        self.btn = btn
        self.counter16 = cnt
        self.tail31 = tail31
        self.structure_ok = ok

    def _commit(self):
        if self.raw[0] != self.SYNC0 or self.raw[1] != self.SYNC1:
            return False
        self._extract()
        if not self.structure_ok:
            return False
        data = 0
        for i in range(8):
            data = (data << 8) | self.raw[i]
        self.emit({"data": data, "serial": self.serial, "btn": self.btn,
                   "btn_name": self._BTN.get(self.btn, "0x%X" % self.btn),
                   "cnt": self.counter16, "crc": None, "crc_ok": False,
                   "struct_ok": True, "tail31": self.tail31})
        return True

    def _sync_feed(self, event):
        res, self.mstate, bit = m_adv(self.mstate, event)
        if not res:
            return False
        self.sync_shift = ((self.sync_shift << 1) | (1 if bit else 0)) & 0xFFFF
        if self.sync_bits < 16:
            self.sync_bits += 1
        return self.sync_bits >= 16 and self.sync_shift == self.SYNC16_INV

    def _sync_enter_data(self):
        self.raw = [0] * 13
        self.raw[0] = self.SYNC0
        self.raw[1] = self.SYNC1
        self.byte_count = 2
        self.d.parser_step = 3
        self.ddata = 0
        self.dcount = 16

    def _feed_event(self, event):
        if self.d.parser_step == 2:  # Sync
            if self._sync_feed(event):
                self._sync_enter_data()
            return
        res, self.mstate, bit = m_adv(self.mstate, event)
        if not res:
            return
        if self.d.parser_step != 3:  # Data
            return
        bit = not bit  # data bits are inverted
        self.ddata = ((self.ddata << 1) | (1 if bit else 0)) & 0xFFFFFFFF
        self.dcount += 1
        if (self.dcount & 7) == 0:
            bv = self.ddata & 0xFF
            if self.byte_count < self.DATA_BYTES:
                self.raw[self.byte_count] = bv
                self.byte_count += 1
            self.ddata = 0
            if self.byte_count == self.DATA_BYTES:
                self._commit()
                self._reset()

    def _feed_pulse(self, level, duration):
        if self._is_short(duration):
            self._feed_event(EV_SHORT_HIGH if level else EV_SHORT_LOW)
            return True
        if self._is_long(duration):
            self._feed_event(EV_LONG_HIGH if level else EV_LONG_LOW)
            return True
        return False

    def _enter_sync(self, level, duration):
        self.d.parser_step = 2
        self.byte_count = 0
        self.sync_shift = 0
        self.sync_bits = 0
        self.raw = [0] * 13
        self.mstate = ST_START1
        if self._is_short(duration):
            ev = EV_SHORT_HIGH if level else EV_SHORT_LOW
            if ev in (EV_SHORT_LOW, EV_LONG_LOW):
                self.mstate = ST_MID0
            self._feed_event(ev)
        elif self._is_long(duration):
            ev = EV_LONG_HIGH if level else EV_LONG_LOW
            if ev in (EV_SHORT_LOW, EV_LONG_LOW):
                self.mstate = ST_MID0
            self._feed_event(ev)
        else:
            self._reset()

    def feed(self, level, duration):
        st = self.d.parser_step
        if st == 0:  # Reset
            if self._is_short(duration):
                self.preamble_count = 1
                self.d.parser_step = 1
        elif st == 1:  # Preamble
            if self._is_short(duration):
                if self.preamble_count < 0xFFFF:
                    self.preamble_count += 1
            elif (not level) and self._is_long(duration):
                if self.preamble_count >= self.PREAMBLE_MIN:
                    self._enter_sync(level, duration)
                else:
                    self._reset()
            else:
                self._reset()
        elif st in (2, 3):  # Sync / Data
            if not self._feed_pulse(level, duration):
                self._reset()
            self.d.te_last = duration


# ============================================================ FORD V3
# ts=240 tl=480 delta=60. FM. 104-bit Manchester. NO CRC -- weak structural
# gate only (rejects constant runs / serial==0). Reported crc_ok=False,
# struct_ok=True; treat as UNVERIFIED (documented false-positive prone).
class FordV3(ProtoDecoder):
    NAME = "Ford V3"
    CONST = Const(240, 480, 60, 104)
    DATA_BYTES = 13
    PREAMBLE_MIN = 30
    _BTN = {0x01: "Lock", 0x02: "Unlock"}

    def __init__(self):
        super().__init__()
        self.mstate = ST_START1
        self.raw = [0] * 13
        self.bit_count = 0
        self.preamble_count = 0
        self.serial = 0
        self.counter = 0
        self.btn = 0

    def _reset_data(self):
        self.raw = [0] * 13
        self.bit_count = 0
        self.preamble_count = 0
        self.mstate = ST_START1

    def _add_bit(self, bit):
        if self.bit_count >= 104:
            return
        bi = self.bit_count // 8
        off = 7 - (self.bit_count % 8)
        if bit:
            self.raw[bi] |= (1 << off)
        self.bit_count += 1

    def _parse(self):
        b = self.raw
        self.serial = (b[1] << 24) | (b[2] << 16) | (b[3] << 8) | b[4]
        self.counter = ((((~b[7]) & 0xFF) << 8) | ((~b[8]) & 0xFF)) & 0xFFFF
        self.btn = 0x02 if (b[6] & 0x01) else 0x01

    def _emit_if_ready(self):
        if self.bit_count < 104:
            return
        self._parse()
        if all(x == 0 for x in self.raw) or all(x == 0xFF for x in self.raw):
            return
        if self.btn not in (0x01, 0x02):
            return
        if self.serial == 0:
            return
        data = 0
        for i in range(8):
            data = (data << 8) | self.raw[i]
        self.emit({"data": data, "serial": self.serial, "btn": self.btn,
                   "btn_name": self._BTN.get(self.btn, "?"), "cnt": self.counter,
                   "crc": None, "crc_ok": False, "struct_ok": True})

    def feed(self, level, duration):
        c = self.CONST
        st = self.d.parser_step
        if st == 0:  # Reset
            if is_short(duration, c):
                self._reset_data()
                self.preamble_count = 1
                self.d.parser_step = 1
        elif st == 1:  # Preamble
            if is_short(duration, c):
                self.preamble_count += 1
            elif self.preamble_count >= self.PREAMBLE_MIN and is_long(duration, c):
                self.mstate = ST_MID1
                ev = EV_LONG_HIGH if level else EV_LONG_LOW
                res, self.mstate, bit = m_adv(self.mstate, ev)
                if res:
                    self._add_bit(bit)
                self.d.parser_step = 2
            else:
                self.d.parser_step = 0
        elif st == 2:  # Data
            short = is_short(duration, c)
            long_ = is_long(duration, c)
            if not short and not long_:
                self._emit_if_ready()
                self.d.parser_step = 0
                if short:  # (unreachable, mirrors C) restart preamble
                    self._reset_data()
                    self.preamble_count = 1
                    self.d.parser_step = 1
                return
            if level:
                ev = EV_SHORT_HIGH if short else EV_LONG_HIGH
            else:
                ev = EV_SHORT_LOW if short else EV_LONG_LOW
            res, self.mstate, bit = m_adv(self.mstate, ev)
            if res:
                self._add_bit(bit)
                if self.bit_count >= 104:
                    self._emit_if_ready()
                    self.d.parser_step = 0


ALL = [FordV0, FordV1, FordV2, FordV3]
