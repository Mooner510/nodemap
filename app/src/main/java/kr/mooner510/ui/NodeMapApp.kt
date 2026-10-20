package kr.mooner510.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kr.mooner510.appGraph
import kr.mooner510.data.AppSettings
import kr.mooner510.web.WebUiHost

/**
 * Root after unlock. Onboarding is still Compose; everything else is the web UI (apps/ui) drawn edge to edge,
 * with screens that haven't been ported opening in [NativeScreenActivity].
 */
@Composable
fun NodeMapApp() {
    NodeMapTheme(fullBleed = true) {
        val graph = LocalContext.current.appGraph
        var settings by remember { mutableStateOf<AppSettings?>(null) }

        LaunchedEffect(graph) {
            graph.preferences.settings.collect { settings = it }
        }

        val current = settings
        when {
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            !current.onboardingCompleted -> Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                OnboardingScreen()
            }
            else -> WebUiHost(Modifier.fillMaxSize())
        }
    }
}
