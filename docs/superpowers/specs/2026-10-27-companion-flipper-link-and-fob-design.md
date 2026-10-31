# FoxFW Companion — Flipper-Link Hardening + Decode Fob (Design)

**Date:** 2026-10-27
**Component:** FoxFW companion Android app (`com.foxfw.companion`, v2.1) + the headless
`fox_subghz_io.fap` radio bridge.
**Workspace:** `C:\Users\stone\OneDrive\Desktop\fox apk` — app source in `source/`, FAPs
(binary + source) in `faps/`.

---

## 1. Intent & why

The companion app already has a broad feature set, but two things are unproven or missing:

1. **Nobody has proven that every feature's Flipper link actually carries data end-to-end
   over *live BLE*.** Many paths were validated over the USB CLI or on one channel, but the
   "launch a FAP + push/pull files over BLE" hop has been repeatedly built-but-not-fully-proven.
2. **You can decode a sub-GHz fob but you can't *use* it from the phone** the way FoxFW's
   on-device fob view lets you — per-button, with the rolling counter, forging a fresh valid
   code when we hold the cipher.

This project hardens the first and builds the second. The end state: the phone is a full
**fob + offload console** driving the Flipper's CC1101 (internal or external), not just a
decoder readout.

## 2. Goals

### Part A — Flipper-link audit & hardening
- Prove, on hardware, the **live-BLE** path for every file-based feature: `appStart` of
  `fox_subghz_io` / `fox_nfc_io` / `fox_import_settings`, plus chunked storage write/read.
  This is the recurring built-but-unproven hop.
- Confirm the **Hitag2/Fiat capture → phone cracker → result** loop end-to-end over BLE.
  (Both ends are built and byte-compatible: firmware `subghz_scene_fiat_v1_recover.c` emits
  `BEGIN 0x11`/`TRACE 0x12`/`CANCEL 0x14`; app `FlipperBle` → `CrackService` → `NativeSolver`
  cracks and returns `PROGRESS 0x15`/`RESULT 0x16`.)
- Confirm the **PSA TEA offload** loop end-to-end over BLE.
- **Surface the FAP's real op result to the phone.** Today `FlipperRf.tx` returns true when the
  FAP was *launched* (`appStart` status 0), not when TX actually happened, and the FAP's exit
  codes never reach the phone. Add a one-line status file the FAP writes at the end of each op
  (`ok` / `radio-fail` / `no-device` / `bad-args`); `FlipperRf` reads it after `appStart` (the way
  `rx` already reads `rx.sub`) and surfaces the real result — so a launched-but-failed op is
  reported, not assumed. This is what makes Part A honest certification rather than "it launched."
- Fix the small correctness issues the recon found (see §6).

### Part B — Decode fob
- Decoded items **retain their frame** (serial / counter / button / next) and the **raw `.sub`**;
  Decode rows become tappable.
- A **fob screen**: per-button pad (buttons come from the protocol) + a live counter with
  "send next" and a manual bump — FoxFW's fob behavior.
- **Cipher-backed** protocols forge a *fresh valid frame per press* and TX it; **decode-only**
  protocols fall back to **replay, explicitly labelled**.
- A **general custom-modulation TX path** so the fob/Decode is not limited to the 4 fixed
  presets (covers the Pandora 2-FSK dialog preset and any arbitrary deviation).

### Part C — USB transport
- Let the user drive the Flipper over **USB** as well as Bluetooth, and **auto-detect** when a
  Flipper Zero is plugged in. Every RPC feature (the whole fob, Decode, Archive, Pandora, NFC,
  settings, screen mirror) works over USB; only the BLE-only offloads do not (see §6.6).

## 3. Non-goals (this project)
- **Porting TX encoders for the decode-only makes** — VAG/Audi, BMW, Ford, Toyota, Mazda-GM,
  most Mitsubishi, generic KeeLoq. Deferred follow-on. These are **replay-only** in the fob
  for now, and labelled as such.
