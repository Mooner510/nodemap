package kr.mooner510

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NodeMapApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { graph.repository.migrateLegacyEncryption() }
        }
    }
}

val android.content.Context.appGraph: AppGraph
    get() = (applicationContext as NodeMapApplication).graph
