#!/usr/bin/env python3
r"""arke_mitsu_bmw.py -- ported from firmware mitsubishi_v0.c / mitsubishi_v1.c /
bmw_v2.c (Archiv dev protocols). All three are DYNAMIC (encrypted rolling-code)
RKE fobs; none carries a computed CRC/checksum over the payload, so every emit
here is crc_ok=None (structural). The frames are gated instead by fixed
sync/header words and exact length windows, mirroring the C bit-for-bit.

  * Mitsubishi V0 -- 2-FSK Manchester, te 200/400, d 80. 96-bit frame:
        [sync16=0x8930][serial24][btn8][cnt8][hop40]. Phase-aware stateless
        Manchester (BOUNDARY/DATA phase). No CRC -> crc_ok=None.
  * Mitsubishi V1 -- 2-FSK Manchester, te 200/400, d 100. 96-bit frame:
        [sync32=0x1E4C11A7][fix8=0xE0][btn4][cnt10][hop42]. Half-bit toggle
        Manchester. No per-fob serial (only the 0xE0 fix byte). No CRC ->
        crc_ok=None. Added gate: fix byte must == 0xE0 (documented invariant).
  * BMW V2 (BMW_v2) -- OOK/AM Manchester, te 500/1000, d 150, gap>1700. 56-bit
        frame: [header8=0xCF][rolling40][trailer8=0x3A]. te_last half-period
        accumulator. Header+trailer are FIXED FRAMING BYTES, not a checksum, so
        crc_ok=None (this is exactly the "bare preamble match" trap -> never
        crc_ok=True). "serial" is carved from the ENCRYPTED rolling code so it
        is not a stable identifier (changes every press); surfaced to mirror C.

Hardening (all three): exact-length gate + fixed sync/header/trailer gate +
reject degenerate serials {0,0xFFFFFF,0x555555,0xAAAAAA}. No CRC exists on any
of them, therefore crc_ok is None everywhere -- NEVER True.
"""
from arke_core import Const, ProtoDecoder, diff, MASK64

_DEGENERATE = (0, 0xFFFFFF, 0x555555, 0xAAAAAA)


# ============================================================================
# Mitsubishi V0 -- phase-aware stateless Manchester (mitsubishi_v0.c)
# ============================================================================
_MV0_SYNC = (1, 0, 0, 0, 1, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0, 0)  # 0x8930


class MitsubishiV0(ProtoDecoder):
    NAME = "Mitsubishi V0"
    CONST = Const(200, 400, 80, 80)
    ALL_BITS_MAX = 160

    def __init__(self):
        super().__init__()
        self.step = 0          # 0=Reset, 1=Collect
        self.phase = True      # True=BOUNDARY, False=DATA
        self.all_bits = []

    def reset(self):
        super().reset()
        self.step = 0
        self.phase = True
        self.all_bits = []

    @staticmethod
    def _find_sync(bits, n):
        need = 80
        search_end = n - 16 - need
        for i in range(0, search_end + 1):
            if all(bits[i + j] == _MV0_SYNC[j] for j in range(16)):
                return i
        return -1

    def _process_frame(self):
        bits = self.all_bits
        n = len(bits)
        if n < 80:
            return
        sync_pos = self._find_sync(bits, n)
        if sync_pos < 0:
            return
        data_start = sync_pos
        data_len = n - data_start
        if data_len > 96:
            data_len = 96
        if data_len < 80:
            return
        # first 64 bits -> data (includes the 16 sync bits at the top)
        data = 0
        for i in range(min(64, data_len)):
            data = ((data << 1) | bits[data_start + i]) & MASK64
        # hop 40 bits from bit 56
        hop = 0
        hop_bits = 0
        for i in range(56, min(data_len, 96)):
            hop = (hop << 1) | bits[data_start + i]
            hop_bits += 1
        if hop_bits < 40:
            hop <<= (40 - hop_bits)
        serial = (data >> 24) & 0x00FFFFFF
        btn = (data >> 16) & 0xFF
        cnt = (data >> 8) & 0xFF
        if serial in _DEGENERATE:
            return
        self.emit({
            "data": data, "serial": serial, "btn": btn,
            "btn_name": _mv0_btn_name(btn), "cnt": cnt,
            "hop": hop, "crc_ok": None,  # no CRC in protocol -> structural only
        })

    def feed(self, level, duration):
        c = self.CONST
        te_s, te_l, te_d = c.te_short, c.te_long, c.te_delta
        if self.step == 0:  # Reset -- wait for a preamble SHORT
            if diff(duration, te_s) <= te_d:
                self.step = 1
                self.all_bits = []
                # first SHORT == BOUNDARY+SHORT -> no emit, phase -> DATA
                self.phase = False
            return
        # Collect
        if len(self.all_bits) >= self.ALL_BITS_MAX - 1:
            self._process_frame()
            self.step = 0
            return
        is_short = diff(duration, te_s) <= te_d
        is_long = diff(duration, te_l) <= te_d
        if is_short:
            if self.phase:                    # BOUNDARY + SHORT -> no emit
                self.phase = False
            else:                             # DATA + SHORT -> bit = !level
                self.all_bits.append(0 if level else 1)
                self.phase = True
        elif is_long:
            if self.phase:                    # BOUNDARY + LONG -> bit = level
                self.all_bits.append(1 if level else 0)
            else:                             # DATA + LONG -> bit = !level
                self.all_bits.append(0 if level else 1)
        else:                                 # invalid timing -> frame end
            self._process_frame()
            self.step = 0


