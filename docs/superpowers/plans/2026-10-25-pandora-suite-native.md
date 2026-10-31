# Pandora Suite (Native) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task (Native execution was chosen). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the FoxFW companion app reflect the *entire* proven Pandora suite natively — emulate the real D-605/MAX (ARM) and DXL-5000 (PIC18) keyfob firmwares on the phone, recover their unlock codes (the "code-grabber"/Cracker), and forge+transmit command frames — using the Flipper only as the OOK CC1101 radio.

**Architecture:** Port the proven C from the Flipper firmware apps into the app's existing NDK tree. The ARM emulator (`pa_emu`+`thumb_core`) already ships in the app and exposes the full proven path (boot, buttons, knock, `tx_edge` OOK bit-bang capture, framebuffer) — the current `pandora_jni.c` just bypasses it with a byte-shortcut; switch to the real path. Add a PIC18 emulator (`pic18_core`+`ihex` vendored verbatim; a portable `pic_emu` driver extracted from `PandoraPIC/main.c` the same way `pa_emu`/`pa_rf` were extracted from `PandoraARM/main.c`). Add a `cracker` module (ARM knock-scan + PIC PIN recover/brute, extracted from `PandoraPINBrute/main.c`). Correct the RF to OOK at the proven parameters. Rework `PandoraScreen` into a load→detect→crack→emulate→forge→TX suite.

**Tech Stack:** Android NDK (C, arm64-v8a via CMake), JNI, Kotlin/Jetpack Compose. Host unit tests in C compiled with the system toolchain (gcc/clang) using `PIC18_HOST`/host shims, driven from a `hosttest/Makefile` per the existing firmware pattern.

**Spec / authority (the proven implementations this plan ports — read these, do not re-derive):**
- `C:\Users\stone\Downloads\mob_only_build\applications\system\PandoraARM\main.c` — ARM D-605/MAX driver + RF bridge (OOK650, `tx_edge` capture, knock).
- `C:\Users\stone\Downloads\mob_only_build\applications\system\PandoraPIC\main.c` + `lib/pic18core/{pic18_core.c,pic18_core.h,ihex.c,ihex.h}` — DXL-5000 PIC18 driver + core.
- `C:\Users\stone\Downloads\mob_only_build\applications\system\PandoraPINBrute\main.c` — the Cracker (auto-detect ARM/PIC → knock / PIN).
- `C:\Users\stone\pandora-tembed\src\radio_tembed.cpp` + `lib\pandora_emu\pa_emu.c` — proven RF params + the full-emu `pa_transmit` edge path.
- Existing app native: `C:\Users\stone\foxfw-companion-app\app\src\main\cpp\pandora\{pa_emu.c,pa_emu.h,pa_rf.c,thumb_core.c,pandora_jni.c}`.

## Global Constraints

- **Modulation is OOK for every Pandora variant.** D-605/MAX bit-bang OOK on the ARM DATA pin; DXL-5000 bit-bangs OOK on LATB. Never 2-FSK. (The earlier 2-FSK companion change was wrong and is reverted by this plan.)
- **Proven RF parameters (from `radio_tembed.cpp` + `pa_emu.c` profiles):** D-605 carrier base `433926875` Hz, TX half-bit cell `rf_tx_cell_us = 400` µs; MAX cell `500` µs; DXL-5000 OOK on LATB, cell per its PIC profile. Frame = preamble `0xAA`,`1`,`0xAA` + 9 data bytes (ARM) / per-fw (PIC), Manchester MSB-first, ×4 with inter-frame gap. Prefer the firmware-captured `tx_edge` timings over any synthesized half-bit when the full-emu path is used.
- **Port proven C verbatim where possible.** `pic18_core.c`/`ihex.c` are pure C (no Furi) — vendor unchanged. Extract driver/cracker logic from the Flipper `main.c` files into new pure-C files; do not invent algorithms.
- **No new BLE channel / no firmware reflash required.** TX goes through the existing `fox_subghz_io.fap` (already on-device) via `FlipperRf`, using an OOK preset index the current firmware already supports (stock `Ook650Async` = preset 1). Do not depend on reflashing preset 4.
- **Core-firmware-change archive rule:** if any edit touches shared firmware (`api_symbols.csv`, `firmware_api.c`, `lib/*`, `targets/*`) — not FAP app code, not app/ code — copy it to `C:\Users\stone\OneDrive\Desktop\core firmware changes`. This plan is not expected to touch shared firmware.
- Package `com.foxfw.companion`; JDK21 at `C:\Users\stone\android-build-tools\jdk-extract\jdk-21.0.12.1+1`; build `./gradlew assembleDebug`; adb at `C:\Users\stone\android-build-tools\sdk\platform-tools\adb.exe`; phone S22 Ultra wireless adb `192.168.1.197`.

