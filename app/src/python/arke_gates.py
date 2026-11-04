#!/usr/bin/env python3
r"""
arke_gates.py -- firmware-exact fixed-code gate/garage SubGHz decoders (Tier A of the full port).

Faithful ports of the FoxFW firmware feed() state machines (lib/subghz/protocols/*.c), so a RAW .sub
decodes on the phone exactly as it does on the Flipper. "Keep his where both exist": these use the
firmware's own timing constants and state machine, unlike the approximate pulse-clustering heuristics
in extra_protocols.py, and run alongside them. NAME strings match the firmware's SUBGHZ_PROTOCOL_*_NAME
defines so a phone decode labels identically to the Flipper.

Three state-machine families cover all of these:

  * _FixedOOK     -- LOW-then-HIGH per bit: long preamble LOW, a start HIGH, then each data bit is a
                     (LOW, HIGH) pair, ended by a long LOW gap. (CAME, Nice Flo, Gate-TX, Ansonic,
                     Holtek, Holtek HT12X.)
  * _RepeatHighLow-- HIGH-then-LOW per bit, needs the frame to repeat identically (last_data ==
                     decode_data) before it reports. (Princeton / PT2262, SMC5326.)
  * _ResyncHighLow-- HIGH-then-LOW per bit where the long inter-frame gap both completes the final bit
                     and re-syncs the next frame; no repeat needed. (Linear, Clemsa.)
"""
from arke_core import ProtoDecoder, Const, diff


# ============================================================ LOW-then-HIGH family (_FixedOOK)
class _FixedOOK(ProtoDecoder):
    """Generic 4-step fixed-code OOK decoder (CAME / Nice-Flo / Gate-TX / Ansonic / Holtek family)."""
    PREAMBLE_MULT = 36         # preamble LOW ~= te_short * this
    PREAMBLE_DELTA_MULT = 36   # ... within te_delta * this
    START_LONG = False         # start HIGH is te_long (Gate-TX) instead of te_short
    START_MULT = 1             # start HIGH is te_short * this (e.g. Doitrand = te_short*2), if not START_LONG
    START_DELTA_MULT = 1       # tolerance mult for the start pulse
    END_SHORT_MULT = 4         # end-gap LOW >= te_short*END_SHORT_MULT + te_delta*END_DELTA_ADD
    END_DELTA_ADD = 0
    LONG_DELTA_MULT = 1        # tolerance mult for long pulses in the bit decode
    BIT0_SHORT_LOW = True      # bit 0 = short-low + long-high (False inverts it, e.g. Ansonic)
    VALID_BITS = None          # set of accepted bit counts, else MIN_BITS is a floor
    MIN_BITS = 12

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset — wait for the long preamble LOW
            if (not level) and diff(duration, c.te_short * self.PREAMBLE_MULT) < c.te_delta * self.PREAMBLE_DELTA_MULT:
                d.parser_step = 1
        elif step == 1:  # FoundStartBit — expect the start HIGH
            if level:
                start_te = c.te_long if self.START_LONG else c.te_short * self.START_MULT
                if diff(duration, start_te) < c.te_delta * self.START_DELTA_MULT:
                    d.parser_step = 2
                    d.decode_data = 0
                    d.decode_count_bit = 0
                else:
                    d.parser_step = 0
        elif step == 2:  # SaveDuration — a LOW: either the end gap, or the first half of a bit
            if not level:
                if duration >= c.te_short * self.END_SHORT_MULT + c.te_delta * self.END_DELTA_ADD:
                    d.parser_step = 1
                    n = d.decode_count_bit
                    ok = (n in self.VALID_BITS) if self.VALID_BITS is not None else (n >= self.MIN_BITS)
                    if ok and d.decode_data:
                        self.emit(dict(serial=d.decode_data, bits=n))
                else:
                    d.te_last = duration
                    d.parser_step = 3
            else:
                d.parser_step = 0
        elif step == 3:  # CheckDuration — the HIGH that completes the bit
            if level:
                ld = c.te_delta * self.LONG_DELTA_MULT
                short_low_long_high = diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < ld
                long_low_short_high = diff(d.te_last, c.te_long) < ld and diff(duration, c.te_short) < c.te_delta
                if short_low_long_high:
                    d.add_bit(0 if self.BIT0_SHORT_LOW else 1)
                    d.parser_step = 2
                elif long_low_short_high:
                    d.add_bit(1 if self.BIT0_SHORT_LOW else 0)
                    d.parser_step = 2
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0