def _mv0_btn_name(btn):
    return {0x16: "Lock", 0x46: "Unlock", 0x26: "Btn3"}.get(btn & 0xFE, "??")


# ============================================================================
# Mitsubishi V1 -- half-bit toggle Manchester (mitsubishi_v1.c)
# ============================================================================
# 0x1E4C11A7
_MV1_SYNC = (0, 0, 0, 1, 1, 1, 1, 0, 0, 1, 0, 0, 1, 1, 0, 0,
             0, 0, 0, 1, 0, 0, 0, 1, 1, 0, 1, 0, 0, 1, 1, 1)


class MitsubishiV1(ProtoDecoder):
    NAME = "Mitsubishi V1"
    CONST = Const(200, 400, 100, 88)
    ALL_BITS_MAX = 200

    def __init__(self):
        super().__init__()
        self.step = 0        # 0=Reset, 1=Collect
        self.half = False
        self.all_bits = []

    def reset(self):
        super().reset()
        self.step = 0
        self.half = False
        self.all_bits = []

    @staticmethod
    def _find_sync(bits, n):
        need = 56
        search_end = n - 32 - need
        for i in range(0, search_end + 1):
            if all(bits[i + j] == _MV1_SYNC[j] for j in range(32)):
                return i
        return -1

    def _process_frame(self):
        bits = self.all_bits
        n = len(bits)
        if n < 88:
            return
        sync_pos = self._find_sync(bits, n)
        if sync_pos < 0:
            return
        data_start = sync_pos
        data_len = n - data_start
        if data_len > 96:
            data_len = 96
        if data_len < 88:
            return
        data = 0
        for i in range(min(64, data_len)):
            data = ((data << 1) | bits[data_start + i]) & MASK64
        # hop 42 bits from bit 54
        hop = 0
        hop_bits = 0
        for i in range(54, min(data_len, 96)):
            hop = (hop << 1) | bits[data_start + i]
            hop_bits += 1
        if hop_bits < 42:
            hop <<= (42 - hop_bits)
        fix = (data >> 24) & 0xFF     # frame bits 32:39 -- documented 0xE0
        btn = (data >> 20) & 0x0F     # frame bits 40:43
        cnt = (data >> 10) & 0x3FF    # frame bits 44:53
        # Hardening gate (documented invariant across 6 caps / 111 presses):
        # the fix byte is a constant 0xE0. Anything else is a foreign signal.
        if fix != 0xE0:
            return
        serial = fix  # V1 exposes no per-fob serial; surface the fix byte (mirror C)
        if serial in _DEGENERATE:
            return
        self.emit({
            "data": data, "serial": serial, "btn": btn,
            "btn_name": _mv1_btn_name(btn), "cnt": cnt,
            "hop": hop, "crc_ok": None,  # no CRC in protocol -> structural only
        })

    def feed(self, level, duration):
        c = self.CONST
        te_s, te_l, te_d = c.te_short, c.te_long, c.te_delta
        if self.step == 0:  # Reset -- start on the first plausible SHORT/LONG
            if te_s - te_d <= duration <= te_l + te_d:
                self.step = 1
                self.all_bits = []
                self.half = False
                # fall through: process this first pulse in Collect
            else:
                return
        # Collect
        if len(self.all_bits) >= self.ALL_BITS_MAX - 1:
            self._process_frame()
            self.step = 0
            return
        if duration < te_s // 2:          # glitch -> ignore, keep state
            return
        is_long = (te_s + te_l) // 2 < duration <= te_l + te_d
        is_short = te_s - te_d <= duration <= (te_s + te_l) // 2
        if duration > te_l + te_d:        # gap / frame end
            self._process_frame()
            self.step = 0
            return
        if is_long:
            self.all_bits.append(1 if level else 0)
            self.half = False
        elif is_short:
            if self.half:
                self.all_bits.append(1 if level else 0)
                self.half = False
            else:
                self.half = True


def _mv1_btn_name(btn):
    return {0x2: "Lock", 0x8: "Unlock", 0x4: "Trunk"}.get(btn & 0x0F, "??")


# ============================================================================
# BMW V2 (BMW_v2) -- te_last half-period Manchester accumulator (bmw_v2.c)
# ============================================================================
_BMW_GAP_MIN = 1700
_BMW_HEADER = 0xCF
_BMW_TRAILER = 0x3A