- New decoders or new protocols.
- **KeeLoq-capture offload to the phone cracker** — the firmware has a keeloq decrypt scene,
  but the app's solver is Hitag2-specific. Out of scope; noted only.
- Any cloud functionality.
- **Hitag/PSA offload over USB** — the firmware's offload channel is BLE-only (`bt_custom_data_tx`);
  a USB custom-data path would be a firmware change. Out of scope; those two features require BLE.

## 4. Anti-goals (would be a failure even if it technically works)
- A fob that **silently replays a rolling code as if it were a working press** — replay must
  be labelled so the user is never misled about why a receiver didn't respond.
- Any **regression to the proven comms**: screen mirror, remote input, SD browser, settings
  sync, Chameleon-over-BLE.
- TX **outside the user's UK/ETSI SRD norms** by default (433.92 / 868).

## 5. Constraints
- **Platform/brand fixed:** Kotlin / Jetpack Compose, package `com.foxfw.companion`, the FoxFW
  brand, the existing 5-tab navigation and screen set.
- **Build:** Temurin JDK21 + Android SDK + NDK r26b + Chaquopy (`buildPython` must be Python
  ≤ 3.12). `tail` on the gradle log masks `BUILD FAILED` as exit 0 — **grep the log for
  `BUILD SUCCESSFUL`**. FAP via the **absolute** `fbt.cmd` path (relative fails silently);
  grep its log too.
- **Workspace / OneDrive:** everything lands under `fox apk`. Building *inside* a OneDrive-synced
  folder risks sync-locks on `build/` and `.gradle`; build in a local scratch or pause sync,
  then land the APK/FAP + source changes back in `fox apk`.
- **Honesty of completion:** RF/firmware features are "done" **only with on-device proof**.
  Host/unit tests prove the logic; the live BLE/RF hops are the user's to run (or run together
  in a device session). Never report a radio path as working on a compile alone.
- **Region:** UK/ETSI. Default sub-GHz to 433.92 / 868 SRD; never silently default to US bands.

## 6. The approach (decided)

### 6.1 Fob behavior (approved)
Tap a decoded sub → fob opens showing a **per-button pad** (Lock / Unlock / Trunk / Panic / …
per the protocol) and a **live counter**. "Send" forges + TXes the next valid code and advances
the counter; a manual **bump** (+1 / set value) lets the user re-sync if the car has rolled
ahead. **Cipher present** → forge fresh valid frame; **cipher absent** → replay the captured
frame, clearly labelled "replay only — won't open a rolling receiver."

Cipher-backed families with a **verified pulse renderer *and* counter recovery** (the forge
targets): **Porsche, Honda (V1 OOK / V2 FSK), Mazda, Renault V0, Sheriff, Scherkhan, all
fixed-code gates (CAME/NICE/Holtek/Princeton/Linear/Chamberlain), Pandora** (native).

**KIA V6** is a forge target via **Route B** (§6.2a), not replay-only: the phone forges the frame
*fields* (`kia_v6_build_frame(serial, btn, cnt+1)` is fixed and working — the AES-key bug was
resolved earlier) and the Flipper renders + TXes them with its own KIA encoder, so no phone-side
pulse renderer is needed. **Fiat Ducato stays replay-only**: its counter is encrypted and not
recoverable from a capture, so there is no next code to forge by either route.

### 6.2 Encode bridge (new, Python / Chaquopy — host-testable, TDD)
Add to `phone_arke.py`:
- `fob_info(variant) -> {has_cipher: bool, buttons: [{code, label}], freq_hz, preset}` — keyed on
  the **decoded protocol variant** (e.g. Honda V1 vs V2), not the broad make, because modulation is
  not 1:1 with make (Honda V1 is OOK/AM, V2 is FSK/FM). `freq_hz` + `preset` are transcribed from the
  firmware ProtoPirate **per-protocol FrequencyPreset** (in scope — the Python decoders only carry TE
  timings). A variant needing a deviation outside AM270/AM650/FM238/FM476 uses the §6.3 custom-preset
  path. Tells the UI what pad to draw and how to TX.
