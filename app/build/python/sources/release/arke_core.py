#!/usr/bin/env python3
r"""
arke_core.py -- shared primitives for porting Flipper Sub-GHz automotive decoders
to pure Python, so RAW (.sub) pulse captures can be demodulated on the PC exactly
as the firmware does on-device.

A ported decoder is a subclass of ProtoDecoder with:
  * NAME  (str)         -- protocol name for the report
  * CONST (Const)       -- te_short/te_long/te_delta
  * feed(self, level, duration)  -- the firmware feed() state machine
  * on lock, call self.emit(dict(serial=..., btn=..., cnt=..., crc_ok=bool, ...))

The RAW driver reads RAW_Data (signed durations; +=high/-=low) and feeds every
registered decoder in parallel, mirroring how the Flipper runs all decoders at
once. A frame is only reported "verified" when its own CRC checks out.
"""

MASK64 = 0xFFFFFFFFFFFFFFFF


# ---- timing ----
class Const:
    __slots__ = ("te_short", "te_long", "te_delta", "min_bits")

    def __init__(self, te_short, te_long, te_delta, min_bits=0):
        self.te_short = te_short
        self.te_long = te_long
        self.te_delta = te_delta
        self.min_bits = min_bits


def diff(x, y):
    return y - x if x < y else x - y


def is_short(d, c):
    return diff(d, c.te_short) < c.te_delta


def is_long(d, c):
    return diff(d, c.te_long) < c.te_delta


# ---- bit maths (blocks/math.c) ----
def crc8(msg, poly, init=0):
    r = init & 0xFF
    for b in msg:
        r ^= b & 0xFF
        for _ in range(8):
            r = ((r << 1) ^ poly) & 0xFF if (r & 0x80) else (r << 1) & 0xFF
    return r


def crc16(msg, poly, init=0):
    r = init & 0xFFFF
    for b in msg:
        r ^= (b & 0xFF) << 8
        for _ in range(8):
            r = ((r << 1) ^ poly) & 0xFFFF if (r & 0x8000) else (r << 1) & 0xFFFF
    return r


def reverse_key(key, bit_count):
    r = 0
    for i in range(bit_count):
        r = (r << 1) | ((key >> i) & 1)
    return r


def rev8(b):
    b &= 0xFF
    r = 0
    for i in range(8):
        if b & (1 << i):
            r |= 1 << (7 - i)
    return r


def parity(key, bit_count):
    p = 0
    for i in range(bit_count):
        p ^= (key >> i) & 1
    return p


def u64_to_bytes_be(d):
    return [(d >> (56 - 8 * i)) & 0xFF for i in range(8)]


def bytes_to_u64_be(bs):
    d = 0
    for b in bs[:8]:
        d = (d << 8) | (b & 0xFF)
    return d


# ---- Manchester (toolbox/manchester_decoder.c), verified via PSA end-to-end ----
# Events: ShortLow=0 ShortHigh=2 LongLow=4 LongHigh=6 Reset=8.
M_SHORT_LOW, M_SHORT_HIGH, M_LONG_LOW, M_LONG_HIGH, M_RESET_EV = 0, 2, 4, 6, 8
_M_TRANS = (0b00000001, 0b10010001, 0b10011011, 0b11111011)
_M_START = 1  # ManchesterStateMid1


def manchester_event(duration, level, c):
    if diff(duration, c.te_short) < c.te_delta:
        return M_SHORT_LOW if level else M_SHORT_HIGH
    if diff(duration, c.te_long) < c.te_delta:
        return M_LONG_LOW if level else M_LONG_HIGH
    return M_RESET_EV


class Manchester:
    """Returns None (no bit), True (bit 1) or False (bit 0) per advance."""
    __slots__ = ("state",)

    def __init__(self):
        self.state = _M_START

    def reset(self):
        self.state = _M_START

    def advance(self, event):
        if event == M_RESET_EV:
            self.state = _M_START
            return None
        ns = (_M_TRANS[self.state] >> event) & 0x3
        if ns == self.state:
            self.state = _M_START
            return None
        self.state = ns
        if ns == 2:  # Mid0
            return False
        if ns == 1:  # Mid1
            return True
        return None


# ---- decoder state (blocks/decoder.c SubGhzBlockDecoder) ----
class Decoder:
    __slots__ = ("parser_step", "te_last", "decode_data", "decode_count_bit", "head_64")

    def __init__(self):
        self.reset()

    def reset(self):
        self.parser_step = 0
        self.te_last = 0
        self.decode_data = 0
        self.decode_count_bit = 0
        self.head_64 = 0

    def add_bit(self, bit):
        self.decode_data = ((self.decode_data << 1) | (bit & 1)) & MASK64
        self.decode_count_bit += 1

    def add_to_128(self, bit):
        # blocks/decoder.c subghz_protocol_blocks_add_to_128_bit
        self.decode_count_bit += 1
        if self.decode_count_bit > 64:
            self.head_64 = ((self.head_64 << 1) | (self.decode_data >> 63)) & MASK64
        self.decode_data = ((self.decode_data << 1) | (bit & 1)) & MASK64

    def wide(self):
        """full accumulated value across head_64:decode_data (for >64-bit frames)."""
        return (self.head_64 << 64) | self.decode_data


# ---- decoder base + registry ----
class ProtoDecoder:
    NAME = "?"
    CONST = None

    def __init__(self):
        self.d = Decoder()
        self.results = []

    def reset(self):
        self.d.reset()

    def emit(self, fields):
        fields.setdefault("proto", self.NAME)
        # de-dup identical frames within one capture
        key = (fields.get("serial"), fields.get("btn"), fields.get("cnt"),
               fields.get("data"))
        for r in self.results:
            if (r.get("serial"), r.get("btn"), r.get("cnt"), r.get("data")) == key:
                return
        self.results.append(fields)

    def feed(self, level, duration):
        raise NotImplementedError


# ---- .sub parsing + driver ----
def parse_sub(path):
    meta, raws = {}, []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if ":" not in line:
                continue
            k, _, v = line.partition(":")
            k, v = k.strip(), v.strip()
            if k == "RAW_Data":
                raws.append(v)
            else:
                meta.setdefault(k, v)
    return meta, raws


def iter_durations(raws):
    for chunk in raws:
        for tok in chunk.split():
            try:
                yield int(tok)
            except ValueError:
                pass


def run_decoders(path, decoder_classes):
    """Feed one RAW .sub through every decoder class; return {name: [frames]}."""
    meta, raws = parse_sub(path)
    decs = [cls() for cls in decoder_classes]
    for v in iter_durations(raws):
        lvl, dur = v > 0, abs(v)
        for dec in decs:
            try:
                dec.feed(lvl, dur)
            except Exception:
                pass
    return {dec.NAME: dec.results for dec in decs if dec.results}
