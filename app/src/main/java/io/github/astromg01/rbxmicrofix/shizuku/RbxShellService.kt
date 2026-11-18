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
        return mode?.let { "OK|$it" } ?: "ERR|game_mode_unavailable"
    }

    override fun getCurrentOverlayConfig(): String =
        execute(listOf(DEVICE_CONFIG, "get", GAME_OVERLAY_NAMESPACE, ROBLOX_PACKAGE)).let { result ->
            if (result.exitCode == 0) "OK|${result.output.ifBlank { ABSENT_CONFIG }}"
            else "ERR|overlay_read_failed"
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

        val previousMode = previousGameMode.toIntOrNull()?.takeIf { it in 0..4 } ?: STANDARD_MODE
        val originalOverlay = previousOverlayConfig.takeUnless { it == ABSENT_CONFIG }.orEmpty()
        val nextOverlay = runCatching {
            GameOverlayConfig.withPerformanceProfile(
                original = originalOverlay,
                downscaleFactor = downscaleFactor,
                fpsOverride = fpsOverride,
                useAngle = useAngle,
            )
        }.getOrElse { return "ERR|invalid_overlay" }

        val write = execute(listOf(DEVICE_CONFIG, "put", GAME_OVERLAY_NAMESPACE, ROBLOX_PACKAGE, nextOverlay))
        if (write.exitCode != 0) {
            val overlayRestored = restoreOverlay(originalOverlay)
            val modeRestored = restoreGameMode(previousMode)
            return if (overlayRestored && modeRestored) "ERR|overlay_write_failed" else "ERR|rollback_failed"
        }

        // DeviceConfig notifies GameManagerService asynchronously. Retry the mode
        // switch briefly so the new mode=2 intervention is available before selection.
        var modeResult = ShellResult(-1, "not_attempted")
        for (attempt in 0 until MODE_SWITCH_ATTEMPTS) {
            if (attempt > 0) Thread.sleep(MODE_SWITCH_DELAY_MS)
            modeResult = execute(listOf(CMD, "game", "mode", "performance", ROBLOX_PACKAGE))
            if (modeResult.exitCode == 0) break
        }

        if (modeResult.exitCode != 0) {
            val overlayRestored = restoreOverlay(originalOverlay)
            val modeRestored = restoreGameMode(previousMode)
            return if (overlayRestored && modeRestored) "ERR|mode_switch_failed" else "ERR|rollback_failed"
        }

        val confirmedMode = GameModeParser.parseCurrentMode(
            execute(listOf(CMD, "game", "list-modes", ROBLOX_PACKAGE)).output,
        )
        if (confirmedMode != PERFORMANCE_MODE) {
            val overlayRestored = restoreOverlay(originalOverlay)
            val modeRestored = restoreGameMode(previousMode)
            return if (overlayRestored && modeRestored) "ERR|mode_not_selected" else "ERR|rollback_failed"
        }

        return "OK"
    }

    override fun restoreOriginalState(
        previousGameMode: String,
        previousOverlayConfig: String,
    ): String {
        val previousMode = previousGameMode.toIntOrNull()?.takeIf { it in 0..4 } ?: STANDARD_MODE
        val overlayValue = previousOverlayConfig.takeUnless { it == ABSENT_CONFIG }

        val overlayRestored = restoreOverlay(overlayValue.orEmpty(), wasAbsent = overlayValue == null)
        val modeRestored = restoreGameMode(previousMode)

        return when {
            overlayRestored && modeRestored -> "OK"
            !overlayRestored && !modeRestored -> "ERR|overlay_and_mode_restore_failed"
            !overlayRestored -> "ERR|overlay_restore_failed"
            else -> "ERR|mode_restore_failed"
        }
    }

    override fun forceStopRoblox(): String {
        val result = execute(listOf(AM, "force-stop", ROBLOX_PACKAGE))
        return if (result.exitCode == 0) "OK" else "ERR|client_restart_failed"
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    private fun restoreOverlay(value: String, wasAbsent: Boolean = value.isEmpty()): Boolean {
        val result = if (wasAbsent) {
            execute(listOf(DEVICE_CONFIG, "delete", GAME_OVERLAY_NAMESPACE, ROBLOX_PACKAGE))
        } else {
            execute(listOf(DEVICE_CONFIG, "put", GAME_OVERLAY_NAMESPACE, ROBLOX_PACKAGE, value))
        }
        return result.exitCode == 0
    }

    private fun restoreGameMode(mode: Int): Boolean {
        // GAME_MODE_UNSUPPORTED is not selectable. Standard is the platform default.
        val effectiveMode = if (mode == UNSUPPORTED_MODE) STANDARD_MODE else mode
        val modeName = when (effectiveMode) {
            PERFORMANCE_MODE -> "performance"
            BATTERY_MODE -> "battery"
            CUSTOM_MODE -> "custom"
            else -> "standard"
        }
        return execute(listOf(CMD, "game", "mode", modeName, ROBLOX_PACKAGE)).exitCode == 0
    }

    private fun execute(arguments: List<String>): ShellResult {
        return try {
            val process = ProcessBuilder(arguments)
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return ShellResult(-1, "timeout")
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            ShellResult(process.exitValue(), output)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            ShellResult(-1, "interrupted")
        } catch (error: Throwable) {
            ShellResult(-1, error.javaClass.simpleName)
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
        const val MODE_SWITCH_ATTEMPTS = 5
        const val MODE_SWITCH_DELAY_MS = 250L
        const val ABSENT_CONFIG = "null"
        const val UNSUPPORTED_MODE = 0
        const val STANDARD_MODE = 1
        const val PERFORMANCE_MODE = 2
        const val BATTERY_MODE = 3
        const val CUSTOM_MODE = 4
    }
}
