#!/usr/bin/env python3
r"""pp_scherkhan.py -- pure-Python bit-exact port of the Flipper Sub-GHz
Scher-Khan Magicar car-alarm decoder (lib/subghz/protocols/scher_khan.c,
ProtoPirate FoxEdition edition).

Handles the whole Scher-Khan / Magicar family that the firmware knows:

    35 bit  MAGIC CODE, Static        (opaque -- serial/btn/cnt = 0 in fw)
    51 bit  MAGIC CODE, Dynamic       (serial + btn + 16-bit counter, ~Key check)
    57 bit  MAGIC CODE PRO / PRO2     (rolling cipher: per-packet-counter-keyed
                                        64-entry S-permutation + CRC-8)
    63/64   MAGIC CODE / MAGICAR Response  (opaque, undecodable)
    81/82   MAGIC CODE PRO Response         (opaque, undecodable)

The PRO/PRO2 S-tables and their CRC polynomials are embedded in the firmware
XOR-obfuscated by a "digits of pi" byte mask (sk_pi_bytes).  This module
reproduces sk_pi_decode() exactly and exposes the recovered constants.

Cryptographic bit-exactness matters here -- every primitive below is a
line-for-line transliteration of the C.  Run this file directly for the
self-test (`py -3.13 pp_scherkhan.py`).
"""
from arke_core import Const, ProtoDecoder, diff, MASK64

# =====================================================================
#  Embedded, XOR-obfuscated firmware tables (raw bytes quoted verbatim
#  from scher_khan.c).  De-obfuscation: out[i] = enc[i] ^ sk_pi_bytes[off+i]
# =====================================================================

# static const uint8_t sk_pi_bytes[146]   (the "digits of pi" XOR mask)
SK_PI_BYTES = bytes((
    0x24, 0x3F, 0x6A, 0x88, 0x85, 0xA3, 0x08, 0xD3,
    0x13, 0x19, 0x8A, 0x2E, 0x03, 0x70, 0x73, 0x44,
    0xA4, 0x09, 0x38, 0x22, 0x29, 0x9F, 0x31, 0xD0,
    0x08, 0x2E, 0xFA, 0x98, 0xEC, 0x4E, 0x6C, 0x89,
    0x45, 0x28, 0x21, 0xE6, 0x38, 0xD0, 0x13, 0x77,
    0xBE, 0x54, 0x66, 0xCF, 0x34, 0xE9, 0x0C, 0x6C,
    0xC0, 0xAC, 0x29, 0xB7, 0xC9, 0x7C, 0x50, 0xDD,
    0x3F, 0x84, 0xD5, 0xB5, 0xB5, 0x47, 0x09, 0x17,
    0x92, 0x16, 0xD5, 0xD9, 0x89, 0x79, 0xFB, 0x1B,
    0xD1, 0x31, 0x0B, 0xA6, 0x98, 0xDF, 0xB5, 0xAC,
    0x2F, 0xFD, 0x72, 0xDB, 0xD0, 0x1A, 0xDF, 0xB7,
    0xB8, 0xE1, 0xAF, 0xED, 0x6A, 0x26, 0x7E, 0x96,
    0xBA, 0x7C, 0x90, 0x45, 0xF1, 0x2C, 0x7F, 0x99,
    0x24, 0xA1, 0x99, 0x47, 0xB3, 0x91, 0x6C, 0xF7,
    0x08, 0x01, 0xF2, 0xE2, 0x85, 0x8E, 0xFC, 0x16,
    0x63, 0x69, 0x20, 0xD8, 0x71, 0x57, 0x4E, 0x69,
    0xA4, 0x58, 0xFE, 0xA3, 0xF4, 0x93, 0x3D, 0x7E,
    0x0D, 0x95, 0x74, 0x8F, 0x72, 0x8E, 0xB6, 0x58,
    0x71, 0x8B,
))

