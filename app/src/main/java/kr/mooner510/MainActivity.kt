package kr.mooner510

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import kr.mooner510.tracking.TrackingService
import kr.mooner510.ui.NodeMapApp
import kr.mooner510.ui.NodeMapTheme
import kotlinx.coroutines.launch

/** Survives configuration changes (but not process death) so rotation doesn't re-lock the app. */
class MainLockState : ViewModel() {
    var unlocked = false
}

private enum class LockPhase { Authenticating, Retry, Enroll }

class MainActivity : FragmentActivity() {
    private var lockPhase by mutableStateOf(LockPhase.Authenticating)
    private val enrollLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!unlocked) authenticate()
    }
    private val lockState: MainLockState by viewModels()
    private var unlocked: Boolean
        get() = lockState.unlocked
        set(value) {
            lockState.unlocked = value
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (unlocked) {
            // Configuration change after a successful unlock: don't prompt again.
            onUnlocked()
            return
        }
        setContent {
            NodeMapTheme {
                LockGate(
                    phase = lockPhase,
                    onRetry = { authenticate() },
                    onEnroll = { enrollLauncher.launch(BiometricLock.enrollIntent()) },
                )
            }
        }

        lifecycleScope.launch {
            val settings = appGraph.preferences.current()
            when {
                !settings.onboardingCompleted -> onUnlocked()
                // BiometricPrompt silently refuses to show once the FragmentManager state is saved,
                // which would leave the user on the spinner forever. Wait until STARTED.
                settings.biometricLockEnabled -> withStarted { authenticate() }
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
        if (!BiometricLock.isAvailable(this)) {
            lockPhase = LockPhase.Enroll
            return
        }
        lockPhase = LockPhase.Authenticating

        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (isChangingConfigurations || isFinishing) return
                    lockPhase = LockPhase.Retry
                }
            },
        ).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("NodeMap 잠금 해제")
                .setSubtitle("위치 및 타임라인 기록을 보호합니다.")
                .setAllowedAuthenticators(BiometricLock.AUTHENTICATORS)
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

@Composable
private fun LockGate(phase: LockPhase, onRetry: () -> Unit, onEnroll: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (phase) {
            LockPhase.Authenticating -> CircularProgressIndicator()
            LockPhase.Retry -> LockMessage("잠금이 해제되지 않았습니다.", "다시 시도", onRetry)
            LockPhase.Enroll -> LockMessage(
                "앱 잠금을 사용하려면 생체 정보 또는 화면 잠금을 먼저 등록해야 합니다.",
                "등록하러 가기",
                onEnroll,
            )
        }
    }
}

@Composable
private fun LockMessage(text: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onAction) { Text(action) }
    }
}
