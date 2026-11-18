package io.github.astromg01.rbxmicrofix.data

import android.content.Context
import io.github.astromg01.rbxmicrofix.core.GameModeParser
import io.github.astromg01.rbxmicrofix.core.PerformanceProfile
import io.github.astromg01.rbxmicrofix.shizuku.ShizukuGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface OptimizerResult {
    data class Applied(
        val profile: PerformanceProfile,
        val fps60: Boolean,
        val useAngle: Boolean,
    ) : OptimizerResult

    data object Restored : OptimizerResult
    data object NothingToRestore : OptimizerResult
    data object PermissionFailure : OptimizerResult
    data object OperationFailure : OptimizerResult
    data object RollbackFailure : OptimizerResult
}

class OptimizerRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val gateway = ShizukuGateway(context)

    suspend fun apply(
        profile: PerformanceProfile,
        fps60: Boolean,
        useAngle: Boolean,
    ): OptimizerResult = withContext(Dispatchers.IO) {
        val service = connectOrNull() ?: return@withContext connectionFailure()

        var previousMode = preferences.getString(KEY_PREVIOUS_MODE, null)
        var previousOverlay = preferences.getString(KEY_PREVIOUS_OVERLAY, null)

        if (!canRestore()) {
            val modeResponse = runCatching { service.currentGameMode }.getOrNull()
                ?: return@withContext OptimizerResult.OperationFailure
            if (!modeResponse.startsWith(OK_PREFIX)) return@withContext OptimizerResult.OperationFailure
            val mode = GameModeParser.parseMode(modeResponse.removePrefix(OK_PREFIX))
                ?: return@withContext OptimizerResult.OperationFailure

            val overlayResponse = runCatching { service.currentOverlayConfig }.getOrNull()
                ?: return@withContext OptimizerResult.OperationFailure
            if (!overlayResponse.startsWith(OK_PREFIX)) return@withContext OptimizerResult.OperationFailure
            val overlay = overlayResponse.removePrefix(OK_PREFIX).ifBlank { ABSENT_CONFIG }

            val saved = preferences.edit()
                .putString(KEY_PREVIOUS_MODE, mode.toString())
                .putString(KEY_PREVIOUS_OVERLAY, overlay)
                .commit()
            if (!saved) return@withContext OptimizerResult.OperationFailure

            previousMode = mode.toString()
            previousOverlay = overlay
        }

        val modeSnapshot = previousMode ?: return@withContext OptimizerResult.OperationFailure
        val overlaySnapshot = previousOverlay ?: return@withContext OptimizerResult.OperationFailure
        val response = runCatching {
            service.applyRenderingProfile(
                profile.downscaleFactor,
                if (fps60) FPS_60 else FPS_NATIVE,
                useAngle,
                modeSnapshot,
                overlaySnapshot,
            )
        }.getOrNull()

        when (response) {
            SUCCESS -> {
                val persisted = preferences.edit()
                    .putBoolean(KEY_PROFILE_ACTIVE, true)
                    .putString(KEY_ACTIVE_PROFILE, profile.name)
                    .putBoolean(KEY_FPS_60, fps60)
                    .putBoolean(KEY_USE_ANGLE, useAngle)
                    .commit()
                if (persisted) {
                    OptimizerResult.Applied(profile, fps60, useAngle)
                } else {
                    val rollback = runCatching {
                        service.restoreOriginalState(modeSnapshot, overlaySnapshot)
                    }.getOrNull()
                    if (rollback == SUCCESS) {
                        clearSavedState()
                        OptimizerResult.OperationFailure
                    } else {
                        OptimizerResult.RollbackFailure
                    }
                }
            }
            ROLLBACK_FAILED -> OptimizerResult.RollbackFailure
            else -> {
                // The remote service rolls back partial changes before returning a failure.
                clearSavedState()
                OptimizerResult.OperationFailure
            }
        }
    }

    suspend fun restore(): OptimizerResult = withContext(Dispatchers.IO) {
        if (!canRestore()) return@withContext OptimizerResult.NothingToRestore

        val previousMode = preferences.getString(KEY_PREVIOUS_MODE, "1") ?: "1"
        val previousOverlay = preferences.getString(KEY_PREVIOUS_OVERLAY, ABSENT_CONFIG) ?: ABSENT_CONFIG
        val service = connectOrNull() ?: return@withContext connectionFailure()
        val result = runCatching {
            service.restoreOriginalState(previousMode, previousOverlay)
        }.getOrNull()

        if (result == SUCCESS) {
            clearSavedState()
            OptimizerResult.Restored
        } else {
            OptimizerResult.OperationFailure
        }
    }

    suspend fun forceStopRoblox(): Boolean = withContext(Dispatchers.IO) {
        val service = connectOrNull() ?: return@withContext false
        runCatching { service.forceStopRoblox() == SUCCESS }.getOrDefault(false)
    }

    fun activeProfile(): PerformanceProfile? {
        if (!preferences.getBoolean(KEY_PROFILE_ACTIVE, false)) return null
        val name = preferences.getString(KEY_ACTIVE_PROFILE, null) ?: return null
        return runCatching { PerformanceProfile.valueOf(name) }.getOrNull()
    }

    fun fps60Enabled(): Boolean = preferences.getBoolean(KEY_FPS_60, false)

    fun angleEnabled(): Boolean = preferences.getBoolean(KEY_USE_ANGLE, false)

    fun canRestore(): Boolean =
        preferences.contains(KEY_PREVIOUS_MODE) && preferences.contains(KEY_PREVIOUS_OVERLAY)

    fun release() = gateway.release()

    private suspend fun connectOrNull() = try {
        gateway.connect()
    } catch (_: SecurityException) {
        null
    } catch (_: Throwable) {
        null
    }

    private fun connectionFailure(): OptimizerResult =
        if (!runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false) ||
            runCatching { rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED }
                .getOrDefault(true)
        ) {
            OptimizerResult.PermissionFailure
        } else {
            OptimizerResult.OperationFailure
        }

    private fun clearSavedState() {
        preferences.edit()
            .remove(KEY_PREVIOUS_MODE)
            .remove(KEY_PREVIOUS_OVERLAY)
            .remove(KEY_ACTIVE_PROFILE)
            .putBoolean(KEY_PROFILE_ACTIVE, false)
            .putBoolean(KEY_FPS_60, false)
            .putBoolean(KEY_USE_ANGLE, false)
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "rbx_microfix_state"
        const val KEY_PROFILE_ACTIVE = "profile_active"
        const val KEY_ACTIVE_PROFILE = "active_profile"
        const val KEY_PREVIOUS_MODE = "previous_game_mode"
        const val KEY_PREVIOUS_OVERLAY = "previous_game_overlay"
        const val KEY_FPS_60 = "fps_60_enabled"
        const val KEY_USE_ANGLE = "use_angle_enabled"
        const val OK_PREFIX = "OK|"
        const val SUCCESS = "OK"
        const val ROLLBACK_FAILED = "ERR|rollback_failed"
        const val ABSENT_CONFIG = "null"
        const val FPS_60 = "60"
        const val FPS_NATIVE = "disable"
    }
}
