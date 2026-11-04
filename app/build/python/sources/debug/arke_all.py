#!/usr/bin/env python3
r"""
arke_all.py -- aggregate every ported automotive RAW decoder into one registry.
Modules are optional: whichever arke_* modules are present contribute their ALL /
ALL_DECODERS list. Used by the master runner and by decoders.py for RAW captures.
"""
import importlib

_MODULES = [
    ("arke_decoders", "ALL_DECODERS"),   # KiaV0, KiaV1
    ("arke_kia_rest", "ALL"),
    ("arke_ford", "ALL"),
    ("arke_toyota", "ALL"),
    ("arke_honda", "ALL"),
    ("arke_vag", "ALL"),
    ("arke_mazda_gm", "ALL"),
    ("arke_misc", "ALL"),
    ("arke_bmw", "ALL"),
    ("arke_renault", "ALL"),
    # ProtoPirate / Fox ports (2026-08-27): keyed + keyless car ciphers with
    # single-capture clone / next-code. Modules appear as their agents finish.
    ("pp_sheriff", "ALL"),        # Sheriff CFM ZX-750/ZX-930 (KeeLoq + 2 mfr keys)
    ("pp_scherkhan", "ALL"),      # Scher-Khan Magicar PRO/PRO2
    ("pp_porsche", "ALL"),        # Porsche Cayenne / VW Touareg / Audi (keyless)
    ("pp_renault_v0", "ALL"),     # Renault "Type 13" rolling (keyless matrix)
    ("pp_honda_mazda", "ALL"),    # Honda V1/V2 + Mazda V0 (keyless plaintext)
    # Archiv.zip ports (2026-09-01): validated automotive decoders not previously
    # in the arke engine. All no-CRC/structural (crc_ok=None) except Ducato's 1-bit
    # check; strength is in exact-length + sync-word + serial gates.
    ("arke_renault_v123", "ALL"), # Renault V1 (Clio2) / V2 (Clio3) / V3 (Megane)
    ("arke_mitsu_bmw", "ALL"),    # Mitsubishi V0/V1 + BMW V2
    ("arke_ducato", "ALL"),       # Fiat Ducato V0 (71-bit KeeLoq-style)
    # G4MEOVER18/ProtoPirate ports (2026-09-01): KIA V6 (AES-128, decode-only),
    # KIA V7 (CRC8), Land Rover V0.
    ("arke_kia67_lr", "ALL"),
    # Full firmware port, Tier A (2026-11-02): fixed-code gate/garage decoders, firmware-exact.
    ("arke_gates", "ALL"),       # CAME, Nice FLO, GateTX, Ansonic, Holtek, Holtek_HT12X,
                                 #   Linear, Clemsa, SMC5326, Princeton (PT2262)
]

ALL_DECODERS = []
LOADED = []
FAILED = []

for mod_name, attr in _MODULES:
    try:
        mod = importlib.import_module(mod_name)
        lst = getattr(mod, attr, None)
        if lst:
            ALL_DECODERS.extend(lst)
            LOADED.append("%s(%d)" % (mod_name, len(lst)))
    except Exception as e:  # module not written yet, or import error
        FAILED.append("%s: %s" % (mod_name, e))


if __name__ == "__main__":
    print("loaded:", ", ".join(LOADED) or "(none)")
    if FAILED:
        print("failed:", "; ".join(FAILED))
    print("total decoders:", len(ALL_DECODERS))
    for d in ALL_DECODERS:
        print("  -", d.NAME)
