package kr.mooner510.map

import android.content.Context
import kr.mooner510.data.NodeMapRepository
import kr.mooner510.data.PreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

sealed interface OfflineDownloadState {
    data object Idle : OfflineDownloadState
    data class Downloading(
        val name: String,
        val completed: Long,
        val required: Long,
        val bytes: Long,
        val auto: Boolean = false,
        /** When the latest tile arrived (0 = none yet), so the UI can tell a live download from a stuck one. */
        val lastTileAt: Long = 0,
    ) : OfflineDownloadState
    data class Complete(val name: String, val bytes: Long) : OfflineDownloadState
    data class Failed(val message: String) : OfflineDownloadState
}

data class OfflineRegionInfo(
    val id: Long,
    val name: String,
    val completedResources: Long,
    val requiredResources: Long,
    val completedBytes: Long,
    val auto: Boolean = false,
)

/**
 * Saves the map around every recorded track into one growing region of [TileCache], which the web map reads
 * through the WebView. Downloads only run on Wi-Fi.
 * Every OpenFreeMap style the app offers shares the same tiles, so a region works in all of them.
 */
class OfflineMapManager(
    private val context: Context,
    private val preferences: PreferencesStore,
    private val repository: NodeMapRepository,
    private val cache: TileCache,
    private val wifi: WifiMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var job: Job? = null
    @Volatile private var jobIsAuto = false
    /** Why the running job is being cancelled; written to the download log. */
    @Volatile private var stopReason: String? = null
    @Volatile private var activeRegion: Long? = null
    private val _downloadState = MutableStateFlow<OfflineDownloadState>(OfflineDownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    init {
        scope.launch {
            deleteLegacyNativeCache()
            cache.closeInterruptedLogs()
        }
        // Map downloads never continue over mobile data.
        scope.launch { wifi.wifi.collect { if (!it) stop("Wi-Fi 연결이 끊김") } }
    }

    private val busy get() = job?.isActive == true

    /**
     * Downloads the map around every recorded track now, without waiting for the auto loop. Returns once the
     * download has started; it keeps going while the app is in the background but stops if Wi-Fi drops.
     */
    suspend fun downloadAll() = lock.withLock {
        check(wifi.wifi.value) { "Wi-Fi에 연결되어 있을 때만 받을 수 있어요." }
        // Already fetching the same region: just keep it going when the app leaves the screen.
        if (busy) {
            jobIsAuto = false
            return@withLock
        }
        _downloadState.value = OfflineDownloadState.Downloading(AUTO_REGION_NAME, 0, 0, 0)
        job = launchJob(auto = false) { run(AUTO_REGION_NAME, auto = false) { planAuto() } }
    }

    /**
     * The auto loop's run: same as [downloadAll] but waits for the result and leaves a running download alone.
     * Returns true once the region is complete, false if it could not run or was stopped.
     */
    suspend fun syncAuto(): Boolean {
        val started = lock.withLock {
            if (busy || !wifi.wifi.value) return false
            launchJob(auto = true) { run(AUTO_REGION_NAME, auto = true) { planAuto() } }.also { job = it }
        }
        started.join()
        return _downloadState.value !is OfflineDownloadState.Failed && !started.isCancelled
    }

    /** Cancels a running auto download; one the user started is left alone. */
    fun stopAuto(reason: String) {
        if (jobIsAuto) stop(reason)
    }

    private fun stop(reason: String) {
        if (busy) {
            stopReason = reason
            job?.cancel()
        }
    }

    private fun launchJob(auto: Boolean, block: suspend () -> Unit): Job {
        jobIsAuto = auto
        stopReason = null
        return scope.launch { block() }
    }

    private suspend fun planAuto(): Pair<Long, List<Source>> {
        val sources = prepareResources()
        val (regionId, coveredUntil) = cache.autoRegion() ?: (cache.createAutoRegion(AUTO_REGION_NAME) to 0L)
        // Inclusive, so the segment from the last covered point to the first new one is part of the corridor.
        val points = repository.trackPointsBetween(coveredUntil, Long.MAX_VALUE)
        if (points.isNotEmpty()) {
            val tiles = sources.flatMapTo(HashSet()) { TileCoverage.tiles(it.key, points, it.minZoom, it.maxZoom) }
            cache.extendRegion(regionId, tiles, points.last().timestamp)
        }
        return regionId to sources
    }

    suspend fun list(): List<OfflineRegionInfo> = withContext(Dispatchers.IO) { cache.regions() }

    suspend fun recentLogs(limit: Int = 10): List<DownloadLogEntry> = withContext(Dispatchers.IO) { cache.recentLogs(limit) }

    suspend fun delete(id: Long): Boolean = lock.withLock {
        if (activeRegion == id) {
            stopReason = "지역을 삭제함"
            job?.cancelAndJoin()
            _downloadState.value = OfflineDownloadState.Idle
        }
        withContext(Dispatchers.IO) { cache.deleteRegion(id) }
    }

    private suspend fun run(name: String, auto: Boolean, plan: suspend () -> Pair<Long, List<Source>>) {
        val result = runCatching {
            val (regionId, sources) = plan()
            activeRegion = regionId
            downloadRegion(name, regionId, sources, auto)
        }
        activeRegion = null
        _downloadState.value = result.getOrElse {
            if (it is CancellationException) OfflineDownloadState.Idle
            else OfflineDownloadState.Failed(it.message ?: "지도를 저장하지 못했습니다.")
        }
    }

    private suspend fun downloadRegion(name: String, regionId: Long, sources: List<Source>, auto: Boolean): OfflineDownloadState {
        val templates = sources.associate { it.key to it.template }
        val missing = cache.missingTiles(regionId)
        val info = cache.regions().first { it.id == regionId }
        // Nothing new for the auto region is the normal case; don't announce it.
        if (missing.isEmpty()) return if (auto) OfflineDownloadState.Idle else OfflineDownloadState.Complete(name, info.completedBytes)
        val total = info.requiredResources
        val done = AtomicLong(total - missing.size)
        val bytes = AtomicLong(info.completedBytes)
        val lastTileAt = AtomicLong()
        val consecutiveFailures = AtomicInteger()
        val queue = ConcurrentLinkedQueue(missing)

        val logId = cache.startLog(name, auto, missing.size)
        val startDone = done.get()
        val startBytes = bytes.get()
        fun finishLog(result: String, message: String?) = runCatching {
            cache.finishLog(logId, result, message, done.get() - startDone, bytes.get() - startBytes, total - done.get(), lastTileAt.get())
        }

        try {
            coroutineScope {
                val progress = launch {
                    var ticks = 0
                    while (isActive) {
                        _downloadState.value = OfflineDownloadState.Downloading(name, done.get(), total, bytes.get(), auto, lastTileAt.get())
                        if (ticks++ % LOG_EVERY_TICKS == 0) {
                            runCatching { cache.updateLog(logId, done.get() - startDone, bytes.get() - startBytes, total - done.get(), lastTileAt.get()) }
                        }
                        delay(PROGRESS_INTERVAL_MS)
                    }
                }
                List(PARALLEL_DOWNLOADS) {
                    launch {
                        while (isActive && consecutiveFailures.get() < GIVE_UP_AFTER) {
                            val tile = queue.poll() ?: break
                            val size = templates[tile.source]?.let { template -> retry { cache.downloadTile(template, tile) } }
                            if (size == null) {
                                consecutiveFailures.incrementAndGet()
                            } else {
                                consecutiveFailures.set(0)
                                done.incrementAndGet()
                                bytes.addAndGet(size.toLong())
                                lastTileAt.set(System.currentTimeMillis())
                            }
                        }
                    }
                }.forEach { it.join() }
                progress.cancel()
            }

            val remaining = total - done.get()
            if (consecutiveFailures.get() >= GIVE_UP_AFTER) {
                throw IOException("지도 서버에 연결할 수 없어 멈췄어요. 남은 ${remaining}개는 이어 받기로 받을 수 있어요.")
            }
            if (remaining > 0) throw IOException("타일 ${remaining}개를 받지 못했어요. 이어 받기로 다시 시도할 수 있어요.")
        } catch (e: CancellationException) {
            finishLog(TileCache.LOG_STOPPED, stopReason ?: "중단됨")
            throw e
        } catch (e: Exception) {
            finishLog(TileCache.LOG_FAILED, e.message)
            throw e
        }
        finishLog(TileCache.LOG_COMPLETE, null)
        return OfflineDownloadState.Complete(name, bytes.get())
    }

    private suspend fun <T> retry(block: () -> T): T? {
        repeat(3) { attempt ->
            runCatching { return block() }
            delay(500L shl attempt)
        }
        return null
    }

    // ---- Styles and their resources ---------------------------------------------------

    private class Source(val key: String, val template: String, val minZoom: Int, val maxZoom: Int)

    /**
     * Caches every style the app offers plus their TileJSON, sprites and Latin glyph ranges, and returns the
     * tile sources to download. Hangul/CJK labels are drawn by maplibre with the system font, so their glyph
     * ranges are not needed.
     */
    private suspend fun prepareResources(): List<Source> = withContext(Dispatchers.IO) {
        val styleUris = (STYLE_URIS + preferences.current().mapStyleUri).distinct().filter { it.contains(TileCache.HOST) }
        val sources = LinkedHashMap<String, Source>()
        val fonts = HashSet<String>()
        val sprites = HashSet<String>()
        val glyphTemplates = HashSet<String>()
        styleUris.forEach { uri ->
            val style = runCatching { JSONObject(String(cache.prefetchResource(uri, force = true))) }
                .getOrElse { throw IOException("지도 서버에 연결할 수 없어요.", it) }
            style.optString("glyphs").takeIf { it.isNotEmpty() }?.let(glyphTemplates::add)
            when (val sprite = style.opt("sprite")) {
                is String -> sprites += sprite
                is JSONArray -> for (i in 0 until sprite.length()) sprite.optJSONObject(i)?.optString("url")?.let(sprites::add)
            }
            val layers = style.optJSONArray("layers") ?: JSONArray()
            for (i in 0 until layers.length()) {
                val font = layers.optJSONObject(i)?.optJSONObject("layout")?.optJSONArray("text-font") ?: continue
                // Only literal font stacks; the bundled styles don't use expressions here.
                if ((0 until font.length()).all { font.opt(it) is String }) {
                    fonts += (0 until font.length()).joinToString(",") { font.getString(it) }
                }
            }
            val styleSources = style.optJSONObject("sources") ?: return@forEach
            styleSources.keys().forEach { id ->
                val src = styleSources.getJSONObject(id)
                if (src.optString("type") !in setOf("vector", "raster")) return@forEach
                val tileJson = src.optString("url").takeIf { it.isNotEmpty() }
                    ?.let { JSONObject(String(cache.prefetchResource(it, force = true))) }
                    ?: src
                val template = tileJson.optJSONArray("tiles")?.optString(0)?.takeIf { it.isNotEmpty() } ?: return@forEach
                val key = TileCache.tileKey(TileCache.tileUrl(template, TileKey("", 0, 0, 0)))?.source ?: return@forEach
                sources.getOrPut(key) {
                    Source(
                        key = key,
                        template = template,
                        minZoom = tileJson.optInt("minzoom", 0),
                        maxZoom = tileJson.optInt("maxzoom", MAX_ZOOM).coerceAtMost(MAX_ZOOM),
                    )
                }
            }
        }
        sprites.forEach { base ->
            listOf("", "@2x").forEach { ratio ->
                listOf(".json", ".png").forEach { ext -> runCatching { cache.prefetchResource("$base$ratio$ext") } }
            }
        }
        glyphTemplates.forEach { template ->
            fonts.forEach { stack ->
                val encoded = URLEncoder.encode(stack, "UTF-8").replace("+", "%20")
                GLYPH_RANGES.forEach { range ->
                    runCatching { cache.prefetchResource(template.replace("{fontstack}", encoded).replace("{range}", range)) }
                }
            }
        }
        if (sources.isEmpty()) throw IOException("이 지도 스타일은 오프라인 저장을 지원하지 않아요.")
        sources.values.toList()
    }

    /** Regions saved by the native MapLibre SDK before the map moved into the WebView; nothing reads them now. */
    private fun deleteLegacyNativeCache() {
        listOfNotNull(context.filesDir, context.getExternalFilesDir(null)).forEach { dir ->
            dir.listFiles { f: File -> f.name.startsWith("mbgl-offline.db") }?.forEach { it.delete() }
        }
    }

    companion object {
        const val AUTO_REGION_NAME = "다닌 곳 (자동 저장)"
        private const val MAX_ZOOM = 14
        private const val PARALLEL_DOWNLOADS = 4
        private const val GIVE_UP_AFTER = 24
        private const val PROGRESS_INTERVAL_MS = 250L
        /** Write the log heartbeat every 2 s rather than on every progress tick. */
        private const val LOG_EVERY_TICKS = 8
        private val STYLE_URIS = listOf("liberty", "bright", "positron", "dark").map { "https://${TileCache.HOST}/styles/$it" }
        private val GLYPH_RANGES = listOf("0-255", "256-511", "8192-8447")
    }
}
