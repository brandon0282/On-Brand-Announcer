package dev.bws.announcer.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bws.announcer.core.LogEntry
import dev.bws.announcer.core.NotificationParser
import dev.bws.announcer.core.OutputMode
import dev.bws.announcer.core.PrivacyMode
import dev.bws.announcer.core.TimeAnnouncer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

// ================================================================ Apps

private data class AppItem(val pkg: String, val label: String)

@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val c by app.settings.config.collectAsStateWithLifecycle()
    val log by app.log.entries.collectAsStateWithLifecycle()
    val days by app.stats.days.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    val all = remember(log.size) {
        val pm = ctx.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        (log.map { AppItem(it.pkg, it.app) } + launchable + c.apps.keys.map { AppItem(it, NotificationParser.labelFor(pm, it)) })
            .distinctBy { it.pkg }.filter { it.pkg != ctx.packageName }
    }
    val counts = remember(days) { days.flatMap { it.apps.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() } }
    val recentPkgs = remember(log) { log.map { it.pkg }.toSet() }
    val match = all.filter { query.isBlank() || it.label.contains(query, true) }
    val recent = match.filter { it.pkg in recentPkgs }.sortedByDescending { counts[it.pkg] ?: 0 }
    val others = match.filter { it.pkg !in recentPkgs }.sortedBy { it.label.lowercase() }

    @Composable
    fun Row(item: AppItem, divider: Boolean) {
        val rule = c.ruleFor(item.pkg)
        val sub = buildList {
            counts[item.pkg]?.let { add("$it read") }
            rule.spokenName?.let { add(if (it.isEmpty()) "name silent" else "“$it”") }
            if (rule.voiceName != null) add("own voice")
        }.joinToString(" · ").ifEmpty { null }
        IosRow(item.label, subtitle = sub, leading = { AppIcon(item.pkg) }, divider = divider, chevron = true,
            onClick = { nav.push(Route.AppDetail(item.pkg, item.label)) }) {
            IosSwitch(rule.enabled) { v -> app.settings.setAppRule(item.pkg, rule.copy(enabled = v)) }
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { LargeTitle("Apps") }
        item { IosSearchField(query, { query = it }) }
        if (recent.isNotEmpty()) item {
            IosSection("Recently notified", footer = "Tap an app to give it its own name, voice or word limit.") {
                recent.forEachIndexed { i, it -> Row(it, i < recent.lastIndex) }
            }
        }
        if (others.isNotEmpty()) item {
            IosSection("All apps") { others.forEachIndexed { i, it -> Row(it, i < others.lastIndex) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun AppDetailScreen(pkg: String, label: String) {
    val nav = LocalNav.current
    val c by app.settings.config.collectAsStateWithLifecycle()
    val voices by app.speaker.voices.collectAsStateWithLifecycle()
    val days by app.stats.days.collectAsStateWithLifecycle()
    val rule = c.ruleFor(pkg)
    fun set(f: (dev.bws.announcer.core.AppRule) -> dev.bws.announcer.core.AppRule) = app.settings.setAppRule(pkg, f(c.ruleFor(pkg)))
    var name by remember { mutableStateOf(rule.spokenName?.takeIf { it.isNotEmpty() } ?: label) }
    val mode = when { rule.spokenName == null -> 0; rule.spokenName.isEmpty() -> 2; else -> 1 }
    val week = days.takeLast(7).sumOf { it.apps[pkg] ?: 0 }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NavBar(label, back = "Apps")
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(pkg, 72.dp)
            Spacer(Modifier.height(8.dp))
            Text(label, style = IosType.title2)
            Text("$week read in the last 7 days", style = IosType.footnote, color = ios.secondary)
        }
        IosSection {
            IosToggleRow("Read Notifications", rule.enabled, icon = Icons.Filled.Notifications, iconColor = ios.red, divider = false) { v ->
                set { it.copy(enabled = v) }
            }
        }
        IosSection("Announce as", footer = "Silent skips the app name and reads only the message.") {
            IosSegmented(listOf("App name", "Custom", "Silent"), mode, { m ->
                set { it.copy(spokenName = when (m) { 0 -> null; 1 -> name.trim(); else -> "" }) }
            }, Modifier.padding(12.dp).fillMaxWidth())
            if (mode == 1) {
                IosDivider()
                IosTextField(name, { name = it; set { r -> r.copy(spokenName = it.trim()) } }, "Spoken name")
            }
        }
        IosSection("Voice") {
            val v = voices.firstOrNull { it.id == rule.voiceName }
            IosRow("Voice", icon = Icons.Filled.Face, iconColor = ios.purple, value = v?.displayName ?: "Main voice", chevron = true,
                onClick = { nav.push(Route.VoicePicker(pkg, label)) })
            IosSlider("Max words", (if (rule.maxWords > 0) rule.maxWords else c.maxWords).toFloat(), 10f..150f, 13,
                if (rule.maxWords > 0) "${rule.maxWords}" else "Default (${c.maxWords})", divider = false) { x ->
                set { it.copy(maxWords = x.roundToInt()) }
            }
        }
        IosSection {
            IosRow("Play Sample", titleColor = ios.tint, divider = rule.maxWords > 0, onClick = {
                val spoken = when (mode) { 0 -> "$label. "; 1 -> "${name.trim()}. "; else -> "" }
                app.speaker.speak(spoken + "This is a sample notification.", rule.voiceName)
            })
            if (rule.maxWords > 0) IosRow("Use Default Word Limit", titleColor = ios.tint, divider = false, onClick = { set { it.copy(maxWords = 0) } })
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ================================================================ Activity

@Composable
fun ActivityScreen() {
    val log by app.log.entries.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val zone = ZoneId.systemDefault()
    val time = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val shown = log.filter { when (filter) { 1 -> it.spoken != null; 2 -> it.spoken == null; else -> true } }
    val byDay = shown.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { LargeTitle("Activity") }
                if (log.isNotEmpty()) Box(Modifier.padding(end = 12.dp, bottom = 12.dp)) {
                    IosButton("Clear", color = ios.red) { app.log.clear() }
                }
            }
        }
        item {
            IosSegmented(listOf("All", "Read", "Skipped"), filter, { filter = it },
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth())
        }
        if (shown.isEmpty()) item {
            Text("Nothing here yet. Each notification shows up with what was said, or why it was skipped.",
                Modifier.padding(24.dp), color = ios.secondary)
        }
        byDay.forEach { (day, entries) ->
            item(key = day.toString()) {
                IosSection(dayLabel(day)) {
                    entries.forEachIndexed { i, e -> ActivityRow(e, time.format(Instant.ofEpochMilli(e.time).atZone(zone)), i < entries.lastIndex) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ActivityRow(e: LogEntry, time: String, divider: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.clickable { expanded = !expanded }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            AppIcon(e.pkg)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Text(e.app, Modifier.weight(1f), style = IosType.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(time, style = IosType.footnote, color = ios.secondary)
                }
                if (e.spoken != null) {
                    Text(e.spoken, style = IosType.subhead, maxLines = if (expanded) 20 else 2, overflow = TextOverflow.Ellipsis)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(ios.orange))
                        Spacer(Modifier.width(6.dp))
                        Text("Skipped: ${e.reason}", style = IosType.subhead, color = ios.secondary)
                    }
                }
            }
        }
        if (divider) IosDivider(57.dp)
    }
}

private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
    }
}

// ================================================================ Settings

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val c by app.settings.config.collectAsStateWithLifecycle()
    val set = app.settings::update

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(app.settings.exportJson().toByteArray()) }
        Toast.makeText(ctx, "Settings exported", Toast.LENGTH_SHORT).show()
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val ok = runCatching { app.settings.importJson(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()) }.isSuccess
        Toast.makeText(ctx, if (ok) "Settings imported" else "Not a valid settings file", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LargeTitle("Settings")

        IosSection {
            IosRow("User Guide", subtitle = "How every feature works, plus troubleshooting", icon = Icons.Filled.Info,
                iconColor = ios.tint, chevron = true, divider = false, onClick = { nav.push(Route.Manual) })
        }

        IosSection("Output") {
            IosSegmented(listOf("Headphones only", "Headphones & speaker"),
                if (c.outputMode == OutputMode.HEADPHONES_ONLY) 0 else 1,
                { i -> set { it.copy(outputMode = if (i == 0) OutputMode.HEADPHONES_ONLY else OutputMode.ALWAYS) } },
                Modifier.padding(12.dp).fillMaxWidth())
        }

        IosSection("Smart features") {
            IosRow("Quiet Hours", icon = Icons.Filled.Lock, iconColor = ios.indigo, value = quietSummary(c.quiet), chevron = true,
                onClick = { nav.push(Route.QuietHours) })
            IosRow("Priority People", icon = Icons.Filled.Star, iconColor = ios.orange,
                value = if (c.priorityPeople.isEmpty()) "None" else "${c.priorityPeople.size}", chevron = true,
                onClick = { nav.push(Route.Priority) })
            IosRow("Devices", icon = Icons.Filled.Place, iconColor = ios.teal, value = "${c.devices.size}", chevron = true,
                onClick = { nav.push(Route.Devices) })
            IosRow("Time Announcements", icon = Icons.Filled.DateRange, iconColor = ios.purple,
                value = if (c.timeInterval == 0) "Off" else TimeAnnouncer.label(c.timeInterval).removePrefix("Every "), chevron = true,
                divider = false, onClick = { nav.push(Route.TimeAnnouncements) })
        }

        IosSection("Caller ID", footer = "Reads the caller's name from your phone app's incoming-call notification. No phone or contacts permission needed.") {
            IosToggleRow("Announce Callers", c.callerId, icon = Icons.Filled.Call, iconColor = ios.green, divider = c.callerId) { v -> set { it.copy(callerId = v) } }
            if (c.callerId) {
                IosToggleRow("Repeat While Ringing", c.callerRepeat) { v -> set { it.copy(callerRepeat = v) } }
                IosToggleRow("Use Speaker Without Headphones", c.callerSpeaker, divider = false) { v -> set { it.copy(callerSpeaker = v) } }
            }
        }

        IosSection("When to speak") {
            IosToggleRow("While Screen Is On", c.speakWhenScreenOn, icon = Icons.Filled.Info, iconColor = ios.tint) { v -> set { it.copy(speakWhenScreenOn = v) } }
            IosToggleRow("During Calls", c.speakDuringCall, icon = Icons.Filled.Call, iconColor = ios.green) { v -> set { it.copy(speakDuringCall = v) } }
            IosToggleRow("Respect Do Not Disturb", c.respectDnd, icon = Icons.Filled.Lock, iconColor = ios.indigo) { v -> set { it.copy(respectDnd = v) } }
            IosToggleRow("Skip Silent Notifications", c.skipSilent, icon = Icons.Filled.Notifications, iconColor = ios.gray) { v -> set { it.copy(skipSilent = v) } }
            IosToggleRow("Read Newly Seen Apps", c.newAppsEnabled, icon = Icons.Filled.Add, iconColor = ios.orange, divider = false) { v -> set { it.copy(newAppsEnabled = v) } }
        }

        IosSection("Timing", footer = "Delay waits so the voice doesn't talk over the notification sound. If you dismiss a notification before it's read, it's skipped.") {
            IosSlider("Delay", c.delaySeconds.toFloat(), 0f..15f, 14, if (c.delaySeconds == 0) "Off" else "${c.delaySeconds} s") { v ->
                set { it.copy(delaySeconds = v.roundToInt()) }
            }
            IosSlider("Combine bursts within", c.combineSeconds.toFloat(), 0f..10f, 9,
                if (c.combineSeconds == 0) "Off" else "${c.combineSeconds} s",
                subtitle = "Several messages from the same person become one: “3 new messages from Mom”") { v ->
                set { it.copy(combineSeconds = v.roundToInt()) }
            }
            IosSlider("Ignore repeats within", c.duplicateWindowSeconds.toFloat(), 0f..600f, 19,
                if (c.duplicateWindowSeconds == 0) "Off" else formatSecs(c.duplicateWindowSeconds), divider = false) { v ->
                set { it.copy(duplicateWindowSeconds = (v / 30).roundToInt() * 30) }
            }
        }

        IosSection("Privacy", footer = when (c.privacy) {
            PrivacyMode.SHARED_ONLY -> "Full messages in your earbuds and headphones. On the phone speaker, car or other shared audio, only the app name (\u201cNotification from WhatsApp\u201d). Set which devices are private in Devices."
            PrivacyMode.ALWAYS -> "Only the app name is ever said, even in earbuds."
            PrivacyMode.OFF -> "Full messages everywhere, including the phone speaker and car."
        }) {
            IosSegmented(listOf("Shared audio only", "Always", "Off"),
                when (c.privacy) { PrivacyMode.SHARED_ONLY -> 0; PrivacyMode.ALWAYS -> 1; PrivacyMode.OFF -> 2 },
                { i -> set { it.copy(privacy = listOf(PrivacyMode.SHARED_ONLY, PrivacyMode.ALWAYS, PrivacyMode.OFF)[i]) } },
                Modifier.padding(12.dp).fillMaxWidth())
            IosDivider()
            IosToggleRow("Protect Sensitive Messages", c.protectSensitive,
                subtitle = "Codes, banking and security alerts are read in full only on private devices", divider = false) { v ->
                set { it.copy(protectSensitive = v) }
            }
        }

        IosSection("What to say") {
            IosToggleRow("Say App Name", c.sayAppName) { v -> set { it.copy(sayAppName = v) } }
            IosToggleRow("Skip Emojis", c.skipEmojis) { v -> set { it.copy(skipEmojis = v) } }
            IosToggleRow("Shorten Links", c.shortenLinks, subtitle = "“link to youtube.com”") { v -> set { it.copy(shortenLinks = v) } }
            IosSlider("Max words", c.maxWords.toFloat(), 10f..150f, 13, "${c.maxWords}") { v -> set { it.copy(maxWords = v.roundToInt()) } }
            IosRow("Block & Allow Words", value = "${c.blockWords.size + c.allowWords.size}", chevron = true, divider = false,
                onClick = { nav.push(Route.Words) })
        }

        IosSection("System") {
            IosRow("Notification Access", icon = Icons.Filled.Lock, iconColor = ios.gray, chevron = true,
                onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
            IosRow("Text-to-Speech Engine", icon = Icons.Filled.Build, iconColor = ios.gray, chevron = true, divider = false,
                onClick = { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS")) })
        }

        IosSection("Backup") {
            IosRow("Export Settings", titleColor = ios.tint, onClick = { exporter.launch("announcer-settings.json") })
            IosRow("Import Settings", titleColor = ios.tint, divider = false, onClick = { importer.launch(arrayOf("application/json", "text/*")) })
        }

        val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }
        IosSection("Updates", footer = "Opens the latest release in your browser. If its version is newer than yours, download the APK there and install it over this one; your settings are kept. Announcer itself never connects to the internet.") {
            IosRow("Check for Updates", icon = Icons.Filled.Refresh, iconColor = ios.green,
                value = "You have ${version.orEmpty()}", chevron = true, divider = false, onClick = {
                    val ok = runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.isSuccess
                    if (!ok) Toast.makeText(ctx, "No browser found. Visit $RELEASES_URL", Toast.LENGTH_LONG).show()
                })
        }

        IosSection("Data", footer = "Announcer has no internet permission. Your notifications, history and stats never leave this phone.") {
            IosRow("Reset Statistics", titleColor = ios.red, divider = false, onClick = { app.stats.clear() })
        }
        Text("Announcer ${version.orEmpty()}", Modifier.fillMaxWidth().padding(16.dp), color = ios.secondary,
            style = IosType.footnote, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
    }
}

private const val RELEASES_URL = "https://github.com/brandon0282/On-Brand-Announcer/releases/latest"

private fun formatSecs(s: Int) = if (s < 60) "$s s" else if (s % 60 == 0) "${s / 60} min" else "%.1f min".format(s / 60.0)

// ================================================================ Words

@Composable
fun WordsScreen() {
    val c by app.settings.config.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NavBar("Words", back = "Settings")
        WordSection("Block words", "Notifications containing any of these are never read.", c.blockWords, ios.red) { l ->
            app.settings.update { it.copy(blockWords = l) }
        }
        WordSection("Allow words", "If you add any, ONLY notifications containing one of these are read.", c.allowWords, ios.green) { l ->
            app.settings.update { it.copy(allowWords = l) }
        }
    }
}

@Composable
private fun WordSection(title: String, footer: String, words: List<String>, color: androidx.compose.ui.graphics.Color, onChange: (List<String>) -> Unit) {
    var entry by remember { mutableStateOf("") }
    IosSection(title, footer = footer) {
        words.forEach { w ->
            IosRow(w, leading = {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(ios.red).clickable { onChange(words - w) },
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(10.dp, 2.dp).background(androidx.compose.ui.graphics.Color.White))
                }
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(start = 16.dp).size(22.dp).clip(RoundedCornerShape(11.dp)).background(color), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp))
            }
            IosTextField(entry, { entry = it }, "Add word or phrase", Modifier.weight(1f))
            if (entry.isNotBlank()) IosButton("Add", bold = true) {
                val w = entry.trim()
                if (words.none { it.equals(w, true) }) onChange(words + w)
                entry = ""
            }
        }
    }
}
