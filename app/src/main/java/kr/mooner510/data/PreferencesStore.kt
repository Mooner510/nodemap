package kr.mooner510.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "nodemap_settings")

enum class TrackingPreset(val label: String) {
    PRECISE("정밀"),
    BALANCED("균형"),
    BATTERY("절전"),
}

data class AppSettings(
    val trackingEnabled: Boolean = true,
    val trackingPreset: TrackingPreset = TrackingPreset.BALANCED,
    val reverseGeocodingEnabled: Boolean = false,
    val biometricLockEnabled: Boolean = true,
    val autoStartAfterBoot: Boolean = true,
    val mapStyleUri: String = PreferencesStore.DEFAULT_MAP_STYLE,
    val onboardingCompleted: Boolean = false,
    val dialTickMinutes: Int = 5,
    val dialRadiusMinutes: Int = 180,
    val detailWindowMinutes: Int = 120,
    val totalWindowMinutes: Int = 720,
    val cameraWindowMinutes: Int = 30,
)

class PreferencesStore(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map(::decode)

    suspend fun current(): AppSettings = settings.first()
    suspend fun setTrackingEnabled(value: Boolean) = set(TRACKING_ENABLED, value)
    suspend fun setTrackingPreset(value: TrackingPreset) = set(TRACKING_PRESET, value.name)
    suspend fun setReverseGeocodingEnabled(value: Boolean) = set(REVERSE_GEOCODING, value)
    suspend fun setBiometricLockEnabled(value: Boolean) = set(BIOMETRIC_LOCK, value)
    suspend fun setAutoStartAfterBoot(value: Boolean) = set(AUTO_BOOT, value)
    suspend fun setMapStyleUri(value: String) = set(MAP_STYLE_URI, value)
    suspend fun setOnboardingCompleted(value: Boolean) = set(ONBOARDING_COMPLETED, value)

    suspend fun setTimelineDisplaySettings(
        tickMinutes: Int,
        radiusMinutes: Int,
        detailMinutes: Int,
        totalMinutes: Int,
        cameraMinutes: Int,
    ) {
        val tick = tickMinutes.coerceAtLeast(1)
        val radius = radiusMinutes.coerceAtLeast(1)
        val total = totalMinutes.coerceAtLeast(1)
        val detail = detailMinutes.coerceIn(1, total)
        val camera = cameraMinutes.coerceIn(1, detail)
        context.dataStore.edit {
            it[DIAL_TICK_MINUTES] = tick
            it[DIAL_RADIUS_MINUTES] = radius
            it[DETAIL_WINDOW_MINUTES] = detail
            it[TOTAL_WINDOW_MINUTES] = total
            it[CAMERA_WINDOW_MINUTES] = camera
        }
    }

    private fun decode(preferences: Preferences): AppSettings {
        val total = (preferences[TOTAL_WINDOW_MINUTES] ?: 720).coerceAtLeast(1)
        val detail = (preferences[DETAIL_WINDOW_MINUTES] ?: 120).coerceIn(1, total)
        val camera = (preferences[CAMERA_WINDOW_MINUTES] ?: 30).coerceIn(1, detail)
        return AppSettings(
            trackingEnabled = preferences[TRACKING_ENABLED] ?: true,
            trackingPreset = preferences[TRACKING_PRESET]
                ?.let { runCatching { TrackingPreset.valueOf(it) }.getOrNull() }
                ?: TrackingPreset.BALANCED,
            reverseGeocodingEnabled = preferences[REVERSE_GEOCODING] ?: false,
            biometricLockEnabled = preferences[BIOMETRIC_LOCK] ?: true,
            autoStartAfterBoot = preferences[AUTO_BOOT] ?: true,
            mapStyleUri = preferences[MAP_STYLE_URI] ?: DEFAULT_MAP_STYLE,
            onboardingCompleted = preferences[ONBOARDING_COMPLETED] ?: false,
            dialTickMinutes = (preferences[DIAL_TICK_MINUTES] ?: 5).coerceAtLeast(1),
            dialRadiusMinutes = (preferences[DIAL_RADIUS_MINUTES] ?: 180).coerceAtLeast(1),
            detailWindowMinutes = detail,
            totalWindowMinutes = total,
            cameraWindowMinutes = camera,
        )
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    companion object {
        const val DEFAULT_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
        private val TRACKING_ENABLED = booleanPreferencesKey("tracking_enabled")
        private val TRACKING_PRESET = stringPreferencesKey("tracking_preset")
        private val REVERSE_GEOCODING = booleanPreferencesKey("reverse_geocoding")
        private val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")
        private val AUTO_BOOT = booleanPreferencesKey("auto_boot")
        private val MAP_STYLE_URI = stringPreferencesKey("map_style_uri")
        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val DIAL_TICK_MINUTES = intPreferencesKey("dial_tick_minutes")
        private val DIAL_RADIUS_MINUTES = intPreferencesKey("dial_radius_minutes")
        private val DETAIL_WINDOW_MINUTES = intPreferencesKey("detail_window_minutes")
        private val TOTAL_WINDOW_MINUTES = intPreferencesKey("total_window_minutes")
        private val CAMERA_WINDOW_MINUTES = intPreferencesKey("camera_window_minutes")
    }
}
