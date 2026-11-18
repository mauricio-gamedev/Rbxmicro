# Session checkpoint — 2026-11-18

## Product decisions
- Project: RbxMicroFix.
- Repository: `mauricio-gamedev/Rbxmicro`.
- Android-first and Roblox-specific; target package `com.roblox.client`.
- No diagnostic screens, telemetry, monitoring dashboard, RAM cleaner, or generic tweaks.
- First device: Samsung Galaxy A06 SM-A065M, Android 16 / API 36.
- HTML is a local WebView presentation layer connected to native Kotlin/Shizuku code.
- No root requested.

## Version 0.2.2 implementation
- Local HTML/CSS/JS mobile interface, no external assets or network navigation.
- Restricted JavaScript bridge for profile apply, restore, explicit Roblox restart, launch and Shizuku launch.
- Profiles: 90%, 85%, 80%, 75% backbuffer scale.
- Optional 60 FPS Android Game Mode override, disabled by default.
- Optional ANGLE intervention using the AOSP `useAngle` key, disabled by default.
- Automatic 5-second Android Game Loading boost via `loadingBoost=5000`.
- DeviceConfig `game_overlay` merge preserves other mode segments and unknown keys.
- Current Game Mode query parses AOSP `cmd game list-modes com.roblox.client` output inside Shizuku UserService.
- Apply sequence writes `device_config`, selects performance mode using `cmd game mode performance`, then checks current mode.
- Original Game Mode and DeviceConfig value are saved before first modification; apply failure attempts rollback.
- Explicit restart action uses `am force-stop com.roblox.client`, then launches the official client.
- Unit tests cover profile values, loading boost and overlay merging.
- Shizuku AIDL methods use explicit transaction IDs, preserving the reserved destroy/exit IDs.

## Important corrections from 0.1.0
- AOSP contains `cmd game list-modes`; the engine uses it to read the selected mode.
- Separated Game Mode intervention configuration from Game Mode selection.
- Corrected the ANGLE configuration key from `angle` to `useAngle`.
- Added all AIDL transaction IDs after the first CI compile caught an AIDL interface rule.
- Removed Compose UI in favor of a local HTML/native bridge, as requested.

## Validation
- Core Kotlin smoke tests, overlay composition, profile catalog, Android XML, and HTML JavaScript syntax checks passed locally.
- CI is verifying AIDL compilation, Android packaging, unit tests, and APK generation.
- Galaxy A06 physical behavior still needs test confirmation.
- No physical FPS uplift is claimed before target-device testing.

## Next steps
1. Complete GitHub Actions and resolve build/test failures.
2. Verify the actual intervention behavior on Galaxy A06 Android 16.
3. Compare backbuffer profiles with the same Roblox experience and session conditions.
4. Check ANGLE separately, then restore/retain it based on the real client behavior.
5. Keep only settings that demonstrably improve actual Roblox client behavior.
