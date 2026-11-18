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
        val usedGameManagerOverride: Boolean,
        val extrasConfigured: Boolean,
    ) : OptimizerResult

    data object Restored : OptimizerResult
    data object NothingToRestore : OptimizerResult
    data object PermissionFailure : OptimizerResult
    data class OperationFailure(val details: String = "Operação rejeitada pelo Android.") : OptimizerResult
    data class RollbackFailure(val details: String = "Não foi possível concluir a reversão.") : OptimizerResult
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

        // A new profile starts from the original device state, never on top of
        // a previous RbxMicroFix override.
        if (canRestore()) {
            val previousMode = preferences.getString(KEY_PREVIOUS_MODE, "0") ?: "0"
            val previousOverlay = preferences.getString(KEY_PREVIOUS_OVERLAY, ABSENT_CONFIG) ?: ABSENT_CONFIG
            val hadManagerOverride = preferences.getBoolean(KEY_MANAGER_OVERRIDE_ACTIVE, false)
            val restoreResponse = runCatching {
                service.restoreOriginalState(previousMode, previousOverlay, hadManagerOverride)
            }.getOrNull()

            if (restoreResponse != SUCCESS) {
                return@withContext OptimizerResult.RollbackFailure(restoreResponse ?: "Sem resposta ao restaurar o perfil anterior.")
            }
            clearSavedState()
        }

        val modeResponse = runCatching { service.currentGameMode }.getOrNull()
            ?: return@withContext OptimizerResult.OperationFailure
        if (!modeResponse.startsWith(OK_PREFIX)) {
            return@withContext OptimizerResult.OperationFailure
        }
        val previousMode = GameModeParser.parseMode(modeResponse.removePrefix(OK_PREFIX))
            ?: return@withContext OptimizerResult.OperationFailure

        val overlayResponse = runCatching { service.currentOverlayConfig }.getOrNull()
            ?: return@withContext OptimizerResult.OperationFailure
        if (!overlayResponse.startsWith(OK_PREFIX)) {
            return@withContext OptimizerResult.OperationFailure
        }
        val previousOverlay = overlayResponse.removePrefix(OK_PREFIX).ifBlank { ABSENT_CONFIG }

        val snapshotSaved = preferences.edit()
            .putString(KEY_PREVIOUS_MODE, previousMode.toString())
            .putString(KEY_PREVIOUS_OVERLAY, previousOverlay)
            .putBoolean(KEY_MANAGER_OVERRIDE_ACTIVE, false)
            .commit()
        if (!snapshotSaved) {
            return@withContext OptimizerResult.OperationFailure
        }

        val response = runCatching {
            service.applyRenderingProfile(
                profile.downscaleFactor,
                if (fps60) FPS_60 else FPS_NATIVE,
                useAngle,
                previousMode.toString(),
                previousOverlay,
            )
        }.getOrNull()

        if (response == null) {
            return@withContext OptimizerResult.RollbackFailure("O serviço Shizuku encerrou a conexão durante a aplicação.")
        }

        if (response.startsWith(OK_PREFIX)) {
            val route = response.split('|').getOrNull(1).orEmpty()
            val usedManagerOverride = route == GAME_MANAGER_ROUTE
            val extrasConfigured = response.contains("extras=1")
            val actualAngle = useAngle && extrasConfigured && !usedManagerOverride

            val persisted = preferences.edit()
                .putBoolean(KEY_PROFILE_ACTIVE, true)
                .putString(KEY_ACTIVE_PROFILE, profile.name)
                .putBoolean(KEY_FPS_60, fps60)
                .putBoolean(KEY_USE_ANGLE, actualAngle)
                .putBoolean(KEY_EXTRAS_CONFIGURED, extrasConfigured && !usedManagerOverride)
                .putBoolean(KEY_MANAGER_OVERRIDE_ACTIVE, usedManagerOverride)
                .commit()

            if (persisted) {
                OptimizerResult.Applied(
                    profile = profile,
                    fps60 = fps60,
                    useAngle = actualAngle,
                    usedGameManagerOverride = usedManagerOverride,
                    extrasConfigured = extrasConfigured && !usedManagerOverride,
                )
            } else {
                val rollback = runCatching {
                    service.restoreOriginalState(
                        previousMode.toString(),
                        previousOverlay,
                        usedManagerOverride,
                    )
                }.getOrNull()
                if (rollback == SUCCESS) {
                    clearSavedState()
                    OptimizerResult.OperationFailure("O perfil foi aplicado, mas não foi possível salvar o estado local; a configuração original foi restaurada.")
                } else {
                    OptimizerResult.RollbackFailure(rollback ?: "Falha ao restaurar o perfil após erro de gravação local.")
                }
            }
        } else {
            val rollbackCompleted = response.contains("rollback=ok")
            if (rollbackCompleted) {
                clearSavedState()
            } else {
                preferences.edit()
                    .putBoolean(
                        KEY_MANAGER_OVERRIDE_ACTIVE,
                        response.contains("manager_override_possible=1"),
                    )
                    .commit()
            }

            if (response.startsWith("ERR|rollback_failed")) {
                OptimizerResult.RollbackFailure(response)
            } else {
                OptimizerResult.OperationFailure(response)
            }
        }
    }

    suspend fun restore(): OptimizerResult = withContext(Dispatchers.IO) {
        if (!canRestore()) return@withContext OptimizerResult.NothingToRestore

        val previousMode = preferences.getString(KEY_PREVIOUS_MODE, "0") ?: "0"
        val previousOverlay = preferences.getString(KEY_PREVIOUS_OVERLAY, ABSENT_CONFIG) ?: ABSENT_CONFIG
        val hadManagerOverride = preferences.getBoolean(KEY_MANAGER_OVERRIDE_ACTIVE, false)
        val service = connectOrNull() ?: return@withContext connectionFailure()
        val result = runCatching {
            service.restoreOriginalState(previousMode, previousOverlay, hadManagerOverride)
        }.getOrNull()

        if (result == SUCCESS) {
            clearSavedState()
            OptimizerResult.Restored
        } else {
            OptimizerResult.OperationFailure(result ?: "O serviço Shizuku não respondeu à restauração.")
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
    fun loadingBoostConfigured(): Boolean = preferences.getBoolean(KEY_EXTRAS_CONFIGURED, false)

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
            .putBoolean(KEY_EXTRAS_CONFIGURED, false)
            .putBoolean(KEY_MANAGER_OVERRIDE_ACTIVE, false)
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
        const val KEY_EXTRAS_CONFIGURED = "extras_configured"
        const val KEY_MANAGER_OVERRIDE_ACTIVE = "manager_override_active"
        const val OK_PREFIX = "OK|"
        const val SUCCESS = "OK"
        const val ABSENT_CONFIG = "null"
        const val FPS_60 = "60"
        const val FPS_NATIVE = "disable"
        const val GAME_MANAGER_ROUTE = "game_manager_override"
    }
}
