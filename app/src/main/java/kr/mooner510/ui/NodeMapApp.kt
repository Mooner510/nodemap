package kr.mooner510.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kr.mooner510.web.WebUiHost

/** Root after unlock: every screen, onboarding included, is the web UI (apps/ui) drawn edge to edge. */
@Composable
fun NodeMapApp() {
    NodeMapTheme(fullBleed = true) {
        WebUiHost(Modifier.fillMaxSize())
    }
}
