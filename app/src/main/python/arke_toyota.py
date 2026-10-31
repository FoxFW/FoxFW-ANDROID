#!/usr/bin/env python3
r"""
arke_toyota.py -- ported Flipper Sub-GHz automotive RAW demodulators for the
Toyota V0 and Lexus V0 protocols, built on arke_core.

Ported 1:1 from the firmware feed() state machines in
    C:\Users\stone\momentum-tembed\lib\subghz\protocols\toyota_v0.c
    C:\Users\stone\momentum-tembed\lib\subghz\protocols\lexus_v0.c

IMPORTANT — validation model (read before trusting a "decode"):
  * Toyota V0 has NO CRC. Its self-validating frame check is a BIT-COMPLEMENT:
    the 88 data bits are re-transmitted inverted 8 bits later, so
    data[i] XOR data[i+96] must be 1 for every i in 0..87. The firmware accepts
    up to 2 bit errors. We treat "complement passes" as crc_ok, and also report
    the exact error count (errors==0 = a perfect frame). The 88-bit payload is a
    fully ENCRYPTED rolling code: there is no cleartext serial/button/counter to
    extract (per the firmware author's own notes), so those fields are None and
    only the raw key is surfaced. crc_ok here means "structurally valid frame".

  * Lexus V0 has NO per-frame CRC AT ALL. It extracts a 32-bit serial and a
    160-bit encrypted rolling code and only rejects all-0/all-1 serials. The one
    available cross-frame validator is the "Toyota complement signature": the
    serial alternates between S and ~S on successive frames. We therefore set
    crc_ok True for a Lexus frame ONLY when a complement-partner serial (~S) is
    also seen in the same capture; a lone/unpaired serial is crc_ok False
    (honest: unverifiable). Note the firmware was authored for 2-FSK @434MHz;
    the captures in this collection are OOK @312-314MHz.
"""
from arke_core import ProtoDecoder, Const


def _within(d, center, delta):
    # mirrors firmware DURATION_DIFF(d, center) <= delta
    return (d - center if d > center else center - d) <= delta


# ============================================================ TOYOTA V0
# OOK Manchester, te_short=750 te_long=1500 delta=250.
# Preamble = >=12 shorts, first long triggers data (long IS first data bit).
# Manchester: one LONG = one bit(=level); two SHORTs = one bit(=2nd short level).
# Frame = 184 bits: [0..87]=data [88..95]=zero-gap [96..183]=complement.
class ToyotaV0(ProtoDecoder):
    NAME = "Toyota V0"
    CONST = Const(750, 1500, 250, 88)
    TE_S, TE_L, TE_D = 750, 1500, 250
    DATA_BITS = 88
    FRAME_BITS = 184        # 88 data + 8 gap + 88 complement
    MIN_PREAMBLE = 12
    COMPL_OFF = 96          # complement bit i lives at i+96

    STEP_RESET, STEP_PRE, STEP_DATA = 0, 1, 2

    def __init__(self):
        super().__init__()
        self._init()

    def _init(self):
        self.step = self.STEP_RESET
        self.preamble_count = 0
        self.half_bit = False
        self.bit_count = 0
        self.all_bits = [0] * (self.FRAME_BITS + 8)

    def reset(self):
        super().reset()
        self._init()

    def _restart(self):
        self.step = self.STEP_RESET
        self.preamble_count = 0

    def _is_short(self, d):
        return _within(d, self.TE_S, self.TE_D)

    def _is_long(self, d):
        return _within(d, self.TE_L, self.TE_D)

    def _check_frame(self):
        if self.bit_count < self.FRAME_BITS:
            return
        b = self.all_bits
        errors = 0
        for i in range(self.DATA_BITS):
            if (b[i] ^ b[i + self.COMPL_OFF]) != 1:
                errors += 1
        if errors > 2:
            return  # firmware rejects >2

        # first 64 bits
        key_hi = 0
        for i in range(64):
            key_hi = (key_hi << 1) | b[i]
        # bits 64..87 (24 bits)
        key_lo = 0
        for i in range(64, self.DATA_BITS):
            key_lo = (key_lo << 1) | b[i]
        full = (key_hi << 24) | key_lo  # 88-bit payload
        data_bytes = [(full >> (80 - 8 * k)) & 0xFF for k in range(11)]

        self.emit({
            "data": full,
            "key": "%022X" % full,
            "bits": self.DATA_BITS,
            "serial": None,          # encrypted rolling code — not separable
            "btn": None,
            "btn_name": "(encrypted)",
            "cnt": None,
            "crc_ok": errors <= 2,
            "errors": errors,
            "b0": data_bytes[0],     # bytes 0/2 vary within a burst (rolling)
            "b2": data_bytes[2],
        })

    def feed(self, level, duration):
        st = self.step
        if st == self.STEP_RESET:
            if self._is_short(duration):
                self.preamble_count = 1
                self.step = self.STEP_PRE
        elif st == self.STEP_PRE:
            if self._is_short(duration):
                self.preamble_count += 1
            elif self._is_long(duration) and self.preamble_count >= self.MIN_PREAMBLE:
                self.step = self.STEP_DATA
                self.bit_count = 0
                self.half_bit = False
                self.all_bits[self.bit_count] = 1 if level else 0
                self.bit_count += 1
            else:
                self._restart()
        elif st == self.STEP_DATA:
            is_s = self._is_short(duration)
            is_l = self._is_long(duration)
            if is_l:
                if self.half_bit:
                    self._check_frame()
                    self._restart()
                else:
                    self.all_bits[self.bit_count] = 1 if level else 0
                    self.bit_count += 1
            elif is_s:
                if self.half_bit:
                    self.all_bits[self.bit_count] = 1 if level else 0
                    self.bit_count += 1
                    self.half_bit = False
                else:
                    self.half_bit = True
            else:
                self._check_frame()
                self._restart()
            if self.bit_count >= self.FRAME_BITS:
                self._check_frame()
                self._restart()
        else:
            self._restart()


