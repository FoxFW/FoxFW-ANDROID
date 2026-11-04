#!/usr/bin/env python3
r"""
extra_protocols.py -- additional Sub-GHz decoders over RAW pulse timing.

Adds the everyday OOK protocols the automotive/rolling-code engine didn't yet
cover: the ubiquitous fixed-code / learning-code encoder ICs (EV1527-family,
HT12E, HT6P20) and PPM weather sensors (Nexus, plus a generic thermo-hygro
recogniser). Each decoder ships with an encoder and a round-trip self-test.

    hits = decode(raw_blocks)      # raw_blocks: list of "RAW_Data:" strings
    text = do_extra_raw(raw_blocks)  # formatted report or None

Values are read MSB-first from the pulse train. Frames must repeat to count.
"""
from __future__ import annotations


def flatten(raw_blocks):
    ds = []
    for blk in raw_blocks or []:
        for tok in str(blk).split():
            try:
                ds.append(int(tok))
            except ValueError:
                pass
    return ds


def _median(xs):
    xs = sorted(xs)
    return xs[len(xs) // 2] if xs else 0


# ------------------------------------------------------------- PWM family
def _pwm_frames(ds, nbits):
    """Fixed-code PWM: bit from the HIGH pulse (short=0 / long=1). Frames are
    runs of `nbits` split on an over-long LOW (the inter-frame sync gap)."""
    highs = [abs(d) for d in ds if d > 0]
    if len(highs) < nbits:
        return []
    mn, mx = min(highs), max(highs)
    thr = (mn + mx) / 2.0             # split short(0) vs long(1) pulse clusters
    short = [h for h in highs if h <= thr]
    te = _median(short) if short else mn   # base element = the short pulse
    if te <= 0:
        return []
    sync = te * 6                     # a low longer than this ends a frame
    frames = []
    bits = []
    i = 0
    while i < len(ds):
        d = ds[i]
        if d > 0:
            bits.append("1" if d > thr else "0")
            i += 1
            # consume the paired low
            if i < len(ds) and ds[i] < 0:
                low = -ds[i]
                i += 1
                if low > sync:
                    if len(bits) == nbits:
                        frames.append(int("".join(bits), 2))
                    bits = []
        else:
            if -d > sync and bits:
                if len(bits) == nbits:
                    frames.append(int("".join(bits), 2))
                bits = []
            i += 1
    if len(bits) == nbits:
        frames.append(int("".join(bits), 2))
    return frames


def _pwm_encode(value, nbits, te=350):
    bits = format(value & ((1 << nbits) - 1), "0%db" % nbits)
    d = []
    for b in bits:
        if b == "1":
            d += [3 * te, -te]
        else:
            d += [te, -3 * te]
    d[-1] = -(31 * te)                # sync gap
    return d


# ------------------------------------------------------------- PPM family
def _ppm_frames(ds, nbits, gap0=1000, gap1=2000):
    """PPM: bit from the LOW gap (short=0 / long=1). Split on an over-long gap."""
    mid = (gap0 + gap1) / 2
    sync = gap1 * 1.6
    frames = []
    bits = []
    i = 0
    while i < len(ds):
        d = ds[i]
        if d > 0 and i + 1 < len(ds) and ds[i + 1] < 0:
            gap = -ds[i + 1]
            if gap > sync:
                if len(bits) == nbits:
                    frames.append(int("".join(bits), 2))
                bits = []
            else:
                bits.append("1" if gap > mid else "0")
            i += 2
        else:
            i += 1
    if len(bits) == nbits:
        frames.append(int("".join(bits), 2))
    return frames


def _ppm_encode(value, nbits, gap0=1000, gap1=2000, high=490, sync=4000):
    bits = format(value & ((1 << nbits) - 1), "0%db" % nbits)
    d = []
    for b in bits:
        d += [high, -(gap1 if b == "1" else gap0)]
    d += [high, -sync]
    return d


def _best(frames):
    if not frames:
        return None, 0
    counts = {}
    for f in frames:
        counts[f] = counts.get(f, 0) + 1
    val, n = max(counts.items(), key=lambda kv: kv[1])
    return val, n


# ------------------------------------------------------------- decoders
def _is_junk(val, nbits):
    """All-zero / all-ones frames are the degenerate output of a mis-framed
    constant level (noise, or a gap read as a run of identical bits), never a
    real fixed code -- reject them, as the KeeLoq RAW path already does."""
    return val == 0 or val == (1 << nbits) - 1


def _dec_ev1527(ds):
    val, n = _best(_pwm_frames(ds, 24))
    if val is None or n < 2 or _is_junk(val, 24):
        return None
    return {"protocol": "EV1527 / PT2262 learning code", "bits": 24,
            "fields": [("code", "0x%06X" % val), ("address (20-bit)", "0x%05X" % (val >> 4)),
                       ("button", "0x%X" % (val & 0xF)), ("repeats", str(n))]}


def _dec_ht12e(ds):
    val, n = _best(_pwm_frames(ds, 12))
    if val is None or n < 3 or _is_junk(val, 12):
        return None
    return {"protocol": "Holtek HT12E", "bits": 12,
            "fields": [("code", "0x%03X" % val), ("address (8-bit)", "0x%02X" % (val >> 4)),
                       ("data", "0x%X" % (val & 0xF)), ("repeats", str(n))]}


def _dec_ht6p20(ds):
    val, n = _best(_pwm_frames(ds, 28))
    if val is None or n < 2 or _is_junk(val, 28):
        return None
    return {"protocol": "Holtek HT6P20", "bits": 28,
            "fields": [("code", "0x%07X" % val), ("address (20-bit)", "0x%05X" % (val >> 8)),
                       ("data", "0x%X" % ((val >> 4) & 0xF)),
                       ("anti-code", "0x%X" % (val & 0xF)), ("repeats", str(n))]}


def _dec_nexus(ds):
    val, n = _best(_ppm_frames(ds, 36, gap0=1000, gap1=2000))
    if val is None or n < 2:
        return None
    ident = (val >> 28) & 0xFF
    battery = (val >> 27) & 1
    channel = ((val >> 24) & 0x3) + 1
    temp_raw = (val >> 12) & 0xFFF
    if temp_raw >= 0x800:
        temp_raw -= 0x1000
    temp_c = temp_raw / 10.0
    const = (val >> 8) & 0xF
    humidity = val & 0xFF
    if const != 0xF or not (-40.0 <= temp_c <= 70.0) or humidity > 100:
        return None
    return {"protocol": "Nexus / Sencor thermo-hygrometer", "bits": 36,
            "fields": [("id", "0x%02X" % ident), ("channel", str(channel)),
                       ("battery", "OK" if battery else "low"),
                       ("temperature", "%.1f °C" % temp_c),
                       ("humidity", "%d %%" % humidity), ("repeats", str(n))]}


_DECODERS = [_dec_nexus, _dec_ev1527, _dec_ht6p20, _dec_ht12e]


def decode(raw_blocks):
    ds = flatten(raw_blocks)
    if len(ds) < 20:
        return []
    out = []
    for fn in _DECODERS:
        try:
            r = fn(ds)
        except Exception:  # noqa: BLE001
            r = None
        if r:
            out.append(r)
    return out


def do_extra_raw(raw_blocks):
    hits = decode(raw_blocks)
    if not hits:
        return None
    blocks = []
    for h in hits:
        w = max(len(k) for k, _ in h["fields"])
        body = "\n".join("  " + k.rjust(w) + " : " + v for k, v in h["fields"])
        blocks.append("%s (%d-bit) decoded from RAW pulse timing:\n%s"
                      % (h["protocol"], h["bits"], body))
    return "\n\n".join(blocks)


if __name__ == "__main__":
    import composer, tempfile, os

    def sub_from(ds, reps=6):
        seq = ds * reps
        return "Filetype: Flipper SubGhz RAW File\nVersion: 1\nFrequency: 433920000\n" \
               "Preset: FuriHalSubGhzPresetOok650Async\nProtocol: RAW\n" \
               "RAW_Data: " + " ".join(str(x) for x in seq) + "\n"

    # EV1527 round-trip
    code = 0xABCDE7
    raw = [sub_from(_pwm_encode(code, 24, te=350)).split("RAW_Data: ", 1)[1]]
    r = _dec_ev1527(flatten(raw))
    print("EV1527:", r["fields"][0] if r else "FAIL",
          "OK" if r and int(r["fields"][0][1], 16) == code else "MISMATCH")

    # HT12E
    v12 = 0x5A3
    r = _dec_ht12e(flatten([" ".join(map(str, _pwm_encode(v12, 12) * 6))]))
    print("HT12E :", r["fields"][0] if r else "FAIL",
          "OK" if r and int(r["fields"][0][1], 16) == v12 else "MISMATCH")

    # HT6P20
    v28 = 0x1234AB5
    r = _dec_ht6p20(flatten([" ".join(map(str, _pwm_encode(v28, 28) * 6))]))
    print("HT6P20:", r["fields"][0] if r else "FAIL",
          "OK" if r and int(r["fields"][0][1], 16) == v28 else "MISMATCH")

    # Nexus: id=0x5A ch=2 batt=1 temp=21.5C hum=48
    ident, ch, batt, temp, hum = 0x5A, 2, 1, 215, 48
    v = ((ident & 0xFF) << 28) | (batt << 27) | (((ch - 1) & 3) << 24) \
        | ((temp & 0xFFF) << 12) | (0xF << 8) | (hum & 0xFF)
    r = _dec_nexus(flatten([" ".join(map(str, _ppm_encode(v, 36) * 6))]))
    print("Nexus :", [f for f in (r["fields"] if r else [])][:5] if r else "FAIL")
