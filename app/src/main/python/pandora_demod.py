# Pandora D-605 / MAX .sub demodulator.
#
# FlipperRf.rx() returns a Flipper RAW .sub (OOK edge durations) captured off the
# air. This recovers the 9 on-air data bytes that PandoraNative.decode() consumes.
#
# Framing (mirror of PandoraNative.pulses(), inverted):
#   - Manchester, MSB-first:  bit 1 -> half-bits [1,0],  bit 0 -> half-bits [0,1]
#   - D-605 preamble: 0xAA, single '1', 0xAA   then 9 data bytes
#   - MAX   preamble: 14x 0xFF, 0xD7 sync       then 9 data bytes
#   - RAW_Data is run-length: each signed duration = (n * halfbit), sign = level.
#
# A real capture has timing jitter and an unknown half-bit period, so we estimate
# the half-bit, oversample to a half-bit symbol stream, Manchester-decode under
# both bit phases, and scan the resulting bitstream for the sync, then read 9 bytes.

import json

D605, MAX = 0, 1


def parse_sub(text):
    """Flipper RAW .sub text -> list of signed int durations (us)."""
    out = []
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("RAW_Data:"):
            for tok in line[len("RAW_Data:"):].split():
                try:
                    out.append(int(tok))
                except ValueError:
                    pass
    return out


def _estimate_halfbit(durs):
    """Smallest pulse width ~= the half-bit period. Robust to a few outliers."""
    w = sorted(abs(d) for d in durs if abs(d) > 0)
    if not w:
        return 0
    # the short-pulse cluster: values within 1.6x of the minimum
    lo = w[0]
    cluster = [x for x in w if x <= lo * 1.6]
    cluster.sort()
    return cluster[len(cluster) // 2]  # median of the short cluster


def _halfbit_stream(durs, hb):
    """Expand run-length pulses into a per-half-bit level sequence."""
    sym = []
    for d in durs:
        n = max(1, int(round(abs(d) / float(hb))))
        n = min(n, 64)  # guard against a gap being read as thousands of bits
        lvl = 1 if d > 0 else 0
        sym.extend([lvl] * n)
    return sym


def _manchester_bits(sym, phase):
    """Decode half-bit symbols to data bits under a bit-pair phase (0 or 1).
    Returns (bits, errors). 1 = [1,0], 0 = [0,1]; equal pair = error (skip)."""
    bits = []
    errs = 0
    i = phase
    while i + 1 < len(sym):
        a, b = sym[i], sym[i + 1]
        if a == 1 and b == 0:
            bits.append(1)
        elif a == 0 and b == 1:
            bits.append(0)
        else:
            errs += 1  # mid-bit without transition: alignment / glitch
        i += 2
    return bits, errs


def _bits_to_bytes(bits):
    """MSB-first pack; drop a trailing partial byte."""
    out = bytearray()
    for i in range(0, (len(bits) // 8) * 8, 8):
        v = 0
        for b in bits[i:i + 8]:
            v = (v << 1) | b
        out.append(v)
    return bytes(out)


def _find_frames_bitwise(bits, model):
    """Scan a bitstream for the model's sync, then read 72 bits (9 bytes)."""
    frames = []
    if model == MAX:
        # sync byte 0xD7 (follows the 0xFF run); read 9 bytes after it, MSB-first.
        sync = [1, 1, 0, 1, 0, 1, 1, 1]
        n = len(bits)
        i = 0
        while i + 8 + 72 <= n:
            if bits[i:i + 8] == sync:
                frames.append(_bits_to_bytes(bits[i + 8:i + 8 + 72]))
                i += 8 + 72
            else:
                i += 1
    else:
        # D-605: preamble 0xAA, '1', 0xAA -> the distinctive tail is ...0xAA then data.
        # Anchor on the 17-bit preamble (10101010 1 10101010); data follows.
        pre = [1, 0, 1, 0, 1, 0, 1, 0, 1, 1, 0, 1, 0, 1, 0, 1, 0]
        n = len(bits)
        i = 0
        while i + len(pre) + 72 <= n:
            if bits[i:i + len(pre)] == pre:
                frames.append(_bits_to_bytes(bits[i + len(pre):i + len(pre) + 72]))
                i += len(pre) + 72
            else:
                i += 1
    return frames


def demod(text, model):
    """RAW .sub text -> list of unique candidate 9-byte on-air frames (as bytes)."""
    durs = parse_sub(text)
    hb = _estimate_halfbit(durs)
    if hb <= 0:
        return []
    sym = _halfbit_stream(durs, hb)
    seen, frames = set(), []
    for phase in (0, 1):
        bits, _ = _manchester_bits(sym, phase)
        for f in _find_frames_bitwise(bits, model):
            if len(f) == 9 and f not in seen:
                seen.add(f)
                frames.append(f)
    return frames


def demod_json(text, model):
    """Chaquopy entry: returns JSON list of hex strings, one per candidate frame."""
    return json.dumps([f.hex().upper() for f in demod(text, int(model))])
