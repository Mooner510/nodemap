package kr.mooner510

import android.content.Context
import kr.mooner510.backup.BackupManager
import kr.mooner510.backup.BackupServerClient
import kr.mooner510.backup.CloudBackupManager
import kr.mooner510.backup.GoogleBackupAuth
import kr.mooner510.data.AttachmentStore
import kr.mooner510.data.CryptoManager
import kr.mooner510.data.NodeMapDb
import kr.mooner510.data.NodeMapRepository
import kr.mooner510.data.PreferencesStore
import kr.mooner510.geocode.ReverseGeocoder
import kr.mooner510.map.OfflineMapManager
import kr.mooner510.map.TileCache
import kr.mooner510.routine.RoutineShortcutManager

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val crypto = CryptoManager(java.io.File(appContext.filesDir, "nodemap-datakey.bin"))
    val db = NodeMapDb(appContext)
    val attachmentStore = AttachmentStore(appContext, crypto)
    val repository = NodeMapRepository(db, crypto, attachmentStore)
    val preferences = PreferencesStore(appContext)
    val reverseGeocoder = ReverseGeocoder(appContext, repository, crypto, preferences)
    val tileCache = TileCache(appContext)
    val offlineMapManager = OfflineMapManager(appContext, preferences, tileCache)
    val routineShortcutManager = RoutineShortcutManager(appContext, repository)
    val backupManager = BackupManager(appContext, repository, attachmentStore)
    private val googleBackupAuth = GoogleBackupAuth(BuildConfig.GOOGLE_SERVER_CLIENT_ID)
    private val backupServerClient = BackupServerClient(BuildConfig.BACKUP_SERVER_URL)
    val cloudBackupManager = CloudBackupManager(appContext, backupManager, googleBackupAuth, backupServerClient)
}
