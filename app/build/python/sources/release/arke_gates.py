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
from arke_core import ProtoDecoder, Const, diff, crc8, rev8, Manchester, MASK64, reverse_key
from arke_core import M_RESET_EV, M_SHORT_LOW, M_SHORT_HIGH, M_LONG_LOW, M_LONG_HIGH


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


class Doorbell32(_RepeatHighLow):
    NAME = "Doorbell32"                  # firmware doorbell32.c, 32-bit, repeat-gated
    CONST = Const(515, 1545, 200)
    PREAMBLE_MULT = 14                   # DOORBELL32_GUARD_TIME
    PREAMBLE_DELTA_MULT = 7              # te_delta*14/2
    END_LONG_MULT = 2
    LONG_DELTA_MULT = 2
    BITS = 32


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


class Hay21(_ResetHighLow):
    NAME = "Hay21"                       # firmware hay21.c, 21-bit
    CONST = Const(300, 700, 150)
    PRE_UNIT = "long"; PRE_MULT = 6; PRE_DELTA_MULT = 3
    RESYNC_UNIT = "long"; RESYNC_MULT = 6; RESYNC_ADD = 0; RESYNC_DELTA_MULT = 2
    BITS = 21


class Elplast(_ResetHighLow):
    NAME = "Elplast"                     # firmware elplast.c, 18-bit (SUBGHZ_PROTOCOL_ELPLAST_NAME)
    CONST = Const(230, 1550, 160)
    PRE_UNIT = "long"; PRE_MULT = 8; PRE_DELTA_MULT = 13
    RESYNC_UNIT = "long"; RESYNC_MULT = 8; RESYNC_ADD = 0; RESYNC_DELTA_MULT = 13
    BITS = 18


class Treadmill37(_ResetHighLow):
    NAME = "Treadmill37"                 # firmware treadmill37.c, 37-bit
    CONST = Const(300, 900, 150)
    PRE_UNIT = "short"; PRE_MULT = 20; PRE_DELTA_MULT = 4
    RESYNC_UNIT = "short"; RESYNC_MULT = 20; RESYNC_ADD = 0; RESYNC_DELTA_MULT = 4
    BITS = 37


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


class Marantec24(ProtoDecoder):
    """firmware marantec24.c, 24-bit. HIGH-then-LOW, but NON-complementary LOWs and inverted polarity:
    bit 0 = long HIGH + te_short*3 LOW; bit 1 = short HIGH + te_long*2 LOW. The te_long*9 inter-frame gap
    completes the final bit from te_last (long -> 0, short -> 1), reports, and resets to the preamble."""
    NAME = "Marantec24"                  # firmware SUBGHZ_PROTOCOL_MARANTEC24_NAME
    CONST = Const(800, 1600, 200)
    PREAMBLE_MULT = 9                    # preamble/resync = te_long*9
    PREAMBLE_DELTA_MULT = 4
    BITS = 24

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        gap = c.te_long * self.PREAMBLE_MULT
        gap_tol = c.te_delta * self.PREAMBLE_DELTA_MULT
        if step == 0:  # Reset — preamble LOW
            if (not level) and diff(duration, gap) < gap_tol:
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 1
        elif step == 1:  # SaveDuration — HIGH
            if level:
                d.te_last = duration
                d.parser_step = 2
            else:
                d.parser_step = 0
        elif step == 2:  # CheckDuration — LOW
            if level:
                d.parser_step = 0
                return
            long_hi = diff(d.te_last, c.te_long) < c.te_delta
            short_hi = diff(d.te_last, c.te_short) < c.te_delta
            if long_hi and diff(duration, c.te_short * 3) < c.te_delta:
                d.add_bit(0); d.parser_step = 1
            elif short_hi and diff(duration, c.te_long * 2) < c.te_delta:
                d.add_bit(1); d.parser_step = 1
            elif diff(duration, gap) < gap_tol:         # inter-frame gap completes final bit
                if long_hi:
                    d.add_bit(0)
                elif short_hi:
                    d.add_bit(1)
                if d.decode_count_bit == self.BITS and d.decode_data:
                    self.emit(dict(serial=d.decode_data, bits=self.BITS))
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 0
            else:
                d.parser_step = 0


class Mastercode(ProtoDecoder):
    """firmware mastercode.c, 36-bit. HIGH-then-LOW (short HI = 0, long HI = 1) with a WIDE long tolerance
    (te_delta*8). The te_short*15 inter-frame gap completes the final bit from te_last. Faithful to the
    firmware quirk: after a complete frame it does NOT clear decode_data/count and drops back to
    SaveDuration (so it reports once per lock until a malformed pulse resets it)."""
    NAME = "Mastercode"                  # firmware SUBGHZ_PROTOCOL_MASTERCODE_NAME
    CONST = Const(1072, 2145, 150)
    PREAMBLE_MULT = 15                   # preamble/resync = te_short*15
    PREAMBLE_DELTA_MULT = 15
    LONG_DELTA_MULT = 8                  # long-pulse tolerance
    BITS = 36

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        gap = c.te_short * self.PREAMBLE_MULT
        gap_tol = c.te_delta * self.PREAMBLE_DELTA_MULT
        ld = c.te_delta * self.LONG_DELTA_MULT
        if step == 0:  # Reset — preamble LOW
            if (not level) and diff(duration, gap) < gap_tol:
                d.parser_step = 1
                d.decode_data = 0
                d.decode_count_bit = 0
        elif step == 1:  # SaveDuration — HIGH
            if level:
                d.te_last = duration
                d.parser_step = 2
            else:
                d.parser_step = 0
        elif step == 2:  # CheckDuration — LOW
            if level:
                d.parser_step = 0
                return
            short_hi = diff(d.te_last, c.te_short) < c.te_delta
            long_hi = diff(d.te_last, c.te_long) < ld
            if short_hi and diff(duration, c.te_long) < ld:
                d.add_bit(0); d.parser_step = 1
            elif long_hi and diff(duration, c.te_short) < c.te_delta:
                d.add_bit(1); d.parser_step = 1
            elif diff(duration, gap) < gap_tol:         # inter-frame gap completes final bit
                if short_hi:
                    d.add_bit(0)
                elif long_hi:
                    d.add_bit(1)
                else:
                    d.parser_step = 0
                    return
                if d.decode_count_bit == self.BITS and d.decode_data:
                    self.emit(dict(serial=d.decode_data, bits=self.BITS))
                d.parser_step = 1                       # firmware: no reset, drop back to SaveDuration
            else:
                d.parser_step = 0


class KeyFinder(ProtoDecoder):
    """firmware keyfinder.c, 24-bit. HIGH-then-LOW (short HI = 1, long HI = 0). After the 24 data bits the
    frame is terminated by FOUR short HIGH pulses (counted, short LOWs between ignored); the 4th arms the
    report. Preamble = te_short*10 LOW."""
    NAME = "KeyFinder"                   # firmware SUBGHZ_PROTOCOL_KEYFINDER_NAME
    CONST = Const(400, 1200, 150)
    PREAMBLE_MULT = 10
    PREAMBLE_DELTA_MULT = 5
    BITS = 24

    def __init__(self):
        super().__init__()
        self.end_count = 0

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
        elif step == 1:  # SaveDuration
            if d.decode_count_bit == self.BITS:            # collecting the 4-short-pulse terminator
                if level and diff(duration, c.te_short) < c.te_delta:
                    self.end_count += 1
                    if self.end_count == 4:
                        d.parser_step = 3
                        self.end_count = 0
                elif (not level) and diff(duration, c.te_short) < c.te_delta:
                    pass                                   # ignore the short LOW between terminator pulses
                else:
                    d.decode_data = 0; d.decode_count_bit = 0; self.end_count = 0; d.parser_step = 0
                return
            if level:
                d.te_last = duration; d.parser_step = 2
            else:
                d.parser_step = 0
        elif step == 2:  # CheckDuration — LOW
            if not level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta:
                    d.add_bit(1); d.parser_step = 1
                elif diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(0); d.parser_step = 1
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0
        elif step == 3:  # Finish
            if d.decode_count_bit == self.BITS and d.decode_data:
                self.emit(dict(serial=d.decode_data, bits=self.BITS))
            d.decode_data = 0; d.decode_count_bit = 0; self.end_count = 0; d.parser_step = 0


class IDo(ProtoDecoder):
    """firmware ido.c ("iDo 117/111"), 48-bit. Starts with a HIGH preamble (te_short*10) then a matching
    LOW. Each bit is a short HIGH + a LOW whose length picks the bit (te_long = 0, te_short = 1). A HIGH
    >= te_short*5 ends the frame (reports when >= 48 bits) and re-arms the preamble."""
    NAME = "iDo 117/111"                 # firmware SUBGHZ_PROTOCOL_IDO_NAME
    CONST = Const(450, 1450, 150)
    PREAMBLE_MULT = 10
    PREAMBLE_DELTA_MULT = 5
    END_SHORT_MULT = 5
    BITS = 48

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        pre = c.te_short * self.PREAMBLE_MULT
        pre_tol = c.te_delta * self.PREAMBLE_DELTA_MULT
        if step == 0:  # Reset — HIGH preamble
            if level and diff(duration, pre) < pre_tol:
                d.parser_step = 1
        elif step == 1:  # FoundPreambula — matching LOW
            if (not level) and diff(duration, pre) < pre_tol:
                d.parser_step = 2; d.decode_data = 0; d.decode_count_bit = 0
            else:
                d.parser_step = 0
        elif step == 2:  # SaveDuration — HIGH
            if level:
                if duration >= c.te_short * self.END_SHORT_MULT + c.te_delta:   # end gap HIGH
                    d.parser_step = 1
                    if d.decode_count_bit >= self.BITS and d.decode_data:
                        self.emit(dict(serial=d.decode_data, bits=d.decode_count_bit))
                    d.decode_data = 0; d.decode_count_bit = 0
                else:
                    d.te_last = duration; d.parser_step = 3
            else:
                d.parser_step = 0
        elif step == 3:  # CheckDuration — LOW picks the bit
            if not level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta * 3:
                    d.add_bit(0); d.parser_step = 2
                elif diff(d.te_last, c.te_short) < c.te_delta * 3 and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(1); d.parser_step = 2
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0


class HormannHSM(ProtoDecoder):
    """firmware hormann.c ("Hormann HSM"), 44-bit. HIGH preamble te_short*24, then a te_short LOW start.
    Bits are HIGH-then-LOW (short HI = 0, long HI = 1). A HIGH >= te_short*5 ends the frame, but only
    reports if the fixed HSM pattern bits are present ((data & 0xFF000000003) == 0xFF000000003)."""
    NAME = "Hormann HSM"                 # firmware SUBGHZ_PROTOCOL_HORMANN_HSM_NAME
    CONST = Const(500, 1000, 200)
    PREAMBLE_MULT = 24
    PREAMBLE_DELTA_MULT = 24
    END_SHORT_MULT = 5
    PATTERN = 0xFF000000003
    BITS = 44

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset — HIGH preamble
            if level and diff(duration, c.te_short * self.PREAMBLE_MULT) < c.te_delta * self.PREAMBLE_DELTA_MULT:
                d.parser_step = 1
        elif step == 1:  # FoundStartBit — a te_short LOW
            if (not level) and diff(duration, c.te_short) < c.te_delta:
                d.parser_step = 2; d.decode_data = 0; d.decode_count_bit = 0
            else:
                d.parser_step = 0
        elif step == 2:  # SaveDuration — HIGH
            if level:
                if duration >= c.te_short * self.END_SHORT_MULT and \
                        (d.decode_data & self.PATTERN) == self.PATTERN:
                    d.parser_step = 1
                    if d.decode_count_bit >= self.BITS and d.decode_data:
                        self.emit(dict(serial=d.decode_data, bits=d.decode_count_bit))
                else:
                    d.te_last = duration; d.parser_step = 3
            else:
                d.parser_step = 0
        elif step == 3:  # CheckDuration — LOW
            if not level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta:
                    d.add_bit(0); d.parser_step = 2
                elif diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(1); d.parser_step = 2
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0


class Legrand(ProtoDecoder):
    """firmware legrand.c, 18-bit, repeat-gated. LOW preamble te_short*16, then a lone HIGH first bit
    (short = 0, long = 1), then (LOW, HIGH) pairs: long LOW + short HIGH = 0, short LOW + long HIGH = 1.
    Reports only when an identical frame repeats; long-pulse tolerance ×3."""
    NAME = "Legrand"                     # firmware SUBGHZ_PROTOCOL_LEGRAND_NAME
    CONST = Const(375, 1125, 150)
    PREAMBLE_MULT = 16
    PREAMBLE_DELTA_MULT = 8
    LONG_DELTA_MULT = 3
    BITS = 18

    def __init__(self):
        super().__init__()
        self.last_data = 0

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
        elif step == 1:  # FirstBit — a lone HIGH (short = 0, long = 1)
            if level:
                if diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(0)
                if diff(duration, c.te_long) < ld:
                    d.add_bit(1)
                if d.decode_count_bit > 0:
                    d.parser_step = 2
                    return
            d.parser_step = 0
        elif step == 2:  # SaveDuration — LOW
            if not level:
                d.te_last = duration
                d.parser_step = 3
            else:
                d.parser_step = 0
        elif step == 3:  # CheckDuration — HIGH completes the (LOW,HIGH) bit
            if level:
                found = False
                if diff(d.te_last, c.te_long) < ld and diff(duration, c.te_short) < c.te_delta:
                    found = True; d.add_bit(0)
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < ld:
                    found = True; d.add_bit(1)
                if found:
                    if d.decode_count_bit < self.BITS:
                        d.parser_step = 2
                        return
                    if self.last_data and self.last_data == d.decode_data:
                        self.emit(dict(serial=d.decode_data, bits=self.BITS))
                    self.last_data = d.decode_data
            d.parser_step = 0


class X10(ProtoDecoder):
    """firmware x10.c, 32-bit. HIGH preamble te_short*16 + LOW te_short*8; each bit is a short HIGH then a
    LOW that picks it (te_short = 0, te_long = 1). A short HIGH after 32 bits ends the frame; reports only
    if the X10 complement checksum holds (byte3 = ~byte2 and byte1 = ~byte0)."""
    NAME = "X10"                         # firmware SUBGHZ_PROTOCOL_X10_NAME
    CONST = Const(600, 1800, 100)
    PRE_HI_MULT = 16; PRE_HI_DELTA = 7
    PRE_LO_MULT = 8; PRE_LO_DELTA = 5
    BITS = 32

    @staticmethod
    def _valid(data):
        return (((data >> 24) ^ (data >> 16)) & 0xFF) == 0xFF and (((data >> 8) ^ data) & 0xFF) == 0xFF

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset — HIGH preamble
            if level and diff(duration, c.te_short * self.PRE_HI_MULT) < c.te_delta * self.PRE_HI_DELTA:
                d.parser_step = 1
        elif step == 1:  # FoundPreambula — matching LOW
            if (not level) and diff(duration, c.te_short * self.PRE_LO_MULT) < c.te_delta * self.PRE_LO_DELTA:
                d.parser_step = 2; d.decode_data = 0; d.decode_count_bit = 0
            else:
                d.parser_step = 0
        elif step == 2:  # SaveDuration — a short HIGH (data) or the ending short HIGH
            if level and diff(duration, c.te_short) < c.te_delta:
                if d.decode_count_bit == self.BITS:
                    d.parser_step = 0
                    if self._valid(d.decode_data):
                        self.emit(dict(serial=d.decode_data, bits=self.BITS))
                    d.decode_data = 0; d.decode_count_bit = 0
                else:
                    d.te_last = duration; d.parser_step = 3
            else:
                d.parser_step = 0
        elif step == 3:  # CheckDuration — LOW picks the bit
            if not level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(0); d.parser_step = 2
                elif diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta * 2:
                    d.add_bit(1); d.parser_step = 2
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0


class Magellan(ProtoDecoder):
    """firmware magellan.c, 32-bit. Preamble = a train of >10 short HIGH/LOW pulses, then te_short*6 HIGH +
    te_long LOW. Bits: short HIGH + long LOW = 1, long HIGH + short LOW = 0. A LOW >= te_long*3 ends the
    frame; reports only if the CRC-8 (poly 0x31) of the first 3 bytes equals the 4th."""
    NAME = "Magellan"                    # firmware SUBGHZ_PROTOCOL_MAGELLAN_NAME
    CONST = Const(200, 400, 100)
    BITS = 32

    def __init__(self):
        super().__init__()
        self.header_count = 0

    @staticmethod
    def _crc_ok(data):
        payload = [(data >> 24) & 0xFF, (data >> 16) & 0xFF, (data >> 8) & 0xFF]
        return (data & 0xFF) == crc8(payload, 0x31, 0)

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset — a short HIGH starts the header train
            if level and diff(duration, c.te_short) < c.te_delta:
                d.parser_step = 1; d.te_last = duration; self.header_count = 0
        elif step == 1:  # CheckPreambula — count short pulses, then a short HIGH + long LOW to advance
            if level:
                d.te_last = duration
            else:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    self.header_count += 1
                elif diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta * 2 \
                        and self.header_count > 10:
                    d.parser_step = 2
                else:
                    d.parser_step = 0
        elif step == 2:  # FoundPreambula — te_short*6 HIGH + te_long LOW
            if level:
                d.te_last = duration
            else:
                if diff(d.te_last, c.te_short * 6) < c.te_delta * 3 and diff(duration, c.te_long) < c.te_delta * 2:
                    d.parser_step = 3; d.decode_data = 0; d.decode_count_bit = 0
                else:
                    d.parser_step = 0
        elif step == 3:  # SaveDuration — HIGH
            if level:
                d.te_last = duration; d.parser_step = 4
            else:
                d.parser_step = 0
        elif step == 4:  # CheckDuration — LOW
            if not level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta:
                    d.add_bit(1); d.parser_step = 3
                elif diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(0); d.parser_step = 3
                elif duration >= c.te_long * 3:
                    if d.decode_count_bit == self.BITS and self._crc_ok(d.decode_data):
                        self.emit(dict(serial=d.decode_data, bits=self.BITS))
                    d.decode_data = 0; d.decode_count_bit = 0; d.parser_step = 0
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0


class TelcomaEdge(ProtoDecoder):
    """firmware telcoma_edge.c ("Telcoma/Cardin EDGE"), 32-bit. Edge/Manchester: each pulse is one half-bit
    (te_long = two halves of the same level); a high→low pair = 1, low→high pair = 0. Any out-of-range
    duration ends the frame; reports 32 or 33 collected bits (count==33 → data>>1) only when the top byte
    is 0xFF."""
    NAME = "Telcoma/Cardin EDGE"         # firmware SUBGHZ_PROTOCOL_TELCOMA_EDGE_NAME
    CONST = Const(1270, 2540, 520)
    BITS = 32

    def __init__(self):
        super().__init__()
        self.half_pending = False
        self.half_level = False

    def _add_half(self, level):
        if not self.half_pending:
            self.half_pending = True
            self.half_level = level
            return
        self.half_pending = False
        if self.half_level and not level:
            self.d.add_bit(1)
        elif (not self.half_level) and level:
            self.d.add_bit(0)

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        if diff(duration, c.te_short) < c.te_delta:
            self._add_half(level)
        elif diff(duration, c.te_long) < c.te_delta:
            self._add_half(level)
            self._add_half(level)
        else:
            count = d.decode_count_bit
            if count == 32 or count == 33:
                key = (d.decode_data >> 1) if count == 33 else d.decode_data
                key &= 0xFFFFFFFF
                if (key >> 24) == 0xFF:
                    self.emit(dict(serial=key, bits=self.BITS))
            d.decode_data = 0
            d.decode_count_bit = 0
            self.half_pending = False


class PowerSmart(ProtoDecoder):
    """firmware power_smart.c, 64-bit Manchester. Each pulse is a Manchester event (te_long tol ×2); the
    shared arke_core.Manchester state machine yields a bit, stored INVERTED (decode_data = (data<<1)|!bit).
    Reports when the header bytes match ((data & 0xFF000000FF000000) == 0xFD000000AA000000) AND a validity
    check holds (a 16-bit field equals the complement of another, and a byte equals ~byte0 - 1)."""
    NAME = "Power Smart"                 # firmware SUBGHZ_PROTOCOL_POWER_SMART_NAME
    CONST = Const(225, 450, 100)
    HEADER = 0xFD000000AA000000
    HEADER_MASK = 0xFF000000FF000000
    BITS = 64

    def __init__(self):
        super().__init__()
        self.m = Manchester()

    @staticmethod
    def _valid(packet):
        inv = (~packet) & MASK64
        d1 = (packet >> 40) & 0xFFFF
        d2 = (inv >> 8) & 0xFFFF
        d3 = (packet >> 32) & 0xFF
        d4 = ((inv & 0xFF) - 1) & 0xFF
        return d1 == d2 and d3 == d4

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        event = 8  # ManchesterEventReset
        if not level:
            if diff(duration, c.te_short) < c.te_delta:
                event = 0  # ShortLow
            elif diff(duration, c.te_long) < c.te_delta * 2:
                event = 4  # LongLow
        else:
            if diff(duration, c.te_short) < c.te_delta:
                event = 2  # ShortHigh
            elif diff(duration, c.te_long) < c.te_delta * 2:
                event = 6  # LongHigh
        if event != 8:
            bit = self.m.advance(event)
            if bit is not None:
                d.decode_data = ((d.decode_data << 1) | (0 if bit else 1)) & MASK64
            if (d.decode_data & self.HEADER_MASK) == self.HEADER and self._valid(d.decode_data):
                self.emit(dict(serial=d.decode_data, bits=self.BITS))
                d.decode_data = 0
                d.decode_count_bit = 0
        else:
            d.decode_data = 0
            d.decode_count_bit = 0
            self.m.reset()


class CardinS508(ProtoDecoder):
    """firmware cardin_s508.c, 128-bit Manchester, repeat-gated. Threshold-classified pulses (per-level
    short/long maxima) drive the shared Manchester machine; decoded bits feed a 12-bit sync shift
    register. On sync (0x0CF4) or inverted sync (0x030B) it locks, then accumulates 128 payload bits into
    a hi:lo pair (inverted if the inverted sync matched). Reports the 128-bit value only when two
    consecutive payloads match."""
    NAME = "Cardin S508"                 # firmware SUBGHZ_PROTOCOL_CARDIN_S508_NAME
    PULSE_MIN = 40
    HI_SHORT_MAX = 165; HI_LONG_MAX = 340
    LO_SHORT_MAX = 135; LO_LONG_MAX = 300
    SYNC = 0x0CF4; SYNC_INV = 0x030B; SYNC_MASK = 0x0FFF
    PAYLOAD_BITS = 128

    def __init__(self):
        super().__init__()
        self.m = Manchester()
        self.have_last = False
        self.last_hi = 0
        self.last_lo = 0
        self._reset_acc()

    def _reset_acc(self):
        self.sync_shift = 0
        self.sync_locked = False
        self.inverted = False
        self.payload_hi = 0
        self.payload_lo = 0
        self.payload_count = 0
        self.m.reset()

    def _classify(self, level, duration):
        if duration < self.PULSE_MIN:
            return None
        short_max = self.HI_SHORT_MAX if level else self.LO_SHORT_MAX
        long_max = self.HI_LONG_MAX if level else self.LO_LONG_MAX
        if duration < short_max:
            return True
        if duration < long_max:
            return False
        return None

    def _add_bit(self, bit):
        if not self.sync_locked:
            self.sync_shift = ((self.sync_shift << 1) | (1 if bit else 0)) & self.SYNC_MASK
            if self.sync_shift == self.SYNC:
                self.sync_locked = True; self.inverted = False
            elif self.sync_shift == self.SYNC_INV:
                self.sync_locked = True; self.inverted = True
            else:
                return
            self.payload_hi = 0; self.payload_lo = 0; self.payload_count = 0
            return
        self.payload_hi = ((self.payload_hi << 1) | (self.payload_lo >> 63)) & MASK64
        self.payload_lo = ((self.payload_lo << 1) | (1 if bit else 0)) & MASK64
        self.payload_count += 1
        if self.payload_count < self.PAYLOAD_BITS:
            return
        hi = (~self.payload_hi & MASK64) if self.inverted else self.payload_hi
        lo = (~self.payload_lo & MASK64) if self.inverted else self.payload_lo
        match = self.have_last and self.last_hi == hi and self.last_lo == lo
        self.last_hi = hi; self.last_lo = lo; self.have_last = True
        self.sync_locked = False; self.sync_shift = 0
        if not match:
            return
        self.emit(dict(serial=(hi << 64) | lo, bits=self.PAYLOAD_BITS))

    def feed(self, level, duration):
        is_short = self._classify(level, duration)
        if is_short is None:
            self._reset_acc()
            return
        event = (2 if level else 0) if is_short else (6 if level else 4)
        bit = self.m.advance(event)
        if bit is not None:
            self._add_bit(1 if bit else 0)


class SomfyTelis(ProtoDecoder):
    """firmware somfy_telis.c, 56-bit Manchester. A te_short*4 HIGH/LOW preamble train then a te_short*7
    HIGH starts the data (Manchester primed with a LongHigh). Reports when 56 bits are collected and the
    Somfy CRC-4 over the de-obfuscated frame (data ^ data>>8) checks. Emits the de-obfuscated serial/btn/cnt."""
    NAME = "Somfy Telis"                 # firmware SUBGHZ_PROTOCOL_SOMFY_TELIS_NAME
    CONST = Const(640, 1280, 250)
    BITS = 56

    def __init__(self):
        super().__init__()
        self.m = Manchester()
        self.header_count = 0

    @staticmethod
    def _crc(data):
        data &= 0xFFF0FFFFFFFFFF
        crc = 0
        for i in range(0, 56, 8):
            crc = crc ^ (data >> i) ^ (data >> (i + 4))
        return crc & 0xF

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        event = 8
        if step == 0:  # Reset — te_short*4 HIGH
            if level and diff(duration, c.te_short * 4) < c.te_delta * 4:
                d.parser_step = 1; self.header_count += 1
        elif step == 1:  # FoundPreambula — te_short*4 LOW
            if (not level) and diff(duration, c.te_short * 4) < c.te_delta * 4:
                d.parser_step = 2
            else:
                self.header_count = 0; d.parser_step = 0
        elif step == 2:  # CheckPreambula — another preamble HIGH, or the te_short*7 data-start HIGH
            if level:
                if diff(duration, c.te_short * 4) < c.te_delta * 4:
                    d.parser_step = 1; self.header_count += 1
                elif self.header_count > 1 and diff(duration, c.te_short * 7) < c.te_delta * 4:
                    d.parser_step = 3; d.decode_data = 0; d.decode_count_bit = 0; self.header_count = 0
                    self.m.reset(); self.m.advance(6)   # prime LongHigh
        elif step == 3:  # DecoderData
            if not level:
                if diff(duration, c.te_short) < c.te_delta:
                    event = 0   # ShortLow
                elif diff(duration, c.te_long) < c.te_delta:
                    event = 4   # LongLow
                elif duration >= c.te_long + c.te_delta:   # end gap
                    if d.decode_count_bit == self.BITS:
                        clear = d.decode_data ^ (d.decode_data >> 8)
                        if ((clear >> 40) & 0xF) == self._crc(clear):
                            self.emit(dict(serial=clear & 0xFFFFFF, btn=(clear >> 44) & 0xF,
                                           cnt=(clear >> 24) & 0xFFFF, data=d.decode_data, bits=self.BITS))
                    d.decode_data = 0; d.decode_count_bit = 0
                    self.m.reset(); self.m.advance(6)
                    d.parser_step = 0
                else:
                    d.parser_step = 0
            else:
                if diff(duration, c.te_short) < c.te_delta:
                    event = 2   # ShortHigh
                elif diff(duration, c.te_long) < c.te_delta:
                    event = 6   # LongHigh
                else:
                    d.parser_step = 0
            if event != 8:
                bit = self.m.advance(event)
                if bit is not None:
                    d.decode_data = ((d.decode_data << 1) | (1 if bit else 0)) & MASK64
                    d.decode_count_bit += 1


class CameTwee(ProtoDecoder):
    """firmware came_twee.c, 54-bit Manchester. A te_long*51 LOW preamble starts the data (Manchester
    primed with LongLow, LongHigh, ShortLow); bits are stored INVERTED. Reports any 54-bit frame (no CRC);
    a LOW >= te_long*2+te_delta ends it and re-primes."""
    NAME = "CAME TWEE"                   # firmware SUBGHZ_PROTOCOL_CAME_TWEE_NAME
    CONST = Const(500, 1000, 250)
    BITS = 54

    def __init__(self):
        super().__init__()
        self.m = Manchester()

    def _prime(self):
        self.m.advance(4); self.m.advance(6); self.m.advance(0)   # LongLow, LongHigh, ShortLow

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        event = 8
        if step == 0:  # Reset — te_long*51 LOW preamble
            if (not level) and diff(duration, c.te_long * 51) < c.te_delta * 20:
                d.parser_step = 1; d.decode_data = 0; d.decode_count_bit = 0
                self._prime()
        elif step == 1:  # DecoderData
            if not level:
                if diff(duration, c.te_short) < c.te_delta:
                    event = 0   # ShortLow
                elif diff(duration, c.te_long) < c.te_delta:
                    event = 4   # LongLow
                elif duration >= c.te_long * 2 + c.te_delta:   # end gap
                    if d.decode_count_bit == self.BITS:
                        self.emit(dict(serial=d.decode_data, bits=self.BITS))
                    d.decode_data = 0; d.decode_count_bit = 0
                    self._prime()
                else:
                    d.parser_step = 0
            else:
                if diff(duration, c.te_short) < c.te_delta:
                    event = 2   # ShortHigh
                elif diff(duration, c.te_long) < c.te_delta:
                    event = 6   # LongHigh
                else:
                    d.parser_step = 0
            if event != 8:
                bit = self.m.advance(event)
                if bit is not None:
                    d.decode_data = ((d.decode_data << 1) | (0 if bit else 1)) & MASK64
                    d.decode_count_bit += 1


class IntertechnoV3(ProtoDecoder):
    """firmware intertechno_v3.c, 32-bit (or 36-bit dimming) OOK. te_short*37 LOW preamble, a short HIGH +
    te_short*10 LOW sync, then each bit is framed as HIGH / LOW(te_last) / HIGH / LOW: the SaveDuration LOW
    picks the bit (te_short=0, te_long=1). A LOW >= te_short*11 ends and reports at 32 or 36 bits."""
    NAME = "Intertechno_V3"              # firmware SUBGHZ_PROTOCOL_INTERTECHNO_V3_NAME
    CONST = Const(275, 1375, 150)
    BITS = 32
    DIMMING_BITS = 36

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset — te_short*37 LOW
            if (not level) and diff(duration, c.te_short * 37) < c.te_delta * 15:
                d.parser_step = 1
        elif step == 1:  # StartSync — short HIGH
            d.parser_step = 2 if (level and diff(duration, c.te_short) < c.te_delta) else 0
        elif step == 2:  # FoundSync — te_short*10 LOW
            if (not level) and diff(duration, c.te_short * 10) < c.te_delta * 3:
                d.parser_step = 3; d.decode_data = 0; d.decode_count_bit = 0
            else:
                d.parser_step = 0
        elif step == 3:  # StartDuration — short HIGH
            d.parser_step = 4 if (level and diff(duration, c.te_short) < c.te_delta) else 0
        elif step == 4:  # SaveDuration — LOW (bit low, or end gap)
            if not level:
                if duration >= c.te_short * 11:
                    d.parser_step = 1
                    if d.decode_count_bit == self.BITS or d.decode_count_bit == self.DIMMING_BITS:
                        self.emit(dict(serial=d.decode_data, bits=d.decode_count_bit))
                else:
                    d.te_last = duration; d.parser_step = 5
            else:
                d.parser_step = 0
        elif step == 5:  # CheckDuration — HIGH; te_last (the LOW) picks the bit
            if level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(0); d.parser_step = 6
                elif diff(d.te_last, c.te_long) < c.te_delta * 2 and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(1); d.parser_step = 6
                elif diff(d.te_last, c.te_short) < c.te_delta * 2 and diff(duration, c.te_short) < c.te_delta \
                        and d.decode_count_bit == 27:
                    d.add_bit(0); d.parser_step = 6
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0
        elif step == 6:  # EndDuration — short or long LOW
            if (not level) and (diff(duration, c.te_short) < c.te_delta or diff(duration, c.te_long) < c.te_delta * 2):
                d.parser_step = 3
            else:
                d.parser_step = 0


def _atomo_decrypt(buff):
    """firmware came_atomo.c atomo_decrypt — keyless LFSR-style descramble over 8 bytes, in place."""
    buff[0] = (buff[0] ^ 5) & 0x7F
    tmpB = (-buff[0]) & 0x7F
    bit_cnt = 8
    while bit_cnt < 59:
        if (tmpB & 0x18) and (((tmpB // 8) & 3) != 3):
            tmpB = ((tmpB << 1) & 0xFF) | 1
        else:
            tmpB = (tmpB << 1) & 0xFF
        if tmpB & 0x80:
            buff[bit_cnt // 8] ^= (0x80 >> (bit_cnt & 7))
        bit_cnt += 1


def _atomo_encrypt(buff):
    """firmware came_atomo.c atomo_encrypt — inverse of _atomo_decrypt (used for validation)."""
    tmpB = (~buff[0] + 1) & 0x7F
    bit_cnt = 8
    while bit_cnt < 59:
        if (tmpB & 0x18) and (((tmpB // 8) & 3) != 3):
            tmpB = ((tmpB << 1) & 0xFF) | 1
        else:
            tmpB = (tmpB << 1) & 0xFF
        if tmpB & 0x80:
            buff[bit_cnt // 8] ^= (0x80 >> (bit_cnt & 7))
        bit_cnt += 1
    buff[0] = (buff[0] ^ 5) & 0x7F


class CameAtomo(ProtoDecoder):
    """firmware came_atomo.c, 62-bit Manchester rolling code. te=600/1200. Captures the biphase
    frame (primed Reset+ShortLow, inverted store), then the keyless atomo descramble yields
    serial/cnt/btn — no external keystore needed."""
    NAME = "CAME Atomo"                   # firmware SUBGHZ_PROTOCOL_CAME_ATOMO_NAME
    CONST = Const(600, 1200, 250)
    BITS = 62

    def _prime(self):
        self.m.reset()
        self.m.advance(0)   # ManchesterEventShortLow

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        if not hasattr(self, "m"):
            self.m = Manchester()
        step = d.parser_step
        if step == 0:  # Reset — preamble LOW (te_long*10 or te_long*60)
            if (not level) and (diff(duration, c.te_long * 10) < c.te_delta * 20 or
                                diff(duration, c.te_long * 60) < c.te_delta * 40):
                d.parser_step = 1
                d.decode_data = 0
                d.decode_count_bit = 1
                self._prime()
            return
        # step 1: DecoderData
        event = None
        if not level:
            if diff(duration, c.te_short) < c.te_delta:
                event = 0   # ShortLow
            elif diff(duration, c.te_long) < c.te_delta:
                event = 4   # LongLow
            elif duration >= c.te_long * 2 + c.te_delta:
                if d.decode_count_bit == self.BITS:
                    data = d.decode_data & MASK64
                    serial, cnt, cnt_2, btn = self._descramble(data)
                    self.emit(dict(serial=serial, cnt=cnt, btn=btn, bits=self.BITS, data=data))
                d.decode_data = 0
                d.decode_count_bit = 1
                self._prime()
                return
            else:
                d.parser_step = 0
                return
        else:
            if diff(duration, c.te_short) < c.te_delta:
                event = 2   # ShortHigh
            elif diff(duration, c.te_long) < c.te_delta:
                event = 6   # LongHigh
            else:
                d.parser_step = 0
                return
        bit = self.m.advance(event)
        if bit is not None:
            d.decode_data = (d.decode_data << 1) | (0 if bit else 1)   # inverted store
            d.decode_count_bit += 1

    @staticmethod
    def _descramble(data):
        data ^= 0xFFFFFFFFFFFFFFFF
        data = (data << 4) & 0xFFFFFFFFFFFFFFFF
        pack = [(data >> (56 - 8 * i)) & 0xFF for i in range(8)]
        _atomo_decrypt(pack)
        cnt_2 = pack[0]
        cnt = (pack[1] << 8) | pack[2]
        serial = (pack[3] << 24) | (pack[4] << 16) | (pack[5] << 8) | pack[6]
        btn_decode = pack[7] >> 4
        btn = {0x0: 0x1, 0x2: 0x2, 0x4: 0x3, 0x6: 0x4}.get(btn_decode, 0)
        return serial, cnt, cnt_2, btn


class MegaCode(ProtoDecoder):
    """firmware megacode.c, 24-bit pulse-position OOK (Linear MegaCode, 315MHz). te=1000.
    Start bit is a lone short HIGH (=1). Each later bit is framed by a LOW gap whose length
    (adjusted by -3*te when the previous bit was 0) picks 1 (~5te) or 0 (~2te). A LOW >= 10te ends."""
    NAME = "MegaCode"                    # firmware SUBGHZ_PROTOCOL_MEGACODE_NAME
    CONST = Const(1000, 1000, 200)
    BITS = 24

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset
            if (not level) and diff(duration, c.te_short * 13) < c.te_delta * 17:
                d.parser_step = 1
        elif step == 1:  # FoundStartBit — lone short HIGH = start bit 1
            if level and diff(duration, c.te_short) < c.te_delta:
                d.decode_data = 0; d.decode_count_bit = 0
                d.add_bit(1); self.last_bit = 1
                d.parser_step = 2
            else:
                d.parser_step = 0
        elif step == 2:  # SaveDuration — LOW gap or end
            if not level:
                if duration >= c.te_short * 10:
                    d.parser_step = 0
                    if d.decode_count_bit == self.BITS:
                        self.emit(dict(serial=d.decode_data, bits=d.decode_count_bit))
                else:
                    d.te_last = (duration - c.te_short * 3) if not self.last_bit else duration
                    d.parser_step = 3
            else:
                d.parser_step = 0
        elif step == 3:  # CheckDuration — HIGH; adjusted gap picks the bit
            if level:
                if diff(d.te_last, c.te_short * 5) < c.te_delta * 5 and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(1); self.last_bit = 1; d.parser_step = 2
                elif diff(d.te_last, c.te_short * 2) < c.te_delta * 2 and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(0); self.last_bit = 0; d.parser_step = 2
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0


class ChamberlainCode(ProtoDecoder):
    """firmware chamberlain_code.c, Chamberlain/Liftmaster trinary (7/8/9 dip). te=1000/3000.
    Collects 4-bit symbols (STOP=0b0001, 1=0b0011, 0=0b0111) from (gap,pulse) pairs; a terminal
    LOW >5te triggers a mask match that strips framing and converts nibbles to 7/8/9 output bits."""
    NAME = "Cham_Code"                   # firmware SUBGHZ_PROTOCOL_CHAMB_CODE_NAME
    CONST = Const(1000, 3000, 200)
    MIN_BITS = 10
    _STOP = 0b0001; _B1 = 0b0011; _B0 = 0b0111
    _M7 = 0xF000000FF0F; _C7 = 0x10000001101
    _M8 = 0xF00000F00F;  _C8 = 0x1000001001
    _M9 = 0xF000000000F; _C9 = 0x10000000001

    def _to_bit(self, data, size):
        res = 0
        for i in range(size):
            nib = data & 0xF
            if nib == self._B0:
                pass            # bit 0
            elif nib == self._B1:
                res |= (1 << i)  # bit 1
            else:
                return None
            data >>= 4
        return res

    def _parse(self, d):
        if d.decode_count_bit > self.MIN_BITS + 1:
            return None
        data = d.decode_data
        if (data & self._M7) == self._C7:
            n = 7; data &= ~self._M7
            data = (data >> 12) | ((data >> 4) & 0xF)
        elif (data & self._M8) == self._C8:
            n = 8; data &= ~self._M8
            data = (data >> 4) | (self._B0 << 8)
        elif (data & self._M9) == self._C9:
            n = 9; data &= ~self._M9
            data = data >> 4
        else:
            return None
        bits = self._to_bit(data, n)
        return None if bits is None else (bits, n)

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset
            if (not level) and diff(duration, c.te_short * 39) < c.te_delta * 20:
                d.parser_step = 1
        elif step == 1:  # FoundStartBit
            if level and diff(duration, c.te_short) < c.te_delta:
                d.decode_data = self._STOP
                d.decode_count_bit = 1
                d.parser_step = 2
            else:
                d.parser_step = 0
        elif step == 2:  # SaveDuration
            if not level:
                if duration > c.te_short * 5:
                    if d.decode_count_bit >= self.MIN_BITS:
                        r = self._parse(d)
                        if r is not None:
                            self.emit(dict(serial=r[0], bits=r[1]))
                    d.parser_step = 0
                else:
                    d.te_last = duration; d.parser_step = 3
            else:
                d.parser_step = 0
        elif step == 3:  # CheckDuration — (te_last, duration) selects the symbol
            if level:
                if diff(d.te_last, c.te_short * 3) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.decode_data = (d.decode_data << 4) | self._STOP; d.decode_count_bit += 1; d.parser_step = 2
                elif diff(d.te_last, c.te_short * 2) < c.te_delta and diff(duration, c.te_short * 2) < c.te_delta:
                    d.decode_data = (d.decode_data << 4) | self._B1; d.decode_count_bit += 1; d.parser_step = 2
                elif diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_short * 3) < c.te_delta:
                    d.decode_data = (d.decode_data << 4) | self._B0; d.decode_count_bit += 1; d.parser_step = 2
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0


class Prastel(ProtoDecoder):
    """firmware prastel.c, 42-bit PWM OOK. te=320/640. bit0 = short LOW + long HIGH; bit1 = long LOW
    + short HIGH. Serial/btn/cnt come from the firmware's unpack+unscramble (display-only maths)."""
    NAME = "Prastel"                     # firmware SUBGHZ_PROTOCOL_PRASTEL_NAME
    CONST = Const(320, 640, 150)
    BITS = 42

    @staticmethod
    def _rev(v):
        v = ((v >> 4) | (v << 4)) & 0xFF
        v = (((v & 0xCC) >> 2) | ((v & 0x33) << 2)) & 0xFF
        v = (((v & 0xAA) >> 1) | ((v & 0x55) << 1)) & 0xFF
        return v

    @classmethod
    def _unscramble(cls, p):
        carry = 0
        for _ in range(3):
            for i in range(2, 7):
                cur = p[i]
                p[i] = ((carry << 7) | (cur >> 1)) & 0xFF
                carry = cur & 1
        p[0] = cls._rev(p[0])
        t2 = p[2] & 0xDF
        p[1] = cls._rev(p[1])
        p[3] = cls._rev(p[3])
        p[4] = cls._rev(p[4])
        swapped = ((t2 << 4) | (t2 >> 4)) & 0xFF
        p[2] = cls._rev((~swapped) & 0xFF)
        p6 = p[6]
        p[5] = cls._rev(p[5])
        if p6 & 1:
            p[5] ^= 1
        a = (p[5] * 21) & 0xFF
        b = (a + 1) & 0xFF
        t = (b * 21) & 0xFF
        x = cls._rev(p6)
        n = (t + 1) & 0xFF
        if n != 0:
            limit = (~t) & 0xFF
            i = 0
            while True:
                x = (x * 21 + 1) & 0xFF
                i += 1
                if not (limit > i):
                    break
        p[5] = n ^ 0xA7
        p[6] = x ^ 0x69

    @classmethod
    def _decode(cls, data):
        payload = (((data >> 30) & 0xFFF) << 32) | ((data << 2) & 0xFFFFFFFF)
        p = [(payload >> (48 - 8 * i)) & 0xFF for i in range(7)]
        cls._unscramble(p)
        serial = ((p[1] & 0xF0) << 8) | p[3] | ((p[4] << 12) & 0xF0000) | \
                 ((p[2] & 0x0F) << 8) | ((p[2] & 0x80) << 13)
        idx = (p[4] & 0x0F) - 7
        bmap = [4, 0, 0, 0, 3, 0, 2, 1]
        btn = bmap[idx] if 0 <= idx <= 7 else 0
        cnt = p[5] | (p[6] << 8)
        return serial, btn, cnt

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset
            if (not level) and c.te_short * 12 < duration < c.te_short * 86:
                d.parser_step = 1
        elif step == 1:  # FoundStartBit
            if not level:
                return
            if diff(duration, c.te_short) < c.te_delta:
                d.parser_step = 2; d.decode_data = 0; d.decode_count_bit = 0
            else:
                d.parser_step = 0
        elif step == 2:  # SaveDuration
            if not level:
                if duration >= c.te_short * 4:
                    d.parser_step = 1
                    if d.decode_count_bit == self.BITS:
                        data = d.decode_data & MASK64
                        serial, btn, cnt = self._decode(data)
                        self.emit(dict(serial=serial, btn=btn, cnt=cnt,
                                       bits=d.decode_count_bit, data=data & 0x3FFFFFFFFFF))
                else:
                    d.te_last = duration; d.parser_step = 3
            else:
                d.parser_step = 0
        elif step == 3:  # CheckDuration
            if level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta:
                    d.add_bit(0); d.parser_step = 2
                elif diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(1); d.parser_step = 2
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0


# ---------------------------------------------------------------------------
# Nice FloR-S / Nice One / Nice O-Code  (firmware nice_flor_s.c + nice_flor_s_o.h)
#
# The 32-byte substitution table is the Nice cipher S-box. In the firmware it
# ships as an AES-256 keystore asset (applications/main/subghz/resources/subghz/
# assets/nice_flor_s) that is decrypted at runtime with the Flipper's hardware
# secure-enclave key (slot 1) — the plaintext is never in the source and cannot
# be recovered off-device. To light up serial/cnt/btn here, dump the 32 decrypted
# bytes from a Flipper and paste them into NICE_FLOR_S_TABLE below. While the
# table is all-zero (unset) the decoder still DETECTS and captures the frame
# (raw data + bit count); it just can't resolve the identity.
# ---------------------------------------------------------------------------
# The 32-byte Nice S-box is not stored in the clear. It uses the SAME obfuscation scheme as
# keeloq_keys.py (SHA-256 keystream XOR + base64, seed in-file), decoded once at import, so the
# whole project is uniform. Obfuscation, not security: anyone who runs the module can recover the
# bytes (as with any client-side key) — it just keeps the raw S-box out of the shipped source/APK.
_NICE_FLOR_S_SEED = b"FoxFW-nice-flor-s-sbox-v1"
_NICE_FLOR_S_ENC = "ChKLLpC9fIr+SH9e6usg4cJy4gZNnu9KIehkA1AHDFQ="


def _nice_flor_s_table():
    try:
        import hashlib, base64
        data = base64.b64decode(_NICE_FLOR_S_ENC)
        ks = bytearray()
        ctr = 0
        while len(ks) < len(data):
            ks += hashlib.sha256(_NICE_FLOR_S_SEED + ctr.to_bytes(4, "big")).digest()
            ctr += 1
        return bytes(a ^ b for a, b in zip(data, ks))
    except Exception:
        return bytes(32)   # fall back to capture-only; never break the module


NICE_FLOR_S_TABLE = _nice_flor_s_table()
NICE_FLOR_S_IC_PLAIN = 0xFFFF


def _nice_o_cnt_swap(cnt):
    b = cnt & 0x2492
    return (cnt ^ (((b << 1) | ((b ^ 0x2492) >> 1)) & 0xFFFF)) & 0xFFFF


def _nice_o_mask(n):
    a = (0x0E - n) & 0x0F
    b = (n + 2) & 0xFFFFFFFF
    odd = n & 1
    c = b + (odd << 1)
    bn = b & 0x0F
    cn = c & 0x0F
    m = (a << 8) | (bn << 4) | (bn << 20) | (a << 24) | cn | (cn << 16)
    m |= (((((b ^ (0x0E - n) ^ c) & 0x0F) + (odd << 2)) << 12) & 0xFFFF)
    mask_32 = m & 0x0FFFFFFF
    mask_16 = ((m & 0x00FF) << 8) | ((m >> 8) & 0x00FF)
    return mask_32, mask_16 & 0xFFFF


def _nice_bytes_le(data):
    return [(data >> (8 * i)) & 0xFF for i in range(8)]


def _nice_from_le(p):
    v = 0
    for i in range(8):
        v |= (p[i] & 0xFF) << (8 * i)
    return v


def _nice_magic_xor(p, k):
    for i in range(1, 6):
        p[i] ^= k & 0xFF


def _nice_o_encrypt(data, ic, buf):
    p = _nice_bytes_le(data)
    for y in range(2):
        k = buf[p[0] & 0x1F]
        _nice_magic_xor(p, k); p[5] &= 0x0F; p[0] ^= k & 0xE0
        k = (buf[p[0] >> 3] + 0x25) & 0xFF
        _nice_magic_xor(p, k); p[5] &= 0x0F; p[0] ^= k & 0x7
        if y == 0:
            p[0], p[1] = p[1], p[0]
    p[5] = ~p[5] & 0x0F
    k = ~p[4] & 0xFF; p[4] = p[0] ^ ((ic >> 8) & 0xFF); p[0] = ~p[2] & 0xFF; p[2] = k
    k = p[1] ^ (ic & 0xFF); p[1] = ~p[3] & 0xFF; p[3] = k
    return _nice_from_le(p)


def _nice_o_decrypt(data, ic, buf):
    p = _nice_bytes_le(data)
    k = p[4] ^ ((ic >> 8) & 0xFF); p[5] = ~p[5] & 0xFF; p[4] = ~p[2] & 0xFF; p[2] = ~p[0] & 0xFF; p[0] = k
    k = p[3] ^ (ic & 0xFF); p[3] = ~p[1] & 0xFF; p[1] = k
    for y in range(2):
        k = (buf[p[0] >> 3] + 0x25) & 0xFF
        _nice_magic_xor(p, k); p[5] &= 0x0F; p[0] ^= k & 0x7
        k = buf[p[0] & 0x1F]
        _nice_magic_xor(p, k); p[5] &= 0x0F; p[0] ^= k & 0xE0
        if y == 0:
            p[0], p[1] = p[1], p[0]
    return _nice_from_le(p)


def _nice_o_decode_frame(data, ic, buf):
    parcel = ((((data >> 48) & 0x0F) ^ ((data >> 44) & 0x0F)) ^ 0x0F) & 0x0F
    mask_32, mask_16 = _nice_o_mask(parcel)
    dec = _nice_o_decrypt(data, (ic ^ mask_16) & 0xFFFF, buf)
    cnt = _nice_o_cnt_swap(dec & 0xFFFF)
    serial = ((dec >> 16) ^ mask_32) & 0x0FFFFFFF
    btn = (data >> 48) & 0x0F
    return serial, cnt, btn


class NiceFlorS(ProtoDecoder):
    """firmware nice_flor_s.c — Nice FloR-S (52b), Nice One (72b), Nice O-Code (112b). te=500/1000/300.
    Header te_short*38 LOW, te_short*3 HIGH, te_short*3 LOW; then PWM bits (bit0 = short HIGH + long LOW,
    bit1 = long HIGH + short LOW), terminated by a te_short*3 HIGH. decode_data is 64-bit so the firmware
    stashes the first 52 bits in data_2 and continues in decode_data; we recombine to one big int.
    Serial/cnt/btn need the 32-byte Nice S-box (NICE_FLOR_S_TABLE); until it is set, capture-only."""
    NAME = "Nice FloR-S"                  # firmware SUBGHZ_PROTOCOL_NICE_FLOR_S_NAME
    CONST = Const(500, 1000, 300)
    MIN_BITS = 52
    ONE_BITS = 72
    O_LONG_BITS = 112

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset
            if (not level) and diff(duration, c.te_short * 38) < c.te_delta * 38:
                d.parser_step = 1
        elif step == 1:  # CheckHeader — HIGH ~te_short*3
            d.parser_step = 2 if (level and diff(duration, c.te_short * 3) < c.te_delta * 3) else 0
        elif step == 2:  # FoundHeader — LOW ~te_short*3
            if (not level) and diff(duration, c.te_short * 3) < c.te_delta * 3:
                d.parser_step = 3; d.decode_data = 0; d.decode_count_bit = 0; self._data2 = 0
            else:
                d.parser_step = 0
        elif step == 3:  # SaveDuration — HIGH: terminator or bit's high half
            if level:
                if diff(duration, c.te_short * 3) < c.te_delta:
                    d.parser_step = 0
                    n = d.decode_count_bit
                    if n in (self.MIN_BITS, self.ONE_BITS, self.O_LONG_BITS):
                        extra = n - self.MIN_BITS
                        full = ((self._data2 & MASK64) << extra) | (d.decode_data & ((1 << extra) - 1 if extra else 0))
                        if extra == 0:
                            full = self._data2 & MASK64
                        self._report(full, n)
                else:
                    d.te_last = duration; d.parser_step = 4
            # a LOW while in SaveDuration is ignored (stays in step 3), matching firmware
        elif step == 4:  # CheckDuration — LOW: (te_last HIGH, duration LOW) picks the bit
            if not level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta:
                    d.add_bit(0); d.parser_step = 3
                elif diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(1); d.parser_step = 3
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0
            if d.decode_count_bit == self.MIN_BITS:   # 64-bit split at 52 bits
                self._data2 = d.decode_data
                d.decode_data = 0

    def _report(self, full, nbits):
        res = dict(bits=nbits, data=full & MASK64)
        if any(NICE_FLOR_S_TABLE):            # table provided → resolve identity
            if nbits == self.MIN_BITS:        # plain Nice FloR-S
                plain = _nice_o_decrypt(full & 0xFFFFFFFFFFF, NICE_FLOR_S_IC_PLAIN, NICE_FLOR_S_TABLE)
                res["serial"] = (plain >> 16) & 0x0FFFFFFF
                res["cnt"] = plain & 0xFFFF
                res["btn"] = (full >> 48) & 0x0F
            else:                              # Nice One / O-Code: decode the 72-bit body
                body = (full >> (nbits - self.ONE_BITS)) & 0xFFFFFFFFFFFFFFFF
                serial, cnt, btn = _nice_o_decode_frame(body, NICE_FLOR_S_IC_PLAIN, NICE_FLOR_S_TABLE)
                res["serial"] = serial; res["cnt"] = cnt; res["btn"] = btn
        else:
            res["serial"] = full & MASK64     # no table: raw frame as fallback id
        self.emit(res)


class Marantec(ProtoDecoder):
    """firmware marantec.c, 49-bit Manchester. te=1000/2000. Preamble LOW te_long*5; decode starts
    primed (data=1,count=1), NON-inverted store; end gap >=2te_long+delta reports and re-primes."""
    NAME = "Marantec"
    CONST = Const(1000, 2000, 200)
    BITS = 49

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        if not hasattr(self, "m"):
            self.m = Manchester()
        if d.parser_step == 0:
            if (not level) and diff(duration, c.te_long * 5) < c.te_delta * 8:
                d.parser_step = 1; d.decode_data = 1; d.decode_count_bit = 1; self.m.reset()
            return
        event = None
        if not level:
            if diff(duration, c.te_short) < c.te_delta:
                event = 0
            elif diff(duration, c.te_long) < c.te_delta:
                event = 4
            elif duration >= c.te_long * 2 + c.te_delta:
                if d.decode_count_bit == self.BITS:
                    data = d.decode_data & MASK64
                    self.emit(dict(serial=((data >> 12) & 0xFFFFFF00) | ((data >> 8) & 0xFF),
                                   btn=(data >> 16) & 0xF, bits=self.BITS, data=data))
                d.decode_data = 1; d.decode_count_bit = 1; self.m.reset()
                return
            else:
                d.parser_step = 0; return
        else:
            if diff(duration, c.te_short) < c.te_delta:
                event = 2
            elif diff(duration, c.te_long) < c.te_delta:
                event = 6
            else:
                d.parser_step = 0; return
        bit = self.m.advance(event)
        if bit is not None:
            d.decode_data = ((d.decode_data << 1) | (1 if bit else 0)) & MASK64
            d.decode_count_bit += 1


class KeyFinder2(ProtoDecoder):
    """firmware keyfinder2.c, 11-bit. te=350/1020. Three 'wide' header bits (1500/2500 symbols) then
    normal PWM bits (bit1 = short HIGH + long LOW, bit0 = long HIGH + short LOW). btn = data & 0x7."""
    NAME = "KeyFinder2"
    CONST = Const(350, 1020, 150)
    BITS = 11
    WS = 1500; WL = 2500; WD = 350; HEADER = 3

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:   # Reset — HIGH wide
            if level and (diff(duration, self.WS) < self.WD or diff(duration, self.WL) < self.WD):
                d.decode_data = 0; d.decode_count_bit = 0; d.te_last = duration; d.parser_step = 2
        elif step == 1:  # SaveWide — HIGH
            if level:
                d.te_last = duration; d.parser_step = 2
            else:
                d.parser_step = 0
        elif step == 2:  # CheckWide — LOW
            if not level:
                if diff(d.te_last, self.WS) < self.WD and diff(duration, self.WL) < self.WD:
                    d.add_bit(1)
                elif diff(d.te_last, self.WL) < self.WD and diff(duration, self.WS) < self.WD:
                    d.add_bit(0)
                else:
                    d.parser_step = 0; return
                d.parser_step = 3 if d.decode_count_bit == self.HEADER else 1
            else:
                d.parser_step = 0
        elif step == 3:  # SaveDuration — HIGH
            if level:
                d.te_last = duration; d.parser_step = 4
            else:
                d.parser_step = 0
        elif step == 4:  # CheckDuration — LOW
            if not level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta:
                    d.add_bit(1)
                elif diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(0)
                else:
                    d.parser_step = 0; return
                if d.decode_count_bit == self.BITS:
                    v = d.decode_data & 0x7FF
                    self.emit(dict(serial=v, btn=v & 0x7, bits=self.BITS, data=v))
                    d.parser_step = 0
                else:
                    d.parser_step = 3
            else:
                d.parser_step = 0


class Dooya(ProtoDecoder):
    """firmware dooya.c, 40-bit PWM. te=366/733. Preamble LOW te_long*12 + start LOW te_long*2; bit0 =
    short HIGH + long LOW, bit1 = long HIGH + short LOW; terminal LOW >=te_long*4 completes + reports."""
    NAME = "Dooya"
    CONST = Const(366, 733, 120)
    BITS = 40

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:
            if (not level) and diff(duration, c.te_long * 12) < c.te_delta * 20:
                d.parser_step = 1
        elif step == 1:  # FoundStartBit
            if not level:
                if diff(duration, c.te_long * 2) < c.te_delta * 3:
                    d.parser_step = 2; d.decode_data = 0; d.decode_count_bit = 0
                else:
                    d.parser_step = 0
            elif diff(duration, c.te_short * 13) < c.te_delta * 5:
                pass
            else:
                d.parser_step = 0
        elif step == 2:  # SaveDuration — HIGH
            if level:
                d.te_last = duration; d.parser_step = 3
            else:
                d.parser_step = 0
        elif step == 3:  # CheckDuration — LOW
            if not level:
                if duration >= c.te_long * 4:
                    if diff(d.te_last, c.te_short) < c.te_delta:
                        d.add_bit(0)
                    elif diff(d.te_last, c.te_long) < c.te_delta * 2:
                        d.add_bit(1)
                    else:
                        d.parser_step = 0; return
                    d.parser_step = 1
                    if d.decode_count_bit == self.BITS:
                        data = d.decode_data & MASK64
                        self.emit(dict(serial=data >> 16,
                                       cnt=((data >> 8) & 0x0F) if ((data >> 12) & 0x0F) else 0xFF,
                                       btn=data & 0xFF, bits=self.BITS, data=data))
                elif diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta * 2:
                    d.add_bit(0); d.parser_step = 2
                elif diff(d.te_last, c.te_long) < c.te_delta * 2 and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(1); d.parser_step = 2
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0


class NeroSketch(ProtoDecoder):
    """firmware nero_sketch.c, 40-bit PWM. te=330/660. Long short-pulse preamble (>40 counts) then
    bit0 = short HIGH + long LOW, bit1 = long HIGH + short LOW; terminal HIGH >=2te+2delta reports."""
    NAME = "Nero Sketch"
    CONST = Const(330, 660, 150)
    BITS = 40

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:
            if level and diff(duration, c.te_short) < c.te_delta:
                d.parser_step = 1; d.te_last = duration; self._hdr = 0
        elif step == 1:  # CheckPreambula
            if level:
                if diff(duration, c.te_short) < c.te_delta or diff(duration, c.te_short * 4) < c.te_delta:
                    d.te_last = duration
                else:
                    d.parser_step = 0
            elif diff(duration, c.te_short) < c.te_delta:
                if diff(d.te_last, c.te_short) < c.te_delta:
                    self._hdr += 1
                elif diff(d.te_last, c.te_short * 4) < c.te_delta:
                    if self._hdr > 40:
                        d.parser_step = 2; d.decode_data = 0; d.decode_count_bit = 0
                    else:
                        d.parser_step = 0
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0
        elif step == 2:  # SaveDuration — HIGH
            if level:
                if duration >= c.te_short * 2 + c.te_delta * 2:
                    d.parser_step = 0
                    if d.decode_count_bit == self.BITS:
                        data = d.decode_data & MASK64
                        self.emit(dict(serial=data, bits=self.BITS, data=data))
                    d.decode_data = 0; d.decode_count_bit = 0
                else:
                    d.te_last = duration; d.parser_step = 3
            else:
                d.parser_step = 0
        elif step == 3:  # CheckDuration — LOW
            if not level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta:
                    d.add_bit(0); d.parser_step = 2
                elif diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(1); d.parser_step = 2
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0


class PhoenixV2(ProtoDecoder):
    """firmware phoenix_v2.c, 52-bit PWM. te=427/853. Preamble LOW te_short*60, start HIGH te_short*6;
    SaveDuration is the LOW (terminator/te_last), CheckDuration the HIGH: bit1 = short LOW + long HIGH,
    bit0 = long LOW + short HIGH; terminal LOW >=te_short*10+delta reports."""
    NAME = "Phoenix_V2"
    CONST = Const(427, 853, 100)
    BITS = 52

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:
            if (not level) and diff(duration, c.te_short * 60) < c.te_delta * 30:
                d.parser_step = 1
        elif step == 1:  # FoundStartBit — HIGH te_short*6
            if level and diff(duration, c.te_short * 6) < c.te_delta * 4:
                d.parser_step = 2; d.decode_data = 0; d.decode_count_bit = 0
            else:
                d.parser_step = 0
        elif step == 2:  # SaveDuration — LOW
            if not level:
                if duration >= c.te_short * 10 + c.te_delta:
                    d.parser_step = 1
                    if d.decode_count_bit == self.BITS:
                        data = d.decode_data & MASK64
                        self.emit(dict(serial=data, bits=self.BITS, data=data))
                    d.decode_data = 0; d.decode_count_bit = 0
                else:
                    d.te_last = duration; d.parser_step = 3
            # level HIGH here: firmware stays in SaveDuration
        elif step == 3:  # CheckDuration — HIGH
            if level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta * 3:
                    d.add_bit(1); d.parser_step = 2
                elif diff(d.te_last, c.te_long) < c.te_delta * 3 and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(0); d.parser_step = 2
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0


class ReversRB2(ProtoDecoder):
    """firmware revers_rb2.c, 64-bit Manchester. te=250/500. ~600us LOW reset, 4 alternating short
    edges arm it (seed 0xF/4 bits), then Manchester bits; reports when preamble 0xFF + stop 0x200."""
    NAME = "Revers_RB2"
    CONST = Const(250, 500, 160)
    BITS = 64

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        if not hasattr(self, "m"):
            self.m = Manchester()
        step = d.parser_step
        if step == 0:   # Reset — LOW ~600
            if (not level) and diff(duration, 600) < c.te_delta:
                d.parser_step = 1; d.decode_data = 0; d.decode_count_bit = 0
                self.m.reset(); self._hc = 0; self._rlast = 0
            return
        if step == 1:   # Header — 4 alternating short edges
            short = diff(duration, c.te_short) < c.te_delta
            if not level:
                if short:
                    if self._rlast == 1:
                        self._hc += 1
                    self._rlast = 0
                else:
                    self._hc = 0; self._rlast = 0; d.parser_step = 0; return
            else:
                if short:
                    if self._rlast == 0:
                        self._hc += 1
                    self._rlast = 1
                else:
                    self._hc = 0; self._rlast = 0; d.parser_step = 0; return
            if self._hc == 4:
                self._hc = 0; d.decode_data = 0xF; d.decode_count_bit = 4; d.parser_step = 2
            return
        # step 2: DecoderData
        event = None
        if not level:
            if diff(duration, c.te_short) < c.te_delta:
                event = 0
            elif diff(duration, c.te_long) < c.te_delta:
                event = 4
            else:
                d.parser_step = 0; return
        else:
            if diff(duration, c.te_short) < c.te_delta:
                event = 2
            elif diff(duration, c.te_long) < c.te_delta:
                event = 6
            else:
                d.parser_step = 0; return
        bit = self.m.advance(event)
        if bit is not None:
            self._addbit(1 if bit else 0)

    def _addbit(self, bit):
        d = self.d
        d.decode_data = ((d.decode_data << 1) | bit) & MASK64
        d.decode_count_bit += 1
        if d.decode_count_bit >= 65:
            d.decode_data = 0; d.decode_count_bit = 0; return
        if d.decode_count_bit < self.BITS:
            return
        preamble = (d.decode_data >> 48) & 0xFF
        stop = d.decode_data & 0x3FF
        if preamble == 0xFF and stop == 0x200:
            data = d.decode_data & MASK64
            self.emit(dict(serial=(data & 0xFFFFFFFFFFFF) >> 10, bits=d.decode_count_bit, data=data))
            d.decode_data = 0; d.decode_count_bit = 0; self.m.reset()


class DickertMAHS(ProtoDecoder):
    """firmware dickert_mahs.c, 36-bit. te=400/800. Preamble LOW te_long*50; each bit is a (LOW,HIGH)
    pair summing to ~1200 where the LOW length picks it (long=1, short=0); reports on next preamble."""
    NAME = "Dickert_MAHS"
    CONST = Const(400, 800, 100)
    BITS = 36

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        if not hasattr(self, "_tmp"):
            self._tmp = [0, 0]; self._tc = 0
        step = d.parser_step
        if step == 0:   # Reset
            if d.decode_count_bit >= self.BITS:
                self.emit(dict(serial=0, btn=0, bits=d.decode_count_bit, data=d.decode_data & MASK64))
                d.decode_data = 0; d.decode_count_bit = 0
            if (not level) and diff(duration, c.te_long * 50) < c.te_delta * 70:
                d.parser_step = 1
        elif step == 1:  # Initial
            if not level:
                pass
            elif diff(duration, c.te_short) < c.te_delta:
                d.parser_step = 2; d.decode_data = 0; d.decode_count_bit = 0; self._tc = 0
            else:
                d.parser_step = 0
        elif step == 2:  # Recording
            if ((not level) and self._tc == 0) or (level and self._tc == 1):
                self._tmp[self._tc] = duration; self._tc += 1
                if self._tc == 2:
                    if diff(self._tmp[0] + self._tmp[1], 1200) < c.te_delta:
                        if diff(self._tmp[0], c.te_long) < c.te_delta:
                            d.add_bit(1)
                        elif diff(self._tmp[0], c.te_short) < c.te_delta:
                            d.add_bit(0)
                        self._tc = 0
                    else:
                        self._tc = 0; d.parser_step = 0
            else:
                self._tc = 0; d.parser_step = 0


class AllstarFirefly(ProtoDecoder):
    """firmware allstar_firefly.c, 18-bit PWM. te=600/4000. Preamble LOW te_short*50; bit1 = long HIGH +
    short LOW, bit0 = short HIGH + long LOW; a trailing te_short*50 gap completes the last bit + reports."""
    NAME = "Allstar Firefly"
    CONST = Const(600, 4000, 300)
    BITS = 18

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:
            if (not level) and diff(duration, c.te_short * 50) < c.te_delta * 5:
                d.decode_data = 0; d.decode_count_bit = 0; d.parser_step = 1
        elif step == 1:  # SaveDuration — HIGH
            if level:
                d.te_last = duration; d.parser_step = 2
            else:
                d.parser_step = 0
        elif step == 2:  # CheckDuration — LOW
            if not level:
                if diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(1); d.parser_step = 1
                elif diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta:
                    d.add_bit(0); d.parser_step = 1
                elif diff(duration, c.te_short * 50) < c.te_delta * 5:
                    if diff(d.te_last, c.te_long) < c.te_delta:
                        d.add_bit(1)
                    elif diff(d.te_last, c.te_short) < c.te_delta:
                        d.add_bit(0)
                    if d.decode_count_bit == self.BITS:
                        data = d.decode_data & MASK64
                        self.emit(dict(serial=data, bits=self.BITS, data=data))
                    d.decode_data = 0; d.decode_count_bit = 0; d.parser_step = 0
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0


class SecPlusV1(ProtoDecoder):
    """firmware secplus_v1.c, Security+ 1.0 — ternary rolling, two 21-symbol packets. te=500/1500.
    Packet 1 (short start) and packet 2 (long start) each carry a base-3 start + 20 symbols; once both
    are seen, the halves decode to fixed (serial) and rolling (reversed 32-bit)."""
    NAME = "Security+ 1.0"
    CONST = Const(500, 1500, 100)
    MIN = 21

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        if not hasattr(self, "_arr"):
            self._arr = [0] * 44; self._acc = 0; self._base = 0
        step = d.parser_step
        if step == 0:   # Reset
            if (not level) and diff(duration, c.te_short * 120) < c.te_delta * 120:
                d.parser_step = 1; d.decode_data = 0; d.decode_count_bit = 0
                self._acc = 0; self._arr = [0] * 44
        elif step == 1:  # SearchStartBit — HIGH
            if level:
                if diff(duration, c.te_short) < c.te_delta:
                    self._base = 0; self._arr[d.decode_count_bit + 0] = 0
                    d.decode_count_bit += 1; d.parser_step = 2
                elif diff(duration, c.te_long) < c.te_delta:
                    self._base = 21; self._arr[d.decode_count_bit + 21] = 2
                    d.decode_count_bit += 1; d.parser_step = 2
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0
        elif step == 2:  # SaveDuration — LOW
            if not level:
                if diff(duration, c.te_short * 120) < c.te_delta * 120:
                    if d.decode_count_bit == self.MIN:
                        self._acc |= 1 if self._base == 0 else 2
                        if self._acc == 3:
                            self._decode()
                    d.parser_step = 1; d.decode_data = 0; d.decode_count_bit = 0
                else:
                    d.te_last = duration; d.parser_step = 3
            else:
                d.parser_step = 0
        elif step == 3:  # DecoderData — HIGH; (te_last LOW, duration HIGH) picks the ternary symbol
            if level and d.decode_count_bit <= self.MIN:
                if diff(d.te_last, c.te_short * 3) < c.te_delta * 3 and diff(duration, c.te_short) < c.te_delta:
                    self._arr[d.decode_count_bit + self._base] = 0; d.decode_count_bit += 1; d.parser_step = 2
                elif diff(d.te_last, c.te_short * 2) < c.te_delta * 2 and diff(duration, c.te_short * 2) < c.te_delta * 2:
                    self._arr[d.decode_count_bit + self._base] = 1; d.decode_count_bit += 1; d.parser_step = 2
                elif diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_short * 3) < c.te_delta * 3:
                    self._arr[d.decode_count_bit + self._base] = 2; d.decode_count_bit += 1; d.parser_step = 2
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0

    def _decode(self):
        arr = self._arr
        rolling = 0; fixed = 0; acc = 0
        for i in range(1, 21, 2):
            rolling = rolling * 3 + arr[i]; acc += arr[i]
            dg = (60 + arr[i + 1] - acc) % 3; fixed = fixed * 3 + dg; acc += dg
        acc = 0
        for i in range(22, 42, 2):
            rolling = rolling * 3 + arr[i]; acc += arr[i]
            dg = (60 + arr[i + 1] - acc) % 3; fixed = fixed * 3 + dg; acc += dg
        rolling = reverse_key(rolling, 32)
        self.emit(dict(serial=fixed, cnt=rolling, bits=self.MIN * 2,
                       data=((fixed << 32) | rolling) & MASK64))


class SomfyKeytis(ProtoDecoder):
    """firmware somfy_keytis.c, 80-bit Manchester = Somfy Telis 56-bit core + a 24-bit press-duration
    tail. te=640/1280. Same te_short*4 preamble train + te_short*7 data-start (Manchester primed with a
    LongHigh), same de-obfuscation (data ^ data>>8) + CRC-4. Only the first 56 Manchester bits form the
    key; bits 56..79 are the press counter (ignored here). Emits the de-obfuscated serial/btn/cnt."""
    NAME = "Somfy Keytis"                # firmware SUBGHZ_PROTOCOL_SOMFY_KEYTIS_NAME
    CONST = Const(640, 1280, 250)
    BITS = 80
    DATA_BITS = 56

    def __init__(self):
        super().__init__()
        self.m = Manchester()
        self.header_count = 0

    @staticmethod
    def _crc(data):
        data &= 0xFFF0FFFFFFFFFF
        crc = 0
        for i in range(0, 56, 8):
            crc = crc ^ (data >> i) ^ (data >> (i + 4))
        return crc & 0xF

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        event = 8
        if step == 0:  # Reset — te_short*4 HIGH
            if level and diff(duration, c.te_short * 4) < c.te_delta * 4:
                d.parser_step = 1; self.header_count += 1
        elif step == 1:  # FoundPreambula — te_short*4 LOW
            if (not level) and diff(duration, c.te_short * 4) < c.te_delta * 4:
                d.parser_step = 2
            else:
                self.header_count = 0; d.parser_step = 0
        elif step == 2:  # CheckPreambula — preamble HIGH, or te_short*7 data-start
            if level:
                if diff(duration, c.te_short * 4) < c.te_delta * 4:
                    d.parser_step = 1; self.header_count += 1
                elif self.header_count > 1 and diff(duration, c.te_short * 7) < c.te_delta * 4:
                    d.parser_step = 3; d.decode_data = 0; d.decode_count_bit = 0; self.header_count = 0
                    self.m.reset(); self.m.advance(6)
        elif step == 3:  # DecoderData
            if not level:
                if diff(duration, c.te_short) < c.te_delta:
                    event = 0
                elif diff(duration, c.te_long) < c.te_delta:
                    event = 4
                elif duration >= c.te_long + c.te_delta:   # end gap
                    if d.decode_count_bit == self.BITS:
                        clear = d.decode_data ^ (d.decode_data >> 8)
                        if ((clear >> 40) & 0xF) == self._crc(clear):
                            self.emit(dict(serial=clear & 0xFFFFFF, btn=(clear >> 44) & 0xF,
                                           cnt=(clear >> 24) & 0xFFFF, data=d.decode_data, bits=self.BITS))
                    d.decode_data = 0; d.decode_count_bit = 0
                    self.m.reset(); self.m.advance(6)
                    d.parser_step = 0
                else:
                    d.parser_step = 0
            else:
                if diff(duration, c.te_short) < c.te_delta:
                    event = 2
                elif diff(duration, c.te_long) < c.te_delta:
                    event = 6
                else:
                    d.parser_step = 0
            if event != 8:
                bit = self.m.advance(event)
                if bit is not None:
                    if d.decode_count_bit < self.DATA_BITS:
                        d.decode_data = ((d.decode_data << 1) | (1 if bit else 0)) & MASK64
                    d.decode_count_bit += 1


class KingGatesStylo4k(ProtoDecoder):
    """firmware kinggates_stylo_4k.c, 89-bit PWM. te=400/1100. Short HIGH/LOW preamble (>2) then a
    te_long*2 LOW and a te_short*2 start HIGH; SaveDuration is the LOW, CheckDuration the HIGH:
    bit1 = short LOW + long HIGH, bit0 = long LOW + short HIGH. 64-bit split at 53 bits; terminal
    LOW >= te_long*3 reports."""
    NAME = "KingGates Stylo4k"
    CONST = Const(400, 1100, 140)
    BITS = 89
    SPLIT = 53

    def __init__(self):
        super().__init__()
        self.header_count = 0
        self._d2 = 0

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:   # Reset
            if level and diff(duration, c.te_short) < c.te_delta:
                d.parser_step = 1; self.header_count += 1
        elif step == 1:  # CheckPreambula
            if (not level) and diff(duration, c.te_short) < c.te_delta:
                d.parser_step = 0; return
            if self.header_count > 2 and diff(duration, c.te_long * 2) < c.te_delta * 2:
                d.parser_step = 2
            else:
                d.parser_step = 0; self.header_count = 0
        elif step == 2:  # CheckStartBit
            if level and diff(duration, c.te_short * 2) < c.te_delta * 2:
                d.parser_step = 3; d.decode_data = 0; d.decode_count_bit = 0
                self.header_count = 0; self._d2 = 0
        elif step == 3:  # SaveDuration — LOW
            if not level:
                if duration >= c.te_long * 3:
                    if d.decode_count_bit == self.BITS:
                        self._decode(self._d2 & MASK64, d.decode_data & 0xFFFFFFFFF)  # 53-bit fix, 36-bit tail
                    d.parser_step = 0; d.decode_data = 0; d.decode_count_bit = 0; self.header_count = 0
                else:
                    d.te_last = duration; d.parser_step = 4
            else:
                d.parser_step = 0; self.header_count = 0
        elif step == 4:  # CheckDuration — HIGH
            if level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta * 2:
                    d.add_bit(1); d.parser_step = 3
                elif diff(d.te_last, c.te_long) < c.te_delta * 2 and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(0); d.parser_step = 3
                else:
                    d.parser_step = 0; self.header_count = 0
                if d.decode_count_bit == self.SPLIT:
                    self._d2 = d.decode_data; d.decode_data = 0
            else:
                d.parser_step = 0; self.header_count = 0

    def _decode(self, data53, data36):
        """Full KingGates rolling decode via SIMPLE_KINGGATES (type 10) key — direct keeloq_decrypt."""
        try:
            import kl_crack
            mkey = _keeloq_mkey(10)   # KEELOQ_LEARNING_SIMPLE_KINGGATES
            if mkey is None:
                return
            hop = kl_crack.rev_bits(data36 >> 4, 32)
            fix = kl_crack.rev_bits(data53, 53)
            btn = (fix >> 17) & 0x0F
            serial = ((fix >> 5) & 0xFFFF0000) | (fix & 0xFFFF)
            decrypt = kl_crack.keeloq_decrypt(hop, mkey)
            if (decrypt >> 28) == btn and ((decrypt >> 24) & 0x0F) == 0x0C and \
               ((decrypt >> 16) & 0xFF) == (serial & 0xFF):
                self.emit(dict(serial=serial, btn=btn, cnt=decrypt & 0xFFFF,
                               bits=self.BITS, data=data53))
        except Exception:
            pass


class DitecGOL4(ProtoDecoder):
    """firmware ditec_gol4.c, 54-bit PWM. te=400/1100. Preamble LOW te_long*22, start HIGH te_short*2;
    SaveDuration is the LOW, CheckDuration the HIGH: bit1 = short LOW + long HIGH, bit0 = long LOW +
    short HIGH. Reports when a te_long*20-class end gap (te_last) is seen in CheckDuration at 54 bits."""
    NAME = "Ditec GOL4"
    CONST = Const(400, 1100, 200)
    BITS = 54

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:   # Reset
            if (not level) and diff(duration, c.te_long * 22) < c.te_long * 4:
                d.decode_data = 0; d.decode_count_bit = 0; d.parser_step = 1
        elif step == 1:  # StartBit — HIGH te_short*2
            if level and diff(duration, c.te_short * 2) < c.te_delta:
                d.parser_step = 2
            else:
                d.parser_step = 0
        elif step == 2:  # SaveDuration — LOW
            if not level:
                d.te_last = duration; d.parser_step = 3
            else:
                d.parser_step = 0
        elif step == 3:  # CheckDuration
            if level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta:
                    d.add_bit(1); d.parser_step = 2
                elif diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(0); d.parser_step = 2
                # else: firmware leaves parser_step unchanged (stays in CheckDuration)
            else:
                if diff(d.te_last, c.te_long * 20) < c.te_long * 3:
                    if d.decode_count_bit == self.BITS:
                        data = d.decode_data & MASK64
                        self.emit(dict(serial=data, bits=self.BITS, data=data))
                d.decode_data = 0; d.decode_count_bit = 0; d.parser_step = 0


def _keeloq_mkey(learning_type, name=None):
    """Look up a manufacturer key from the embedded keeloq keystore by learning type (and name)."""
    try:
        import keeloq_keys
        for k, t, nm in keeloq_keys.KEYS:
            if t == learning_type and (name is None or nm == name):
                return k
    except Exception:
        pass
    return None


class Jarolift(ProtoDecoder):
    """firmware jarolift.c, 72-bit KeeLoq (NORMAL_JAROLIFT, learning type 11). te=400/800. 8x short
    preamble + te_long*5, PWM bits (bit1 = short HIGH + long LOW), 64-bit split at 64. Full rolling
    decode: reverse → serial/hop, normal_learning(serial, Jarolift key) → keeloq_decrypt(hop), validated
    by (decrypt>>16)&0xFF == serial&0xFF. Emits resolved serial/btn/cnt only on that match."""
    NAME = "Jarolift"
    CONST = Const(400, 800, 167)
    BITS = 72

    def __init__(self):
        super().__init__()
        self.header_count = 0
        self._d1 = 0

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:   # Reset
            if level and diff(duration, c.te_short) < c.te_delta:
                d.parser_step = 1; self.header_count += 1
        elif step == 1:  # CheckPreambula
            if (not level) and diff(duration, c.te_short) < c.te_delta:
                d.parser_step = 0; return
            if (not level) and self.header_count == 8 and diff(duration, c.te_long * 5) < c.te_delta * 6:
                d.parser_step = 2; d.decode_data = 0; d.decode_count_bit = 0
                self.header_count = 0; self._d1 = 0
            else:
                d.parser_step = 0; self.header_count = 0
        elif step == 2:  # SaveDuration — HIGH
            if level:
                d.te_last = duration; d.parser_step = 3
            else:
                self.header_count = 0; d.parser_step = 0
        elif step == 3:  # CheckDuration — LOW
            if not level:
                if d.decode_count_bit == 64:
                    self._d1 = d.decode_data; d.decode_data = 0
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta:
                    d.add_bit(1); d.parser_step = 2
                elif diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(0); d.parser_step = 2
                elif duration >= c.te_long * 3:
                    if diff(d.te_last, c.te_long) < c.te_delta:
                        d.add_bit(0)
                    elif diff(d.te_last, c.te_short) < c.te_delta:
                        d.add_bit(1)
                    if d.decode_count_bit == self.BITS:
                        self._decode(self._d1 & MASK64, d.decode_data & 0xFF)
                    d.parser_step = 0; d.decode_data = 0; d.decode_count_bit = 0; self.header_count = 0
                else:
                    d.parser_step = 0; self.header_count = 0
            else:
                self.header_count = 0; d.parser_step = 0

    def _decode(self, data, data_2):
        try:
            import kl_crack
            mkey = _keeloq_mkey(11)   # KEELOQ_LEARNING_NORMAL_JAROLIFT
            if mkey is None:
                return
            key64 = kl_crack.rev_bits(data, 64)
            serial = (key64 >> 32) & 0x0FFFFFFF
            hop = key64 & 0xFFFFFFFF
            man = kl_crack.normal_learning(serial, mkey)
            decrypt = kl_crack.keeloq_decrypt(hop, man)
            if ((decrypt >> 16) & 0xFF) == (serial & 0xFF):
                self.emit(dict(serial=serial, btn=(key64 >> 60) & 0xF,
                               cnt=decrypt & 0xFFFF, bits=self.BITS, data=data))
        except Exception:
            pass


def _rev8(v):
    v = ((v >> 4) | (v << 4)) & 0xFF
    v = ((v & 0x33) << 2) | ((v >> 2) & 0x33)
    v = ((v & 0x55) << 1) | ((v >> 1) & 0x55)
    return v & 0xFF


class Mic6sc2(ProtoDecoder):
    """firmware mic6sc2.c (Microchip 6SC2 car remote, rtl_433 port). OOK Manchester ZEROBIT, te=250/500.
    80-88 bit byte-buffer frame: 0x55 0x54 preamble, b[2..5] 32-bit encrypted (reverse8, big-endian),
    b[6] low nibble = button, b[7..8] sequence, b[9] checksum; integrity XOR(b[2..10])==0. Note the
    ZEROBIT convention inverts level->event (HIGH pulse -> *Low event)."""
    NAME = "Microchip 6SC2"
    CONST = Const(250, 500, 100)

    def __init__(self):
        super().__init__()
        self.m = Manchester()
        self.frame = bytearray(11)
        self.bit_count = 0

    def _soft_reset(self):
        self.frame = bytearray(11)
        self.bit_count = 0
        self.m.reset()

    def feed(self, level, duration):
        c = self.CONST
        if diff(duration, c.te_short) < c.te_delta:
            event = 0 if level else 2          # ShortLow / ShortHigh (level inverted)
        elif diff(duration, c.te_long) < c.te_delta:
            event = 4 if level else 6          # LongLow / LongHigh
        else:
            self._process(); self._soft_reset(); return
        bit = self.m.advance(event)
        if bit is None:
            return
        if self.bit_count < 88:
            if bit:
                self.frame[self.bit_count >> 3] |= (1 << (7 - (self.bit_count & 7)))
            self.bit_count += 1
        if self.bit_count == 16 and (self.frame[0] != 0x55 or self.frame[1] != 0x54):
            self._soft_reset(); return
        if self.bit_count >= 88:
            self._process(); self._soft_reset()

    def _process(self):
        if self.bit_count < 80:
            return
        b = self.frame
        if b[0] != 0x55 or b[1] != 0x54:
            return
        xs = 0
        for i in range(2, 11):
            xs ^= b[i]
        if xs != 0:
            return
        encrypted = (_rev8(b[5]) << 24) | (_rev8(b[4]) << 16) | (_rev8(b[3]) << 8) | _rev8(b[2])
        button = _rev8(b[6]) & 0x0F
        sequence = (_rev8(b[8]) << 8) | _rev8(b[7])
        data = 0
        for i in range(2, 10):
            data = (data << 8) | b[i]
        self.emit(dict(serial=encrypted, btn=button, cnt=sequence, data=data, bits=64))


def _beninca_aes_key():
    """firmware get_subghz_protocol_beninca_arc_aes_key — 16-byte AES key from the type-9 keystore entry."""
    mfkey = _keeloq_mkey(9, "Beninca_ARC")
    if mfkey is None:
        return None
    lo = mfkey & 0xFFFFFFFF
    hi = (mfkey >> 32) & 0xFFFFFFFF

    def bswap(x):
        return ((x >> 24) & 0xFF) | ((x >> 8) & 0xFF00) | ((x << 8) & 0xFF0000) | ((x << 24) & 0xFF000000)
    a = mfkey & 0xFFFFFFFFFFFFFFFF
    b = ((bswap(lo) << 32) | bswap(hi)) & 0xFFFFFFFFFFFFFFFF
    return bytes([(a >> (56 - i * 8)) & 0xFF for i in range(8)] +
                 [(b >> (56 - i * 8)) & 0xFF for i in range(8)])


class BenincaARC(ProtoDecoder):
    """firmware beninca_arc.c + decode_beninca_arc.fal, 128-bit PWM. te=300/600. Preamble LOW te_long*16;
    bit1 = short HIGH + long LOW, bit0 = long HIGH + short LOW; 64-bit split at 64. Full decode: 16 bytes
    (data||data_2) -> per-byte bit-reverse -> AES-128-ECB decrypt (key derived from the type-9 keystore
    entry) -> serial (bytes 0..3), btn (byte 4), cnt (bytes 10..13)."""
    NAME = "Beninca ARC"
    CONST = Const(300, 600, 155)
    BITS = 128

    def __init__(self):
        super().__init__()
        self._d1 = 0

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:   # Start
            if (not level) and diff(duration, c.te_long * 16) < c.te_delta * 15:
                d.decode_data = 0; d.decode_count_bit = 0; self._d1 = 0
                d.parser_step = 1
        elif step == 1:  # HighLevel
            if level:
                d.te_last = duration; d.parser_step = 2
                if d.decode_count_bit == 64 and d.decode_data != 0:
                    self._d1 = d.decode_data; d.decode_data = 0
                elif d.decode_count_bit == 128:
                    self._decode(self._d1 & MASK64, d.decode_data & MASK64)
                    d.parser_step = 0
            else:
                d.parser_step = 0
        elif step == 2:  # LowLevel
            if not level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta:
                    d.add_bit(1); d.parser_step = 1
                elif diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(0); d.parser_step = 1
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0

    def _decode(self, data, data_2):
        try:
            key = _beninca_aes_key()
            if key is None:
                return
            import aes128
            enc = bytes([(data >> (56 - i * 8)) & 0xFF for i in range(8)] +
                        [(data_2 >> (56 - i * 8)) & 0xFF for i in range(8)])
            enc = bytes(_rev8(x) for x in enc)           # reverse_bits_in_bytes
            dec = aes128.decrypt_block(key, enc)
            serial = (dec[0] << 24) | (dec[1] << 16) | (dec[2] << 8) | dec[3]
            btn = dec[4]
            cnt = (dec[10] << 24) | (dec[11] << 16) | (dec[12] << 8) | dec[13]
            self.emit(dict(serial=serial, btn=btn, cnt=cnt, bits=self.BITS,
                           data=((data << 64) | data_2)))
        except Exception:
            pass


def _faac_learning(seed, key):
    """firmware subghz_protocol_keeloq_common_faac_learning — seed -> 64-bit device key."""
    import kl_crack
    hs = (seed >> 16) & 0xFFFF
    lsb = ((hs << 16) | 0x544D) & 0xFFFFFFFF
    return (kl_crack.keeloq_encrypt(seed & 0xFFFFFFFF, key) << 32) | kl_crack.keeloq_encrypt(lsb, key)


class FaacSLH(ProtoDecoder):
    """firmware faac_slh.c, 64-bit Faac SLH (SLH = seed-learning hopping, learning type 5). te=255/595.
    HIGH te_long*2 + LOW te_long*2 preamble; bit0 = short HIGH + long LOW, bit1 = long HIGH + short LOW;
    terminal HIGH >= te_short*3+delta reports. serial/btn come from the fixed half; the rolling counter
    needs the seed (recovered from a program frame, persisted on the instance) + faac_learning(seed,key)."""
    NAME = "Faac SLH"
    CONST = Const(255, 595, 100)
    BITS = 64

    def __init__(self):
        super().__init__()
        self.seed = 0

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:   # Reset
            if level and diff(duration, c.te_long * 2) < c.te_delta * 3:
                d.parser_step = 1
        elif step == 1:  # FoundPreambula — LOW te_long*2
            if (not level) and diff(duration, c.te_long * 2) < c.te_delta * 3:
                d.parser_step = 2; d.decode_data = 0; d.decode_count_bit = 0
            else:
                d.parser_step = 0
        elif step == 2:  # SaveDuration — HIGH
            if level:
                if duration >= c.te_short * 3 + c.te_delta:
                    d.parser_step = 1
                    if d.decode_count_bit == self.BITS:
                        self._decode(d.decode_data & MASK64)
                    d.decode_data = 0; d.decode_count_bit = 0
                else:
                    d.te_last = duration; d.parser_step = 3
            else:
                d.parser_step = 0
        elif step == 3:  # CheckDuration — LOW
            if not level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta:
                    d.add_bit(0); d.parser_step = 2
                elif diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(1); d.parser_step = 2
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0

    def _decode(self, data):
        code_fix = (data >> 32) & 0xFFFFFFFF
        code_hop = data & 0xFFFFFFFF
        dp = [code_hop & 0xFF, (code_hop >> 8) & 0xFF, (code_hop >> 16) & 0xFF, (code_hop >> 24) & 0xFF,
              code_fix & 0xFF, (code_fix >> 8) & 0xFF, (code_fix >> 16) & 0xFF, (code_fix >> 24) & 0xFF]
        if dp[7] == 0x52 and dp[6] == 0x0F and dp[0] == 0x00:   # program frame -> recover seed
            for _ in range(dp[1] & 0xF):
                t = dp[2]
                dp[2] = (dp[2] >> 1) | ((dp[3] & 1) << 7)
                dp[3] = (dp[3] >> 1) | ((dp[4] & 1) << 7)
                dp[4] = (dp[4] >> 1) | ((dp[5] & 1) << 7)
                dp[5] = (dp[5] >> 1) | ((t & 1) << 7)
            dp[2] ^= dp[1]; dp[3] ^= dp[1]; dp[4] ^= dp[1]; dp[5] ^= dp[1]
            self.seed = (dp[5] << 24) | (dp[4] << 16) | (dp[3] << 8) | dp[2]
            self.emit(dict(seed=self.seed, cnt=dp[1], bits=self.BITS, data=data))
            return
        if code_fix != 0:
            serial = code_fix >> 4
            btn = code_fix & 0xF
            cnt = 0
            key = _keeloq_mkey(5, "FAAC_SLH")   # KEELOQ_LEARNING_FAAC
            if key is not None:
                try:
                    import kl_crack
                    man = _faac_learning(self.seed, key)
                    cnt = kl_crack.keeloq_decrypt(code_hop, man) & 0xFFFFF
                except Exception:
                    pass
            self.emit(dict(serial=serial, btn=btn, cnt=cnt, bits=self.BITS, data=data))


def _parity(v, n):
    """firmware subghz_protocol_blocks_get_parity: sum of the low n bits, mod 2."""
    return bin(v & ((1 << n) - 1)).count("1") & 1


def _honeywell_crc16(msg, n, poly, init):
    """firmware subghz_protocol_honeywell_crc16 (MSB-first, no reflect/xorout)."""
    rem = init & 0xFFFF
    for i in range(n):
        rem ^= (msg[i] << 8)
        rem &= 0xFFFF
        for _ in range(8):
            if rem & 0x8000:
                rem = ((rem << 1) ^ poly) & 0xFFFF
            else:
                rem = (rem << 1) & 0xFFFF
    return rem & 0xFFFF


class Honeywell(ProtoDecoder):
    """firmware honeywell.c (Honeywell/2GIG/Ademco security sensors, rtl_433 port). OOK Manchester,
    te=143/280, init state Mid1. 62-bit frame: 14/15/16-bit preamble (0x3FFE/0x7FFE/0xFFFE) + 4-bit
    channel + 20-bit serial + 8-bit status + 16-bit CRC16 (poly 0x8050 for channel 2/4/A, 0x8005 for
    channel 8, init 0, over the 4 bytes between preamble and CRC). Reports only on CRC match."""
    NAME = "Honeywell Sec"
    CONST = Const(143, 280, 51)

    def __init__(self):
        super().__init__()
        self.m = Manchester()
        self.data = 0
        self.count = 0

    def feed(self, level, duration):
        c = self.CONST
        event = None
        if not level:
            if diff(duration, c.te_short) < c.te_delta:
                event = 0      # ShortLow
            elif diff(duration, c.te_long) < c.te_delta * 2:
                event = 4      # LongLow
        else:
            if diff(duration, c.te_short) < c.te_delta:
                event = 2      # ShortHigh
            elif diff(duration, c.te_long) < c.te_delta * 2:
                event = 6      # LongHigh
        if event is None:
            self.data = 0; self.count = 0; self.m.reset()
            return
        bit = self.m.advance(event)
        if bit is None:
            return
        self._addbit(1 if bit else 0)

    def _addbit(self, bit):
        self.data = ((self.data << 1) | bit) & MASK64
        self.count += 1
        if self.count < 62:
            return
        d = self.data
        preamble = (d >> 48) & 0xFFFF
        if preamble in (0b0011111111111110, 0b0111111111111110, 0b1111111111111110):
            datatocrc = [(d >> 40) & 0xFF, (d >> 32) & 0xFF, (d >> 24) & 0xFF, (d >> 16) & 0xFF]
            channel = (d >> 44) & 0xF
            if channel in (0x2, 0x4, 0xA):
                crc_calc = _honeywell_crc16(datatocrc, 4, 0x8050, 0)
            elif channel == 0x8:
                crc_calc = _honeywell_crc16(datatocrc, 4, 0x8005, 0)
            else:
                self.data = 0; self.count = 0; return
            if (d & 0xFFFF) == crc_calc:
                serial = (d >> 24) & 0xFFFFF
                status = (d >> 16) & 0xFF
                self.emit(dict(serial=serial, btn=channel, cnt=status, bits=62, data=d))
            self.data = 0; self.count = 0
        elif self.count >= 64:
            self.data = 0; self.count = 0


class HoneywellWDB(ProtoDecoder):
    """firmware honeywell_wdb.c (shows as "Honeywell"). OOK PWM, te=160/320. Preamble LOW te_short*3;
    bit0 = short HIGH + long LOW, bit1 = long HIGH + short LOW; a terminal HIGH ~te_short*3 reports when
    48 bits are collected and the LSB parity bit equals the parity of the upper 47 bits."""
    NAME = "Honeywell"
    CONST = Const(160, 320, 60)
    BITS = 48

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:        # Reset — preamble LOW te_short*3
            if (not level) and diff(duration, c.te_short * 3) < c.te_delta:
                d.decode_count_bit = 0; d.decode_data = 0
                d.parser_step = 1
        elif step == 1:      # SaveDuration — HIGH
            if level:
                if diff(duration, c.te_short * 3) < c.te_delta:   # terminal HIGH
                    if d.decode_count_bit == self.BITS and \
                       (d.decode_data & 0x01) == _parity(d.decode_data >> 1, self.BITS - 1):
                        self.emit(dict(serial=(d.decode_data >> 1) & ((1 << 47) - 1),
                                       btn=0, cnt=d.decode_data & 0x01,
                                       bits=self.BITS, data=d.decode_data))
                    d.parser_step = 0
                else:
                    d.te_last = duration; d.parser_step = 2
            else:
                d.parser_step = 0
        elif step == 2:      # CheckDuration — LOW
            if not level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta:
                    d.add_bit(0); d.parser_step = 1
                elif diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(1); d.parser_step = 1
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0


class LinearDelta3(ProtoDecoder):
    """firmware linear_delta3.c (Linear Delta-3). OOK, te_short=500, te_long=2000, te_delta=150, 8-bit
    repeat code. Preamble LOW ~te_short*70; HIGH-then-LOW bits (short HIGH + te_short*7 LOW = 1, long
    HIGH + long LOW = 0); a long terminal LOW (>= te_short*10) finalises the frame. Reports only when
    two consecutive 8-bit frames match (last_data == decode_data)."""
    NAME = "Linear Delta3"
    CONST = Const(500, 2000, 150)
    BITS = 8

    def __init__(self):
        super().__init__()
        self._last_data = 0

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:        # Reset — preamble LOW te_short*70
            if (not level) and diff(duration, c.te_short * 70) < c.te_delta * 24:
                d.decode_data = 0; d.decode_count_bit = 0
                d.parser_step = 1
        elif step == 1:      # SaveDuration — HIGH
            if level:
                d.te_last = duration; d.parser_step = 2
            else:
                d.parser_step = 0
        elif step == 2:      # CheckDuration — LOW
            if not level:
                if duration >= c.te_short * 10:          # terminal long gap
                    d.parser_step = 0
                    if diff(d.te_last, c.te_short) < c.te_delta:
                        d.add_bit(1)
                    elif diff(d.te_last, c.te_long) < c.te_delta:
                        d.add_bit(0)
                    if d.decode_count_bit == self.BITS:
                        if self._last_data == d.decode_data and self._last_data:
                            self.emit(dict(serial=0, btn=0, cnt=0,
                                           bits=self.BITS, data=d.decode_data))
                        d.parser_step = 1
                        self._last_data = d.decode_data
                    return
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_short * 7) < c.te_delta:
                    d.add_bit(1); d.parser_step = 1
                elif diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_long) < c.te_delta:
                    d.add_bit(0); d.parser_step = 1
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0



# ====================== nero ======================
_DYN_TE = 208
_DYN_FRAME_BITS = 251
_DYN_HEADER = 0x54C
_DYN_HEADER_BITS = 11
_DYN_SYNC_WORDS = 4
_DYN_DATA_WORDS = 11
_DYN_COUNT_BIT = 66

_NORM_COUNT_BIT = 72
_NORM_HEADER = 0xE24
_NORM_HEAD_BITS = 12
_NORM_TAIL_START = _NORM_HEAD_BITS + 16 * 7 * 2
_NORM_ID_BITS = 52
_NORM_ID_PREAM = 8
_NORM_GAP_MIN = 13 * _DYN_TE

_NERO_RADIO_DYN_SYNC = (0x896C, 0x7435, 0xCF7C, 0x117B)
_NERO_RADIO_DYN_SYMBOLS = (
    0x117B, 0x12BD, 0x13C7, 0x14F6, 0x172E, 0x1D65, 0x1D8B, 0x1E53, 0x1ECC, 0x22DE, 0x236D,
    0x2597, 0x25EA, 0x26F1, 0x29BC, 0x2E1D, 0x2E66, 0x33B2, 0x344F, 0x3754, 0x38D5, 0x3A2B,
    0x3C78, 0x43AB, 0x44DB, 0x453D, 0x4772, 0x4AF4, 0x4DA6, 0x4E69, 0x519E, 0x5791, 0x58B3,
    0x59E8, 0x5B0D, 0x66AC, 0x691B, 0x6A87, 0x6DD0, 0x7127, 0x7259, 0x76C2, 0x85CD, 0x86A7,
    0x871B, 0x8B6A, 0x8DB1, 0x98DA, 0x9AE1, 0x9B94, 0xA176, 0xA9C3, 0xAC8E, 0xB0EC, 0xB11D,
    0xB433, 0xB689, 0xC24F, 0xC89D, 0xCE16, 0xD23A, 0xD364, 0xD455, 0xD54A)


def _nero_radio_hamming(nibble):
    parity = (0x7, 0x6, 0x5, 0x3)
    par = 0
    for i in range(4):
        if nibble & (1 << i):
            par ^= parity[i]
    return ((nibble << 3) | par) & 0x7F


class NeroRadio(ProtoDecoder):
    """firmware nero_radio.c. te=200/400. Three formats share a 4800-baud chip stream.
    Static: standard PWM 56/57-bit. Dynamic: 0x54C header + 4 sync + 10 data symbols (6-bit each)
    + 1 trailing symbol. Normal rolling: 0xE24 header + 16 Manchester Hamming(7,4) words (64-bit
    key) + 2 raw tail words. All replay-only (cipher unknown)."""
    NAME = "Nero Radio"
    CONST = Const(200, 400, 80)
    # steps
    RESET, CHK_PRE, SAVE, CHK_DUR, DYN, NORM_ID_SYNC, NORM_ID, NORM_DATA = range(8)
    NORM_ID_PREAM = _NORM_ID_PREAM

    def __init__(self):
        super().__init__()
        self.header_count = 0
        self.dyn_bit = 0
        self.dyn_word = 0
        self.dyn_last = 0
        self.norm_id_ok = False
        self.norm_id = 0
        self.norm_word = 0
        self.norm_tail = 0

    # ---- dynamic-format bit accumulator ----
    def _dyn_add_bit(self, bit):
        pos = self.dyn_bit
        self.dyn_bit += 1
        if pos >= _DYN_FRAME_BITS:
            return False
        if pos < _DYN_HEADER_BITS:
            return bit == ((_DYN_HEADER >> (_DYN_HEADER_BITS - 1 - pos)) & 1)
        pos -= _DYN_HEADER_BITS
        self.dyn_word = ((self.dyn_word << 1) | bit) & 0xFFFF
        if (pos & 0xF) != 0xF:
            return True
        word = pos >> 4
        if word < _DYN_SYNC_WORDS:
            return self.dyn_word == _NERO_RADIO_DYN_SYNC[word]
        for i in range(len(_NERO_RADIO_DYN_SYMBOLS)):
            if _NERO_RADIO_DYN_SYMBOLS[i] != self.dyn_word:
                continue
            if word == _DYN_SYNC_WORDS + _DYN_DATA_WORDS - 1:
                self.dyn_last = i
            else:
                self.d.decode_data = (self.d.decode_data << 6) | i
            return True
        return False

    # ---- normal-rolling bit accumulator ----
    def _norm_add_bit(self, bit):
        pos = self.dyn_bit
        self.dyn_bit += 1
        if pos >= _DYN_FRAME_BITS:
            return False
        if pos < _NORM_HEAD_BITS:
            return bit == ((_NORM_HEADER >> (_NORM_HEAD_BITS - 1 - pos)) & 1)
        if pos < _NORM_TAIL_START:
            rel = pos - _NORM_HEAD_BITS
            if not (rel & 1):
                self.dyn_word = bit
                return True
            if self.dyn_word == bit:
                return False               # not Manchester
            self.norm_word = ((self.norm_word << 1) | self.dyn_word) & 0xFF
            word_end = ((rel >> 1) % 7 == 6)
        else:
            rel = pos - _NORM_TAIL_START
            if rel == 7:
                return not bit
            self.norm_word = ((self.norm_word << 1) | bit) & 0xFF
            word_end = (rel == 6) or (rel == 14)
        if not word_end:
            return True
        for nibble in range(16):
            if _nero_radio_hamming(nibble) != (self.norm_word & 0x7F):
                continue
            if pos < _NORM_TAIL_START:
                self.d.decode_data = (self.d.decode_data << 4) | nibble
            else:
                self.norm_tail = ((self.norm_tail << 4) | nibble) & 0xFF
            return True
        return False

    def _emit_frame(self, bits, data, data_2):
        self.emit(dict(serial=data, btn=0, cnt=0, bits=bits, data=data, data_2=data_2))

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        st = d.parser_step
        if st == self.RESET:
            if level and diff(duration, c.te_short) < c.te_delta:
                d.parser_step = self.CHK_PRE
                d.te_last = duration
                self.header_count = 0
            return
        if st == self.CHK_PRE:
            if level:
                if (diff(duration, c.te_short) < c.te_delta or
                        diff(duration, c.te_short * 4) < c.te_delta or
                        diff(duration, c.te_long) < c.te_delta):
                    d.te_last = duration
                else:
                    d.parser_step = self.RESET
            elif diff(duration, 1240) < 200 and diff(d.te_last, c.te_long) < c.te_delta:
                if self.header_count > 25:
                    d.parser_step = self.DYN
                    d.decode_data = 0; d.decode_count_bit = 0
                    self.dyn_bit = 0; self.dyn_word = 0
                else:
                    d.parser_step = self.RESET
            elif (diff(duration, c.te_long) < c.te_delta and
                  diff(d.te_last, c.te_short) < c.te_delta and
                  self.header_count >= self.NORM_ID_PREAM - 3):
                d.parser_step = self.NORM_ID_SYNC
            elif (diff(duration, 830) < 120 and
                  diff(d.te_last, c.te_short) < c.te_delta and self.header_count > 40):
                d.parser_step = self.NORM_DATA
                d.decode_data = 0; d.decode_count_bit = 0
                self.dyn_bit = 0; self.norm_word = 0; self.norm_tail = 0
            elif diff(duration, c.te_short) < c.te_delta:
                if diff(d.te_last, c.te_short) < c.te_delta:
                    self.header_count += 1
                elif diff(d.te_last, c.te_short * 4) < c.te_delta:
                    if self.header_count > 40:
                        d.parser_step = self.SAVE
                        d.decode_data = 0; d.decode_count_bit = 0
                    else:
                        d.parser_step = self.RESET
                else:
                    d.parser_step = self.RESET
            else:
                d.parser_step = self.RESET
            return
        if st == self.SAVE:
            if level:
                d.te_last = duration; d.parser_step = self.CHK_DUR
            else:
                d.parser_step = self.RESET
            return
        if st == self.CHK_DUR:
            if not level:
                if duration >= 900:
                    if diff(d.te_last, c.te_short) < c.te_delta:
                        d.add_bit(0)
                    elif diff(d.te_last, c.te_long) < c.te_delta:
                        d.add_bit(1)
                    if d.decode_count_bit in (56, 57):
                        self._emit_frame(d.decode_count_bit, d.decode_data, None)
                    d.decode_data = 0; d.decode_count_bit = 0
                    d.parser_step = self.RESET
                elif (diff(d.te_last, c.te_short) < c.te_delta and
                      diff(duration, c.te_long) < c.te_delta):
                    d.add_bit(0); d.parser_step = self.SAVE
                elif (diff(d.te_last, c.te_long) < c.te_delta and
                      diff(duration, c.te_short) < c.te_delta):
                    d.add_bit(1); d.parser_step = self.SAVE
                else:
                    d.parser_step = self.RESET
            else:
                d.parser_step = self.RESET
            return
        if st == self.DYN:
            if (not level) and duration >= 1500:
                valid = (_DYN_FRAME_BITS - self.dyn_bit) <= 8
                while valid and self.dyn_bit < _DYN_FRAME_BITS:
                    valid = self._dyn_add_bit(False)
                if valid:
                    self._emit_frame(_DYN_COUNT_BIT, d.decode_data, self.dyn_last)
                d.decode_data = 0; d.decode_count_bit = 0
                d.parser_step = self.RESET
            else:
                count = (duration + _DYN_TE // 2) // _DYN_TE
                if count == 0 or count > 7:
                    d.parser_step = self.RESET; return
                for _ in range(count):
                    if not self._dyn_add_bit(level):
                        d.parser_step = self.RESET; break
            return
        if st == self.NORM_ID_SYNC:
            if level and diff(duration, 1250) < 200:
                d.parser_step = self.NORM_ID
                self.dyn_bit = 0; self.norm_id = 0; self.norm_id_ok = False
            else:
                d.parser_step = self.RESET
            return
        if st == self.NORM_ID:
            if (not level) and duration >= 1000:
                self.norm_id_ok = (self.dyn_bit == _NORM_ID_BITS)
                d.parser_step = self.RESET
            else:
                count = (duration + _DYN_TE // 2) // _DYN_TE
                self.dyn_bit += count
                if count == 0 or count > 7 or self.dyn_bit > _NORM_ID_BITS:
                    d.parser_step = self.RESET; return
                for _ in range(count):
                    self.norm_id = (self.norm_id << 1) | (1 if level else 0)
            return
        if st == self.NORM_DATA:
            if (not level) and duration >= _NORM_GAP_MIN:
                valid = self.norm_id_ok and (_DYN_FRAME_BITS - self.dyn_bit) <= 16
                while valid and self.dyn_bit < _DYN_FRAME_BITS:
                    valid = self._norm_add_bit(False)
                valid = valid and (self.norm_tail == (d.decode_data & 0xFF))
                if valid:
                    self._emit_frame(_NORM_COUNT_BIT, d.decode_data,
                                     (self.norm_id << 8) | self.norm_tail)
                self.norm_id_ok = False
                d.decode_data = 0; d.decode_count_bit = 0
                d.parser_step = self.RESET
            else:
                count = (duration + _DYN_TE // 2) // _DYN_TE
                if count == 0 or count > 16:
                    self.norm_id_ok = False; d.parser_step = self.RESET; return
                for _ in range(count):
                    if not self._norm_add_bit(level):
                        self.norm_id_ok = False; d.parser_step = self.RESET; break
            return


# ====================== secplus ======================
_SECPLUS_V2_HEADER = 0x3C0000000000
_SECPLUS_V2_HEADER_MASK = 0xFFFF3C0000000000
_SECPLUS_V2_PACKET_1 = 0x000000000000
_SECPLUS_V2_PACKET_2 = 0x010000000000
_SECPLUS_V2_PACKET_MASK = 0x30000000000

# Manchester events (match arke_core)
_SL, _SH, _LL, _LH, _RST = 0, 2, 4, 6, 8


class SecPlusV2(ProtoDecoder):
    NAME = "Security+ 2.0"
    CONST = Const(250, 500, 110)
    RESET, DATA = 0, 1

    def __init__(self):
        super().__init__()
        from arke_core import Manchester
        self.m = Manchester()
        self.packet_1 = 0

    def _seed(self):
        # firmware re-seeds the Manchester phase with Reset, LongHigh, ShortLow
        self.m.advance(_RST); self.m.advance(_LH); self.m.advance(_SL)

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        event = None
        if d.parser_step == self.RESET:
            if (not level) and diff(duration, c.te_long * 130) < c.te_delta * 100:
                d.parser_step = self.DATA
                d.decode_data = 0; d.decode_count_bit = 0
                self.packet_1 = 0
                self._seed()
            return
        # DATA
        if not level:
            if diff(duration, c.te_short) < c.te_delta:
                event = _SL
            elif diff(duration, c.te_long) < c.te_delta:
                event = _LL
            elif duration >= (c.te_long * 2 + c.te_delta):
                if d.decode_count_bit == 62:
                    d_data = d.decode_data
                    if self._check_packet(d_data):
                        self.emit(dict(serial=d_data, btn=0, cnt=0, bits=62,
                                       data=d_data, data_2=self.packet_1))
                        d.parser_step = self.RESET
                d.decode_data = 0; d.decode_count_bit = 0
                self._seed()
                event = None
            else:
                d.parser_step = self.RESET
        else:
            if diff(duration, c.te_short) < c.te_delta:
                event = _SH
            elif diff(duration, c.te_long) < c.te_delta:
                event = _LH
            else:
                d.parser_step = self.RESET
        if event is not None:
            bit = self.m.advance(event)
            if bit is not None:
                d.decode_data = (d.decode_data << 1) | (1 if bit else 0)
                d.decode_count_bit += 1

    def _check_packet(self, data):
        if (data & _SECPLUS_V2_HEADER_MASK) == _SECPLUS_V2_HEADER:
            if (data & _SECPLUS_V2_PACKET_MASK) == _SECPLUS_V2_PACKET_1:
                self.packet_1 = data
            elif (data & _SECPLUS_V2_PACKET_MASK) == _SECPLUS_V2_PACKET_2 and self.packet_1:
                return True
        return False


# ====================== starline ======================
def _byteswap64(key):
    out = 0
    for i in range(0, 64, 8):
        out |= ((key >> i) & 0xFF) << (56 - i)
    return out & 0xFFFFFFFFFFFFFFFF


def _star_line_decrypt_fields(fix, hop):
    """Mirror subghz_protocol_star_line_check_remote_controller_selector: returns cnt or None."""
    try:
        import kl_crack, keeloq_keys
    except Exception:
        return None
    btn = (fix >> 24) & 0xFF
    end_serial = fix & 0xFF

    def chk(decrypt):
        return (decrypt >> 24) == btn and ((decrypt >> 16) & 0xFF) == end_serial

    for key, t, _name in keeloq_keys.KEYS:
        if t == 1:  # SIMPLE
            d = kl_crack.keeloq_decrypt(hop, key)
            if chk(d):
                return d & 0xFFFF
        elif t == 2:  # NORMAL
            man = kl_crack.normal_learning(fix, key)
            d = kl_crack.keeloq_decrypt(hop, man)
            if chk(d):
                return d & 0xFFFF
        elif t == 0:  # UNKNOWN -> 4 variants
            d = kl_crack.keeloq_decrypt(hop, key)
            if chk(d):
                return d & 0xFFFF
            man_rev = _byteswap64(key)
            d = kl_crack.keeloq_decrypt(hop, man_rev)
            if chk(d):
                return d & 0xFFFF
            man = kl_crack.normal_learning(fix, key)
            d = kl_crack.keeloq_decrypt(hop, man)
            if chk(d):
                return d & 0xFFFF
            man = kl_crack.normal_learning(fix, man_rev)
            d = kl_crack.keeloq_decrypt(hop, man)
            if chk(d):
                return d & 0xFFFF
    return None


class StarLine(ProtoDecoder):
    NAME = "Star Line"
    CONST = Const(250, 500, 120)
    RESET, PREAMB, SAVE, CHK = 0, 1, 2, 3
    BITS = 64

    def __init__(self):
        super().__init__()
        self.header_count = 0

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        st = d.parser_step
        if st == self.RESET:
            if level:
                if diff(duration, c.te_long * 2) < c.te_delta * 2:
                    d.parser_step = self.PREAMB
                    self.header_count += 1
                elif self.header_count > 4:
                    d.decode_data = 0; d.decode_count_bit = 0
                    d.te_last = duration
                    d.parser_step = self.CHK
            else:
                self.header_count = 0
            return
        if st == self.PREAMB:
            # either a matching LOW (continue preamble) or anything else -> back to Reset
            if (not level) and diff(duration, c.te_long * 2) < c.te_delta * 2:
                d.parser_step = self.RESET
            else:
                self.header_count = 0
                d.parser_step = self.RESET
            return
        if st == self.SAVE:
            if level:
                if duration >= (c.te_long + c.te_delta):
                    d.parser_step = self.RESET
                    if self.BITS <= d.decode_count_bit <= self.BITS + 2:
                        self._report(d.decode_data)
                    d.decode_data = 0; d.decode_count_bit = 0
                    self.header_count = 0
                else:
                    d.te_last = duration
                    d.parser_step = self.CHK
            else:
                d.parser_step = self.RESET
            return
        if st == self.CHK:
            if not level:
                if diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_short) < c.te_delta:
                    if d.decode_count_bit < self.BITS:
                        d.add_bit(0)
                    else:
                        d.decode_count_bit += 1
                    d.parser_step = self.SAVE
                elif diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_long) < c.te_delta:
                    if d.decode_count_bit < self.BITS:
                        d.add_bit(1)
                    else:
                        d.decode_count_bit += 1
                    d.parser_step = self.SAVE
                else:
                    d.parser_step = self.RESET
            else:
                d.parser_step = self.RESET
            return

    def _report(self, data):
        key = reverse_key(data, self.BITS)
        key_fix = (key >> 32) & 0xFFFFFFFF
        key_hop = key & 0xFFFFFFFF
        serial = key_fix & 0x00FFFFFF
        btn = (key_fix >> 24) & 0xFF
        cnt = _star_line_decrypt_fields(key_fix, key_hop)
        self.emit(dict(serial=serial, btn=btn, cnt=(cnt if cnt is not None else 0),
                       bits=self.BITS, data=data))


# ====================== hormann ======================
# Manchester events (arke_core ints)
_SL, _SH, _LL, _LH, _RST = 0, 2, 4, 6, 8
# alt-FSM states
_START1, _MID1, _START0, _MID0 = 0, 1, 2, 3
_RESET_STATE = _MID1


def _manchester_advance_alt(state, event):
    """Returns (new_state, result_bool, data_or_None). Faithful port of hormann's manchester_advance_alt."""
    if event == _RST:
        return _RESET_STATE, False, None
    if state == _START1:
        if event == _SL:
            return _MID1, True, False
        if event == _LL:
            return _START0, True, False
        return _RESET_STATE, False, None
    if state == _MID1:
        if event == _SH:
            return _START1, False, None
        if event == _SL:
            return _START0, False, None
        return _RESET_STATE, False, None
    if state == _START0:
        if event == _SH:
            return _MID0, True, True
        if event == _LH:
            return _START1, True, True
        return _RESET_STATE, False, None
    if state == _MID0:
        if event == _SL:
            return _START0, False, None
        if event == _SH:
            return _START1, False, None
        return _RESET_STATE, False, None
    return _RESET_STATE, False, None


def _crc8le(msg, poly, init):
    """Canonical Flipper subghz_protocol_blocks_crc8le: reflect init+poly, LSB-first."""
    rem = rev8(init & 0xFF)
    poly = rev8(poly & 0xFF)
    for b in msg:
        rem ^= (b & 0xFF)
        for _ in range(8):
            rem = ((rem >> 1) ^ poly) if (rem & 1) else (rem >> 1)
            rem &= 0xFF
    return rem & 0xFF


class HormannBiSecur(ProtoDecoder):
    NAME = "Hormann BiSecur"
    CONST = Const(208, 416, 104)
    RESET, PRE_SHORT, PRE_VLONG, PRE_LONG, DATA = range(5)
    BITS = 176

    def __init__(self):
        super().__init__()
        self.m_state = _RESET_STATE
        self.sync_cnt = 0
        self.buf = bytearray(22)

    def _soft_reset(self):
        self.d.parser_step = self.RESET
        self.buf = bytearray(22)
        self.d.decode_count_bit = 0
        self.m_state = 0

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        short = c.te_short; long = c.te_long; delta = c.te_delta
        half_short = short // 2
        st = d.parser_step
        if st == self.RESET:
            if (not level) and diff(duration, short + half_short) < delta:
                d.parser_step = self.PRE_SHORT
            return
        if st == self.PRE_SHORT:
            if diff(duration, short) < delta:
                return
            if level and diff(duration, long * 4) < delta:
                d.parser_step = self.PRE_VLONG
                return
            d.parser_step = self.RESET
            return
        if st == self.PRE_VLONG:
            if (not level) and diff(duration, long) < delta:
                self.sync_cnt = 3
                d.parser_step = self.PRE_LONG
                return
            d.parser_step = self.RESET
            return
        if st == self.PRE_LONG:
            want = self.sync_cnt & 1
            self.sync_cnt -= 1
            if (1 if level else 0) == want and diff(duration, long) < delta:
                if self.sync_cnt == 0:
                    self.m_state, _, _ = _manchester_advance_alt(self.m_state, _RST)  # seed
                    d.parser_step = self.DATA
                return
            d.parser_step = self.RESET
            return
        if st == self.DATA:
            event = _RST
            if diff(duration, short) < delta or (d.decode_count_bit == self.BITS - 1 and duration > short):
                event = _SH if (not level) else _SL
            if diff(duration, long) < delta:
                event = _LH if (not level) else _LL
            if event == _RST:
                self._soft_reset()
            else:
                self.m_state, result, new_level = _manchester_advance_alt(self.m_state, event)
                if result:
                    self._add_bit(new_level)
            return

    def _add_bit(self, level):
        d = self.d
        if d.decode_count_bit >= self.BITS:
            return
        if level:
            idx = d.decode_count_bit // 8
            self.buf[idx] |= 1 << (7 - (d.decode_count_bit % 8))
        d.decode_count_bit += 1
        if d.decode_count_bit >= self.BITS:
            self._finish()
            self._soft_reset()

    def _finish(self):
        b = self.buf
        typ = b[0]
        if typ == 0x50:
            crc_calc = (~(crc8(b[1:21], 0x07, 0x00) ^ 0x55)) & 0xFF
        elif typ == 0x70:
            crc_calc = _crc8le(b[0:21], 0x07, 0xFF)
        else:
            return
        if crc_calc != b[21]:
            return
        serial = 0
        for i in range(1, 5):
            serial = (serial << 8) | b[i]
        data = 0
        for i in range(5, 13):
            data = (data << 8) | b[i]
        data_2 = 0
        for i in range(13, 21):
            data_2 = (data_2 << 8) | b[i]
        self.emit(dict(serial=serial, btn=typ, cnt=0, bits=self.BITS, data=data, data_2=data_2))


# ====================== alutech ======================
_M32 = 0xFFFFFFFF

# ---- embedded 32-byte rainbow table (keystream-XOR obfuscated, uniform with Nice/keeloq) ----
_ALUTECH_SEED = b"FoxFW-alutech-at-4n-rainbow-v1"
_ALUTECH_ENC = "TwuEv8m9+mgxMRP8i1QEJ9Y1VJy8BwaDuVoC0V5kLZk="  # 32-byte table, SHA256-keystream XOR (uniform w/ Nice/keeloq)


def _alutech_magic_table():
    """Return the 32-byte table, or None if not yet dumped/embedded."""
    if not _ALUTECH_ENC:
        return None
    try:
        import hashlib, base64
        data = base64.b64decode(_ALUTECH_ENC)
        ks = bytearray(); ctr = 0
        while len(ks) < len(data):
            ks += hashlib.sha256(_ALUTECH_SEED + ctr.to_bytes(4, "big")).digest(); ctr += 1
        return bytes(a ^ b for a, b in zip(data, ks))
    except Exception:
        return None


def _magic(buf, n):
    a = n * 4
    v = 0
    for i in range(a, a + 4):
        v = (v << 8) | buf[i]
    return v & _M32


def _alutech_decrypt(data, table):
    """firmware subghz_protocol_alutech_at_4n_decrypt (XTEA-style, magic indices 0..5)."""
    m = [_magic(table, i) for i in range(6)]
    p = bytearray(data.to_bytes(8, "little"))
    data1 = (p[0] << 24 | p[1] << 16 | p[2] << 8 | p[3]) & _M32
    data2 = (p[4] << 24 | p[5] << 16 | p[6] << 8 | p[7]) & _M32
    i = m[0]
    while True:
        data2 = (data2 - (((m[1] + ((data1 << 4) & _M32)) & _M32) ^
                          (((m[2] + (data1 >> 5)) & _M32) ^ ((data1 + i) & _M32)))) & _M32
        data3 = (data2 + i) & _M32
        i = (i + m[3]) & _M32
        data1 = (data1 - (((m[4] + ((data2 << 4) & _M32)) & _M32) ^
                          (((m[5] + (data2 >> 5)) & _M32) ^ data3))) & _M32
        if i == 0:
            break
    p[0] = (data1 >> 24) & 0xFF; p[1] = (data1 >> 16) & 0xFF; p[3] = data1 & 0xFF
    p[4] = (data2 >> 24) & 0xFF; p[5] = (data2 >> 16) & 0xFF; p[2] = (data1 >> 8) & 0xFF
    p[6] = (data2 >> 8) & 0xFF; p[7] = data2 & 0xFF
    return int.from_bytes(p, "little")


def _alutech_crc(data):
    """firmware subghz_protocol_alutech_at_4n_decrypt_data_crc."""
    crc = data & 0xFF
    for _ in range(8):
        if crc & 0x80:
            crc = ((crc << 1) ^ 0x31) & 0xFF
        else:
            crc = (crc << 1) & 0xFF
    return (~crc) & 0xFF


class AlutechAT4N(ProtoDecoder):
    NAME = "Alutech AT-4N"
    CONST = Const(400, 800, 140)
    RESET, PREAMB, SAVE, CHK = 0, 1, 2, 3
    BITS = 72

    def __init__(self):
        super().__init__()
        self.header_count = 0
        self._payload = 0   # 64-bit data_2

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        st = d.parser_step
        if st == self.RESET:
            if level and diff(duration, c.te_short) < c.te_delta:
                d.parser_step = self.PREAMB
                self.header_count += 1
            return
        if st == self.PREAMB:
            if (not level) and diff(duration, c.te_short) < c.te_delta:
                d.parser_step = self.RESET
                return
            if self.header_count > 9 and diff(duration, c.te_short * 10) < c.te_delta * 10:
                d.parser_step = self.SAVE
                d.decode_data = 0; d.decode_count_bit = 0
            else:
                d.parser_step = self.RESET
                self.header_count = 0
            return
        if st == self.SAVE:
            if level:
                d.te_last = duration
                d.parser_step = self.CHK
            return
        if st == self.CHK:
            if not level:
                if duration >= (c.te_short * 2 + c.te_delta):
                    if diff(d.te_last, c.te_short) < c.te_delta:
                        d.add_bit(1)
                    elif diff(d.te_last, c.te_long) < c.te_delta * 2:
                        d.add_bit(0)
                    d.parser_step = self.RESET
                    if d.decode_count_bit == self.BITS:
                        self._report(self._payload, d.decode_data)
                        d.decode_data = 0; d.decode_count_bit = 0
                        self.header_count = 0
                elif diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_long) < c.te_delta * 2:
                    d.add_bit(1)
                    if d.decode_count_bit == 64:
                        self._payload = d.decode_data; d.decode_data = 0
                    d.parser_step = self.SAVE
                elif diff(d.te_last, c.te_long) < c.te_delta * 2 and diff(duration, c.te_short) < c.te_delta:
                    d.add_bit(0)
                    if d.decode_count_bit == 64:
                        self._payload = d.decode_data; d.decode_data = 0
                    d.parser_step = self.SAVE
                else:
                    d.parser_step = self.RESET
                    self.header_count = 0
            else:
                d.parser_step = self.RESET
                self.header_count = 0
            return

    def _report(self, payload, crc8bit):
        table = _alutech_magic_table()
        if table is not None:
            data = reverse_key(payload, 64)
            data = _alutech_decrypt(data, table)
            if (data >> 56) == _alutech_crc((data >> 8) & 0xFF):
                btn = data & 0xFF
                cnt = (data >> 8) & 0xFFFF
                serial = (data >> 24) & 0xFFFFFFFF
                self.emit(dict(serial=serial, btn=btn, cnt=cnt, bits=self.BITS,
                               data=payload, crc=crc8bit))
                return
        # no table (or crc mismatch): capture the raw encrypted payload for identification/replay
        self.emit(dict(serial=payload, btn=0, cnt=0, bits=self.BITS, data=payload, crc=crc8bit))





# ====================== batch1: structural auto RKE ======================
#!/usr/bin/env python3
r"""
batch1_decoders.py -- firmware-exact ports of 10 FoxFW automotive/RKE SubGHz feed()
state machines (lib/subghz/protocols/*.c) into arke_core ProtoDecoder subclasses.

Each decoder mirrors the C feed() exactly: same timing thresholds, parser_step order,
bit convention, CRC and field extraction. Paste these classes (and the module-level
helpers above them) into arke_gates.py.

NOTE ON NAME STRINGS: the per-protocol *.h headers that #define the
SUBGHZ_PROTOCOL_*_NAME / *_PROTOCOL_NAME macros were NOT in the uploaded source tree
(only the .c files were). The NAME values below are inferred from the macro name and
the established codebase convention (e.g. existing "Mitsubishi V0"/"Mitsubishi V1").
VERIFY each against the real .h define before shipping.

NOTE ON bmw_cas3: BMW_CAS3_MAX_BITS / BMW_CAS3_MIN_BITS live in the missing bmw_cas3.h.
They are assumed here (MAX=128, MIN=64) from the code structure (generic.data holds the
first 64 bits, generic.data_2 the remainder; deserialize reads a single 8-byte "Data"
second word). The feed/field logic is exact; only those two bounds are assumptions.
"""


# ============================================================ module-level helpers

def _suzuki_crc8(data_bytes):
    # suzuki_crc8 / mitsubishi_v0a_crc8: poly 0x7F, init 0x00, MSB-first.
    return crc8(data_bytes, 0x7F, 0x00)


# ---- Audi (Pandora) scramble/parity engine (@0xf8fc) -------------------------
def _audi_bit(x, n):
    return (x >> n) & 1


def _audi_parity(b3, b4, b5):
    return (_audi_bit(b3, 3) ^ _audi_bit(b4, 4) ^ _audi_bit(b4, 6) ^ _audi_bit(b4, 7) ^
            _audi_bit(b5, 4) ^ _audi_bit(b5, 7)) & 1


def _audi_unscramble_iteration(block):
    """Inverse of one scramble iteration over block[0..2]={b3,b4,b5}. Returns
    (new_block, parity_ok). Exact port of audi_unscramble_iteration()."""
    o0, o1, o2 = block[0], block[1], block[2]
    p = o0 & 1
    in0 = ((o0 >> 1) & 0x7F) | ((o1 & 1) << 7)
    in1 = ((o1 >> 1) & 0x7F) | ((o2 & 1) << 7)
    in2_low7 = (o2 >> 1) & 0x7F
    known = (_audi_bit(in0, 3) ^ _audi_bit(in1, 4) ^ _audi_bit(in1, 6) ^
             _audi_bit(in1, 7) ^ _audi_bit(in2_low7, 4)) & 1
    in2_bit7 = (p ^ known) & 1
    in2 = in2_low7 | (in2_bit7 << 7)
    ok = (_audi_parity(in0, in1, in2) == p)
    return [in0 & 0xFF, in1 & 0xFF, in2 & 0xFF], ok


def _audi_decrypt_block(raw):
    """Un-scramble raw[3..5] in place (4 iterations). Returns parity_ok.
    raw is a list of 8 ints; mutated."""
    block = [raw[3], raw[4], raw[5]]
    ok = True
    for _ in range(4):  # AUDI_SCRAMBLE_ROUNDS
        block, it_ok = _audi_unscramble_iteration(block)
        ok = it_ok and ok
    raw[3], raw[4], raw[5] = block[0], block[1], block[2]
    return ok


# ---- Opel V1 XTEA (key "RobertBOSCH48331") -----------------------------------
_OPEL_V1_KEY = bytes([0x52, 0x6F, 0x62, 0x65, 0x72, 0x74, 0x42, 0x4F,
                      0x53, 0x43, 0x48, 0x34, 0x38, 0x33, 0x33, 0x31])
_U32 = 0xFFFFFFFF


def _opel_v1_xtea_decrypt(key, block8):
    """Exact port of opel_v1_xtea_decrypt: 32-round XTEA, big-endian words."""
    k = [((key[i * 4] << 24) | (key[i * 4 + 1] << 16) |
          (key[i * 4 + 2] << 8) | key[i * 4 + 3]) & _U32 for i in range(4)]
    v0 = ((block8[0] << 24) | (block8[1] << 16) | (block8[2] << 8) | block8[3]) & _U32
    v1 = ((block8[4] << 24) | (block8[5] << 16) | (block8[6] << 8) | block8[7]) & _U32
    s = 0xC6EF3720
    for _ in range(32):
        v1 = (v1 - (((s + k[(s >> 11) & 3]) & _U32) ^ ((v0 + (((v0 >> 5) ^ (v0 << 4)) & _U32)) & _U32))) & _U32
        s = (s - 0x9E3779B9) & _U32
        v0 = (v0 - (((s + k[s & 3]) & _U32) ^ ((v1 + (((v1 >> 5) ^ (v1 << 4)) & _U32)) & _U32))) & _U32
    return [(v0 >> 24) & 0xFF, (v0 >> 16) & 0xFF, (v0 >> 8) & 0xFF, v0 & 0xFF,
            (v1 >> 24) & 0xFF, (v1 >> 16) & 0xFF, (v1 >> 8) & 0xFF, v1 & 0xFF]


# ============================================================ 1. Suzuki (suzuki.c)
class Suzuki(ProtoDecoder):
    """firmware suzuki.c, 64-bit. HIGH-pulse PWM: te_long(500)=1, te_short(250)=0.
    Long te_short preamble (>=200 counted LOW shorts), start bit forced to 1 on the
    first te_long HIGH, frame closed by a ~2000us LOW gap. CRC-8 (poly 0x7F) over bits
    12..59 must equal bits 4..11. Fields split from the 32/32 halves."""
    NAME = "SUZUKI"                       # inferred SUBGHZ_PROTOCOL_SUZUKI_NAME
    CONST = Const(250, 500, 99)
    GAP_TIME = 2000
    GAP_DELTA = 399
    MIN_PREAMBLE = 200

    def __init__(self):
        super().__init__()
        self.header_count = 0

    @staticmethod
    def _calc_crc(data):
        cd = [(data >> 52) & 0xFF, (data >> 44) & 0xFF, (data >> 36) & 0xFF,
              (data >> 28) & 0xFF, (data >> 20) & 0xFF, (data >> 12) & 0xFF]
        return _suzuki_crc8(cd)

    def _verify(self, data):
        return ((data >> 4) & 0xFF) == self._calc_crc(data)

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset
            if not level:
                return
            if diff(duration, c.te_short) > c.te_delta:
                return
            d.decode_data = 0
            d.decode_count_bit = 0
            d.parser_step = 1
            self.header_count = 0
        elif step == 1:  # CountPreamble
            if level:
                if self.header_count >= self.MIN_PREAMBLE:
                    if diff(duration, c.te_long) <= c.te_delta:
                        d.parser_step = 2
                        d.add_bit(1)
            else:
                if diff(duration, c.te_short) <= c.te_delta:
                    d.te_last = duration
                    self.header_count += 1
                else:
                    d.parser_step = 0
        elif step == 2:  # DecodeData
            if level:
                if duration < c.te_long:
                    diff_long = 500 - duration
                    if diff_long > 99:
                        diff_short = (250 - duration) if duration < 250 else (duration - 250)
                        if diff_short <= 99:
                            d.add_bit(0)
                    else:
                        d.add_bit(1)
                else:
                    diff_long = duration - 500
                    if diff_long <= 99:
                        d.add_bit(1)
            else:
                diff_gap = (self.GAP_TIME - duration) if duration < self.GAP_TIME else (duration - self.GAP_TIME)
                if diff_gap <= self.GAP_DELTA:
                    if d.decode_count_bit == 64:
                        data = d.decode_data
                        if self._verify(data):
                            data_high = (data >> 32) & 0xFFFFFFFF
                            data_low = data & 0xFFFFFFFF
                            serial = ((data_high & 0xFFF) << 16) | (data_low >> 16)
                            btn = (data_low >> 12) & 0xF
                            cnt = ((data_high << 4) & 0xFFFFFFFF) >> 16
                            self.emit(dict(serial=serial, btn=btn, cnt=cnt, bits=64, data=data))
                    d.decode_data = 0
                    d.decode_count_bit = 0
                    d.parser_step = 0


# ============================================================ 2. Audi (audi.c)
class Audi(ProtoDecoder):
    """firmware audi.c (Pandora "Audi"), 64-bit PWM, LSB-first within each byte.
    Header HIGH 2500 + LOW 850, then 64 PWM bits (bit1 = HIGH 1100 + LOW 550,
    bit0 = HIGH 550 + LOW 1100). Reports when UID (b0..b2) is non-degenerate and the
    {b3,b4,b5} scramble inverse is self-consistent (parity_ok) and the frame is not a
    repeat. serial=UID, btn=b6, cnt=(plain_b3<<8)|plain_b4."""
    NAME = "Audi"                         # inferred AUDI_PROTOCOL_NAME
    TE_SHORT = 550
    TE_LONG = 1100
    TE_DELTA = 137
    HEADER_HIGH = 2500
    HEADER_LOW = 850
    WIRE_BITS = 64

    def __init__(self):
        super().__init__()
        self._reset_all()

    def _reset_all(self):
        self.last_raw = None
        self._data_reset()

    def _data_reset(self):
        self.bit_count = 0
        self.have_high = False
        self.pending_high = 0
        self.bytes = [0] * 8

    @staticmethod
    def _is_short(dd):
        return diff(dd, Audi.TE_SHORT) < Audi.TE_DELTA

    @staticmethod
    def _is_long(dd):
        return diff(dd, Audi.TE_LONG) < Audi.TE_DELTA

    def _add_bit(self, bit):
        if self.bit_count >= self.WIRE_BITS:
            return
        if bit:
            self.bytes[self.bit_count >> 3] |= (1 << (self.bit_count & 7))
        self.bit_count += 1

    @staticmethod
    def _uid(raw):
        return (raw[0] << 16) | (raw[1] << 8) | raw[2]

    def _report(self):
        if self.bit_count < self.WIRE_BITS:
            return
        raw = list(self.bytes)
        uid = self._uid(raw)
        if uid == 0 or uid == 0xFFFFFF:
            return
        plain = list(raw)
        if not _audi_decrypt_block(plain):
            return
        if self.last_raw is not None and self.last_raw == raw:
            return
        self.last_raw = list(raw)
        serial = uid
        btn = raw[6]
        cnt = (plain[3] << 8) | plain[4]
        data = 0
        for b in raw:
            data = (data << 8) | b
        self.emit(dict(serial=serial, btn=btn, cnt=cnt, bits=self.WIRE_BITS, data=data))

    def feed(self, level, duration):
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset -- header HIGH ~2500
            if level and diff(duration, self.HEADER_HIGH) < self.TE_DELTA * 2:
                d.parser_step = 1
        elif step == 1:  # HeaderLow -- ~850
            if (not level) and diff(duration, self.HEADER_LOW) < self.TE_DELTA * 2:
                d.parser_step = 2
                self._data_reset()
            else:
                d.parser_step = 0
        elif step == 2:  # Data
            if level:
                if diff(duration, self.HEADER_HIGH) < self.TE_DELTA * 2:
                    self._report()
                    d.parser_step = 1
                    return
                if self._is_short(duration) or self._is_long(duration):
                    self.pending_high = duration
                    self.have_high = True
                else:
                    self._report()
                    self._data_reset()
                    d.parser_step = 0
            else:
                if not self.have_high:
                    self._report()
                    self._data_reset()
                    d.parser_step = 0
                    return
                self.have_high = False
                high_long = self._is_long(self.pending_high)
                high_short = self._is_short(self.pending_high)
                low_long = self._is_long(duration)
                low_short = self._is_short(duration)
                if high_long and low_short:
                    self._add_bit(True)
                elif high_short and low_long:
                    self._add_bit(False)
                elif high_short and (not low_short) and (not low_long):
                    self._add_bit(False)
                    self._report()
                    self._data_reset()
                    d.parser_step = 0
                    return
                else:
                    self._report()
                    self._data_reset()
                    d.parser_step = 0
                    return
                if self.bit_count == self.WIRE_BITS:
                    self._report()
                    self._data_reset()
                    d.parser_step = 0


# ============================================================ 3. Opel V0 (opel_v0.c)
class OpelV0(ProtoDecoder):
    """firmware opel_v0.c, burst-width classifier (experimental). Carrier bursts
    (marks + tiny<120us merges) separated by 4000..7000us gaps; each burst is one bit
    (width>3200 = 1). Preamble = continuous carrier >=50000us. Frame ends on a gap
    >20000us. Requires a modal 64+/-2-bit frame, rejects all-zero/all-one. No serial in
    the C -> emit serial=full data int, include data."""
    NAME = "Opel V0"                      # inferred SUBGHZ_PROTOCOL_OPEL_V0_NAME
    CARRIER_PERIOD = 830
    MERGE_THRESHOLD = 120
    GAP_MIN = 4000
    GAP_MAX = 7000
    PREAMBLE_MIN = 50000
    BIT_THRESHOLD = 3200
    MIN_BITS = 40
    MAX_BITS = 150
    FRAME_GAP = 20000
    FRAME_BITS = 64
    FRAME_DELTA = 2

    def __init__(self):
        super().__init__()
        self.current_burst = 0
        self.preamble_len = 0
        self.bit_count = 0
        self.burst_widths = [0] * self.MAX_BITS

    def _process_frame(self):
        if self.bit_count < self.MIN_BITS:
            return
        data = 0
        data_2 = 0
        count = self.bit_count
        if count > 96:
            count = 96
        for i in range(count):
            bit = 1 if self.burst_widths[i] > self.BIT_THRESHOLD else 0
            if i < 64:
                data = ((data << 1) | bit) & MASK64
            else:
                data_2 = ((data_2 << 1) | bit) & 0xFFFFFFFF
        if count < (self.FRAME_BITS - self.FRAME_DELTA) or count > (self.FRAME_BITS + self.FRAME_DELTA):
            return
        used_mask = MASK64 if count >= 64 else ((1 << count) - 1)
        all_zero = (data == 0) and (data_2 == 0)
        all_one = (data == used_mask) and (count <= 64 or data_2 == ((1 << (count - 64)) - 1))
        if all_zero or all_one:
            return
        if count < 64:
            data = (data << (64 - count)) & MASK64
        seed = 0
        if count > 64:
            extra = count - 64
            seed = (data_2 << (32 - extra)) & 0xFFFFFFFF
        self.emit(dict(serial=data, bits=count, data=data, seed=seed))

    def feed(self, level, duration):
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset
            if level:
                self.current_burst = duration
                self.preamble_len = 0
                self.bit_count = 0
                d.parser_step = 1
        elif step == 1:  # Preamble
            if level:
                self.current_burst += duration
            else:
                if duration < self.MERGE_THRESHOLD:
                    self.current_burst += duration
                elif duration < self.GAP_MAX:
                    self.preamble_len += self.current_burst
                    if self.preamble_len >= self.PREAMBLE_MIN:
                        d.parser_step = 2
                        self.bit_count = 0
                    self.current_burst = 0
                else:
                    d.parser_step = 0
        elif step == 2:  # Data
            if level:
                self.current_burst += duration
                if self.current_burst > 10000:
                    self._process_frame()
                    self.preamble_len = self.current_burst
                    self.current_burst = 0
                    self.bit_count = 0
                    d.parser_step = 1
            else:
                if duration < self.MERGE_THRESHOLD:
                    self.current_burst += duration
                elif self.GAP_MIN <= duration <= self.GAP_MAX:
                    if self.current_burst > 500 and self.bit_count < self.MAX_BITS:
                        self.burst_widths[self.bit_count] = min(self.current_burst, 0xFFFF)
                        self.bit_count += 1
                    self.current_burst = 0
                elif duration > self.FRAME_GAP:
                    if self.current_burst > 500 and self.bit_count < self.MAX_BITS:
                        self.burst_widths[self.bit_count] = min(self.current_burst, 0xFFFF)
                        self.bit_count += 1
                    self._process_frame()
                    self.current_burst = 0
                    self.bit_count = 0
                    self.preamble_len = 0
                    d.parser_step = 0
                else:
                    if self.current_burst > 0:
                        self.current_burst += duration


# ============================================================ 4. Opel V1 (opel_v1.c)
class OpelV1(ProtoDecoder):
    """firmware opel_v1.c, 93-bit Manchester + XTEA. Manchester bits accumulate into a
    128-bit (data_high:data_low) register; at 93 bits framing=(data_high>>24)&0x1F must
    be 0x02, serial = data_high&0xFFFFFF, cipher = data_low. XTEA-decrypt the cipher
    (key "RobertBOSCH48331"); valid only if plaintext byte7 == 0x05. btn=plain[1],
    cnt=plain[3..6] BE."""
    NAME = "Opel V1"                      # inferred SUBGHZ_PROTOCOL_OPEL_V1_NAME
    CONST = Const(120, 240, 70)
    FRAME_BITS = 93
    FRAMING = 0x02
    PLAINTEXT_TAIL = 0x05

    def __init__(self):
        super().__init__()
        self.m = Manchester()
        self._acc_reset()

    def _acc_reset(self):
        self.data_high = 0
        self.data_low = 0
        self.bit_count = 0
        self.m.reset()

    def _add_bit(self, bit):
        self.data_high = ((self.data_high << 1) | ((self.data_low >> 63) & 1)) & MASK64
        self.data_low = ((self.data_low << 1) | (1 if bit else 0)) & MASK64
        if self.bit_count < self.FRAME_BITS:
            self.bit_count += 1

    def _try_finish(self):
        if self.bit_count < self.FRAME_BITS:
            return False
        framing = (self.data_high >> 24) & 0x1F
        if framing != self.FRAMING:
            return False
        serial = self.data_high & 0xFFFFFF
        cipher = self.data_low
        block = [(cipher >> (56 - i * 8)) & 0xFF for i in range(8)]
        plain = _opel_v1_xtea_decrypt(_OPEL_V1_KEY, block)
        button = plain[1]
        count = (plain[3] << 24) | (plain[4] << 16) | (plain[5] << 8) | plain[6]
        tail = plain[7]
        if tail != self.PLAINTEXT_TAIL:
            return False
        self.emit(dict(serial=serial, btn=button, cnt=count, bits=64, data=cipher))
        return True

    def feed(self, level, duration):
        c = self.CONST
        if diff(duration, c.te_short) < c.te_delta:
            event = 0 if level else 2        # firmware: level?ShortLow:ShortHigh
        elif diff(duration, c.te_long) < c.te_delta:
            event = 4 if level else 6
        else:
            self._acc_reset()
            return
        bit = self.m.advance(event)
        if bit is not None:
            self._add_bit(bit)
            if self._try_finish():
                self.data_high = 0
                self.data_low = 0
                self.bit_count = 0


# ============================================================ 5. Peugot V0 (peugot_v0.c)
class PeugotV0(ProtoDecoder):
    """firmware peugot_v0.c, 80-bit PSA rolling code. Half-bit sampler: two te_short
    pulses OR one te_long pulse = one raw bit whose value is the pulse LEVEL. Collects a
    raw bit stream; a gap/invalid closes the frame. Finds a >=30-ones preamble run that
    flows into the FE FF AD marker; 80 data bits after FE: first 64 -> generic.data,
    next 16 -> data_16. No serial in C -> emit serial=full data int, include data."""
    NAME = "Peugot V0"                    # inferred SUBGHZ_PROTOCOL_PEUGOT_V0_NAME
    CONST = Const(220, 440, 100)
    DATA_BITS = 80
    MAX_BITS = 256
    MIN_PREAMBLE_BITS = 30
    SYNC_LEN = 8
    MIN_DATA_BITS = 70
    SYNC_SEARCH_LEN = 24
    _SYNC = [1, 1, 1, 1, 1, 1, 1, 0,   # FE
             1, 1, 1, 1, 1, 1, 1, 1,   # FF
             1, 0, 1, 0, 1, 1, 0, 1]   # AD

    def __init__(self):
        super().__init__()
        self.all_bits = []
        self.bit_count = 0
        self.half_bit = False
        self.data_16 = 0

    def _process_frame(self):
        if self.bit_count < (self.SYNC_LEN + self.MIN_DATA_BITS):
            return
        bits = self.all_bits
        sync_pos = -1
        run = 0
        i = 0
        while i + self.SYNC_SEARCH_LEN <= self.bit_count:
            if bits[i] == 1:
                run += 1
                i += 1
                continue
            if run >= (self.MIN_PREAMBLE_BITS + 7) and i >= 7:
                cand = i - 7
                match = True
                for j in range(self.SYNC_SEARCH_LEN):
                    if bits[cand + j] != self._SYNC[j]:
                        match = False
                        break
                if match:
                    sync_pos = cand
                    break
            run = 0
            i += 1
        if sync_pos < 0:
            return
        data_start = sync_pos + self.SYNC_LEN
        data_available = self.bit_count - data_start
        if data_available < self.DATA_BITS:
            return
        data_bits = self.DATA_BITS
        data = 0
        bits_for_data = 64 if data_bits > 64 else data_bits
        for k in range(bits_for_data):
            data = (data << 1) | bits[data_start + k]
        if bits_for_data < 64:
            data <<= (64 - bits_for_data)
        data &= MASK64
        data_16 = 0
        if data_bits > 64:
            extra_bits = min(data_bits - 64, 16)
            for k in range(extra_bits):
                data_16 = (data_16 << 1) | bits[data_start + 64 + k]
            if extra_bits < 16:
                data_16 <<= (16 - extra_bits)
        data_16 &= 0xFFFF
        if (data == 0 and data_16 == 0) or (data == MASK64 and data_16 == 0xFFFF):
            return
        self.data_16 = data_16
        self.emit(dict(serial=data, bits=self.DATA_BITS, data=data, data16=data_16))

    def feed(self, level, duration):
        c = self.CONST
        is_short = diff(duration, c.te_short) <= c.te_delta
        is_long = diff(duration, c.te_long) <= c.te_delta
        if is_short:
            if self.half_bit:
                if self.bit_count < self.MAX_BITS:
                    self.all_bits.append(1 if level else 0)
                    self.bit_count += 1
                self.half_bit = False
            else:
                self.half_bit = True
        elif is_long:
            if self.bit_count < self.MAX_BITS:
                self.all_bits.append(1 if level else 0)
                self.bit_count += 1
            self.half_bit = False
        else:
            if self.bit_count > 0:
                self._process_frame()
                self.all_bits = []
                self.bit_count = 0
                self.half_bit = False
        if self.bit_count >= self.MAX_BITS:
            self._process_frame()
            self.all_bits = []
            self.bit_count = 0
            self.half_bit = False


# ============================================================ 6. BMW CAS3 (bmw_cas3.c)
class BmwCas3(ProtoDecoder):
    """firmware bmw_cas3.c, PPM (AES fob, structural). ~250us mark; ~500us space = 0,
    ~1500us space = 1. Two back-to-back >=10ms sync intervals arm the frame. Bits stored
    MSB-first; generic.data = first 64 bits, data_2 = remainder, serial = data>>32.
    MAX_BITS/MIN_BITS assumed (128/64 -- see module docstring)."""
    NAME = "BMW CAS3"                     # inferred BMW_CAS3_PROTOCOL_NAME
    SYNC_MIN = 10000
    MARK_MIN = 100
    MARK_MAX = 400
    ZERO_MIN = 300
    ZERO_MAX = 700
    ONE_MIN = 1050
    ONE_MAX = 1950
    MAX_BITS = 128           # ASSUMED (BMW_CAS3_MAX_BITS not in uploaded header)
    MIN_BITS = 64            # ASSUMED (BMW_CAS3_MIN_BITS not in uploaded header)

    def __init__(self):
        super().__init__()
        self.step = 0        # 0=Sync 1=Mark 2=Space
        self.sync_count = 0
        self.bit_count = 0
        self.bits = [0] * (self.MAX_BITS // 8)

    def _finish(self):
        if self.bit_count >= self.MIN_BITS:
            data = 0
            data_2 = 0
            for i in range(self.bit_count):
                bit = (self.bits[i // 8] >> (7 - (i % 8))) & 1
                if i < 64:
                    data = ((data << 1) | bit) & MASK64
                else:
                    data_2 = ((data_2 << 1) | bit) & MASK64
            serial = (data >> 32) & 0xFFFFFFFF
            self.emit(dict(serial=serial, bits=self.bit_count, data=data, data_2=data_2))
        self.bit_count = 0

    def _resync(self, duration):
        self.step = 0
        self.sync_count = 1 if duration >= self.SYNC_MIN else 0

    def feed(self, level, duration):
        if self.step == 0:  # Sync
            if duration >= self.SYNC_MIN:
                self.sync_count += 1
                if self.sync_count >= 2:
                    self.step = 1
                    self.bit_count = 0
                    self.bits = [0] * (self.MAX_BITS // 8)
            else:
                self.sync_count = 0
        elif self.step == 1:  # Mark
            if level and self.MARK_MIN <= duration <= self.MARK_MAX:
                self.step = 2
            else:
                self._finish()
                self._resync(duration)
        elif self.step == 2:  # Space
            bit = -1
            if not level:
                if self.ZERO_MIN <= duration <= self.ZERO_MAX:
                    bit = 0
                elif self.ONE_MIN <= duration <= self.ONE_MAX:
                    bit = 1
            if bit < 0:
                self._finish()
                self._resync(duration)
                return
            if bit:
                self.bits[self.bit_count // 8] |= (0x80 >> (self.bit_count % 8))
            self.bit_count += 1
            if self.bit_count >= self.MAX_BITS:
                self._finish()
                self.step = 0
                self.sync_count = 0
            else:
                self.step = 1


# ============================================================ 7. BMW CAS4 (bmw_cas4.c)
class BmwCas4(ProtoDecoder):
    """firmware bmw_cas4.c, 64-bit Manchester. Preamble = >=10 pulses of 300..700us,
    then a >=1800us LOW gap resets Manchester and starts data. 64 Manchester bits into
    raw_data (MSB-first) and generic.data; reports only if raw_data[0]==0x30 and
    raw_data[6]==0xC5. serial = data>>32. No field math in C -> emit serial=data>>32,
    include data."""
    NAME = "BMW CAS4"                     # inferred BMW_CAS4_PROTOCOL_NAME
    CONST = Const(500, 1000, 150)
    PRE_PULSE_MIN = 300
    PRE_PULSE_MAX = 700
    PRE_MIN = 10
    DATA_BITS = 64
    GAP_MIN = 1800
    BYTE0_MARKER = 0x30
    BYTE6_MARKER = 0xC5

    def __init__(self):
        super().__init__()
        self.m = Manchester()
        self.state = 0      # 0=Reset 1=Preamble 2=Data
        self.preamble_count = 0
        self.raw_data = [0] * 8
        self.bit_count = 0

    def feed(self, level, duration):
        c = self.CONST
        if self.state == 0:  # Reset
            if level and self.PRE_PULSE_MIN <= duration <= self.PRE_PULSE_MAX:
                self.state = 1
                self.preamble_count = 1
                self.d.te_last = duration
        elif self.state == 1:  # Preamble
            if self.PRE_PULSE_MIN <= duration <= self.PRE_PULSE_MAX:
                self.preamble_count += 1
                self.d.te_last = duration
            elif (not level) and duration >= self.GAP_MIN:
                if self.preamble_count >= self.PRE_MIN:
                    self.bit_count = 0
                    self.d.decode_data = 0
                    self.raw_data = [0] * 8
                    self.m.reset()
                    self.state = 2
                else:
                    self.state = 0
            else:
                self.state = 0
        elif self.state == 2:  # Data
            if self.bit_count >= self.DATA_BITS:
                self.state = 0
                return
            event = 8
            if diff(duration, c.te_short) < c.te_delta:
                event = 0 if level else 2    # firmware: level?ShortLow:ShortHigh
            elif diff(duration, c.te_long) < c.te_delta:
                event = 4 if level else 6
            if event != 8:
                bit = self.m.advance(event)
                if bit is not None:
                    new_bit = 1 if bit else 0
                    if self.bit_count < self.DATA_BITS:
                        byte_idx = self.bit_count // 8
                        bit_pos = 7 - (self.bit_count % 8)
                        if new_bit:
                            self.raw_data[byte_idx] |= (1 << bit_pos)
                        self.d.decode_data = ((self.d.decode_data << 1) | new_bit) & MASK64
                    self.bit_count += 1
                    if self.bit_count == self.DATA_BITS:
                        if self.raw_data[0] == self.BYTE0_MARKER and self.raw_data[6] == self.BYTE6_MARKER:
                            data = self.d.decode_data
                            serial = (data >> 32) & 0xFFFFFFFF
                            self.emit(dict(serial=serial, bits=self.DATA_BITS, data=data))
                        self.state = 0
            else:
                self.state = 0
            self.d.te_last = duration


# ============================================================ 8. KIA V2 (kia_v2.c)
class KiaV2(ProtoDecoder):
    """firmware kia_v2.c, 53-bit Manchester. Long te_long(1000) preamble (>=100 counted),
    a te_short HIGH forces the first bit (count seeded to 1 then add_bit(1)), then
    Manchester bits to count==53. serial=(data>>20)&0xFFFFFFFF, btn=(data>>16)&0xF,
    raw_count=(data>>4)&0xFFF, cnt=((raw_count>>4)|(raw_count<<8))&0xFFF, 4-bit CRC in
    data&0xF."""
    NAME = "KIA/HYU V2"                       # inferred SUBGHZ_PROTOCOL_KIA_V2_NAME
    CONST = Const(500, 1000, 150)
    BITS = 53

    def __init__(self):
        super().__init__()
        self.header_count = 0
        self.m = Manchester()

    @staticmethod
    def _calc_crc(data):
        dwc = data >> 4
        crc = 0
        for i in range(6):
            b = (dwc >> (i * 8)) & 0xFF
            crc ^= (b & 0x0F) ^ (b >> 4)
        return (crc + 1) & 0x0F

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset
            if level and diff(duration, c.te_long) < c.te_delta:
                d.parser_step = 1
                d.te_last = duration
                self.header_count = 0
                self.m.reset()
        elif step == 1:  # CheckPreamble
            if level:
                if diff(duration, c.te_long) < c.te_delta:
                    d.te_last = duration
                    self.header_count += 1
                elif diff(duration, c.te_short) < c.te_delta:
                    if self.header_count >= 100:
                        self.header_count = 0
                        d.decode_data = 0
                        d.decode_count_bit = 1
                        d.parser_step = 2
                        d.add_bit(1)
                    else:
                        d.te_last = duration
                else:
                    d.parser_step = 0
            else:
                if diff(duration, c.te_long) < c.te_delta:
                    self.header_count += 1
                    d.te_last = duration
                elif diff(duration, c.te_short) < c.te_delta:
                    d.te_last = duration
                else:
                    d.parser_step = 0
        elif step == 2:  # CollectRawBits
            if diff(duration, c.te_short) < c.te_delta:
                event = 0 if level else 2
            elif diff(duration, c.te_long) < c.te_delta:
                event = 4 if level else 6
            else:
                d.parser_step = 0
                return
            bit = self.m.advance(event)
            if bit is not None:
                d.decode_data = ((d.decode_data << 1) | (1 if bit else 0)) & MASK64
                d.decode_count_bit += 1
                if d.decode_count_bit == self.BITS:
                    data = d.decode_data
                    serial = (data >> 20) & 0xFFFFFFFF
                    btn = (data >> 16) & 0x0F
                    raw_count = (data >> 4) & 0xFFF
                    cnt = ((raw_count >> 4) | (raw_count << 8)) & 0xFFF
                    if (data & 0x0F) == self._calc_crc(data):
                        self.emit(dict(serial=serial, btn=btn, cnt=cnt, bits=self.BITS, data=data))
                    d.decode_data = 0
                    d.decode_count_bit = 0
                    self.header_count = 0
                    d.parser_step = 0


# ============================================================ 9. Mitsubishi V0A (mitsubishi_v0a.c)
class MitsubishiV0a(ProtoDecoder):
    """firmware mitsubishi_v0a.c, 61-bit, repeat-gated. te_short(250) preamble pairs,
    a te_long/te_long pair forces the first bit (count seeded to 1 then add_bit(1)).
    Data bits: HIGH short + LOW short = 0, HIGH long + LOW long = 1. Frame closes on a
    HIGH >= te_long+2*delta. CRC-8 (poly 0x7F) over bits 8..55 == bits 0..7. Reports only
    when an identical frame repeats with preamble count in [72,88]. No serial in C ->
    emit serial=full data int, include data."""
    NAME = "Mitsubishi V0-a"               # inferred MITSUBISHI_PROTOCOL_V0A_NAME
    CONST = Const(250, 500, 100)
    BITS = 61
    PREAMBLE_MIN = 72
    PREAMBLE_MAX = 88

    def __init__(self):
        super().__init__()
        self.header_count = 0
        self.last_data = 0
        self.have_last = False

    @staticmethod
    def _calc_crc(data):
        cd = [(data >> 48) & 0xFF, (data >> 40) & 0xFF, (data >> 32) & 0xFF,
              (data >> 24) & 0xFF, (data >> 16) & 0xFF, (data >> 8) & 0xFF]
        return _suzuki_crc8(cd)

    def _verify(self, data):
        return (data & 0xFF) == self._calc_crc(data)

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset
            if level and diff(duration, c.te_short) < c.te_delta:
                d.parser_step = 1
                d.te_last = duration
                self.header_count = 0
        elif step == 1:  # CheckPreambula
            if level:
                if diff(duration, c.te_short) < c.te_delta or diff(duration, c.te_long) < c.te_delta:
                    d.te_last = duration
                else:
                    d.parser_step = 0
            elif (diff(duration, c.te_short) < c.te_delta and
                  diff(d.te_last, c.te_short) < c.te_delta):
                self.header_count += 1
            elif (diff(duration, c.te_long) < c.te_delta and
                  diff(d.te_last, c.te_long) < c.te_delta):
                if self.header_count > 15:
                    d.parser_step = 2
                    d.decode_data = 0
                    d.decode_count_bit = 1
                    d.add_bit(1)
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0
        elif step == 2:  # SaveDuration
            if level:
                if duration >= (c.te_long + c.te_delta * 2):
                    d.parser_step = 0
                    if d.decode_count_bit == self.BITS:
                        data = d.decode_data
                        if self._verify(data):
                            is_repeat = (self.have_last and self.last_data == data and
                                         self.PREAMBLE_MIN <= self.header_count <= self.PREAMBLE_MAX)
                            self.last_data = data
                            self.have_last = True
                            if is_repeat:
                                self.emit(dict(serial=data, bits=self.BITS, data=data))
                    d.decode_data = 0
                    d.decode_count_bit = 0
                else:
                    d.te_last = duration
                    d.parser_step = 3
            else:
                d.parser_step = 0
        elif step == 3:  # CheckDuration
            if not level:
                if (diff(d.te_last, c.te_short) < c.te_delta and diff(duration, c.te_short) < c.te_delta):
                    d.add_bit(0)
                    d.parser_step = 2
                elif (diff(d.te_last, c.te_long) < c.te_delta and diff(duration, c.te_long) < c.te_delta):
                    d.add_bit(1)
                    d.parser_step = 2
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0


# ============================================================ 10. Nidec RKE (nidec_rke.c)
class NidecRke(ProtoDecoder):
    """firmware nidec_rke.c (Honda OEM OUCG8D-344H-A), 64-bit FSK-PWM. Mark ~250=raw 1,
    ~500=raw 0; space >=700 ends the packet. Sync = raw 0xFFFFFFF0; then payload bits are
    captured INVERTED (raw^1). Structural gate (byte5 high nibble 0x5, non-degenerate
    id/seq/sec, button whitelist). serial=id(b2..b4), btn=b5&0xF, cnt=seq(b0..b1),
    security(b6..b7). Accepts 56..64 bits (tail zero-padded)."""
    NAME = "Nidec RKE"                    # inferred NIDEC_RKE_PROTOCOL_NAME
    TE_SHORT = 250
    TE_LONG = 500
    MARK_MIN = 130
    MARK_MID = 375
    MARK_MAX = 700
    GAP_RESET = 700
    SYNC_WORD = 0xFFFFFFF0
    PAYLOAD_BITS = 64
    MIN_BITS = 56

    def __init__(self):
        super().__init__()
        self.sync_reg = 0
        self.payload = 0
        self.payload_bits = 0
        self.step = 0      # 0=Reset 1=Data

    @staticmethod
    def _byte(data, index):
        return (data >> (56 - 8 * index)) & 0xFF

    @staticmethod
    def _btn_valid(b):
        return b in (0x3, 0x4, 0x5, 0x6, 0xF)

    def _process(self):
        bits = self.payload_bits
        if bits < self.MIN_BITS:
            return
        if bits > self.PAYLOAD_BITS:
            bits = self.PAYLOAD_BITS
        data = self.payload
        if bits < self.PAYLOAD_BITS:
            data = (data << (self.PAYLOAD_BITS - bits)) & MASK64
        byte5 = self._byte(data, 5)
        if (byte5 & 0xF0) != 0x50:
            return
        sequence = (self._byte(data, 0) << 8) | self._byte(data, 1)
        idv = (self._byte(data, 2) << 16) | (self._byte(data, 3) << 8) | self._byte(data, 4)
        button = byte5 & 0x0F
        security = (self._byte(data, 6) << 8) | self._byte(data, 7)
        if idv == 0 or idv == 0xFFFFFF:
            return
        if sequence == 0 or sequence == 0xFFFF:
            return
        if security == 0 or security == 0xFFFF:
            return
        if not self._btn_valid(button):
            return
        self.emit(dict(serial=idv, btn=button, cnt=sequence, bits=bits, data=data, seed=security))

    def feed(self, level, duration):
        if not level:
            if duration >= self.GAP_RESET:
                if self.step == 1:
                    self._process()
                self.step = 0
                self.sync_reg = 0
                self.payload = 0
                self.payload_bits = 0
            return
        if self.MARK_MIN <= duration < self.MARK_MID:
            raw_bit = 1
        elif self.MARK_MID <= duration <= self.MARK_MAX:
            raw_bit = 0
        else:
            self.step = 0
            self.sync_reg = 0
            self.payload = 0
            self.payload_bits = 0
            return
        if self.step == 0:  # Reset -- hunt sync
            self.sync_reg = ((self.sync_reg << 1) | raw_bit) & 0xFFFFFFFF
            if self.sync_reg == self.SYNC_WORD:
                self.step = 1
                self.payload = 0
                self.payload_bits = 0
        elif self.step == 1:  # Data
            self.payload = ((self.payload << 1) | (raw_bit ^ 1)) & MASK64
            self.payload_bits += 1
            if self.payload_bits >= self.PAYLOAD_BITS:
                self._process()
                self.step = 0
                self.sync_reg = 0
                self.payload = 0
                self.payload_bits = 0


# ====================== batch2: ppa-Manchester + structural autos ======================
#!/usr/bin/env python3
r"""
batch2_decoders.py -- firmware-exact ports of 12 FoxFW SubGHz automotive decoders.

Faithful Python ports of the firmware feed() state machines in
lib/subghz/protocols/*.c, matching the house style of arke_gates.py (Mic6sc2 is
the canonical ppa_manchester_event + byte-buffer example). Each class subclasses
ProtoDecoder from arke_core.

Protocols (firmware .c -> class):
  siemens_vdo      -> SiemensVdo        (ppa Manchester ZEROBIT + 16-bit sync hunt + XOR MIC)
  alps_rke         -> AlpsRke           (short-preamble lock + ppa Manchester + 0x55 window search)
  code_alarm       -> CodeAlarm         (ppa Manchester, reset-bracketed 60-bit, XOR-fold id)
  hyundai_kia_rio  -> HyundaiKiaRio     (PWM HIGH-mark bit + 10.4ms sync + ~checksum)
  santa_fe         -> SantaFe           (PWM HIGH-mark bit + 12ms sync + CRC8/0x31)
  mazda_infinity   -> MazdaInfinity     (half-cell Manchester window, FF FF D7 sync, de-whiten)
  hundai           -> Hundai            (half-cell Manchester, tail-anchored on 4x 0x55)
  audiovox         -> Audiovox          (PWM HIGH-mark bit, 25-bit, invert + button-mask MIC)
  astrostart       -> Astrostart        (PPM gap-encodes-bit, 52-bit, ~btn + nibble checksum)
  compustar        -> Compustar         (PWM mark-encodes-bit, 36-bit, ~button MIC)
  continental_rke  -> ContinentalRke    (rtl_433 manchester-zerobit accumulator + 20-bit preamble + XOR)
  tesla_charge     -> TeslaCharge       (toolbox Manchester NON-inverting, single fixed 50-bit code)

NAME strings: the firmware NAME #defines live in the per-protocol .h files, which
were NOT present in the uploaded source tree (only the .c files). The NAME values
below are inferred from rtl_433 / Flipper display-name convention and MUST be
confirmed against the firmware SUBGHZ_PROTOCOL_*_NAME / *_PROTOCOL_NAME defines.
Everything else (timing, steps, bit order, sync words, CRC, field math) is ported
bit-exact from the .c feed()/process() code.
"""

# Manchester event ints (match arke_core.Manchester.advance and the firmware enum).
M_SHORT_LOW, M_SHORT_HIGH, M_LONG_LOW, M_LONG_HIGH, M_RESET = 0, 2, 4, 6, 8


def ppa_manchester_event(duration, level, c):
    """Exact port of pp_auto_common.c ppa_manchester_event: a HIGH pulse maps to a
    *Low* event (level is INVERTED), single te_delta window for both short/long."""
    if diff(duration, c.te_short) < c.te_delta:
        return M_SHORT_LOW if level else M_SHORT_HIGH
    if diff(duration, c.te_long) < c.te_delta:
        return M_LONG_LOW if level else M_LONG_HIGH
    return M_RESET


# ============================================================ siemens_vdo
class SiemensVdo(ProtoDecoder):
    """firmware siemens_vdo.c. Manchester ZEROBIT (ppa, inverts level), te=220/440,
    delta=100. Recovered bits feed a 16-bit sync hunt (0x6001, or inverted 0x9FFE ->
    bit-invert the captured bytes). After sync, 96 payload bits MSB-first into 12 bytes.
    MIC: sum != 0/0xFF*12 AND XOR(all 12 bytes)==0. id = b[3..0] LE; data = b[4..11] BE;
    btn = data>>56, cnt = (data>>40)&0xFFFF."""
    NAME = "Siemens VDO"
    CONST = Const(220, 440, 100)
    SYNC = 0x6001
    SYNC_INV = 0x9FFE
    PAYLOAD_BITS = 96
    PBYTES = 12

    def __init__(self):
        super().__init__()
        self.ms = Manchester()
        self.sync_shift = 0
        self.sync_bits = 0
        self.synced = False
        self.invert = False
        self.data_bit_count = 0
        self.bytes = bytearray(self.PBYTES)

    def _reset_state(self):
        self.sync_shift = 0
        self.sync_bits = 0
        self.synced = False
        self.invert = False
        self.data_bit_count = 0
        self.ms.reset()

    def feed(self, level, duration):
        event = ppa_manchester_event(duration, level, self.CONST)
        if event == M_RESET:
            self._reset_state()
            return
        bit = self.ms.advance(event)
        if bit is None:
            return
        data = 1 if bit else 0
        if not self.synced:
            self.sync_shift = ((self.sync_shift << 1) | data) & 0xFFFF
            if self.sync_bits < 16:
                self.sync_bits += 1
            if self.sync_bits >= 16:
                if self.sync_shift == self.SYNC:
                    self.synced = True
                    self.invert = False
                elif self.sync_shift == self.SYNC_INV:
                    self.synced = True
                    self.invert = True
                if self.synced:
                    self.data_bit_count = 0
                    self.bytes = bytearray(self.PBYTES)
            return
        idx = self.data_bit_count >> 3
        if idx < self.PBYTES and data:
            self.bytes[idx] |= (1 << (7 - (self.data_bit_count & 7)))
        self.data_bit_count += 1
        if self.data_bit_count >= self.PAYLOAD_BITS:
            self._process()
            self.synced = False
            self.invert = False
            self.sync_shift = 0
            self.sync_bits = 0
            self.data_bit_count = 0

    def _process(self):
        b = [((~self.bytes[i]) & 0xFF) if self.invert else self.bytes[i]
             for i in range(self.PBYTES)]
        s = sum(b)
        if s == 0 or s == 0xFF * self.PBYTES:
            return
        x = 0
        for v in b:
            x ^= v
        if x != 0:
            return
        idv = (b[3] << 24) | (b[2] << 16) | (b[1] << 8) | b[0]
        data = 0
        for i in range(4, self.PBYTES):
            data = (data << 8) | b[i]
        btn = (data >> 56) & 0xFF
        cnt = (data >> 40) & 0xFFFF
        self.emit(dict(serial=idv, btn=btn, cnt=cnt, bits=self.PAYLOAD_BITS, data=data))


# ============================================================ code_alarm
class CodeAlarm(ProtoDecoder):
    """firmware code_alarm.c. Manchester ZEROBIT (ppa), te=550/1100, delta=200. A reset
    gap arms collection; bits accumulate (<<1|bit) until the next reset gap at exactly 60
    bits. Checks: top 16 bits (preamble) == 0; payload40=(data>>1)&40ones -> 5 bytes,
    sum != 0 and < 0xFF*5; code36=payload40&36ones -> 4 bytes, id = XOR-fold, btn =
    bytes[0]>>4, cnt = 0."""
    NAME = "Code Alarm"
    CONST = Const(550, 1100, 200)
    BITS = 60

    def __init__(self):
        super().__init__()
        self.ms = Manchester()

    def feed(self, level, duration):
        d = self.d
        event = ppa_manchester_event(duration, level, self.CONST)
        if d.parser_step == 0:  # Reset: the inter-frame gap arms collection
            if event == M_RESET:
                d.decode_data = 0
                d.decode_count_bit = 0
                self.ms.reset()
                d.parser_step = 1
        else:  # Data
            if event == M_RESET:
                if d.decode_count_bit == self.BITS:
                    self._decode(d.decode_data)
                d.decode_data = 0
                d.decode_count_bit = 0
                self.ms.reset()
            else:
                bit = self.ms.advance(event)
                if bit is not None:
                    d.add_bit(1 if bit else 0)
                    if d.decode_count_bit > self.BITS + 4:
                        d.decode_data = 0
                        d.decode_count_bit = 0
                        self.ms.reset()

    def _decode(self, data):
        if (data >> 44) != 0:
            return
        payload40 = (data >> 1) & 0xFFFFFFFFFF
        by = [(payload40 >> 32) & 0xFF, (payload40 >> 24) & 0xFF, (payload40 >> 16) & 0xFF,
              (payload40 >> 8) & 0xFF, payload40 & 0xFF]
        bsum = sum(by)
        if bsum == 0 or bsum >= 0xFF * 5:
            return
        code36 = payload40 & 0xFFFFFFFFF
        code = [(code36 >> 28) & 0xFF, (code36 >> 20) & 0xFF, (code36 >> 12) & 0xFF,
                (code36 >> 4) & 0xFF]
        idv = ((code[0] ^ code[1]) << 16) | ((code[1] ^ code[2]) << 8) | (code[2] ^ code[3])
        btn = by[0] >> 4
        self.emit(dict(serial=idv, btn=btn, cnt=0, bits=self.BITS, data=data))


# ============================================================ alps_rke
class AlpsRke(ProtoDecoder):
    """firmware alps_rke.c. te=500/1000, delta=150. Locks on >=8 consecutive te_short
    pulses (0x55 preamble), then ppa-Manchester-decodes into a bit ring; a reset gap
    finalizes. Window search (both polarities) over the ring for 9 bytes: byte[0]==0x55,
    byte[5]==byte[6], id=byte[1..4] BE not 0/0xFFFFFFFF/0x55555555/0xAAAAAAAA; keeps the
    last valid alignment. btn=byte[5]>>4, cnt=(byte[7]<<8)|byte[8]."""
    NAME = "Alps FWB1U545"
    CONST = Const(500, 1000, 150)
    FRAME_BITS = 76
    NEED_BITS = 72
    MIN_PREAMBLE = 8
    MAX_BITS = 96
    RING_DROP = 16

    def __init__(self):
        super().__init__()
        self.ms = Manchester()
        self.preamble_count = 0
        self.bits = []

    def _store_bit(self, bit):
        if len(self.bits) >= self.MAX_BITS:
            self.bits = self.bits[self.RING_DROP:]
        self.bits.append(bit & 1)

    def _manchester_feed(self, level, duration):
        event = ppa_manchester_event(duration, level, self.CONST)
        if event == M_RESET:
            self._finalize()
            return
        bit = self.ms.advance(event)
        if bit is not None:
            self._store_bit(1 if bit else 0)

    def _finalize(self):
        if not self._try_decode(False):
            self._try_decode(True)
        self.d.parser_step = 0
        self.preamble_count = 0
        self.bits = []

    def _try_decode(self, invert):
        count = len(self.bits)
        if count < self.NEED_BITS:
            return False
        best = None
        for off in range(0, count - self.NEED_BITS + 1):
            by = []
            for k in range(9):
                b = 0
                for j in range(8):
                    bit = self.bits[off + k * 8 + j]
                    if invert:
                        bit ^= 1
                    b = ((b << 1) | (bit & 1)) & 0xFF
                by.append(b)
            if by[0] != 0x55:
                continue
            if by[5] != by[6]:
                continue
            idv = (by[1] << 24) | (by[2] << 16) | (by[3] << 8) | by[4]
            if idv == 0 or idv == 0xFFFFFFFF:
                continue
            if idv == 0x55555555 or idv == 0xAAAAAAAA:
                continue
            best = list(by)
        if best is None:
            return False
        idv = (best[1] << 24) | (best[2] << 16) | (best[3] << 8) | best[4]
        btn = best[5] >> 4
        seq = (best[7] << 8) | best[8]
        data = ((idv << 32) | (best[5] << 24) | (best[6] << 16) | seq) & MASK64
        self.emit(dict(serial=idv, btn=btn, cnt=seq, bits=self.FRAME_BITS, data=data))
        return True

    def feed(self, level, duration):
        d = self.d
        is_short = diff(duration, self.CONST.te_short) < self.CONST.te_delta
        if d.parser_step == 0:  # Reset
            if is_short:
                self.preamble_count = 1
                d.parser_step = 1
        elif d.parser_step == 1:  # Preamble
            if is_short:
                self.preamble_count += 1
                if self.preamble_count >= self.MIN_PREAMBLE:
                    d.parser_step = 2
                    self.bits = []
                    self.ms.reset()
                    self._manchester_feed(level, duration)
            else:
                d.parser_step = 0
                self.preamble_count = 0
        elif d.parser_step == 2:  # Data
            self._manchester_feed(level, duration)


# ============================================================ hyundai_kia_rio
class HyundaiKiaRio(ProtoDecoder):
    """firmware hyundai_kia_rio.c. PWM, te=312/728, delta=150. Sync = HIGH ~te_short then
    LOW ~10400 (+/-1560). Each bit: HIGH long=1 / short=0, then a LOW separator. 64 bits
    MSB-first (<<1|bit). Checksum: c=(serial^(serial>>16))^mask; rx=~c must match.
    serial=data>>32, mask=(data>>16)&0xFFFF, btn=mask_to_button(mask), cnt=0."""
    NAME = "Hyundai/Kia RIO"
    CONST = Const(312, 728, 150)
    SYNC = 10400
    SYNC_DELTA = 1560
    BITS = 64

    def __init__(self):
        super().__init__()
        self.data = 0
        self.bit_count = 0

    def feed(self, level, duration):
        c = self.CONST
        ts, tl, td = c.te_short, c.te_long, c.te_delta
        d = self.d
        st = d.parser_step
        if st == 0:
            if level and diff(duration, ts) < td:
                d.parser_step = 1
        elif st == 1:
            if (not level) and diff(duration, self.SYNC) < self.SYNC_DELTA:
                d.parser_step = 2
                self.data = 0
                self.bit_count = 0
            else:
                d.parser_step = 0
        elif st == 2:
            if level:
                if diff(duration, tl) < td:
                    self._addbit(1)
                    d.parser_step = 3
                elif diff(duration, ts) < td:
                    self._addbit(0)
                    d.parser_step = 3
                elif duration > 3000:
                    if self.bit_count >= self.BITS:
                        self._publish()
                    d.parser_step = 0
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0
        elif st == 3:
            if not level:
                if diff(duration, ts) < td or diff(duration, tl) < td:
                    if self.bit_count >= self.BITS:
                        self._publish()
                        d.parser_step = 0
                    else:
                        d.parser_step = 2
                elif duration > 3000:
                    if self.bit_count >= self.BITS:
                        self._publish()
                    d.parser_step = 0
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0

    def _addbit(self, bit):
        self.data = ((self.data << 1) | (bit & 1)) & MASK64
        self.bit_count += 1

    @staticmethod
    def _cksum(serial, mask):
        c = (serial ^ (serial >> 16)) & 0xFFFF
        c ^= mask
        return (~c) & 0xFFFF

    @staticmethod
    def _btn(mask):
        return {0x0100: 1, 0x0200: 2, 0x0400: 4, 0x0800: 8}.get(mask, (mask >> 8) & 0xFF)

    def _publish(self):
        serial = (self.data >> 32) & 0xFFFFFFFF
        mask = (self.data >> 16) & 0xFFFF
        rx = self.data & 0xFFFF
        if rx != self._cksum(serial, mask):
            return
        self.emit(dict(serial=serial, btn=self._btn(mask), cnt=0,
                       bits=self.BITS, data=self.data))


# ============================================================ santa_fe
def _sf_crc8(data, length):
    """firmware santa_fe sf_crc8: init 0xFF, poly 0x31, MSB-first, no reflect/xorout."""
    crc = 0xFF
    for i in range(length):
        crc ^= data[i]
        for _ in range(8):
            if crc & 0x80:
                crc = ((crc << 1) ^ 0x31) & 0xFF
            else:
                crc = (crc << 1) & 0xFF
    return crc


class SantaFe(ProtoDecoder):
    """firmware santa_fe.c. PWM, te=125/375, delta=100. Sync = HIGH ~te_long then LOW
    ~12000 (+/-1800). Each bit: HIGH long=1 / short=0 then LOW separator. 80 bits MSB-first
    into 10 bytes. Gate: data[9]==crc8(data,9). rolling=d[0..3] BE, serial=d[4..6],
    cnt=d[7], btn=d[8]. data = rolling<<32 | serial<<8 | cnt."""
    NAME = "SantaFe 13-16"
    CONST = Const(125, 375, 100)
    SYNC = 12000
    SYNC_DELTA = 1800
    BITS = 80
    NBYTES = 10

    def __init__(self):
        super().__init__()
        self.data = bytearray(self.NBYTES)
        self.bit_count = 0

    def feed(self, level, duration):
        c = self.CONST
        ts, tl, td = c.te_short, c.te_long, c.te_delta
        d = self.d
        st = d.parser_step
        if st == 0:
            if level and diff(duration, tl) < td:
                d.parser_step = 1
        elif st == 1:
            if (not level) and diff(duration, self.SYNC) < self.SYNC_DELTA:
                d.parser_step = 2
                self.data = bytearray(self.NBYTES)
                self.bit_count = 0
            else:
                d.parser_step = 0
        elif st == 2:
            if level:
                if diff(duration, tl) < td:
                    self._addbit(1)
                    d.parser_step = 3
                elif diff(duration, ts) < td:
                    self._addbit(0)
                    d.parser_step = 3
                elif duration > 3000:
                    if self.bit_count >= self.BITS:
                        self._process()
                    d.parser_step = 0
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0
        elif st == 3:
            if not level:
                if diff(duration, ts) < td or diff(duration, tl) < td:
                    if self.bit_count >= self.BITS:
                        self._process()
                        d.parser_step = 0
                    else:
                        d.parser_step = 2
                elif duration > 3000:
                    if self.bit_count >= self.BITS:
                        self._process()
                    d.parser_step = 0
                else:
                    d.parser_step = 0
            else:
                d.parser_step = 0

    def _addbit(self, bit):
        if self.bit_count < self.BITS:
            if bit:
                self.data[self.bit_count // 8] |= (1 << (7 - (self.bit_count % 8)))
            self.bit_count += 1

    def _process(self):
        if self.bit_count < self.BITS:
            return
        b = self.data
        if b[9] != _sf_crc8(b, 9):
            return
        rolling = (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]
        serial = (b[4] << 16) | (b[5] << 8) | b[6]
        counter = b[7]
        button = b[8]
        data = ((rolling << 32) | (serial << 8) | counter) & MASK64
        self.emit(dict(serial=serial, btn=button, cnt=counter, bits=self.BITS, data=data))


# ============================================================ mazda_infinity
def _mazda_parity(v):
    v ^= v >> 4
    v ^= v >> 2
    v ^= v >> 1
    return v & 1


def _mazda_checksum(b):
    return sum(b[0:7]) & 0xFF


def _mazda_interleave(b):
    b5, b6 = b[5], b[6]
    b[5] = (b5 & 0xAA) | (b6 & 0x55)
    b[6] = (b5 & 0x55) | (b6 & 0xAA)


def _mazda_whiten(b):
    p = _mazda_parity(b[7])
    if p == 1:
        for k in range(6):
            b[k] ^= b[6]
    else:
        for k in (0, 1, 2, 3, 4, 6):
            b[k] ^= b[5]


def _mazda_wire_to_logical(wire):
    b = [(255 - wire[i]) & 0xFF for i in range(8)]
    _mazda_whiten(b)      # inverse of step 3
    _mazda_interleave(b)  # inverse of step 2
    return b


def _mazda_valid(logical):
    uid = (logical[0] << 24) | (logical[1] << 16) | (logical[2] << 8) | logical[3]
    return uid != 0 and uid != 0xFFFFFFFF


class MazdaInfinity(ProtoDecoder):
    """firmware mazda_infinity.c. Manchester half-cells, te=250/500, delta=62. Each pulse ->
    1 (short) or 2 (long) half-cells of its level. A sliding 12-byte (384-cell) window scans
    byte-aligned offsets for FF FF D7 sync + 8 wire bytes + 0x5A trailer, with cell pairs
    HIGH,LOW->1 / LOW,HIGH->0. wire -> un-invert(255-x) -> un-whiten -> de-interleave = logical.
    serial=b0..3 BE, btn=b4, cnt=(b5<<8)|b6; emits first occurrence (repeat-suppressed)."""
    NAME = "Mazda Infinity"
    CONST = Const(250, 500, 62)
    WINDOW_BYTES = 12
    PAY_BYTES = 8
    BITS = 64

    def __init__(self):
        super().__init__()
        self.cells = []
        self.last_logical = None

    def _is_short(self, dur):
        return diff(dur, self.CONST.te_short) < self.CONST.te_delta

    def _is_long(self, dur):
        return diff(dur, self.CONST.te_short * 2) < self.CONST.te_delta

    def feed(self, level, duration):
        d = self.d
        lv = 1 if level else 0
        if d.parser_step == 0:
            if self._is_short(duration):
                self.cells = []
                d.parser_step = 1
                self._push(lv)
            elif self._is_long(duration):
                self.cells = []
                d.parser_step = 1
                self._push(lv)
                self._push(lv)
        else:
            if self._is_short(duration):
                self._push(lv)
            elif self._is_long(duration):
                self._push(lv)
                self._push(lv)
            else:
                self._try()
                self.cells = []
                d.parser_step = 0

    def _push(self, lv):
        cap = self.WINDOW_BYTES * 8 * 2
        if len(self.cells) < cap:
            self.cells.append(lv)
        else:
            self.cells.pop(0)
            self.cells.append(lv)
        self._try()

    def _cells_to_bytes(self, off, nbytes):
        out = [0] * nbytes
        for bit in range(nbytes * 8):
            first = self.cells[off + bit * 2]
            second = self.cells[off + bit * 2 + 1]
            if first == second:
                return None
            if first != 0 and second == 0:
                out[bit // 8] |= (1 << (7 - (bit & 7)))
        return out

    def _try(self):
        need = self.WINDOW_BYTES * 8 * 2
        if len(self.cells) < need:
            return
        max_off = len(self.cells) - need
        off = 0
        while off <= max_off:
            win = self._cells_to_bytes(off, self.WINDOW_BYTES)
            if win is None:
                off += 2
                continue
            if win[0] != 0xFF or win[1] != 0xFF or win[2] != 0xD7:
                off += 2
                continue
            if win[self.WINDOW_BYTES - 1] != 0x5A:
                off += 2
                continue
            wire = win[3:11]
            logical = _mazda_wire_to_logical(wire)
            if not _mazda_valid(logical):
                off += 2
                continue
            if self.last_logical is not None and self.last_logical == logical:
                return
            self.last_logical = list(logical)
            serial = (logical[0] << 24) | (logical[1] << 16) | (logical[2] << 8) | logical[3]
            btn = logical[4]
            cnt = (logical[5] << 8) | logical[6]
            data = 0
            for v in logical:
                data = (data << 8) | v
            self.emit(dict(serial=serial, btn=btn, cnt=cnt, bits=self.BITS, data=data))
            return


# ============================================================ hundai
class Hundai(ProtoDecoder):
    """firmware hundai.c (new Hyundai). Manchester half-cells, te=500/1000, delta=125.
    A LOW >=1500 (header/IFG) starts collection; pulses -> 1/2 half-cells. At the next gap,
    decode the TAIL: last 13 bytes (4x 0x55 anchor + 9 body). Cell pairs HIGH,LOW->1 /
    LOW,HIGH->0. uid=b0..3 BE (not 0/0xFFFFFFFF). serial=uid, btn=b5&0x0F, cnt=b6;
    emits first occurrence (repeat-suppressed)."""
    NAME = "Hundai"
    CONST = Const(500, 1000, 125)
    BOUNDARY = 1500
    ANCHOR = 4
    WIRE = 9
    TAIL = 13
    CELL_CAP = (32 + 9 + 4) * 8 * 2 + 8

    def __init__(self):
        super().__init__()
        self.cells = []
        self.last_reported = None

    def _is_short(self, dur):
        return diff(dur, self.CONST.te_short) < self.CONST.te_delta

    def _is_long(self, dur):
        return diff(dur, self.CONST.te_short * 2) < self.CONST.te_delta

    def feed(self, level, duration):
        d = self.d
        lv = 1 if level else 0
        if d.parser_step == 0:
            if (not level) and duration >= self.BOUNDARY:
                self.cells = []
                d.parser_step = 1
        else:
            if self._is_short(duration):
                self._push(lv)
            elif self._is_long(duration):
                self._push(lv)
                self._push(lv)
            else:
                self._try()
                self.cells = []
                if (not level) and duration >= self.BOUNDARY:
                    d.parser_step = 1
                else:
                    d.parser_step = 0

    def _push(self, lv):
        if len(self.cells) < self.CELL_CAP:
            self.cells.append(lv)
        else:
            self.cells.pop(0)
            self.cells.append(lv)

    def _cells_to_bytes(self, off, nbytes):
        out = [0] * nbytes
        for bit in range(nbytes * 8):
            first = self.cells[off + bit * 2]
            second = self.cells[off + bit * 2 + 1]
            if first == second:
                return None
            if first != 0 and second == 0:
                out[bit // 8] |= (1 << (7 - (bit & 7)))
        return out

    def _try(self):
        tail = self.TAIL * 8 * 2
        if len(self.cells) < tail:
            return
        off = len(self.cells) - tail
        win = self._cells_to_bytes(off, self.TAIL)
        if win is None:
            return
        for i in range(self.ANCHOR):
            if win[i] != 0x55:
                return
        frame = win[self.ANCHOR:self.ANCHOR + self.WIRE]
        uid = (frame[0] << 24) | (frame[1] << 16) | (frame[2] << 8) | frame[3]
        if uid == 0 or uid == 0xFFFFFFFF:
            return
        self._commit(frame)

    def _commit(self, frame):
        if self.last_reported is not None and self.last_reported == frame:
            return
        self.last_reported = list(frame)
        serial = (frame[0] << 24) | (frame[1] << 16) | (frame[2] << 8) | frame[3]
        btn = frame[5] & 0x0F
        cnt = frame[6]
        data = 0
        for i in range(1, 9):
            data = (data << 8) | frame[i]
        self.emit(dict(serial=serial, btn=btn, cnt=cnt, bits=72, data=data))


# ============================================================ audiovox
class Audiovox(ProtoDecoder):
    """firmware audiovox.c. OOK PWM, te=445(=1)/895(=0). Sync mark anchors the frame
    (1131<=HIGH<1600). 25 bits MSB-first; a reset gap (>=1300) or over-long mark ends the
    row. Checks on the 25-bit word: b2=(data>>1)&0xFF raw -> reject (b2&0xAA) or b2==0x55;
    invert all; id=(~b0<<8)|~b1 not 0/0xFFFF; >=1 button bit of ~b2 at 0x01/0x04/0x10/0x40;
    serial=id, btn = (~b2)&0x55."""
    NAME = "Audiovox PRO"
    CONST = Const(445, 895, 180)
    MARK_MIN = 150
    SHORT_LONG_MID = 670
    LONG_SYNC_MID = 1131
    SYNC_MAX = 1600
    GAP_RESET = 1300
    BITS = 25
    MAXB = 40

    def feed(self, level, duration):
        d = self.d
        if d.parser_step == 0:
            if level and duration >= self.LONG_SYNC_MID and duration < self.SYNC_MAX:
                d.parser_step = 1
                d.decode_data = 0
                d.decode_count_bit = 0
        else:
            if level:
                if duration < self.MARK_MIN:
                    d.parser_step = 0
                elif duration < self.SHORT_LONG_MID:
                    d.add_bit(1)
                elif duration < self.LONG_SYNC_MID:
                    d.add_bit(0)
                elif duration < self.SYNC_MAX:
                    self._process()
                    d.decode_data = 0
                    d.decode_count_bit = 0
                else:
                    self._process()
                    d.parser_step = 0
                if d.decode_count_bit > self.MAXB:
                    d.parser_step = 0
            else:
                if duration >= self.GAP_RESET:
                    self._process()
                    d.parser_step = 0

    def _process(self):
        d = self.d
        if d.decode_count_bit != self.BITS:
            return
        data = d.decode_data
        b0 = (data >> 17) & 0xFF
        b1 = (data >> 9) & 0xFF
        b2 = (data >> 1) & 0xFF
        if (b2 & 0xAA) or b2 == 0x55:
            return
        i0 = (~b0) & 0xFF
        i1 = (~b1) & 0xFF
        i2 = (~b2) & 0xFF
        idv = ((i0 << 8) | i1) & 0xFFFF
        if idv == 0 or idv == 0xFFFF:
            return
        matches = 0
        mask = 0x01
        for _ in range(4):
            if i2 & mask:
                matches += 1
            mask <<= 2
        if not matches:
            return
        btn = i2 & 0x55
        self.emit(dict(serial=idv, btn=btn, cnt=0, bits=self.BITS, data=data))


# ============================================================ astrostart
class Astrostart(ProtoDecoder):
    """firmware astrostart.c. OOK PPM, the GAP after each mark carries the bit
    (short 326=0 / long 526=1, mid 426). Marks 120..900; gap <150 noise, >=750 finalizes.
    52 bits MSB-first. bytes[0]==~bytes[1]; nibble-sum checksum (masked 4 bits per step) over
    the 4 id bytes == data&0x0F. serial=bytes[2..5] BE, btn=bytes[0], cnt=0."""
    NAME = "AstroStart 2000"
    CONST = Const(326, 526, 120)
    GAP_MID = 426
    GAP_MIN = 150
    GAP_MAX = 750
    PULSE_MIN = 120
    PULSE_MAX = 900
    BITS = 52

    def feed(self, level, duration):
        d = self.d
        if d.parser_step == 0:
            if level and self.PULSE_MIN <= duration <= self.PULSE_MAX:
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 1
        else:  # CheckGap
            if level:
                if duration < self.PULSE_MIN or duration > self.PULSE_MAX:
                    self._finalize()
                    d.parser_step = 0
                    d.decode_data = 0
                    d.decode_count_bit = 0
            else:
                if duration < self.GAP_MIN:
                    d.parser_step = 0
                    d.decode_data = 0
                    d.decode_count_bit = 0
                elif duration >= self.GAP_MAX:
                    self._finalize()
                    d.parser_step = 0
                    d.decode_data = 0
                    d.decode_count_bit = 0
                else:
                    bit = 0 if duration < self.GAP_MID else 1
                    if d.decode_count_bit >= 64:
                        d.parser_step = 0
                        d.decode_data = 0
                        d.decode_count_bit = 0
                    else:
                        d.add_bit(bit)

    def _finalize(self):
        d = self.d
        if d.decode_count_bit != self.BITS:
            return
        data = d.decode_data
        by = [(data >> 44) & 0xFF, (data >> 36) & 0xFF, (data >> 28) & 0xFF,
              (data >> 20) & 0xFF, (data >> 12) & 0xFF, (data >> 4) & 0xFF]
        rxck = data & 0x0F
        if by[0] != ((~by[1]) & 0xFF):
            return
        exp = 0
        for i in range(2, 6):
            exp = (exp + (by[i] >> 4)) & 0x0F
            exp = (exp + by[i]) & 0x0F
        if exp != rxck:
            return
        serial = (by[2] << 24) | (by[3] << 16) | (by[4] << 8) | by[5]
        btn = by[0]
        self.emit(dict(serial=serial, btn=btn, cnt=0, bits=self.BITS, data=data))


# ============================================================ compustar
class Compustar(ProtoDecoder):
    """firmware compustar.c. OOK PWM, the mark width is the bit (short 708=1 / long 1076=0,
    mid 892). Marks 400..1330; a gap >=1400 flushes the frame. 36 bits MSB-first (a 35-bit
    capture is normalised to 36 by appending a 0). id=(data>>20)&0xFFFF not 0/0xFFFF;
    (data>>17)&7==7; MIC (~binv)&0xFF==button; btn=(data>>1)&0xFF, cnt=0."""
    NAME = "Compustar"
    CONST = Const(708, 1076, 250)
    PULSE_MIN = 400
    PULSE_MAX = 1330
    GAP_END = 1400
    MID = 892
    BITS = 36
    MAXB = 40

    def feed(self, level, duration):
        d = self.d
        if d.parser_step == 0:
            if level and self.PULSE_MIN <= duration <= self.PULSE_MAX:
                d.decode_data = 0
                d.decode_count_bit = 0
                d.add_bit(1 if duration < self.MID else 0)
                d.parser_step = 1
        else:
            if level:
                if duration < self.PULSE_MIN or duration > self.PULSE_MAX:
                    d.parser_step = 0
                    d.decode_data = 0
                    d.decode_count_bit = 0
                elif d.decode_count_bit >= self.MAXB:
                    d.parser_step = 0
                    d.decode_data = 0
                    d.decode_count_bit = 0
                else:
                    d.add_bit(1 if duration < self.MID else 0)
            else:
                if duration >= self.GAP_END:
                    self._process()
                    d.parser_step = 0
                    d.decode_data = 0
                    d.decode_count_bit = 0

    def _process(self):
        d = self.d
        count = d.decode_count_bit
        data = d.decode_data
        if count == 35:
            data = (data << 1) & MASK64
            count = 36
        if count != self.BITS:
            return
        data &= 0xFFFFFFFFF
        idv = (data >> 20) & 0xFFFF
        if idv == 0 or idv == 0xFFFF:
            return
        if ((data >> 17) & 0x7) != 0x7:
            return
        binv = (data >> 9) & 0xFF
        btn = (data >> 1) & 0xFF
        if ((~binv) & 0xFF) != btn:
            return
        self.emit(dict(serial=idv, btn=btn, cnt=0, bits=self.BITS, data=data))


# ============================================================ continental_rke
class ContinentalRke(ProtoDecoder):
    """firmware continental_rke.c. FSK Manchester-ZEROBIT, rtl_433 accumulator slicer:
    te=100/200, delta=40, reset=1500, threshold=150. Each pulse accumulates; when accum+dur
    crosses 150 a data edge emits bit = level (falling/mark=1, rising/space=0), accum=0.
    Rolling 20-bit preamble hunt (0xF0F06), then 112 payload bits MSB-first into 14 bytes.
    MIC: XOR(all 14 bytes)==0. id=b0..3 BE, btn=b5>>4, cnt=b6..8 BE, seed=b9..12 BE."""
    NAME = "Continental RKE"
    CONST = Const(100, 200, 40)
    RESET = 1500
    THRESH = 150
    TMAX = 240       # te_long + te_delta
    TMIN = 60        # te_short - te_delta
    PRE = 0xF0F06
    PMASK = 0xFFFFF
    PBYTES = 14
    PBITS = 112

    def __init__(self):
        super().__init__()
        self._restart()

    def _restart(self):
        self.te_accum = 0
        self.preamble_shift = 0
        self.collecting = False
        self.frame_bit = 0
        self.frame = bytearray(self.PBYTES)

    def feed(self, level, duration):
        if (not level) and duration >= self.RESET:
            self._restart()
            return
        if duration > self.TMAX or duration < self.TMIN:
            self._restart()
            return
        if self.te_accum + duration > self.THRESH:
            self._add_bit(1 if level else 0)
            self.te_accum = 0
        else:
            self.te_accum += duration

    def _add_bit(self, bit):
        bit &= 1
        if not self.collecting:
            self.preamble_shift = ((self.preamble_shift << 1) | bit) & self.PMASK
            if self.preamble_shift == self.PRE:
                self.collecting = True
                self.frame_bit = 0
                self.frame = bytearray(self.PBYTES)
            return
        idx = self.frame_bit
        if bit:
            self.frame[idx >> 3] |= (0x80 >> (idx & 7))
        self.frame_bit += 1
        if self.frame_bit >= self.PBITS:
            self._process()
            self.collecting = False
            self.preamble_shift = 0
            self.frame_bit = 0

    def _process(self):
        x = 0
        for v in self.frame:
            x ^= v
        if x != 0:
            return
        b = self.frame
        idv = (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]
        btn = b[5] >> 4
        seq = (b[6] << 16) | (b[7] << 8) | b[8]
        enc = (b[9] << 24) | (b[10] << 16) | (b[11] << 8) | b[12]
        data = 0
        for i in range(8):
            data = (data << 8) | b[i]
        self.emit(dict(serial=idv, btn=btn, cnt=seq, bits=64, data=data, seed=enc))


# ============================================================ tesla_charge
class TeslaCharge(ProtoDecoder):
    """firmware tesla_charge.c. Fixed (non-rolling) code. Manchester via the toolbox state
    machine with a NON-inverting event map (level HIGH -> *High event, unlike ppa), te=400/800,
    delta=140; a gap >=1000 ends the burst. Emits only when exactly 50 bits were collected and
    decode_data == 0x00036B5564C725E6 (serial reported as the full fixed value)."""
    NAME = "Tesla Charge"
    CONST = Const(400, 800, 140)
    GAP_MIN = 1000
    FIX = 0x00036B5564C725E6
    BITS = 50

    def __init__(self):
        super().__init__()
        self.ms = Manchester()

    def _event(self, level, duration):
        c = self.CONST
        if duration >= self.GAP_MIN:
            return M_RESET
        if diff(duration, c.te_short) < c.te_delta:
            return M_SHORT_HIGH if level else M_SHORT_LOW
        if diff(duration, c.te_long) < c.te_delta:
            return M_LONG_HIGH if level else M_LONG_LOW
        return M_RESET

    def feed(self, level, duration):
        d = self.d
        event = self._event(level, duration)
        if event == M_RESET:
            if d.decode_count_bit == self.BITS and d.decode_data == self.FIX:
                self.emit(dict(serial=self.FIX, btn=0, cnt=0, bits=self.BITS, data=self.FIX))
            d.decode_data = 0
            d.decode_count_bit = 0
            self.ms.reset()
            return
        bit = self.ms.advance(event)
        if bit is not None:
            d.decode_data = ((d.decode_data << 1) | (1 if bit else 0)) & MASK64
            d.decode_count_bit += 1
            if d.decode_count_bit > 64:
                d.decode_data = 0
                d.decode_count_bit = 0




# ====================== batch4a: Toyota + Fiat family ======================
#!/usr/bin/env python3
r"""
batch4a_decoders.py -- 1:1 Python ports of five FoxFW 2.0 Sub-GHz firmware
decoders, built on arke_core. Ported EXACTLY from the firmware feed() state
machines in lib/subghz/protocols/:

    toyota.c          -> NAME "Toyota"      (variants A Manchester / B NRZ / C PWM)
    fiat_marelli.c    -> NAME "MARELLI"     (Manchester, dynamic te, Hitag2 info)
    fiat_v1.c         -> NAME "Fiat V1"     (differential-cell + BCM-Hitag2 auth)
    fiat_v2.c         -> NAME "Fiat V2"     (differential-cell, FCA/legacy fields)
    fiat_v2_v3_v5.c   -> NAME "Fiat Multi"  (V3 / V5, table Manchester)

Fidelity notes:
  * Toyota keeps its own 128-bit register (bits_lo/bits_hi/bit_count) exactly as
    the C, with the same toyota_push_bit carry and toyota_extract MSB-first slice.
  * Fiat V1/V2 reproduce the firmware's two-half-cell differential scheme and the
    MSB-first 13/14-byte raw buffer, including the invert fallback window.
  * Fiat Multi ports fiat_manchester_decode byte-for-byte (table {0x01,0x91,0x9B,
    0xFB}); it intentionally does NOT reuse arke_core.Manchester because that one
    resets state on a no-progress transition whereas the firmware table leaves it.
  * The BCM-Hitag2 cipher + the 95-key dictionary are ported faithfully; they are
    informational (never gate the callback in firmware), so emit() carries a
    hitag2_* result but a frame still emits when no key matches.
"""

# =====================================================================
# BCM / Hitag2 cipher (fiat_v1.c) -- shared by Fiat V1, Fiat V2, Marelli
# =====================================================================

def _truth(table, index):
    return (table >> index) & 1


def _filter_index(a, b, c, d):
    return (a << 3) | (b << 2) | (c << 1) | d


def _byte_bit(byte, bit):
    return (byte >> bit) & 1


def _bcm_filter(state):
    group = 0
    group |= _truth(0x2c79, _filter_index(
        _byte_bit(state[0], 1), _byte_bit(state[0], 2),
        _byte_bit(state[0], 4), _byte_bit(state[0], 5)))
    group |= _truth(0x6671, _filter_index(
        _byte_bit(state[1], 0), _byte_bit(state[1], 1),
        _byte_bit(state[1], 3), _byte_bit(state[1], 7))) << 1
    group |= _truth(0x6671, _filter_index(
        _byte_bit(state[3], 5), _byte_bit(state[2], 0),
        _byte_bit(state[2], 2), _byte_bit(state[2], 6))) << 2
    group |= _truth(0x6671, _filter_index(
        _byte_bit(state[4], 6), _byte_bit(state[3], 0),
        _byte_bit(state[3], 2), _byte_bit(state[3], 3))) << 3
    group |= _truth(0x2c79, _filter_index(
        _byte_bit(state[5], 1), _byte_bit(state[5], 3),
        _byte_bit(state[5], 4), _byte_bit(state[4], 5))) << 4
    return _truth(0x7907287b, group)


def _parity8(value):
    value &= 0xFF
    value ^= value >> 4
    value ^= value >> 2
    value ^= value >> 1
    return value & 1


_BCM_MASKS = (0xb3, 0x80, 0x83, 0x22, 0x00, 0x73)


def _bcm_feedback(state):
    fb = 0
    for i in range(6):
        fb ^= _parity8(state[i] & _BCM_MASKS[i])
    return fb & 1


def _bcm_shift(state, input_bit):
    for i in range(5):
        state[i] = ((state[i] << 1) | (state[i + 1] >> 7)) & 0xFF
    state[5] = ((state[5] << 1) | (input_bit & 1)) & 0xFF


def _input_bit_u32_be(value, index):
    return (value >> (31 - index)) & 1


def _input_bit_bytes_be(bytes_, index):
    return (bytes_[index >> 3] >> (7 - (index & 7))) & 1


def fiat_bcm_generate_authenticator(uid, button, control, key, epoch):
    """fiat_v1_bcm_generate_authenticator() -- BCM/Hitag2 32-bit authenticator."""
    state = [
        (uid >> 24) & 0xFF, (uid >> 16) & 0xFF, (uid >> 8) & 0xFF, uid & 0xFF,
        key[4] & 0xFF, key[5] & 0xFF,
    ]
    iv = ((epoch & 0x3FFFF) << 14) | ((control & 0x3FF) << 4) | (button & 0xF)
    for i in range(32):
        inp = (_input_bit_u32_be(iv, i) ^ _input_bit_bytes_be(key, i)
               ^ _bcm_filter(state))
        _bcm_shift(state, inp)
    authenticator = 0
    for i in range(32):
        authenticator = ((authenticator << 1) | _bcm_filter(state)) & 0xFFFFFFFF
        _bcm_shift(state, _bcm_feedback(state))
    return authenticator


# fiat_v1_known_keys[95][6] -- transcribed verbatim from fiat_v1.c
FIAT_KNOWN_KEYS = [
    (0xB7, 0x92, 0x80, 0xAE, 0xCC, 0x37), (0xD4, 0x24, 0x28, 0xF7, 0xD9, 0x66),
    (0x4D, 0x34, 0x3F, 0xD4, 0xE7, 0xB6), (0x6D, 0x6B, 0xF2, 0x1D, 0x3A, 0x1A),
    (0xA3, 0xF3, 0xAC, 0xF7, 0xB9, 0x10), (0x4D, 0x49, 0x4B, 0x52, 0x4F, 0x4E),
    (0xCD, 0x49, 0x4B, 0x52, 0x4F, 0x4E), (0x33, 0xFA, 0x2F, 0xCD, 0xC3, 0x3B),
    (0x4D, 0x49, 0x4B, 0x52, 0x4F, 0x4D), (0x4D, 0x69, 0x6B, 0x72, 0x6F, 0x6E),
    (0x6D, 0x69, 0x6B, 0x72, 0x6F, 0x6E), (0x44, 0x45, 0x4C, 0x50, 0x48, 0x49),
    (0x44, 0x65, 0x6C, 0x70, 0x68, 0x69), (0x4D, 0x41, 0x52, 0x45, 0x4C, 0x4C),
    (0x4D, 0x61, 0x72, 0x65, 0x6C, 0x6C), (0x4D, 0x41, 0x52, 0x45, 0x4C, 0x49),
    (0x43, 0x4F, 0x4E, 0x54, 0x49, 0x4E), (0x53, 0x49, 0x45, 0x4D, 0x45, 0x4E),
    (0x42, 0x4F, 0x53, 0x43, 0x48, 0x21), (0x56, 0x44, 0x4F, 0x43, 0x41, 0x52),
    (0x46, 0x49, 0x41, 0x54, 0x30, 0x30), (0x46, 0x69, 0x61, 0x74, 0x56, 0x31),
    (0x41, 0x4C, 0x46, 0x41, 0x52, 0x4F), (0x4C, 0x41, 0x4E, 0x43, 0x49, 0x41),
    (0x00, 0x00, 0x00, 0x00, 0x00, 0x00), (0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF),
    (0x00, 0x00, 0x00, 0x00, 0x00, 0x01), (0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
    (0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA), (0x55, 0x55, 0x55, 0x55, 0x55, 0x55),
    (0xDE, 0xAD, 0xBE, 0xEF, 0xCA, 0xFE), (0xCA, 0xFE, 0xBA, 0xBE, 0xDE, 0xAD),
    (0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC), (0xBD, 0xF5, 0xE8, 0x46, 0xC3, 0x99),
    (0x9A, 0xC4, 0x99, 0x9C, 0x1E, 0x1C), (0x40, 0x00, 0x00, 0x00, 0x00, 0x00),
    (0x21, 0x8F, 0x24, 0x0F, 0x59, 0xC3), (0x53, 0x03, 0x64, 0x3F, 0xFB, 0xE3),
    (0x8A, 0x8B, 0x94, 0x00, 0x00, 0x00), (0xE7, 0x81, 0xA3, 0xD5, 0xBE, 0xF3),
    (0xAD, 0x2E, 0xCE, 0x5F, 0x6A, 0x1F), (0xCD, 0x27, 0x40, 0x89, 0x81, 0xF4),
    (0x37, 0xCC, 0xAE, 0x80, 0x92, 0xB7), (0xB7, 0x92, 0x80, 0xAE, 0xCC, 0x38),
    (0xB7, 0x92, 0x80, 0xAE, 0xCC, 0x36), (0xB8, 0x92, 0x80, 0xAE, 0xCC, 0x37),
    (0x66, 0xD9, 0xF7, 0x28, 0x24, 0xD4), (0xD4, 0x24, 0x28, 0xF7, 0xD9, 0x67),
    (0xD4, 0x24, 0x28, 0xF7, 0xD9, 0x65), (0xB6, 0xE7, 0xD4, 0x3F, 0x34, 0x4D),
    (0x4D, 0x34, 0x3F, 0xD4, 0xE7, 0xB7), (0x1A, 0x3A, 0x1D, 0xF2, 0x6B, 0x6D),
    (0x6D, 0x6B, 0xF2, 0x1D, 0x3A, 0x1B), (0x10, 0xB9, 0xF7, 0xAC, 0xF3, 0xA3),
    (0xA3, 0xF3, 0xAC, 0xF7, 0xB9, 0x11), (0x3B, 0xC3, 0xCD, 0x2F, 0xFA, 0x33),
    (0x33, 0xFA, 0x2F, 0xCD, 0xC3, 0x3A), (0x00, 0x00, 0x00, 0x00, 0x00, 0x02),
    (0x00, 0x00, 0x00, 0x00, 0x00, 0x03), (0x00, 0x00, 0x00, 0x00, 0x00, 0x04),
    (0x00, 0x00, 0x00, 0x00, 0x00, 0x08), (0x00, 0x00, 0x00, 0x00, 0x00, 0x10),
    (0x00, 0x00, 0x00, 0x00, 0x01, 0x00), (0x00, 0x00, 0x00, 0x01, 0x00, 0x00),
    (0x00, 0x00, 0x01, 0x00, 0x00, 0x00), (0x00, 0x01, 0x00, 0x00, 0x00, 0x00),
    (0x01, 0x00, 0x00, 0x00, 0x00, 0x00), (0x00, 0x00, 0x00, 0x00, 0x00, 0x99),
    (0x00, 0x00, 0x00, 0x00, 0x99, 0x99), (0x00, 0x00, 0x00, 0x99, 0x99, 0x99),
    (0x99, 0x99, 0x99, 0x99, 0x99, 0x99), (0x00, 0x00, 0x00, 0x00, 0x00, 0x11),
    (0x11, 0x11, 0x11, 0x11, 0x11, 0x11), (0x00, 0x00, 0x00, 0x00, 0x00, 0x22),
    (0x22, 0x22, 0x22, 0x22, 0x22, 0x22), (0x00, 0x00, 0x00, 0x00, 0x00, 0x33),
    (0x33, 0x33, 0x33, 0x33, 0x33, 0x33), (0x00, 0x00, 0x00, 0x00, 0x00, 0x44),
    (0x44, 0x44, 0x44, 0x44, 0x44, 0x44), (0x00, 0x00, 0x00, 0x00, 0x00, 0x77),
    (0x77, 0x77, 0x77, 0x77, 0x77, 0x77), (0x00, 0x00, 0x00, 0x00, 0x00, 0x88),
    (0x88, 0x88, 0x88, 0x88, 0x88, 0x88), (0x00, 0x00, 0x00, 0x00, 0x00, 0xEE),
    (0xEE, 0xEE, 0xEE, 0xEE, 0xEE, 0xEE), (0x00, 0x01, 0x02, 0x03, 0x04, 0x05),
    (0x10, 0x20, 0x30, 0x40, 0x50, 0x60), (0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45),
    (0x12, 0x34, 0x56, 0x78, 0x90, 0xAB), (0x8D, 0xCA, 0x22, 0x11, 0x00, 0xFE),
    (0x7B, 0x3F, 0x91, 0x2E, 0xC5, 0x08), (0xF1, 0xE2, 0xD3, 0xC4, 0xB5, 0xA6),
    (0x1F, 0x2E, 0x3D, 0x4C, 0x5B, 0x6A), (0xE3, 0xD1, 0xB4, 0x7C, 0x8F, 0x25),
    (0x9C, 0x4A, 0x71, 0xE8, 0x03, 0x56),
]
assert len(FIAT_KNOWN_KEYS) == 95


# =====================================================================
# 1. Toyota (toyota.c) -- NAME "Toyota"
# =====================================================================
# Variant detect from the FIRST high pulse (reset step):
#   short of const_b and <310us  -> variant B (Tundra, NRZ 200/390)
#   short of const_a and >=310us -> variant C (Prius/Corolla, PWM-on-high)
# Variant A (Corolla Manchester 400/800) is unreachable from reset in firmware
# (no code path selects variant 0 on the first pulse) but its state machine is
# ported for completeness. Frame = 32-bit hop | 28-bit serial | 4-bit button,
# MSB-first in a 128-bit register; generic.data packs hop<<32 | serial<<4 | btn.
_TOY_A = Const(400, 800, 100)   # min_count 68
_TOY_B = Const(200, 390, 60)    # min_count 67

_TOY_A_MIN = 68
_TOY_B_MIN = 67
_TOY_A_BITS = 68
_TOY_B_BITS = 67
_TOY_VARIANT_THRESH = 310
_TOY_A_KIA_SYNC_MIN, _TOY_A_KIA_SYNC_MAX = 901, 1600
_TOY_B_SYNC_GAP_MIN, _TOY_B_SYNC_GAP_MAX = 1700, 2200
_TOY_B_PREAMBLE_MIN = 10
_TOY_A_PREAMBLE_MIN = 10
_TOY_B_NRZ_MIDPOINT = 287
_TOY_B_DATA_PULSE_MIN, _TOY_B_DATA_PULSE_MAX = 100, 500
_TOY_C_BITS = 66
_TOY_C_FRAME_BITS = 68
_TOY_C_PREAMBLE_MIN = 6
_TOY_C_SYNC_MIN, _TOY_C_SYNC_MAX = 1050, 1500
_TOY_C_HIGH_MIDPOINT = 600
_TOY_C_PRE_HIGH_MIN, _TOY_C_PRE_HIGH_MAX = 200, 550
_TOY_C_PRE_LOW_MIN, _TOY_C_PRE_LOW_MAX = 200, 650
_TOY_C_DATA_HIGH_MIN, _TOY_C_DATA_HIGH_MAX = 200, 1050
_TOY_C_END_LOW = 1000

# step enum (ToyotaDecoderStep)
_TS_RESET, _TS_PREA, _TS_DATAA, _TS_PREB, _TS_DATAB, _TS_PREC, _TS_DATAC = range(7)


def _tdiff(a, b):
    return a - b if a > b else b - a


def _t_short(d, c):
    return _tdiff(d, c.te_short) < c.te_delta


def _t_long(d, c):
    return _tdiff(d, c.te_long) < c.te_delta


class Toyota(ProtoDecoder):
    NAME = "Toyota"
    CONST = _TOY_A

    def __init__(self):
        super().__init__()
        self.variant = 0
        self._treset()

    def _treset(self):
        """subghz_protocol_decoder_toyota_reset() -- note: does NOT clear variant."""
        self.d.parser_step = _TS_RESET
        self.d.te_last = 0
        self.bits_lo = 0
        self.bits_hi = 0
        self.bit_count = 0
        self.te_last = 0
        self.have_high = False
        self.preamble_count = 0
        self.hop = 0
        self.serial = 0
        self.button = 0

    def reset(self):
        super().reset()
        self.variant = 0
        self._treset()

    def _push_bit(self, bit):
        carry = (self.bits_lo >> 63) & 1
        self.bits_hi = ((self.bits_hi << 1) | carry) & 0xFF
        self.bits_lo = ((self.bits_lo << 1) | (bit & 1)) & MASK64
        self.bit_count += 1

    def _extract(self, offset, length):
        result = 0
        total = self.bit_count
        for i in range(length):
            pos = (total - 1) - (offset + i)
            b = 0
            if pos >= 64:
                b = (self.bits_hi >> (pos - 64)) & 1
            elif pos >= 0:
                b = (self.bits_lo >> pos) & 1
            result = (result << 1) | b
        return result

    @staticmethod
    def _plausible(serial, button, hop, variant):
        if serial == 0 or serial == 0x0FFFFFFF:
            return False
        if hop == 0 or hop == 0xFFFFFFFF:
            return False
        b = button & 0x0F
        if variant == 2:
            return b in (0x0B, 0x0E, 0x0D, 0x07)
        elif variant == 1:
            return b in (0x0A, 0x05, 0x0F, 0x04)
        else:
            return b in (0x08, 0x01, 0x09, 0x02, 0x04)

    def _fire(self, min_bits):
        if self.bit_count < min_bits:
            return
        self.hop = self._extract(0, 32)
        self.serial = self._extract(32, 28)
        self.button = self._extract(60, 4) & 0x0F
        if not self._plausible(self.serial, self.button, self.hop, self.variant):
            return
        data = ((self.hop << 32) | (self.serial << 4) | (self.button & 0x0F)) & MASK64
        self.d.decode_data = data
        self.d.decode_count_bit = self.bit_count
        self.emit(dict(serial=self.serial, btn=self.button, cnt=self.variant,
                       bits=self.bit_count, data=data))

    def _fire_c(self):
        if self.bit_count < _TOY_C_BITS:
            return
        self.hop = self._extract(0, 32)
        self.serial = self._extract(32, 28)
        self.button = self._extract(60, 4) & 0x0F
        if not self._plausible(self.serial, self.button, self.hop, 2):
            return
        self.variant = 2
        data = ((self.hop << 32) | (self.serial << 4) | (self.button & 0x0F)) & MASK64
        self.d.decode_data = data
        self.d.decode_count_bit = self.bit_count
        self.emit(dict(serial=self.serial, btn=self.button, cnt=2,
                       bits=self.bit_count, data=data))

    # --- variant A: Manchester 400/800 ---
    def _feed_a(self, level, duration):
        c = _TOY_A
        d = self.d
        if d.parser_step == _TS_PREA:
            if level:
                self.te_last = duration
                self.have_high = True
                return
            if not self.have_high:
                self._treset(); return
            self.have_high = False
            if _TOY_A_KIA_SYNC_MIN <= duration <= _TOY_A_KIA_SYNC_MAX:
                self._treset(); return
            hs = _t_short(self.te_last, c); hl = _t_long(self.te_last, c)
            ls = _t_short(duration, c); ll = _t_long(duration, c)
            if hs and ls:
                self.preamble_count += 1
                return
            if self.preamble_count < _TOY_A_PREAMBLE_MIN:
                self._treset(); return
            if not ((hl and ls) or (hs and ll)):
                self._treset(); return
            self.bits_lo = 0; self.bits_hi = 0; self.bit_count = 0
            if hl and ls:
                self._push_bit(0)
            elif hs and ll:
                self._push_bit(1)
            d.parser_step = _TS_DATAA
            return
        if d.parser_step == _TS_DATAA:
            if level:
                if _t_short(duration, c) or _t_long(duration, c):
                    self.te_last = duration; self.have_high = True
                else:
                    if self.bit_count >= _TOY_A_MIN:
                        self._fire(_TOY_A_MIN)
                    self._treset()
                return
            if not self.have_high:
                return
            self.have_high = False
            hs = _t_short(self.te_last, c); hl = _t_long(self.te_last, c)
            ls = _t_short(duration, c); ll = _t_long(duration, c)
            if hl and ls:
                self._push_bit(0)
            elif hs and ll:
                self._push_bit(1)
            else:
                if self.bit_count >= _TOY_A_MIN:
                    self._fire(_TOY_A_MIN)
                self._treset(); return
            if self.bit_count >= _TOY_A_BITS:
                self._fire(_TOY_A_MIN)
                self._treset()

    # --- variant B: NRZ 200/390 ---
    def _feed_b(self, level, duration):
        c = _TOY_B
        d = self.d
        if d.parser_step == _TS_PREB:
            if level:
                if _t_short(duration, c):
                    self.te_last = duration; self.have_high = True
                else:
                    self._treset()
                return
            if not self.have_high:
                self._treset(); return
            self.have_high = False
            if _TOY_B_SYNC_GAP_MIN <= duration <= _TOY_B_SYNC_GAP_MAX:
                if self.preamble_count >= _TOY_B_PREAMBLE_MIN:
                    self.bits_lo = 0; self.bits_hi = 0; self.bit_count = 0
                    self.have_high = False
                    d.parser_step = _TS_DATAB
                else:
                    self._treset()
                return
            if _t_long(duration, c):
                self.preamble_count += 1
                return
            self._treset(); return
        if d.parser_step == _TS_DATAB:
            if duration >= _TOY_B_SYNC_GAP_MIN:
                if self.bit_count >= _TOY_B_MIN:
                    self._fire(_TOY_B_MIN)
                self._treset(); return
            if duration < _TOY_B_DATA_PULSE_MIN or duration > _TOY_B_DATA_PULSE_MAX:
                self._treset(); return
            bit = 1 if duration > _TOY_B_NRZ_MIDPOINT else 0
            self._push_bit(bit)
            if self.bit_count >= _TOY_B_BITS:
                self._fire(_TOY_B_MIN)
                self._treset()

    # --- variant C: PWM on the high pulse ---
    def _start_data_c(self):
        self.bits_lo = 0; self.bits_hi = 0; self.bit_count = 0
        self.have_high = False
        self.d.parser_step = _TS_DATAC

    def _feed_c(self, level, duration):
        d = self.d
        if d.parser_step == _TS_PREC:
            if level:
                self.te_last = duration; self.have_high = True
                return
            if not self.have_high:
                self._treset(); return
            self.have_high = False
            if _TOY_C_SYNC_MIN <= duration <= _TOY_C_SYNC_MAX:
                if self.preamble_count >= _TOY_C_PREAMBLE_MIN:
                    self._start_data_c()
                else:
                    self._treset()
                return
            if (_TOY_C_PRE_HIGH_MIN <= self.te_last <= _TOY_C_PRE_HIGH_MAX and
                    _TOY_C_PRE_LOW_MIN <= duration <= _TOY_C_PRE_LOW_MAX):
                self.preamble_count += 1
                return
            self._treset(); return
        if d.parser_step == _TS_DATAC:
            if level:
                if _TOY_C_DATA_HIGH_MIN <= duration <= _TOY_C_DATA_HIGH_MAX:
                    if self.bit_count < _TOY_C_FRAME_BITS:
                        self._push_bit(1 if duration > _TOY_C_HIGH_MIDPOINT else 0)
                    self.have_high = True
                    self.te_last = duration
                else:
                    if self.bit_count >= _TOY_C_BITS:
                        self._fire_c()
                    self._treset()
                    self.variant = 2
                    d.parser_step = _TS_PREC
                    self.te_last = duration
                    self.have_high = True
                return
            self.have_high = False
            if _TOY_C_SYNC_MIN <= duration <= _TOY_C_SYNC_MAX:
                if self.bit_count >= _TOY_C_BITS:
                    self._fire_c()
                self._start_data_c()
                return
            if duration > _TOY_C_END_LOW:
                if self.bit_count >= _TOY_C_BITS:
                    self._fire_c()
                self._treset()
                self.variant = 2
                d.parser_step = _TS_PREC
                self.preamble_count = 0

    def feed(self, level, duration):
        d = self.d
        if d.parser_step == _TS_RESET:
            if not level:
                return
            fits_b = _t_short(duration, _TOY_B) and duration < _TOY_VARIANT_THRESH
            fits_a = _t_short(duration, _TOY_A) and duration >= _TOY_VARIANT_THRESH
            if fits_b:
                self.variant = 1
                self.te_last = duration
                self.have_high = True
                self.preamble_count = 0
                d.parser_step = _TS_PREB
            elif fits_a:
                self.variant = 2
                self.te_last = duration
                self.have_high = True
                self.preamble_count = 0
                d.parser_step = _TS_PREC
            return
        if self.variant == 1:
            self._feed_b(level, duration)
        elif self.variant == 2:
            self._feed_c(level, duration)
        else:
            self._feed_a(level, duration)


# =====================================================================
# 2. MARELLI (fiat_marelli.c) -- NAME "MARELLI"
# =====================================================================
# Manchester with a dynamically-measured te (averaged over the 0xFF preamble, or
# sync/8 on the retransmit path). Frame 80..104 bits into a 13-byte MSB-first raw
# buffer; a full 104-bit frame carries a CRC-8 (init 0x03, poly 0x01) in raw[12].
# Accept gate: raw[0]==0xFF and (raw[1]&1)==0. serial=raw[2..5], btn=raw[6]>>4,
# cnt=(raw[7]>>3)&0x1F.  (Shares the Fiat V1 Hitag2 cipher for info only.)
_MAR_PRE_PULSE_MIN, _MAR_PRE_PULSE_MAX = 50, 350
_MAR_PRE_MIN = 80
_MAR_MAX_BITS = 104
_MAR_MIN_BITS = 80
_MAR_GAP_MULT = 4
_MAR_SYNC_MIN_MULT, _MAR_SYNC_MAX_MULT = 4, 12
_MAR_RETX_GAP_MIN = 5000
_MAR_RETX_SYNC_MIN, _MAR_RETX_SYNC_MAX = 400, 2800

_MS_RESET, _MS_PRE, _MS_SYNC, _MS_DATA, _MS_RETX = range(5)

# arke_core Manchester event codes
_M_SHORT_LOW, _M_SHORT_HIGH, _M_LONG_LOW, _M_LONG_HIGH, _M_RESET = 0, 2, 4, 6, 8


class Marelli(ProtoDecoder):
    NAME = "MARELLI"
    CONST = Const(260, 520, 80)

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self._mreset()

    def _mreset(self):
        self.state = _MS_RESET
        self.preamble_count = 0
        self.bit_count = 0
        self.extra_data = 0
        self.te_last = 0
        self.te_sum = 0
        self.te_count = 0
        self.te_detected = 0
        self.gdata = 0
        self.raw = [0] * 13
        self.man.reset()

    def reset(self):
        super().reset()
        self._mreset()

    def _prepare_data(self):
        self.bit_count = 0
        self.extra_data = 0
        self.gdata = 0
        self.raw = [0] * 13
        self.man.reset()
        self.state = _MS_DATA

    def _man_event(self, level, duration):
        te_short = self.te_detected if self.te_detected else self.CONST.te_short
        te_long = te_short * 2
        te_delta = te_short // 2
        if te_delta < 30:
            te_delta = 30
        if _tdiff(duration, te_short) < te_delta:
            return _M_SHORT_LOW if level else _M_SHORT_HIGH
        if _tdiff(duration, te_long) < te_delta:
            return _M_LONG_LOW if level else _M_LONG_HIGH
        return _M_RESET

    def _frame_complete(self):
        self.d.decode_count_bit = self.bit_count
        crc_ok = True
        if self.bit_count >= 104:
            calc = crc8(self.raw[:12], 0x01, 0x03)
            crc_ok = (calc == self.raw[12])
        preamble_ok = (self.raw[0] == 0xFF) and ((self.raw[1] & 0x01) == 0)
        if crc_ok and preamble_ok:
            serial = ((self.raw[2] << 24) | (self.raw[3] << 16) |
                      (self.raw[4] << 8) | self.raw[5])
            btn = (self.raw[6] >> 4) & 0xF
            cnt = (self.raw[7] >> 3) & 0x1F
            self.d.decode_data = self.gdata
            self.emit(dict(serial=serial, btn=btn, cnt=cnt,
                           bits=self.bit_count, data=self.gdata))
        self.state = _MS_RESET

    def feed(self, level, duration):
        st = self.state
        if st == _MS_RESET:
            if level:
                if _MAR_PRE_PULSE_MIN <= duration <= _MAR_PRE_PULSE_MAX:
                    self.state = _MS_PRE
                    self.preamble_count = 1
                    self.te_sum = duration
                    self.te_count = 1
                    self.te_last = duration
            else:
                if duration > _MAR_RETX_GAP_MIN:
                    self.state = _MS_RETX
                    self.te_last = duration
            return

        if st == _MS_PRE:
            if _MAR_PRE_PULSE_MIN <= duration <= _MAR_PRE_PULSE_MAX:
                self.preamble_count += 1
                self.te_sum += duration
                self.te_count += 1
                self.te_last = duration
            elif not level:
                if self.preamble_count >= _MAR_PRE_MIN and self.te_count > 0:
                    self.te_detected = self.te_sum // self.te_count
                    gap_threshold = self.te_detected * _MAR_GAP_MULT
                    if duration > gap_threshold:
                        self.state = _MS_SYNC
                        self.te_last = duration
                    else:
                        self.state = _MS_RESET
                else:
                    self.state = _MS_RESET
            else:
                self.state = _MS_RESET
            return

        if st == _MS_SYNC:
            sync_min = self.te_detected * _MAR_SYNC_MIN_MULT
            sync_max = self.te_detected * _MAR_SYNC_MAX_MULT
            if level and sync_min <= duration <= sync_max:
                self._prepare_data()
                self.te_last = duration
            else:
                self.state = _MS_RESET
            return

        if st == _MS_RETX:
            if level and _MAR_RETX_SYNC_MIN <= duration <= _MAR_RETX_SYNC_MAX:
                if not self.te_detected:
                    self.te_detected = duration // 8
                    if self.te_detected < 70:
                        self.te_detected = 100
                    if self.te_detected > 350:
                        self.te_detected = 260
                self._prepare_data()
                self.te_last = duration
            else:
                self.state = _MS_RESET
            return

        if st == _MS_DATA:
            event = self._man_event(level, duration)
            frame_complete = False
            if event != _M_RESET:
                r = self.man.advance(event)
                if r is not None:
                    new_bit = 1 if r else 0
                    if self.bit_count < _MAR_MAX_BITS:
                        byte_idx = self.bit_count // 8
                        bit_pos = 7 - (self.bit_count % 8)
                        if new_bit:
                            self.raw[byte_idx] |= (1 << bit_pos)
                    if self.bit_count < 64:
                        self.gdata = ((self.gdata << 1) | new_bit) & MASK64
                    else:
                        self.extra_data = ((self.extra_data << 1) | new_bit) & 0xFFFFFFFF
                    self.bit_count += 1
                    if self.bit_count >= _MAR_MAX_BITS:
                        frame_complete = True
            else:
                if self.bit_count >= _MAR_MIN_BITS:
                    frame_complete = True
                else:
                    self.state = _MS_RESET
            if frame_complete:
                self._frame_complete()
            self.te_last = duration
            return


# =====================================================================
# Fiat V1 / V2 shared differential-cell machinery
# =====================================================================
# Each logical bit is two half-cells of OPPOSITE level (first != second). A short
# pulse pushes one cell of its level, a long pulse pushes two cells of its level.
# A full wire window (2*wire_bits cells) is sliced into bytes MSB-first; decode is
# tried straight, then inverted.

def _fiat_cells_to_raw(cells, wire_bits, invert):
    nbytes = (wire_bits + 7) // 8
    raw = [0] * nbytes
    for bit_index in range(wire_bits):
        first = cells[bit_index * 2]
        second = cells[bit_index * 2 + 1]
        if first == second:
            return None
        bit = 1 if first else 0
        if invert:
            bit ^= 1
        if bit:
            raw[bit_index >> 3] |= (1 << (7 - (bit_index & 7)))
    return raw


# =====================================================================
# 3. Fiat V1 (fiat_v1.c) -- NAME "Fiat V1"
# =====================================================================
# Two parallel timing variants (0: 250/500, 1: 100/200). 104 wire bits -> 13 raw
# bytes. Frame valid: raw[0]==0x00, raw[1]==0x01, xor(0x01,raw[0..11])==raw[12],
# btn=raw[6]>>4 in {1,2,4,8}, uid=raw[2..5] non-degenerate. generic.data =
# serial<<32 | hop (102 logical bits). Hitag2 key verified at epoch 0 (info only).
_FV1_WIRE_BITS = 104
_FV1_WIRE_CELLS = 208
_FV1_BOUNDARY_MIN = 800
_FV1_LOGICAL_BITS = 102
_FV1_C0 = Const(250, 500, 100)
_FV1_C1 = Const(100, 200, 50)
_FV1_CONSTS = (_FV1_C0, _FV1_C1)

_FS_RESET, _FS_DATA = 0, 1


class FiatV1(ProtoDecoder):
    NAME = "Fiat V1"
    CONST = _FV1_C0

    def __init__(self):
        super().__init__()
        self._freset()

    def _freset(self):
        self.d.parser_step = _FS_RESET
        self.cells = [[], []]
        self.last_raw = None
        self.raw = [0] * 13
        self.tail_bits = 2

    def reset(self):
        super().reset()
        self._freset()

    @staticmethod
    def _is_short(variant, dur):
        c = _FV1_CONSTS[variant]
        return _tdiff(dur, c.te_short) < c.te_delta

    @staticmethod
    def _is_long(variant, dur):
        c = _FV1_CONSTS[variant]
        return _tdiff(dur, c.te_long) < c.te_delta

    def _is_pulse(self, dur):
        for v in range(2):
            if self._is_short(v, dur) or self._is_long(v, dur):
                return True
        return False

    @staticmethod
    def _frame_xor(raw):
        v = 0x01
        for i in range(12):
            v ^= raw[i]
        return v

    @classmethod
    def _frame_valid(cls, raw):
        if raw[0] != 0x00 or raw[1] != 0x01:
            return False
        if cls._frame_xor(raw) != raw[12]:
            return False
        if (raw[6] >> 4) not in (0x1, 0x2, 0x4, 0x8):
            return False
        uid = (raw[2] << 24) | (raw[3] << 16) | (raw[4] << 8) | raw[5]
        return uid != 0 and uid != 0xFFFFFFFF

    def _commit(self, raw):
        if not self._frame_valid(raw):
            return False
        if self.last_raw is not None and self.last_raw == raw:
            return True
        self.raw = list(raw)
        self.last_raw = list(raw)
        self._decode_fields()
        return True

    def _decode_fields(self):
        raw = self.raw
        uid = (raw[2] << 24) | (raw[3] << 16) | (raw[4] << 8) | raw[5]
        btn = raw[6] >> 4
        cnt = ((raw[6] & 0x0F) << 6) | (raw[7] >> 2)
        hop = (((raw[7] & 0x03) << 30) | (raw[8] << 22) |
               (raw[9] << 14) | (raw[10] << 6) | (raw[11] >> 2))
        self.tail_bits = raw[11] & 0x03
        frame_xor = raw[12]
        data = ((uid << 32) | hop) & MASK64
        self.d.decode_data = data
        self.d.decode_count_bit = _FV1_LOGICAL_BITS
        # Hitag2 dictionary verify at epoch 0 (informational, mirrors firmware)
        key = None
        epoch = 0
        control = cnt & 0x3FF
        for k in FIAT_KNOWN_KEYS:
            if fiat_bcm_generate_authenticator(uid, btn, control, k, 0) == hop:
                key = k
                break
        self.emit(dict(serial=uid, btn=btn, cnt=cnt, bits=_FV1_LOGICAL_BITS,
                       data=data, hop=hop, frame_xor=frame_xor,
                       hitag2_key_valid=(key is not None),
                       hitag2_key=("%02X%02X%02X%02X%02X%02X" % key) if key else None,
                       hitag2_epoch=epoch if key else None))

    def _try_decode(self, variant):
        cells = self.cells[variant]
        if len(cells) != _FV1_WIRE_CELLS:
            return
        raw = _fiat_cells_to_raw(cells, _FV1_WIRE_BITS, False)
        if raw is not None and self._commit(raw):
            return
        raw = _fiat_cells_to_raw(cells, _FV1_WIRE_BITS, True)
        if raw is not None:
            self._commit(raw)

    def _push_cell(self, variant, level):
        cells = self.cells[variant]
        bit = 1 if level else 0
        if len(cells) < _FV1_WIRE_CELLS:
            cells.append(bit)
        else:
            cells.pop(0)
            cells.append(bit)
        self._try_decode(variant)

    def _clear_cells(self, variant):
        self.cells[variant] = []

    def _feed_data_pulse(self, level, duration):
        matched = False
        for v in range(2):
            if self._is_short(v, duration):
                self._push_cell(v, level)
                matched = True
            elif self._is_long(v, duration):
                self._push_cell(v, level)
                self._push_cell(v, level)
                matched = True
            else:
                if (not level) and duration >= _FV1_BOUNDARY_MIN:
                    self._push_cell(v, False)
                self._clear_cells(v)
        return matched

    def feed(self, level, duration):
        d = self.d
        if d.parser_step == _FS_RESET:
            if self._is_pulse(duration):
                self._clear_cells(0)
                self._clear_cells(1)
                d.parser_step = _FS_DATA
                self._feed_data_pulse(level, duration)
        elif d.parser_step == _FS_DATA:
            if not self._feed_data_pulse(level, duration):
                d.parser_step = _FS_RESET


# =====================================================================
# 4. Fiat V2 (fiat_v2.c) -- NAME "Fiat V2"
# =====================================================================
# Same differential-cell scheme, single variant 210/420. 112 wire bits -> 14 raw
# bytes. Frame valid: raw[0]==0x00, raw[1]==0x01, (raw[7]>>6) in {2,3,1}, uid
# non-degenerate. FCA frames (raw[6]&0xF0==0xD0) shift the hop/counter fields.
# generic.btn = raw[7] (full byte), generic.data = uid<<32 | hop (112 bits).
_FV2_WIRE_BITS = 112
_FV2_WIRE_CELLS = 224
_FV2_BOUNDARY_MIN = 900
_FV2_C = Const(210, 420, 100)
_FV2_FCA_NIBBLE = 0xD0


class FiatV2(ProtoDecoder):
    NAME = "Fiat V2"
    CONST = _FV2_C

    def __init__(self):
        super().__init__()
        self._freset()

    def _freset(self):
        self.d.parser_step = _FS_RESET
        self.cells = []
        self.last_raw = None
        self.raw = [0] * 14

    def reset(self):
        super().reset()
        self._freset()

    @staticmethod
    def _is_short(dur):
        return _tdiff(dur, _FV2_C.te_short) < _FV2_C.te_delta

    @staticmethod
    def _is_long(dur):
        return _tdiff(dur, _FV2_C.te_long) < _FV2_C.te_delta

    @staticmethod
    def _uid(raw):
        return (raw[2] << 24) | (raw[3] << 16) | (raw[4] << 8) | raw[5]

    @staticmethod
    def _is_fca(raw):
        return (raw[6] & 0xF0) == _FV2_FCA_NIBBLE

    @classmethod
    def _hop(cls, raw):
        if cls._is_fca(raw):
            return (raw[10] << 24) | (raw[11] << 16) | (raw[12] << 8) | raw[13]
        return (raw[9] << 24) | (raw[10] << 16) | (raw[11] << 8) | raw[12]

    @classmethod
    def _counter(cls, raw):
        if cls._is_fca(raw):
            raw_cnt = (raw[8] << 6) | (raw[9] >> 2)
            return (~raw_cnt) & 0x3FFF
        raw_cnt = ((raw[7] & 0x3F) << 5) | (raw[8] >> 3)
        return (~raw_cnt) & 0x7FF

    @staticmethod
    def _button_valid(button):
        sel = button >> 6
        return sel in (0x2, 0x3, 0x1)

    @classmethod
    def _frame_valid(cls, raw):
        if raw[0] != 0x00 or raw[1] != 0x01:
            return False
        if not cls._button_valid(raw[7]):
            return False
        uid = cls._uid(raw)
        return uid != 0 and uid != 0xFFFFFFFF

    def _commit(self, raw):
        if not self._frame_valid(raw):
            return False
        if self.last_raw is not None and self.last_raw == raw:
            return True
        self.raw = list(raw)
        self.last_raw = list(raw)
        self._decode_fields()
        return True

    def _decode_fields(self):
        raw = self.raw
        uid = self._uid(raw)
        button = raw[7]
        hop = self._hop(raw)
        cnt = self._counter(raw)
        data = ((uid << 32) | hop) & MASK64
        self.d.decode_data = data
        self.d.decode_count_bit = _FV2_WIRE_BITS
        # Hitag2 verify over keys x 4 IV combos (informational)
        key = None
        combo_found = 0
        for k in FIAT_KNOWN_KEYS:
            for combo in range(4):
                if self._key_matches_combo(raw, k, combo):
                    key = k
                    combo_found = combo
                    break
            if key:
                break
        self.emit(dict(serial=uid, btn=button, cnt=cnt, bits=_FV2_WIRE_BITS,
                       data=data, hop=hop,
                       hitag2_key_valid=(key is not None),
                       hitag2_key=("%02X%02X%02X%02X%02X%02X" % key) if key else None,
                       hitag2_iv_combo=combo_found if key else None))

    @classmethod
    def _iv_button(cls, raw, combo):
        sel = (raw[7] >> 6) & 0x0F
        if (combo & 0x01) == 0:
            return sel
        if sel == 0x1:
            return 0x2
        if sel == 0x2:
            return 0x4
        if sel == 0x3:
            return 0x8
        return 0x0

    @classmethod
    def _iv_control(cls, raw, combo):
        cnt = cls._counter(raw) & 0x3FF
        if (combo & 0x02) == 0:
            return cnt
        return (~cnt) & 0x3FF

    @classmethod
    def _key_matches_combo(cls, raw, key, combo):
        uid = cls._uid(raw)
        btn = cls._iv_button(raw, combo)
        ctrl = cls._iv_control(raw, combo)
        hop = cls._hop(raw)
        return fiat_bcm_generate_authenticator(uid, btn, ctrl, key, 0) == hop

    def _try_decode(self):
        if len(self.cells) != _FV2_WIRE_CELLS:
            return
        raw = _fiat_cells_to_raw(self.cells, _FV2_WIRE_BITS, False)
        if raw is not None and self._commit(raw):
            return
        raw = _fiat_cells_to_raw(self.cells, _FV2_WIRE_BITS, True)
        if raw is not None:
            self._commit(raw)

    def _push_cell(self, level):
        bit = 1 if level else 0
        if len(self.cells) < _FV2_WIRE_CELLS:
            self.cells.append(bit)
        else:
            self.cells.pop(0)
            self.cells.append(bit)
        self._try_decode()

    def _feed_data_pulse(self, level, duration):
        if self._is_short(duration):
            self._push_cell(level)
            return True
        if self._is_long(duration):
            self._push_cell(level)
            self._push_cell(level)
            return True
        if (not level) and duration >= _FV2_BOUNDARY_MIN:
            self._push_cell(False)
        self.cells = []
        return False

    def feed(self, level, duration):
        d = self.d
        if d.parser_step == _FS_RESET:
            if self._is_short(duration) or self._is_long(duration):
                self.cells = []
                d.parser_step = _FS_DATA
                self._feed_data_pulse(level, duration)
        elif d.parser_step == _FS_DATA:
            if not self._feed_data_pulse(level, duration):
                d.parser_step = _FS_RESET


# =====================================================================
# 5. Fiat Multi (fiat_v2_v3_v5.c) -- NAME "Fiat Multi"
# =====================================================================
# Single registration, variant (V3/V5) chosen from the first pulse's te_short.
# Table Manchester (ported exactly; no arke_core reuse -- see module docstring).
#   V3: te 460/920, 70-bit frame [22 fix|2 btn|2 status|44 hop], hop linear
#       cryptanalysis gates (btn parity + 4 XOR invariants + stop bits).
#   V5: te 200/400, 97-bit frame [32 fix|8 type|8 btn|8 seed|8 cnt|32 hop|1 stop].
# A frame's leading decoded 1-bits are the preamble and are stripped before field
# extraction.
_FIAT_MANCH_TABLE = (0x01, 0x91, 0x9B, 0xFB)


def fiat_manchester_decode(state, timing_type):
    """fiat_manchester_decode(): returns (new_state, bit_or_None)."""
    if timing_type == 8:
        return 1, None
    if state >= 4:
        return 1, None
    entry = _FIAT_MANCH_TABLE[state]
    new_state = (entry >> timing_type) & 0x3
    if state == new_state:
        return state, None
    if new_state == 2:
        return new_state, 0
    if new_state == 1:
        return new_state, 1
    return new_state, None


_FIATV3_TE_SHORT, _FIATV3_TE_LONG, _FIATV3_TE_DELTA = 460, 920, 200
_FIATV3_MIN_GAP = 1500
_FIATV3_FRAME_BITS = 70
_FIATV3_MIN_FRAME_BITS = 60
_FIATV3_MAX_BITS = 100
_FIATV3_PREAMBLE_MIN = 4
_FIATV3_PREAMBLE_MAX = 20
_FIATV3_MAX_TIMINGS = 170
_FIATV3_MIN_PREAMBLE_GAP = 400

_FIATV5_TE_SHORT, _FIATV5_TE_LONG, _FIATV5_TE_DELTA = 200, 400, 100
_FIATV5_MIN_GAP = 2000
_FIATV5_FRAME_BITS = 97
_FIATV5_MIN_FRAME_BITS = 90
_FIATV5_MAX_BITS = 130
_FIATV5_PREAMBLE_MIN = 10

_FIATV5_BTN_OPEN, _FIATV5_BTN_TRUNK, _FIATV5_BTN_CLOSE = 0x80, 0x40, 0x20

# params table rows: (variant, te_short, te_long, te_delta, min_gap, frame_bits,
#                      min_frame, max_bits, min_count_for_found)
_FIAT_VP = (
    (3, _FIATV3_TE_SHORT, _FIATV3_TE_LONG, _FIATV3_TE_DELTA, _FIATV3_MIN_GAP,
     _FIATV3_FRAME_BITS, _FIATV3_MIN_FRAME_BITS, _FIATV3_MAX_BITS,
     _FIATV3_MIN_FRAME_BITS),
    (5, _FIATV5_TE_SHORT, _FIATV5_TE_LONG, _FIATV5_TE_DELTA, _FIATV5_MIN_GAP,
     _FIATV5_FRAME_BITS, _FIATV5_MIN_FRAME_BITS, _FIATV5_MAX_BITS,
     _FIATV5_MIN_FRAME_BITS),
)


def _fiat_v3_hop_btn_extract(hop):
    mask = (3 << 4) | (3 << 12) | (3 << 20) | (3 << 28) | (3 << 36)
    masked = hop & mask
    masked ^= masked >> 32
    masked ^= masked >> 16
    masked ^= masked >> 8
    masked ^= masked >> 4
    masked ^= masked >> 2
    masked ^= masked >> 1
    return masked & 1


def _fiat_v3_hop_invariants_check(hop):
    r1 = (3 << 5) | (3 << 13) | (3 << 21) | (3 << 29) | (3 << 37)
    r2 = (5 << 5) | (5 << 13) | (5 << 21) | (5 << 29) | (5 << 37)
    r3 = ((1 << 5) | (1 << 8) | (1 << 13) | (1 << 16) | (1 << 21) | (1 << 24) |
          (1 << 29) | (1 << 32) | (1 << 37) | (1 << 40))
    r4 = (1 << 1) | (1 << 9) | (1 << 17) | (1 << 25) | (1 << 33) | (1 << 41)
    for m in (r1, r2, r3, r4):
        v = hop & m
        v ^= v >> 32
        v ^= v >> 16
        v ^= v >> 8
        v ^= v >> 4
        v ^= v >> 2
        v ^= v >> 1
        if v & 1:
            return False
    return True


def _fiat_v5_valid_button(btn):
    return btn in (_FIATV5_BTN_OPEN, _FIATV5_BTN_TRUNK, _FIATV5_BTN_CLOSE)


class FiatMulti(ProtoDecoder):
    NAME = "Fiat Multi"
    CONST = Const(_FIATV5_TE_SHORT, _FIATV5_TE_LONG, _FIATV5_TE_DELTA)

    def __init__(self):
        super().__init__()
        self._fmreset()

    def _fmreset(self):
        self.d.parser_step = _FS_RESET
        self.variant = 3
        self.manchester_state = 1
        self.bits = []
        self.bit_count = 0
        # v3 preamble tracking
        self.v3_timing_count = 0
        self.v3_pre_gap_sum = 0
        self.v3_pre_gap_count = 0
        self.v3_pre_ended = False

    def reset(self):
        super().reset()
        self._fmreset()

    def _vp(self):
        return _FIAT_VP[0] if self.variant == 3 else _FIAT_VP[1]

    def _clear_counters(self):
        self.bit_count = 0
        self.bits = []
        self.manchester_state = 1
        if self.variant == 3:
            self.v3_timing_count = 0
            self.v3_pre_gap_sum = 0
            self.v3_pre_gap_count = 0
            self.v3_pre_ended = False

    def _process_frame(self):
        start = 0
        while start < self.bit_count and self.bits[start] == 1:
            start += 1
        preamble_len = start
        data_len = self.bit_count - start

        if self.variant == 3:
            if preamble_len < _FIATV3_PREAMBLE_MIN or data_len < _FIATV3_MIN_FRAME_BITS:
                return
            if preamble_len > _FIATV3_PREAMBLE_MAX:
                return
            if self.bit_count >= _FIATV3_MAX_BITS:
                return
            if self.v3_timing_count > _FIATV3_MAX_TIMINGS:
                return
            if self.v3_pre_gap_count > 0:
                avg_gap = self.v3_pre_gap_sum // self.v3_pre_gap_count
                if avg_gap < _FIATV3_MIN_PREAMBLE_GAP:
                    return
            if data_len > _FIATV3_FRAME_BITS:
                data_len = _FIATV3_FRAME_BITS

            fix = 0
            for i in range(min(22, data_len)):
                fix = (fix << 1) | self.bits[start + i]
            btn = 0
            for i in range(22, min(24, data_len)):
                btn = (btn << 1) | self.bits[start + i]
            status = 0
            for i in range(24, min(26, data_len)):
                status = (status << 1) | self.bits[start + i]
            hop = 0
            for i in range(26, min(70, data_len)):
                hop = (hop << 1) | self.bits[start + i]

            if data_len >= _FIATV3_FRAME_BITS:
                if (hop >> 42) != 0:
                    return
                if (hop & 1) != 1:
                    return

            data = (((fix & 0x3FFFFF) << 42) | ((btn & 0x3) << 40) |
                    ((hop >> 4) & 0xFFFFFFFFFF)) & MASK64
            hop_btn = _fiat_v3_hop_btn_extract(hop)
            if hop_btn != btn:
                return
            if not _fiat_v3_hop_invariants_check(hop):
                return
            self.d.decode_data = data
            self.emit(dict(serial=fix, btn=btn, cnt=0, bits=_FIATV3_FRAME_BITS,
                           data=data, status=status, hop=hop))
            return

        # variant 5
        if preamble_len < _FIATV5_PREAMBLE_MIN or data_len < _FIATV5_MIN_FRAME_BITS:
            return
        if data_len > _FIATV5_FRAME_BITS:
            data_len = _FIATV5_FRAME_BITS

        fix = 0
        for i in range(min(32, data_len)):
            fix = (fix << 1) | self.bits[start + i]
        type_byte = 0
        for i in range(32, min(40, data_len)):
            type_byte = (type_byte << 1) | self.bits[start + i]
        btn = 0
        for i in range(40, min(48, data_len)):
            btn = (btn << 1) | self.bits[start + i]
        seed = 0
        for i in range(48, min(56, data_len)):
            seed = (seed << 1) | self.bits[start + i]
        cnt = 0
        for i in range(56, min(64, data_len)):
            cnt = (cnt << 1) | self.bits[start + i]
        hop = 0
        for i in range(64, min(96, data_len)):
            hop = (hop << 1) | self.bits[start + i]

        if not _fiat_v5_valid_button(btn):
            return
        if data_len >= _FIATV5_FRAME_BITS:
            if (hop & 1) != 1:
                return

        data = (((fix & 0xFFFFFFFF) << 32) | (hop & 0xFFFFFFFF)) & MASK64
        self.d.decode_data = data
        self.emit(dict(serial=fix, btn=btn, cnt=cnt, bits=_FIATV5_FRAME_BITS,
                       data=data, type=type_byte, seed=seed, hop=hop))

    def _decode_timing(self, level, duration):
        vp = self._vp()
        _, te_short, te_long, te_delta, _, _, _, max_bits, _ = vp
        valid = False
        timing_type = 0
        if _tdiff(duration, te_short) <= te_delta:
            timing_type = 0 if level else 2
            valid = True
        elif _tdiff(duration, te_long) <= te_delta:
            timing_type = 4 if level else 6
            valid = True

        if valid:
            if self.variant == 3:
                self.v3_timing_count += 1
                if (not level) and (not self.v3_pre_ended):
                    self.v3_pre_gap_sum += duration
                    self.v3_pre_gap_count += 1
                if self.v3_timing_count > _FIATV3_MAX_TIMINGS:
                    self.d.parser_step = _FS_RESET
                    self.bit_count = 0
                    self.bits = []
                    self.manchester_state = 1
                    self.v3_timing_count = 0
                    self.v3_pre_gap_sum = 0
                    self.v3_pre_gap_count = 0
                    self.v3_pre_ended = False
                    return
            new_state, bit = fiat_manchester_decode(self.manchester_state, timing_type)
            self.manchester_state = new_state
            if bit is not None:
                if self.bit_count < max_bits:
                    self.bits.append(bit)
                    self.bit_count += 1
                if self.variant == 3 and bit == 0 and not self.v3_pre_ended:
                    self.v3_pre_ended = True
                if self.bit_count >= max_bits:
                    self._process_frame()
                    self.d.parser_step = _FS_RESET
                    self._clear_counters()
        else:
            if self.bit_count >= vp[8]:
                self._process_frame()
            self.d.parser_step = _FS_RESET
            self._clear_counters()

    def feed(self, level, duration):
        d = self.d
        if d.parser_step == _FS_RESET:
            best_idx = -1
            best_diff = 1 << 32
            for i, vp in enumerate(_FIAT_VP):
                te_short = vp[1]
                te_delta = vp[3]
                dd = _tdiff(duration, te_short)
                if dd <= te_delta and dd < best_diff:
                    best_diff = dd
                    best_idx = i
            if best_idx >= 0:
                self.variant = _FIAT_VP[best_idx][0]
                d.parser_step = _FS_DATA
                self.manchester_state = 1
                self.bit_count = 0
                self.bits = []
                self.v3_timing_count = 0
                self.v3_pre_gap_sum = 0
                self.v3_pre_gap_count = 0
                self.v3_pre_ended = False
                self._decode_timing(level, duration)
        elif d.parser_step == _FS_DATA:
            vp = self._vp()
            min_gap = vp[4]
            min_count = vp[8]
            if duration > min_gap:
                if self.bit_count >= min_count:
                    self._process_frame()
                self.d.parser_step = _FS_RESET
                self._clear_counters()
                return
            self._decode_timing(level, duration)




# ====================== batch4b: PSA / Hitag2 / Urmet ======================
M32 = 0xFFFFFFFF


def _man_adv(man, event):
    """Mirror manchester_advance(state, event, &state, &bit): return
    (decoded_bool, bit_int)."""
    r = man.advance(event)
    return (r is not None), (1 if r else 0)


# ============================================================================
# PSA GROUP  (psa.c)   NAME = "PSA GROUP"
# ============================================================================
# te_short=250 te_long=500 te_delta=100, min 128 bits.
# Two physical front-ends share one struct:
#   FM  : State0 -> State1 (count >0x46 te_short lows) -> State2 (Manchester
#         80-bit key1:key2, end on HIGH ~1000us).
#   AM  : State0 -> State3 (bi-phase pre-amble, per-burst te estimate) ->
#         State4 (Manchester into am_bits[], psa_am_complete scans offsets for
#         the nibble-0xA framed 80-bit payload).
# On lock generic.data = (key1_high<<32)|key1_low (64b) and the callback
# fires; serialize() runs psa_decrypt_fast (direct XOR / mode 0x23) to fill
# serial/btn/cnt when the checksum validates.

_PSA_TE_SHORT_125 = 0x7d
_PSA_TOLERANCE_99 = 99
_PSA_TOLERANCE_49 = 0x31
_PSA_PATTERN_THRESHOLD_1 = 0x46
_PSA_MAX_BITS = 0x79       # 121
_PSA_KEY1_BITS = 0x40      # 64
_PSA_KEY2_BITS = 0x50      # 80
_PSA_AM_PRE_MIN = 60
_PSA_AM_PRE_MAX = 200
_PSA_AM_PRE_THRESHOLD = 8
_PSA_AM_PRE_GLITCH_DECAY = 2
_PSA_AM_PRE_MAX_GLITCH = 3
_PSA_AM_RESYNC_BITS = 8
_PSA_AM_LONG_MIN = 360
_PSA_AM_LONG_MAX = 560
_PSA_AM_LONG_MIN_PCT = 4

_TEA_DELTA = 0x9E3779B9
_TEA_ROUNDS = 32
_PSA_BF1_CONST_U4 = 0x0E0F5C41
_PSA_BF1_CONST_U5 = 0x0F5C4123
_PSA_BF1_KEY_SCHEDULE = (0x4A434915, 0xD6743C2B, 0x1F29D308, 0xE6B79A64)
_PSA_BF2_KEY_SCHEDULE = (0x4039C240, 0xEDA92CAB, 0x4306C02A, 0x02192A04)
_PSA_BF1_START = 0x23000000
_PSA_BF1_END = 0x24000000
_PSA_BF2_START = 0xF3000000
_PSA_BF2_END = 0xF4000000

_PSA_CRC16_TABLE = (
    0x0000, 0x8005, 0x800F, 0x000A, 0x801B, 0x001E, 0x0014, 0x8011,
    0x8033, 0x0036, 0x003C, 0x8039, 0x0028, 0x802D, 0x8027, 0x0022,
    0x8063, 0x0066, 0x006C, 0x8069, 0x0078, 0x807D, 0x8077, 0x0072,
    0x0050, 0x8055, 0x805F, 0x005A, 0x804B, 0x004E, 0x0044, 0x8041,
    0x80C3, 0x00C6, 0x00CC, 0x80C9, 0x00D8, 0x80DD, 0x80D7, 0x00D2,
    0x00F0, 0x80F5, 0x80FF, 0x00FA, 0x80EB, 0x00EE, 0x00E4, 0x80E1,
    0x00A0, 0x80A5, 0x80AF, 0x00AA, 0x80BB, 0x00BE, 0x00B4, 0x80B1,
    0x8093, 0x0096, 0x009C, 0x8099, 0x0088, 0x808D, 0x8087, 0x0082,
    0x8183, 0x0186, 0x018C, 0x8189, 0x0198, 0x819D, 0x8197, 0x0192,
    0x01B0, 0x81B5, 0x81BF, 0x01BA, 0x81AB, 0x01AE, 0x01A4, 0x81A1,
    0x01E0, 0x81E5, 0x81EF, 0x01EA, 0x81FB, 0x01FE, 0x01F4, 0x81F1,
    0x81D3, 0x01D6, 0x01DC, 0x81D9, 0x01C8, 0x81CD, 0x81C7, 0x01C2,
    0x0140, 0x8145, 0x814F, 0x014A, 0x815B, 0x015E, 0x0154, 0x8151,
    0x8173, 0x0176, 0x017C, 0x8179, 0x0168, 0x816D, 0x8167, 0x0162,
    0x8123, 0x0126, 0x012C, 0x8129, 0x0138, 0x813D, 0x8137, 0x0132,
    0x0110, 0x8115, 0x811F, 0x011A, 0x810B, 0x010E, 0x0104, 0x8101,
    0x8303, 0x0306, 0x030C, 0x8309, 0x0318, 0x831D, 0x8317, 0x0312,
    0x0330, 0x8335, 0x833F, 0x033A, 0x832B, 0x032E, 0x0324, 0x8321,
    0x0360, 0x8365, 0x836F, 0x036A, 0x837B, 0x037E, 0x0374, 0x8371,
    0x8353, 0x0356, 0x035C, 0x8359, 0x0348, 0x834D, 0x8347, 0x0342,
    0x03C0, 0x83C5, 0x83CF, 0x03CA, 0x83DB, 0x03DE, 0x03D4, 0x83D1,
    0x83F3, 0x03F6, 0x03FC, 0x83F9, 0x03E8, 0x83ED, 0x83E7, 0x03E2,
    0x83A3, 0x03A6, 0x03AC, 0x83A9, 0x03B8, 0x83BD, 0x83B7, 0x03B2,
    0x0390, 0x8395, 0x839F, 0x039A, 0x838B, 0x038E, 0x0384, 0x8381,
    0x0280, 0x8285, 0x828F, 0x028A, 0x829B, 0x029E, 0x0294, 0x8291,
    0x82B3, 0x02B6, 0x02BC, 0x82B9, 0x02A8, 0x82AD, 0x82A7, 0x02A2,
    0x82E3, 0x02E6, 0x02EC, 0x82E9, 0x02F8, 0x82FD, 0x82F7, 0x02F2,
    0x02D0, 0x82D5, 0x82DF, 0x02DA, 0x82CB, 0x02CE, 0x02C4, 0x82C1,
    0x8243, 0x0246, 0x024C, 0x8249, 0x0258, 0x825D, 0x8257, 0x0252,
    0x0270, 0x8275, 0x827F, 0x027A, 0x826B, 0x026E, 0x0264, 0x8261,
    0x0220, 0x8225, 0x822F, 0x022A, 0x823B, 0x023E, 0x0234, 0x8231,
    0x8213, 0x0216, 0x021C, 0x8219, 0x0208, 0x820D, 0x8207, 0x0202,
)


def _psa_abs_diff(a, b):
    return (b - a) if a < b else (a - b)


def psa_setup_byte_buffer(buf, key1_low, key1_high, key2_low):
    for i in range(8):
        shift = i * 8
        if shift < 32:
            bv = (key1_low >> shift) & 0xFF
        else:
            bv = (key1_high >> (shift - 32)) & 0xFF
        buf[7 - i] = bv
    buf[9] = key2_low & 0xFF
    buf[8] = (key2_low >> 8) & 0xFF


def psa_calculate_checksum(buf):
    checksum = 0
    for i in range(2, 8):
        checksum += (buf[i] & 0xF) + ((buf[i] >> 4) & 0xF)
    buf[11] = (checksum * 0x10) & 0xFF


def psa_copy_reverse(temp, source):
    temp[0] = source[5]
    temp[1] = source[4]
    temp[2] = source[3]
    temp[3] = source[2]
    temp[4] = source[9]
    temp[5] = source[8]
    temp[6] = source[7]
    temp[7] = source[6]


def psa_second_stage_xor_decrypt(buf):
    temp = bytearray(8)
    psa_copy_reverse(temp, buf)
    buf[2] = temp[0] ^ temp[6]
    buf[3] = temp[2] ^ temp[0]
    buf[4] = temp[6] ^ temp[3]
    buf[5] = temp[7] ^ temp[1]
    buf[6] = temp[3] ^ temp[1]
    buf[7] = temp[6] ^ temp[4] ^ temp[5]


def _tea_encrypt(v0, v1, key):
    a, b = v0 & M32, v1 & M32
    s = 0
    for _ in range(_TEA_ROUNDS):
        temp = (key[s & 3] + s) & M32
        s = (s + _TEA_DELTA) & M32
        a = (a + (temp ^ ((((b >> 5) ^ ((b << 4) & M32)) + b) & M32))) & M32
        temp = (key[(s >> 11) & 3] + s) & M32
        b = (b + (temp ^ ((((a >> 5) ^ ((a << 4) & M32)) + a) & M32))) & M32
    return a, b


def _tea_decrypt(v0, v1, key):
    a, b = v0 & M32, v1 & M32
    s = (_TEA_DELTA * _TEA_ROUNDS) & M32
    for _ in range(_TEA_ROUNDS):
        temp = (key[(s >> 11) & 3] + s) & M32
        s = (s - _TEA_DELTA) & M32
        b = (b - (temp ^ ((((a >> 5) ^ ((a << 4) & M32)) + a) & M32))) & M32
        temp = (key[s & 3] + s) & M32
        a = (a - (temp ^ ((((b >> 5) ^ ((b << 4) & M32)) + b) & M32))) & M32
    return a, b


def _tea_build_schedule(key):
    s0 = [0] * _TEA_ROUNDS
    s1 = [0] * _TEA_ROUNDS
    for i in range(_TEA_ROUNDS):
        sum0 = (i * _TEA_DELTA) & M32
        sum1 = ((i + 1) * _TEA_DELTA) & M32
        s0[i] = (key[sum0 & 3] + sum0) & M32
        s1[i] = (key[(sum1 >> 11) & 3] + sum1) & M32
    return s0, s1


def _tea_encrypt_with_schedule(v0, v1, sched):
    s0, s1 = sched
    a, b = v0 & M32, v1 & M32
    for i in range(_TEA_ROUNDS):
        a = (a + (s0[i] ^ ((((b >> 5) ^ ((b << 4) & M32)) + b) & M32))) & M32
        b = (b + (s1[i] ^ ((((a >> 5) ^ ((a << 4) & M32)) + a) & M32))) & M32
    return a, b


def psa_prepare_tea_data(buf):
    w0 = ((buf[3] << 16) | (buf[2] << 24) | (buf[4] << 8) | buf[5]) & M32
    w1 = ((buf[7] << 16) | (buf[6] << 24) | (buf[8] << 8) | buf[9]) & M32
    return w0, w1


def psa_calculate_tea_crc(v0, v1):
    crc = ((v0 >> 24) & 0xFF) + ((v0 >> 16) & 0xFF) + ((v0 >> 8) & 0xFF) + (v0 & 0xFF)
    crc += ((v1 >> 24) & 0xFF) + ((v1 >> 16) & 0xFF) + ((v1 >> 8) & 0xFF)
    return crc & 0xFF


def psa_calculate_crc16_bf2(buf, length):
    crc = 0
    for i in range(length):
        crc = ((crc << 8) ^ _PSA_CRC16_TABLE[((crc >> 8) ^ buf[i]) & 0xFF]) & 0xFFFF
    return crc


def psa_unpack_tea_result_to_buffer(buf, v0, v1):
    buf[2] = (v0 >> 24) & 0xFF
    buf[3] = (v0 >> 16) & 0xFF
    buf[4] = (v0 >> 8) & 0xFF
    buf[5] = v0 & 0xFF
    buf[6] = (v1 >> 24) & 0xFF
    buf[7] = (v1 >> 16) & 0xFF
    buf[8] = (v1 >> 8) & 0xFF
    buf[9] = v1 & 0xFF


def psa_extract_fields_mode23(buf):
    """psa.c variant. Returns dict of decrypted fields."""
    return {
        "button": buf[8] & 0xF,
        "serial": ((buf[3] << 8) | (buf[2] << 16) | buf[4]) & M32,
        "counter": (buf[6] | (buf[5] << 8)) & M32,
        "crc": buf[7] & 0xFFFF,
        "type": 0x23,
    }


def psa_extract_fields_mode36(buf):
    return {
        "button": (buf[5] >> 4) & 0xF,
        "serial": ((buf[3] << 8) | (buf[2] << 16) | buf[4]) & M32,
        "counter": (((buf[7] << 8) | (buf[6] << 16) | buf[8] |
                     ((buf[5] & 0xF) << 24)) & M32),
        "crc": buf[9] & 0xFFFF,
        "type": 0x36,
    }


class PsaGroup(ProtoDecoder):
    NAME = "PSA GROUP"
    CONST = Const(250, 500, 100, 128)

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self._init_state()

    def _init_state(self):
        self.state = 0
        self.prev_duration = 0
        self.decode_data_low = 0
        self.decode_data_high = 0
        self.decode_count_bit = 0
        self.key1_low = 0
        self.key1_high = 0
        self.validation_field = 0
        self.key2_low = 0
        self.key2_high = 0
        self.status_flag = 0
        self.decrypted = 0
        self.mode_serialize = 0
        self.decrypted_button = 0
        self.decrypted_serial = 0
        self.decrypted_counter = 0
        self.decrypted_crc = 0
        self.decrypted_seed = 0
        self.decrypted_type = 0
        self.pattern_counter = 0
        self.last_key1_low = 0
        self.last_key1_high = 0
        self.te_sum = 0
        self.te_count = 0
        self.te_detected = 0
        self.pre_glitch = 0
        self.am_await_high = 0
        self.am_bits = [0] * 96
        self.am_bits_len = 0
        self.am_long_count = 0
        self.am_pulse_count = 0
        self.man.reset()

    def reset(self):
        super().reset()
        self._init_state()

    # --- direct-XOR fast decrypt (mode 0x23) ---------------------------------
    def _direct_xor_decrypt(self, buf):
        psa_calculate_checksum(buf)
        checksum = buf[11]
        key2_high = buf[8]
        validation_result = (checksum ^ key2_high) & 0xF0
        if validation_result == 0:
            buf[13] = buf[9] ^ buf[8]
            psa_second_stage_xor_decrypt(buf)
            f = psa_extract_fields_mode23(buf)
            self.decrypted_button = f["button"]
            self.decrypted_serial = f["serial"]
            self.decrypted_counter = f["counter"]
            self.decrypted_crc = f["crc"]
            self.decrypted_type = 0x23
            self.decrypted_seed = f["serial"]
            return True
        return False

    def _decrypt_fast(self):
        buf = bytearray(48)
        psa_setup_byte_buffer(buf, self.key1_low, self.key1_high, self.key2_low)
        if self._direct_xor_decrypt(buf):
            self.mode_serialize = 0x23
            self.decrypted = 0x50
        else:
            self.decrypted = 0x00
            self.mode_serialize = 0x36

    # --- emit (mirrors feed callback + serialize decrypt_fast) ---------------
    def _fire_if_new(self):
        is_dup = (self.key1_low == self.last_key1_low and
                  self.key1_high == self.last_key1_high)
        psa_like = (self.am_pulse_count == 0) or \
            (self.am_long_count * 100 >= self.am_pulse_count * _PSA_AM_LONG_MIN_PCT)
        if (not is_dup) and psa_like:
            self.last_key1_low = self.key1_low
            self.last_key1_high = self.key1_high
            self._emit_frame()

    def _emit_frame(self):
        data = ((self.key1_high << 32) | self.key1_low) & MASK64
        # serialize() runs decrypt_fast and, on validation, fills serial/btn/cnt
        self._decrypt_fast()
        fields = {"data": data, "bits": 64, "serial": data}
        if self.decrypted == 0x50:
            fields["serial"] = self.decrypted_serial
            fields["btn"] = self.decrypted_button
            fields["cnt"] = self.decrypted_counter
            fields["type"] = self.decrypted_type
        self.emit(fields)

    # --- AM helpers (State4 payload scan) ------------------------------------
    def _add_am_bit(self, bit):
        bit = 0 if bit else 1
        if self.am_bits_len >= 96:
            for i in range(1, 96):
                self.am_bits[i - 1] = self.am_bits[i]
            self.am_bits[95] = bit
        else:
            self.am_bits[self.am_bits_len] = bit
            self.am_bits_len += 1
        carry = (self.decode_data_low >> 31) & 1
        self.decode_data_low = ((self.decode_data_low << 1) | bit) & M32
        self.decode_data_high = ((self.decode_data_high << 1) | carry) & M32
        self.decode_count_bit += 1
        if self.decode_count_bit == _PSA_KEY1_BITS:
            self.key1_low = self.decode_data_low
            self.key1_high = self.decode_data_high
            self.decode_data_low = 0
            self.decode_data_high = 0

    def _am_frame_at(self, off):
        if self.am_bits_len < off + 80:
            return None
        h = l = k = 0
        for i in range(0, 32):
            h = ((h << 1) | self.am_bits[off + i]) & M32
        for i in range(32, 64):
            l = ((l << 1) | self.am_bits[off + i]) & M32
        for i in range(64, 80):
            k = ((k << 1) | self.am_bits[off + i]) & M32
        nib = (h >> 16) & 0xF
        return h, l, k, nib

    @staticmethod
    def _am_varied(hi, lo):
        vals = [(hi >> 24) & 0xFF, (hi >> 16) & 0xFF, (hi >> 8) & 0xFF, hi & 0xFF,
                (lo >> 24) & 0xFF, (lo >> 16) & 0xFF, (lo >> 8) & 0xFF, lo & 0xFF]
        n = 0
        for i in range(8):
            seen = False
            for j in range(i):
                if vals[j] == vals[i]:
                    seen = True
                    break
            if not seen:
                n += 1
        return n >= 4

    def _am_complete(self):
        if self.am_bits_len < 80:
            return False
        max_off = self.am_bits_len - 80
        if max_off > 8:
            max_off = 8
        best_off = -1
        best_score = -1
        for off in range(0, max_off + 1):
            fr = self._am_frame_at(off)
            if fr is None:
                continue
            hi, lo, k2, nib = fr
            if nib != 0xA:
                continue
            if not self._am_varied(hi, lo):
                continue
            score = 0
            bit = 31
            while bit >= 20 and ((hi >> bit) & 1):
                score += 1
                bit -= 1
            if score > best_score:
                best_score = score
                best_off = off

        if best_off < 0:
            self.decode_data_low = 0
            self.decode_data_high = 0
            self.decode_count_bit = 0
            self.am_bits_len = 0
            self.state = 0
            return False

        hi, lo, k2, nib = self._am_frame_at(best_off)
        self.key1_high = hi
        self.key1_low = lo
        self.key2_low = k2
        self.key2_high = 0
        self.validation_field = k2 & 0xFFFF
        self.mode_serialize = 2
        self.status_flag = 0x80

        buf = bytearray(48)
        psa_setup_byte_buffer(buf, self.key1_low, self.key1_high, self.key2_low)
        if self._direct_xor_decrypt(buf):
            self.mode_serialize = 0x23
            self.decrypted = 0x50
        else:
            self.decrypted = 0x00
            self.mode_serialize = 0x36

        self._fire_if_new()

        self.decode_data_low = 0
        self.decode_data_high = 0
        self.decode_count_bit = 0
        self.am_bits_len = 0
        self.state = 0
        return True

    # --- feed ----------------------------------------------------------------
    def feed(self, level, duration):
        if self.am_pulse_count < 0xFFFF:
            self.am_pulse_count += 1
        if _PSA_AM_LONG_MIN <= duration <= _PSA_AM_LONG_MAX and self.am_long_count < 0xFFFF:
            self.am_long_count += 1

        new_state = self.state
        prev_dur = self.prev_duration
        te_short = 250
        te_long = 500
        st = self.state

        if st == 0:
            if not level:
                return
            if duration < te_short:
                tolerance = te_short - duration
                if tolerance > _PSA_TOLERANCE_99:
                    if duration < _PSA_TE_SHORT_125:
                        tolerance = _PSA_TE_SHORT_125 - duration
                    else:
                        tolerance = duration - _PSA_TE_SHORT_125
                    if tolerance > _PSA_TOLERANCE_49:
                        return
                    new_state = 3
                else:
                    new_state = 1
            else:
                tolerance = duration - te_short
                if tolerance > _PSA_TOLERANCE_99:
                    return
                new_state = 1
            self.decode_data_low = 0
            self.decode_data_high = 0
            self.pattern_counter = 0
            self.decode_count_bit = 0
            self.mode_serialize = 0
            self.te_sum = duration
            self.te_count = 1
            self.te_detected = 0
            self.prev_duration = duration
            self.man.reset()
            # fall to tail

        elif st == 1:
            if level:
                return
            handled = False
            if duration < te_short:
                tolerance = te_short - duration
                if tolerance < 100:
                    if _psa_abs_diff(prev_dur, te_short) <= _PSA_TOLERANCE_99:
                        self.pattern_counter += 1
                    self.prev_duration = duration
                    return
            else:
                tolerance = duration - te_short
                if tolerance < 100:
                    if _psa_abs_diff(prev_dur, te_short) <= _PSA_TOLERANCE_99:
                        self.pattern_counter += 1
                    self.prev_duration = duration
                    return
                else:
                    if duration < te_long:
                        long_diff = te_long - duration
                    else:
                        long_diff = duration - te_long
                    if long_diff < 100:
                        if self.pattern_counter > _PSA_PATTERN_THRESHOLD_1:
                            new_state = 2
                            self.decode_data_low = 0
                            self.decode_data_high = 0
                            self.decode_count_bit = 0
                            self.man.reset()
                            self.state = new_state
                        self.pattern_counter = 0
                        self.prev_duration = duration
                        return
            # fell through
            new_state = 0
            self.pattern_counter = 0
            # fall to tail

        elif st == 2:
            res = self._feed_state2(level, duration, te_short, te_long)
            if res is not None:
                return
            new_state = self._s2_new_state
            # fall to tail

        elif st == 3:
            res = self._feed_state3(level, duration, prev_dur)
            if res is not None:
                return
            new_state = self._s3_new_state
            # fall to tail

        elif st == 4:
            res = self._feed_state4(level, duration)
            if res == "return":
                return
            if res == "break":
                new_state = self._s4_new_state
            # fall to tail

        # ---- tail (C lines 1241-1244) ----
        if new_state == 0:
            self.am_bits_len = 0
        self.state = new_state
        self.prev_duration = duration

    # State2 : returns None => break to tail (self._s2_new_state), else "return"
    def _feed_state2(self, level, duration, te_short, te_long):
        self._s2_new_state = self.state  # == 2
        if self.decode_count_bit >= _PSA_MAX_BITS:
            self._s2_new_state = 0
            return None

        if level and self.decode_count_bit == _PSA_KEY2_BITS:
            if duration >= 800:
                if duration < 1000:
                    end_diff = 1000 - duration
                else:
                    end_diff = duration - 1000
                if end_diff <= 199:
                    self.validation_field = self.decode_data_low & 0xFFFF
                    self.key2_low = self.decode_data_low
                    self.key2_high = self.decode_data_high
                    self.mode_serialize = 1
                    self.status_flag = 0x80
                    buf = bytearray(48)
                    psa_setup_byte_buffer(buf, self.key1_low, self.key1_high, self.key2_low)
                    if self._direct_xor_decrypt(buf):
                        self.mode_serialize = 0x23
                        self.decrypted = 0x50
                    else:
                        self.decrypted = 0x00
                        self.mode_serialize = 0x36
                    if self.decrypted != 0x50 and ((self.key1_high >> 16) & 0xF) != 0xA:
                        self.decode_data_low = 0
                        self.decode_data_high = 0
                        self.decode_count_bit = 0
                        self.state = 0
                        return "return"
                    self._fire_if_new()
                    self.decode_data_low = 0
                    self.decode_data_high = 0
                    self.decode_count_bit = 0
                    self.state = 0
                    return "return"

        manchester_input = 0
        should_process = False

        if duration < te_short:
            tolerance = te_short - duration
            if tolerance >= 100:
                return "return"
            manchester_input = ((int(level) ^ 1) & 0x7f) << 1
            should_process = True
        else:
            tolerance = duration - te_short
            if tolerance < 100:
                manchester_input = ((int(level) ^ 1) & 0x7f) << 1
                should_process = True
            elif duration < te_long:
                diff_from_250 = duration - te_short
                diff_from_500 = te_long - duration
                if diff_from_500 < 150 or diff_from_250 > diff_from_500:
                    manchester_input = 6 if level == 0 else 4
                    should_process = True
                elif diff_from_250 < 150:
                    manchester_input = ((int(level) ^ 1) & 0x7f) << 1
                    should_process = True
                else:
                    if duration > 10000:
                        self._s2_new_state = 0
                        self.pattern_counter = 0
                        return "return"
                    if 350 <= duration <= 400:
                        manchester_input = 6 if level == 0 else 4
                        should_process = True
                    else:
                        return "return"
            else:
                long_diff = duration - te_long
                if long_diff < 100:
                    manchester_input = 6 if level == 0 else 4
                    should_process = True
                else:
                    if not level:
                        if duration > 10000:
                            self._s2_new_state = 0
                            self.pattern_counter = 0
                            return "return"
                        return "return"
                    should_process = False

        if should_process and self.decode_count_bit < _PSA_KEY2_BITS:
            decoded, bit = _man_adv(self.man, manchester_input)
            if decoded:
                carry = (self.decode_data_low >> 31) & 1
                self.decode_data_low = ((self.decode_data_low << 1) | (1 if bit else 0)) & M32
                self.decode_data_high = ((self.decode_data_high << 1) | carry) & M32
                self.decode_count_bit += 1
                if self.decode_count_bit == _PSA_KEY1_BITS:
                    self.key1_low = self.decode_data_low
                    self.key1_high = self.decode_data_high
                    self.decode_data_low = 0
                    self.decode_data_high = 0

        if not level:
            return "return"

        if not should_process:
            if duration < 1000:
                end_diff = 1000 - duration
            else:
                end_diff = duration - 1000
            if end_diff <= 199:
                if self.decode_count_bit != _PSA_KEY2_BITS:
                    return "return"
                self.validation_field = self.decode_data_low & 0xFFFF
                if ((self.key1_high >> 16) & 0xF) == 0xA:
                    self.key2_low = self.decode_data_low
                    self.key2_high = self.decode_data_high
                    self.mode_serialize = 1
                    self.status_flag = 0x80
                    buf = bytearray(48)
                    psa_setup_byte_buffer(buf, self.key1_low, self.key1_high, self.key2_low)
                    if self._direct_xor_decrypt(buf):
                        self.mode_serialize = 0x23
                        self.decrypted = 0x50
                    else:
                        self.decrypted = 0x00
                        self.mode_serialize = 0x36
                    self._fire_if_new()
                    self.decode_data_low = 0
                    self.decode_data_high = 0
                    self.decode_count_bit = 0
                    self._s2_new_state = 0
                else:
                    return "return"
            else:
                return "return"
        return None  # break

    # State3 : AM pre-amble. returns None=>break(self._s3_new_state) else "return"
    def _feed_state3(self, level, duration, prev_dur):
        self._s3_new_state = self.state  # == 3
        if level:
            self.prev_duration = duration
            return "return"
        high = prev_dur
        period = high + duration
        te_avg3 = (self.te_sum // self.te_count) if self.te_count > 0 else _PSA_TE_SHORT_125
        if te_avg3 < _PSA_AM_PRE_MIN:
            te_avg3 = _PSA_TE_SHORT_125
        period_est = te_avg3 * 2
        cell_mid = period_est + (period_est >> 1)
        cell_hi = period_est + period_est + (period_est >> 1)
        cell_min = _PSA_AM_PRE_MIN * 2
        is_cell = (cell_min <= period <= cell_mid and
                   _PSA_AM_PRE_MIN <= high <= _PSA_AM_PRE_MAX and
                   _PSA_AM_PRE_MIN <= duration <= _PSA_AM_PRE_MAX)
        is_boundary = (not is_cell) and (cell_mid < period <= cell_hi)

        if is_cell:
            self.pattern_counter += 1
            self.pre_glitch = 0
            self.te_sum += (period >> 1)
            self.te_count += 1
            if self.te_count > 32:
                self.te_sum = te_avg3 * 8
                self.te_count = 8
            self.prev_duration = duration
            return "return"

        if is_boundary and self.pattern_counter >= _PSA_AM_PRE_THRESHOLD:
            self.te_detected = te_avg3
            self.am_long_count = 0
            self.am_pulse_count = 0
            self.decode_data_low = 0
            self.decode_data_high = 0
            self.decode_count_bit = 0
            self.am_bits_len = 0
            self.man.reset()
            self.am_await_high = 0
            self.state = 4
            self.pattern_counter = 0
            self.pre_glitch = 0
            self.prev_duration = duration
            return "return"

        if self.pattern_counter >= _PSA_AM_PRE_THRESHOLD:
            self.te_detected = te_avg3
            self.am_long_count = 0
            self.am_pulse_count = 0
            self.decode_data_low = 0
            self.decode_data_high = 0
            self.decode_count_bit = 0
            self.am_bits_len = 0
            self.man.reset()
            self.am_await_high = 1
            self.state = 4
            self.pattern_counter = 0
            self.pre_glitch = 0
            self.prev_duration = duration
            return "return"

        self.pre_glitch += 1
        if self.pattern_counter >= _PSA_AM_PRE_GLITCH_DECAY:
            self.pattern_counter -= _PSA_AM_PRE_GLITCH_DECAY
        else:
            self.pattern_counter = 0
        if self.pre_glitch <= _PSA_AM_PRE_MAX_GLITCH and self.pattern_counter > 0:
            self.prev_duration = duration
            return "return"
        self._s3_new_state = 0
        self.pattern_counter = 0
        self.pre_glitch = 0
        return None  # break

    # State4 : AM Manchester. returns "return" | "break"
    def _feed_state4(self, level, duration):
        self._s4_new_state = self.state  # == 4
        te_s = self.te_detected if self.te_detected else _PSA_TE_SHORT_125
        te_l = te_s * 2
        te_tol = (te_s * 7) // 10
        midpoint = te_s + (te_s >> 1)

        if self.am_await_high:
            if duration > te_l + te_tol:
                self.prev_duration = duration
                return "return"
            self.am_await_high = 0
            self.man.reset()
            self.decode_data_low = 0
            self.decode_data_high = 0
            self.decode_count_bit = 0
            self.am_bits_len = 0

        if self.decode_count_bit >= _PSA_KEY2_BITS and duration > te_l + te_tol:
            if self._am_complete():
                return "return"

        if self.decode_count_bit >= _PSA_MAX_BITS:
            if not self._am_complete():
                self._s4_new_state = 0
            if self.state == 0:
                return "break"
            return "return"

        if duration > te_l + te_tol:
            if self.decode_count_bit >= _PSA_KEY2_BITS:
                if self._am_complete():
                    return "return"
            if self.decode_count_bit < _PSA_AM_RESYNC_BITS:
                self.decode_data_low = 0
                self.decode_data_high = 0
                self.decode_count_bit = 0
                self.am_bits_len = 0
                self.man.reset()
                return "return"
            if duration > 10000:
                self._s4_new_state = 0
                self.am_bits_len = 0
                return "break"
            return "return"

        if duration <= midpoint:
            if _psa_abs_diff(duration, te_s) > te_tol:
                if self.decode_count_bit < _PSA_AM_RESYNC_BITS:
                    self.decode_data_low = 0
                    self.decode_data_high = 0
                    self.decode_count_bit = 0
                    self.am_bits_len = 0
                    self.man.reset()
                return "return"
            manchester_input4 = M_SHORT_LOW if level else M_SHORT_HIGH
        else:
            if _psa_abs_diff(duration, te_l) > te_tol:
                if self.decode_count_bit < _PSA_AM_RESYNC_BITS:
                    self.decode_data_low = 0
                    self.decode_data_high = 0
                    self.decode_count_bit = 0
                    self.am_bits_len = 0
                    self.man.reset()
                return "return"
            manchester_input4 = M_LONG_LOW if level else M_LONG_HIGH

        decoded, bit = _man_adv(self.man, manchester_input4)
        if decoded:
            self._add_am_bit(1 if bit else 0)
        return "break"


# ============================================================================
# PSA OLD  (psa2.c)   NAME = "PSA OLD"
# ============================================================================
# te_short=250 te_long=500, two bitrates:
#   full: State0 -> State1 (count >0x46 te lows) -> State2 (Manchester 80-bit,
#         end on HIGH ~1000us).
#   half: State0 -> State3 (count >0x45 half-te ~125 lows) -> State4
#         (Manchester 80-bit, end on HIGH ~500us).
# generic.data = (key1_high<<32)|key1_low (64b); decrypt_fast = direct XOR
# (mode 0x23) run on lock + serialize.

_PSA2_TE_SHORT_HALF = 125
_PSA2_TE_LONG_HALF = 250
_PSA2_VALID_NIBBLE = 0xA


def _psa2_near(dur, target, tol):
    return (dur >= target - tol) and (dur <= target + tol)


def psa2_extract_fields_mode23(buf):
    return {
        "button": buf[8] & 0xF,
        "serial": ((buf[2] << 16) | (buf[3] << 8) | buf[4]) & M32,
        "counter": (buf[6] | (buf[5] << 8)) & M32,
        "crc": buf[7] & 0xFFFF,
        "type": 0x23,
    }


class PsaOld(ProtoDecoder):
    NAME = "PSA OLD"
    CONST = Const(250, 500, 100, 128)

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self._init_state()

    def _init_state(self):
        self.state = 0
        self.prev_duration = 0
        self.decode_data_low = 0
        self.decode_data_high = 0
        self.decode_count_bit = 0
        self.key1_low = 0
        self.key1_high = 0
        self.validation_field = 0
        self.key2_low = 0
        self.key2_high = 0
        self.status_flag = 0
        self.mode_serialize = 0
        self.decrypted = 0
        self.decrypted_button = 0
        self.decrypted_serial = 0
        self.decrypted_counter = 0
        self.decrypted_crc = 0
        self.decrypted_seed = 0
        self.decrypted_type = 0
        self.pattern_counter = 0
        self.last_key1_low = 0
        self.last_key1_high = 0
        self.bf_attempted = 0
        self.man.reset()

    def reset(self):
        super().reset()
        self._init_state()

    def _direct_xor_decrypt(self):
        buf = bytearray(48)
        psa_setup_byte_buffer(buf, self.key1_low, self.key1_high, self.key2_low)
        psa_calculate_checksum(buf)
        checksum = buf[11]
        key2_high = buf[8]
        validation = (checksum ^ key2_high) & 0xF0
        if validation == 0:
            buf[8] = (buf[8] & 0x0F) | (checksum & 0xF0)
            buf[13] = buf[9] ^ buf[8]
            psa_second_stage_xor_decrypt(buf)
            f = psa2_extract_fields_mode23(buf)
            self.decrypted_button = f["button"]
            self.decrypted_serial = f["serial"]
            self.decrypted_counter = f["counter"]
            self.decrypted_crc = f["crc"]
            self.decrypted_type = 0x23
            self.decrypted_seed = f["serial"]
            return True
        return False

    def _decrypt_fast(self):
        if self._direct_xor_decrypt():
            self.mode_serialize = 0x23
            self.decrypted = 0x50
        else:
            self.decrypted = 0x00
            self.mode_serialize = 0x36

    def _fire_if_new(self):
        dup = (self.key1_low == self.last_key1_low and
               self.key1_high == self.last_key1_high)
        if not dup:
            self.last_key1_low = self.key1_low
            self.last_key1_high = self.key1_high
            data = ((self.key1_high << 32) | self.key1_low) & MASK64
            self._decrypt_fast()
            fields = {"data": data, "bits": 64, "serial": data}
            if self.decrypted == 0x50:
                fields["serial"] = self.decrypted_serial
                fields["btn"] = self.decrypted_button
                fields["cnt"] = self.decrypted_counter
                fields["type"] = self.decrypted_type
            self.emit(fields)

    def _packet_complete_s2(self):
        self.validation_field = self.decode_data_low & 0xFFFF
        self.key2_low = self.decode_data_low
        self.key2_high = self.decode_data_high
        self.mode_serialize = 1
        self.status_flag = 0x80
        self._decrypt_fast()
        if self.decrypted != 0x50 and (self.validation_field & 0xF) != _PSA2_VALID_NIBBLE:
            self.decode_data_low = 0
            self.decode_data_high = 0
            self.decode_count_bit = 0
            self.state = 0
            return
        self._fire_if_new()
        self.decode_data_low = 0
        self.decode_data_high = 0
        self.decode_count_bit = 0
        self.state = 0

    def feed(self, level, duration):
        te_s = 250
        te_l = 500
        tol = 100
        te_hs = _PSA2_TE_SHORT_HALF
        te_hl = _PSA2_TE_LONG_HALF
        tol_h = 50
        st = self.state

        if st == 0:
            if not level:
                return
            self.decode_data_low = 0
            self.decode_data_high = 0
            self.pattern_counter = 0
            self.decode_count_bit = 0
            self.mode_serialize = 0
            self.prev_duration = duration
            self.man.reset()
            if _psa2_near(duration, te_s, tol):
                self.state = 1
            elif _psa2_near(duration, te_hs, tol_h):
                self.state = 3
            # break -> tail

        elif st == 1:
            if level:
                return
            if _psa2_near(duration, te_s, tol):
                if _psa2_near(self.prev_duration, te_s, tol):
                    self.pattern_counter += 1
                self.prev_duration = duration
                return
            if _psa2_near(duration, te_l, tol):
                if self.pattern_counter > 0x46:
                    self.decode_data_low = 0
                    self.decode_data_high = 0
                    self.decode_count_bit = 0
                    self.man.reset()
                    self.state = 2
                self.pattern_counter = 0
                self.prev_duration = duration
                return
            self.state = 0
            self.pattern_counter = 0
            # break -> tail

        elif st == 2:
            if self.decode_count_bit >= _PSA_MAX_BITS:
                self.state = 0
                self.prev_duration = duration
                return
            if level and self.decode_count_bit == _PSA_KEY2_BITS:
                if _psa2_near(duration, 1000, 199):
                    self._packet_complete_s2()
                    return
            process = False
            if _psa2_near(duration, te_s, tol):
                mc_event = ((int(level) ^ 1) & 0x7F) << 1
                process = True
            elif _psa2_near(duration, te_l, tol):
                mc_event = 4 if level else 6
                process = True
            else:
                if (not level) and _psa2_near(duration, 1000, 199):
                    if self.decode_count_bit == _PSA_KEY2_BITS and \
                       (self.validation_field & 0xF) == _PSA2_VALID_NIBBLE:
                        self._packet_complete_s2()
                        return
                return
            if process and self.decode_count_bit < _PSA_KEY2_BITS:
                decoded, bit = _man_adv(self.man, mc_event)
                if decoded:
                    carry = (self.decode_data_low >> 31) & 1
                    self.decode_data_low = ((self.decode_data_low << 1) | (1 if bit else 0)) & M32
                    self.decode_data_high = ((self.decode_data_high << 1) | carry) & M32
                    self.decode_count_bit += 1
                    if self.decode_count_bit == _PSA_KEY1_BITS:
                        self.key1_low = self.decode_data_low
                        self.key1_high = self.decode_data_high
                        self.decode_data_low = 0
                        self.decode_data_high = 0
            if not level:
                return
            # break -> tail

        elif st == 3:
            if level:
                return
            if _psa2_near(duration, te_hs, tol_h):
                if _psa2_near(self.prev_duration, te_hs, tol_h):
                    self.pattern_counter += 1
                else:
                    self.pattern_counter = 0
                self.prev_duration = duration
                return
            if te_hl <= duration < 0x12C:
                if self.pattern_counter > 0x45:
                    self.decode_data_low = 0
                    self.decode_data_high = 0
                    self.decode_count_bit = 0
                    self.man.reset()
                    self.state = 4
                self.pattern_counter = 0
                self.prev_duration = duration
                return
            self.state = 0
            # break -> tail

        elif st == 4:
            if self.decode_count_bit >= _PSA_MAX_BITS:
                self.state = 0
                self.prev_duration = duration
                return
            if not level:
                if _psa2_near(duration, te_hs, tol_h):
                    mc_event = ((int(level) ^ 1) & 0x7F) << 1
                elif te_hl <= duration < 0x12C:
                    mc_event = 4 if level else 6
                else:
                    return
                decoded, bit = _man_adv(self.man, mc_event)
                if decoded:
                    carry = (self.decode_data_low >> 31) & 1
                    self.decode_data_low = ((self.decode_data_low << 1) | (1 if bit else 0)) & M32
                    self.decode_data_high = ((self.decode_data_high << 1) | carry) & M32
                    self.decode_count_bit += 1
                    if self.decode_count_bit == _PSA_KEY1_BITS:
                        self.key1_low = self.decode_data_low
                        self.key1_high = self.decode_data_high
                        self.decode_data_low = 0
                        self.decode_data_high = 0
            else:
                if _psa2_near(duration, 500, 99):
                    if self.decode_count_bit != _PSA_KEY2_BITS:
                        return
                    self.validation_field = self.decode_data_low & 0xFFFF
                    self.key2_low = self.decode_data_low
                    self.key2_high = self.decode_data_high
                    self.mode_serialize = 2
                    self.status_flag = 0x80
                    self._decrypt_fast()
                    if self.decrypted != 0x50 and \
                       (self.validation_field & 0xF) != _PSA2_VALID_NIBBLE:
                        self.decode_data_low = 0
                        self.decode_data_high = 0
                        self.decode_count_bit = 0
                        self.state = 0
                        return
                    self._fire_if_new()
                    self.decode_data_low = 0
                    self.decode_data_high = 0
                    self.decode_count_bit = 0
                    self.state = 0
                    return
            # break -> tail

        self.prev_duration = duration


# ============================================================================
# Hitag2  (hitag2.c)   NAME = "Hitag2"
# ============================================================================
# NXP PCF7946/7947 RKE. te_short=240 te_long=480. feed() buffers raw signed
# pulse durations of a burst (gap >= 1500us ends a burst), then
# process_burst(): per-burst te_short histogram estimate -> stateless
# G.E.-Thomas Manchester demod (both polarities) -> find_and_emit scans every
# start cs for marker 0x0001, the fixed 0-bit at cs+79, the 10-byte XOR-parity
# checksum, and a non-degenerate UID.  Fields are relative to cs.
# generic.data = UID:32|btn:4|lctr:10|ks_hi:18 (64b); 103-bit frame.

_HITAG2_DATA_BITS = 103
_HITAG2_ALL_BITS_MAX = 400
_HITAG2_MIN_BITS = 88
_HITAG2_MAXDUR = 700
_HITAG2_GAP = 1500
_HITAG2_MIN_PULSE = 30
_HITAG2_TE_MIN = 70
_HITAG2_TE_MAX = 320


def _hitag2_bits_to_int(bits, lo, length):
    v = 0
    for i in range(length):
        v = (v << 1) | (bits[lo + i] & 1)
    return v & M32


def hitag2_estimate_te(durs, count):
    bins = (_HITAG2_TE_MAX - _HITAG2_TE_MIN) // 10 + 1
    hist = [0] * bins
    for i in range(count):
        d = -durs[i] if durs[i] < 0 else durs[i]
        if d < _HITAG2_TE_MIN or d > _HITAG2_TE_MAX:
            continue
        hist[(d - _HITAG2_TE_MIN) // 10] += 1
    best = 0
    for i in range(1, bins):
        if hist[i] > hist[best]:
            best = i
    return _HITAG2_TE_MIN + best * 10 + 5


def hitag2_demod(durs, count, te_short, polarity, out):
    """Fills list `out` with decoded bits; returns bit count n."""
    te_long = 2 * te_short
    delta = te_short * 45 // 100
    if delta < 40:
        delta = 40
    n = 0
    half = False
    for i in range(count):
        if n >= _HITAG2_ALL_BITS_MAX - 1:
            break
        level = durs[i] > 0
        d = (-durs[i]) if durs[i] < 0 else durs[i]
        is_long = diff(d, te_long) <= delta
        is_short = diff(d, te_short) <= delta
        bl = (1 if level else 0) if polarity else (0 if level else 1)
        if is_long:
            out[n] = bl
            n += 1
            half = False
        elif is_short:
            if half:
                out[n] = bl
                n += 1
                half = False
            else:
                half = True
        else:
            half = False
    return n


class Hitag2(ProtoDecoder):
    NAME = "Hitag2"
    CONST = Const(240, 480, 120, _HITAG2_MIN_BITS)

    def __init__(self):
        super().__init__()
        self._init_state()

    def _init_state(self):
        self.durs = [0] * _HITAG2_MAXDUR
        self.dur_count = 0
        self.all_bits = [0] * _HITAG2_ALL_BITS_MAX
        self.uid = 0
        self.btn = 0
        self.lctr = 0
        self.ks = 0
        self.chk = 0

    def reset(self):
        super().reset()
        self.dur_count = 0

    def _find_and_emit(self, n):
        b = self.all_bits
        if n < _HITAG2_MIN_BITS:
            return False
        cs = 0
        while cs + 88 <= n:
            if b[cs + 79] != 0:
                cs += 1
                continue
            if cs < 15:
                cs += 1
                continue
            marker = _hitag2_bits_to_int(b, cs - 15, 16) & 0xFFFF
            if marker != 0x0001:
                cs += 1
                continue
            x = 0
            for i in range(cs, cs + 80, 8):
                x ^= _hitag2_bits_to_int(b, i, 8) & 0xFF
            chk = _hitag2_bits_to_int(b, cs + 80, 8) & 0xFF
            if x != chk:
                cs += 1
                continue
            uid = _hitag2_bits_to_int(b, cs + 1, 32)
            if uid == 0 or uid == 0xFFFFFFFF:
                cs += 1
                continue
            self.uid = uid
            self.btn = _hitag2_bits_to_int(b, cs + 33, 4) & 0xF
            self.lctr = _hitag2_bits_to_int(b, cs + 37, 10) & 0x3FF
            self.ks = _hitag2_bits_to_int(b, cs + 47, 32)
            self.chk = chk
            if (self.lctr == 0 and self.ks == 0) or \
               (self.lctr == 0x3FF and self.ks == 0xFFFFFFFF):
                cs += 1
                continue
            data = (((self.uid << 32) |
                     ((self.btn & 0xF) << 28) |
                     ((self.lctr & 0x3FF) << 18) |
                     (self.ks >> 14)) & MASK64)
            self.emit({"serial": self.uid, "btn": self.btn, "cnt": self.lctr,
                       "ks": self.ks, "chk": self.chk,
                       "data": data, "bits": _HITAG2_DATA_BITS})
            return True
        return False

    def _process_burst(self):
        if self.dur_count < _HITAG2_MIN_BITS:
            return
        te = hitag2_estimate_te(self.durs, self.dur_count)
        for pol in range(2):
            n = hitag2_demod(self.durs, self.dur_count, te, pol != 0, self.all_bits)
            if self._find_and_emit(n):
                return

    def feed(self, level, duration):
        if duration >= _HITAG2_GAP:
            self._process_burst()
            self.dur_count = 0
            return
        if duration < _HITAG2_MIN_PULSE:
            return
        if self.dur_count >= _HITAG2_MAXDUR:
            self._process_burst()
            self.dur_count = 0
        v = int(duration)
        if v > 32000:
            v = 32000
        self.durs[self.dur_count] = v if level else -v
        self.dur_count += 1


# ============================================================================
# Urmet  (urmet.c)   NAME = "Urmet"
# ============================================================================
# te=500 (PPM @ 2000 baud).  Lock sequence: a Manchester block (>=16 short
# 40..280us edges) -> ~34ms inter-block gap -> PPM preamble H1 L1 H1 L11 ->
# data: each symbol is H~1te + a variable low gap of N te.  bit_buf records
# 1 then N zeros per symbol; rx_gaps records N.  On the trailing end-gap
# (>=20000us) finish() decodes inverted USART 8N1 (idle low / start high /
# LSB-first / wire-inverted data), accepts only the 5/7/D/F nibble alphabet
# with 16..40 bytes and >=2 gaps.  generic.data = first <=8 bytes big-endian.

_URMET_TE = 500
_URMET_TE_DELTA = 150
_URMET_SYNC_TE = 11
_URMET_SYNC_DELTA_TE = 3
_URMET_END_GAP_US = 20000
_URMET_MAN_EDGE_MIN_US = 40
_URMET_MAN_EDGE_MAX_US = 280
_URMET_MAN_MIN_EDGES = 16
_URMET_MAN_GAP_MAX_US = 60000
_URMET_MIN_BYTES = 16
_URMET_MAX_BYTES = 40
_URMET_MAX_GAPS = 192
_URMET_BITBUF = 512

# steps
_U_RESET = 0
_U_MAN = 1
_U_ARMED = 2
_U_PRE_L1 = 3
_U_PRE_H2 = 4
_U_PRE_SYNC = 5
_U_DATA = 6


def urmet_is_manchester_edge(duration):
    return _URMET_MAN_EDGE_MIN_US <= duration <= _URMET_MAN_EDGE_MAX_US


def urmet_is_te1(duration):
    return diff(duration, _URMET_TE) < _URMET_TE_DELTA


def urmet_is_sync_low(duration):
    return diff(duration, _URMET_TE * _URMET_SYNC_TE) < _URMET_TE * _URMET_SYNC_DELTA_TE


def urmet_te_count(duration):
    n = (duration + _URMET_TE // 2) // _URMET_TE
    if n < 1:
        n = 1
    if n > 20:
        n = 20
    return n


def urmet_bytes_to_key(bytes_, count):
    key = 0
    n = count if count < 8 else 8
    for i in range(n):
        key = (key << 8) | (bytes_[i] & 0xFF)
    if n < 8:
        key <<= ((8 - n) * 8)
    return key & MASK64


def urmet_nibble_ok(b):
    hi = (b >> 4) & 0x0F
    lo = b & 0x0F
    return (hi in (0x5, 0x7, 0xD, 0xF)) and (lo in (0x5, 0x7, 0xD, 0xF))


def urmet_payload_plausible(bytes_, count):
    if count < _URMET_MIN_BYTES or count > _URMET_MAX_BYTES:
        return False
    for i in range(count):
        if not urmet_nibble_ok(bytes_[i]):
            return False
    return True


def urmet_decode_inv_usart(bits, bit_cnt, out, out_max):
    """Decode inverted USART 8N1. Returns byte count n (fills out list)."""
    n = 0
    i = 0
    while i < bit_cnt and n < out_max:
        if bits[i] != 1:
            i += 1
            continue
        if i > 0 and bits[i - 1] != 0:
            i += 1
            continue
        if (i + 9) >= bit_cnt:
            break
        val = 0
        for bb in range(8):
            wire = bits[i + 1 + bb]
            val |= ((0 if wire else 1) << bb)
        if bits[i + 9] != 0:
            i += 1
            continue
        out[n] = val & 0xFF
        n += 1
        i += 10
        while i < bit_cnt and bits[i] == 0:
            i += 1
    return n


def urmet_append_bits(bit_buf, bit_cnt, level, n):
    """Returns new bit_cnt."""
    i = 0
    while i < n and bit_cnt < _URMET_BITBUF:
        bit_buf[bit_cnt] = 1 if level else 0
        bit_cnt += 1
        i += 1
    return bit_cnt


def urmet_append_gap(gaps, gap_count, gap_te):
    """Returns (ok, new_gap_count)."""
    if gap_count >= _URMET_MAX_GAPS:
        return False, gap_count
    gaps[gap_count] = gap_te
    return True, gap_count + 1


class Urmet(ProtoDecoder):
    NAME = "Urmet"
    CONST = Const(500, 1000, 150, _URMET_MIN_BYTES * 8)

    def __init__(self):
        super().__init__()
        self._init_state()

    def _init_state(self):
        self.step = _U_RESET
        self.pending_high = False
        self.man_edges = 0
        self.bit_cnt = 0
        self.bit_buf = [0] * _URMET_BITBUF
        self.rx_gap_count = 0
        self.rx_gaps = [0] * _URMET_MAX_GAPS
        self.gap_count = 0
        self.gaps = [0] * _URMET_MAX_GAPS
        self.raw_bytes = [0] * _URMET_MAX_BYTES
        self.byte_count = 0

    def reset(self):
        super().reset()
        self.step = _U_RESET
        self.pending_high = False
        self.man_edges = 0
        self.bit_cnt = 0
        # keep last decoded raw_bytes / gaps

    def _abort(self):
        self.step = _U_RESET
        self.pending_high = False
        self.man_edges = 0
        self.bit_cnt = 0
        self.rx_gap_count = 0

    def _finish(self):
        decoded = [0] * _URMET_MAX_BYTES
        n = urmet_decode_inv_usart(self.bit_buf, self.bit_cnt, decoded, _URMET_MAX_BYTES)
        if urmet_payload_plausible(decoded, n) and self.rx_gap_count >= 2:
            self.raw_bytes[:n] = decoded[:n]
            self.byte_count = n
            self.gaps[:self.rx_gap_count] = self.rx_gaps[:self.rx_gap_count]
            self.gap_count = self.rx_gap_count
            data = urmet_bytes_to_key(decoded, n)
            self.emit({"serial": data, "data": data, "bits": n * 8,
                       "byte_count": n, "gap_count": self.gap_count,
                       "raw": bytes(decoded[:n])})
        self._abort()

    def feed(self, level, duration):
        if (not level) and duration >= _URMET_END_GAP_US:
            if self.step == _U_DATA:
                if self.bit_cnt < _URMET_BITBUF:
                    self.bit_buf[self.bit_cnt] = 0
                    self.bit_cnt += 1
                self._finish()
            elif (self.step == _U_MAN and self.man_edges >= _URMET_MAN_MIN_EDGES and
                  duration <= _URMET_MAN_GAP_MAX_US):
                self.step = _U_ARMED
                self.man_edges = 0
                self.pending_high = False
            else:
                self._abort()
            return

        step = self.step
        if step == _U_RESET:
            if urmet_is_manchester_edge(duration):
                self.step = _U_MAN
                self.man_edges = 1

        elif step == _U_MAN:
            if urmet_is_manchester_edge(duration):
                if self.man_edges < 0xFFF0:
                    self.man_edges += 1
            elif urmet_is_te1(duration):
                self._abort()
            else:
                if _URMET_MAN_EDGE_MAX_US * 2 < duration < _URMET_END_GAP_US:
                    self._abort()

        elif step == _U_ARMED:
            if level and urmet_is_te1(duration):
                self.step = _U_PRE_L1
            elif urmet_is_manchester_edge(duration):
                self.step = _U_MAN
                self.man_edges = 1

        elif step == _U_PRE_L1:
            if (not level) and urmet_is_te1(duration):
                self.step = _U_PRE_H2
            elif level and urmet_is_te1(duration):
                self.step = _U_PRE_L1
            else:
                self._abort()

        elif step == _U_PRE_H2:
            if level and urmet_is_te1(duration):
                self.step = _U_PRE_SYNC
            else:
                self._abort()

        elif step == _U_PRE_SYNC:
            if (not level) and urmet_is_sync_low(duration):
                self.bit_cnt = 0
                self.rx_gap_count = 0
                self.pending_high = False
                self.bit_cnt = urmet_append_bits(self.bit_buf, self.bit_cnt, True, 1)
                self.bit_cnt = urmet_append_bits(self.bit_buf, self.bit_cnt, False, 1)
                self.bit_cnt = urmet_append_bits(self.bit_buf, self.bit_cnt, True, 1)
                self.bit_cnt = urmet_append_bits(self.bit_buf, self.bit_cnt, False, _URMET_SYNC_TE)
                _, self.rx_gap_count = urmet_append_gap(self.rx_gaps, self.rx_gap_count, 1)
                _, self.rx_gap_count = urmet_append_gap(self.rx_gaps, self.rx_gap_count, _URMET_SYNC_TE)
                self.step = _U_DATA
            elif level and urmet_is_te1(duration):
                self.step = _U_PRE_L1
            else:
                self._abort()

        elif step == _U_DATA:
            if level:
                if (not urmet_is_te1(duration)) or self.pending_high:
                    self._abort()
                else:
                    self.pending_high = True
                return
            if not self.pending_high:
                if duration > _URMET_TE * 2:
                    self._abort()
                return
            self.pending_high = False
            gap = urmet_te_count(duration)
            if gap < 1 or gap > 15:
                self._abort()
                return
            ok, self.rx_gap_count = urmet_append_gap(self.rx_gaps, self.rx_gap_count, gap)
            if not ok:
                self._finish()
                return
            self.bit_cnt = urmet_append_bits(self.bit_buf, self.bit_cnt, True, 1)
            self.bit_cnt = urmet_append_bits(self.bit_buf, self.bit_cnt, False, gap)
            if self.bit_cnt >= _URMET_BITBUF or self.rx_gap_count >= _URMET_MAX_GAPS:
                self._finish()

        else:
            self._abort()


ALL = [Came, NiceFlo, GateTx, Ansonic, Holtek, HoltekHt12x, Doitrand,
       Linear, Clemsa, SMC5326, Doorbell32, Princeton,
       Feron, Roger, NordIce, GangQi, Hay21, Elplast, Treadmill37,
       Bett, Hollarm, Marantec24, Mastercode, KeyFinder, IDo, HormannHSM, Legrand,
       X10, Magellan, TelcomaEdge, PowerSmart, CardinS508, SomfyTelis, CameTwee, IntertechnoV3,
       MegaCode, ChamberlainCode, Prastel, CameAtomo, NiceFlorS,
       Marantec, KeyFinder2, Dooya, NeroSketch, PhoenixV2, ReversRB2,
       DickertMAHS, AllstarFirefly, SecPlusV1, SomfyKeytis, KingGatesStylo4k, DitecGOL4,
       Jarolift, FaacSLH, Mic6sc2, BenincaARC,
       Honeywell, HoneywellWDB, LinearDelta3,
       NeroRadio, SecPlusV2, StarLine, HormannBiSecur, AlutechAT4N,
       Suzuki, Audi, OpelV0, OpelV1, PeugotV0, BmwCas3, BmwCas4, KiaV2, MitsubishiV0a, NidecRke,
       SiemensVdo, CodeAlarm, AlpsRke, HyundaiKiaRio, SantaFe, MazdaInfinity,
       Hundai, Audiovox, Astrostart, Compustar, ContinentalRke, TeslaCharge,
       Toyota, Marelli, FiatV1, FiatV2, FiatMulti,
       PsaGroup, PsaOld, Hitag2, Urmet]
