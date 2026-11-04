#!/usr/bin/env python3
r"""arke_renault.py -- ported from firmware renault_v4.c, which actually decodes
the Nissan/xHorse aftermarket UNIVERSAL keyfob (88-bit Manchester, te 125/250).
Recovers serial(32b) + button + an 8-bit VISIBLE counter (+1 per press ->
next-code). Strong self-check: XOR of all 11 bytes == 0xFF, button nibble in
{E,D,B,7}, 2-bit pad == 0b01. Genuine encrypted Renault (v1/v2/v3) only exposes a
16-bit pseudo-serial or nothing, so those aren't ported."""
from arke_core import Const, ProtoDecoder, diff

SYNC_MIN = 800
FRAME_BITS = 88


class RenaultV4(ProtoDecoder):
    NAME = "Renault/xHorse V4"
    CONST = Const(125, 250, 70, 88)

    def __init__(self):
        super().__init__()
        self.synced = False
        self.man = 1
        self.bits = []
        self.last = None

    def reset(self):
        super().reset()
        self.synced = False
        self.man = 1
        self.bits = []
        self.last = None

    def _man_decode(self, r1):
        """returns (produced, bit); mirrors _rv4_manchester_decode."""
        s = self.man
        if r1 == 0:      # short high
            if s in (0, 2): self.man = 1; return (False, 0)
            if s == 1: self.man = 0; return (True, 0)
            if s == 3: self.man = 2; return (True, 0)
        elif r1 == 1:    # short low
            if s in (0, 2): self.man = 3; return (False, 0)
            if s == 1: self.man = 2; return (True, 1)
            if s == 3: self.man = 0; return (True, 1)
        elif r1 == 2:    # long high
            if s in (0, 2): self.man = 0; return (True, 0)
            if s in (1, 3): self.man = 1; return (False, 0)
        elif r1 == 3:    # long low
            if s in (0, 2): self.man = 2; return (True, 1)
            if s in (1, 3): self.man = 3; return (False, 0)
        return (False, 0)

    def _process(self):
        bits = self.bits
        n = len(bits)
        for inv in (0, 1):
            pv = 0 if inv else 1
            pre = 0
            while pre < n and bits[pre] == pv:
                pre += 1
            if pre < 10:
                continue
            ds = pre + 1
            dlen = n - ds
            if dlen < FRAME_BITS or dlen > FRAME_BITS + 4:
                continue
            use = min(dlen, FRAME_BITS)
            frame = bytearray(12)
            for i in range(use):
                if bits[ds + i]:
                    frame[i >> 3] |= (0x80 >> (i % 8))
            xor = 0
            for i in range(11):
                xor ^= frame[i]
            if xor != 0xFF:
                continue
            btn_nib = (frame[4] >> 4) & 0x0F
            if btn_nib not in (0xE, 0xD, 0xB, 0x7):
                continue
            if ((frame[4] >> 2) & 0x03) != 0x01:  # pad invariant
                continue
            serial = (frame[0] << 24) | (frame[1] << 16) | (frame[2] << 8) | frame[3]
            counter8 = ((frame[4] & 0x03) << 6) | ((frame[5] >> 2) & 0x3F)
            key = bytes(frame[:11])
            if key == self.last:
                return
            self.last = key
            self.emit({"data": serial, "serial": serial, "btn": btn_nib,
                       "btn_name": "0x%X" % btn_nib, "cnt": counter8, "crc_ok": True})
            return

    def feed(self, level, duration):
        c = self.CONST
        if not self.synced:
            if level and duration >= SYNC_MIN:
                self.synced = True
                self.bits = []
                self.man = 1
            return
        is_short = diff(duration, c.te_short) < c.te_delta
        is_long = diff(duration, c.te_long) < c.te_delta
        if is_short or is_long:
            if not is_long:
                r1 = 0 if level else 1
            else:
                r1 = 2 if level else 3
            produced, bit = self._man_decode(r1)
            if produced and len(self.bits) < 128:
                self.bits.append(bit)
        else:
            self._process()
            if level and duration >= SYNC_MIN:
                self.bits = []
                self.man = 1
            else:
                self.synced = False


ALL = [RenaultV4]
