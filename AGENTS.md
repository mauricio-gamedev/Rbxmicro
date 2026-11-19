# Repository instructions

## Product scope
RbxMicroFix is a Roblox-specific Android rendering utility. Do not add diagnostics, telemetry, monitoring dashboards, RAM cleaners, task killers, or generic Android tweak catalogs.

## Engineering rules
- Roblox target is fixed to `com.roblox.client`.
- The local HTML page is a presentation layer only; all device changes go through the native Kotlin bridge and the fixed Shizuku UserService API.
- Expose only allowlisted operations and parameters to JavaScript. Never expose arbitrary shell execution.
- Pass shell commands as `ProcessBuilder` argument arrays. Never interpolate user-provided shell text.
- Every configuration change must preserve the original `game_overlay` value and selected Game Mode and provide deterministic restoration.
- The ANGLE switch must use the Android Game Mode intervention key `useAngle`.
- Do not modify Roblox private data, inject code into the Roblox process, patch the Roblox APK, or bypass Roblox client protections.
- Do not claim improvements were proven on a physical device until a device test is completed.
- Keep remote shell work off the UI thread.
- Preserve Shizuku UserService transaction IDs: `destroy=16777114`, `exit=1`.
- Keep all HTML/CSS/JS local and block WebView network navigation.

## Validation
Run `gradle --no-daemon testDebugUnitTest assembleDebug` before proposing a merge. Add unit tests for overlay composition, profile values, and state restoration. Use a feature branch and pull request for substantive changes.
