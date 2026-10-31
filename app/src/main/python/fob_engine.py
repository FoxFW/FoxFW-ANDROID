"""Fob variant registry + fob_info + forge.

For a decoded variant (the frame's `make` string):
  - fob_info(variant) -> what the fob UI needs: has_cipher, route, buttons, freq/preset
  - forge(variant, serial_hex, counter, button_code, extra) -> a Flipper .sub (or {error})

route:
  "A"      = phone renders RAW pulses into a RAW .sub (pp_* ciphers + fixed-code gates)
  "B"      = Flipper renders a keyed protocol .sub via its own encoder (KIA V6; see B3)
  "replay" = no cipher/renderer on the phone -> replay the capture only

Only families with a verified pulse renderer AND counter recovery from one capture are
forge targets. Scher-Khan (no renderer), Honda V2 (needs a capture signature) and KIA V0/V1/V7
are replay here; KIA V6 is Route B (forge implemented in B3). Keep this module furi-free and
stdlib-only (Chaquopy constraint).
"""
import json
import re

# ---- per-family button maps (code -> label), using each decoder's real button values ----
_BTN_PORSCHE = [{"code": 1, "label": "Unlock"}, {"code": 2, "label": "Lock"},
                {"code": 3, "label": "Trunk"}, {"code": 4, "label": "Panic"}]
_BTN_HONDA_V1 = [{"code": 0, "label": "Unlock"}, {"code": 8, "label": "Lock"},
                 {"code": 9, "label": "Trunk"}, {"code": 10, "label": "Panic"}]
_BTN_MAZDA = [{"code": 1, "label": "Lock"}, {"code": 2, "label": "Unlock"},
              {"code": 4, "label": "Trunk"}, {"code": 8, "label": "Remote"}]
_BTN_RENAULT = [{"code": 1, "label": "Unlock"}, {"code": 2, "label": "Lock"},
                {"code": 3, "label": "Trunk"}, {"code": 4, "label": "Panic"}]
_BTN_SHERIFF = [{"code": 0x10, "label": "Lock"}, {"code": 0x20, "label": "Unlock"},
                {"code": 0x40, "label": "Trunk"}, {"code": 0x80, "label": "Panic"}]
_BTN_KIA = [{"code": 1, "label": "Unlock"}, {"code": 2, "label": "Lock"},
            {"code": 3, "label": "Trunk"}, {"code": 4, "label": "Panic"}]
_GATE_BTNS = [{"code": 1, "label": "Send"}]

_AM650 = "AM650"

_REGISTRY = {
    "porsche":    dict(route="A", has_cipher=True, buttons=_BTN_PORSCHE, freq_hz=433920000, preset=_AM650),
    "honda_v1":   dict(route="A", has_cipher=True, buttons=_BTN_HONDA_V1, freq_hz=433920000, preset=_AM650),
    "mazda_pp":   dict(route="A", has_cipher=True, buttons=_BTN_MAZDA,   freq_hz=433920000, preset=_AM650),
    "sheriff":    dict(route="A", has_cipher=True, buttons=_BTN_SHERIFF, freq_hz=433920000, preset=_AM650),
    "kia_v6":     dict(route="B", has_cipher=True, buttons=_BTN_KIA,     freq_hz=433920000, preset=_AM650, fw_protocol="KIA"),
    # fixed-code gates (Route A; the "code" is the captured fixed code)
    "came":        dict(route="A", has_cipher=True, buttons=_GATE_BTNS, freq_hz=433920000, preset=_AM650, gate=True),
    "nice":        dict(route="A", has_cipher=True, buttons=_GATE_BTNS, freq_hz=433920000, preset=_AM650, gate=True),
    "holtek":      dict(route="A", has_cipher=True, buttons=_GATE_BTNS, freq_hz=433920000, preset=_AM650, gate=True),
    "princeton":   dict(route="A", has_cipher=True, buttons=_GATE_BTNS, freq_hz=433920000, preset=_AM650, gate=True),
    "linear":      dict(route="A", has_cipher=True, buttons=_GATE_BTNS, freq_hz=433920000, preset=_AM650, gate=True),
    "chamberlain": dict(route="A", has_cipher=True, buttons=_GATE_BTNS, freq_hz=433920000, preset=_AM650, gate=True),
}

# phone variant key -> firmware ProtoPirate protocol name (Route B .sub "Protocol:")
_FW_PROTOCOL = {"kia_v6": "Kia V6"}

_GATE_BARRIERS = {
    "came": "CAME 12-bit", "nice": "NICE FLO 12-bit", "holtek": "Holtek HT12E 12-bit",
    "princeton": "Princeton 12-bit", "linear": "Linear 10-bit", "chamberlain": "Chamberlain 9-bit",
}

_PRESET_FULL = {
    "AM270": "FuriHalSubGhzPresetOok270Async", "AM650": "FuriHalSubGhzPresetOok650Async",
    "FM238": "FuriHalSubGhzPreset2FSKDev238Async", "FM476": "FuriHalSubGhzPreset2FSKDev476Async",
}

_REPLAY = dict(route="replay", has_cipher=False, buttons=[], freq_hz=433920000, preset=_AM650)


