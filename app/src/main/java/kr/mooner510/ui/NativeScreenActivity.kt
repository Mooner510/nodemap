package kr.mooner510.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import kr.mooner510.web.NativeScreen

/**
 * Hosts screens that haven't been ported to the web UI yet (pin-rule editor, cloud backup, advanced settings).
 * The web UI opens them through the bridge; the back gesture returns to it. Remove a branch here as soon as its
 * screen exists in apps/ui.
 */
class NativeScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val screen = NativeScreen.entries.firstOrNull { it.key == intent.getStringExtra(EXTRA_SCREEN) }
        if (screen == null) {
            finish()
            return
        }
        setContent {
            NodeMapTheme {
                Box(Modifier.fillMaxSize()) {
                    when (screen) {
                        NativeScreen.Rules -> PinRulesScreen()
                        NativeScreen.CloudBackup -> CloudBackupScreen()
                        NativeScreen.AdvancedSettings -> SettingsScreen()
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_SCREEN = "screen"

        fun intent(context: Context, screen: NativeScreen): Intent =
            Intent(context, NativeScreenActivity::class.java).putExtra(EXTRA_SCREEN, screen.key)
    }
}