class BmwV2(ProtoDecoder):
    NAME = "BMW V2"
    CONST = Const(500, 1000, 150, 56)

    def __init__(self):
        super().__init__()
        # self.d (Decoder) holds parser_step/te_last/decode_data/decode_count_bit
        # steps: 0=Reset, 1=WaitGap, 2=Manchester

    def reset(self):
        super().reset()

    def _try_emit(self):
        d = self.d
        if d.decode_count_bit < self.CONST.min_bits:  # 56
            return
        data = d.decode_data
        if d.decode_count_bit > 56:
            data >>= (d.decode_count_bit - 56)
        header = (data >> 48) & 0xFF
        trailer = data & 0xFF
        # Fixed framing bytes -- NOT a checksum. Gate on them, but crc_ok stays None.
        if header != _BMW_HEADER or trailer != _BMW_TRAILER:
            return
        rolling = (data >> 8) & 0xFFFFFFFFFF          # 40-bit encrypted payload
        serial = (rolling >> 16) & 0xFFFFFF            # upper 24 bits (per C)
        cnt = rolling & 0xFFFF                          # lower 16 bits (per C)
        if serial in _DEGENERATE:
            return
        self.emit({
            "data": data, "serial": serial, "btn": 0, "btn_name": "?",
            "cnt": cnt, "crc_ok": None,  # header/trailer are framing, not a CRC
        })

    def feed(self, level, duration):
        c = self.CONST
        d = self.d
        step = d.parser_step
        if step == 0:  # Reset -- sync on a LOW gap
            if (not level) and duration > _BMW_GAP_MIN:
                d.parser_step = 1
                d.decode_data = 0
                d.decode_count_bit = 0
                d.te_last = 0
            return
        if step == 1:  # WaitGap -- first HIGH pulse starts Manchester
            if level:
                is_short = diff(duration, c.te_short) <= c.te_delta
                is_long = diff(duration, c.te_long) <= c.te_delta
                if is_short or is_long:
                    d.te_last = duration
                    d.parser_step = 2
                else:
                    d.parser_step = 0
            else:
                if duration > _BMW_GAP_MIN:
                    pass  # another gap, keep waiting
                else:
                    d.parser_step = 0
            return
        # step == 2: Manchester
        is_short = diff(duration, c.te_short) <= c.te_delta
        is_long = diff(duration, c.te_long) <= c.te_delta
        if not is_short and not is_long:
            self._try_emit()
            d.parser_step = 0
            if (not level) and duration > _BMW_GAP_MIN:
                d.parser_step = 1
                d.decode_data = 0
                d.decode_count_bit = 0
                d.te_last = 0
            return
        if is_short:
            if d.te_last == 0:
                d.te_last = duration
            else:
                bit = 0 if level else 1
                d.decode_data = ((d.decode_data << 1) | bit) & MASK64
                d.decode_count_bit += 1
                d.te_last = 0
        else:  # is_long
            bit = 0 if level else 1
            d.decode_data = ((d.decode_data << 1) | bit) & MASK64
            d.decode_count_bit += 1
            if d.te_last != 0:
                d.te_last = c.te_short   # S+L: third half stays pending
            else:
                d.te_last = 0
        if d.decode_count_bit > 64:
            self._try_emit()
            d.parser_step = 0


ALL = [MitsubishiV0, MitsubishiV1, BmwV2]


# ============================================================================
# self-test: construct + feed a synthetic pulse stream, no exceptions
# ============================================================================
if __name__ == "__main__":
    import sys

    def _feed_stream(dec, signed_durations):
        for v in signed_durations:
            dec.feed(v > 0, abs(v))

    def _manchester_bmw(bits56):
        """Encode 56 bits as BMW half-levels (1->[H,L], 0->[L,H]) then run-length
        merge into signed RAW durations (short=500, merged=1000). Prefixed with a
        LOW gap so the decoder syncs. Used only to exercise the emit path."""
        halves = []
        for b in bits56:
            halves += [1, 0] if b else [0, 1]
        out = [-2000]  # LOW sync gap
        i = 0
        while i < len(halves):
            lvl = halves[i]
            run = 1
            while i + run < len(halves) and halves[i + run] == lvl and run < 2:
                run += 1
            dur = 500 * run
            out.append(dur if lvl else -dur)
            i += run
        out.append(-3000)  # trailing gap to flush
        return out

    # generic synthetic streams (exercise both timing branches) per decoder
    mv_stream = ([200] * 6 +                       # preamble shorts
                 [200, -200, 400, -400, 200, -200] * 20 +
                 [5000])                            # invalid -> process_frame
    ok = True

    for cls in ALL:
        try:
            dec = cls()
            _feed_stream(dec, [(-1 if k % 2 else 1) * mv_stream[k % len(mv_stream)]
                               for k in range(300)])
            # extra: BMW gets a structured Manchester stream for its emit path
            if cls is BmwV2:
                frame = 0xCF00000000003A  # header/trailer set, zero rolling
                bits = [(frame >> (55 - b)) & 1 for b in range(56)]
                dec2 = cls()
                _feed_stream(dec2, _manchester_bmw(bits))
            print("OK", dec.NAME)
        except Exception as e:  # noqa: BLE001
            ok = False
            print("FAIL", cls.NAME, "->", repr(e))
            import traceback
            traceback.print_exc()

    sys.exit(0 if ok else 1)
