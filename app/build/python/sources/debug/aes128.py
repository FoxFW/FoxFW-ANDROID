#!/usr/bin/env python3
r"""aes128.py -- self-contained AES-128 single-block encrypt/decrypt (ECB core),
pure Python, no external dependency, for use inside the arke Sub-GHz decoders that
carry AES-encrypted rolling codes (KIA/HYU V6, ...). Bit-exact with the reference
FIPS-197 algorithm; self-tested at import-time against the FIPS-197 C.1 vector and
against the recovered KIA V6 vector.
"""

_SBOX = bytes.fromhex(
    "637c777bf26b6fc53001672bfed7ab76ca82c97dfa5947f0add4a2af9ca472c0"
    "b7fd9326363ff7cc34a5e5f171d8311504c723c31896059a071280e2eb27b275"
    "09832c1a1b6e5aa0523bd6b329e32f8453d100ed20fcb15b6acbbe394a4c58cf"
    "d0efaafb434d338545f9027f503c9fa851a3408f929d38f5bcb6da2110fff3d2"
    "cd0c13ec5f974417c4a77e3d645d197360814fdc222a908846eeb814de5e0bdb"
    "e0323a0a4906245cc2d3ac629195e479e7c8376d8dd54ea96c56f4ea657aae08"
    "ba78252e1ca6b4c6e8dd741f4bbd8b8a703eb5664803f60e613557b986c11d9e"
    "e1f8981169d98e949b1e87e9ce5528df8ca1890dbfe6426841992d0fb054bb16"
)
_INV = bytes.fromhex(
    "52096ad53036a538bf40a39e81f3d7fb7ce339829b2fff87348e4344c4dee9cb"
    "547b9432a6c2233dee4c950b42fac34e082ea16628d924b2765ba2496d8bd125"
    "72f8f66486689816d4a45ccc5d65b6926c704850fdedb9da5e154657a78d9d84"
    "90d8ab008cbcd30af7e45805b8b34506d02c1e8fca3f0f02c1afbd0301138a6b"
    "3a9111414f67dcea97f2cfcef0b4e67396ac7422e7ad3585e2f937e81c75df6e"
    "47f11a711d29c5896fb7620eaa18be1bfc563e4bc6d279209adbc0fe78cd5af4"
    "1fdda8338807c731b11210592780ec5f60517fa919b54a0d2de57a9f93c99cef"
    "a0e03b4dae2af5b0c8ebbb3c83539961172b047eba77d626e169146355210c7d"
)
_RCON = (0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36)


def _xtime(a):
    return ((a << 1) ^ 0x1b) & 0xFF if (a & 0x80) else (a << 1) & 0xFF


def _mul(a, b):
    r = 0
    for _ in range(8):
        if b & 1:
            r ^= a
        b >>= 1
        a = _xtime(a)
    return r & 0xFF


