package dev.bws.announcer.ui

import android.content.Intent
import android.provider.Settings as SysSettings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bws.announcer.core.DayStats
import dev.bws.announcer.core.ListenerService
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

@Composable
fun DashboardScreen(onOpenTab: (Int) -> Unit) {
    val ctx = LocalContext.current
    val c by app.settings.config.collectAsStateWithLifecycle()
    val days by app.stats.days.collectAsStateWithLifecycle()
    val log by app.log.entries.collectAsStateWithLifecycle()
    val voices by app.speaker.voices.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(ListenerService.hasAccess(ctx)) }
    var device by remember { mutableStateOf(ListenerService.deviceState(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = ListenerService.hasAccess(ctx); device = ListenerService.deviceState(ctx); app.speaker.refreshVoices()
    }

    val today = LocalDate.now()
    val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val byDate = days.associateBy { it.date }
    val weekStats = week.map { byDate[it.toString()] ?: DayStats(it.toString()) }
    val t = weekStats.last()
    val y = weekStats[5]
    val voiceName = voices.firstOrNull { it.id == c.voiceName }?.displayName ?: "System default"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LargeTitle("Summary", today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")))

        // ---- status hero
        IosSection {
            val (color, icon, headline, detail) = when {
                !access -> Quad(ios.orange, Icons.Filled.Warning, "Needs notification access", "Tap below to let Announcer listen.")
                !c.enabled -> Quad(ios.gray, Icons.Filled.Close, "Paused", "Announcements are off.")
                c.quiet.isActive(device.minuteOfDay, device.dayOfWeek) -> Quad(ios.indigo, Icons.Filled.Lock, "Quiet hours",
                    "Until ${formatMinute(c.quiet.endMinute)}" + if (c.priorityPeople.isNotEmpty() && c.priorityBypass) " · priority people allowed" else "")
                else -> Quad(ios.green, Icons.Filled.CheckCircle, "Listening",
                    if (device.headphones) (device.deviceName ?: "Headphones") + " connected" else if (c.outputMode.name == "ALWAYS") "Using the phone speaker"
                    else "Waiting for headphones")
            }
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(headline, style = IosType.headline)
                    Text(detail, style = IosType.footnote, color = ios.secondary)
                    Text("Voice: $voiceName", style = IosType.footnote, color = ios.secondary)
                }
                if (access) IosSwitch(c.enabled) { v -> app.settings.update { it.copy(enabled = v) }; if (!v) app.speaker.stop() }
            }
            if (!access) {
                IosFilledButton("Grant Access", modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth()) {
                    ctx.startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
        }

        // ---- stat tiles
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(Modifier.weight(1f), Icons.Filled.Notifications, ios.tint, "Read today", t.read, delta(t.read, y.read))
                StatTile(Modifier.weight(1f), Icons.Filled.Close, ios.orange, "Skipped today", t.skipped, delta(t.skipped, y.skipped))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(Modifier.weight(1f), Icons.Filled.Face, ios.purple, "Words spoken", t.words,
                    "≈ ${formatMinutes(t.words)} listening")
                StatTile(Modifier.weight(1f), Icons.Filled.DateRange, ios.teal, "Read this week", weekStats.sumOf { it.read },
                    "${weekStats.sumOf { it.skipped }} skipped")
            }
        }

        // ---- 7-day chart
        var sel by remember { mutableStateOf<Int?>(null) }
        val shown = sel ?: 6
        IosSection("Last 7 days") {
            Column(Modifier.padding(16.dp)) {
                val d = weekStats[shown]
                Text("${d.read}", style = IosType.title2)
                Text(
                    if (shown == 6) "read today" else "read on " + week[shown].format(DateTimeFormatter.ofPattern("EEEE")),
                    style = IosType.footnote, color = ios.secondary,
                )
                Spacer(Modifier.height(12.dp))
                BarChart(
                    values = weekStats.map { it.read }, selected = shown, color = ios.tint, height = 120.dp,
                    labels = week.map { it.dayOfWeek.getDisplayName(JTextStyle.NARROW, Locale.getDefault()) },
                    onSelect = { sel = it },
                )
            }
        }

        // ---- hourly
        IosSection("Today by hour", footer = "When notifications were read aloud today.") {
            var hourSel by remember { mutableStateOf<Int?>(null) }
            Column(Modifier.padding(16.dp)) {
                val h = hourSel
                Text(
                    if (h == null) "Peak: ${peakHour(t.hours)}" else "${t.hours[h]} read at ${hourLabel(h)}",
                    style = IosType.subhead, color = ios.secondary,
                )
                Spacer(Modifier.height(8.dp))
                BarChart(values = t.hours, selected = h, color = ios.indigo, height = 80.dp,
                    labels = List(24) { if (it % 6 == 0) hourLabel(it) else "" }, onSelect = { hourSel = it }, gap = 0.35f)
            }
        }

        // ---- top apps
        val weekApps = weekStats.flatMap { it.apps.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        val labels = weekStats.flatMap { it.labels.entries }.associate { it.key to it.value }
        val top = weekApps.entries.sortedByDescending { it.value }.take(5)
        IosSection("Top apps · 7 days") {
            if (top.isEmpty()) Empty("No announcements yet this week.")
            val max = top.maxOfOrNull { it.value } ?: 1
            top.forEachIndexed { i, (pkg, n) ->
                MeterRow(leading = { AppIcon(pkg) }, title = labels[pkg] ?: pkg, count = n, fraction = n / max.toFloat(),
                    color = ios.tint, divider = i < top.lastIndex)
            }
        }

        // ---- skip reasons
        val reasons = weekStats.flatMap { it.reasons.entries }.groupBy({ it.key }, { it.value })
            .mapValues { it.value.sum() }.entries.sortedByDescending { it.value }.take(5)
        IosSection("Why things were skipped · 7 days", footer = "Adjust these in Settings if something important was skipped.") {
            if (reasons.isEmpty()) Empty("Nothing skipped.")
            val max = reasons.maxOfOrNull { it.value } ?: 1
            reasons.forEachIndexed { i, (r, n) ->
                MeterRow(leading = null, title = r, count = n, fraction = n / max.toFloat(), color = ios.gray, divider = i < reasons.lastIndex)
            }
        }

        // ---- last announcement
        log.firstOrNull { it.spoken != null }?.let { e ->
            IosSection("Last announcement") {
                IosRow(e.app, subtitle = e.spoken, leading = { AppIcon(e.pkg) }, divider = false, chevron = true,
                    onClick = { onOpenTab(3) })
            }
        }

        IosSection {
            IosRow("Test voice", icon = Icons.Filled.PlayArrow, iconColor = ios.green, value = voiceName, onClick = {
                app.speaker.preview("This is how your notifications will sound.", c.voiceName)
            }, divider = false)
        }
        Spacer(Modifier.height(24.dp))
    }
}