class Came(_FixedOOK):
    NAME = "CAME"
    CONST = Const(320, 640, 150)
    PREAMBLE_MULT = 56
    PREAMBLE_DELTA_MULT = 63
    VALID_BITS = {12, 18, 24, 25, 42}   # CAME-12/24, Airforce-18, Prastel-25/42


class NiceFlo(_FixedOOK):
    NAME = "Nice FLO"
    CONST = Const(700, 1400, 200)
    MIN_BITS = 12                        # 12-bit (FloR) or 24-bit


class GateTx(_FixedOOK):
    NAME = "GateTX"                      # firmware SUBGHZ_PROTOCOL_GATE_TX_NAME
    CONST = Const(350, 700, 100)
    PREAMBLE_MULT = 47
    PREAMBLE_DELTA_MULT = 47
    START_LONG = True
    START_DELTA_MULT = 3
    END_SHORT_MULT = 10
    END_DELTA_ADD = 1
    LONG_DELTA_MULT = 3
    VALID_BITS = {24}


class Ansonic(_FixedOOK):
    NAME = "Ansonic"
    CONST = Const(555, 1111, 120)
    PREAMBLE_MULT = 35
    PREAMBLE_DELTA_MULT = 35
    END_SHORT_MULT = 4
    VALID_BITS = {12}
    BIT0_SHORT_LOW = False               # Ansonic bit 0 = long-low + short-high (inverted)


class Holtek(_FixedOOK):
    NAME = "Holtek"                      # firmware holtek.c, 40-bit (header nibble 0x5)
    CONST = Const(430, 870, 100)
    PREAMBLE_MULT = 36
    PREAMBLE_DELTA_MULT = 36
    END_SHORT_MULT = 10
    END_DELTA_ADD = 1
    LONG_DELTA_MULT = 2
    VALID_BITS = {40}


class HoltekHt12x(_FixedOOK):
    NAME = "Holtek_HT12X"                # firmware holtek_ht12x.c, 12-bit
    CONST = Const(320, 640, 200)
    PREAMBLE_MULT = 28
    PREAMBLE_DELTA_MULT = 20
    END_SHORT_MULT = 10
    END_DELTA_ADD = 1
    LONG_DELTA_MULT = 2
    VALID_BITS = {12}


class Doitrand(_FixedOOK):
    NAME = "Doitrand"                    # firmware doitrand.c, 37-bit
    CONST = Const(400, 1100, 150)
    PREAMBLE_MULT = 62
    PREAMBLE_DELTA_MULT = 30
    START_MULT = 2                       # start HIGH = te_short*2
    START_DELTA_MULT = 3
    END_SHORT_MULT = 10
    END_DELTA_ADD = 1
    LONG_DELTA_MULT = 3
    VALID_BITS = {37}


# ============================================================ HIGH-then-LOW, repeat-gated
class _RepeatHighLow(ProtoDecoder):
    """HIGH-then-LOW per bit; only reports once an identical frame repeats (last_data == decode_data).
    Princeton / PT2262 and SMC5326."""
    PREAMBLE_MULT = 36
    PREAMBLE_DELTA_MULT = 36
    LONG_DELTA_MULT = 3        # long-pulse tolerance in the bit decode
    END_LONG_MULT = 2          # end-of-frame gap: LOW >= te_long * this
    BITS = 24
    SERIAL_SPLIT = False       # True -> serial = data>>4, btn = data&0xF (PT2262 20+4)

    def __init__(self):
        super().__init__()
        self.last_data = 0

    def reset(self):
        self.d.reset()

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset — the long preamble LOW
            if (not level) and diff(duration, c.te_short * self.PREAMBLE_MULT) < c.te_delta * self.PREAMBLE_DELTA_MULT:
                d.parser_step = 1
                d.decode_data = 0
                d.decode_count_bit = 0
        elif step == 1:  # SaveDuration — a HIGH
            if level:
                d.te_last = duration
                d.parser_step = 2
        elif step == 2:  # CheckDuration — a LOW
            if not level:
                if duration >= c.te_long * self.END_LONG_MULT:   # end-of-frame gap
                    d.parser_step = 1
                    if d.decode_count_bit == self.BITS:
                        if self.last_data == d.decode_data and self.last_data:
                            self._emit(d.decode_data)
                        self.last_data = d.decode_data
                    d.decode_data = 0
                    d.decode_count_bit = 0
                else:
                    ld = c.te_delta * self.LONG_DELTA_MULT
                    if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < ld:
                        d.add_bit(0); d.parser_step = 1
                    elif diff(d.te_last, c.te_long) < ld and diff(duration, c.te_short) < c.te_delta:
                        d.add_bit(1); d.parser_step = 1
                    else:
                        d.parser_step = 0
            else:
                d.parser_step = 0

    def _emit(self, v):
        if self.SERIAL_SPLIT:
            self.emit(dict(serial=v >> 4, btn=v & 0xF, bits=self.BITS))
        else:
            self.emit(dict(serial=v, bits=self.BITS))


