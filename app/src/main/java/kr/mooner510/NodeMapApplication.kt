package kr.mooner510

import android.app.Application
import org.maplibre.android.MapLibre

class NodeMapApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        graph = AppGraph(this)
    }
}

val android.content.Context.appGraph: AppGraph
    get() = (applicationContext as NodeMapApplication).graph