- `forge(variant, serial_hex, counter, button_code) -> sub_text | {error}` — dispatches to that
  variant's encoder (`pp_porsche.compute_frame`+`frame_to_pulses`, `pp_honda_mazda.*_encode`+
  `_build_upload`, `pp_renault_v0.build_key`+`render_pulses`, `pp_sheriff.encode`+`frame_to_durations`,
  `pp_scherkhan.pro_encode_frame`, `barriers.one_sub`), renders pulses, and wraps them in a Flipper
  RAW `.sub` with the `freq_hz` + `preset` from `fob_info`. **Counter contract:** `forge` transmits the
  frame for **exactly `counter`** (the value the UI displays); per-make adapters normalize each
  encoder's internal +1 convention so transmitted == `counter`. The UI's "send next" passes
  displayed+1, the manual bump sets the value, and the displayed counter always equals what is sent
  (§8.3). Replay-only variants return `{error}` → UI shows labelled replay of the captured `.sub`.
- **TDD gate (WSL python):** `forge(make, serial, counter, btn)` → `decode_text(sub)` must recover
  the same serial / counter / button for every cipher-backed make. (Round-trip is necessary,
  not sufficient — see §8.)

### 6.2a Two TX routes
A forged frame reaches the air one of two ways; the fob picks per variant from `fob_info`:
- **Route A — phone-rendered RAW pulses.** For the `pp_*` clone ciphers (Porsche / Honda / Mazda /
  Renault / Sheriff / Scherkhan), Pandora, and fixed-code gates — protocols FoxFW has no on-device
  encoder for — the phone renders pulses into a RAW `.sub` and the FAP replays them.
- **Route B — Flipper-rendered protocol `.sub`.** For protocols FoxFW *does* encode (KIA now; the
  groundwork for un-deferring VAG/etc. later) the phone computes the forged fields
  (serial/counter/key) and writes a *keyed protocol* `.sub`; the FAP TXes it through the firmware's
  own encoder (the `ProtoPirate` emulate path / `subghz_txrx_create_protocol_key` + protocol
  registry — both already in the firmware), and the Flipper does the Manchester rendering.
