#!/usr/bin/env python3
r"""
arke_kia_rest.py -- KIA/HYU V3/V4 and V5 RAW demodulators (ported from firmware
kia_v3_v4.c / kia_v5.c). V3/V4 counter is KeeLoq-encrypted (KIA mf key), V5 uses
an 18-round mixer cipher (KIA v5 key). Keys come from the keeloq_mfcodes_user
keystore (types 10 and 13). Self-verifying: V3/V4 via the decrypt btn/serial-lsb
invariant; V5 via its 2-bit CRC.
"""
from arke_core import (Const, ProtoDecoder, is_short, is_long, diff, rev8,
                       Manchester, manchester_event, M_RESET_EV)

try:
    import kl_crack
    _keeloq_decrypt = kl_crack.keeloq_decrypt
except Exception:
    _keeloq_decrypt = None

# keeloq_mfcodes_user: KEY:TYPE:NAME  (type 10 = KIA mf key, 13 = KIA v5 key).
# Multiple v3/4 manufacturer keys exist across Kia/Hyundai models; try each and
# let the decrypt self-check (btn + serial-lsb) pick the right one.
KIA_MF_KEYS = [0xA8F5DFFC8DAA5CDB, 0xCB72B95709057920]
KIA_MF_KEY = KIA_MF_KEYS[0]
KIA_V5_KEY = 0x535446524B453030

_BTN = {0x1: "Lock", 0x2: "Unlock", 0x3: "Trunk", 0x4: "Panic"}


# ============================================================ KIA/HYU V3/V4
# ts=400 tl=800 delta=150. Preamble = >=8 short pairs, then a 1000-1500us gap
# (gap on LOW => V3 [inverted], on HIGH => V4). Then a raw pulse-width bitstream
# (short=0, long=1, one bit PER pulse edge) until a 1000-1500 gap ends the frame.
class KiaV3V4(ProtoDecoder):
    NAME = "KIA/HYU V3V4"
    CONST = Const(400, 800, 150, 68)

    def __init__(self):
        super().__init__()
        self.raw = bytearray(32)
        self.nraw = 0
        self.is_v3 = False
        self.header = 0

    def reset(self):
        super().reset()
        self.raw = bytearray(32)
        self.nraw = 0
        self.is_v3 = False
        self.header = 0

    def _add(self, bit):
        if self.nraw < 256:
            bi = self.nraw // 8
            off = 7 - (self.nraw % 8)
            if bit:
                self.raw[bi] |= (1 << off)
            else:
                self.raw[bi] &= ~(1 << off)
            self.nraw += 1

    def _process(self):
        if self.nraw < 68 or _keeloq_decrypt is None:
            return
        b = bytearray(self.raw)
        if self.is_v3:
            n = (self.nraw + 7) // 8
            for i in range(n):
                b[i] = (~b[i]) & 0xFF
        crc = (b[8] >> 4) & 0x0F
        encrypted = ((rev8(b[3]) << 24) | (rev8(b[2]) << 16) | (rev8(b[1]) << 8) | rev8(b[0])) & 0xFFFFFFFF
        serial = ((rev8(b[7] & 0xF0) << 24) | (rev8(b[6]) << 16) | (rev8(b[5]) << 8) | rev8(b[4])) & 0xFFFFFFFF
        btn = (rev8(b[7]) & 0xF0) >> 4
        serial_lsb = serial & 0xFF
        decrypted = None
        for mk in KIA_MF_KEYS:          # try each known v3/4 manufacturer key
            d = _keeloq_decrypt(encrypted, mk) & 0xFFFFFFFF
            if ((d >> 28) & 0x0F) == btn and ((d >> 16) & 0xFF) == serial_lsb:
                decrypted = d
                break
        if decrypted is None:
            return  # no key passes the self-check -> not a real V3/V4 frame
        cnt = decrypted & 0xFFFF
        self.emit({"variant": "V3" if self.is_v3 else "V4", "serial": serial,
                   "btn": btn, "btn_name": _BTN.get(btn, "0x%X" % btn),
                   "cnt": cnt, "crc": crc, "crc_ok": True})

    def feed(self, level, duration):
        d = self.d
        c = self.CONST
        st = d.parser_step
        if st == 0:
            if level and diff(duration, c.te_short) < c.te_delta:
                d.parser_step = 1
                d.te_last = duration
                self.header = 1
        elif st == 1:
            if level:
                if diff(duration, c.te_short) < c.te_delta:
                    d.te_last = duration
                elif 1000 < duration < 1500:
                    if self.header >= 8:
                        d.parser_step = 2
                        self.nraw = 0
                        self.is_v3 = False
                        self.raw = bytearray(32)
                    else:
                        d.parser_step = 0
                else:
                    d.parser_step = 0
            else:
                if 1000 < duration < 1500:
                    if self.header >= 8:
                        d.parser_step = 2
                        self.nraw = 0
                        self.is_v3 = True
                        self.raw = bytearray(32)
                    else:
                        d.parser_step = 0
                elif (diff(duration, c.te_short) < c.te_delta
                      and diff(d.te_last, c.te_short) < c.te_delta):
                    self.header += 1
                elif duration > 1500:
                    d.parser_step = 0
        elif st == 2:
            if level:
                if 1000 < duration < 1500:
                    self._process()
                    d.parser_step = 0
                elif diff(duration, c.te_short) < c.te_delta:
                    self._add(False)
                elif diff(duration, c.te_long) < c.te_delta:
                    self._add(True)
                else:
                    d.parser_step = 0
            else:
                if (1000 < duration < 1500) or duration > 1500:
                    self._process()
                    d.parser_step = 0
                # otherwise LOW pulse is just spacing -- ignore


