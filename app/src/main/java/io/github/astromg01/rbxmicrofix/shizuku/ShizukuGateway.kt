package io.github.astromg01.rbxmicrofix.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import androidx.annotation.MainThread
import io.github.astromg01.rbxmicrofix.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

class ShizukuGateway(context: Context) {
    private val appContext = context.applicationContext

    @Volatile
    private var remote: IRbxShellService? = null
    private var pending: CompletableDeferred<IRbxShellService>? = null

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext, RbxShellService::class.java),
    )
        .daemon(false)
        .processNameSuffix("rbx_engine")
        .debuggable(BuildConfig.DEBUG)
        .version(3)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = IRbxShellService.Stub.asInterface(binder)
            remote = service
            pending?.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            pending?.completeExceptionally(RemoteException("Shizuku service disconnected"))
            pending = null
        }
    }

    suspend fun connect(): IRbxShellService {
        remote?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        remote = null

        val awaiting = withContext(Dispatchers.Main.immediate) {
            remote?.let { return@withContext CompletableDeferred(it) }
            pending?.let { return@withContext it }

            if (!Shizuku.pingBinder()) throw IllegalStateException("Shizuku is not running")
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Shizuku permission not granted")
            }

            CompletableDeferred<IRbxShellService>().also {
                pending = it
                Shizuku.bindUserService(serviceArgs, connection)
            }
        }

        return try {
            withTimeout(SERVICE_CONNECT_TIMEOUT_MS) { awaiting.await() }
        } catch (error: Throwable) {
            withContext(Dispatchers.Main.immediate) {
                if (pending === awaiting) pending = null
            }
            throw error
        }
    }

    @MainThread
    fun release() {
        try {
            if (remote != null || pending != null) {
                Shizuku.unbindUserService(serviceArgs, connection, true)
            }
        } catch (_: Throwable) {
            // Shizuku may already have stopped.
        } finally {
            remote = null
            pending = null
        }
    }

    private companion object {
        const val SERVICE_CONNECT_TIMEOUT_MS = 12_000L
    }
}
