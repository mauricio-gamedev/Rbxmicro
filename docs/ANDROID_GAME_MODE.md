# Android Game Mode integration

## Actual client rendering path

RbxMicroFix does not simulate optimization inside the HTML page. The native Shizuku UserService writes the per-package `game_overlay` configuration and selects Android's `performance` Game Mode for the official Roblox package.

Android's Game Mode intervention system documents WindowManager backbuffer resizing and ANGLE as game-specific rendering interventions. The AOSP `GameManagerService` parser reads the per-mode keys `mode`, `downscaleFactor`, `fps`, and `useAngle`.

## Configuration format

Example for the selected performance mode:

```text
mode=2,downscaleFactor=0.85,fps=60,useAngle=false
```

Other existing modes (for example `mode=3` battery configuration) are preserved. Only the `mode=2` segment is replaced by RbxMicroFix. The original complete DeviceConfig value is persisted before the first modification.

## Apply sequence

1. Read the current package Game Mode using the AOSP `cmd game list-modes com.roblox.client` command.
2. Read the exact `game_overlay` value for `com.roblox.client`.
3. Persist both values locally before changing the system.
4. Merge the selected rendering profile into the performance-mode segment.
5. Write the new package configuration using `device_config put game_overlay`.
6. Select `performance` using `cmd game mode performance com.roblox.client`, retrying briefly while the DeviceConfig listener updates GameManagerService.
7. Confirm the selected mode by reading the current-mode field from `cmd game list-modes`.
8. Ask the user to restart Roblox explicitly so WindowManager applies the new backbuffer size to the relaunched client.

## Restore sequence

1. Restore or delete the package's `game_overlay` key exactly as it existed before the first RbxMicroFix change.
2. Restore the previously selected Game Mode.
3. Keep the saved snapshot if any restoration step fails, allowing another restore attempt.
4. Roblox is restarted separately by explicit user action.

## FPS override

The Android Game Mode `fps` key is an override/limit, not an FPS unlock. The app therefore leaves it disabled by default and offers a 60 FPS cap as an explicit option for a 60 Hz target display.

## ANGLE

AOSP uses the `useAngle` key for the ANGLE intervention. The option is off by default and reversible. The framework can apply it only when the platform's ANGLE integration allows it for the package.

## Research sources

- Android Game Mode interventions: https://developer.android.com/games/optimize/adpf/gamemode/gamemode-interventions
- Android Game Mode API: https://developer.android.com/games/optimize/adpf/gamemode/gamemode-api
- AOSP GameManagerShellCommand: https://android.googlesource.com/platform/frameworks/base/+/12f5992e4df6/services/core/java/com/android/server/app/GameManagerShellCommand.java
- AOSP GameManagerService: https://android.googlesource.com/platform/frameworks/base/+/4a55cb64c88a417889fd8d441f601c3c7bb01db4/services/core/java/com/android/server/app/GameManagerService.java
- Shizuku API: https://github.com/RikkaApps/Shizuku-API