private data class Quad(val a: Color, val b: ImageVector, val c: String, val d: String)

@Composable
private fun StatTile(modifier: Modifier, icon: ImageVector, color: Color, label: String, value: Int, caption: String) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(ios.card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold), color = color)
        }
        Spacer(Modifier.height(6.dp))
        Text("%,d".format(value), style = IosType.stat, color = ios.label)
        Text(caption, style = IosType.caption, color = ios.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Single-series bar chart: one hue, bars anchored to a hairline baseline with rounded top ends,
 * selected bar full strength and others muted. Tap a bar to inspect it.
 */
@Composable
fun BarChart(
    values: List<Int>, labels: List<String>, selected: Int?, color: Color, height: androidx.compose.ui.unit.Dp,
    onSelect: (Int) -> Unit, gap: Float = 0.45f,
) {
    val base = ios.separator
    val muted = color.copy(alpha = 0.35f)
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(values.size) {
                detectTapGestures { o -> onSelect((o.x / (size.width / values.size)).toInt().coerceIn(0, values.lastIndex)) }
            },
        ) {
            val slot = size.width / values.size
            val bw = slot * (1 - gap)
            val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
            val r = 4.dp.toPx().coerceAtMost(bw / 2)
            values.forEachIndexed { i, v ->
                if (v <= 0) return@forEachIndexed
                val h = (v / max.toFloat()) * (size.height - 2.dp.toPx())
                val x = i * slot + (slot - bw) / 2
                val top = size.height - h
                val c = if (selected == null || i == selected) color else muted
                drawRoundRect(c, Offset(x, top), Size(bw, h), CornerRadius(r, r))
                // square off the bottom so bars sit flat on the baseline
                if (h > r) drawRect(c, Offset(x, size.height - r), Size(bw, r))
            }
            drawRect(base, Offset(0f, size.height - 0.5.dp.toPx()), Size(size.width, 0.5.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEachIndexed { i, l ->
                Text(l, Modifier.weight(1f), style = IosType.caption, textAlign = TextAlign.Center,
                    color = if (i == selected) ios.label else ios.secondary, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun MeterRow(leading: (@Composable () -> Unit)?, title: String, count: Int, fraction: Float, color: Color, divider: Boolean) {
    Column {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.let { it(); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Row {
                    Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = IosType.subhead)
                    Text("$count", style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), color = ios.secondary)
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(ios.fill)) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
                }
            }
        }
        if (divider) IosDivider(if (leading != null) 57.dp else 16.dp)
    }
}

@Composable
private fun Empty(text: String) =
    Text(text, Modifier.padding(16.dp), color = ios.secondary, style = IosType.subhead)

private fun delta(now: Int, before: Int) = when {
    now == before -> "same as yesterday"
    now > before -> "${now - before} more than yesterday"
    else -> "${before - now} fewer than yesterday"
}

/** ~150 spoken words per minute at normal speed. */
private fun formatMinutes(words: Int): String {
    val m = words / 150.0
    return if (m < 1) "${(m * 60).toInt()} sec" else "%.0f min".format(m)
}

private fun hourLabel(h: Int) = when { h == 0 -> "12a"; h < 12 -> "${h}a"; h == 12 -> "12p"; else -> "${h - 12}p" }
private fun peakHour(hours: List<Int>): String {
    val m = hours.maxOrNull() ?: 0
    return if (m == 0) "nothing yet today" else "${hourLabel(hours.indexOf(m))} ($m read)"
}
