# KeeLoq manufacturer keystore for on-phone forge/crack.
#
# The key list is stored OBFUSCATED (SHA-256 keystream XOR + base64), decoded at import, so the
# plaintext manufacturer keys are not sitting in the public source tree. This is obfuscation, not
# security: the seed below lives in this file on purpose, so the project still compiles and runs
# unchanged with no extra steps. Regenerate with scratchpad/gen_keys.py from the clean keystore.
import base64, hashlib

_SEED = b"FoxFW-keeloq-keystore-obfuscation-v1"

_ENC = (
    "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"
)

def _keystream(seed, n):
    out = bytearray(); ctr = 0
    while len(out) < n:
        out += hashlib.sha256(seed + ctr.to_bytes(4, "big")).digest(); ctr += 1
    return bytes(out[:n])

def _dexor(data, seed):
    ks = _keystream(seed, len(data))
    return bytes(a ^ b for a, b in zip(data, ks))

def _load():
    raw = _dexor(base64.b64decode(_ENC), _SEED).decode("utf-8")
    keys = []
    for line in raw.splitlines():
        if not line:
            continue
        hx, lt, name = line.split(":", 2)
        keys.append((int(hx, 16), int(lt), name))
    return keys

# (key_int, learning_type, name)
KEYS = _load()