## Review Focus

- **Wrong-variant dump loaded** (e.g. a DXL-5000 `.hex` sent to the ARM emulator): auto-detect must classify ARM vs PIC from the image and refuse/redirect rather than emulate garbage. Test in Task 4.
- **Truncated / non-keyfob file**: loader must reject (bounded read, no OOB) and surface a clean error, not crash the native lib. Test in Task 1/2 with a short buffer.
- **PIN not present as literals** (brute path): the 0000–9999 brute must terminate and report "not found" if no code validates, never hang. Test in Task 4 with a doctored image.
- **Counter rollover / repeated forge**: forging the same command twice must advance the rolling counter exactly as the fw does (the on-air frame differs), matching the decode round-trip. Test in Task 3.
- **TX with Flipper not connected**: forge still succeeds natively and the UI reports "connect Flipper to transmit" rather than silently failing. Test in Task 6 (UI-level).

---

### Task 1: Vendor the PIC18 core into the NDK build + host-test harness

**Files:**
- Create: `app/src/main/cpp/pandora/pic18_core.c`, `pic18_core.h`, `ihex.c`, `ihex.h` (copied verbatim from `PandoraPIC/lib/pic18core/`).
- Modify: `app/src/main/cpp/CMakeLists.txt` (add `pic18_core.c ihex.c` to a native lib target).
- Create: `app/src/main/cpp/pandora/hosttest/Makefile`, `app/src/main/cpp/pandora/hosttest/test_pic_core.c`.

**Interfaces:**
- Produces: `Pic18Cpu` type + `pic18_*` API (from `pic18_core.h`) available to later tasks; `ihex_parse(...)` from `ihex.h`.

- [ ] **Step 1: Copy the four files verbatim; write a host test that loads the DXL-5000 example and steps the core.**

```c
// test_pic_core.c — load the example dump, boot, run N steps, assert no fault.
// Fixture: PandoraPIC/resources/"Pandora DXL-5000 (example).bin" copied into hosttest/fixtures/.
void test_pic_boot_runs(void) {
    Pic18Cpu cpu; /* init per pic18_core.h */
    load_fixture_into(&cpu, "fixtures/dxl5000.bin");
    for (int i = 0; i < 200000 && !cpu.halted; i++) pic18_step(&cpu);
    assert(cpu.steps > 0 && !cpu.halted);     // ran, did not fault/halt-early
}
void test_pic_short_buffer_safe(void) {       // Review Focus: truncated file
    Pic18Cpu cpu; uint8_t buf[16] = {0};
    load_prog(&cpu, buf, sizeof buf);          // must not OOB
    pic18_step(&cpu);                          // reads return 0xFF, no crash
}
```

- [ ] **Step 2: `cd app/src/main/cpp/pandora/hosttest && make` — Expected: FAIL (no Makefile/target yet).**
- [ ] **Step 3: Write the Makefile (clang/gcc, `-DPIC18_HOST`), implement `load_fixture_into`/`load_prog` helpers using `ihex_parse` for `.hex` and raw read for `.bin`.**
- [ ] **Step 4: `make && ./test_pic_core` — Expected: PASS 2/2.**
- [ ] **Step 5: Add `pic18_core.c ihex.c` to CMake (new `pandorapic` SHARED lib or extend `pandora`); confirm `./gradlew :app:compileDebugSources` configures CMake without error (full APK build is Task 7).**
- [ ] **Step 6: Commit.**