class Princeton(_RepeatHighLow):
    NAME = "Princeton"
    CONST = Const(390, 1170, 300)
    PREAMBLE_MULT = 36
    PREAMBLE_DELTA_MULT = 36
    BITS = 24
    SERIAL_SPLIT = True          # serial = data>>4, btn = data&0xF


class SMC5326(_RepeatHighLow):
    NAME = "SMC5326"
    CONST = Const(300, 900, 200)
    PREAMBLE_MULT = 24
    PREAMBLE_DELTA_MULT = 12
    BITS = 25


# ============================================================ HIGH-then-LOW, gap-resync
class _ResyncHighLow(ProtoDecoder):
    """HIGH-then-LOW per bit; the long inter-frame gap (~= preamble) completes the final bit and
    re-syncs. No repeat needed. Linear, Clemsa."""
    PREAMBLE_MULT = 42
    PREAMBLE_DELTA_MULT = 15
    LONG_DELTA_MULT = 1        # long-pulse tolerance (Linear 1, Clemsa 3)
    MIN_BITS = 10

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        ld = c.te_delta * self.LONG_DELTA_MULT
        if step == 0:  # Reset — preamble LOW
            if (not level) and diff(duration, c.te_short * self.PREAMBLE_MULT) < c.te_delta * self.PREAMBLE_DELTA_MULT:
                d.parser_step = 1
                d.decode_data = 0
                d.decode_count_bit = 0
        elif step == 1:  # SaveDuration — a HIGH
            if level:
                d.te_last = duration
                d.parser_step = 2
            else:
                d.parser_step = 0
        elif step == 2:  # CheckDuration — a LOW
            if level:
                d.parser_step = 0
                return
            short_hi = diff(d.te_last, c.te_short) < c.te_delta
            long_hi = diff(d.te_last, c.te_long) < ld
            if short_hi and diff(duration, c.te_long) < ld:
                d.add_bit(0); d.parser_step = 1
            elif long_hi and diff(duration, c.te_short) < c.te_delta:
                d.add_bit(1); d.parser_step = 1
            elif diff(duration, c.te_short * self.PREAMBLE_MULT) < c.te_delta * self.PREAMBLE_DELTA_MULT:
                # inter-frame gap: complete the final bit from the stored HIGH, report, re-sync
                if short_hi:
                    d.add_bit(0)
                elif long_hi:
                    d.add_bit(1)
                else:
                    d.parser_step = 0
                    return
                if d.decode_count_bit == self.MIN_BITS and d.decode_data:
                    self.emit(dict(serial=d.decode_data, bits=self.MIN_BITS))
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 1   # preamble already consumed; ready for the next frame's first HIGH
            else:
                d.parser_step = 0


class Linear(_ResyncHighLow):
    NAME = "Linear"
    CONST = Const(500, 1500, 350)
    PREAMBLE_MULT = 42
    PREAMBLE_DELTA_MULT = 15
    LONG_DELTA_MULT = 1
    MIN_BITS = 10


class Clemsa(_ResyncHighLow):
    NAME = "Clemsa"
    CONST = Const(385, 2695, 150)
    PREAMBLE_MULT = 51
    PREAMBLE_DELTA_MULT = 25
    LONG_DELTA_MULT = 3
    MIN_BITS = 18