def _match(variant):
    """Map a decoder NAME / make string to a canonical registry key, or None (=replay)."""
    s = (variant or "").strip().lower()
    if not s:
        return None
    if "porsche" in s:
        return "porsche"
    if "honda" in s and re.search(r"\bv1\b", s):
        return "honda_v1"          # \bv1\b so "Honda V10" does NOT collide; V2 needs a signature -> replay
    if "mazda" in s and "(pp)" in s:
        return "mazda_pp"
    # Renault V0: cipher + build_key exist, but render_pulses does not round-trip
    # through the decoder yet -> replay for now (deferred-encoder work).
    if "sheriff" in s:
        return "sheriff"
    # Scher-Khan: cipher exists but no phone pulse renderer -> replay (Route-B candidate later)
    if "kia" in s:
        if re.search(r"\bv7\b", s):
            return None
        if re.search(r"\bv6\b", s) or s == "kia":
            return "kia_v6"
        return None
    for g in ("came", "nice", "holtek", "princeton", "linear", "chamberlain"):
        if g in s:
            return g
    return None


def fob_info(variant):
    key = _match(variant)
    spec = _REGISTRY.get(key) if key else None
    out = dict(spec) if spec is not None else dict(_REPLAY, buttons=[])
    out.setdefault("custom_regs", None)
    out.setdefault("gate", False)
    out.setdefault("fw_protocol", None)
    return out


# --------------------------- forge ---------------------------
def _sub_from_signed(ds, freq_hz, preset_name):
    head = ("Filetype: Flipper SubGhz RAW File\nVersion: 1\nFrequency: %d\n"
            "Preset: %s\nProtocol: RAW\n" % (freq_hz, _PRESET_FULL.get(preset_name, _PRESET_FULL["AM650"])))
    parts = [head]
    for i in range(0, len(ds), 512):
        parts.append("RAW_Data: " + " ".join(str(int(x)) for x in ds[i:i + 512]) + "\n")
    return "".join(parts)


def _tuples_to_signed(pairs):
    """(level_bool, us) -> signed durations (+high / -low), merging adjacent same-level
    runs into one duration (as a real RF capture appears, and as the RAW decoders expect)."""
    out = []
    for lvl, dur in pairs:
        s = int(dur) if lvl else -int(dur)
        if out and (out[-1] > 0) == bool(lvl):
            out[-1] += s
        else:
            out.append(s)
    return out


def forge(variant, serial_hex, counter, button_code, extra=None):
    key = _match(variant)
    fi = fob_info(variant)
    if fi["route"] == "replay" or not fi["has_cipher"]:
        return {"error": "no phone cipher/renderer for '%s' — replay only" % variant}
    serial = int(serial_hex, 16) if isinstance(serial_hex, str) else int(serial_hex)
    c = int(counter)
    b = int(button_code)
    freq, preset = fi["freq_hz"], fi["preset"]

    if key == "porsche":
        import pp_porsche
        pkt = pp_porsche.compute_frame(serial & 0xFFFFFF, (c - 1) & 0xFFFF, b & 0x0F)  # +1 internal -> tx == c
        return {"route": "A", "sub": _sub_from_signed(pp_porsche.frame_to_pulses(pkt), freq, preset)}
    if key == "honda_v1":
        import pp_honda_mazda as h
        ds = _tuples_to_signed(h._honda_v1_build_upload(h.honda_v1_encode(serial, b, c)))
        return {"route": "A", "sub": _sub_from_signed(ds, freq, preset)}
    if key == "mazda_pp":
        import pp_honda_mazda as h
        ds = _tuples_to_signed(h._mazda_v0_build_upload(h.mazda_v0_encode(serial, b, c)))
        return {"route": "A", "sub": _sub_from_signed(ds, freq, preset)}
    if key == "sheriff":
        import pp_sheriff as s
        model = (extra or {}).get("model", list(s.MODELS)[0])
        ds = s.frame_to_durations(s.encode(model, serial, b, c & 0xFFFF))
        return {"route": "A", "sub": _sub_from_signed(ds, freq, preset)}
    if key in _GATE_BARRIERS:
        import barriers
        out = barriers.one_sub(_GATE_BARRIERS[key], serial)  # serial_hex carries the fixed code
        if out.lstrip().startswith("{"):
            return {"error": "gate forge failed"}
        return {"route": "A", "sub": out}
    if key == "kia_v6":
        import arke_kia67_lr as k
        fx = int((extra or {}).get("fx", 0)) & 0xFF
        part1, part2, part3 = k.kia_v6_build_frame(fx, serial & 0xFFFFFF, b & 0x0F, c & 0xFFFFFFFF)
        key_bytes = [(part1 >> (56 - 8 * i)) & 0xFF for i in range(8)]   # stored_part1, MSB-first
        key_hex = " ".join("%02X" % x for x in key_bytes)
        preset_full = _PRESET_FULL.get(preset, _PRESET_FULL["AM650"])
        sub = (
            "Filetype: Flipper SubGhz Key File\nVersion: 1\n"
            "Frequency: %d\nPreset: %s\nProtocol: %s\nBit: 144\n"
            "Key: %s\nSerial: %d\nBtn: %d\nCnt: %d\n"
            "Key_2: %d\nKey_3: %d\nKey_4: %d\nFx: %d\n"
            % (freq, preset_full, _FW_PROTOCOL["kia_v6"], key_hex,
               serial & 0xFFFFFF, b & 0x0F, c & 0xFFFFFFFF,
               part2 & 0xFFFFFFFF, (part2 >> 32) & 0xFFFFFFFF, part3 & 0xFFFF, fx)
        )
        return {"route": "B", "sub": sub, "protocol": _FW_PROTOCOL["kia_v6"]}
    return {"error": "forge not implemented for '%s'" % variant}
