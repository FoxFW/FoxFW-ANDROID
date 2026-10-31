# FoxFW Companion — fob + USB device-test checklist (Task V1)

Run on the real phone (S22 Ultra) + Flipper (FoxFW). Record pass/fail + evidence per row.
Nothing here is proven until it's checked on hardware — host/unit tests only prove the logic.

**Prereqs:** flash the rebuilt `fox_subghz_io.fap` (`faps/fox_subghz_io.fap`, API 88.5) to
`/ext/apps/Fox/` on the Flipper SD; install the rebuilt APK; Flipper past its first-boot wizard.

| # | Hop | Steps | Expected | Result |
|---|-----|-------|----------|--------|
| 1 | BLE connect | Tap scan; pair | header shows "BLE linked" | |
| 2 | appStart + storage (BLE) | Decode → Capture 4s | FAP launches, rx.sub pulled back | |
| 3 | Decode → decode | capture a known fob | a frame decodes (make/serial/cnt) | |
| 4 | **Fob Route A TX** | Decode → tap a Porsche/Honda/Mazda/Sheriff row → press a button | 2nd SDR/Flipper receives a frame on the capture's freq/preset; counter auto-advances; status "sent" | |
| 5 | **Fob counter bump** | press −1/+1, then a button | transmitted counter == displayed counter (no off-by-one) | |
| 6 | **Fob Route B (KIA V6)** | tap a KIA V6 row → press a button | status "forged — transmit via ProtoPirate"; `/ext/subghz/fox_fob.sub` written; open it in ProtoPirate → it TXes; a 2nd RX decodes the forged serial/cnt/btn | |
| 7 | **Replay labelling** | tap a VAG/BMW/Ford row | shows "Replay only — won't open a rolling receiver"; no forge buttons | |
| 8 | io_status honesty | TX with the Flipper's radio busy / ext board absent (dev=Ext) | status reflects `radio-fail`/`no-device`, not a false "sent" | |
| 9 | Archive replay modulation | capture on FM (2-FSK), save, Archive → re-TX | TX goes out on the capture's modulation (not forced AM650) | |
| 10 | Hitag/Fiat offload (BLE) | on Flipper, run Fiat recover → offload to phone | app jumps to Hitag2, cracks, sends key back; Flipper shows the key | |
| 11 | PSA offload (BLE) | on Flipper, PSA decrypt → offload | app jumps to PSA, cracks, returns plaintext; Flipper % advances | |
| 12 | NFC FAP launch | Tools → Tag Maker → an HF/LF op | `fox_nfc_io.fap` runs, status returns | |
| 13 | Settings sync | Settings → Fox settings → reload/apply | reads 42-field state; apply round-trips | |
| 14 | **USB auto-detect** | plug Flipper into phone (USB-C) | app offers to open / prompts USB permission; header shows "USB linked" | |
| 15 | **appStart + storage (USB)** | over USB: Decode capture + a fob TX | same as rows 2/4 but over the cable | |
| 16 | **USB→BLE fallback** | unplug USB while connected | falls back to BLE (if paired); header updates | |
| 17 | USB-only offload gating | USB only (no BLE): try Hitag/PSA | they stay unavailable (offload needs the BLE custom channel) | |

**On any failure:** use systematic-debugging, fix in the owning task, re-run that row. Do not mark
a row passed without a device result. Fill results inline and commit this file.