# ============================================================ HIGH-then-LOW, resync-resets
class _ResetHighLow(ProtoDecoder):
    """HIGH-then-LOW per bit (short HIGH = 0, long HIGH = 1, each followed by the complementary LOW);
    long preamble LOW. The inter-frame resync gap, seen as the LOW after a bit's HIGH, completes that
    final bit, reports, and resets to the preamble search (unlike _ResyncHighLow which stays armed).
    Feron, Roger. Preamble and resync gaps are independently configurable (they differ in Feron)."""
    PRE_UNIT = "short"; PRE_MULT = 19; PRE_DELTA_MULT = 5          # Reset-step preamble LOW
    RESYNC_UNIT = "short"; RESYNC_MULT = 19; RESYNC_ADD = 0; RESYNC_DELTA_MULT = 5  # end-of-frame gap
    BITS = 28

    def _u(self, unit):
        c = self.CONST
        return c.te_long if unit == "long" else c.te_short

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        pre = self._u(self.PRE_UNIT) * self.PRE_MULT
        pre_tol = c.te_delta * self.PRE_DELTA_MULT
        rs = self._u(self.RESYNC_UNIT) * self.RESYNC_MULT + self.RESYNC_ADD
        rs_tol = c.te_delta * self.RESYNC_DELTA_MULT
        if step == 0:  # Reset — preamble LOW
            if (not level) and diff(duration, pre) < pre_tol:
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 1
        elif step == 1:  # SaveDuration — a HIGH
            if level:
                d.te_last = duration
                d.parser_step = 2
            else:
                d.parser_step = 0
        elif step == 2:  # CheckDuration — a LOW
            if level:
                d.parser_step = 0
                return
            short_hi = diff(d.te_last, c.te_short) < c.te_delta
            long_hi = diff(d.te_last, c.te_long) < c.te_delta
            if long_hi and diff(duration, c.te_short) < c.te_delta:
                d.add_bit(1); d.parser_step = 1
            elif short_hi and diff(duration, c.te_long) < c.te_delta:
                d.add_bit(0); d.parser_step = 1
            elif diff(duration, rs) < rs_tol:         # inter-frame gap completes the final bit
                if short_hi:
                    d.add_bit(0)
                elif long_hi:
                    d.add_bit(1)
                if d.decode_count_bit == self.BITS and d.decode_data:
                    self.emit(dict(serial=d.decode_data, bits=self.BITS))
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 0
            else:
                d.parser_step = 0


class Feron(_ResetHighLow):
    NAME = "Feron"                       # firmware feron.c, 32-bit
    CONST = Const(350, 750, 150)
    PRE_UNIT = "long"; PRE_MULT = 6; PRE_DELTA_MULT = 4
    RESYNC_UNIT = "short"; RESYNC_MULT = 1; RESYNC_ADD = 150; RESYNC_DELTA_MULT = 1
    BITS = 32


class Roger(_ResetHighLow):
    NAME = "Roger"                       # firmware roger.c, 28-bit
    CONST = Const(500, 1000, 270)
    PRE_UNIT = "short"; PRE_MULT = 19; PRE_DELTA_MULT = 5
    RESYNC_UNIT = "short"; RESYNC_MULT = 19; RESYNC_ADD = 0; RESYNC_DELTA_MULT = 5
    BITS = 28


class NordIce(_ResetHighLow):
    NAME = "Nord ICE"                    # firmware nord_ice.c, 33-bit (SUBGHZ_PROTOCOL_NORD_ICE_NAME)
    CONST = Const(300, 800, 150)
    PRE_UNIT = "short"; PRE_MULT = 25; PRE_DELTA_MULT = 11
    RESYNC_UNIT = "short"; RESYNC_MULT = 25; RESYNC_ADD = 0; RESYNC_DELTA_MULT = 11
    BITS = 33


class GangQi(_ResetHighLow):
    NAME = "GangQi"                      # firmware gangqi.c, 34-bit
    CONST = Const(500, 1200, 200)
    PRE_UNIT = "long"; PRE_MULT = 2; PRE_DELTA_MULT = 3
    RESYNC_UNIT = "long"; RESYNC_MULT = 2; RESYNC_ADD = 0; RESYNC_DELTA_MULT = 3
    BITS = 34