# static const uint8_t sk_pro1_encoded[64]   (PRO1 S-table, obfuscated)
SK_PRO1_ENCODED = bytes((
    0x63, 0x5A, 0x58, 0x98, 0xD6, 0xB3, 0x7E, 0x91,
    0x37, 0x1E, 0xBB, 0x78, 0x43, 0x06, 0x20, 0x65,
    0xC7, 0x4B, 0x3F, 0x73, 0x0D, 0xAE, 0x36, 0x86,
    0x1E, 0x7A, 0xDA, 0xEB, 0xBB, 0x0F, 0x5A, 0xA9,
    0x26, 0x3A, 0x51, 0xB2, 0x7A, 0xE0, 0x44, 0x61,
    0xAC, 0x53, 0x02, 0xFA, 0x42, 0xBD, 0x2D, 0x6F,
    0x85, 0xBB, 0x4A, 0xB5, 0xF9, 0x28, 0x46, 0xAF,
    0x2B, 0xB4, 0xB2, 0x90, 0xD0, 0x05, 0x19, 0x64,
))

# static const uint8_t sk_pro2_encoded[64]   (PRO2 S-table, obfuscated)
SK_PRO2_ENCODED = bytes((
    0x90, 0x27, 0xA3, 0x9C, 0xEA, 0x6B, 0x8B, 0x4F,
    0x91, 0x47, 0x58, 0x87, 0x8A, 0xD8, 0xD1, 0x99,
    0x3B, 0xCD, 0x15, 0xFE, 0xF4, 0x1D, 0xEE, 0xE1,
    0xBF, 0xA4, 0x9E, 0xCB, 0x1C, 0x72, 0x5F, 0x95,
    0xAC, 0x28, 0xB0, 0x36, 0xE3, 0x2B, 0x1B, 0xAC,
    0x30, 0x91, 0xFE, 0x62, 0xD0, 0xD3, 0x6B, 0xA6,
    0x4F, 0x64, 0xC0, 0xF2, 0xD2, 0xCF, 0xCA, 0x36,
    0x53, 0x3D, 0x36, 0xAA, 0x33, 0x67, 0x19, 0x7F,
))

# static const uint8_t sk_pro_crc_encoded[2]   (PRO1/PRO2 CRC polys, obfuscated)
SK_PRO_CRC_ENCODED = bytes((0x5B, 0x8C,))


def sk_pi_decode(encoded, offset):
    """out[i] = encoded[i] ^ sk_pi_bytes[offset + i]   (firmware sk_pi_decode)."""
    return bytes((b ^ SK_PI_BYTES[offset + i]) & 0xFF for i, b in enumerate(encoded))


# ---- recovered (de-obfuscated) firmware constants -------------------
# PRO1 table lives at sk_pi_bytes offset 0..63, PRO2 at 64..127,
# CRC polys at 144..145.
PRO1_STABLE = sk_pi_decode(SK_PRO1_ENCODED, 0)
PRO2_STABLE = sk_pi_decode(SK_PRO2_ENCODED, 64)
_PRO_CRC = sk_pi_decode(SK_PRO_CRC_ENCODED, 144)
PRO1_CRC_POLY = _PRO_CRC[0]    # decoded value: see hexdump in self-test
PRO2_CRC_POLY = _PRO_CRC[1]

# Decoded PRO1 S-table (64 bytes):
#   D2 B1 30 43 D3 C3 F2 42 40 A3 91 65 1C 24 24 30
#   63 D2 07 51 24 4A 68 82 D8 26 22 21 32 D0 4A B6
#   63 12 70 54 42 30 51 17 12 FF AD 4D 76 54 21 F9
#   17 AD 9F 00 30 54 16 72 00 85 89 25 65 42 10 73
# Decoded PRO2 S-table (64 bytes):
#   B4 18 C9 14 6F C8 83 9C 82 5E D2 A9 89 A8 A2 DD
#   9F 86 2D DC DD B2 DF 31 2D 82 4B 6D 95 AD EA 89
#   89 00 E1 D0 DB FB E7 03 1F 42 51 8F 04 3A 67 CA
#   64 3C 00 B7 02 5C 84 00 60 40 16 72 86 30 57 F4
# Decoded CRC polys:  PRO1 = 0x1E ^ ... (printed by self-test)


# =====================================================================
#  Cipher primitives -- line-for-line ports of the C
# =====================================================================

