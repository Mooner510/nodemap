package kr.mooner510.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.mooner510.appGraph
import kr.mooner510.data.AppSettings

private data class MainTab(val label: String, val icon: ImageVector)

@Composable
fun NodeMapApp() {
    NodeMapTheme {
        val context = LocalContext.current
        val graph = context.appGraph
        var settings by remember { mutableStateOf<AppSettings?>(null) }

        LaunchedEffect(graph) {
            graph.preferences.settings.collect { settings = it }
        }

        val current = settings
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@NodeMapTheme
        }
        if (!current.onboardingCompleted) {
            OnboardingScreen()
            return@NodeMapTheme
        }

        val tabs = remember {
            listOf(
                MainTab("타임랩스", Icons.Rounded.Map),
                MainTab("압정", Icons.Rounded.PushPin),
                MainTab("설정", Icons.Rounded.Settings),
            )
        }
        var selectedTab by remember { mutableIntStateOf(0) }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NodeBottomBar(
                    tabs = tabs,
                    selected = selectedTab,
                    onSelected = { selectedTab = it },
                )
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (selectedTab) {
                    0 -> TimelineScreen()
                    1 -> PinsAndRulesScreen()
                    else -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun NodeBottomBar(
    tabs: List<MainTab>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 7.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(62.dp)
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                val active = selected == index
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clickable { onSelected(index) },
                    shape = RoundedCornerShape(18.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(25.dp),
                        )
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
