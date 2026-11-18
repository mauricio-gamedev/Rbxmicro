# Session checkpoint — 2026-11-18

## Product decisions
- Project: RbxMicroFix.
- Repository: `mauricio-gamedev/Rbxmicro`.
- Android-first and Roblox-specific; target package `com.roblox.client`.
- No diagnostic screens, telemetry, monitoring dashboard, RAM cleaner, or generic tweaks.
- First device: Samsung Galaxy A06 SM-A065M, Android 16 / API 36.
- HTML is a local WebView presentation layer connected to native Kotlin/Shizuku code.
- No root requested.

## Version 0.2.1 implementation
- Local HTML/CSS/JS mobile interface, no external assets or network navigation.
- Restricted JavaScript bridge for profile apply, restore, explicit Roblox restart, launch and Shizuku launch.
- Profiles: 90%, 85%, 80%, 75% backbuffer scale.
- Optional 60 FPS Android Game Mode override, disabled by default.
- Optional ANGLE intervention using the correct AOSP `useAngle` key, disabled by default.
- DeviceConfig `game_overlay` merge preserves other mode segments and unknown keys.
- Current Game Mode query parses AOSP `cmd game list-modes com.roblox.client` output inside Shizuku UserService.
- Apply sequence writes `device_config`, selects performance mode using `cmd game mode performance`, then checks current mode.
- Original Game Mode and DeviceConfig value are saved before first modification; apply failure attempts rollback.
- Explicit restart action uses `am force-stop com.roblox.client`, then launches the official client.
- Unit tests cover profile values and overlay merging.

## Important corrections from 0.1.0
- Corrected the earlier incorrect note: AOSP contains `cmd game list-modes`; the engine uses it to read the selected mode.
- Separated Game Mode intervention configuration from Game Mode selection.
- Corrected the ANGLE configuration key from `angle` to `useAngle`.
- Removed Compose UI in favor of a local HTML/native bridge, as requested.

## Validation
- Repository was empty when provided; initial README commit seeded `main`, and all implementation is being developed on `feature/android-rendering-engine`.
- Full Android build / CI and Galaxy A06 device tests remain to be run.
- No physical FPS uplift is claimed yet.

## Next steps
1. Run GitHub Actions and fix all compile/test failures.
2. Verify `cmd game list-modes` and Shizuku shell execution on A06 Android 16.
3. Confirm `game_overlay` is applied to `com.roblox.client` and persists after force-stop/relaunch.
4. Test 90%, 85%, 80%, 60 FPS override and ANGLE separately, restoring between tests.
5. Keep only settings that demonstrably improve actual Roblox client behavior.
