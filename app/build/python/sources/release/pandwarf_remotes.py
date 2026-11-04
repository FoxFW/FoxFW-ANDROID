"""PandwaRF fixed-code OOK remote protocols, ported from the decompiled app v1.39.2
(com.comthings.gollum.app.devicelib.protocol.Sub1GHzRfProtocol*). Each builds a keyed byte
frame exactly as the Java does; keybytes_to_signed() then expands it to Flipper RAW pulse
durations at the protocol's datarate. Generate-able from the companion's Car & Gate screen;
on-air correctness is device-verified (V1). Furi-free, stdlib-only (Chaquopy).

NOTE: KeeLoq / PT2262 / Somfy RTS / Linear in the PandwaRF library are already covered by the
companion's arke/ProtoPirate engine, so they are NOT re-ported here.
"""

_PRESET_OOK650 = "FuriHalSubGhzPresetOok650Async"
_SYNC = [0x88, 0x80, 0x00, 0x00]  # the 4-byte inter-frame sync (Java `c`)


def dec_to_trinary(i, n):
    """Base-class decToTrinary: n trinary digits MSB-first, 1->'1' 2->'f' 0->'0'."""
    digs = []
    x = i
    for _ in range(n):
        r = x % 3
        x //= 3
        digs.append("1" if r == 1 else "f" if r == 2 else "0")
    return "".join(reversed(digs))


def dec_to_binary(i, n):
    return format(i & ((1 << n) - 1), "0{}b".format(n))


def _msg11(nine_char):
    """getMessageString: reverse the 9-char code, insert 'ff' after the 6th -> 11 chars."""
    rev = nine_char[::-1]
    return rev[:6] + "ff" + rev[6:]


def _frame(msg11, sym):
    """getDataToSend: 8 repeats of [11 symbol-bytes + 4 sync-bytes] = 120 bytes."""
    out = []
    for _ in range(8):
        for ch in msg11:
            out.append(sym.get(ch, 0))
        out.extend(_SYNC)
    return bytes(out)


def chacon_frame(code):
    sym = {"1": 0x88, "0": 0x8E, "f": 0xEE}
    return _frame(_msg11(dec_to_trinary(code, 9)), sym)


def evology_frame(code):
    sym = {"1": 0x8E, "0": 0x88, "f": 0xEE}  # 1/0 swapped vs Chacon
    return _frame(_msg11(dec_to_binary(511 - code, 9)), sym)


def idk_car87_frame(code):
    """IDK CAR-87: 8 repeats of [4 sync-bytes {0x80,0,0,0} + 12 symbol-bytes] = 128 bytes.
    Message = reversed 8-bit (255-code) binary + '1111'. (sync-first, unlike Chacon/Evology.)"""
    db = dec_to_binary(255 - code, 8)
    msg = db[::-1] + "1111"
    sym = {"1": 0x8E, "0": 0x88, "f": 0xEE}
    out = []
    for _ in range(8):
        out.extend([0x80, 0x00, 0x00, 0x00])
        for ch in msg:
            out.append(sym.get(ch, 0))
    return bytes(out)


def keybytes_to_signed(frame, baud):
    """Expand the CC1111 async-serial keying bytes to signed OOK durations (+high/-low),
    MSB-first per byte at the protocol's bit time, merging same-level runs."""
    bit_us = round(1_000_000 / baud)
    out = []
    for byte in frame:
        for bit in range(7, -1, -1):
            lvl = (byte >> bit) & 1
            s = bit_us if lvl else -bit_us
            if out and (out[-1] > 0) == bool(lvl):
                out[-1] += s
            else:
                out.append(s)
    return out


def _sub(ds, freq_hz):
    head = ("Filetype: Flipper SubGhz RAW File\nVersion: 1\nFrequency: %d\n"
            "Preset: %s\nProtocol: RAW\n" % (freq_hz, _PRESET_OOK650))
    parts = [head]
    for i in range(0, len(ds), 512):
        parts.append("RAW_Data: " + " ".join(str(int(x)) for x in ds[i:i + 512]) + "\n")
    return "".join(parts)


# name -> (frequency, baud, code-bit-space, frame builder)
_REG = {
    "Chacon 84181": dict(freq=433777000, baud=4545, codes=3 ** 9, gen=chacon_frame),
    "Evology chime": dict(freq=433807000, baud=4545, codes=512, gen=evology_frame),
    "IDK CAR-87": dict(freq=433625000, baud=3330, codes=256, gen=idk_car87_frame),
}


def protocols():
    """List the PandwaRF fixed-code remotes for the Car & Gate picker."""
    return [{"name": n, "freq": v["freq"], "codes": v["codes"]} for n, v in _REG.items()]


def one_sub(name, code, freq=None):
    """Generate a RAW .sub for one code of a PandwaRF remote. {error} if unknown."""
    v = _REG.get(name)
    if v is None:
        return {"error": "unknown PandwaRF remote '%s'" % name}
    ds = keybytes_to_signed(v["gen"](int(code)), v["baud"])
    return _sub(ds, int(freq) if freq else v["freq"])