---

### Task 2: Extract the portable DXL-5000 driver (`pic_emu`) + JNI forge

**Files:**
- Create: `app/src/main/cpp/pandora/pic_emu.c`, `pic_emu.h` — profiles (`mariofull`/`mario2` from `PandoraPIC/main.c`), boot-to-main-loop, button inject, SW-SPI framebuffer capture, OOK `tx_edge` capture on LATB, `pic_forge(...)`.
- Create: `app/src/main/cpp/pandora/pic_jni.c` — `PandoraPicNative` JNI.
- Create (Kotlin): `app/src/main/java/com/foxfw/companion/PandoraPicNative.kt`.
- Modify: `CMakeLists.txt` (add `pic_emu.c pic_jni.c` to the lib), `hosttest/test_pic_core.c` (add forge test).

**Interfaces:**
- Consumes: Task 1 `pic18_core`/`ihex`.
- Produces: `PandoraPicNative.forge(fw: ByteArray, serial: ByteArray, counter: Int, cmd: Int): ByteArray?` and `framebuffer(): ByteArray?`; C `PicEmu` + `pic_forge`.

- [ ] **Step 1: Host test — forge a DXL-5000 command frame from the example dump; assert a stable, non-empty on-air byte string and that re-forging with counter+1 differs.**

```c
void test_pic_forge_frame(void) {
    uint8_t out[32]; int n = pic_forge(dxl_fw, dxl_len, SERIAL4, /*ctr*/1, /*cmd*/CMD_ARM, out, sizeof out);
    assert(n >= 6);                            // a real frame was emitted off the bit-bang pin
    uint8_t out2[32]; int n2 = pic_forge(dxl_fw, dxl_len, SERIAL4, 2, CMD_ARM, out2, sizeof out2);
    assert(n2 == n && memcmp(out, out2, n) != 0);   // rolling counter changed the frame
}
```

- [ ] **Step 2: `make && ./test_pic_core` — Expected: FAIL (`pic_forge` undefined).**
- [ ] **Step 3: Implement `pic_emu.c` by porting the driver pieces of `PandoraPIC/main.c` (profile table, `send_byte_pc` hook, `ook_tx_lat`/`ook_tx_bit` capture, boot run-to-main-loop, button register `0xF8E`). Strip all Furi/gui. `pic_forge` = boot + inject the "send command" gesture + capture the OOK bit-bang edges → data bytes.**
- [ ] **Step 4: `make && ./test_pic_core` — Expected: PASS.**
- [ ] **Step 5: Add `PandoraPicNative` JNI (`forge`, `framebuffer`) mirroring `PandoraNative`; wire CMake; Kotlin external decls + asset-loading helper for the DXL firmware dump.**
- [ ] **Step 6: Commit.**

---

### Task 3: ARM path — use the full proven emulator (correct OOK edges) + expose knock-code

**Files:**
- Modify: `app/src/main/cpp/pandora/pandora_jni.c` — add a `forgeEdges` path that boots `pa_emu`, injects the knock + command gesture, and captures `tx_edge` (level, insn) into an edge list at `rf_tx_cell_us`; keep the existing byte `forge` for decode round-trip. Remove the misleading 2-FSK globals (`g_last_modtyp`-driven FSK decision stays for *reporting* only, never to pick modulation).
- Modify: `app/src/main/java/com/foxfw/companion/PandoraNative.kt` — add `forgeEdges(...): IntArray?` (signed-µs OOK pulses) and `knockCode(fw): IntArray?`; keep `forge`/`decode`.
- Modify: `hosttest` (ARM) — add an edge-forge + round-trip test if a host harness exists; else a JNI-level assertion documented in the ledger.

