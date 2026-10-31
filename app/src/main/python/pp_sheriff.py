#!/usr/bin/env python3
r"""pp_sheriff.py -- Sheriff CFM (ZX-750 / ZX-930) car-alarm remote.

Ported bit-for-bit from ProtoPirate / Fox lib/subghz/protocols/sheriff_cfm.c.

A KeeLoq rolling code wrapped in a small per-model byte transform, with the two
manufacturer KeeLoq keys embedded in the firmware -- obfuscated by XOR against a
"digits of pi" byte mask.  Recovering the keys makes this a FULL clone target:

  decode(frame64)      -> (model, button, serial, counter)
  encode(model, sn, b, cnt) -> frame64
  next_code(frame64)   -> (next_frame64, counter+1)      # the clone / next press

The two recovered manufacturer keys (see cfm_mfkey below) are:
  ZX-750 = 0xB51A7AAB27351596
  ZX-930 = 0x381D7D9A2A17AE99
They exist only inside this firmware file -- not in any public KeeLoq mfr list.

Wire format: 64-bit, PWM.  te_short=400 te_long=800.  bit1 = short-high+long-low,
bit0 = long-high+short-low.  Header = >2 short high pulses then a te_short*10 gap.

Firmware quirk (faithfully reproduced): the plaintext packs a 10-bit serial_lo at
bit 16 and the button byte at bit 24, so bits 24-25 overlap.  The button check
therefore only succeeds when the serial's bits 8-9 are clear (serial & 0x300 == 0);
that is exactly the set of frames the on-device encoder can round-trip.
"""
from arke_core import Const, ProtoDecoder, diff
from kl_crack import keeloq_decrypt, keeloq_encrypt

# ---- embedded manufacturer keys (pi-XOR obfuscated in the firmware) ----------
_PI = [0xA4, 0x58, 0xFE, 0xA3, 0xF4, 0x93, 0x3D, 0x7E,
       0x0D, 0x95, 0x74, 0x8F, 0x72, 0x8E, 0xB6, 0x58]
_ZX750_ENC = [0x32, 0x4D, 0xCB, 0x84, 0x5F, 0xE9, 0x27, 0xCB]  # pi offset 0
_ZX930_ENC = [0x94, 0x3B, 0x63, 0xA5, 0xE8, 0xF3, 0xAB, 0x60]  # pi offset 8


def _mfkey(enc, off):
    dkey = [enc[i] ^ _PI[off + i] for i in range(8)]
    key = 0
    for i in range(7, -1, -1):          # key = (key<<8) | dkey[i], i = 7..0
        key = (key << 8) | dkey[i]
    return key


MODEL_ZX750, MODEL_ZX930 = 0, 1
MODELS = {
    MODEL_ZX750: ("ZX-750", _mfkey(_ZX750_ENC, 0)),
    MODEL_ZX930: ("ZX-930", _mfkey(_ZX930_ENC, 8)),
}
BTN_NAME = {0x10: "Lock", 0x20: "Unlock", 0x40: "Trunk", 0x80: "Panic"}

# ---- timing ----
CONST = Const(400, 800, 140, 64)


# ---- per-model byte transforms (operate on little-endian hop bytes) ----------
def _rlf(b):
    return ((b << 1) | (b >> 7)) & 0xFF


def _rrf(b):
    return ((b >> 1) | (b << 7)) & 0xFF


def _swap(b):
    return ((b << 4) | (b >> 4)) & 0xFF


def _decrypt_transform(hop, model):
    hop = list(hop)
    if model == MODEL_ZX750:
        hop[0] = _swap(hop[0])
        hop[2] = _swap(hop[2])
    elif model == MODEL_ZX930:
        hop[0] = (~hop[0]) & 0xFF
        hop[1], hop[2] = hop[2], hop[1]
        hop[0] = _rrf(hop[0])
        hop[1] = _swap(hop[1])
        hop[1] = _rlf(hop[1])
        hop[1] = _rlf(hop[1])
    return hop


def _encrypt_transform(hop, model):
    hop = list(hop)
    if model == MODEL_ZX750:
        hop[0] = _swap(hop[0])
        hop[2] = _swap(hop[2])
    elif model == MODEL_ZX930:
        hop[1] = _rrf(hop[1])
        hop[1] = _rrf(hop[1])
        hop[1] = _swap(hop[1])
        hop[0] = _rlf(hop[0])
        hop[0] = (~hop[0]) & 0xFF
        hop[1], hop[2] = hop[2], hop[1]
    return hop


