package io.github.astromg01.rbxmicrofix

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.astromg01.rbxmicrofix.core.PerformanceProfile
import io.github.astromg01.rbxmicrofix.data.OptimizerRepository
import io.github.astromg01.rbxmicrofix.data.OptimizerResult
import kotlinx.coroutines.launch
import org.json.JSONObject
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private lateinit var repository: OptimizerRepository
    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var pageReady = false
    @Volatile private var statusMessage = "Escolha um perfil para aplicar a renderização ao Roblox."
    @Volatile private var busy = false
    @Volatile private var shizukuReady = false
    private var queuedAction: PendingAction? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            shizukuReady = true
            if (!busy) statusMessage = "Conector Shizuku pronto."
            val queued = queuedAction
            if (queued != null) {
                queuedAction = null
                requestShizukuPermission(queued)
            }
            publishState()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            shizukuReady = false
            if (!busy) statusMessage = "Conector Shizuku desconectado."
            publishState()
        }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                shizukuReady = true
                executeQueuedAction()
            } else {
                queuedAction = null
                statusMessage = "Permissão do Shizuku não concedida."
                publishState()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = OptimizerRepository(applicationContext)
        shizukuReady = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        webView = WebView(this).apply {
            setBackgroundColor(0xFF090B12.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    publishState()
                }
            }
            addJavascriptInterface(NativeBridge(), JS_BRIDGE_NAME)
        }
        setContentView(webView)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val html = assets.open(HTML_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        webView.loadDataWithBaseURL(
            LOCAL_WEB_ORIGIN,
            html,
            "text/html",
            "UTF-8",
            null,
        )
    }

    override fun onResume() {
        super.onResume()
        shizukuReady = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        publishState()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        repository.release()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun requestShizukuPermission(action: PendingAction) {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            queuedAction = action
            statusMessage = "Inicie o Shizuku para ativar o conector. A ação continuará ao retornar."
            publishState()
            openShizuku()
            return
        }

        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            statusMessage = "Atualize o Shizuku para a versão atual."
            publishState()
            return
        }

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        queuedAction = action
        if (granted) {
            executeQueuedAction()
        } else {
            runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST) }
                .onFailure {
                    queuedAction = null
                    statusMessage = "Não foi possível solicitar a autorização do conector."
                    publishState()
                }
        }
    }

    private fun executeQueuedAction() {
        val action = queuedAction ?: return
        queuedAction = null
        when (action) {
            is PendingAction.Apply -> applyProfile(action.profile, action.fps60, action.useAngle)
            PendingAction.Restore -> restoreProfile()
            PendingAction.Restart -> restartRoblox()
        }
    }

    private fun applyProfile(profile: PerformanceProfile, fps60: Boolean, useAngle: Boolean) {
        if (busy) return
        busy = true
        statusMessage = "Aplicando ${profile.title.lowercase()} ao perfil de renderização do Roblox..."
        publishState()

        lifecycleScope.launch {
            when (val result = repository.apply(profile, fps60, useAngle)) {
                is OptimizerResult.Applied -> {
                    statusMessage = buildString {
                        append("Perfil ${result.profile.title} aplicado ao Roblox.")
                        append(" Reinicie o cliente para a nova intervenção entrar na renderização.")
                    }
                }
                OptimizerResult.PermissionFailure -> statusMessage = "Autorize o RbxMicroFix no Shizuku e tente novamente."
                OptimizerResult.RollbackFailure -> statusMessage = "A aplicação foi interrompida. Use Restaurar configuração para concluir a reversão."
                else -> statusMessage = "Não foi possível aplicar o perfil. A configuração anterior foi preservada quando a reversão foi concluída."
            }
            busy = false
            publishState()
        }
    }

    private fun restoreProfile() {
        if (busy) return
        busy = true
        statusMessage = "Restaurando a configuração original do Roblox..."
        publishState()

        lifecycleScope.launch {
            when (repository.restore()) {
                OptimizerResult.Restored -> statusMessage = "Configuração original restaurada. Reinicie o Roblox para aplicar a restauração."
                OptimizerResult.NothingToRestore -> statusMessage = "Nenhuma alteração do RbxMicroFix está ativa."
                OptimizerResult.PermissionFailure -> statusMessage = "Autorize o RbxMicroFix no Shizuku e tente novamente."
                else -> statusMessage = "Não foi possível concluir a restauração."
            }
            busy = false
            publishState()
        }
    }

    private fun restartRoblox() {
        if (busy) return
        busy = true
        statusMessage = "Reiniciando o cliente Roblox..."
        publishState()

        lifecycleScope.launch {
            val stopped = repository.forceStopRoblox()
            busy = false
            if (!stopped) {
                statusMessage = "Não foi possível reiniciar o cliente Roblox."
                publishState()
                return@launch
            }

            statusMessage = "Abrindo o Roblox com o perfil aplicado..."
            publishState()
            mainHandler.postDelayed({ launchRoblox() }, RESTART_DELAY_MS)
        }
    }

    private fun launchRoblox() {
        val launchIntent = packageManager.getLaunchIntentForPackage(ROBLOX_PACKAGE)
        if (launchIntent == null) {
            statusMessage = "O Roblox oficial não está instalado."
            publishState()
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
    }

    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent != null) {
            startActivity(intent)
        } else {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URL)))
            }
        }
    }

    private fun publishState() {
        if (!pageReady || !::webView.isInitialized) return
        val payload = JSONObject()
            .put("status", statusMessage)
            .put("busy", busy)
            .put("shizukuReady", shizukuReady)
            .put("activeProfile", repository.activeProfile()?.name ?: JSONObject.NULL)
            .put("fps60", repository.fps60Enabled())
            .put("useAngle", repository.angleEnabled())
            .put("canRestore", repository.canRestore())
            .toString()
        webView.post {
            webView.evaluateJavascript("window.RbxUI && window.RbxUI.onNativeState($payload)", null)
        }
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun getState(): String = JSONObject()
            .put("status", statusMessage)
            .put("busy", busy)
            .put("shizukuReady", shizukuReady)
            .put("activeProfile", repository.activeProfile()?.name ?: JSONObject.NULL)
            .put("fps60", repository.fps60Enabled())
            .put("useAngle", repository.angleEnabled())
            .put("canRestore", repository.canRestore())
            .toString()

        @JavascriptInterface
        fun applyProfile(profileName: String, fps60: Boolean, useAngle: Boolean) {
            runOnUiThread {
                val profile = runCatching { PerformanceProfile.valueOf(profileName) }.getOrNull()
                if (profile == null) {
                    statusMessage = "Perfil inválido."
                    publishState()
                } else {
                    requestShizukuPermission(PendingAction.Apply(profile, fps60, useAngle))
                }
            }
        }

        @JavascriptInterface
        fun restoreOriginal() = runOnUiThread {
            requestShizukuPermission(PendingAction.Restore)
        }

        @JavascriptInterface
        fun restartRoblox() = runOnUiThread {
            requestShizukuPermission(PendingAction.Restart)
        }

        @JavascriptInterface
        fun launchRoblox() = runOnUiThread { this@MainActivity.launchRoblox() }

        @JavascriptInterface
        fun openShizuku() = runOnUiThread { this@MainActivity.openShizuku() }
    }

    private sealed interface PendingAction {
        data class Apply(val profile: PerformanceProfile, val fps60: Boolean, val useAngle: Boolean) : PendingAction
        data object Restore : PendingAction
        data object Restart : PendingAction
    }

    private companion object {
        const val ROBLOX_PACKAGE = "com.roblox.client"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_PERMISSION_REQUEST = 701
        const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
        const val JS_BRIDGE_NAME = "RbxNative"
        const val HTML_ASSET = "index.html"
        const val LOCAL_WEB_ORIGIN = "https://appassets.androidplatform.net/"
        const val RESTART_DELAY_MS = 500L
    }
}