# ============================================================ LEXUS V0
# Manchester rolling code. Custom timing bands (firmware constants):
#   preamble pulse in [250,470]; LONG in [500,980]; SHORT < 500; GAP >= 980.
# Preamble >=16 clean pulses, first LONG triggers data (LONG IS first bit).
# Frame = 224 bits: [0..31]=header(discard) [32..191]=160-bit rolling
#                   [192..223]=32-bit serial.
class LexusV0(ProtoDecoder):
    NAME = "Lexus V0"
    CONST = Const(350, 700, 150, 192)
    PRE_LO, PRE_HI = 250, 470
    LONG_LO, LONG_HI = 500, 980
    GAP = 980
    MIN_PRE = 16
    HEADER_BITS = 32
    DATA_BITS = 160
    SERIAL_BITS = 32
    FRAME_BITS = 224
    DATA_BYTES = 20

    STEP_RESET, STEP_PRE, STEP_DATA = 0, 1, 2

    def __init__(self):
        super().__init__()
        self._init()

    def _init(self):
        self.step = self.STEP_RESET
        self.preamble_count = 0
        self.half_bit = False
        self.bit_count = 0
        self.all_bits = [0] * (self.FRAME_BITS + 8)

    def reset(self):
        super().reset()
        self._init()

    def _restart(self):
        self.step = self.STEP_RESET
        self.preamble_count = 0
        self.half_bit = False
        self.bit_count = 0

    def _process_frame(self):
        if self.bit_count < self.FRAME_BITS:
            return
        b = self.all_bits
        serial = 0
        base = self.HEADER_BITS + self.DATA_BITS
        for i in range(self.SERIAL_BITS):
            serial = (serial << 1) | b[base + i]
        if serial in (0x00000000, 0xFFFFFFFF):
            return  # degenerate = false trigger noise

        rolling = []
        for k in range(self.DATA_BYTES):
            v = 0
            for j in range(8):
                v = (v << 1) | b[self.HEADER_BITS + k * 8 + j]
            rolling.append(v)

        data64 = 0
        for k in range(8):
            data64 = (data64 << 8) | rolling[k]

        self.emit({
            "data": data64,
            "serial": serial,
            "serial_hex": "%08X" % serial,
            "btn": None,
            "btn_name": "(encrypted)",
            "cnt": None,
            "bits": self.HEADER_BITS + self.DATA_BITS + self.SERIAL_BITS,
            "rolling": "".join("%02X" % x for x in rolling),
            "crc_ok": False,   # patched to True post-hoc if a ~S partner exists
        })

    def feed(self, level, duration):
        in_pre = self.PRE_LO <= duration <= self.PRE_HI
        is_long = self.LONG_LO <= duration <= self.LONG_HI
        is_short = duration < self.LONG_LO
        st = self.step
        if st == self.STEP_RESET:
            if in_pre:
                self.preamble_count = 1
                self.step = self.STEP_PRE
        elif st == self.STEP_PRE:
            if in_pre:
                self.preamble_count += 1
            elif is_long and self.preamble_count >= self.MIN_PRE:
                self.step = self.STEP_DATA
                self.bit_count = 0
                self.half_bit = False
                self.all_bits[self.bit_count] = 1 if level else 0
                self.bit_count += 1
            else:
                self._restart()
        elif st == self.STEP_DATA:
            if duration >= self.GAP:
                self._process_frame()
                self._restart()
                return
            if is_long:
                if self.half_bit:
                    self._process_frame()
                    self._restart()
                    return
                else:
                    self.all_bits[self.bit_count] = 1 if level else 0
                    self.bit_count += 1
            elif is_short:
                if self.half_bit:
                    self.all_bits[self.bit_count] = 1 if level else 0
                    self.bit_count += 1
                    self.half_bit = False
                else:
                    self.half_bit = True
            if self.bit_count >= self.FRAME_BITS:
                self._process_frame()
                self._restart()
        else:
            self._restart()

    def emit(self, fields):
        # cross-frame complement-signature validation (Toyota/Lexus alternate
        # serial S and ~S). Mark BOTH partners crc_ok once the pair is seen.
        super().emit(fields)
        s = fields.get("serial")
        if s is None:
            return
        comp = (~s) & 0xFFFFFFFF
        for r in self.results:
            if r.get("serial") == comp:
                r["crc_ok"] = True
                fields["crc_ok"] = True


ALL = [ToyotaV0, LexusV0]