**Interfaces:**
- Consumes: existing `pa_emu` (`pa_emu_boot`, `pa_emu_set_button`, `pa_emu_run_frame`, `tx_edge` op, `.knock` in `PA_PROFILE_*`).
- Produces: `PandoraNative.forgeEdges(model, fw, serial, counter, cmd): IntArray?` returning OOK pulses (+on/−off µs); `PandoraNative.knockCode(model, fw): IntArray?`.

- [ ] **Step 1: Test (host or JNI-smoke) — `forgeEdges` for D-605 returns a non-empty signed-µs pulse list whose shortest |cell| ≈ 400 µs; decoding the implied bytes round-trips through `decode`.**
- [ ] **Step 2: Run — Expected: FAIL (`forgeEdges` undefined).**
- [ ] **Step 3: Implement the `tx_edge`-capture forge in `pandora_jni.c` modeled on `pandora-tembed` `pa_transmit()` (press the command button, run frames, collect edges via the `tx_edge` op) and convert insn→µs at the profile cell.**
- [ ] **Step 4: Run — Expected: PASS.**
- [ ] **Step 5: Commit.**

---

### Task 4: Cracker module — ARM knock-scan + PIC PIN recover/brute + variant auto-detect

**Files:**
- Create: `app/src/main/cpp/pandora/cracker.c`, `cracker.h` — `pa_detect_variant(img,len) -> {ARM_D605, ARM_MAX, PIC_DXL, UNKNOWN}`; `pa_crack_knock(img,len, out[8], *n)`; `pic_crack_pin(fw,len) -> int` (−1 = not found). Extract from `PandoraPINBrute/main.c`.
- Create: `app/src/main/cpp/pandora/crack_jni.c`; Kotlin `PandoraCracker.kt`.
- Modify: `CMakeLists.txt`, `hosttest/test_cracker.c` (new).

**Interfaces:**
- Consumes: Task 1 PIC core (for PIN brute), Task 3 ARM profiles (for knock scan).
- Produces: `PandoraCracker.detect(img): Int`, `PandoraCracker.knock(img): IntArray?`, `PandoraCracker.pin(img): Int`.

- [ ] **Step 1: Host tests against the ground-truth oracles:**

```c
void test_detect(void){ assert(pa_detect_variant(d605,ld)==ARM_D605);
                         assert(pa_detect_variant(dxl,lx)==PIC_DXL); }
void test_knock_d605(void){ uint8_t k[8]; int n; pa_crack_knock(d605,ld,k,&n);
    // oracle: "UP OK OK UP UP DOWN"
    assert(n==6 && k[0]==PA_B_UP&&k[1]==PA_B_OK&&k[2]==PA_B_OK&&k[3]==PA_B_UP&&k[4]==PA_B_UP&&k[5]==PA_B_DOWN); }
void test_knock_max(void){ /* oracle: "UP OK DOWN OK UP DOWN" */ }
void test_pin_dxl(void){ assert(pic_crack_pin(dxl,lx)==2552); }       // oracle _PIN.txt
void test_pin_not_found(void){ assert(pic_crack_pin(zeroed,lz)==-1); } // Review Focus: terminates
```

- [ ] **Step 2: `make && ./test_cracker` — Expected: FAIL.**
- [ ] **Step 3: Implement `cracker.c` by porting the detect + knock static-scan + PIN literal-read/brute(0000–9999) from `PandoraPINBrute/main.c`.**
- [ ] **Step 4: `make && ./test_cracker` — Expected: PASS 5/5.**
- [ ] **Step 5: JNI + `PandoraCracker.kt`; wire CMake.**
- [ ] **Step 6: Commit.**

---

### Task 5: Correct the OOK TX radio path in the app

**Files:**
- Modify: `app/src/main/java/com/foxfw/companion/PandoraNative.kt` — `subFile(pulses, freqHz=433926875, model)` writes `Preset: FuriHalSubGhzPresetOok650Async` (stock OOK); drop the `fsk`/`PANDORA_2FSK_REGS` branch. `pulses(...)` half-bit defaults from model (400 D-605 / 500 MAX) — but when `forgeEdges` (Task 3) is used, transmit those captured edges directly.
- Modify: `app/src/main/java/com/foxfw/companion/FlipperRf.kt` — remove `PANDORA_2FSK`; Pandora uses `AM650` (preset 1 = OOK).
- Modify: `app/src/main/cpp/pandora/pandora_jni.c` — keep `nativeModTyp`/`nativeDeviHz` for *reporting only*; they must not steer modulation.

