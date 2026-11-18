package io.github.astromg01.rbxmicrofix.shizuku

import androidx.annotation.Keep
import io.github.astromg01.rbxmicrofix.core.GameModeParser
import io.github.astromg01.rbxmicrofix.core.GameOverlayConfig
import java.util.concurrent.TimeUnit

@Keep
class RbxShellService : IRbxShellService.Stub() {

    override fun getCurrentGameMode(): String {
        val result = execute(listOf(CMD, "game", "list-modes", ROBLOX_PACKAGE))
        val mode = if (result.exitCode == 0) GameModeParser.parseCurrentMode(result.output) else null
        return mode?.let { "OK|$it" }
            ?: "ERR|game_mode_unavailable|${describe(result)}"
    }

    override fun getCurrentOverlayConfig(): String =
        execute(listOf(DEVICE_CONFIG, "get", GAME_OVERLAY_NAMESPACE, ROBLOX_PACKAGE)).let { result ->
            if (result.exitCode == 0) "OK|${result.output.ifBlank { ABSENT_CONFIG }}"
            else "ERR|overlay_read_failed|${describe(result)}"
        }

    override fun applyRenderingProfile(
        downscaleFactor: String,
        fpsOverride: String,
        useAngle: Boolean,
        previousGameMode: String,
        previousOverlayConfig: String,
    ): String {
        if (downscaleFactor !in GameOverlayConfig.allowedDownscaleFactors) return "ERR|invalid_scale"
        if (fpsOverride !in GameOverlayConfig.allowedFpsOverrides) return "ERR|invalid_fps"

        val previousMode = previousGameMode.toIntOrNull()?.takeIf { it in 0..4 }
            ?: return "ERR|invalid_previous_mode"
        val originalOverlayWasAbsent = previousOverlayConfig == ABSENT_CONFIG
        val originalOverlay = previousOverlayConfig.takeUnless { originalOverlayWasAbsent }.orEmpty()
        val nextOverlay = runCatching {
            GameOverlayConfig.withPerformanceProfile(
                original = originalOverlay,
                downscaleFactor = downscaleFactor,
                fpsOverride = fpsOverride,
                useAngle = useAngle,
            )
        }.getOrElse { return "ERR|invalid_overlay" }

        // The custom-mode shell fallback stores a per-user override. Only use it
        // when list-configs confirms there is no existing intervention to preserve.
        val customFallback = canSafelyUseCustomModeFallback()

        var overlayChanged = false
        var directFailure = "not_attempted"

        // Preferred route: write the complete per-package overlay so ANGLE and
        // loadingBoost are included alongside backbuffer scaling and FPS.
        val write = execute(
            listOf(DEVICE_CONFIG, "put", GAME_OVERLAY_NAMESPACE, ROBLOX_PACKAGE, nextOverlay),
        )
        if (write.exitCode == 0) {
            overlayChanged = true
            if (waitForOverlayValue(nextOverlay)) {
                val selected = selectPerformanceMode()
                if (selected.first) {
                    return "OK|device_config|extras=1"
                }
                directFailure = selected.second
            } else {
                directFailure = "DeviceConfig put returned success, but the stored value did not match the requested profile"
            }
        } else {
            directFailure = "DeviceConfig put: ${describe(write)}"
            val current = currentOverlayValue()
            overlayChanged = current != null &&
                current != if (originalOverlayWasAbsent) ABSENT_CONFIG else originalOverlay
        }

        if (!customFallback.first) {
            val rollback = rollback(
                previousMode = previousMode,
                originalOverlay = originalOverlay,
                originalOverlayWasAbsent = originalOverlayWasAbsent,
                overlayChanged = overlayChanged,
                managerOverrideMayExist = false,
            )
            return if (rollback) {
                "ERR|apply_failed|direct=${compact(directFailure)}|custom_fallback_blocked=${compact(customFallback.second)}|rollback=ok"
            } else {
                "ERR|rollback_failed|direct=${compact(directFailure)}|custom_fallback_blocked=${compact(customFallback.second)}|overlay=${describeOverlayState(originalOverlay, originalOverlayWasAbsent)}|mode=${describeModeState(previousMode)}"
            }
        }

        // Android 14+ provides the always-available Custom game mode (4).
        // Unlike Performance mode, it does not require the game to opt in.
        // In this API --fps is numeric and 0 disables the cap.
        val fpsArgument = if (fpsOverride == FPS_60) FPS_60 else FPS_DISABLED
        val managerApply = execute(
            listOf(
                CMD, "game", "set",
                "--mode", CUSTOM_MODE.toString(),
                "--downscale", downscaleFactor,
                "--fps", fpsArgument,
                ROBLOX_PACKAGE,
            ),
        )

        // The command may have changed persistent user settings even if the
        // shell process timed out or returned an error; rollback must attempt reset.
        val managerOverrideMayExist = true
        if (managerApply.exitCode == 0 && waitForMode(CUSTOM_MODE)) {
            // The manager override now owns the active Custom mode. Restore any
            // temporary DeviceConfig write to avoid leaving a second profile.
            val cleanup = if (overlayChanged) {
                restoreOverlay(originalOverlay, originalOverlayWasAbsent)
            } else {
                true
            }
            return if (cleanup) {
                "OK|game_manager_override|extras=0"
            } else {
                "OK|game_manager_override|extras=0|overlay_cleanup_pending"
            }
        }

        val managerFailure = describe(managerApply)
        val rollback = rollback(
            previousMode = previousMode,
            originalOverlay = originalOverlay,
            originalOverlayWasAbsent = originalOverlayWasAbsent,
            overlayChanged = overlayChanged,
            managerOverrideMayExist = managerOverrideMayExist,
        )

        return if (rollback) {
            "ERR|apply_failed|direct=${compact(directFailure)}|custom_mode=${compact(managerFailure)}|rollback=ok"
        } else {
            "ERR|rollback_failed|custom_override_possible=1|direct=${compact(directFailure)}|custom_mode=${compact(managerFailure)}|overlay=${describeOverlayState(originalOverlay, originalOverlayWasAbsent)}|mode=${describeModeState(previousMode)}"
        }
    }

