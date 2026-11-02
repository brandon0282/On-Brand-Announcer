package dev.bws.announcer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bws.announcer.AnnouncerApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent { IosTheme { Root() } }
    }
}

internal val app get() = AnnouncerApp.instance

/** Pushed pages on top of a tab (iOS navigation-stack style). */
sealed interface Route {
    data class AppDetail(val pkg: String, val label: String) : Route
    data object Words : Route
    data class VoicePicker(val pkg: String?, val label: String?, val device: String? = null) : Route
    data object QuietHours : Route
    data object Devices : Route
    data class DeviceDetail(val name: String) : Route
    data object Priority : Route
    data object TimeAnnouncements : Route
    data object Manual : Route
}

class Nav {
    val stack = mutableStateListOf<Route>()
    fun push(r: Route) { stack += r }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
}

val LocalNav = staticCompositionLocalOf { Nav() }

private data class Tab(val title: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("Summary", Icons.Filled.Home),
    Tab("Apps", Icons.Filled.Notifications),
    Tab("Voice", Icons.Filled.Face),
    Tab("Activity", Icons.Filled.DateRange),
    Tab("Settings", Icons.Filled.Settings),
)

@Composable
private fun Root() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val nav = remember { Nav() }
    BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }

    CompositionLocalProvider(LocalNav provides nav) {
        Column(Modifier.fillMaxSize().background(ios.background)) {
            Box(Modifier.weight(1f).statusBarsPadding()) {
                val top = nav.stack.lastOrNull()
                AnimatedContent(
                    targetState = Triple(top, tab, nav.stack.size),
                    transitionSpec = {
                        val from = initialState.third; val to = targetState.third
                        when {
                            to > from -> slideInHorizontally(tween(280)) { it } togetherWith slideOutHorizontally(tween(280)) { -it / 3 }
                            to < from -> slideInHorizontally(tween(280)) { -it / 3 } togetherWith slideOutHorizontally(tween(280)) { it }
                            else -> fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                        }
                    },
                    label = "nav",
                ) { (route, t, _) ->
                    when (route) {
                        is Route.AppDetail -> AppDetailScreen(route.pkg, route.label)
                        is Route.Words -> WordsScreen()
                        is Route.VoicePicker -> VoicePickerScreen(route.pkg, route.label, route.device)
                        is Route.QuietHours -> QuietHoursScreen()
                        is Route.Devices -> DevicesScreen()
                        is Route.DeviceDetail -> DeviceDetailScreen(route.name)
                        is Route.Priority -> PriorityScreen()
                        is Route.TimeAnnouncements -> TimeAnnouncementsScreen()
                        is Route.Manual -> ManualScreen()
                        null -> when (t) {
                            0 -> DashboardScreen(onOpenTab = { tab = it })
                            1 -> AppsScreen()
                            2 -> VoiceScreen()
                            3 -> ActivityScreen()
                            else -> SettingsScreen()
                        }
                    }
                }
            }
            TabBar(tab) { nav.stack.clear(); tab = it }
        }
    }
}

@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.background(ios.card.copy(alpha = 0.96f))) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
        Row(Modifier.navigationBarsPadding().height(54.dp)) {
            tabs.forEachIndexed { i, t ->
                val color = if (i == selected) ios.tint else ios.gray
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, t.title, tint = color, modifier = Modifier.size(26.dp))
                    Text(t.title, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Inline nav bar for pushed pages: "‹ Back" on the left, centered title, optional trailing action. */
@Composable
fun NavBar(title: String, back: String = "Back", trailing: (@Composable () -> Unit)? = null) {
    val nav = LocalNav.current
    Box(Modifier.fillMaxWidth().height(44.dp)) {
        Row(
            Modifier.align(Alignment.CenterStart).clickable(remember { MutableInteractionSource() }, null) { nav.pop() }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.KeyboardArrowLeft, null, tint = ios.tint, modifier = Modifier.size(32.dp))
            Text(back, color = ios.tint)
        }
        Text(title, style = IosType.headline, color = ios.label, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).widthIn(max = 220.dp), maxLines = 1)
        trailing?.let { Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) { it() } }
    }
}

@Composable
fun Hairline(color: Color = ios.separator) = Box(Modifier.fillMaxWidth().height(0.5.dp).background(color))