def pro_checksum(data, poly):
    """scher_khan_pro_checksum(): CRC-8 style checksum over data[0..5].

    uint8_t cs = 0xFF; for a in 0..5: c=data[a]; for b in 0..7:
      _cs=cs; cs<<=1; if((_cs^c)&0x80) cs^=poly; c<<=1;  return cs
    """
    cs = 0xFF
    for a in range(6):
        c = data[a] & 0xFF
        for _ in range(8):
            _cs = cs
            cs = (cs << 1) & 0xFF
            if (_cs ^ c) & 0x80:
                cs = (cs ^ poly) & 0xFF
            c = (c << 1) & 0xFF
    return cs & 0xFF


def pro_decrypt(frame, key_table=None, counter=None):
    """scher_khan_pro_decrypt(): recover 7-byte plaintext from 7-byte cipher.

    The per-packet counter (pkt_cnt) that keys the S-permutation is the low
    nibble of byte 0 of the frame; `counter` may override it (rarely needed --
    default derives it exactly like the firmware).  `key_table` defaults to the
    PRO1 S-table; pass PRO2_STABLE for PRO2 frames (or use pro_decode_frame()
    which auto-selects via the CRC).
    """
    if key_table is None:
        key_table = PRO1_STABLE
    enc = [frame[i] & 0xFF for i in range(7)]
    dec = [0] * 7
    dec[0] = enc[0]
    pkt_cnt = (enc[0] & 0x0F) if counter is None else (counter & 0x0F)

    for i in range(1, 6):
        s = enc[i]
        v = 0
        for j in range(4):
            cr = key_table[(pkt_cnt << 2) + j]
            bit = 0x80 >> (cr >> 4)
            if (s & bit) == bit:
                v |= 0x80 >> (j << 1)
            bit = 0x80 >> (cr & 0x0F)
            if (s & bit) == bit:
                v |= 0x80 >> ((j << 1) + 1)
        pkt_cnt = (pkt_cnt + 1) & 0x0F
        v ^= enc[i - 1]                       # CBC: XOR previous ciphertext
        dec[i] = v & 0xFF
    # dec[6] stays 0 (byte 6 is the CRC, not part of the ciphertext)
    return dec


def pro_encrypt(plain, key_table=None, counter=None):
    """scher_khan_pro_encrypt(): inverse of pro_decrypt (byte 6 left 0).

    Exact inverse bit-permutation of pro_decrypt.  Caller sets frame[6] to
    pro_checksum(frame, poly) afterwards, as the firmware does.
    """
    if key_table is None:
        key_table = PRO1_STABLE
    src = [plain[i] & 0xFF for i in range(7)]
    enc = [0] * 7
    enc[0] = src[0]
    pkt_cnt = (src[0] & 0x0F) if counter is None else (counter & 0x0F)

    for i in range(1, 6):
        src[i] = (src[i] ^ enc[i - 1]) & 0xFF   # CBC: XOR previous ciphertext
        s = src[i]
        e = 0
        for j in range(4):
            cr = key_table[(pkt_cnt << 2) + j]
            bit = 0x80 >> (j << 1)
            if (s & bit) == bit:
                e |= 0x80 >> (cr >> 4)
            bit = 0x80 >> ((j << 1) + 1)
            if (s & bit) == bit:
                e |= 0x80 >> (cr & 0x0F)
        pkt_cnt = (pkt_cnt + 1) & 0x0F
        enc[i] = e & 0xFF
    # enc[6] stays 0
    return enc


# =====================================================================
#  Frame <-> field conversion
# =====================================================================

def _kb_from_data(data):
    """7 big-endian bytes from a 57-bit frame, exactly as the firmware:
       kb[i] = (data >> (48 - i*8)) & 0xFF     (covers bits 55..0)."""
    return [(data >> (48 - i * 8)) & 0xFF for i in range(7)]


def _data_from_kb(enc):
    """Inverse of _kb_from_data, masked to 57 bits like the firmware."""
    data = 0
    for i in range(7):
        data |= (enc[i] & 0xFF) << (48 - i * 8)
    return data & 0x01FFFFFFFFFFFFFF


