# FoxFW Companion — Flipper-Link Hardening + Decode Fob + USB — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the companion drive the Flipper over BLE *and* USB with proven data flow, and let the user tap a decoded sub to work the full fob (per-button + rolling counter, cipher-forged where we have it, labelled replay otherwise) with the Flipper transmitting on the internal/external CC1101.

**Architecture:** A pure-Python encode engine (Chaquopy) forges frames two ways — Route A (phone renders RAW pulses) and Route B (phone forges fields, the Flipper's own encoder renders a keyed protocol `.sub`). The `fox_subghz_io` FAP gains: honor the `.sub`'s own preset (custom modulation), write an op-status file, and a protocol-`.sub` TX mode. The Kotlin app gains a `FlipperLink` transport abstraction with BLE + new USB (CDC-ACM) implementations behind a `FlipperTransport` manager, a retained-frame `DecodeFeed`, and a `FobScreen`.

**Tech Stack:** Kotlin / Jetpack Compose; Chaquopy Python (≤3.12); Android USB-host CDC-ACM (`usb-serial-for-android`); C FAP built with `fbt`; firmware RPC (protobuf) + BLE serial service.

**Spec:** `docs/superpowers/specs/2026-10-27-companion-flipper-link-and-fob-design.md` (read it; this plan argues from it).

## Global Constraints
- Package `com.foxfw.companion`, Kotlin/Compose, FoxFW brand, existing 5-tab nav — unchanged.
- Build: Temurin JDK21 + Android SDK + NDK r26b + Chaquopy (`buildPython` Python ≤ 3.12). **Grep the gradle log for `BUILD SUCCESSFUL`** (`tail` masks `BUILD FAILED` as exit 0).
- FAP built via the **absolute** `fbt.cmd` path (relative fails silently); grep its log for `APPCHK`/success.
- Workspace is `C:\Users\stone\OneDrive\Desktop\fox apk`. Do **not** run gradle/fbt inside OneDrive (sync-locks `build/`/`.gradle`): build in a local scratch, land APK/FAP + source changes back in `fox apk`.
- **On-device proof** for every RF/firmware/BLE/USB behavior. Host/JVM tests prove logic only; a radio/transport path is not "done" on a compile. Never report a device hop as working without a device run.
- Region UK/ETSI: default sub-GHz 433.92 / 868 SRD; never default to US bands.
- Python modules must stay import-clean under Chaquopy (local + stdlib only, no new pip deps).

## Review Focus
- **Forge correctness per make** — a frame the real receiver rejects (counter window, CRC, bit order, preamble) is a silent failure. Covered partially by the round-trip test in Tasks B2/B3; the real proof is a receiver (Task V1). Label uncertainty in-UI.
- **Custom-preset `.sub` parse** — malformed `Custom_preset_data` must not brick TX; guard + fall back. Test in Task F1.
- **Counter de-sync / off-by-one** — displayed counter must equal what is transmitted. Test in Task B2 (counter contract) and verified in UI Task K3.
- **Replay mislabelling** — a decode-only / replay-only variant must never present as a working rolling fob. Test in Task B1 (`fob_info` route/flags) + Task K3 UI.
- **Transport arbitration / cable pull / permission denial** — active link switches cleanly, UI shows truth, no silent dead transport. Test in Task C1 (arbitration state machine) + verified Task V1.
- **Route B protocol mapping** — wrong phone→firmware protocol/field map TXes the wrong thing silently. Field round-trip through the *phone* decoder in Task B3; through the *firmware* encoder in Task V1.

---

## File Structure

**Python (host-testable, `app/src/main/python/`)**
- `fob_engine.py` — NEW. The variant registry + `fob_info` + `forge` (Routes A & B). Imported by `phone_arke.py`.
- `phone_arke.py` — MODIFY. Re-export `fob_info`/`forge` so Kotlin calls one module.
- `test/test_fob_engine.py` — NEW host tests (run with WSL `python3`).

**FAP C (`faps/src/fox_subghz_io/`, mirrored into the firmware tree to build)**
- `fox_subghz_io.c` — MODIFY. Honor `.sub` preset; write status file; add `TXP` protocol-`.sub` TX.
- `sub_preset.c` / `sub_preset.h` — NEW. Pure `.sub`-preset parse (host-unit-testable).
- `test/test_sub_preset.c` — NEW host test (WSL gcc).

**Kotlin (`app/src/main/java/com/foxfw/companion/`)**
- `FlipperLink.kt` — NEW. Transport interface (RPC surface).
- `FlipperTransport.kt` — NEW. Active-link manager + arbitration.
- `FlipperUsb.kt` — NEW. CDC-ACM link.
- `FlipperBle.kt` — MODIFY. Implement `FlipperLink`.
- `FlipperRf.kt` — MODIFY. Route through `FlipperTransport`; status readback; custom preset; `txProtocol`.
- `ArkeEngine.kt` — MODIFY. `fobInfo()` / `forge()` bridge.
- `DecodeFeed.kt` — MODIFY. `Item` retains `Frame` + `subText`.
- `FobScreen.kt` — NEW. Fob UI.
- `Screens.kt` / `MainActivity.kt` — MODIFY. Tappable decode rows → fob nav; transport indicator; USB offload gating.
- `PsaOffload.kt` — MODIFY. Gate on `customConnected()`.
- `app/src/test/java/com/foxfw/companion/*` — NEW JVM unit tests (RPC framing, arbitration, JSON parse).
- `AndroidManifest.xml` + `res/xml/device_filter.xml` — MODIFY/NEW. USB attach filter.

**Docs**
- `docs/DEVICE-TEST-CHECKLIST.md` — NEW (Task V1).

---

## Task B1: Fob variant registry + `fob_info`

**Files:**
- Create: `app/src/main/python/fob_engine.py`
- Create: `app/src/main/python/test/test_fob_engine.py`

**Interfaces:**
- Consumes: decoder `NAME`s from `arke_all.ALL_DECODERS`; encoder modules (`pp_*`, `arke_kia67_lr`, `barriers`).
- Produces: `fob_info(variant: str) -> dict` → `{has_cipher: bool, route: "A"|"B"|"replay", buttons: [{code:int,label:str}], freq_hz:int, preset:str, custom_regs:str|None}`. A `REGISTRY: dict[str, VariantSpec]` keyed by the decoder `make`/variant string.

- [ ] **Step 1: Write the failing test**
```python
# test_fob_engine.py
def test_fob_info_porsche_is_route_a_with_buttons():
    fi = fob_engine.fob_info("Porsche")
    assert fi["has_cipher"] and fi["route"] == "A"
    assert {b["label"] for b in fi["buttons"]} >= {"Lock", "Unlock"}
    assert fi["freq_hz"] == 433920000 and fi["preset"] in ("AM650","AM270","FM238","FM476")
def test_fob_info_kia_v6_is_route_b():
    assert fob_engine.fob_info("KIA")["route"] == "B"       # firmware-rendered
def test_fob_info_vag_is_replay_only():
    fi = fob_engine.fob_info("VAG"); assert fi["route"] == "replay" and fi["has_cipher"] is False
def test_fob_info_unknown_make_is_replay():
    assert fob_engine.fob_info("NoSuchMake")["route"] == "replay"
```
- [ ] **Step 2: Run, verify fail** — Run: `wsl -d kali-linux python3 -m pytest app/src/main/python/test/test_fob_engine.py -q`. Expected: FAIL (`fob_engine` not found / `fob_info` undefined).
- [ ] **Step 3: Implement** `VariantSpec` + `REGISTRY` + `fob_info(variant)` in `fob_engine.py`. Route-A entries: Porsche, Honda V1/V2, Mazda, Renault V0, Sheriff, Scherkhan, the 6 gates, Pandora. Route-B: KIA (V6). Everything else → `replay` (has_cipher False). Each entry carries buttons (code→label), freq_hz, preset name; unknown make returns the replay default.
- [ ] **Step 4: Run, verify pass** — same command. Expected: PASS.
- [ ] **Step 5: Commit** `git add app/src/main/python/fob_engine.py app/src/main/python/test/test_fob_engine.py && git commit -m "feat(fob): variant registry + fob_info"`

## Task B2: `forge` Route A (phone-rendered RAW pulses) + counter contract

**Files:**
- Modify: `app/src/main/python/fob_engine.py`
- Modify: `app/src/main/python/test/test_fob_engine.py`

**Interfaces:**
- Consumes: `fob_info` (B1); encoders `pp_porsche.compute_frame`+`frame_to_pulses`, `pp_honda_mazda.*_encode`+`_build_upload`, `pp_renault_v0.build_key`+`render_pulses`, `pp_sheriff.encode`+`frame_to_durations`, `pp_scherkhan.pro_encode_frame`, `barriers.one_sub`; `arke_core`/`phone_arke.decode_text` for the round-trip.
- Produces: `forge(variant, serial_hex, counter, button_code) -> dict` → `{"route":"A","sub": "<RAW .sub text>"}` or `{"error": str}`. Per-make adapter so the transmitted counter == the `counter` arg (absorb each encoder's internal +1).

- [ ] **Step 1: Write the failing test** — one round-trip per Route-A cipher family:
```python
@pytest.mark.parametrize("variant,serial", [("Porsche","1A2B3C4D"), ("Honda V1","00AABBCC"),
    ("Mazda","00123456"), ("Renault V0","0A0B0C0D"), ("Sheriff","00112233"), ("Scherkhan","00445566")])
def test_forge_roundtrip_recovers_fields(variant, serial):
    r = fob_engine.forge(variant, serial, counter=0x10, button_code=1)
    assert "sub" in r and r["route"] == "A"
    frames = json.loads(phone_arke.decode_text(r["sub"]))
    hit = [f for f in frames if f.get("serial_hex","").lstrip("0").upper() == serial.lstrip("0").upper()]
    assert hit, f"{variant} forged sub did not decode back"
    assert int(hit[0]["cnt"]) == 0x10          # counter contract: transmitted == requested
    assert int(hit[0]["btn"]) == 1
def test_forge_replay_variant_errors():
    assert "error" in fob_engine.forge("VAG", "DEADBEEF", 1, 1)
```
- [ ] **Step 2: Run, verify fail** — Expected: FAIL (`forge` undefined / KeyError).
- [ ] **Step 3: Implement** `forge` Route-A dispatch + a `.sub` wrapper (`_raw_sub(pulses, freq_hz, preset)` writing `Filetype: Flipper SubGhz RAW File` / `Frequency` / `Preset` / `RAW_Data`). Each adapter normalizes its encoder's counter convention so the decoded `cnt == counter`. Replay/Route-B variants return `{"error": ...}` here (Route B handled in B3).
- [ ] **Step 4: Run, verify pass.** If a family's round-trip can't be made to hold (encoder/decoder mismatch), ledger a ruling and drop that family to `replay` in `fob_info` rather than shipping a bad forge.
- [ ] **Step 5: Commit** `git commit -am "feat(fob): forge Route A + counter contract"`

## Task B3: `forge` Route B (keyed protocol `.sub`) + phone→firmware protocol map

**Files:**
- Modify: `app/src/main/python/fob_engine.py`
- Modify: `app/src/main/python/test/test_fob_engine.py`

**Interfaces:**
- Consumes: `arke_kia67_lr.kia_v6_build_frame(fx, serial, btn, cnt)`; the KIA V6 phone decoder (for field round-trip).
- Produces: `forge(...)` for Route-B variants returns `{"route":"B","sub":"<protocol .sub text>", "protocol": "<firmware protocol name>"}`. A `FW_PROTOCOL: dict[str,str]` mapping phone variant → firmware protocol name (KIA V6 → the FoxFW KIA protocol that owns `Key/Key_2/Key_3`).

- [ ] **Step 1: Write the failing test**
```python
def test_forge_kia_v6_route_b_protocol_sub():
    r = fob_engine.forge("KIA", "0F1E2D3C", counter=0x20, button_code=2)
    assert r["route"] == "B" and r["protocol"]           # names a firmware protocol
    assert "Key:" in r["sub"] and "Protocol:" in r["sub"]  # keyed protocol .sub, not RAW_Data
def test_forge_kia_v6_fields_roundtrip_phone_decoder():
    r = fob_engine.forge("KIA", "0F1E2D3C", 0x20, 2)
    s, c, b, ok = arke_kia67_lr.decode_fields(r)   # or re-decrypt the forged words
    assert s == 0x0F1E2D3C and c == 0x20 and b == 2 and ok
```
- [ ] **Step 2: Run, verify fail** — Expected: FAIL.
- [ ] **Step 3: Implement** Route-B in `forge`: call `kia_v6_build_frame(serial, btn, cnt)` → Key/Key_2/Key_3, write a Flipper **keyed** `.sub` (`Filetype: Flipper SubGhz Key File`, `Protocol: <FW_PROTOCOL["KIA"]>`, `Bit`, `Key`, `Key_2`, `Key_3`, `Serial`, `Cnt`, `Btn` as that protocol's serializer expects), and self-verify the forged words decrypt back to the inputs.
- [ ] **Step 4: Run, verify pass.** (Note: firmware-encoder acceptance of this `.sub` is a device check — Task V1.)
- [ ] **Step 5: Commit** `git commit -am "feat(fob): forge Route B (KIA V6 protocol .sub)"`

## Task F1: FAP honors the `.sub`'s own preset (custom modulation) + fallback

**Files:**
- Create: `faps/src/fox_subghz_io/sub_preset.{c,h}`
- Create: `faps/src/fox_subghz_io/test/test_sub_preset.c`
- Modify: `faps/src/fox_subghz_io/fox_subghz_io.c`

**Interfaces:**
- Produces: `bool fox_sub_preset_parse(const char* sub_text, FoxPreset* out)` → fills `{has_custom, name[32], regs[64], regs_len}` or `{has_custom=false}` when no `Custom_preset_data`. `fox_do_tx`/`fox_do_rx` call it: custom → `FuriHalSubGhzPresetCustom` + regs; else the CLI preset index as today.

- [ ] **Step 1: Write the failing test** (pure C, WSL gcc):
```c
// test_sub_preset.c
void test_parses_custom_regs(void){ /* feed a .sub with Custom_preset_data: 02 0D ... ; expect has_custom && regs_len>0 && regs[0]==0x02 */ }
void test_no_custom_returns_false(void){ /* .sub with Preset: FuriHalSubGhzPresetOok650Async -> has_custom==false */ }
void test_malformed_regs_rejected(void){ /* odd/garbage Custom_preset_data -> has_custom==false (fall back) */ }
```
- [ ] **Step 2: Run, verify fail** — Run: `wsl -d kali-linux bash -lc 'gcc -I faps/src/fox_subghz_io faps/src/fox_subghz_io/sub_preset.c faps/src/fox_subghz_io/test/test_sub_preset.c -o /tmp/tsp && /tmp/tsp'`. Expected: FAIL (link error — `fox_sub_preset_parse` undefined).
- [ ] **Step 3: Implement** `sub_preset.c` (pure string/hex parse, no furi). Then wire `fox_do_tx`/`fox_do_rx` to prefer the parsed custom preset, else the CLI index. Guard: malformed → fall back to the CLI index (never TX on an undefined preset).
- [ ] **Step 4: Run, verify pass** (host test). Then build the FAP: `<abs>\fbt.cmd fap_fox_subghz_io COMPACT=1 DEBUG=0` — Expected: `APPCHK ... fox_subghz_io.fap` passes. (Radio behavior = Task V1.)
- [ ] **Step 5: Commit** `git commit -am "feat(fap): honor .sub-embedded preset + fallback"`

## Task F2: FAP writes an op-status file

**Files:**
- Modify: `faps/src/fox_subghz_io/fox_subghz_io.c`

**Interfaces:**
- Produces: every op writes `/ext/apps_data/fox_companion/io_status.txt` as its last act — one token: `ok` / `radio-fail` / `no-device` / `bad-args`. Written even on the error paths (before `return`).

- [ ] **Step 1 (host-unit where possible, else device):** factor the exit→token mapping into `const char* fox_status_token(int code)` with a tiny host test (`-2→"radio-fail"`, `-3→"no-device"`, `0→"ok"`). Run via WSL gcc. Expected: FAIL then PASS.
- [ ] **Step 2: Implement** the status-file write at each op's end (success and every early-return error), using the mapped token; create the dir if missing.
- [ ] **Step 3: Build** the FAP (as F1). Expected: APPCHK pass.
- [ ] **Step 4: Commit** `git commit -am "feat(fap): write io_status.txt op result"`

## Task F3: FAP `TXP` — TX a keyed protocol `.sub` via the firmware encoder (Route B)

**Files:**
- Modify: `faps/src/fox_subghz_io/fox_subghz_io.c`

**Interfaces:**
- Consumes: the firmware protocol registry + `subghz_transmitter_*` / `subghz_txrx_create_protocol_key` path (as used by `ProtoPirate` emulate and `applications/.../subghz_txrx_create_protocol_key.c`).
- Produces: new arg form `TXP <subpath> <freq> <dev>` — loads the keyed `.sub` through the protocol registry, builds the transmitter, and `subghz_devices_start_async_tx`. Writes `io_status.txt` (F2). dev 0/1/2 = int/ext/auto.

- [ ] **Step 1 (device-verified task):** Implement `TXP` modeled on the firmware's existing protocol-TX path (deserialize `.sub` → `subghz_transmitter_deserialize` → async TX). Build the FAP (APPCHK pass).
- [ ] **Step 2: Verify** on hardware (folded into Task V1): push a KIA V6 protocol `.sub` forged by B3, `loader open` the FAP with `TXP …`, confirm `io_status.txt == ok` and a second Flipper/SDR receives a KIA frame. Expected: decode matches forged serial/cnt/btn. If the firmware encoder rejects the `.sub` shape, ledger a ruling and fix the B3 serializer to match the firmware deserializer.
- [ ] **Step 3: Commit** `git commit -am "feat(fap): TXP protocol-.sub TX via firmware encoder"`

## Task K1: `DecodeFeed` retains frame + sub; `ArkeEngine` fob bridge; tappable rows

**Files:**
- Modify: `app/src/main/java/com/foxfw/companion/DecodeFeed.kt`, `ArkeEngine.kt`, `Screens.kt` (DecodeScreen), `phone_arke.py`
- Create: `app/src/test/java/com/foxfw/companion/FobBridgeTest.kt`

**Interfaces:**
- Consumes: B1/B2/B3 (`fob_info`, `forge`); `phone_arke` re-exports them.
- Produces: `DecodeFeed.Item` gains `frame: ArkeEngine.Frame` + `subText: String`. `ArkeEngine.fobInfo(make): FobInfo?` and `ArkeEngine.forge(make, serialHex, counter, button): ForgeResult` (`sealed`: `Raw(sub)`, `Protocol(sub, protocol)`, `Err(msg)`). `FobInfo(hasCipher, route, buttons:List<FobButton>, freqHz, preset, customRegs)`.

- [ ] **Step 1: Write the failing test** (JVM, parsing only — no Python runtime): feed canned `fob_info`/`forge` JSON strings to the Kotlin parser and assert the mapped `FobInfo`/`ForgeResult`:
```kotlin
@Test fun parsesFobInfoRouteAndButtons() { /* JSON -> FobInfo: route A, 2 buttons, freqHz 433920000 */ }
@Test fun parsesForgeProtocolResult() { /* {"route":"B","sub":..,"protocol":..} -> ForgeResult.Protocol */ }
@Test fun parsesForgeError() { /* {"error":".."} -> ForgeResult.Err */ }
```
- [ ] **Step 2: Run, verify fail** — Run: `./gradlew :app:testDebugUnitTest --tests '*FobBridgeTest*'` (in local scratch). Expected: FAIL (types/parser missing).
- [ ] **Step 3: Implement** the `FobInfo`/`FobButton`/`ForgeResult` types + JSON parsers in `ArkeEngine`; add `fob_info`/`forge` re-exports in `phone_arke.py`; extend `DecodeFeed.Item` (+ `add` keeps `frame`/`subText`); make `DecodeScreen` rows `clickable` invoking an `onOpenFob(Item)` callback.
- [ ] **Step 4: Run, verify pass** (unit tests). Build the APK (grep `BUILD SUCCESSFUL`).
- [ ] **Step 5: Commit** `git commit -am "feat(fob): retain frame+sub, ArkeEngine fob bridge, tappable rows"`

## Task K2: `FlipperRf` — status readback, custom preset, `txProtocol`; PSA gate fix

**Files:**
- Modify: `app/src/main/java/com/foxfw/companion/FlipperRf.kt`, `PsaOffload.kt`
- Create: `app/src/test/java/com/foxfw/companion/FlipperRfTest.kt`

**Interfaces:**
- Consumes: F1 (preset), F2 (`io_status.txt`), F3 (`TXP`); `ArkeEngine.ForgeResult`.
- Produces: `FlipperRf.tx(subText, freqHz, dev, preset, customRegs?): TxResult`, `FlipperRf.txProtocol(protoSub, dev): TxResult`, `FlipperRf.rx(...): RxResult(status, sub?)`. `TxResult` = `Ok | RadioFail | NoDevice | BadArgs | Launched(false)`. Status read from `io_status.txt` after `appStart`.

- [ ] **Step 1: Write the failing test** (JVM): a fake `FlipperLink` returning canned `io_status.txt` bytes → assert `tx` maps `"radio-fail"`→`TxResult.RadioFail`, `"ok"`→`Ok`, missing file→`Launched(false)`. And `PsaOffload` sends only when `customConnected()` (fake link toggles).
- [ ] **Step 2: Run, verify fail** — `./gradlew :app:testDebugUnitTest --tests '*FlipperRfTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** status readback (write preset/customRegs into the `.sub` or pass as args per F1; after `appStart`, read `io_status.txt`, map to `TxResult`); `txProtocol` → `appStart("… TXP …")`; `PsaOffload` gate `rpcConnected()` → `customConnected()`.
- [ ] **Step 4: Run, verify pass;** build APK (`BUILD SUCCESSFUL`).
- [ ] **Step 5: Commit** `git commit -am "feat(fob): FlipperRf status readback + custom preset + txProtocol; psa gate fix"`

## Task K3: `FobScreen` — per-button pad, counter, send/bump, replay labelling

**Files:**
- Create: `app/src/main/java/com/foxfw/companion/FobScreen.kt`
- Modify: `Screens.kt` / `MainActivity.kt` (nav Decode row → FobScreen)

**Interfaces:**
- Consumes: K1 (`Item` w/ frame+sub, `fobInfo`/`forge`), K2 (`FlipperRf.tx`/`txProtocol` + `TxResult`).
- Produces: `FobScreen(item, onBack)` Composable.

- [ ] **Step 1 (device-verified UI task):** Build `FobScreen`: header (make/serial), a **per-button pad** from `fobInfo.buttons`, a **counter** field (shows `frame.cnt`) with **Send next** (counter+1, forge, TX) and **manual bump/set**; CC1101 Int/Ext/Auto selector. `has_cipher` → forge+TX via Route A (`tx`) or Route B (`txProtocol`); else a clearly-labelled **"Replay (won't open a rolling receiver)"** button that re-sends `item.subText`. Surface `TxResult` (sent / radio-fail / no-device / not-connected). Follow FoxFW brand + existing `FoxCard`/`PickChip`/`PickDropdown`.
- [ ] **Step 2: Build** APK. Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 3: Verify (device, Task V1):** tap a decoded Porsche/KIA sub → pad renders, Send next advances the displayed counter and the TX result reflects reality; a decode-only (VAG) sub shows replay-only labelling and no forge button.
- [ ] **Step 4: Commit** `git commit -am "feat(fob): FobScreen per-button pad + counter + replay labelling"`

## Task C1: `FlipperLink` abstraction + `FlipperTransport` + BLE conforms

**Files:**
- Create: `app/src/main/java/com/foxfw/companion/FlipperLink.kt`, `FlipperTransport.kt`
- Modify: `FlipperBle.kt` (implement `FlipperLink`), `FlipperRf.kt` (use `FlipperTransport`)
- Create: `app/src/test/java/com/foxfw/companion/TransportArbitrationTest.kt`

**Interfaces:**
- Produces: `interface FlipperLink` (suspend `storageWrite/read/mkdir/delete/list/stat`, `appStart`, `deviceInfo/powerInfo/getDateTime/setDateTime/playAlert/reboot/factoryReset`, screen stream + input, `val connected: Boolean`, `val kind: LinkKind`). `object FlipperTransport { val active: FlipperLink?; fun prefer(...) ; val kind }` — prefers USB when both present, falls back to BLE on loss. `FlipperRf.connected`/calls route through `FlipperTransport.active`.

- [ ] **Step 1: Write the failing test** (JVM, pure arbitration logic): with fake BLE+USB links, assert: USB present ⇒ `active==usb`; USB detach ⇒ `active==ble`; neither ⇒ `active==null`; a switch mid-"transfer" flips `active` and reports the new `kind`.
- [ ] **Step 2: Run, verify fail** — `./gradlew :app:testDebugUnitTest --tests '*TransportArbitration*'`. Expected: FAIL.
- [ ] **Step 3: Implement** `FlipperLink`; make `FlipperBle` implement it (rename nothing BLE-only — the custom-channel methods stay on `FlipperBle` concretely); `FlipperTransport` arbitration; point `FlipperRf`'s `ble` getter at `FlipperTransport.active`. (Hitag/PSA screens keep using `FoxApplication.instance.ble` directly.)
- [ ] **Step 4: Run, verify pass;** build APK. Expected: `BUILD SUCCESSFUL` and existing BLE features unaffected.
- [ ] **Step 5: Commit** `git commit -am "feat(usb): FlipperLink abstraction + FlipperTransport; BLE conforms"`

## Task C2: `FlipperUsb` — CDC-ACM RPC link

**Files:**
- Create: `app/src/main/java/com/foxfw/companion/FlipperUsb.kt`
- Modify: `app/build.gradle.kts` (add `usb-serial-for-android`), `app/src/main/java/.../Proto.kt` (reuse framing; extract the varint-length reassembler so USB + BLE share it)
- Create: `app/src/test/java/com/foxfw/companion/RpcFramingTest.kt`

**Interfaces:**
- Consumes: C1 (`FlipperLink`), `Proto` codec.
- Produces: `class FlipperUsb(ctx, UsbDevice) : FlipperLink` — opens CDC-ACM, sends `start_rpc_session\r`, streams PB_Main; same pending-RPC map + reassembler as BLE (no credit gating / 448 B chunking).

- [ ] **Step 1: Write the failing test** (JVM): extract the reassembler into a pure `RpcReassembler`; feed it split/combined varint-delimited frames and assert exact message boundaries (same cases as BLE `onRpcData`). Expected: FAIL (class missing), then PASS.
- [ ] **Step 2: Implement** `FlipperUsb` (usb-serial-for-android ProbeTable: VID `0x0483` PID `0x5740` → CdcAcmSerialDriver; confirm on device), the `start_rpc_session` handshake, and the shared `RpcReassembler`. Build APK (`BUILD SUCCESSFUL`).
- [ ] **Step 3: Verify (device, Task V1):** cable-connect; `FlipperUsb` opens, `appStart`+storage round-trip works over USB.
- [ ] **Step 4: Commit** `git commit -am "feat(usb): FlipperUsb CDC-ACM RPC link"`

## Task C3: USB auto-detect + transport indicator + offload gating

**Files:**
- Modify: `AndroidManifest.xml`, `MainActivity.kt`, Tools/Hitag/PSA screens
- Create: `res/xml/device_filter.xml`

**Interfaces:**
- Consumes: C1 (`FlipperTransport`), C2 (`FlipperUsb`).
- Produces: a `BroadcastReceiver` (ATTACHED/DETACHED) + `UsbManager.requestPermission` → opens `FlipperUsb` into `FlipperTransport`; header indicator (USB / BLE / offline); Hitag/PSA tiles disabled with "needs Bluetooth" when `active.kind == USB`.

- [ ] **Step 1 (device-verified task):** Add `uses-feature android.hardware.usb.host`, the `USB_DEVICE_ATTACHED` intent-filter + `device_filter.xml` (Flipper VID/PID), the receiver + permission flow, the indicator, and the offload gating. Build APK (`BUILD SUCCESSFUL`).
- [ ] **Step 2: Verify (device, Task V1):** plug Flipper in → app auto-detects + prompts permission → indicator shows USB; unplug → falls back to BLE; on USB-only, Hitag/PSA tiles show "needs Bluetooth."
- [ ] **Step 3: Commit** `git commit -am "feat(usb): auto-detect + transport indicator + offload gating"`

## Task V1: Device verification pass (Part A proof + fob + USB)

**Files:**
- Create: `docs/DEVICE-TEST-CHECKLIST.md`

**Interfaces:** Consumes everything. Produces a checklist + recorded results.

- [ ] **Step 1:** Write `DEVICE-TEST-CHECKLIST.md` enumerating each hop: BLE connect; `appStart`+storage over BLE and over USB; Decode RX → decode; fob Route A TX (Porsche) received by a 2nd SDR/Flipper; fob Route B TX (KIA V6) received + decoded = forged fields; Archive replay honors capture modulation; Hitag/Fiat capture → offload → crack → result loop (BLE); PSA offload loop (BLE); NFC FAP launch; settings sync; USB auto-detect + fallback; USB-only offload gating.
- [ ] **Step 2 (with the user, on hardware):** run the checklist; record pass/fail + evidence per row. Any failure → `systematic-debugging`, fix in the owning task, re-run that row. Do **not** mark a row done without a device result.
- [ ] **Step 3: Commit** the filled checklist `git commit -am "docs: device-test checklist + results"`

---

## Notes for the executor
- Tasks B1–C2 have real host/JVM tests (TDD). F3, K3, C3 are device-verified (UI/radio/USB with no host harness) — build-clean is the per-task gate, real proof is Task V1; keep those honest in the ledger.
- Part C (C1–C3) is separable from A+B if you want to ship the fob first; C1 is the only part that touches shared code (`FlipperRf`), so land it before relying on USB.
- Do gradle/fbt builds in a local scratch, not inside OneDrive; land artifacts back in `fox apk`.