    override fun restoreOriginalState(
        previousGameMode: String,
        previousOverlayConfig: String,
        hadManagerOverride: Boolean,
    ): String {
        val previousMode = previousGameMode.toIntOrNull()?.takeIf { it in 0..4 }
            ?: return "ERR|invalid_previous_mode"
        val originalOverlayWasAbsent = previousOverlayConfig == ABSENT_CONFIG
        val originalOverlay = previousOverlayConfig.takeUnless { originalOverlayWasAbsent }.orEmpty()

        val rollback = rollback(
            previousMode = previousMode,
            originalOverlay = originalOverlay,
            originalOverlayWasAbsent = originalOverlayWasAbsent,
            overlayChanged = true,
            managerOverrideMayExist = hadManagerOverride,
        )

        return if (rollback) {
            "OK"
        } else {
            "ERR|restore_incomplete|manager_override=${if (hadManagerOverride) 1 else 0}|overlay=${describeOverlayState(originalOverlay, originalOverlayWasAbsent)}|mode=${describeModeState(previousMode)}"
        }
    }

    override fun forceStopRoblox(): String {
        val result = execute(listOf(AM, "force-stop", ROBLOX_PACKAGE))
        return if (result.exitCode == 0) "OK" else "ERR|client_restart_failed|${describe(result)}"
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    private fun canSafelyUseCustomModeFallback(): Pair<Boolean, String> {
        val modes = execute(listOf(CMD, "game", "list-modes", ROBLOX_PACKAGE))
        if (modes.exitCode != 0) {
            return false to "Custom Game Mode unavailable: ${describe(modes)}"
        }
        val availableModes = GameModeParser.parseAvailableModes(modes.output)
        if (!availableModes.contains(CUSTOM_MODE)) {
            return false to "Custom Game Mode is not available for Roblox: ${compact(modes.output)}"
        }

        val configs = execute(listOf(CMD, "game", "list-configs", ROBLOX_PACKAGE))
        if (configs.exitCode != 0) {
            return false to "list-configs unavailable: ${describe(configs)}"
        }
        if (!GameModeParser.reportsNoInterventionConfig(configs.output)) {
            return false to "pre-existing GameManager interventions were found; refusing to replace them: ${compact(configs.output)}"
        }
        return true to ""
    }

    private fun selectPerformanceMode(): Pair<Boolean, String> {
        repeat(MODE_SELECT_ATTEMPTS) { attempt ->
            val list = execute(listOf(CMD, "game", "list-modes", ROBLOX_PACKAGE))
            if (list.exitCode != 0) {
                if (attempt == MODE_SELECT_ATTEMPTS - 1) {
                    return false to "Game Mode list: ${describe(list)}"
                }
            } else {
                val available = GameModeParser.parseAvailableModes(list.output)
                if (available.contains(PERFORMANCE_MODE)) {
                    val selection = execute(listOf(CMD, "game", "mode", "performance", ROBLOX_PACKAGE))
                    if (selection.exitCode == 0 && waitForMode(PERFORMANCE_MODE)) {
                        return true to ""
                    }
                    if (selection.exitCode != 0 && attempt == MODE_SELECT_ATTEMPTS - 1) {
                        return false to "Game Mode performance rejected: ${describe(selection)}"
                    }
                }
            }
            Thread.sleep(MODE_RETRY_DELAY_MS)
        }
        return false to "Performance mode did not become available after DeviceConfig update"
    }

    private fun waitForOverlayValue(expected: String): Boolean {
        repeat(OVERLAY_VERIFY_ATTEMPTS) {
            if (currentOverlayValue() == expected) return true
            Thread.sleep(OVERLAY_VERIFY_DELAY_MS)
        }
        return false
    }

    private fun waitForMode(expected: Int): Boolean {
        repeat(MODE_VERIFY_ATTEMPTS) {
            val result = execute(listOf(CMD, "game", "list-modes", ROBLOX_PACKAGE))
            if (result.exitCode == 0 && GameModeParser.parseCurrentMode(result.output) == expected) {
                return true
            }
            Thread.sleep(MODE_VERIFY_DELAY_MS)
        }
        return false
    }

    private fun currentOverlayValue(): String? {
        val result = execute(listOf(DEVICE_CONFIG, "get", GAME_OVERLAY_NAMESPACE, ROBLOX_PACKAGE))
        if (result.exitCode != 0) return null
        return result.output.ifBlank { ABSENT_CONFIG }
    }

    private fun rollback(
        previousMode: Int,
        originalOverlay: String,
        originalOverlayWasAbsent: Boolean,
        overlayChanged: Boolean,
        managerOverrideMayExist: Boolean,
    ): Boolean {
        val managerReset = if (managerOverrideMayExist) {
            resetGameManagerOverride().exitCode == 0
        } else {
            true
        }

        val overlayRestored = if (overlayChanged) {
            restoreOverlay(originalOverlay, originalOverlayWasAbsent)
        } else {
            true
        }
        val modeRestored = restoreGameMode(previousMode)
        return managerReset && overlayRestored && modeRestored
    }

    private fun resetGameManagerOverride(): ShellResult =
        execute(listOf(CMD, "game", "reset", ROBLOX_PACKAGE))

    private fun restoreOverlay(original: String, wasAbsent: Boolean): Boolean {
        val expected = if (wasAbsent) ABSENT_CONFIG else original
        val current = currentOverlayValue()
        if (current != null && current == expected) return true

        val result = if (wasAbsent) {
            execute(listOf(DEVICE_CONFIG, "delete", GAME_OVERLAY_NAMESPACE, ROBLOX_PACKAGE))
        } else {
            execute(listOf(DEVICE_CONFIG, "put", GAME_OVERLAY_NAMESPACE, ROBLOX_PACKAGE, original))
        }
        return result.exitCode == 0 && waitForOverlayValue(expected)
    }

    private fun restoreGameMode(mode: Int): Boolean {
        // "unsupported" is a derived state, not a selectable Game Mode.
        if (mode == UNSUPPORTED_MODE) return waitForMode(UNSUPPORTED_MODE)

        val modeName = when (mode) {
            PERFORMANCE_MODE -> "performance"
            BATTERY_MODE -> "battery"
            CUSTOM_MODE -> "custom"
            else -> "standard"
        }
        val result = execute(listOf(CMD, "game", "mode", modeName, ROBLOX_PACKAGE))
        return result.exitCode == 0 && waitForMode(mode)
    }

    private fun describeOverlayState(original: String, wasAbsent: Boolean): String {
        val expected = if (wasAbsent) ABSENT_CONFIG else original
        return if (currentOverlayValue() == expected) "restored" else "not_restored"
    }

    private fun describeModeState(previousMode: Int): String =
        GameModeParser.parseCurrentMode(
            execute(listOf(CMD, "game", "list-modes", ROBLOX_PACKAGE)).output,
        )?.let { if (it == previousMode) "restored" else "not_restored" } ?: "unknown"

    private fun describe(result: ShellResult): String =
        "exit=${result.exitCode}, output=${compact(result.output)}"

    private fun compact(value: String): String =
        value.replace(Regex("\\s+"), " ").take(MAX_ERROR_LENGTH).ifBlank { "no_output" }

    private fun execute(arguments: List<String>): ShellResult {
        return try {
            val process = ProcessBuilder(arguments)
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return ShellResult(-1, "timeout: ${arguments.take(3).joinToString(" ")}")
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            ShellResult(process.exitValue(), output)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            ShellResult(-1, "interrupted")
        } catch (error: Throwable) {
            ShellResult(-1, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        }
    }

    private data class ShellResult(val exitCode: Int, val output: String)

    private companion object {
        const val ROBLOX_PACKAGE = "com.roblox.client"
        const val GAME_OVERLAY_NAMESPACE = "game_overlay"
        const val CMD = "/system/bin/cmd"
        const val DEVICE_CONFIG = "/system/bin/device_config"
        const val AM = "/system/bin/am"
        const val COMMAND_TIMEOUT_SECONDS = 8L
        const val MODE_SELECT_ATTEMPTS = 16
        const val MODE_RETRY_DELAY_MS = 300L
        const val MODE_VERIFY_ATTEMPTS = 10
        const val MODE_VERIFY_DELAY_MS = 250L
        const val OVERLAY_VERIFY_ATTEMPTS = 10
        const val OVERLAY_VERIFY_DELAY_MS = 200L
        const val MAX_ERROR_LENGTH = 170
        const val ABSENT_CONFIG = "null"
        const val FPS_60 = "60"
        const val FPS_DISABLED = "0"
        const val UNSUPPORTED_MODE = 0
        const val PERFORMANCE_MODE = 2
        const val BATTERY_MODE = 3
        const val CUSTOM_MODE = 4
    }
}
