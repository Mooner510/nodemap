package kr.mooner510.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CloudBackupManager(
    private val appContext: Context,
    private val backupManager: BackupManager,
    private val auth: GoogleBackupAuth,
    private val server: BackupServerClient,
) {
    val configured: Boolean get() = auth.configured && server.configured

    suspend fun metadata(activityContext: Context): RemoteBackupMetadata? {
        requireConfigured()
        return server.metadata(auth.idToken(activityContext))
    }

    suspend fun backup(
        activityContext: Context,
        password: CharArray,
        expectedCurrentId: String?,
    ): RemoteBackupMetadata {
        requireConfigured()
        var temp: File? = null
        try {
            val idToken = auth.idToken(activityContext)
            val current = server.metadata(idToken)
            require(current?.id == expectedCurrentId) {
                "온라인 백업 상태가 다른 기기에서 변경되었습니다. 상태를 다시 확인하세요."
            }
            temp = withContext(Dispatchers.IO) {
                File.createTempFile("nodemap-cloud-upload-", ".nodemap", appContext.cacheDir)
            }
            backupManager.export(Uri.fromFile(temp), password)
            return server.upload(idToken, temp, PORTABLE_FORMAT_VERSION)
        } finally {
            password.fill('\u0000')
            temp?.let { withContext(Dispatchers.IO) { it.delete() } }
        }
    }

    suspend fun restore(activityContext: Context, password: CharArray): RemoteBackupMetadata {
        requireConfigured()
        var temp: File? = null
        try {
            val idToken = auth.idToken(activityContext)
            val metadata = server.metadata(idToken) ?: error("복원할 온라인 백업이 없습니다.")
            temp = withContext(Dispatchers.IO) {
                File.createTempFile("nodemap-cloud-download-", ".nodemap", appContext.cacheDir)
            }
            server.download(idToken, temp, metadata)
            backupManager.restore(Uri.fromFile(temp), password)
            return metadata
        } finally {
            password.fill('\u0000')
            temp?.let { withContext(Dispatchers.IO) { it.delete() } }
        }
    }

    suspend fun delete(activityContext: Context) {
        requireConfigured()
        server.delete(auth.idToken(activityContext))
    }

    private fun requireConfigured() {
        require(configured) {
            "온라인 백업 빌드 설정이 없습니다. NODEMAP_BACKUP_SERVER_URL과 NODEMAP_GOOGLE_SERVER_CLIENT_ID를 설정하세요."
        }
    }

    companion object {
        const val PORTABLE_FORMAT_VERSION = 2
    }
}
