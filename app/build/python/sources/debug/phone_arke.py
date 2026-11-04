"""Phone bridge for the arke automotive/gate decode+clone engine (Chaquopy).
   Kotlin calls decode_text(sub_text) -> JSON list of decoded frames across ALL makes.
   The whole arke suite (cars + gates + pp_* clone ciphers) runs on-device."""
import json, tempfile, os
import arke_all
import arke_core

# load the full registry once
try:
    _DECODERS = arke_all.ALL_DECODERS
except Exception:
    _DECODERS = []

def decoder_count():
    return len(_DECODERS)

def families():
    """Distinct decoder names for the protocol picker."""
    try:
        return json.dumps(sorted({d.NAME for d in _DECODERS}))
    except Exception:
        return json.dumps([])

def _clean(v):
    # make values JSON-safe
    if isinstance(v, (bytes, bytearray)):
        return v.hex()
    return v

def decode_text(sub_text):
    """Decode a Flipper RAW .sub (text) through every decoder; return JSON frames."""
    fd, path = tempfile.mkstemp(suffix=".sub")
    raws = []
    try:
        with os.fdopen(fd, "w") as f:
            f.write(sub_text)
        res = arke_core.run_decoders(path, _DECODERS)
        try:
            _, raws = arke_core.parse_sub(path)
        except Exception:
            raws = []
    except Exception as e:
        try: os.remove(path)
        except Exception: pass
        return json.dumps({"error": str(e)})
    finally:
        try: os.remove(path)
        except Exception: pass

    out = []
    for name, frames in res.items():
        for fr in frames:
            row = {
                "make": name,
                "serial": _clean(fr.get("serial")),
                "cnt": _clean(fr.get("cnt")),
                "btn": _clean(fr.get("btn")),
                "btn_name": _clean(fr.get("btn_name")),
                "next": _clean(fr.get("next")),
                "next_cnt": _clean(fr.get("next_cnt")),
                "crc_ok": _clean(fr.get("crc_ok")),
                "gate": _clean(fr.get("gate")),
            }
            if isinstance(fr.get("serial"), int):
                row["serial_hex"] = "%X" % fr["serial"]
            out.append(row)

    # also run the fixed-code gate / garage decoders (CAME/PT2262/EV1527/Holtek)
    try:
        import extra_protocols
        for h in extra_protocols.decode(raws):
            fields = {k: v for k, v in h.get("fields", [])}
            out.append({
                "make": h.get("protocol", "fixed-code"),
                "serial_hex": (fields.get("code") or "").lstrip("0x"),
                "btn_name": fields.get("button") or fields.get("data"),
                "bits": h.get("bits"),
                "gate": "fixed-code",
            })
    except Exception:
        pass
    return json.dumps(out)

def decode_sample():
    """Decode a bundled VAG capture — proves the full pipeline on-device."""
    try:
        import sample_vag
        return decode_text(sample_vag.SUB)
    except Exception as e:
        return json.dumps({"error": str(e)})

def self_test():
    return json.dumps({"decoders": len(_DECODERS)})


# ---- fob bridge (Kotlin ArkeEngine.fobInfo/forge call these) ----
import fob_engine


def fob_info(variant):
    """JSON for the fob UI: route / buttons / freq / preset for a decoded variant."""
    return json.dumps(fob_engine.fob_info(variant))


def forge(variant, serial_hex, counter, button_code, extra=None):
    """Forge a frame for (variant, serial, counter, button) -> JSON {route,sub[,protocol]} | {error}."""
    return json.dumps(fob_engine.forge(variant, serial_hex, counter, button_code, extra))
