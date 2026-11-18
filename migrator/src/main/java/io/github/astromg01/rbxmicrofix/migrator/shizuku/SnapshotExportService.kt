package io.github.astromg01.rbxmicrofix.migrator.shizuku

import android.util.Base64
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

@Keep
class SnapshotExportService : ISnapshotExportService.Stub() {

    override fun exportLegacySnapshot(): String {
        val process = try {
            ProcessBuilder(
                RUN_AS,
                LEGACY_PACKAGE,
                "cat",
                LEGACY_PREFS_PATH,
            ).redirectErrorStream(true).start()
        } catch (error: Throwable) {
            return "ERR|run-as_start_failed|${compact(error.message.orEmpty())}"
        }

        return try {
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return "ERR|run-as_timeout"
            }

            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (process.exitValue() != 0) {
                return "ERR|run-as_denied|${compact(output)}"
            }
            if (!output.contains("previous_game_mode") || !output.contains("previous_game_overlay")) {
                return "ERR|recovery_snapshot_not_found"
            }

            val encoded = Base64.encodeToString(output.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "OK|$encoded"
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            "ERR|export_interrupted"
        } catch (error: Throwable) {
            "ERR|export_failed|${compact(error.message.orEmpty())}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    private fun compact(value: String): String =
        value.replace(Regex("\\s+"), " ").take(180).ifBlank { "no_output" }

    private companion object {
        const val LEGACY_PACKAGE = "io.github.astromg01.rbxmicrofix"
        const val LEGACY_PREFS_PATH = "shared_prefs/rbx_microfix_state.xml"
        const val RUN_AS = "/system/bin/run-as"
        const val COMMAND_TIMEOUT_SECONDS = 8L
    }
}
