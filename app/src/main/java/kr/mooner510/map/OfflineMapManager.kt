package kr.mooner510.map

import android.content.Context
import kr.mooner510.data.PreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.util.UUID

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

class OfflineMapManager(
    private val context: Context,
    private val preferences: PreferencesStore,
) {
    private val manager by lazy { OfflineManager.getInstance(context) }
    private val _downloadState = MutableStateFlow<OfflineDownloadState>(OfflineDownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    suspend fun download(
        name: String,
        north: Double,
        east: Double,
        south: Double,
        west: Double,
        minZoom: Double = 5.0,
        maxZoom: Double = 16.0,
    ) {
        val style = preferences.current().mapStyleUri
        val definition = OfflineTilePyramidRegionDefinition(
            style,
            LatLngBounds.from(north, east, south, west),
            minZoom,
            maxZoom,
            context.resources.displayMetrics.density.coerceIn(1f, 3f),
            true,
        )
        val metadata = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("name", name)
            put("createdAt", System.currentTimeMillis())
        }.toString().toByteArray()

        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            _downloadState.value = if (status.isComplete) {
                                OfflineDownloadState.Complete(name, status.completedResourceSize)
                            } else {
                                OfflineDownloadState.Downloading(
                                    name,
                                    status.completedResourceCount,
                                    status.requiredResourceCount,
                                    status.completedResourceSize,
                                )
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            _downloadState.value = OfflineDownloadState.Failed(
                                "${error.reason}: ${error.message}",
                            )
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            _downloadState.value = OfflineDownloadState.Failed(
                                "오프라인 타일 제한 초과: $limit",
                            )
                        }
                    })
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    _downloadState.value = OfflineDownloadState.Failed(error)
                }
            },
        )
    }

    fun list(callback: (List<OfflineRegionInfo>) -> Unit) {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) {
                val available = regions.orEmpty()
                if (available.isEmpty()) {
                    callback(emptyList())
                    return
                }

                val result = mutableListOf<OfflineRegionInfo>()
                var remaining = available.size
                available.forEach { region ->
                    region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            status?.let {
                                result += OfflineRegionInfo(
                                    region.id,
                                    metadataName(region.metadata),
                                    it.completedResourceCount,
                                    it.requiredResourceCount,
                                    it.completedResourceSize,
                                )
                            }
                            remaining -= 1
                            if (remaining == 0) callback(result.sortedBy { it.name })
                        }

                        override fun onError(error: String?) {
                            remaining -= 1
                            if (remaining == 0) callback(result.sortedBy { it.name })
                        }
                    })
                }
            }

            override fun onError(error: String) {
                callback(emptyList())
            }
        })
    }

    fun delete(id: Long, onDone: (Boolean) -> Unit) {
        manager.getOfflineRegion(id, object : OfflineManager.GetOfflineRegionCallback {
            override fun onRegion(region: OfflineRegion) {
                region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = onDone(true)
                    override fun onError(error: String) = onDone(false)
                })
            }

            override fun onRegionNotFound() = onDone(false)
            override fun onError(error: String) = onDone(false)
        })
    }

    private fun metadataName(metadata: ByteArray): String = runCatching {
        JSONObject(metadata.toString(Charsets.UTF_8)).optString("name", "오프라인 지도")
    }.getOrDefault("오프라인 지도")
}
