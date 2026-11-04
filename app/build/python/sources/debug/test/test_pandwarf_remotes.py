"""Host tests for pandwarf_remotes (ported PandwaRF fixed-code TX protocols).
Asserts the ported frame logic matches the decompiled Java (fidelity to source).
On-air correctness is device-verified (V1). Run: python3 test/test_pandwarf_remotes.py"""
import os, sys, traceback
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import pandwarf_remotes as pr


def test_dec_to_trinary():
    assert pr.dec_to_trinary(0, 9) == "000000000"
    assert pr.dec_to_trinary(1, 9) == "000000001"   # digit 1 -> '1'
    assert pr.dec_to_trinary(2, 9) == "00000000f"   # digit 2 -> 'f'
    assert pr.dec_to_trinary(3, 9) == "000000010"


def test_chacon_frame_code0():
    f = pr.chacon_frame(0)
    assert len(f) == 120, len(f)                     # 8 * 15
    # msg "000000ff000": '0'->0x8E, 'f'->0xEE
    assert f[0] == 0x8E and f[5] == 0x8E
    assert f[6] == 0xEE and f[7] == 0xEE
    assert f[8] == 0x8E
    assert list(f[11:15]) == [0x88, 0x80, 0x00, 0x00]  # sync
    assert f[15] == f[0]                              # 8x repeat


def test_evology_frame_code0_symbols_swapped():
    f = pr.evology_frame(0)
    assert len(f) == 120
    # decToBinary(511,9)="111111111" -> msg "111111ff111"; Evology '1'->0x8E '0'->0x88 'f'->0xEE
    assert f[0] == 0x8E and f[5] == 0x8E
    assert f[6] == 0xEE and f[7] == 0xEE
    assert f[8] == 0x8E
    assert list(f[11:15]) == [0x88, 0x80, 0x00, 0x00]


def test_idk_car87_frame_code0():
    f = pr.idk_car87_frame(0)
    assert len(f) == 128, len(f)                     # 8 * 16
    assert list(f[0:4]) == [0x80, 0x00, 0x00, 0x00]  # sync first
    assert f[4] == 0x8E                              # '1' -> 0x8E (255-0=all ones + "1111")
    assert f[16] == 0x80                             # 8x repeat
    assert pr.dec_to_binary(255, 8) == "11111111"


def test_keybytes_to_signed_merges_and_times():
    # 0x80 = 1000_0000 @ 4545 baud (220us/bit): high 220, then low 220*7
    ds = pr.keybytes_to_signed(bytes([0x80]), 4545)
    assert ds[0] == 220 and ds[1] == -220 * 7, ds


def test_one_sub_chacon():
    s = pr.one_sub("Chacon 84181", 12345)
    assert "Filetype: Flipper SubGhz RAW File" in s
    assert "Frequency: 433777000" in s
    assert "Preset: FuriHalSubGhzPresetOok650Async" in s
    assert "RAW_Data:" in s


def test_protocols_lists_chacon_and_evology():
    names = {p["name"] for p in pr.protocols()}
    assert "Chacon 84181" in names and "Evology chime" in names


def _run():
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    failed = 0
    for fn in fns:
        try:
            fn(); print("PASS", fn.__name__)
        except Exception:
            failed += 1; print("FAIL", fn.__name__); traceback.print_exc()
    print("\n%d/%d passed" % (len(fns) - failed, len(fns)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(_run())