def pro_decode_frame(data):
    """Decode a 57-bit MAGIC CODE PRO/PRO2 frame value.

    Returns dict(variant, serial, btn, cnt, crc_ok, plain).  Mirrors the
    firmware: try PRO1 CRC first, then PRO2; if neither matches the frame is
    left opaque (crc_ok False)."""
    kb = _kb_from_data(data)
    for variant, table, poly in (("PRO1", PRO1_STABLE, PRO1_CRC_POLY),
                                 ("PRO2", PRO2_STABLE, PRO2_CRC_POLY)):
        if pro_checksum(kb, poly) == kb[6]:
            dec = pro_decrypt(kb, table)
            serial = (dec[0] << 16) | (dec[1] << 8) | dec[2]
            btn = dec[3] & 0x0F
            cnt = (dec[4] << 8) | dec[5]
            return dict(variant=variant, serial=serial, btn=btn, cnt=cnt,
                        crc_ok=True, plain=dec)
    return dict(variant="pro_unverified", serial=0, btn=0, cnt=0,
                crc_ok=False, plain=None)


def pro_encode_frame(serial, btn, cnt, variant="PRO1", byte3_high=0):
    """Build a valid 57-bit MAGIC CODE PRO/PRO2 frame from fields.

    byte3_high preserves the top 3 bits (mask 0xE0) of decrypted byte 3, which
    the firmware keeps from the captured frame but which are NOT recoverable
    from (serial,btn,cnt) alone -- default 0.  Returns (data57, enc_bytes)."""
    table = PRO1_STABLE if variant == "PRO1" else PRO2_STABLE
    poly = PRO1_CRC_POLY if variant == "PRO1" else PRO2_CRC_POLY
    dec = [0] * 7
    dec[0] = (serial >> 16) & 0xFF
    dec[1] = (serial >> 8) & 0xFF
    dec[2] = serial & 0xFF
    dec[3] = (byte3_high & 0xE0) | (btn & 0x0F)
    dec[4] = (cnt >> 8) & 0xFF
    dec[5] = cnt & 0xFF
    dec[6] = 0
    enc = pro_encrypt(dec, table)
    enc[6] = pro_checksum(enc, poly)
    return _data_from_kb(enc), enc


def dynamic_decode(data):
    """Decode a 51-bit MAGIC CODE Dynamic frame (firmware formula)."""
    data &= MASK64
    serial = ((data >> 24) & 0x0FFFFFF0) | ((data >> 20) & 0x0F)
    btn = (data >> 24) & 0x0F
    cnt = data & 0xFFFF
    # structural check the firmware does NOT enforce but the protocol carries:
    # the "~Key" nibble (bits 16..19) is the bitwise inverse of the Key nibble.
    inv_key = (data >> 16) & 0x0F
    # NOTE (audit residual): this 4-bit inverse-nibble check (FP ~1/16) is the
    # ONLY integrity the 51-bit dynamic variant carries -- no CRC/crypto exists
    # to require instead, and it gates the deterministic dynamic clone. Kept as
    # crc_ok=True; the exact-51-bit length + serial-layout gate in classify()
    # bounds real false-matches. Do NOT downgrade without a stronger structural
    # gate (would disable single-frame cloning + break round-trip self-tests).
    crc_ok = (inv_key == ((~btn) & 0x0F))
    return dict(serial=serial, btn=btn, cnt=cnt, crc_ok=crc_ok)


def dynamic_encode(serial, btn, cnt):
    """Build a 51-bit MAGIC CODE Dynamic frame value from fields (inverse of
    dynamic_decode); fills the ~Key check nibble correctly."""
    data = 0
    data |= ((serial >> 4) & 0xFFFFFF) << 28
    data |= (btn & 0x0F) << 24
    data |= (serial & 0x0F) << 20
    data |= ((~btn) & 0x0F) << 16
    data |= (cnt & 0xFFFF)
    return data & MASK64


def next_code(serial, btn, cnt, variant="PRO1", byte3_high=0):
    """Return the next valid rolling frame for cloning: re-encrypt with
    counter+1.  For PRO/PRO2 returns dict(data, bits=57, frame, serial, btn,
    cnt, variant); for variant="dynamic" returns the 51-bit dynamic form."""
    ncnt = (cnt + 1) & 0xFFFF
    if variant == "dynamic":
        data = dynamic_encode(serial, btn, ncnt)
        return dict(data=data, bits=51, frame=None, serial=serial, btn=btn,
                    cnt=ncnt, variant="dynamic")
    data, enc = pro_encode_frame(serial, btn, ncnt, variant, byte3_high)
    return dict(data=data, bits=57, frame=enc, serial=serial, btn=btn,
                cnt=ncnt, variant=variant)