**Interfaces:**
- Consumes: `forgeEdges` (Task 3), `FlipperRf.tx` (Task-unchanged).
- Produces: a `.sub` string that transmits OOK at 433.9269 MHz with the proven cell timing.

- [ ] **Step 1: Test — `subFile(edges, model=D605)` contains `FuriHalSubGhzPresetOok650Async`, `Frequency: 433926875`, no `Custom_preset_data`, and no `2FSK`.**
- [ ] **Step 2: Run — Expected: FAIL.**
- [ ] **Step 3: Implement the edits.**
- [ ] **Step 4: Run — Expected: PASS.**
- [ ] **Step 5: Commit.**

---

### Task 6: Rework `PandoraScreen` into the full native suite

**Files:**
- Modify: `app/src/main/java/com/foxfw/companion/PandoraScreen.kt` — flow: **Load dump** (file picker; bundled examples as defaults) → **Detect** variant (`PandoraCracker.detect`) → **Crack** (knock for ARM via `knock`, PIN for PIC via `pin`; show result, offer save `_KNOCK.txt`/`_PIN.txt`) → **Emulate** (optional: render `framebuffer()`; inject knock + buttons) → **Forge + TX** (OOK via Flipper; status shows "OOK @433.9269", "connect Flipper to transmit" when offline) → **Decode** a captured frame.
- Modify: `WhatsNew.kt` (add the native Pandora-suite entry), `Tools.kt` (tile subtitle if needed).

**Interfaces:**
- Consumes: `PandoraNative` (ARM), `PandoraPicNative` (PIC), `PandoraCracker`, `FlipperRf`.

- [ ] **Step 1: Wire the screen; variant picker D-605 / MAX / DXL-5000; backend chip reflects live Flipper connection.**
- [ ] **Step 2: Build check (part of Task 7). Manual acceptance: load each bundled example → Crack shows the oracle code; Forge shows a frame; TX reports OOK (or the offline message).**
- [ ] **Step 3: Commit.**

---

### Task 7: Build, install, on-device verification

- [ ] **Step 1: `./gradlew assembleDebug` (NDK rebuilds all libs) — Expected: BUILD SUCCESSFUL; all four native libs present in the APK.**
- [ ] **Step 2: `adb install -r` to the S22 Ultra.**
- [ ] **Step 3: On-device: load D-605 / MAX / DXL-5000 examples → Crack returns `UP OK OK UP UP DOWN` / `UP OK DOWN OK UP DOWN` / `2552`. Forge a frame. With Flipper BLE-connected, TX → status "OOK @433.9269"; the `fox_subghz_io` TX runs and exits clean (loader info → none running).**
- [ ] **Step 4: Commit; update memory `project_foxfw_companion_app.md` with the native Pandora suite.**

## Self-Review notes

- Spec coverage: ARM emulate+knock (T3,T4,T6), PIC emulate+forge (T1,T2,T6), PIN/knock cracker (T4), OOK TX correction (T3,T5), full UI (T6), on-device proof (T7). ESP32 variant is intentionally out of scope (it is a Flipper↔ESP32 bridge, not a phone-native target) — noted here so it is a deliberate omission, not a gap.
- Type consistency: `forge`/`forgeEdges`/`pic_forge` return conventions fixed above; `PA_B_*` button enum shared from `pa_emu.h`; variant enum defined once in `cracker.h`.
- Review Focus → tests: wrong-variant (T4 `test_detect`), truncated file (T1 `test_pic_short_buffer_safe`), PIN-not-found terminates (T4 `test_pin_not_found`), counter rollover (T2/T3 forge-differs), Flipper-offline (T6 manual).
