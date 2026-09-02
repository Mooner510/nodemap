package kr.mooner510

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kr.mooner510.tracking.TrackingService
import kr.mooner510.ui.NodeMapApp
import kr.mooner510.ui.NodeMapTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private var unlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NodeMapTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        lifecycleScope.launch {
            val settings = appGraph.preferences.current()
            when {
                !settings.onboardingCompleted -> onUnlocked()
                settings.biometricLockEnabled -> authenticate()
                else -> onUnlocked()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (unlocked) {
            lifecycleScope.launch { startTrackingIfReady() }
        }
    }

    private fun authenticate() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            onUnlocked()
            return
        }

        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED
                    ) {
                        finish()
                    }
                }
            },
        ).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("NodeMap 잠금 해제")
                .setSubtitle("위치 및 타임라인 기록을 보호합니다.")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    private fun onUnlocked() {
        unlocked = true
        setContent { NodeMapApp() }
        lifecycleScope.launch { startTrackingIfReady() }
    }

    private suspend fun startTrackingIfReady() {
        val settings = appGraph.preferences.current()
        if (
            unlocked &&
            settings.onboardingCompleted &&
            settings.trackingEnabled &&
            hasLocationPermission()
        ) {
            TrackingService.start(this)
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