- Route B needs `fox_subghz_io` to gain a protocol-`.sub` TX mode (or reuse ProtoPirate's emulate)
  and a phone-variant → firmware-protocol name map. **Build Route B's plumbing once, with KIA V6 as
  the first client.**

### 6.3 General custom modulation (FAP change, `fox_subghz_io`)
Make the TX path **honor the preset embedded in the `.sub`** (`Preset:` plus
`Custom_preset_module` / `Custom_preset_data`) instead of only the CLI preset index; fall back
to the CLI index when the `.sub` carries no custom preset. This one change:
- fixes **Archive replay** using the wrong modulation (today it always TXes AM650) — the main payoff,
- enables **arbitrary 2-FSK / custom deviation** for any variant whose `fob_info` preset is not one
  of the four built-ins,
- **retires the half-wired hardcoded preset-4 hack** — the phone just writes a `.sub` carrying
  whatever registers it wants. (This only matters for dialog-class Pandora; the D-605/MAX keyfobs we
  actually emulate are OOK and correctly never use a 2-FSK preset, so the original "Pandora 2-FSK"
  motivation is largely moot — the real value here is Archive correctness + arbitrary deviation.)

App side: `FlipperRf.tx` writes the chosen preset (standard index or custom register block) into
the `.sub` it generates; the FAP honors it.

### 6.4 Data model + UI
- Extend `DecodeFeed.Item` to carry the `ArkeEngine.Frame` + the raw `subText`.
- `DecodeScreen` rows get `onClick` → navigate to a new `FobScreen.kt` (Compose, FoxFW brand,
  reusing existing cards/pills/chips/dropdowns). TX via `FlipperRf.tx` at the item's frequency +
  (custom) preset, with the Int / Ext / Auto CC1101 selector.

### 6.5 Fixes found in recon
- `PsaOffload` gates its result send on `rpcConnected()` (fe62) but sends over the custom
  channel (fe65); it should gate on `customConnected()`. Latent today (both resolve together),
  fix for correctness.
- Archive TX modulation bug is resolved by §6.3 (FAP honors the `.sub`'s own `Preset:`).

### 6.6 USB transport + auto-detect (Part C)
The Flipper's RPC rides a USB CDC/VCP serial port exactly as it rides BLE (`start_rpc_session` in
`rpc.c`, over `cli_vcp`), so every RPC feature works over a cable. Add USB as a second transport:
- **Abstraction:** extract the RPC surface (`storage*`, `appStart`, `list`, system calls, screen
  stream, input) into a `FlipperLink` interface. `FlipperBle` implements it (existing); a new
  `FlipperUsb` implements it over CDC-ACM. A `FlipperTransport` owns the *active* link; `FlipperRf`
  and every RPC screen go through it. The BLE-only custom-channel methods (offload `incoming`,
  `sendProgress`/`sendResult`, PSA) stay on `FlipperBle`.
- **FlipperUsb:** Android USB-host CDC-ACM (via `usb-serial-for-android` with a ProbeTable entry for
  the Flipper, VID `0x0483` / PID `0x5740` — confirm on-device). On open: send `start_rpc_session\r`,
  then the same `Proto` varint-delimited PB_Main stream + reassembler as BLE (no 1024-byte credit
  gating / 448 B chunking needed — USB bulk drains fast).
- **Auto-detect:** manifest `uses-feature android.hardware.usb.host` + a `USB_DEVICE_ATTACHED`
  intent-filter with a `device_filter.xml` (Flipper VID/PID) so Android offers to open the app on
  plug-in; a runtime `BroadcastReceiver` for ATTACHED/DETACHED → `UsbManager.requestPermission` →
  open `FlipperUsb` and make it active. Prefer USB when both are present (faster / more reliable);
  on detach, fall back to BLE. A header indicator shows USB / BLE / offline.
- **Honest limit:** on a **USB-only** connection the Hitag/PSA **offload** tiles are disabled with
  "needs Bluetooth" — the firmware's offload emitter uses the BLE-only custom-data channel
  (`bt_custom_data_tx`), which has no USB path. Everything else works over USB.

## 7. What's left to the builder
- Exact fob pad visual/layout (follow the FoxFW brand + existing Compose patterns).
- **Per-protocol button maps** (code → label) for the encoder-backed families — read each
  encoder's `button` argument semantics.
- Confirm `subghz_file_encoder_worker` TX in `fox_subghz_io` has the preset loaded before the
  async TX starts, and parse the `.sub` preset accordingly (guard malformed data).
- The **device test checklist** for the live-BLE hops (connect → capture → crack → result;
  decode → fob → TX; archive replay; NFC FAP launch; settings sync).

## 8. Review focus (what the unit tests will NOT catch)
1. **Forge correctness per make** — a forged frame the real receiver rejects (counter window,
   CRC, bit order, preamble) is the worst *silent* failure. Round-trip decode is necessary but
   not sufficient; the only real proof is a receiver/car. Label uncertainty honestly.
2. **Custom-preset `.sub` parsing** — malformed `Custom_preset_data` must not brick the FAP TX
   (guard + fall back to a stock preset), and must not TX on the wrong deviation.
3. **Counter de-sync UX** — forging "next" when the car has moved ahead; the manual bump must be
   obvious and the displayed counter must match what will be sent.
4. **Replay mislabelling** — a decode-only protocol must never render as a working rolling fob.
5. **BLE `appStart` failure** — e.g. loader locked during the Flipper first-boot wizard → a clear
   user message, never a silent no-op.
6. **USB/BLE transport arbitration** — both links present, or a cable pulled mid-op: the active
   link must switch cleanly without a half-finished storage transfer corrupting state, and the UI
   must show the truth. USB permission denial → clear message, not a silent dead transport.
7. **Route B protocol mapping** — a wrong phone-variant → firmware-protocol map (or a field the
   firmware encoder rejects) TXes the wrong thing silently. Verify a KIA V6 field set round-trips
   through the *firmware* encoder, not just the phone decoder.