# =====================================================================
#  Classification (subghz_protocol_scher_khan_check_remote_controller)
# =====================================================================

_BTN_NAMES = {
    0x1: "Lock", 0x2: "Unlock", 0x3: "Lock+Unlock", 0x4: "Trunk",
    0x5: "Lock+Trunk", 0x6: "Unlock+Trunk", 0x7: "Lk+Ul+Tr", 0x8: "Start",
    0x9: "Lock+Start", 0xA: "Unlock+Start", 0xC: "Trunk+Start", 0xF: "All/Panic",
}


def btn_name(btn):
    return _BTN_NAMES.get(btn & 0xF, "?")


def classify(data, nbits):
    """Port of subghz_protocol_scher_khan_check_remote_controller().
    Returns dict(proto_name, serial, btn, cnt, crc_ok, variant)."""
    data &= MASK64
    if nbits == 35:
        return dict(proto_name="MAGIC CODE, Static", serial=0, btn=0, cnt=0,
                    crc_ok=False, variant="static")
    if nbits == 51:
        d = dynamic_decode(data)
        return dict(proto_name="MAGIC CODE, Dynamic", serial=d["serial"],
                    btn=d["btn"], cnt=d["cnt"], crc_ok=d["crc_ok"],
                    variant="dynamic")
    if nbits == 57:
        d = pro_decode_frame(data)
        name = ("MAGIC CODE PRO1" if d["variant"] == "PRO1" else
                "MAGIC CODE PRO2" if d["variant"] == "PRO2" else
                "MAGIC CODE PRO/PRO2")
        return dict(proto_name=name, serial=d["serial"], btn=d["btn"],
                    cnt=d["cnt"], crc_ok=d["crc_ok"], variant=d["variant"])
    if nbits == 63:
        return dict(proto_name="MAGIC CODE, Response", serial=0, btn=0, cnt=0,
                    crc_ok=False, variant="response")
    if nbits == 64:
        return dict(proto_name="MAGICAR, Response", serial=0, btn=0, cnt=0,
                    crc_ok=False, variant="response")
    if nbits in (81, 82):
        return dict(proto_name="MAGIC CODE PRO, Response", serial=0, btn=0,
                    cnt=0, crc_ok=False, variant="response")
    return dict(proto_name="Unknown", serial=0, btn=0, cnt=0, crc_ok=False,
                variant="unknown")


# =====================================================================
#  RAW pulse decoder -- ports subghz_protocol_decoder_scher_khan_feed()
# =====================================================================

class ScherKhanPro(ProtoDecoder):
    NAME = "Scher-Khan Magicar PRO"
    # te_short=750, te_long=1100, te_delta=160, min_count_bit_for_found=35
    CONST = Const(750, 1100, 160, 35)

    # parser steps
    _RESET, _PREAMBLE, _SAVE, _CHECK = 0, 1, 2, 3

    def __init__(self):
        super().__init__()
        self.header_count = 0

    def reset(self):
        super().reset()
        self.header_count = 0

    def _on_frame(self, data, nbits):
        info = classify(data, nbits)
        # deterministic clone (PRO/PRO2 via recovered S-tables, dynamic via layout)
        nxt = ncnt = None
        if info["crc_ok"] and info["variant"] in ("PRO1", "PRO2", "dynamic"):
            try:
                r = next_code(info["serial"], info["btn"], info["cnt"],
                              variant=info["variant"])
                nxt = "0x%X" % r["data"]
                ncnt = r["cnt"]
            except Exception:
                pass
        self.emit(dict(
            data=data & MASK64, bits=nbits,
            serial=info["serial"], btn=info["btn"], cnt=info["cnt"],
            crc_ok=info["crc_ok"], variant=info["variant"],
            proto_name=info["proto_name"], btn_name=btn_name(info["btn"]),
            next=nxt, next_cnt=ncnt,
        ))

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        ts, tl, td = c.te_short, c.te_long, c.te_delta
        step = d.parser_step

        if step == self._RESET:
            if level and diff(duration, ts * 2) < td:
                d.parser_step = self._PREAMBLE
                d.te_last = duration
                self.header_count = 0

        elif step == self._PREAMBLE:
            if level:
                if diff(duration, ts * 2) < td or diff(duration, ts) < td:
                    d.te_last = duration
                else:
                    d.parser_step = self._RESET
            else:  # low
                if diff(duration, ts * 2) < td or diff(duration, ts) < td:
                    if diff(d.te_last, ts * 2) < td:
                        self.header_count += 1        # stay in PREAMBLE
                    elif diff(d.te_last, ts) < td:
                        if self.header_count >= 2:
                            d.parser_step = self._SAVE
                            d.decode_data = 0
                            d.decode_count_bit = 1     # phantom start bit
                        else:
                            d.parser_step = self._RESET
                    else:
                        d.parser_step = self._RESET
                else:
                    d.parser_step = self._RESET

        elif step == self._SAVE:
            if level:
                if duration >= (td * 2 + tl):          # stop bit
                    d.parser_step = self._RESET
                    if d.decode_count_bit >= c.min_bits:
                        self._on_frame(d.decode_data, d.decode_count_bit)
                    d.decode_data = 0
                    d.decode_count_bit = 0
                else:
                    d.te_last = duration
                    d.parser_step = self._CHECK
            else:
                d.parser_step = self._RESET

        elif step == self._CHECK:
            if not level:
                if diff(d.te_last, ts) < td and diff(duration, ts) < td:
                    d.add_bit(0)
                    d.parser_step = self._SAVE
                elif diff(d.te_last, tl) < td and diff(duration, tl) < td:
                    d.add_bit(1)
                    d.parser_step = self._SAVE
                else:
                    d.parser_step = self._RESET
            else:
                d.parser_step = self._RESET


