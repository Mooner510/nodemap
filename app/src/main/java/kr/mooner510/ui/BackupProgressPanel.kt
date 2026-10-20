package kr.mooner510.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kr.mooner510.backup.BackupProgress
import kotlin.math.roundToInt

/** Live status of a running backup/restore: current step, percent, elapsed and estimated time left. */
@Composable
fun BackupProgressPanel(progress: BackupProgress, startedAtMs: Long, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val elapsedSec = ((now - startedAtMs) / 1000).coerceAtLeast(0)
    val fraction = progress.fraction.coerceIn(0f, 1f)
    // Early fractions are too noisy (key derivation, DB reads) to extrapolate from.
    val remainingSec = if (fraction >= 0.05f && elapsedSec >= 3) (elapsedSec * (1f - fraction) / fraction).toLong() else null

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(progress.phase, style = MaterialTheme.typography.titleMedium)
            Text("${(fraction * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
        }
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        progress.detail?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "경과 ${formatDuration(elapsedSec)} · " + (remainingSec?.let { "남은 시간 약 ${formatDuration(it)}" } ?: "남은 시간 계산 중"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            "작업이 끝날 때까지 앱을 닫지 마세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatDuration(seconds: Long): String =
    if (seconds >= 60) "${seconds / 60}분 ${seconds % 60}초" else "${seconds}초"
