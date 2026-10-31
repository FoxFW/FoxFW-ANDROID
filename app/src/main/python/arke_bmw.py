#!/usr/bin/env python3
r"""arke_bmw.py -- BMW/Mini RKE RAW demodulator (ported from firmware bmw_v3.c).
FSK Manchester, te 95/190, 0xCC header + 64-bit payload. Recovers the cleartext
device serial + button; the 40-bit rolling code is AES-encrypted (no counter /
next-code -- structural, like GM). Only useful on 2FSK captures."""
from arke_core import Const, ProtoDecoder, diff

_HDR = 0xCC
_GAP = 350
_BTN = {0x02: "Trunk", 0x04: "Unlock", 0x08: "Lock", 0x10: "0x10"}


class BmwV3(ProtoDecoder):
    NAME = "BMW/Mini V3"
    CONST = Const(95, 190, 40, 64)

    def __init__(self):
        super().__init__()
        self.step = 0    # 0 reset, 1 waitgap, 2 findheader, 3 collectdata
        self.data = 0
        self.nbit = 0
        self.te_last = 0

    def reset(self):
        super().reset()
        self.step = 0
        self.data = 0
        self.nbit = 0
        self.te_last = 0

    def _bit(self, bit):
        self.data = ((self.data << 1) | (bit & 1)) & 0xFFFFFFFFFFFFFFFF
        self.nbit += 1
        if self.step == 2:  # FindHeader
            if self.nbit >= 8 and (self.data & 0xFF) == _HDR:
                self.data = 0
                self.nbit = 0
                self.step = 3
            elif self.nbit > 32:
                self.step = 0
        elif self.step == 3:  # CollectData
            if self.nbit == 64:
                data = self.data
                btn = (data >> 56) & 0xFF
                if btn in (0x02, 0x04, 0x08, 0x10):
                    serial = (((data >> 48) & 0xFF) << 8) | ((data >> 40) & 0xFF)
                    cnt = (data >> 16) & 0xFFFF
                    self.emit({"data": data, "serial": serial, "btn": btn,
                               "btn_name": _BTN.get(btn, "0x%X" % btn),
                               "cnt": cnt, "crc_ok": None})  # serial cleartext, RC=AES
                self.step = 0

    def feed(self, level, duration):
        c = self.CONST
        if self.step == 0:
            if (not level) and duration > _GAP:
                self.step = 1
                self.data = 0
                self.nbit = 0
                self.te_last = 0
        elif self.step == 1:
            if level:
                if diff(duration, c.te_short) <= c.te_delta or diff(duration, c.te_long) <= c.te_delta:
                    self.te_last = duration
                    self.step = 2
                else:
                    self.step = 0
            else:
                if duration <= _GAP:
                    self.step = 0
        elif self.step in (2, 3):
            is_short = diff(duration, c.te_short) <= c.te_delta
            is_long = diff(duration, c.te_long) <= c.te_delta
            if not is_short and not is_long:
                self.step = 0
                if (not level) and duration > _GAP:
                    self.step = 1
                    self.data = 0
                    self.nbit = 0
                    self.te_last = 0
                return
            if is_short:
                if self.te_last == 0:
                    self.te_last = duration
                else:
                    self._bit(0 if level else 1)
                    self.te_last = 0
            else:  # long
                if self.te_last != 0:
                    self._bit(0 if level else 1)
                    self.te_last = c.te_short
                else:
                    self._bit(0 if level else 1)
                    self.te_last = 0


ALL = [BmwV3]
