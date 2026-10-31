"""Host tests for the De Bruijn brute added to barriers.py. Run: python3 test/test_barriers.py"""
import os, sys, traceback
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import barriers


def test_debruijn_covers_all_windows():
    for n in (2, 3, 4, 8):
        s = barriers.debruijn_bits(n)
        assert len(s) == 2 ** n, (n, len(s))
        cyc = s + s[: n - 1]
        wins = {tuple(cyc[i:i + n]) for i in range(2 ** n)}
        assert len(wins) == 2 ** n, (n, len(wins))  # every n-bit code appears exactly once


def test_debruijn_sub_is_raw():
    s = barriers.debruijn_sub("CAME 12-bit")
    assert "Filetype: Flipper SubGhz RAW File" in s
    assert "RAW_Data:" in s


def test_debruijn_sub_unknown_errors():
    assert barriers.debruijn_sub("NoSuch").strip().startswith("{")


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
