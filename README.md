# RbxMicroFix

Copyright © 2026 Astromg01. All rights reserved.

**RbxMicroFix** is an Android rendering utility dedicated to the official Roblox client (`com.roblox.client`). Its HTML interface is embedded locally in Android WebView and communicates with native Kotlin code through a closed JavaScript bridge. Device changes are executed by a Shizuku UserService using a fixed allowlist of Android system operations.

## Direct client integration

RbxMicroFix configures Android Game Mode interventions for the Roblox package. The performance-mode segment in the `game_overlay` DeviceConfig namespace can include:

- `downscaleFactor`: WindowManager backbuffer scaling for the Roblox window.
- `fps`: optional Android Game Mode frame-rate override (`60` or `disable`).
- `useAngle`: optional request to route OpenGL ES through ANGLE.
- `loadingBoost`: Android GameManager loading boost duration, set to 5,000 ms.

The utility normally selects Android's `performance` Game Mode after writing the package overlay. When that route is unavailable, it can use Android's `custom` Game Mode (4) through `cmd game set`, but only after confirming that no pre-existing GameManager interventions would be overwritten. The Custom route applies backbuffer scaling and the optional FPS cap; ANGLE and loading boost are specific to the DeviceConfig route. The new backbuffer scale is picked up when Roblox is restarted.

Android source references for the intervention keys and implementation are recorded in `docs/ANDROID_GAME_MODE.md`.

## Profiles

| Profile | Backbuffer scale | Purpose |
|---|---:|---|
| Nitidez | 90% | Lighter adjustment with more retained detail |
| Equilibrado | 85% | Balanced rendering load and sharpness |
| Desempenho | 80% | Lower rendering-buffer workload |
| Máximo | 75% | Stronger reduction in GPU pixel workload |

Optional micro-corrections:

- 60 FPS override, disabled by default (native Roblox/display frame pacing is kept unless selected).
- ANGLE driver request through the `useAngle` Game Mode intervention key, disabled by default.
- 5-second loading boost included automatically in every applied profile.

## Architecture

- Kotlin Android host: application lifecycle, Shizuku permission, Roblox launch/restart and state persistence.
- Local HTML/CSS/JavaScript: mobile-first UI embedded in WebView, no remote scripts or network access.
- JavaScript bridge: only fixed operations (`applyProfile`, `restoreOriginal`, `restartRoblox`, `launchRoblox`, `openShizuku`). No arbitrary command execution.
- Shizuku UserService: fixed package target, allowlisted scale/FPS values, argument-array process execution, AOSP `cmd game list-modes` integration, guarded Custom-mode fallback and verified restoration.
- Transactional state: original Game Mode and `game_overlay` value are saved before the first change and restored on request or on an interrupted apply operation.

## Target

- Primary device: Samsung Galaxy A06 SM-A065M.
- Android: API 36 / Android 16.
- Roblox package: `com.roblox.client`.
- Application ID: `io.github.astromg01.rbxmicrofix`.
- Kotlin + Android WebView + Shizuku API.

## Safe migration from the 0.2.2 CI APK

GitHub-hosted runners generate different debug signing certificates, so Android may reject the new APK as an in-place update. Do not uninstall the old app until its recovery snapshot has been preserved.

1. Keep RbxMicroFix 0.2.2 installed and keep Shizuku running.
2. Install and open the separate **RbxMicroFix Snapshot Migrator** APK from the same CI run.
3. Grant the migrator Shizuku permission and tap **Salvar snapshot**. It uses Android `run-as` to read only the old app's `rbx_microfix_state.xml`; it does not alter system settings.
4. Only after the migrator confirms the snapshot was saved, uninstall the old RbxMicroFix and install the new 0.2.4 APK.
5. On first launch, RbxMicroFix imports the saved original Game Mode and overlay snapshot. Use **Restaurar configuração original** before applying a new profile.

The migration provider only allows the RbxMicroFix package to read the exported snapshot. Keep the migrator installed until the new app confirms that the snapshot has been imported and restoration is complete.

## Shizuku

Shizuku is used with its shell identity; no root is requested. On a non-rooted device, start Shizuku using Android Wireless debugging. Download: <https://shizuku.rikka.app/download/>

## Build

Requirements: JDK 17, Gradle 8.13, Android SDK Platform 36, Build Tools 36.0.0.

```sh
gradle --no-daemon testDebugUnitTest assembleDebug
```

GitHub Actions runs the unit tests and creates a debug APK artifact.

## Safety and reversibility

- All shell invocations use fixed `ProcessBuilder` argument arrays.
- The package name is fixed to `com.roblox.client`.
- Scale values and FPS values are allowlisted.
- ANGLE selection is an explicit user toggle.
- Existing `game_overlay` segments for other modes and unknown keys are preserved when applying a profile.
- The original overlay and selected Game Mode are saved and restored.
- The Custom-mode fallback is used only when `cmd game list-configs` confirms no existing intervention configuration; the app refuses to replace pre-existing GameManager settings.
- Restarting Roblox is a separate explicit action because it terminates the current client session.
- Roblox private files, client binaries and process memory are not modified.

## Validation status

Version 0.2.4 includes the local HTML/native connector, direct Game Mode rendering integration, a guarded Custom-mode fallback, detailed apply/rollback errors, and a one-time snapshot migrator for the 0.2.2 CI APK. Unit tests cover overlay composition, profile values, loading boost and Game Mode parsing. GitHub Actions and physical validation on the Galaxy A06 are the next execution checks; no measured FPS improvement is claimed before those tests.