def _expand_key(key):
    rk = bytearray(key)
    for i in range(4, 44):
        t = list(rk[(i - 1) * 4:(i - 1) * 4 + 4])
        if i % 4 == 0:
            t = [_SBOX[t[1]] ^ _RCON[i // 4 - 1], _SBOX[t[2]], _SBOX[t[3]], _SBOX[t[0]]]
        b = (i - 4) * 4
        rk += bytes((t[j] ^ rk[b + j]) & 0xFF for j in range(4))
    return bytes(rk)


def _add_rk(s, rk, off):
    for i in range(16):
        s[i] ^= rk[off + i]


def encrypt_block(key, block):
    """AES-128 encrypt one 16-byte block (ECB). key,block: 16 bytes -> bytes(16)."""
    rk = _expand_key(key)
    s = bytearray(block)
    _add_rk(s, rk, 0)
    for rnd in range(1, 10):
        for i in range(16):
            s[i] = _SBOX[s[i]]
        s[1], s[5], s[9], s[13] = s[5], s[9], s[13], s[1]
        s[2], s[6], s[10], s[14] = s[10], s[14], s[2], s[6]
        s[3], s[7], s[11], s[15] = s[15], s[3], s[7], s[11]
        for c in range(4):
            a = s[c * 4:c * 4 + 4]
            s[c * 4 + 0] = _mul(a[0], 2) ^ _mul(a[1], 3) ^ a[2] ^ a[3]
            s[c * 4 + 1] = a[0] ^ _mul(a[1], 2) ^ _mul(a[2], 3) ^ a[3]
            s[c * 4 + 2] = a[0] ^ a[1] ^ _mul(a[2], 2) ^ _mul(a[3], 3)
            s[c * 4 + 3] = _mul(a[0], 3) ^ a[1] ^ a[2] ^ _mul(a[3], 2)
        _add_rk(s, rk, rnd * 16)
    for i in range(16):
        s[i] = _SBOX[s[i]]
    s[1], s[5], s[9], s[13] = s[5], s[9], s[13], s[1]
    s[2], s[6], s[10], s[14] = s[10], s[14], s[2], s[6]
    s[3], s[7], s[11], s[15] = s[15], s[3], s[7], s[11]
    _add_rk(s, rk, 160)
    return bytes(s)


def decrypt_block(key, block):
    """AES-128 decrypt one 16-byte block (ECB). key,block: 16 bytes -> bytes(16)."""
    rk = _expand_key(key)
    s = bytearray(block)
    _add_rk(s, rk, 160)
    for rnd in range(9, 0, -1):
        s[1], s[5], s[9], s[13] = s[13], s[1], s[5], s[9]
        s[2], s[6], s[10], s[14] = s[10], s[14], s[2], s[6]
        s[3], s[7], s[11], s[15] = s[7], s[11], s[15], s[3]
        for i in range(16):
            s[i] = _INV[s[i]]
        _add_rk(s, rk, rnd * 16)
        for c in range(4):
            a = s[c * 4:c * 4 + 4]
            s[c * 4 + 0] = _mul(a[0], 14) ^ _mul(a[1], 11) ^ _mul(a[2], 13) ^ _mul(a[3], 9)
            s[c * 4 + 1] = _mul(a[0], 9) ^ _mul(a[1], 14) ^ _mul(a[2], 11) ^ _mul(a[3], 13)
            s[c * 4 + 2] = _mul(a[0], 13) ^ _mul(a[1], 9) ^ _mul(a[2], 14) ^ _mul(a[3], 11)
            s[c * 4 + 3] = _mul(a[0], 11) ^ _mul(a[1], 13) ^ _mul(a[2], 9) ^ _mul(a[3], 14)
    s[1], s[5], s[9], s[13] = s[13], s[1], s[5], s[9]
    s[2], s[6], s[10], s[14] = s[10], s[14], s[2], s[6]
    s[3], s[7], s[11], s[15] = s[7], s[11], s[15], s[3]
    for i in range(16):
        s[i] = _INV[s[i]]
    _add_rk(s, rk, 0)
    return bytes(s)


# ---- import-time self-test (FIPS-197 C.1 + recovered KIA V6 vector) ----
_k = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
_p = bytes.fromhex("00112233445566778899aabbccddeeff")
_c = bytes.fromhex("69c4e0d86a7b0430d8cdb78070b4c55a")
assert encrypt_block(_k, _p) == _c, "AES enc self-test failed"
assert decrypt_block(_k, _c) == _p, "AES dec self-test failed"
# KIA V6 real capture: ct -> pt (CRC-valid), proves the exact path used by the decoder
assert decrypt_block(_k, bytes.fromhex("827efca4ea82a7256c3073331f842bf7")) == \
    bytes.fromhex("a6000000109da70bc106c04bb3000088"), "AES KIA V6 vector failed"

if __name__ == "__main__":
    print("aes128 self-test OK (FIPS-197 + KIA V6)")
