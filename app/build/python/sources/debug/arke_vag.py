#!/usr/bin/env python3
r"""
arke_vag.py -- VAG-group + Nissan automotive RAW demodulators, ported from the
Momentum/tembed Flipper Sub-GHz C decoders to pure Python on arke_core.

Ported (DECODER path only: feed state machine + frame parse + validation):
  * Vag       <- vag.c        (80-bit; OOK 300/600 type1/2, OOK 500/1000+750sync type3/4)
  * VagPolo   <- vag_polo.c   (64/80-bit Manchester, OOK 300/590, sync 0x3F91)
  * AudiV1    <- audi_v1.c    (88-bit Manchester, OOK 260/520, sync3000/hdr2000)
  * AudiV2    <- audi_v2.c    (72-bit Manchester, OOK 280/560, trailer 0x93)
  * AudiV3    <- audi_v3.c    (96-bit Manchester, 2-FSK 383/766)  [FSK; OOK caps won't hit]
  * NissanV0  <- nissan_v0.c  (64-bit Manchester, OOK 500/1000, sync+marker)

VALIDATION NOTE — none of these carry a plain CRC:
  - Vag type2 uses TEA with a hard-coded key schedule (present in vag.c) -> fully
    decryptable here; crc_ok=True when the decrypted button matches the cleartext
    dispatch byte (the firmware's own accept test).
  - Vag type1/3/4 use AUT64 with manufacturer keys we do NOT have -> the rolling
    block cannot be decrypted; we report the structural lock (frame prefix / sync,
    80 bits, valid type byte + dispatch byte -> cleartext button) and crc_ok=None.
  - AudiV2 trailer==0x93 acts as an 8-bit frame check -> reported as crc_ok.
  - AudiV1 exposes a *cleartext* 32-bit serial + button + counter (gate: btn in
    {Lock,Unlock} and serial!=0); rolling hop encrypted -> crc_ok=None (structural).
  - NissanV0 locks on a 12-bit sync(110001)+marker(000010@+48); payload encrypted
    -> crc_ok=None (structural).  VagPolo locks on sync 0x3F91 + button nibble.
Per the task rules, crc_ok=None means "consistent structural lock, no CRC to prove
it"; cross-press consistency (stable serial / incrementing counter) is the success
signal for those.
"""
from arke_core import Const, ProtoDecoder, diff, Manchester, MASK64

# VAG AUT64 keystore (3 keys, index 1/2/3) -- from the VAG AUT64 plaintext keystore
# (feed.txt Section 2 / subghz_vag_default_keys.h; ESP32 crypto is a memcpy no-op).
_VAG_AUT64_BLOB = ("01376C86ADABCC43074DE859C12F36AB"
                   "02377C65CEDC42EAA453E861D9B720FC"
                   "038AA37B1E561F8384B619C52E0A3FD7")
