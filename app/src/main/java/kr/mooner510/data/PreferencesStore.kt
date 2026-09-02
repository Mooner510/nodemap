package kr.mooner510.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

    private fun decode(preferences: Preferences) = AppSettings(
        trackingEnabled = preferences[TRACKING_ENABLED] ?: true,
        trackingPreset = preferences[TRACKING_PRESET]
            ?.let { runCatching { TrackingPreset.valueOf(it) }.getOrNull() }
            ?: TrackingPreset.BALANCED,
        reverseGeocodingEnabled = preferences[REVERSE_GEOCODING] ?: false,
        biometricLockEnabled = preferences[BIOMETRIC_LOCK] ?: true,
        autoStartAfterBoot = preferences[AUTO_BOOT] ?: true,
        mapStyleUri = preferences[MAP_STYLE_URI] ?: DEFAULT_MAP_STYLE,
        onboardingCompleted = preferences[ONBOARDING_COMPLETED] ?: false,
    )

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
    }
}
