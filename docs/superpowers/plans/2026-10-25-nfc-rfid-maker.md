# NFC/RFID Tag Maker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Tag Maker" to the FoxFW companion app that builds NFC/RFID tags of every makeable type and emulates / writes / clones / saves them via the Flipper (HF) or ChameleonUltra (LF + HF emulate).

**Architecture:** A Kotlin `Tag` model serializes to Flipper `.nfc`/`.rfid` files and Chameleon slot payloads. A `TagBackend` interface has two impls — `FlipperTagBackend` (pushes a tag file over storage RPC and launches a new headless `fox_nfc_io.fap`, then polls a status file) and `ChameleonTagBackend` (reuses the existing BLE proto). A capability-aware Compose screen drives them.

**Tech Stack:** Kotlin/Compose (app, JDK21/Gradle), C FAP (MOB firmware, fbt), Flipper generic NFC API (`nfc_poller/listener/device_*`), existing `ChameleonBle/Ops/Proto`, storage/app RPC in `FlipperBle`.

**Spec:** `docs/superpowers/specs/2026-10-25-nfc-rfid-maker-design.md`

## Global Constraints

- App: Kotlin + Jetpack Compose, package `com.foxfw.companion`; build `assembleDebug` with `JAVA_HOME=C:/Users/stone/android-build-tools/jdk-extract/jdk-21.0.12.1+1`.
- Firmware: FAP in `C:\Users\stone\Downloads\mob_only_build\applications\fox\fox_nfc_io\`, `apptype=FlipperAppType.EXTERNAL`, `fap_category="Fox"`; build by ABSOLUTE `fbt.cmd` with `FBT_NO_SYNC=1` and `FBT_TOOLCHAIN_PATH=C:\Users\stone\foxfw-2.0.13\2.0-2.0.13`; only call symbols present in `targets/f7/api_symbols.csv`.
- FAP args mirror `fox_subghz_io`: launched via RPC `appStart`/CLI `loader open <path> <args>`, read/write files under `/ext/apps_data/fox_companion/`, write a one-line status file, exit. Time-box emulation (`ms`, default 30000).
- Repo is not under git; each task ends with a build/validation checkpoint (not a commit) unless we `git init` first.
- **Core firmware changes get archived.** Any change outside a self-contained FAP — the shared API table (`targets/f7/api_symbols.csv`), `firmware_api.c`, `scripts/.../add_symbol.py`, `lib/*`, `targets/*` — a copy of each changed file is placed in `C:\Users\stone\OneDrive\Desktop\core firmware changes`. New FAPs (`fox_nfc_io`, `fox_subghz_io`) are NOT core changes. First applies to the LF-export fast-follow.
- No attacks, no EMV/DESFire/FeliCa write, no writing locked fields; UI states when a target must be magic/T5577/writable-NTAG.
- Serializers must round-trip real Flipper `.nfc`/`.rfid` sample files byte-equivalently (modulo field order the loader accepts).

## Review Focus

- **Loader locked by "Fox Setup"** right after a flash → `FlipperTagBackend` must detect the `appStart`/`loader open` failure and surface "finish Flipper setup first" rather than hang. (Task A4.)
- **No tag present / wrong card stock** on write (not magic, no T5577) → FAP returns `not-writable`/`no-tag`; UI shows it, no silent success. (Tasks F4, A4.)
- **Unknown/!writable HF type chosen with Chameleon** (e.g. NTAG write) → capability matrix greys it out; selecting Auto never routes an impossible op. (Task A2/A6.)
- **Malformed field input** (odd-length hex UID, out-of-range HID facility/card, oversized NDEF) → editor validates before build; backend never receives a malformed `Tag`. (Task A6, A5.)
- **Chameleon BLE disconnect mid-op** → backend reports failure, screen returns to idle, no partial-slot corruption. (Task A3.)

---

## Task F1: `fox_nfc_io.fap` skeleton + arg parse

**Files:**
- Create: `applications/fox/fox_nfc_io/application.fam`, `fox_nfc_io.c`
- Validate: `fbt.cmd fap_fox_nfc_io`

**Interfaces:**
- Produces: a FAP at `/ext/apps/Fox/fox_nfc_io.fap`; entry `fox_nfc_io_app(void* args)` parsing first token ∈ {`EMU_HF`,`WRITE_HF`,`READ_HF`,`EMU_LF`,`WRITE_LF`,`READ_LF`}; writes `/ext/apps_data/fox_companion/nfc_status.txt` = one of `ok|no-tag|not-writable|write-failed|bad-args|unsupported`.

- [ ] Step 1: Write `application.fam` (EXTERNAL, entry `fox_nfc_io_app`, requires `gui`,`nfc`; stack 4K; category Fox).
- [ ] Step 2: Write `fox_nfc_io.c` with `sscanf` mode parse + a `write_status(const char*)` helper; every unknown mode → `bad-args`/`unsupported`.
- [ ] Step 3: Build `fbt.cmd fap_fox_nfc_io`. Expected: `APPCHK ... fox_nfc_io.fap` (links clean).
- [ ] Step 4: Checkpoint (FAP builds).

## Task F2: HF read → `.nfc`

**Files:** Modify: `fox_nfc_io.c`
**Interfaces:** Consumes F1. Produces: `READ_HF <out.nfc>` detects a card via generic `nfc_poller_*`, and on success `nfc_device_save(dev, out)` + status `ok` (else `no-tag`).

- [ ] Step 1: Implement HF read using `nfc_alloc` + `nfc_poller_alloc(nfc, NfcProtocol…)` detection loop (bounded ~3 s) → `nfc_device_save`.
- [ ] Step 2: Build FAP (APPCHK clean).
- [ ] Step 3: On-device: present a known tag, `loader open … READ_HF …`, confirm `.nfc` saved + `nfc_status.txt=ok`.
- [ ] Step 4: Checkpoint.

## Task F3: HF emulate from `.nfc`

**Files:** Modify: `fox_nfc_io.c`
**Interfaces:** Consumes F1/F2. Produces: `EMU_HF <file.nfc> [ms]` → `nfc_device_load` + `nfc_listener_alloc(nfc, protocol, data)` run for `ms`, status `ok`.

- [ ] Step 1: Implement load + `nfc_listener_start` for `ms` (default 30000), then stop/free.
- [ ] Step 2: Build FAP (APPCHK clean).
- [ ] Step 3: On-device: emulate a saved NTAG/MFC, read it back with a phone/second reader.
- [ ] Step 4: Checkpoint.

## Task F4: HF write to magic/NTAG from `.nfc`

**Files:** Modify: `fox_nfc_io.c`
**Interfaces:** Consumes F2/F3. Produces: `WRITE_HF <file.nfc>` → detect target, write loaded data; status `ok|not-writable|no-tag|write-failed`.

- [ ] Step 1: Implement write via the exported `nfc_poller`/device write path for MFC (magic) and NTAG page writes; detect non-writable → `not-writable`. If a required magic-write symbol is not exported, document the gap in the status (`unsupported`) and note it for the LF-export fast-follow PR.
- [ ] Step 2: Build FAP (APPCHK clean).
- [ ] Step 3: On-device: write to a magic MFC 1K and a writable NTAG215; verify by re-reading.
- [ ] Step 4: Checkpoint.

## Task A1: `TagModel.kt` + file (de)serialization

**Files:** Create: `app/src/main/java/com/foxfw/companion/TagModel.kt`; Test: `hosttest/tag_roundtrip` (JVM main or existing hosttest pattern) with sample `.nfc`/`.rfid`.
**Interfaces:** Produces: sealed `Tag`; `HfTag(protocol, uid, sak, atqa, pages/blocks, keys, ndef?)`, `LfTag(protocol, dataHex, fields)`; `fun Tag.toFlipperFile(): String`, `fun parseFlipperFile(text:String): Tag?`, `fun Tag.toChameleonSlot(): ByteArray`.

- [ ] Step 1: Write round-trip test: parse a real `.nfc` (MFC 1K, NTAG215) and `.rfid` (EM4100, HID) sample → re-serialize → loader-accepted equality (key fields match).
- [ ] Step 2: Run test, expect FAIL (no model).
- [ ] Step 3: Implement the sealed types + serializers (Flipper `Filetype`/`Device type`/`UID`/`Data`/blocks; lfrfid `Key type`/`Data`).
- [ ] Step 4: Run test, expect PASS.
- [ ] Step 5: Checkpoint.

## Task A2: `TagBackend` interface + capability matrix

**Files:** Create: `TagBackend.kt`; Test: `hosttest` capability assertions.
**Interfaces:** Produces: `enum Op{EMULATE,WRITE,READ}`; `interface TagBackend { fun capabilities(t:TagType):Set<Op>; suspend fun emulate(t:Tag,ms:Int):Result; suspend fun writeToBlank(t:Tag):Result; suspend fun read(t:TagType):Tag? }`; `object TagCaps` with the design's matrix; `fun pickBackend(op,type,flipper,cham):TagBackend?` (Auto rule).

- [ ] Step 1: Write tests for the matrix (Chameleon NTAG write = absent; Flipper LF = absent until export; Auto prefers Chameleon for EMULATE, Flipper for WRITE).
- [ ] Step 2: Run, expect FAIL.
- [ ] Step 3: Implement matrix + `pickBackend`.
- [ ] Step 4: Run, expect PASS. Checkpoint.

## Task A3: `ChameleonTagBackend`

**Files:** Create: `ChameleonTagBackend.kt` (reuses `ChameleonBle/Ops/Proto`).
**Interfaces:** Consumes A1/A2 + existing Chameleon proto. Produces: HF emulate (set slot + activate), HF/LF read, LF EM410x write-to-T5577.

- [ ] Step 1: Implement against existing `ChameleonOps` commands (set-slot-data, set-active, read, LF write). Reference the existing screen for command IDs.
- [ ] Step 2: On-device (Chameleon over BLE): emulate an NTAG into a slot; read an EM410x; write EM410x→T5577.
- [ ] Step 3: Checkpoint.

## Task A4: `FlipperTagBackend`

**Files:** Create: `FlipperTagBackend.kt`; Modify: `FlipperBle.kt` (reuse `appStart`/storage).
**Interfaces:** Consumes A1/A2, F1–F4. Produces: push `.nfc`/`.rfid` to `/ext/apps_data/fox_companion/`, `appStart("/ext/apps/Fox/fox_nfc_io.fap", "<MODE> <file> [ms]")`, poll `nfc_status.txt`; map `appStart` failure → `LOADER_LOCKED` ("finish Flipper setup").

- [ ] Step 1: Implement push+launch+poll with a timeout; parse status → `Result`.
- [ ] Step 2: On-device: emulate + write an HF tag end-to-end via the FAP; force loader-locked (fresh boot) and confirm the friendly error.
- [ ] Step 3: Checkpoint.

## Task A5: `NdefBuilder.kt`

**Files:** Create: `NdefBuilder.kt`; Test: `hosttest` NDEF vectors.
**Interfaces:** Produces: `fun ndef(records):ByteArray` for URI/Text/WiFi/vCard/AAR; feeds `HfTag.ndef`.

- [ ] Step 1: Test known NDEF byte vectors (URI "https://", Text "en").
- [ ] Step 2–4: FAIL → implement → PASS. Checkpoint.

## Task A6: `TagMakerScreen.kt` + per-type editors

**Files:** Create: `TagMakerScreen.kt` (+ small editor composables); Modify: `Tools.kt` (tile + route `"tagmaker"`).
**Interfaces:** Consumes A1–A5. Produces: Tools entry "Tag Maker"; flow type→editor/NDEF/template/clone/randomize → action bar (Emulate/Write/Save/Clone) with Auto/Flipper/Chameleon chip greying impossible combos; raw preview; validation (hex UID even length, HID ranges, NDEF size).

- [ ] Step 1: Build the screen + editors + capability-aware chip + validation.
- [ ] Step 2: App builds (`assembleDebug`); manual nav check.
- [ ] Step 3: Checkpoint.

## Task A7: `TagLibrary.kt`

**Files:** Create: `TagLibrary.kt`; Modify: `TagMakerScreen.kt` (Save/Load UI).
**Interfaces:** Produces: JSON index + `.nfc`/`.rfid` sidecars in `getExternalFilesDir`; list/save/delete/re-deploy/import-from-Flipper-SD.

- [ ] Step 1: Implement store + UI. Step 2: App builds. Step 3: Checkpoint.

## Task A8: Clone-read + randomize/batch

**Files:** Modify: `TagMakerScreen.kt`, `TagBackend.kt`.
**Interfaces:** Clone = `read()` → populate editor; Randomize = UID/LF-id generators; Batch = loop `writeToBlank` over a sequence (cap 50).

- [ ] Step 1: Implement. Step 2: App builds. Step 3: Checkpoint.

## Task A9: Integrate + ship

**Files:** Modify: `WhatsNew.kt` (add Tag Maker item); build firmware `updater_package` + APK.
- [ ] Step 1: Add What's-New entry.
- [ ] Step 2: Build firmware (bundles `fox_nfc_io.fap`) + APK, both clean.
- [ ] Step 3: Flash + install; on-device matrix validation (LF EM410x via Chameleon; HF MFC/NTAG/NDEF emulate+write+clone via Flipper).
- [ ] Step 4: Checkpoint.

## Fast-follow (separate plan): full Flipper LF
Export `lfrfid_worker_*` + `lfrfid_dict_file_save` via `add_symbol.py`, rebuild firmware API, extend `fox_nfc_io` with `EMU_LF/WRITE_LF/READ_LF` for HID/Indala/AWID/IoProx/Paradox/FDX-B/Viking/Keri → T5577, and flip the capability matrix LF column for Flipper. **Core-change archive:** the edited `api_symbols.csv` (and any `firmware_api.c`/`add_symbol.py` touched) are copied to `C:\Users\stone\OneDrive\Desktop\core firmware changes`.

## Self-review notes
- Spec coverage: operations (A4/A3/A6/A8), types (A1 + F2–F4 HF, A3 LF), backends (A3/A4), capability-aware (A2/A6), library (A7), NDEF (A5), clone/batch (A8). LF-on-Flipper long tail explicitly deferred per locked decision.
- Risk carried: F4 magic-write may hit a non-exported symbol → documented fallback to `unsupported` status + fast-follow, not a silent failure.
- Tests are host-based where logic is pure (A1/A2/A5); device/FAP tasks validate on hardware (no unit harness for radio).