def _u32le(bs):
    return (bs[0] | (bs[1] << 8) | (bs[2] << 16) | (bs[3] << 24)) & 0xFFFFFFFF


def _bytes_le(v):
    return [v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF]


# ---- crypto core -------------------------------------------------------------
def try_decrypt(data):
    """data: 64-bit captured frame. Return (model, btn, serial, cnt) or None."""
    hop_enc = data & 0xFFFFFFFF
    fix = (data >> 32) & 0xFFFFFFFF
    for model, (_name, mfkey) in MODELS.items():
        hop_bytes = _decrypt_transform(_bytes_le(hop_enc), model)
        decrypted = keeloq_decrypt(_u32le(hop_bytes), mfkey)
        dec_serial_lo = (decrypted >> 16) & 0x3FF
        fix_serial_lo = fix & 0x3FF
        btn_byte = (decrypted >> 24) & 0xFF
        if btn_byte in BTN_NAME and dec_serial_lo == fix_serial_lo:
            return (model, btn_byte, fix, decrypted & 0xFFFF)
    return None


def encode(model, serial, btn, cnt):
    """Build the 64-bit frame for (model, serial=fix, btn, counter)."""
    mfkey = MODELS[model][1]
    fix = serial & 0xFFFFFFFF
    serial_lo = fix & 0x3FF
    hop_plain = ((btn & 0xFF) << 24) | ((serial_lo & 0x3FF) << 16) | (cnt & 0xFFFF)
    hop_enc = keeloq_encrypt(hop_plain, mfkey)
    hop_bytes = _encrypt_transform(_bytes_le(hop_enc), model)
    return ((fix << 32) | _u32le(hop_bytes)) & 0xFFFFFFFFFFFFFFFF


def next_code(data):
    """Given a captured frame, return (next_frame64, next_cnt) or None."""
    r = try_decrypt(data)
    if not r:
        return None
    model, btn, fix, cnt = r
    ncnt = (cnt + 1) & 0xFFFF
    return encode(model, fix, btn, ncnt), ncnt


# ---- pulse-train generator (self-test + clone TX waveform) -------------------
def frame_to_durations(data64, bits=64):
    """Mirror the firmware encoder upload: signed us durations (+high/-low)."""
    ts, tl = CONST.te_short, CONST.te_long
    out = []
    for _ in range(11):                       # preamble: 11 short high/low pairs
        out += [ts, -ts]
    out += [ts, -ts * 10]                     # start bit + header gap
    for i in range(bits - 1, -1, -1):         # MSB..LSB
        if (data64 >> i) & 1:
            out += [ts, -tl]                  # bit 1
        else:
            out += [tl, -ts]                  # bit 0
    out += [ts, -ts * 40]                     # trailer
    return out


# ---- decoder (mirrors subghz_protocol_decoder_sheriff_cfm_feed) --------------
_RESET, _PRE, _SAVE, _CHK = 0, 1, 2, 3


