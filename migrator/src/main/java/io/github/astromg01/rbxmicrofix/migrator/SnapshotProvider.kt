package io.github.astromg01.rbxmicrofix.migrator

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

class SnapshotProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val callerPackages = context?.packageManager?.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (MAIN_APP_PACKAGE !in callerPackages) {
            throw SecurityException("Only RbxMicroFix can read the recovery snapshot")
        }
        if (uri.path != SNAPSHOT_PATH || mode != "r") {
            throw FileNotFoundException("Unsupported snapshot request")
        }

        val snapshot = File(requireNotNull(context).filesDir, SNAPSHOT_FILE)
        if (!snapshot.isFile) throw FileNotFoundException("Recovery snapshot has not been exported")
        return ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "text/xml"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val MAIN_APP_PACKAGE = "io.github.astromg01.rbxmicrofix"
        const val SNAPSHOT_PATH = "/snapshot"
        const val SNAPSHOT_FILE = "legacy_recovery_snapshot.xml"
    }
}