try:
    import aut64 as _aut64
    _b = bytes.fromhex(_VAG_AUT64_BLOB)
    _AUT64_KEYS = [_aut64.unpack(_b[i * 16:(i + 1) * 16]) for i in range(len(_b) // 16)]
except Exception:
    _aut64 = None
    _AUT64_KEYS = []

# Manchester event codes (toolbox/manchester_decoder.h)
_SL, _SH, _LL, _LH, _RST = 0, 2, 4, 6, 8


def _dd(a, b):
    return b - a if a < b else a - b


# ============================================================ VAG (vag.c)
_VAG_BTN = {0x1: "Unlock", 0x2: "Lock", 0x4: "Boot"}
_VAG_TEA_KEY = (0x0B46502D, 0x5E253718, 0x2BF93A19, 0x622C1206)
_VAG_TEA_DELTA = 0x9E3779B9
_M32 = 0xFFFFFFFF


def _vag_tea_decrypt(v0, v1):
    s = (_VAG_TEA_DELTA * 32) & _M32
    for _ in range(32):
        v1 = (v1 - ((((v0 << 4) ^ (v0 >> 5)) + v0 ^ (s + _VAG_TEA_KEY[(s >> 11) & 3])))) & _M32
        s = (s - _VAG_TEA_DELTA) & _M32
        v0 = (v0 - ((((v1 << 4) ^ (v1 >> 5)) + v1 ^ (s + _VAG_TEA_KEY[s & 3])))) & _M32
    return v0, v1


def _vag_tea_encrypt(v0, v1):
    """Inverse of _vag_tea_decrypt (port of vag_tea_encrypt) -- for next-code."""
    s = 0
    for _ in range(32):
        v0 = (v0 + ((((v1 << 4) ^ (v1 >> 5)) + v1 ^ (s + _VAG_TEA_KEY[s & 3])))) & _M32
        s = (s + _VAG_TEA_DELTA) & _M32
        v1 = (v1 + ((((v0 << 4) ^ (v0 >> 5)) + v0 ^ (s + _VAG_TEA_KEY[(s >> 11) & 3])))) & _M32
    return v0, v1


def _vag_type2_next_key1(dec, next_cnt):
    """Given the current decrypted plaintext bytes dec[0..7], keep serial+button,
    set the 24-bit LE counter to next_cnt, TEA-encrypt, and repack into the 64-bit
    key1 word the frame carries. Feeding cnt+1 yields the next code the RX accepts."""
    nd = list(dec)
    nd[4] = next_cnt & 0xFF
    nd[5] = (next_cnt >> 8) & 0xFF
    nd[6] = (next_cnt >> 16) & 0xFF
    v0 = (nd[0] << 24) | (nd[1] << 16) | (nd[2] << 8) | nd[3]
    v1 = (nd[4] << 24) | (nd[5] << 16) | (nd[6] << 8) | nd[7]
    e0, e1 = _vag_tea_encrypt(v0, v1)
    eb = [(e0 >> 24) & 0xFF, (e0 >> 16) & 0xFF, (e0 >> 8) & 0xFF, e0 & 0xFF,
          (e1 >> 24) & 0xFF, (e1 >> 16) & 0xFF, (e1 >> 8) & 0xFF, e1 & 0xFF]
    # key1 = [type_byte, block[0..6]]; block[7] goes to key2_high (not in key1).
    # Rebuild key1 keeping the original type_byte (dec is independent of it).
    return eb  # 8 encrypted block bytes; caller assembles key1/key2 for display


def _vag_dispatch_btn(dispatch):
    # dispatch high nibble is the transmitted (cleartext) button indicator
    return (dispatch >> 4) & 0xF


def _vag_dispatch_1_2(d):
    return d in (0x2A, 0x1C, 0x46)


def _vag_dispatch_3_4(d):
    return d in (0x2B, 0x1D, 0x47)


# timing (us) -- type 1/2
_T12_S, _T12_L, _T12_SD, _T12_GD, _T12_PRE = 300, 600, 100, 200, 151
# timing (us) -- type 3/4
_T34_S, _T34_L, _T34_SD, _T34_LD = 500, 1000, 100, 200
_T34_SYNC, _T34_SYNCD, _T34_PRE, _T34_PAIRS = 750, 150, 31, 3

_VAG_GAP_MIN, _VAG_TOTAL, _VAG_KEY1, _VAG_PREFIX, _VAG_LIMIT = 4001, 80, 64, 15, 96
_VAG_PFX_T1, _VAG_PFX_T2 = 0x2F3F, 0x2F1C


class Vag(ProtoDecoder):
    NAME = "VAG"
    CONST = Const(500, 1000, 80, 80)

    # steps: 0 reset,1 pre1,2 data1,3 pre2,4 sync2a,5 sync2b,6 sync2c,7 data2
    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self._clear()

    def _clear(self):
        self.data = 0
        self.bit = 0
        self.hdr = 0
        self.mid = 0
        self.vtype = 0
        self.key1 = 0
        self.key2 = 0

    def reset(self):
        super().reset()
        self.man.reset()
        self._clear()

    # ---- frame parse / validation ----
    def _aut64_decode(self, block, expected_btn):
        """type1/3/4: try the 3 VAG AUT64 keys; accept the key whose decrypted
        button matches the cleartext dispatch button (firmware vag_button_matches
        -- far fewer false matches than the bare {1,2,4} test)."""
        if not _AUT64_KEYS:
            return None
        for k in _AUT64_KEYS:            # firmware order: index 1,2,3
            b = list(block)
            _aut64.decrypt(k, b)
            dec_btn = (b[7] >> 4) & 0xF
            if dec_btn == expected_btn or (b[7] == 0 and expected_btn == 2):
                serial = (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]
                cnt = b[4] | (b[5] << 8) | (b[6] << 16)
                return serial, cnt, dec_btn, k.index
        return None

    def _parse_and_emit(self):
        key1, key2, vtype = self.key1, self.key2, self.vtype
        dispatch = key2 & 0xFF
        key2_high = (key2 >> 8) & 0xFF
        k1 = [(key1 >> (56 - 8 * i)) & 0xFF for i in range(8)]
        type_byte = k1[0]
        block = [k1[1], k1[2], k1[3], k1[4], k1[5], k1[6], k1[7], key2_high]
        btn_ct = _vag_dispatch_btn(dispatch)
        base = {"data": key1, "bits": 64, "key1": key1, "key2": key2, "vtype": vtype,
                "type_byte": type_byte, "dispatch": dispatch,
                "block_enc": "".join("%02X" % b for b in block)}

        # firmware structural gates: reject mis-locked / noise frames before
        # trusting the block. type1/2 need a valid 1/2 dispatch; type3/4 need
        # both the 0xCx device-class prefix and a valid 3/4 dispatch.
        if vtype in (1, 2):
            if not _vag_dispatch_1_2(dispatch):
                return
        elif vtype in (3, 4):
            if (type_byte & 0xF0) != 0xC0 or not _vag_dispatch_3_4(dispatch):
                return
        else:
            return

        if vtype == 2 and _vag_dispatch_1_2(dispatch):
            # TEA-decryptable (schedule is public in firmware)
            v0 = (block[0] << 24) | (block[1] << 16) | (block[2] << 8) | block[3]
            v1 = (block[4] << 24) | (block[5] << 16) | (block[6] << 8) | block[7]
            d0, d1 = _vag_tea_decrypt(v0, v1)
            dec = [(d0 >> 24) & 0xFF, (d0 >> 16) & 0xFF, (d0 >> 8) & 0xFF, d0 & 0xFF,
                   (d1 >> 24) & 0xFF, (d1 >> 16) & 0xFF, (d1 >> 8) & 0xFF, d1 & 0xFF]
            exp = _vag_dispatch_btn(dispatch)
            dec_btn = (dec[7] >> 4) & 0xF
            ok = (dec_btn == exp) or (dec[7] == 0 and exp == 2)
            if ok:
                serial = (dec[0] << 24) | (dec[1] << 16) | (dec[2] << 8) | dec[3]
                cnt = dec[4] | (dec[5] << 8) | (dec[6] << 16)
                btn = (dec[7] >> 4) & 0xF
                # next-code: re-encrypt the plaintext with cnt+1 (embedded TEA key).
                nxt = (cnt + 1) & 0xFFFFFF
                eb = _vag_type2_next_key1(dec, nxt)
                base.update({"serial": serial, "cnt": cnt, "btn": btn,
                             "btn_name": _VAG_BTN.get(btn, "0x%X" % btn),
                             "crc_ok": True, "crypto": "TEA",
                             "next": int.from_bytes(bytes(eb), "big"), "next_cnt": nxt})
                self.emit(base)
                return
            # decrypt didn't validate -> fall through to structural report

        # type 1/3/4: AUT64 (keys loaded from the VAG AUT64 keystore)
        if vtype in (1, 3, 4):
            d = self._aut64_decode(block, btn_ct)
            if d:
                serial, cnt, btn, kidx = d
                base.update({"serial": serial, "cnt": cnt, "btn": btn,
                             "btn_name": _VAG_BTN.get(btn, "0x%X" % btn),
                             "crc_ok": None, "crypto": "AUT64", "key_idx": kidx})
                self.emit(base)
                return

        # no crypto match -> report structural lock + cleartext dispatch button.
        base.update({"serial": None, "cnt": None, "btn": btn_ct,
                     "btn_name": _VAG_BTN.get(btn_ct, "0x%X" % btn_ct),
                     "crc_ok": None,
                     "crypto": "AUT64(no key)" if vtype in (1, 3, 4) else "TEA(no match)"})
        self.emit(base)

    def feed(self, level, duration):
        st = self.d.parser_step
        # ---------------- Reset ----------------
        if st == 0:
            if not level:
                return
            if _dd(duration, _T12_S) < _T12_SD:
                self.d.parser_step = 1
            elif _dd(duration, _T34_S) < _T34_SD:
                self.d.parser_step = 3
            else:
                return
            self.data = 0
            self.hdr = 0
            self.mid = 0
            self.bit = 0
            self.vtype = 0
            self.d.te_last = duration
            self.man.reset()

        # ---------------- Preamble1 (type1/2) ----------------
        elif st == 1:
            if level:
                return
            if _dd(duration, _T12_S) < _T12_SD:
                if _dd(self.d.te_last, _T12_S) < _T12_SD:
                    self.d.te_last = duration
                    self.hdr += 1
                else:
                    self.d.parser_step = 0
                return
            self.d.parser_step = 0
            if self.hdr < _T12_PRE:
                return
            if _dd(duration, _T12_L) >= _T12_GD:
                return
            if _dd(self.d.te_last, _T12_S) >= _T12_SD:
                return
            self.d.parser_step = 2

        # ---------------- Data1 (type1/2) ----------------
        elif st == 2:
            if self.bit >= _VAG_LIMIT:
                self.d.parser_step = 0
                return
            ev = None
            if _dd(duration, _T12_S) < _T12_SD:
                ev = _SL if level else _SH
            elif duration > _T12_S + _T12_SD and _dd(duration, _T12_L) < _T12_GD:
                ev = _LL if level else _LH
            if ev is not None:
                b = self.man.advance(ev)
                if b is not None and self.bit < _VAG_LIMIT:
                    self.data = ((self.data << 1) | (1 if b else 0)) & MASK64
                    self.bit += 1
                    if self.bit == _VAG_PREFIX:
                        if self.data == _VAG_PFX_T1:
                            self.data = 0
                            self.bit = 0
                            self.vtype = 1
                        elif self.data == _VAG_PFX_T2:
                            self.data = 0
                            self.bit = 0
                            self.vtype = 2
                    elif self.bit == _VAG_KEY1:
                        self.key1 = (~self.data) & MASK64
                        self.data = 0
                return
            if level:
                return
            if duration < _VAG_GAP_MIN:
                return
            if self.bit == _VAG_TOTAL:
                self.key2 = (~self.data) & 0xFFFF
                self._parse_and_emit()
            self.data = 0
            self.bit = 0
            self.d.parser_step = 0

        # ---------------- Preamble2 (type3/4) ----------------
        elif st == 3:
            if not level:
                if (_dd(duration, _T34_S) < _T34_SD
                        and _dd(self.d.te_last, _T34_S) < _T34_SD):
                    self.d.te_last = duration
                    self.hdr += 1
                else:
                    self.d.parser_step = 0
                return
            if self.hdr < _T34_PRE:
                return
            if _dd(duration, _T34_L) >= _T34_LD:
                return
            if _dd(self.d.te_last, _T34_S) >= _T34_SD:
                return
            self.d.te_last = duration
            self.d.parser_step = 4

        # ---------------- Sync2A ----------------
        elif st == 4:
            if (not level and _dd(duration, _T34_S) < _T34_SD
                    and _dd(self.d.te_last, _T34_L) < _T34_LD):
                self.d.te_last = duration
                self.d.parser_step = 5
            else:
                self.d.parser_step = 0

        # ---------------- Sync2B ----------------
        elif st == 5:
            if level and _dd(duration, _T34_SYNC) < _T34_SYNCD:
                self.d.te_last = duration
                self.d.parser_step = 6
            else:
                self.d.parser_step = 0

        # ---------------- Sync2C ----------------
        elif st == 6:
            if (not level and _dd(duration, _T34_SYNC) < _T34_SYNCD
                    and _dd(self.d.te_last, _T34_SYNC) < _T34_SYNCD):
                self.mid += 1
                self.d.parser_step = 5
                if self.mid == _T34_PAIRS:
                    self.data = 1
                    self.bit = 1
                    self.man.reset()
                    self.d.parser_step = 7
            else:
                self.d.parser_step = 0

        # ---------------- Data2 (type3/4) ----------------
        elif st == 7:
            ev = None
            if _dd(duration, _T34_S) < _T34_SD:
                ev = _SL if level else _SH
            elif _dd(duration, _T34_L) < _T34_LD:
                ev = _LL if level else _LH
            if ev is not None:
                b = self.man.advance(ev)
                if b is not None:
                    self.data = ((self.data << 1) | (1 if b else 0)) & MASK64
                    self.bit += 1
                    if self.bit == _VAG_KEY1:
                        self.key1 = self.data
                        self.data = 0
            if self.bit != _VAG_TOTAL:
                return
            self.key2 = self.data & 0xFFFF
            self.vtype = 3
            self._parse_and_emit()
            self.data = 0
            self.bit = 0
            self.d.parser_step = 0


# ============================================================ VAG POLO (vag_polo.c)
_VP_S, _VP_L, _VP_D = 300, 590, 130
_VP_SYNC, _VP_PRE_ONES, _VP_BITS, _VP_BYTES = 0x3F91, 32, 80, 10


class VagPolo(ProtoDecoder):
    NAME = "VAG Polo"
    CONST = Const(300, 590, 130, 64)

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self._clear()

    def _clear(self):
        self.sync_reg = 0
        self.pre_ones = 0
        self.payload = [0] * _VP_BYTES
        self.pbits = 0

    def reset(self):
        super().reset()
        self.man.reset()
        self._clear()

    def _event(self, duration, level):
        tol = _VP_D + 20
        if _dd(duration, _VP_S) < tol:
            return _SL if level else _SH
        if _dd(duration, _VP_L) < tol:
            return _LL if level else _LH
        return None

    def _commit(self):
        button = self.payload[0]
        code = self.payload[1:10]
        serial = (code[0] << 24) | (code[1] << 16) | (code[2] << 8) | code[3]
        hi = (button >> 4) & 0xF
        btn = 1 if hi == 0x7 else 2
        name = {0x7: "Unlock", 0xB: "Lock"}.get(hi, "?")
        data = 0
        for b in code[1:9]:
            data = (data << 8) | b
        self.emit({"data": data, "serial": serial, "btn": btn, "btn_name": name,
                   "cnt": None, "crc_ok": None, "button_raw": button,
                   "code": "".join("%02X" % b for b in code)})

    def feed(self, level, duration):
        st = self.d.parser_step
        if st == 0:  # reset
            if level and _dd(duration, _VP_S) < _VP_D + 20:
                self.d.parser_step = 5
                self.man.state = 1  # ManchesterStateMid1
                self.sync_reg = 0
                self.pre_ones = 0
        elif st == 5:  # preamble
            ev = self._event(duration, level)
            if ev is None:
                self.d.parser_step = 0
                return
            b = self.man.advance(ev)
            if b is not None:
                self.sync_reg = ((self.sync_reg << 1) | (1 if b else 0)) & 0xFFFF
                if b:
                    self.pre_ones += 1
                elif self.pre_ones < _VP_PRE_ONES:
                    self.pre_ones = 0
                    self.sync_reg = 0
                    return
                if self.sync_reg == _VP_SYNC and self.pre_ones >= _VP_PRE_ONES:
                    self.pbits = 0
                    self.payload = [0] * _VP_BYTES
                    self.d.parser_step = 6
        elif st == 6:  # data
            ev = self._event(duration, level)
            if ev is None:
                self.d.parser_step = 0
                return
            b = self.man.advance(ev)
            if b is not None:
                bi = self.pbits >> 3
                if bi < _VP_BYTES:
                    self.payload[bi] = ((self.payload[bi] << 1) | (1 if b else 0)) & 0xFF
                self.pbits += 1
                if self.pbits >= _VP_BITS:
                    hi = (self.payload[0] >> 4) & 0xF
                    if hi in (0x7, 0xB):
                        self._commit()
                    self.d.parser_step = 0
                    self.man.state = 1
                    self.sync_reg = 0
                    self.pre_ones = 0
                    self.pbits = 0


# ============================================================ AUDI helpers
def _audi_event(duration, level, ts, tl, td):
    """Audi polarity is INVERTED vs core: level->ShortHigh/LongHigh."""
    if _dd(duration, ts) <= td:
        return _SH if level else _SL
    if _dd(duration, tl) <= td:
        return _LH if level else _LL
    return None


_AUDI_V1_BTN = {0x0E: "Lock", 0x0B: "Unlock"}


# ============================================================ AUDI V1 (audi_v1.c)
_A1_SYNC, _A1_HDR, _A1_SYNCD, _A1_HDRD = 3000, 2000, 800, 500
_A1_S, _A1_L, _A1_D, _A1_BITS, _A1_PRE = 260, 520, 130, 88, 20


class AudiV1(ProtoDecoder):
    NAME = "Audi V1"
    CONST = Const(260, 520, 130, 88)

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self.pre = 0
        self.gdata = 0

    def reset(self):
        super().reset()
        self.man.reset()
        self.pre = 0
        self.gdata = 0

    def _complete(self):
        d = self.d
        if d.decode_count_bit < _A1_BITS:
            return
        rem24 = d.decode_data & 0xFFFFFF
        btn_byte = (self.gdata >> 24) & 0xFF
        hop = ((self.gdata & 0xFFFFFF) << 24) | rem24
        serial = (self.gdata >> 32) & _M32
        btn_id = (btn_byte >> 4) & 0xF
        hop_bits = (hop >> 42) & 0x3F
        cnt = hop_bits | ((btn_byte & 0x03) << 6)
        if btn_id not in (0x0E, 0x0B) or serial == 0:
            return
        self.emit({"data": self.gdata, "serial": serial, "btn": btn_id,
                   "btn_name": _AUDI_V1_BTN.get(btn_id, "0x%X" % btn_id),
                   "cnt": cnt, "crc_ok": None,
                   "hop": "%012X" % hop})

    def feed(self, level, duration):
        d = self.d
        st = d.parser_step
        if st == 0:  # reset -> wait SYNC (~3000 low)
            if not level and _dd(duration, _A1_SYNC) <= _A1_SYNCD:
                d.parser_step = 1
        elif st == 1:  # wait header (~2000 high)
            if level and _dd(duration, _A1_HDR) <= _A1_HDRD:
                d.parser_step = 2
                self.pre = 0
            else:
                d.parser_step = 0
        elif st == 2:  # preamble shorts, first long -> data
            if _dd(duration, _A1_S) <= _A1_D:
                self.pre += 1
            elif _dd(duration, _A1_L) <= _A1_D and self.pre >= _A1_PRE:
                d.parser_step = 3
                d.decode_data = 0
                d.decode_count_bit = 0
                self.gdata = 0
                self.man.reset()
                ev = _LH if level else _LL  # trigger long as separator
                self.man.advance(ev)
            else:
                d.parser_step = 0
        elif st == 3:  # data
            ev = _audi_event(duration, level, _A1_S, _A1_L, _A1_D)
            if ev is not None:
                b = self.man.advance(ev)
                if b is not None:
                    d.decode_data = ((d.decode_data << 1) | (1 if b else 0)) & MASK64
                    d.decode_count_bit += 1
                    if d.decode_count_bit == 64:
                        self.gdata = d.decode_data
                        d.decode_data = 0
            if d.decode_count_bit >= _A1_BITS:
                self._complete()
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 0
                return
            if ev is None:
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 0


# ============================================================ AUDI V2 (audi_v2.c)
_A2_S, _A2_L, _A2_D, _A2_BITS, _A2_PRE, _A2_TRAILER = 280, 560, 100, 72, 40, 0x93


class AudiV2(ProtoDecoder):
    NAME = "Audi V2"
    CONST = Const(280, 560, 100, 72)

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self.pre = 0
        self.gdata = 0

    def reset(self):
        super().reset()
        self.man.reset()
        self.pre = 0
        self.gdata = 0

    def _complete(self):
        d = self.d
        if d.decode_count_bit < _A2_BITS:
            return
        trailer = d.decode_data & 0xFF
        ok = (trailer == _A2_TRAILER)
        # emit only on valid trailer (mirrors firmware accept)
        if ok:
            self.emit({"data": self.gdata, "serial": None, "btn": None,
                       "cnt": None, "crc_ok": True, "trailer": trailer,
                       "key": "%016X" % self.gdata})

    def feed(self, level, duration):
        d = self.d
        st = d.parser_step
        if st == 0:  # reset: any short starts preamble
            if _dd(duration, _A2_S) <= _A2_D:
                self.pre = 1
                d.parser_step = 1
        elif st == 1:  # preamble
            if _dd(duration, _A2_S) <= _A2_D:
                self.pre += 1
            elif _dd(duration, _A2_L) <= _A2_D and self.pre >= _A2_PRE:
                d.parser_step = 2
                d.decode_data = 0
                d.decode_count_bit = 0
                self.gdata = 0
                self.man.reset()
                ev = _LH if level else _LL
                self.man.advance(ev)
            else:
                d.parser_step = 0
                self.pre = 0
        elif st == 2:  # data
            ev = _audi_event(duration, level, _A2_S, _A2_L, _A2_D)
            if ev is not None:
                b = self.man.advance(ev)
                if b is not None:
                    d.decode_data = ((d.decode_data << 1) | (1 if b else 0)) & MASK64
                    d.decode_count_bit += 1
                    if d.decode_count_bit == 64:
                        self.gdata = d.decode_data
                        d.decode_data = 0
            if d.decode_count_bit >= _A2_BITS:
                self._complete()
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 0
                return
            if ev is None:
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 0


# ============================================================ AUDI V3 (audi_v3.c) 2-FSK
_A3_PRE_LO, _A3_PRE_HI = 220, 470
_A3_LONG_LO, _A3_LONG_HI, _A3_GAP, _A3_MIN_PRE = 580, 980, 980, 20
_A3_BITS = 96
_A3_BTN = {0x0C: "Unlock", 0x0D: "Lock"}


class AudiV3(ProtoDecoder):
    NAME = "Audi V3"
    CONST = Const(383, 766, 180, 96)

    def __init__(self):
        super().__init__()
        self._clear()

    def _clear(self):
        self.pre = 0
        self.half = False
        self.bits = []

    def reset(self):
        super().reset()
        self._clear()

    def _process(self):
        if len(self.bits) < _A3_BITS:
            return
        bits = self.bits
        button = 0
        for i in range(8):
            button = (button << 1) | bits[i]
        hop = [0] * 7
        for b in range(7):
            v = 0
            for k in range(8):
                v = (v << 1) | bits[8 + b * 8 + k]
            hop[b] = v
        counter = 0
        for i in range(24):
            counter = (counter << 1) | bits[64 + i]
        chk = 0
        for i in range(8):
            chk = (chk << 1) | bits[88 + i]
        if all(h == 0xFF for h in hop) or all(h == 0x00 for h in hop):
            return
        if button not in (0x0C, 0x0D):
            return
        data = button
        for b in hop:
            data = (data << 8) | b
        self.emit({"data": data, "serial": None, "btn": button,
                   "btn_name": _A3_BTN.get(button, "0x%X" % button),
                   "cnt": counter, "crc_ok": None, "chk": chk,
                   "hop": "".join("%02X" % h for h in hop)})

    def feed(self, level, duration):
        in_pre = _A3_PRE_LO <= duration <= _A3_PRE_HI
        is_long = _A3_LONG_LO <= duration <= _A3_LONG_HI
        is_short = duration < _A3_LONG_LO
        st = self.d.parser_step
        if st == 0:
            if in_pre:
                self.pre = 1
                self.d.parser_step = 1
        elif st == 1:
            if in_pre:
                self.pre += 1
            elif is_long and self.pre >= _A3_MIN_PRE:
                self.d.parser_step = 2
                self.bits = [0 if level else 1]
                self.half = False
            else:
                self._clear()
                self.d.parser_step = 0
        elif st == 2:
            if duration >= _A3_GAP:
                self._process()
                self._clear()
                self.d.parser_step = 0
                return
            if is_long:
                if self.half:
                    self._process()
                    self._clear()
                    self.d.parser_step = 0
                    return
                else:
                    self.bits.append(0 if level else 1)
            elif is_short:
                if self.half:
                    self.bits.append(0 if level else 1)
                    self.half = False
                else:
                    self.half = True
            if len(self.bits) >= _A3_BITS:
                self._process()
                self._clear()
                self.d.parser_step = 0


# ============================================================ NISSAN V0 (nissan_v0.c)
_N_BITS = 64
_N_SYNC = [1, 1, 0, 0, 0, 1]
_N_MARK = [0, 0, 0, 0, 1, 0]
_N_CAP = 380


class NissanV0(ProtoDecoder):
    NAME = "Nissan V0"
    CONST = Const(500, 1000, 200, 64)

    def __init__(self):
        super().__init__()
        self.hb = []
        self.last = None

    def reset(self):
        super().reset()
        self.hb = []
        self.last = None

    def _process(self):
        if len(self.hb) < 140:
            self.hb = []
            return
        # stateless Manchester: pair differing half-bits, slip on equal
        bits = []
        i = 0
        n = len(self.hb)
        while i + 1 < n and len(bits) < 300:
            a = self.hb[i]
            b = self.hb[i + 1]
            if a == b:
                i += 1
                continue
            bits.append(1 if (a == 0 and b == 1) else 0)
            i += 2
        bc = len(bits)
        found = -1
        p = 0
        while p + _N_BITS <= bc:
            ok = True
            for j in range(6):
                if bits[p + j] != _N_SYNC[j]:
                    ok = False
                    break
            if ok:
                for j in range(6):
                    if bits[p + 48 + j] != _N_MARK[j]:
                        ok = False
                        break
            if ok:
                found = p
                break
            p += 1
        self.hb = []
        if found < 0:
            return
        data = 0
        for j in range(_N_BITS):
            data = (data << 1) | bits[found + j]
        if data == self.last:
            return
        self.last = data
        self.emit({"data": data, "serial": None, "btn": None, "cnt": None,
                   "crc_ok": None, "key": "%016X" % data})

    def feed(self, level, duration):
        if 320 <= duration <= 700:
            nh = 1
        elif 800 <= duration <= 1230:
            nh = 2
        elif 1300 <= duration <= 1730:
            nh = 3
        elif 1800 <= duration <= 2350:
            nh = 4
        else:
            self._process()
            return
        v = 1 if level else 0
        for _ in range(nh):
            if len(self.hb) < 512:
                self.hb.append(v)
        if len(self.hb) >= _N_CAP:
            self._process()


ALL = [Vag, VagPolo, AudiV1, AudiV2, AudiV3, NissanV0]
