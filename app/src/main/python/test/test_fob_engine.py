"""Host tests for fob_engine (run with: python3 test_fob_engine.py). No pytest dep."""
import os, sys, traceback
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import json
import fob_engine
import phone_arke


def test_fob_info_porsche_is_route_a_with_buttons():
    fi = fob_engine.fob_info("Porsche")
    assert fi["has_cipher"] is True and fi["route"] == "A", fi
    assert {b["label"] for b in fi["buttons"]} >= {"Lock", "Unlock"}, fi["buttons"]
    assert fi["freq_hz"] == 433920000, fi
    assert fi["preset"] in ("AM650", "AM270", "FM238", "FM476"), fi


def test_fob_info_kia_is_route_b():
    fi = fob_engine.fob_info("KIA")
    assert fi["route"] == "B" and fi["has_cipher"] is True, fi


def test_fob_info_vag_is_replay_only():
    fi = fob_engine.fob_info("VAG")
    assert fi["route"] == "replay" and fi["has_cipher"] is False, fi


def test_fob_info_unknown_make_is_replay():
    fi = fob_engine.fob_info("NoSuchMake")
    assert fi["route"] == "replay" and fi["has_cipher"] is False, fi


def test_fob_info_honda_v1_is_route_a():
    assert fob_engine.fob_info("Honda V1")["route"] == "A"


def test_fob_info_honda_v10_is_replay():
    # must NOT substring-collide "v1" -> Honda V1 forge (wrong-protocol anti-goal)
    fi = fob_engine.fob_info("Honda V10")
    assert fi["route"] == "replay" and fi["has_cipher"] is False, fi


_FORGE_CASES = [
    ("Porsche Cayenne/Touareg", 2),
    ("Honda V1", 8),
    ("Mazda V0 (PP)", 1),
    ("Sheriff CFM", 0x20),
]
_SERIAL = "2B3C4D"   # 24-bit so it fits every family's serial width
_CNT = 0x10


def _forge_decode(variant, btn):
    r = fob_engine.forge(variant, _SERIAL, _CNT, btn)
    assert "sub" in r and r.get("route") == "A", (variant, r)
    frames = json.loads(phone_arke.decode_text(r["sub"]))
    return [f for f in frames if f.get("make") == variant]


def test_forge_roundtrip_and_counter_contract():
    for variant, btn in _FORGE_CASES:
        hits = _forge_decode(variant, btn)
        assert hits, "%s: forged sub did not decode back" % variant
        f = hits[0]
        assert int(f["serial"]) == 0x2B3C4D, (variant, "serial", f.get("serial"))
        assert int(f["cnt"]) == _CNT, (variant, "cnt", f.get("cnt"))   # counter contract
        assert f.get("btn") is not None, (variant, "btn missing")


def test_forge_replay_variant_errors():
    # decode-only / no-phone-renderer families must refuse to forge (replay only)
    assert "error" in fob_engine.forge("VAG", "DEADBEEF", 1, 1)
    assert "error" in fob_engine.forge("Scher-Khan Magicar PRO", "112233", 1, 1)
    assert "error" in fob_engine.forge("Renault V0 (Type13 keyless)", "2B3C4D", 1, 1)
    assert "error" in fob_engine.forge("Honda V2", "2B3C4D", 1, 2)


def _kia_decrypt_parts(part1, part2, part3):
    """Rebuild the 16-byte AES ciphertext from (part1,part2,part3) and decrypt it,
    mirroring KiaV6._commit -> (serial, btn, cnt)."""
    import aes128, arke_kia67_lr as k
    ct = [(part1 >> 40) & 0xFF, (part1 >> 32) & 0xFF, (part1 >> 24) & 0xFF, (part1 >> 16) & 0xFF,
          (part1 >> 8) & 0xFF, part1 & 0xFF,
          (part2 >> 56) & 0xFF, (part2 >> 48) & 0xFF, (part2 >> 40) & 0xFF, (part2 >> 32) & 0xFF,
          (part2 >> 24) & 0xFF, (part2 >> 16) & 0xFF, (part2 >> 8) & 0xFF, part2 & 0xFF,
          (part3 >> 8) & 0xFF, part3 & 0xFF]
    pt = aes128.decrypt_block(k.KIA_V6_AES_KEY, bytes(ct))
    serial = (pt[4] << 16) | (pt[5] << 8) | pt[6]
    btn = pt[7] & 0x0F
    cnt = (pt[8] << 24) | (pt[9] << 16) | (pt[10] << 8) | pt[11]
    return serial, btn, cnt


def test_forge_kia_v6_route_b():
    r = fob_engine.forge("KIA", "1E2D3C", 0x20, 2, extra={"fx": 0x12})
    assert r.get("route") == "B", r
    assert r.get("protocol") == "Kia V6", r
    assert "Protocol: Kia V6" in r["sub"], r["sub"]
    assert "Key: " in r["sub"] and "Key_2:" in r["sub"] and "Key_4:" in r["sub"], r["sub"]


def test_forge_kia_v6_fields_roundtrip():
    import arke_kia67_lr as k
    p1, p2, p3 = k.kia_v6_build_frame(0x12, 0x1E2D3C, 2, 0x20)
    s, btn, cnt = _kia_decrypt_parts(p1, p2, p3)
    assert s == 0x1E2D3C and btn == 2 and cnt == 0x20, (hex(s), btn, cnt)


def _run():
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    failed = 0
    for fn in fns:
        try:
            fn()
            print(f"PASS {fn.__name__}")
        except Exception:
            failed += 1
            print(f"FAIL {fn.__name__}")
            traceback.print_exc()
    print(f"\n{len(fns)-failed}/{len(fns)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(_run())
