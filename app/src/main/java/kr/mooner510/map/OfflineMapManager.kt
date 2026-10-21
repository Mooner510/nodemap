package kr.mooner510.map

import android.content.Context
import kr.mooner510.data.PreferencesStore
import kr.mooner510.data.TrackPoint
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
import java.time.LocalDate
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
)

/**
 * Saves the map around recorded tracks into [TileCache], which the web map reads through the WebView.
 * Every OpenFreeMap style the app offers shares the same tiles, so a region works in all of them.
 */
class OfflineMapManager(
    private val context: Context,
    private val preferences: PreferencesStore,
    private val cache: TileCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var job: Job? = null
    @Volatile private var activeRegion: Long? = null
    private val _downloadState = MutableStateFlow<OfflineDownloadState>(OfflineDownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    init {
        scope.launch { deleteLegacyNativeCache() }
    }

    private val busy get() = job?.isActive == true

    suspend fun downloadTrackPeriod(points: List<TrackPoint>, startDay: LocalDate, endDay: LocalDate) = lock.withLock {
        check(!busy) { "이미 저장 중입니다." }
        require(points.isNotEmpty()) { "선택한 기간에 저장된 위치가 없습니다." }
        val label = if (startDay == endDay) "$startDay 이동 범위" else "$startDay ~ $endDay 이동 범위"
        _downloadState.value = OfflineDownloadState.Downloading(label, 0, 0, 0)
        job = scope.launch {
            run(label) {
                val sources = prepareResources()
                val tiles = sources.flatMapTo(HashSet()) { TileCoverage.tiles(it.key, points, it.minZoom, it.maxZoom) }
                if (tiles.size > MAX_TILES) {
                    throw IOException("저장할 지도가 너무 넓어요(타일 ${tiles.size}개). 기간을 줄여 주세요.")
                }
                cache.createRegion(label, tiles) to sources
            }
        }
    }

    /** Fetches what is still missing for a region that was interrupted. */
    suspend fun resume(id: Long) = lock.withLock {
        check(!busy) { "이미 저장 중입니다." }
        val name = withContext(Dispatchers.IO) { cache.regionName(id) } ?: error("오프라인 지도를 찾을 수 없습니다.")
        _downloadState.value = OfflineDownloadState.Downloading(name, 0, 0, 0)
        job = scope.launch { run(name) { id to prepareResources() } }
    }

    suspend fun list(): List<OfflineRegionInfo> = withContext(Dispatchers.IO) { cache.regions() }

    suspend fun delete(id: Long): Boolean = lock.withLock {
        if (activeRegion == id) {
            job?.cancelAndJoin()
            _downloadState.value = OfflineDownloadState.Idle
        }
        withContext(Dispatchers.IO) { cache.deleteRegion(id) }
    }

    private suspend fun run(name: String, plan: suspend () -> Pair<Long, List<Source>>) {
        val result = runCatching {
            val (regionId, sources) = plan()
            activeRegion = regionId
            downloadRegion(name, regionId, sources)
        }
        activeRegion = null
        _downloadState.value = result.getOrElse {
            if (it is CancellationException) OfflineDownloadState.Idle
            else OfflineDownloadState.Failed(it.message ?: "지도를 저장하지 못했습니다.")
        }
    }

    private suspend fun downloadRegion(name: String, regionId: Long, sources: List<Source>): OfflineDownloadState {
        val templates = sources.associate { it.key to it.template }
        val missing = cache.missingTiles(regionId)
        val info = cache.regions().first { it.id == regionId }
        val total = info.requiredResources
        val done = AtomicLong(total - missing.size)
        val bytes = AtomicLong(info.completedBytes)
        val consecutiveFailures = AtomicInteger()
        val queue = ConcurrentLinkedQueue(missing)

        coroutineScope {
            val progress = launch {
                while (isActive) {
                    _downloadState.value = OfflineDownloadState.Downloading(name, done.get(), total, bytes.get())
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

    private companion object {
        const val MAX_TILES = 30_000
        const val MAX_ZOOM = 14
        const val PARALLEL_DOWNLOADS = 4
        const val GIVE_UP_AFTER = 24
        const val PROGRESS_INTERVAL_MS = 250L
        val STYLE_URIS = listOf("liberty", "bright", "positron", "dark").map { "https://${TileCache.HOST}/styles/$it" }
        val GLYPH_RANGES = listOf("0-255", "256-511", "8192-8447")
    }
}
