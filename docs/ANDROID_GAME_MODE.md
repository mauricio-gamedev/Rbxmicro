# Android Game Mode integration

## Actual client rendering path

RbxMicroFix does not simulate optimization inside the HTML page. The native Shizuku UserService first tries the per-package `game_overlay` configuration and Android's `performance` Game Mode. If that path is unavailable, it can use Android's `custom` Game Mode (4) through GameManagerService, provided the device confirms there are no existing intervention settings to overwrite.

Android's Game Mode intervention system documents WindowManager backbuffer resizing and ANGLE as game-specific rendering interventions. The AOSP `GameManagerService` parser reads the per-mode keys `mode`, `downscaleFactor`, `fps`, `useAngle`, and `loadingBoost`.

## Configuration format

Example for the selected performance mode:

```text
mode=2,downscaleFactor=0.85,fps=60,useAngle=false,loadingBoost=5000
```

Other existing modes (for example `mode=3` battery configuration) are preserved. Only the `mode=2` segment is replaced by RbxMicroFix. The original complete DeviceConfig value is persisted before the first modification.

## Apply sequence

1. Read the current package Game Mode and exact `game_overlay` value.
2. Persist both values locally before changing the system.
3. Query `cmd game list-configs` before any fallback that creates a persistent GameManager user override.
4. Merge the selected profile into the `mode=2` performance segment, then try `device_config put game_overlay` and select `performance` when that mode becomes available.
5. If the direct route fails, use the `cmd game set --mode 4 --downscale <scale> --fps <value> com.roblox.client` route only when `list-configs` reports no existing interventions. This creates a per-user Custom-mode override; FPS uses `0` to disable the cap in this shell API. The app then verifies `custom` as the current mode.
6. The Custom-mode fallback applies backbuffer scaling and the optional FPS cap. ANGLE and the five-second loading boost remain available only on the direct DeviceConfig route.
7. Ask the user to restart Roblox explicitly so WindowManager applies the new backbuffer size to the relaunched client.

The fallback is intentionally blocked if `list-configs` cannot be read or reports an existing intervention. That avoids deleting or replacing GameManager settings that existed before RbxMicroFix.

## Restore sequence

1. Reset the Custom-mode GameManager override created by RbxMicroFix, if that route was used. The app only creates this override after confirming there were no pre-existing GameManager interventions.
2. Restore or delete the package's `game_overlay` key exactly as it existed before the first RbxMicroFix change.
3. Restore the previously selected Game Mode and verify the result.
4. Keep the saved snapshot if any restoration step fails, allowing another restore attempt.
5. Roblox is restarted separately by explicit user action.

## FPS override

The Android Game Mode `fps` key is an override/limit, not an FPS unlock. The app therefore leaves it disabled by default and offers a 60 FPS cap as an explicit option for a 60 Hz target display.

## ANGLE

AOSP uses the `useAngle` key for the ANGLE intervention. The option is off by default and reversible. Android's graphics environment checks the GameManager ANGLE setting when initializing the app's graphics driver, so the selected option is loaded when Roblox is restarted.

## Loading boost

The performance profile includes `loadingBoost=5000`. Android's `GraphicsEnvironment` notifies `GameManager` during graphics-environment setup for game-category applications. The framework uses the configured duration to activate the `GAME_LOADING` power mode and schedules it to turn off after the duration; values above 5,000 ms are clamped to the platform maximum. This targets launch/graphics initialization, not a permanent frame-rate boost.

## Research sources

- Android Game Mode interventions: https://developer.android.com/games/optimize/adpf/gamemode/gamemode-interventions
- Android Game Mode API: https://developer.android.com/games/optimize/adpf/gamemode/gamemode-api
- AOSP GameManagerShellCommand (Android 15 custom mode and set/reset syntax): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/app/GameManagerShellCommand.java
- Android GameManager API (Custom mode added in API 34): https://developer.android.com/reference/android/app/GameManager
- AOSP GameManagerService: https://android.googlesource.com/platform/frameworks/base/+/4a55cb64c88a417889fd8d441f601c3c7bb01db4/services/core/java/com/android/server/app/GameManagerService.java
- AOSP GraphicsEnvironment: https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/os/GraphicsEnvironment.java
- Shizuku API: https://github.com/RikkaApps/Shizuku-API
