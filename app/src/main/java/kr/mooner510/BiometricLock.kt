package kr.mooner510

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager

object BiometricLock {
    const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Opens the system flow that registers a fingerprint/face or, failing that, a screen lock. */
    fun enrollIntent(): Intent =
        Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
            Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
            AUTHENTICATORS,
        )
}