# ============================================================ KIA/HYU V5
# ts=400 tl=800 delta=150. Manchester; preamble >40 short pairs then long. The
# counter is recovered with an 18-round mixer cipher keyed by the KIA v5 key.
def _v5_mixer_decode(encrypted):
    s0 = encrypted & 0xFF
    s1 = (encrypted >> 8) & 0xFF
    s2 = (encrypted >> 16) & 0xFF
    s3 = (encrypted >> 24) & 0xFF
    ks = [(KIA_V5_KEY >> ((7 - i) * 8)) & 0xFF for i in range(8)]
    ri = 1
    for _ in range(18):
        r = ks[ri] & 0xFF
        steps = 8
        while steps > 0:
            if (s3 & 0x40) == 0:
                base = 0x74 if (s3 & 0x02) == 0 else 0x2E
            else:
                base = 0x3A if (s3 & 0x02) == 0 else 0x5C
            if s2 & 0x08:
                base = (((base >> 4) & 0x0F) | ((base & 0x0F) << 4)) & 0xFF
            if s1 & 0x01:
                base = ((base & 0x3F) << 2) & 0xFF
            if s0 & 0x01:
                base = (base << 1) & 0xFF
            temp = (s3 ^ s1) & 0xFF
            s3 = ((s3 & 0x7F) << 1) & 0xFF
            if s2 & 0x80:
                s3 |= 0x01
            s2 = ((s2 & 0x7F) << 1) & 0xFF
            if s1 & 0x80:
                s2 |= 0x01
            s1 = ((s1 & 0x7F) << 1) & 0xFF
            if s0 & 0x80:
                s1 |= 0x01
            s0 = ((s0 & 0x7F) << 1) & 0xFF
            chk = (base ^ (r ^ temp)) & 0xFF
            if chk & 0x80:
                s0 |= 0x01
            r = ((r & 0x7F) << 1) & 0xFF
            steps -= 1
        ri = (ri - 1) & 0x7
    return (s0 + (s1 << 8)) & 0xFFFF


def _v5_calc_crc(data):
    crc = 0
    for i in range(63, -1, -1):
        bit = (data >> i) & 1
        shifted_out = (crc >> 1) & 1
        crc = (((crc & 1) << 1) | bit) & 0xFF
        if shifted_out:
            crc ^= 3
    return crc & 3


class KiaV5(ProtoDecoder):
    NAME = "KIA/HYU V5"
    CONST = Const(400, 800, 150, 64)

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self.header = 0
        self.nbit = 0
        self.dec = 0        # decoded_data (rolls: key then crc bits)
        self.saved = 0      # saved_key (first 64 bits)

    def reset(self):
        super().reset()
        self.man.reset()
        self.header = 0
        self.nbit = 0
        self.dec = 0
        self.saved = 0

    def _commit(self):
        # trailing 3 bits (0b0 c1 c0) hold the 2-bit CRC; gate on it
        crc = self.dec & 0x07
        if crc != _v5_calc_crc(self.saved):
            return
        yek = 0
        for i in range(8):
            byte = (self.saved >> (i * 8)) & 0xFF
            yek |= rev8(byte) << ((7 - i) * 8)
        serial = (yek >> 32) & 0x0FFFFFFF
        btn = (yek >> 60) & 0x0F
        cnt = _v5_mixer_decode(yek & 0xFFFFFFFF)
        # A 2-bit CRC cannot support a single-frame "verified" claim (FP ~1/8);
        # emit structural (crc_ok=None) so it needs >=2 same-serial frames.
        self.emit({"variant": "V5", "serial": serial, "btn": btn,
                   "btn_name": _BTN.get(btn, "0x%X" % btn), "cnt": cnt,
                   "crc": crc, "crc_ok": None})

    def feed(self, level, duration):
        d = self.d
        c = self.CONST
        st = d.parser_step
        if st == 0:
            if level and diff(duration, c.te_short) < c.te_delta:
                d.parser_step = 1
                d.te_last = duration
                self.header = 1
                self.nbit = 0
                self.dec = 0
                self.man.reset()
        elif st == 1:
            if level:
                if diff(duration, c.te_long) < c.te_delta:
                    if self.header > 40:
                        d.parser_step = 2
                        self.nbit = 0
                        self.dec = 0
                        self.saved = 0
                        self.header = 0
                    else:
                        d.te_last = duration
                elif diff(duration, c.te_short) < c.te_delta:
                    d.te_last = duration
                else:
                    d.parser_step = 0
            else:
                if (diff(duration, c.te_short) < c.te_delta
                        and diff(d.te_last, c.te_short) < c.te_delta):
                    self.header += 1
                elif (diff(duration, c.te_long) < c.te_delta
                      and diff(d.te_last, c.te_short) < c.te_delta):
                    self.header += 1
                elif diff(d.te_last, c.te_long) < c.te_delta:
                    self.header += 1
                else:
                    d.parser_step = 0
                d.te_last = duration
        elif st == 2:
            # kia_v5 uses OPPOSITE manchester polarity vs pp_manchester_event
            if diff(duration, c.te_short) < c.te_delta:
                ev = 2 if level else 0        # ShortHigh : ShortLow
            elif diff(duration, c.te_long) < c.te_delta:
                ev = 6 if level else 4        # LongHigh : LongLow
            else:
                if self.nbit >= c.min_bits:
                    self._commit()
                d.parser_step = 0
                return
            if self.nbit <= 66:
                bit = self.man.advance(ev)
                if bit is not None:
                    self.dec = ((self.dec << 1) | (1 if bit else 0)) & 0xFFFFFFFFFFFFFFFF
                    self.nbit += 1
                    if self.nbit == 64:
                        self.saved = self.dec
                        self.dec = 0
            d.te_last = duration


ALL = [KiaV3V4, KiaV5]
