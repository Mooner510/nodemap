package kr.mooner510

import android.content.Context
import kr.mooner510.backup.BackupManager
import kr.mooner510.data.AttachmentStore
import kr.mooner510.data.CryptoManager
import kr.mooner510.data.NodeMapDb
import kr.mooner510.data.NodeMapRepository
import kr.mooner510.data.PreferencesStore
import kr.mooner510.geocode.ReverseGeocoder
import kr.mooner510.map.OfflineMapManager
import kr.mooner510.routine.RoutineShortcutManager

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val crypto = CryptoManager()
    val db = NodeMapDb(appContext)
    val attachmentStore = AttachmentStore(appContext, crypto)
    val repository = NodeMapRepository(db, crypto, attachmentStore)
    val preferences = PreferencesStore(appContext)
    val reverseGeocoder = ReverseGeocoder(appContext, repository, crypto, preferences)
    val offlineMapManager = OfflineMapManager(appContext, preferences)
    val routineShortcutManager = RoutineShortcutManager(appContext, repository)
    val backupManager = BackupManager(appContext, repository, attachmentStore)
}
