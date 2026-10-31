# NFC / RFID Tag Maker — Design

**Date:** 2026-10-25
**Component:** FoxFW companion app (`C:\Users\stone\foxfw-companion-app`) + a new headless
Flipper firmware app (`fox_nfc_io.fap`) in the MOB firmware tree.
**Status:** awaiting approval (no code written yet).

## Intent / why

The companion can already drive the Flipper's Sub-GHz radio and a ChameleonUltra, but it
can't *create* NFC/RFID tags. The user wants one place in the app to build a tag of any type
either device can handle, then either emulate it, write it to a physical blank, clone an
existing one, or save it for later — choosing (or auto-selecting) the Flipper's ST25R3916
(HF) / 125 kHz LF chip, or the ChameleonUltra, as the backend.

"I want it all": every operation, every makeable type, both backends, with capability-aware UI.

## Goals

1. **One Maker flow**: pick type → edit fields → pick action → pick/auto-pick backend → go.
2. **Operations (verb)**: Define+Emulate, Define+Write-to-blank, Clone (read→fill), Save to
   library, Randomize/Sequence (incl. batch burn).
3. **Backends**: ChameleonUltra (BLE, already wired) and Flipper (via a new `fox_nfc_io.fap`).
4. **Backend selection**: Auto (prefer Chameleon for emulate, Flipper for write/LF), Manual,
   and **capability-aware** (grey out what the chosen backend/type can't do).
5. **Tag library** on the phone, one-tap re-deploy to either device.
6. **NDEF builder** (URL / text / Wi-Fi / contact / app-launch) for NFC Forum tags, with a
   live byte preview.
7. File interop: produce/consume Flipper `.nfc` and `.rfid` files; Chameleon slot dumps.

## Non-goals / anti-goals

- **Not** a cryptographic attack tool. Reading locked MIFARE (nested/darkside/mfkey) and key
  recovery stay in the existing Chameleon screen; the Maker consumes keys it's given or that
  a clone-read already recovered. (A "recover keys first" hand-off link is fine.)
- **EMV / bank cards**: read-only display at most; never "made", cloned, or emulated.
- **DESFire / FeliCa / ISO15693 write**: out of scope for v1 as *makeable* types — they are
  read/emulate-leaning. The UI shows them as read/emulate only, not write-to-blank.
- No attempt to defeat secure elements or write read-only tag fields (locked UID on non-magic
  HF, factory-locked NTAG, etc.). The UI states when a target card must be "magic"/writable.
- Not a background service — the Maker runs while its screen is open (like the other tools).

## Constraints / realities

- **Flipper NFC/LF is not exposed over RPC.** It needs a new headless `fox_nfc_io.fap`,
  launched over the storage/app RPC with args (same pattern as `fox_subghz_io.fap`), that
  reads a tag file off `/ext/apps_data/fox_companion/` and emulates / writes / reads it, then
  exits. **After a fresh firmware flash the first-boot "Fox Setup" wizard locks the loader**,
  so the app must detect an `appStart` failure and tell the user to finish Flipper setup.
- **Physical write needs the right stock**: LF → T5577/EM4305; HF MIFARE → magic gen1a/gen2/
  gen4; HF NDEF → a writable NTAG. The UI must say which, and fail gracefully if absent.
- **ChameleonUltra** emulates HF (MFC 1k/4k, Ultralight/NTAG, 14443A) and LF (EM410x) in its
  8+8 slots, reads HF/LF, and writes LF→T5577; its HF physical-write is limited (gen1a magic
  only). So Chameleon = emulate + read + LF write; Flipper = the full write/clone workhorse.
- Flipper `fox_nfc_io` emulate/write runs headless with a minimal/no view; emulation is
  time-boxed (arg `ms`, default ~30 s) so it always returns and unlocks the loader.
- All existing session facts hold: Flipper on CDC (CLI/RPC), Chameleon over NUS BLE (passkey
  123456), write-chunk uses `\r` terminator, loader is single-app (one op at a time).

## Capability matrix (what the Maker offers per type × backend)

Legend: **M**=make/emulate in-app, **E**=emulate on device, **W**=write to physical blank,
**R**=read/clone, **–**=not offered.

| Type | Flipper | Chameleon |
|---|---|---|
| LF EM4100/4102 | E W R | E W(T5577) R |
| LF HID Prox (H10301 + others) | E W R | – (emulate EM only) |
| LF Indala / AWID / IoProx / Paradox / FDX-B / Viking / Keri | E W R | – |
| HF MIFARE Classic 1K/4K | E W(magic) R | E W(gen1a) R |
| HF Ultralight / NTAG213/215/216 | E W R | E R |
| HF NDEF (URL/text/Wi-Fi/contact) → NTAG | M W | M (emulate only) |
| HF ISO14443-A generic (UID/SAK/ATQA) | E R | E R |
| HF ISO15693 (ICODE SLIX) | E R | – |
| HF FeliCa / DESFire | R (display) | R (display) |
| EMV | R (display only) | R (display only) |

## Architecture

### App (Kotlin / Compose)
- **`TagModel.kt`** — a sealed `Tag` hierarchy: `LfTag` (proto, id/fields) and `HfTag`
  (uid, sak/atqa, blocks/pages, keys, optional NDEF). Each knows how to (de)serialize to the
  Flipper `.nfc`/`.rfid` text format and to a Chameleon slot payload.
- **`NdefBuilder.kt`** — records (URI, Text, Wi-Fi, vCard, Android App Record) → NDEF bytes,
  with a live hex preview.
- **`TagBackend` interface** with `capabilities(type): Set<Op>`, `emulate(tag, ms)`,
  `writeToBlank(tag)`, `read(type): Tag?`. Two impls:
  - **`ChameleonTagBackend`** — reuses `ChameleonBle`/`ChameleonOps`/`ChameleonProto`
    (set-slot/emulate, reader read, LF T5577 write).
  - **`FlipperTagBackend`** — writes the tag file via storage RPC to
    `/ext/apps_data/fox_companion/`, launches `fox_nfc_io.fap` with the op + args, polls the
    result file. Surfaces the loader-locked / no-tag errors.
- **`TagMakerScreen.kt`** (+ sub-editors per type) — Tools entry **"Tag Maker"**. Flow:
  Type picker (grouped HF/LF, search) → field editor (or NDEF builder, or template, or
  clone-read, or randomize/sequence) → action bar (Emulate / Write / Save / Clone) with a
  backend chip (Auto/Flipper/Chameleon) that greys out impossible combos → confirm + raw
  preview → run with status.
- **`TagLibrary.kt`** — phone-local store (`getExternalFilesDir`/room-free JSON + files) of
  saved makes; list, re-deploy, export `.nfc`/`.rfid`, import from Flipper SD.
- Batch: an LF "burn N sequential" loop around `writeToBlank`.

### Firmware (`applications/fox/fox_nfc_io/` in the MOB tree)
Headless EXTERNAL app, args:
```
EMU_HF <file.nfc> [ms]     WRITE_HF <file.nfc>     READ_HF <out.nfc>
EMU_LF <file.rfid> [ms]    WRITE_LF <file.rfid>    READ_LF <out.rfid>
```
Uses the firmware `nfc` library (ST25R) for HF and the `lfrfid` library for LF — the same
device APIs the stock NFC/LF RFID apps use, driven from args, writing a result/status file,
then exiting. Modeled on `fox_subghz_io.fap` + `fox_import_settings.fap`.

## Build order (full scope, phased so each phase is testable)

1. **Firmware `fox_nfc_io.fap`** — LF (EM4100/HID/Indala/… emulate, read, T5577 write) +
   HF (MFC 1k/4k, Ultralight/NTAG emulate/read/write, NDEF write). Compile + link check.
2. **App core** — `TagModel`, `TagBackend` + both impls, file (de)serialization. Host-test the
   serializers against known Flipper `.nfc`/`.rfid` samples.
3. **App UI** — Tag Maker screen, per-type editors, capability-aware backend chip.
4. **NDEF builder, library, clone-read, randomize/batch.**
5. **Long tail** — ISO14443-A generic, ISO15693 read/emulate, FeliCa/DESFire/EMV read-display.
6. Build APK + firmware, flash, hardware-validate per type (LF T5577 write, HF magic write,
   NTAG/NDEF, emulate, clone).

## What's left to the builder (my calls unless you override)

- Maker lives as a **Tools entry "Tag Maker"** (consistent with current nav), not a new bottom
  tab.
- Default HF physical-write targets magic **gen1a→gen2 fallback**; NDEF targets NTAG215.
- LF default proto **EM4100**; HID default format **H10301 26-bit**.
- Emulate default duration **30 s** (cancellable); batch cap **50** cards.
- Library stored as JSON + sidecar `.nfc`/`.rfid` files under the app's external files dir.
- `fox_nfc_io` returns a one-line status file the app polls (ok / no-tag / write-failed /
  not-writable), mirroring the Sub-GHz FAP's result convention.

## Decisions (locked 2026-10-25)

- **LF approach = "Chameleon now, export LF next."** First build: full Flipper **HF** +
  **Chameleon LF (EM410x)** + all app features. Immediate fast-follow: export `lfrfid_worker_*`
  into the firmware API and extend `fox_nfc_io.fap` to the full Flipper LF set (HID/Indala/
  AWID/IoProx/Paradox/FDX-B/Viking/Keri → T5577).
- **Build scope = "full set in one go"**: the entire matrix (minus the deferred Flipper-LF
  long tail above) ships before the first flash, in the build order below.

## Builder-check finding (verified against firmware API symbols)

- **Flipper HF is FAP-feasible.** `nfc_alloc`, `nfc_poller_alloc`, `nfc_listener_alloc`,
  `nfc_device_alloc/save/load/set_data` are exported, so `fox_nfc_io.fap` can emulate / read /
  write / save HF via the generic NFC API. (Protocol-specific magic-write helpers aren't all
  exported; magic MFC write may need extra firmware-side work or an exported symbol.)
- **Flipper LF is NOT FAP-feasible as-is.** `lfrfid_worker_*` and `lfrfid_dict_file_save` are
  **not** in the firmware API, so an external FAP can't drive 125 kHz through the normal path.
  Two ways to still get LF:
  - **(LF-A) Export the LF symbols** — add `lfrfid_worker_*` (+ save) to the firmware API via
    `add_symbol.py` and rebuild. This is a **firmware-core change to the shared API table**
    (more invasive than a self-contained FAP), but it puts the full LF set (EM4100/HID/Indala/
    AWID/IoProx/Paradox/FDX-B/…) on the Flipper, writing to T5577.
  - **(LF-B) Route LF through ChameleonUltra only** — already scriptable over BLE, but
    ChameleonUltra LF is **EM410x-only** (emulate + T5577 write); HID/Indala/etc. would be
    unavailable for LF until/unless LF-A is done.

## Open questions for you (change what gets built)

**Write-to-physical-blank scope for v1**: the design targets *all* writable types. If you'd
rather I guarantee the high-value writers first (LF EM4100/HID→T5577, HF MIFARE 1K→magic, HF
NDEF→NTAG) and treat the long-tail LF protocols (Indala/AWID/IoProx/Paradox/FDX-B/Viking/Keri)
as a fast-follow, say so — it changes how much firmware lands in the first build. Default if you
don't: build the full set, in the order above.
