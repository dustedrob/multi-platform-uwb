# CLAUDE.md

Guidance for working in this repository. Keep it accurate: when behavior described here changes,
update this file in the same PR.

## What this repo is

A **Kotlin Multiplatform library (`uwbmodule`)** for Ultra-Wideband (UWB) ranging between nearby
devices on **Android and iOS**, plus a **Compose Multiplatform sample app (`composeApp`)** that
exercises it. BLE is used for discovery and to exchange UWB session parameters; UWB (androidx.core.uwb
on Android, NearbyInteraction on iOS) does the actual ranging.

### Stated goals (from README, repo description, and issue tracker)

1. **Phone-to-phone ranging**: Android↔Android and iPhone↔iPhone, distance plus angle where hardware allows.
2. **Accessory ranging**: phones ranging with UWB boards (Qorvo/NXP, e.g. DWM3001 on nRF52840). iOS uses
   Apple's standard Nearby Interaction Accessory Protocol; Android uses a bespoke protocol that needs
   matching custom firmware (firmware lives outside this repo).
3. **Multi-device ranging**: one session per peer, several peers concurrently (landed in PR #57).
4. **Vendor-neutral library**: the library ships only the mechanism and its own `LOCAL_PROFILE`; vendor
   UUIDs/profiles belong in the consuming app (see `composeApp/.../AccessoryProfiles.kt`).

## Build, test, verify

Gradle needs JDK 17–21 (CI uses Temurin 21; the JBR bundled with Android Studio works). Newer JDKs
fail with an unhelpful error, so point `JAVA_HOME` at one explicitly:

```bash
export JAVA_HOME=/path/to/jdk-17-or-21

# Compile (what CI runs)
./gradlew :uwbmodule:compileDebugKotlinAndroid :composeApp:compileDebugKotlinAndroid
./gradlew :uwbmodule:compileKotlinIosSimulatorArm64 :composeApp:compileKotlinIosSimulatorArm64

# Unit tests (commonTest runs on both targets)
./gradlew :uwbmodule:testDebugUnitTest :uwbmodule:iosSimulatorArm64Test
```

- **Always compile the iOS target after touching `iosMain`.** Kotlin/Native ObjC interop relaxes null and
  cast checks, so code that looks fine can fail only at `compileKotlinIosSimulatorArm64`.
- Real behavior needs real hardware: UWB and BLE do not work on the iOS simulator or Android emulator.
  Contributors (mainly @sdetweil) run hardware tests and post logs in issues/PRs; treat those logs as the
  ground truth for ranging behavior.
- CI (`.github/workflows/build.yml`): Android job on ubuntu; iOS job on a self-hosted macOS runner
  (labels `self-hosted, macOS, ARM64, uwb`) that **skips PRs from forks**. Contributor PRs therefore get
  no iOS CI: compile iOS locally before merging them.
- Tests live in `uwbmodule/src/commonTest`. `DeviceDiscoveryManager` cannot be constructed there
  (its deps are `expect` classes), so tests cover the wire format, profiles, `NearbyDevice`, and the
  `internal` pure helpers (`connectionScore`, `identityKeyFor`, `sessionIdFor`, `ownsSessionOver`).
  Put new decision logic in pure helpers like these so it is testable.

## Architecture

```
composeApp (sample)          uwbmodule (library, package com.dustedrob.uwb)
  UwbDiscoveryViewModel  -->   DeviceDiscoveryManager      (commonMain: orchestrator, owns state)
  AccessoryProfiles             |-- BleManager             (expect; androidMain / iosMain actuals)
  Permissions.*                 |-- MultiplatformUwbManager (expect; androidMain / iosMain actuals)
                                |-- ManagerFactory         (expect; one shared UwbManager per factory)
                                UwbSessionConfig (wire format), UwbProfile/BleDiscoveryConfig, NearbyDevice
```

### Pipeline (per peer)

1. **Discover**: BLE scan matches an advertised service UUID against `BleDiscoveryConfig.profiles`.
   The BLE layer asks `MultiplatformUwbManager` to prepare a per-peer local config *before* reporting
   the device, so the orchestrator can hand that config straight to the exchange.
2. **Exchange** (`ExchangeProtocol`):
   - `ReadWrite` (phone↔phone): client reads the peer's `UwbSessionConfig` from `readFromUuid`, writes its
     own to `writeToUuid`, disconnects. Both the GATT client (on read) and GATT server (on write) fire
     `configExchangedCallback`, so the orchestrator dedups.
   - `AccessoryNotify` (accessory): client subscribes to `notifyFromUuid`, writes an init command to
     `writeToUuid`, accessory notifies its config back. The BLE link **stays open** for
     configure-and-start / stop, sent via `BleManager.sendToPeer`.
3. **Range**: `DeviceDiscoveryManager.onConfigExchanged` -> `MultiplatformUwbManager.startRanging(peerId, remoteConfig)`.
4. **Report**: `nearbyDevices: Flow<List<NearbyDevice>>` (state, distance, azimuth, elevation) and
   `events: Flow<DiscoveryEvent>` (debug log). Stale devices are purged every 5 s (10 s window, 60 s if
   `Suspended`), which also releases their session and BLE cache entry.

### Key design decisions (don't undo these without reading the linked PR/issue)

- **All orchestrator state is behind one `Mutex`** in `DeviceDiscoveryManager`; platform callbacks are
  bounced onto its `Dispatchers.Default` scope. Long platform calls (`startRanging`, `stopRanging`) are
  made **outside** the lock so one peer can't stall the others.
- **Android session scopes are single-use and per peer** (`PeerScopes`): one controller + one controlee
  scope minted at discovery, addresses shipped in the config, the unused role dropped. Never mint a new
  scope/address after the exchange. (PR #57, issue #49/#56)
- **Role election is by UWB address**, not timestamp: `UwbSessionConfig.ownsSessionOver` (smaller
  controlee address is controller). Session id is `sessionIdFor(controller, controlee)`, derived
  identically on both ends. Timestamps in the config are legacy; do not use them to elect.
- **Duplicate BLE identities are collapsed by static-STS key** (Android phones show up under several
  randomized addresses). `identityKeyToPeer` + `connectionScore` make both phones pick the same
  connection. Accessories are identified by UWB address instead. iOS peers need none of this (CoreBluetooth
  UUIDs are stable).
- **Suspend/resume are `SessionEvent`s, not errors** (iOS NI suspends when backgrounded or juggling
  sessions). Accessories get a BLE STOP on suspend with the link retained
  (`retainAccessoryLink`) so the re-run's configure-and-start reaches them.
- **`enableAndroidAccessoryProtocol` is off by default and Android-only.** iOS accessory ranging is
  Apple's standard protocol and always on when an accessory profile is present.
- **Wire format is little-endian** with optional trailers (`UwbSessionConfig.toByteArray`), so accessory
  firmware can lay the struct out natively and older payloads still parse. Add new fields as trailing
  optional `[2B len][bytes]` sections; never reorder existing ones. Message ids (`NI_ACCESSORY_*`,
  `ANDROID_ACCESSORY_*`) are in `UwbProfile.kt`; the Android accessory protocol is documented in
  `ANDROID_ACCESSORY_RANGING_README.md`.
- **Runtime permissions are the app's job.** `UWB_RANGING` is not in the "Nearby devices" group and
  moko-permissions doesn't model it; see `Permissions.android.kt` for the pattern.

### Platform gotchas already learned the hard way

**Android**
- The GATT stack allows one outstanding operation per connection; route writes through `BleQueueManager`
  and call `operationComplete()` from the matching callback.
- Override **both** `onCharacteristicChanged` signatures (API 33+ dispatches the 3-arg form).
- Scope creation is several GMS round trips and must not run on the main thread;
  `createConnectionConfig` (blocking) exists only for the GATT server read path on a binder thread.
- Advertisement payload: 128-bit UUID in the primary packet, device name in the scan response, or you get
  `ADVERTISE_FAILED_DATA_TOO_LARGE`.
- `RangingResultPeerDisconnected` is GMS's catch-all for *any* session end (bad params, failed start),
  and the flow never completes: cancel the collector to close the HW session.
- `minSdk = 30`. `BLUETOOTH_SCAN` is declared `neverForLocation`.

**iOS**
- `NISession.delegate` and `ARSession.delegate` are weak: hold delegates strongly (`activeDelegates`).
- NI does not resume by itself after `sessionSuspensionEnded`; re-run `runWithConfiguration` with the
  stored `NIConfiguration`.
- Camera assistance (iOS 16+, no direction hardware) needs **one shared `ARSession`** across NISessions
  and `sessionShouldAttemptRelocalization == false`; two private ARSessions fight for the camera.
- `horizontalAngle` is radians and NaN until convergence; convert to degrees (Android reports degrees).
  `verticalDirectionEstimate` is a category, not an angle, so elevation is `null` on iOS today.
- `NIAlgorithmConvergenceStatusReason` is `NS_TYPED_ENUM` NSString constants, not an enum in K/N.
- `NearbyInteraction` only forward-declares `ARSession`; cast to `objcnames.classes.ARSession`.
- Several CoreBluetooth delegate methods are deliberately not overridden (ObjC selector clashes in K/N):
  writes use `WriteWithoutResponse`, and `didDisconnect`/`didFailToConnect` are omitted.
- Duplicates are off in `scanForPeripherals`, so `forgetDevice` restarts the scan to re-see a peer.
- `NSLog` is the logging tool; Android uses `Log.d/e` with `TAG`.

## Conventions

- Package `com.dustedrob.uwb` for the library; the sample app's common code is in the default package.
- `expect`/`actual` per platform file: `Foo.kt` (common), `Foo.android.kt` / `Foo.kt` in `androidMain`,
  same in `iosMain`. Platform managers share state through `ManagerFactory`, never through globals.
- Comments explain **why** (the platform quirk or the issue that forced it), often citing the PR/issue
  number. Match that: a non-obvious line gets a one-sentence reason, not a restatement.
- No Koin, no DI framework in the library (the catalog entry is unused). No Compose in `uwbmodule`.
- Keep `uwbmodule` vendor-free: hardware UUIDs, vendor names, and firmware specifics go in the app or
  in docs, never in the library.
- Dependencies and versions live in `gradle/libs.versions.toml`; check there rather than assuming.
- Commit messages: short, one line, imperative. PR descriptions and review comments: brief, what changed
  and why, no templates or restated diffs.