class SheriffCfm(ProtoDecoder):
    NAME = "Sheriff CFM"
    CONST = CONST

    def __init__(self):
        super().__init__()
        self.step = _RESET
        self.header = 0

    def reset(self):
        super().reset()
        self.step = _RESET
        self.header = 0

    def _finish(self):
        d = self.d
        if CONST.min_bits <= d.decode_count_bit <= CONST.min_bits + 2:
            r = try_decrypt(d.decode_data)
            if r:
                model, btn, serial, cnt = r
                nxt = encode(model, serial, btn, (cnt + 1) & 0xFFFF)
                self.emit({"data": d.decode_data, "serial": serial, "btn": btn,
                           "btn_name": BTN_NAME.get(btn, "?"), "cnt": cnt,
                           "model": MODELS[model][0], "crc_ok": True,
                           "next": nxt, "next_cnt": (cnt + 1) & 0xFFFF})

    def feed(self, level, duration):
        c = CONST
        ts, td = c.te_short, c.te_delta
        d = self.d
        if self.step == _RESET:
            if level and diff(duration, ts) < td:
                self.step = _PRE
                self.header += 1
        elif self.step == _PRE:
            if (not level) and diff(duration, ts) < td:
                self.step = _RESET
                return
            if self.header > 2 and diff(duration, ts * 10) < td * 10:
                self.step = _SAVE
                d.decode_data = 0
                d.decode_count_bit = 0
            else:
                self.step = _RESET
                self.header = 0
        elif self.step == _SAVE:
            if level:
                d.te_last = duration
                self.step = _CHK
        elif self.step == _CHK:
            if not level:
                if duration >= ts * 2 + td:
                    self.step = _RESET
                    self._finish()
                    d.decode_data = 0
                    d.decode_count_bit = 0
                    self.header = 0
                elif diff(d.te_last, ts) < td and diff(duration, c.te_long) < td * 2:
                    if d.decode_count_bit < c.min_bits:
                        d.add_bit(1)
                    else:
                        d.decode_count_bit += 1
                    self.step = _SAVE
                elif diff(d.te_last, c.te_long) < td * 2 and diff(duration, ts) < td:
                    if d.decode_count_bit < c.min_bits:
                        d.add_bit(0)
                    else:
                        d.decode_count_bit += 1
                    self.step = _SAVE
                else:
                    self.step = _RESET
                    self.header = 0
            else:
                self.step = _RESET
                self.header = 0


ALL = [SheriffCfm]


# ---- self-test ---------------------------------------------------------------
if __name__ == "__main__":
    ok = True

    # (1) recovered keys match the known values
    exp = {MODEL_ZX750: 0xB51A7AAB27351596, MODEL_ZX930: 0x381D7D9A2A17AE99}
    for m, (nm, k) in MODELS.items():
        good = (k == exp[m])
        ok &= good
        print("key %-7s = 0x%016X  %s" % (nm, k, "OK" if good else "FAIL"))

    # (2) crypto round-trip: encode -> try_decrypt over models/buttons/counters.
    # serials masked to bits 8-9 clear (see the firmware-quirk note in the docstring).
    n = bad = 0
    for model in (MODEL_ZX750, MODEL_ZX930):
        for serial in (0x1A2B3C4D, 0x000000F5, 0xDEADBC0F):
            serial &= 0xFFFFFCFF
            for btn in (0x10, 0x20, 0x40, 0x80):
                for cnt in (0, 1, 0x1234, 0x7FFF, 0xFFFF):
                    fr = encode(model, serial, btn, cnt)
                    r = try_decrypt(fr)
                    n += 1
                    if r != (model, btn, serial, cnt):
                        bad += 1
                        if bad <= 3:
                            print("  crypto FAIL m=%d sn=%08X btn=%02X cnt=%04X -> %r"
                                  % (model, serial, btn, cnt, r))
    print("crypto round-trip: %d/%d ok" % (n - bad, n))
    ok &= (bad == 0)

    # (3) next_code advances the counter and stays valid
    fr = encode(MODEL_ZX930, 0x1A2B3C4D, 0x20, 0x0041)
    nxt, ncnt = next_code(fr)
    r = try_decrypt(nxt)
    good = (ncnt == 0x0042 and r == (MODEL_ZX930, 0x20, 0x1A2B3C4D, 0x0042))
    ok &= good
    print("next_code: cnt 0x41 -> 0x%04X  %s" % (ncnt, "OK" if good else "FAIL"))

    # (4) full end-to-end: build the pulse train, feed the decoder, check it locks
    e2e = bad2 = 0
    for model in (MODEL_ZX750, MODEL_ZX930):
        for btn in (0x10, 0x20, 0x40, 0x80):
            fr = encode(model, 0x1A2B3C4D, btn, 0x0123)
            durs = frame_to_durations(fr)
            dec = SheriffCfm()
            for v in durs:
                dec.feed(v > 0, abs(v))
            e2e += 1
            hit = [x for x in dec.results if x["data"] == fr]
            if not hit or hit[0]["btn"] != btn or hit[0]["cnt"] != 0x0123:
                bad2 += 1
                print("  e2e FAIL model=%d btn=%02X results=%r" % (model, btn, dec.results))
    print("decoder end-to-end: %d/%d locked" % (e2e - bad2, e2e))
    ok &= (bad2 == 0)

    print("\n%s" % ("ALL PASS" if ok else "*** FAILURES ***"))
    raise SystemExit(0 if ok else 1)