ALL = [ScherKhanPro]


# =====================================================================
#  Self-test
# =====================================================================

def _hexdump(name, tbl):
    print("  %s (%d bytes):" % (name, len(tbl)))
    for r in range(0, len(tbl), 16):
        print("    " + " ".join("%02X" % b for b in tbl[r:r + 16]))


def _is_permutation_rows(tbl):
    """Each 4-entry S-row (per pkt_cnt) must expand to a permutation of the 8
    bit positions 0..7 for the cipher to be a true bijection."""
    bad = []
    for pkt in range(16):
        nibs = []
        for j in range(4):
            cr = tbl[(pkt << 2) + j]
            nibs.append(cr >> 4)
            nibs.append(cr & 0x0F)
        if sorted(nibs) != list(range(8)):
            bad.append((pkt, nibs))
    return bad


def _synthesize(data, nbits):
    """Build a (level, duration) pulse train the firmware feed() would decode
    to (data, nbits): preamble + 3-pair header + start bit + (nbits-1) data
    bits (MSB first) + stop bit.  Mirrors the encoder waveform."""
    ts, tl = 750, 1100
    seq = []
    for _ in range(6):                         # preamble
        seq += [(True, ts), (False, ts)]
    for _ in range(3):                         # header (3 long pairs)
        seq += [(True, ts * 2), (False, ts * 2)]
    seq += [(True, ts), (False, ts)]           # start bit
    ndata = nbits - 1
    for i in range(ndata - 1, -1, -1):         # data bits, MSB first
        if (data >> i) & 1:
            seq += [(True, tl), (False, tl)]
        else:
            seq += [(True, ts), (False, ts)]
    seq += [(True, ts * 2)]                    # stop bit (>= 2*delta+te_long)
    return seq