# ============================================================ single-pulse-per-bit PWM (gap-separated)
class Bett(ProtoDecoder):
    """Each bit is ONE high pulse (long = 1, short = 0) separated by a short LOW gap; the frame ends on
    a long te_short*44 LOW. firmware bett.c, 18-bit. Mirrors the firmware's report-but-don't-advance on
    a complete frame (it re-syncs on the next preamble)."""
    NAME = "BETT"                        # firmware SUBGHZ_PROTOCOL_BETT_NAME
    CONST = Const(340, 2000, 150)
    PREAMBLE_MULT = 44
    PREAMBLE_DELTA_MULT = 15
    LONG_DELTA_MULT = 3
    BITS = 18

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        pre = c.te_short * self.PREAMBLE_MULT
        pre_tol = c.te_delta * self.PREAMBLE_DELTA_MULT
        ld = c.te_delta * self.LONG_DELTA_MULT
        if step == 0:  # Reset — preamble LOW, then straight to the first bit's HIGH
            if (not level) and diff(duration, pre) < pre_tol:
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 2
        elif step == 1:  # SaveDuration — a LOW: inter-bit gap or the end-of-frame gap
            if not level:
                if diff(duration, pre) < pre_tol:          # end-of-frame gap
                    if d.decode_count_bit == self.BITS:
                        if d.decode_data:
                            self.emit(dict(serial=d.decode_data, bits=self.BITS))
                        # firmware leaves parser_step at SaveDuration here (re-syncs next preamble)
                    else:
                        d.parser_step = 0
                    d.decode_data = 0
                    d.decode_count_bit = 0
                elif diff(duration, c.te_short) < c.te_delta or diff(duration, c.te_long) < ld:
                    d.parser_step = 2
                else:
                    d.parser_step = 0
        elif step == 2:  # CheckDuration — a HIGH pulse is the bit
            if level:
                if diff(duration, c.te_long) < ld:
                    d.add_bit(1); d.parser_step = 1
                elif diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(0); d.parser_step = 1
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0


class Hollarm(ProtoDecoder):
    """firmware hollarm.c, 42-bit. The HIGH is ALWAYS te_short; the following LOW encodes the bit
    (te_long = 0, te_short*8 = 1). The preamble (te_short*12) doubles as the resync gap, which forces a
    final 0 bit. On a complete frame the reported value is decode_data>>2 and must pass a byte-sum
    checksum: (b4+b3+b2+b1) & 0xFF == b0 over the 40-bit value's 5 bytes."""
    NAME = "Hollarm"                     # firmware SUBGHZ_PROTOCOL_HOLLARM_NAME
    CONST = Const(200, 1000, 200)
    PREAMBLE_MULT = 12
    PREAMBLE_DELTA_MULT = 2
    BIT1_LOW_MULT = 8                    # bit 1 = short HIGH + te_short*8 LOW
    RESYNC_MULT = 12                    # resync gap (== preamble), forces a final 0 bit
    BITS = 42

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        pre = c.te_short * self.PREAMBLE_MULT
        pre_tol = c.te_delta * self.PREAMBLE_DELTA_MULT
        if step == 0:  # Reset — preamble LOW
            if (not level) and diff(duration, pre) < pre_tol:
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 1
        elif step == 1:  # SaveDuration — HIGH (always ~te_short)
            if level:
                d.te_last = duration
                d.parser_step = 2
            else:
                d.parser_step = 0
        elif step == 2:  # CheckDuration — LOW encodes the bit
            if level:
                d.parser_step = 0
                return
            short_hi = diff(d.te_last, c.te_short) < c.te_delta
            if short_hi and diff(duration, c.te_long) < c.te_delta:
                d.add_bit(0); d.parser_step = 1
            elif short_hi and diff(duration, c.te_short * self.BIT1_LOW_MULT) < c.te_delta:
                d.add_bit(1); d.parser_step = 1
            elif diff(duration, c.te_short * self.RESYNC_MULT) < c.te_delta:
                d.add_bit(0)                               # resync always appends a 0 (the 42nd bit)
                if d.decode_count_bit == self.BITS:
                    data = d.decode_data >> 2
                    bytesum = (((data >> 32) & 0xFF) + ((data >> 24) & 0xFF) +
                               ((data >> 16) & 0xFF) + ((data >> 8) & 0xFF)) & 0xFF
                    if bytesum == (data & 0xFF):
                        self.emit(dict(serial=data, bits=self.BITS))
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 0
            else:
                d.parser_step = 0


ALL = [Came, NiceFlo, GateTx, Ansonic, Holtek, HoltekHt12x, Doitrand,
       Linear, Clemsa, SMC5326, Princeton,
       Feron, Roger, NordIce, GangQi, Bett, Hollarm]
