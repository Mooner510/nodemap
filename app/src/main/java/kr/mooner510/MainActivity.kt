package kr.mooner510

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kr.mooner510.tracking.TrackingService
import kr.mooner510.ui.NodeMapApp
import kotlinx.coroutines.launch

class MainActivity:FragmentActivity(){private var unlocked=false;override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme{}};lifecycleScope.launch{if(appGraph.preferences.current().biometricLockEnabled)authenticate() else onUnlocked()}};override fun onResume(){super.onResume();if(unlocked&&hasLocationPermission())TrackingService.start(this)}
 private fun authenticate(){val a=BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL;if(BiometricManager.from(this).canAuthenticate(a)!=BiometricManager.BIOMETRIC_SUCCESS){onUnlocked();return};BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult)=onUnlocked();override fun onAuthenticationError(errorCode:Int,errString:CharSequence){if(errorCode!=BiometricPrompt.ERROR_NEGATIVE_BUTTON&&errorCode!=BiometricPrompt.ERROR_USER_CANCELED)finish()}}).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("NodeMap 잠금 해제").setSubtitle("위치 및 타임라인 기록을 보호합니다.").setAllowedAuthenticators(a).build())}
 private fun onUnlocked(){unlocked=true;setContent{NodeMapApp()};if(hasLocationPermission())TrackingService.start(this)};private fun hasLocationPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED}