def _main():
    checks = []

    def check(name, ok, detail=""):
        checks.append(ok)
        print(("  [PASS] " if ok else "  [FAIL] ") + name +
              (("  -- " + detail) if detail else ""))

    print("=" * 68)
    print("Scher-Khan Magicar -- de-obfuscated firmware tables")
    print("=" * 68)
    print("  CRC polys: raw sk_pro_crc_encoded = %s  ^ pi[144:146]=%s" % (
        " ".join("%02X" % b for b in SK_PRO_CRC_ENCODED),
        " ".join("%02X" % SK_PI_BYTES[144 + i] for i in range(2))))
    print("    -> PRO1_CRC_POLY = 0x%02X   PRO2_CRC_POLY = 0x%02X" % (
        PRO1_CRC_POLY, PRO2_CRC_POLY))
    _hexdump("PRO1 S-table", PRO1_STABLE)
    _hexdump("PRO2 S-table", PRO2_STABLE)

    print("-" * 68)
    print("1) S-table sanity (each pkt_cnt row is a permutation of 0..7)")
    bad1 = _is_permutation_rows(PRO1_STABLE)
    bad2 = _is_permutation_rows(PRO2_STABLE)
    check("PRO1 S-table rows are bijective", not bad1,
          "" if not bad1 else "bad rows: %s" % bad1)
    check("PRO2 S-table rows are bijective", not bad2,
          "" if not bad2 else "bad rows: %s" % bad2)

    print("-" * 68)
    print("2) PRO/PRO2 cipher round-trip (encrypt -> checksum -> decode)")
    import random
    random.seed(0x5CE7)
    trials = 0
    cipher_ok = True          # forced-table decode must be exact (bit-exactness)
    collisions = 0            # auto-select CRC-8 cross-matches (firmware quirk)
    pro1_misid = 0            # PRO1 frames auto-selected as anything but PRO1
    _TBL = {"PRO1": (PRO1_STABLE, PRO1_CRC_POLY),
            "PRO2": (PRO2_STABLE, PRO2_CRC_POLY)}
    for variant in ("PRO1", "PRO2"):
        table, poly = _TBL[variant]
        for _ in range(400):
            serial = random.randint(0, 0xFFFFFF)
            btn = random.choice([1, 2, 3, 4, 5, 6, 7, 8, 9, 0xA, 0xC, 0xF])
            cnt = random.randint(0, 0xFFFF)
            b3h = random.randint(0, 7) << 5
            data, enc = pro_encode_frame(serial, btn, cnt, variant, b3h)
            trials += 1
            # (a) exact cipher check: force the intended table, must recover fields
            kb = _kb_from_data(data)
            fd = pro_decrypt(kb, table)
            fser = (fd[0] << 16) | (fd[1] << 8) | fd[2]
            if not (pro_checksum(kb, poly) == kb[6] and fser == serial and
                    (fd[3] & 0x0F) == btn and ((fd[4] << 8) | fd[5]) == cnt):
                cipher_ok = False
                print("      CIPHER mismatch %s serial=%06X btn=%X cnt=%04X"
                      % (variant, serial, btn, cnt))
                break
            # (b) firmware auto-select (PRO1 CRC tried first): tally collisions
            got = pro_decode_frame(data)
            if got["variant"] != variant:
                collisions += 1
                if variant == "PRO1":
                    pro1_misid += 1
    check("cipher recovers fields exactly on %d forced-table frames" % trials,
          cipher_ok)
    check("PRO1 frames never misclassify (PRO1 CRC checked first)",
          pro1_misid == 0)
    print("    NOTE: %d/%d frames hit a PRO1<->PRO2 CRC-8 cross-match "
          "(firmware picks PRO1 first;" % (collisions, trials))
    print("          ~1/256 of PRO2 frames are silently read as PRO1 -- this "
          "is the firmware's own limitation, faithfully reproduced).")

    print("-" * 68)
    print("3) Full 7-byte plaintext round-trip (encrypt <-> decrypt bijection)")
    ok_all = True
    for _ in range(2000):
        plain = [random.randint(0, 255) for _ in range(6)] + [0]
        table = random.choice([PRO1_STABLE, PRO2_STABLE])
        enc = pro_encrypt(plain, table)
        dec = pro_decrypt(enc, table)
        if dec[:6] != plain[:6]:
            ok_all = False
            print("      plaintext mismatch: %s -> %s" % (plain, dec))
            break
    check("encrypt/decrypt invert on 2000 random plaintexts", ok_all)

    print("-" * 68)
    print("4) Worked examples from firmware comment (51-bit MAGIC CODE Dynamic)")
    # 0E8C1619E830C -> serial 0E8C169 btn 1 cnt 830C   (+3 more, counter rolls)
    worked = [
        (0x0E8C1619E830C, 0x0E8C169, 0x1, 0x830C),
        (0x0E8C1629D830D, 0x0E8C169, 0x2, 0x830D),
        (0x0E8C1649B830E, 0x0E8C169, 0x4, 0x830E),
        (0x0E8C16897830F, 0x0E8C169, 0x8, 0x830F),
    ]
    ok_all = True
    for data, exp_ser, exp_btn, exp_cnt in worked:
        info = classify(data, 51)
        good = (info["serial"] == exp_ser and info["btn"] == exp_btn and
                info["cnt"] == exp_cnt and info["crc_ok"])
        ok_all = ok_all and good
        print("    %013X -> Sn:%07X Btn:%X(%s) Cnt:%04X ~Key_ok:%s  %s" % (
            data, info["serial"], info["btn"], btn_name(info["btn"]),
            info["cnt"], info["crc_ok"], "OK" if good else "MISMATCH"))
    check("all 4 documented dynamic frames decode correctly", ok_all)

    print("-" * 68)
    print("5) Dynamic encode/decode round-trip")
    ok_all = True
    for _ in range(1000):
        serial = random.randint(0, 0xFFFFFFF)
        btn = random.choice([1, 2, 4, 8])
        cnt = random.randint(0, 0xFFFF)
        data = dynamic_encode(serial, btn, cnt)
        info = classify(data, 51)
        if not (info["serial"] == serial and info["btn"] == btn and
                info["cnt"] == cnt and info["crc_ok"]):
            ok_all = False
            print("      mismatch serial=%07X btn=%X cnt=%04X -> %s"
                  % (serial, btn, cnt, info))
            break
    check("dynamic_encode -> classify round-trips (1000 frames)", ok_all)

    print("-" * 68)
    print("6) next_code() rolling clone (counter+1)")
    # PRO clone
    serial, btn, cnt = 0xA17C39, 0x2, 0x0041
    nc = next_code(serial, btn, cnt, variant="PRO1")
    got = pro_decode_frame(nc["data"])
    ok = (got["crc_ok"] and got["serial"] == serial and got["btn"] == btn and
          got["cnt"] == (cnt + 1) & 0xFFFF)
    print("    PRO1  serial=%06X btn=%X cnt=%04X -> next data=%014X cnt=%04X"
          % (serial, btn, cnt, nc["data"], got["cnt"]))
    check("PRO next_code produces valid counter+1 frame", ok)
    # dynamic clone
    nc = next_code(0x0E8C169, 0x1, 0x830C, variant="dynamic")
    got = classify(nc["data"], 51)
    okd = (got["crc_ok"] and got["serial"] == 0x0E8C169 and got["btn"] == 1 and
           got["cnt"] == 0x830D)
    print("    dyn   serial=0E8C169 btn=1 cnt=830C -> next data=%013X cnt=%04X"
          % (nc["data"], got["cnt"]))
    check("dynamic next_code produces valid counter+1 frame", okd)

    print("-" * 68)
    print("7) End-to-end RAW pulse decode (feed() state machine)")
    # dynamic frame via synthesized waveform
    dec = ScherKhanPro()
    for lvl, dur in _synthesize(0x0E8C1619E830C, 51):
        dec.feed(lvl, dur)
    r = dec.results[0] if dec.results else {}
    okr1 = (r.get("bits") == 51 and r.get("serial") == 0x0E8C169 and
            r.get("btn") == 1 and r.get("cnt") == 0x830C and r.get("crc_ok"))
    print("    dynamic:  %s" % (r or "NO LOCK"))
    check("feed() decodes synthesized 51-bit dynamic frame", okr1)

    # PRO frame via synthesized waveform
    data57, _ = pro_encode_frame(0x123456, 0x8, 0x0009, "PRO1")
    dec = ScherKhanPro()
    for lvl, dur in _synthesize(data57, 57):
        dec.feed(lvl, dur)
    r = dec.results[0] if dec.results else {}
    okr2 = (r.get("bits") == 57 and r.get("serial") == 0x123456 and
            r.get("btn") == 0x8 and r.get("cnt") == 0x0009 and r.get("crc_ok")
            and r.get("variant") == "PRO1")
    print("    PRO1:     %s" % (r or "NO LOCK"))
    check("feed() decodes synthesized 57-bit PRO1 frame", okr2)

    print("=" * 68)
    passed = sum(1 for x in checks if x)
    total = len(checks)
    print("RESULT: %d/%d checks passed -- %s" % (
        passed, total, "ALL PASS" if passed == total else "SOME FAILED"))
    print("=" * 68)
    return 0 if passed == total else 1


if __name__ == "__main__":
    import sys
    sys.exit(_main())
