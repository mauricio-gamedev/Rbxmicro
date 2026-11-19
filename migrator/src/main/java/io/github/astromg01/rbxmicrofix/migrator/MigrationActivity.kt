package io.github.astromg01.rbxmicrofix.migrator

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Base64
import rikka.shizuku.Shizuku
import io.github.astromg01.rbxmicrofix.migrator.shizuku.ISnapshotExportService
import io.github.astromg01.rbxmicrofix.migrator.shizuku.SnapshotExportService
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MigrationActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var exportButton: Button
    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: ISnapshotExportService? = null
    private var binding = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            status.text = "Shizuku conectado. Mantenha o app anterior instalado e salve o snapshot."
            exportButton.text = "SALVAR SNAPSHOT"
            exportButton.isEnabled = true
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            status.text = "Shizuku desconectado. Inicie-o novamente."
            exportButton.isEnabled = false
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_REQUEST) {
            runOnUiThread {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindExporter()
                } else {
                    status.text = "Permissão do Shizuku não concedida."
                    exportButton.isEnabled = true
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = ISnapshotExportService.Stub.asInterface(binder)
            exportSnapshot()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        if (!isLegacyAppInstalled()) {
            status.text = "O RbxMicroFix anterior não está instalado. Não há snapshot para migrar."
            exportButton.isEnabled = false
        } else if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            status.text = "Mantenha o RbxMicroFix antigo instalado. O Shizuku está conectado."
        } else {
            status.text = "Mantenha o RbxMicroFix antigo instalado e inicie o Shizuku."
            exportButton.text = "ABRIR SHIZUKU"
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(0xFF0B0E16.toInt())
        }

        val title = TextView(this).apply {
            text = "RbxMicroFix Snapshot Migrator"
            textSize = 22f
            setTextColor(0xFFF3F4FA.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(title, matchWrap())

        val description = TextView(this).apply {
            text = "Copia o snapshot de recuperação do app antigo antes da troca de assinatura. Não altera as configurações do Android nem tenta restaurá-las."
            textSize = 14f
            setTextColor(0xFFB5BED0.toInt())
            setPadding(0, dp(14), 0, dp(20))
        }
        root.addView(description, matchWrap())

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFA99AFF.toInt())
            setPadding(0, 0, 0, dp(24))
        }
        root.addView(status, matchWrap())

        exportButton = Button(this).apply {
            text = "SALVAR SNAPSHOT"
            isAllCaps = false
            setOnClickListener {
                if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                    openShizuku()
                } else {
                    requestPermissionOrBind()
                }
            }
        }
        root.addView(exportButton, matchWrap())
        setContentView(root)
    }

    private fun requestPermissionOrBind() {
        exportButton.isEnabled = false
        status.text = "Verificando autorização do Shizuku..."
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            status.text = "Atualize o Shizuku para a versão 11 ou superior."
            exportButton.isEnabled = true
            return
        }

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (granted) {
            bindExporter()
        } else {
            runCatching { Shizuku.requestPermission(PERMISSION_REQUEST) }
                .onFailure {
                    status.text = "Não foi possível pedir a permissão do Shizuku."
                    exportButton.isEnabled = true
                }
        }
    }

    private fun bindExporter() {
        if (binding || service != null) {
            if (service != null) exportSnapshot()
            return
        }
        binding = true
        status.text = "Conectando ao serviço de exportação..."
        val args = Shizuku.UserServiceArgs(
            ComponentName(this, SnapshotExportService::class.java),
        ).daemon(false)
            .processNameSuffix("snapshot_export")
            .debuggable(BuildConfig.DEBUG)
            .version(1)
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure {
                binding = false
                status.text = "Falha ao iniciar o serviço de exportação: ${it.message.orEmpty()}"
                exportButton.isEnabled = true
            }
    }

    private fun exportSnapshot() {
        val remote = service ?: return
        status.text = "Lendo o snapshot privado do app antigo..."
        thread(name = "legacy-snapshot-export") {
            val result = runCatching { remote.exportLegacySnapshot() }
                .getOrElse { "ERR|service_error|${it.message.orEmpty()}" }

            if (!result.startsWith("OK|")) {
                mainHandler.post {
                    status.text = "Não foi possível salvar: ${result.removePrefix("ERR|").replace('|', ' ')}"
                    exportButton.isEnabled = true
                }
                return@thread
            }

            val xml = runCatching {
                Base64.decode(result.removePrefix("OK|"), Base64.DEFAULT)
            }.getOrNull()

            if (xml == null || !xml.toString(Charsets.UTF_8).contains("previous_game_mode")) {
                mainHandler.post {
                    status.text = "O snapshot retornado está inválido. O app antigo continua intacto."
                    exportButton.isEnabled = true
                }
                return@thread
            }

            runCatching {
                FileOutputStream(File(filesDir, SNAPSHOT_FILE)).use { it.write(xml) }
            }.onSuccess {
                mainHandler.post {
                    status.text = "Snapshot preservado. Agora você pode desinstalar o RbxMicroFix antigo e instalar a versão 0.2.4. Ao abrir a versão nova, a restauração ficará disponível."
                    exportButton.text = "SNAPSHOT SALVO"
                    exportButton.isEnabled = false
                }
            }.onFailure {
                mainHandler.post {
                    status.text = "Não foi possível gravar o snapshot: ${it.message.orEmpty()}"
                    exportButton.isEnabled = true
                }
            }
        }
    }

    private fun isLegacyAppInstalled(): Boolean =
        runCatching { packageManager.getApplicationInfo(LEGACY_PACKAGE, 0) }.isSuccess

    private fun openShizuku() {
        startActivity(
            packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SHIZUKU_DOWNLOAD_URL)),
        )
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        val args = Shizuku.UserServiceArgs(
            ComponentName(this, SnapshotExportService::class.java),
        ).daemon(false).processNameSuffix("snapshot_export").debuggable(BuildConfig.DEBUG).version(1)
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private companion object {
        const val LEGACY_PACKAGE = "io.github.astromg01.rbxmicrofix"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val PERMISSION_REQUEST = 812
        const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
        const val SNAPSHOT_FILE = "legacy_recovery_snapshot.xml"
    }
}
