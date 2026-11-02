package dev.bws.announcer.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings as SysSettings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bws.announcer.core.ListenerService
import dev.bws.announcer.core.QuietHours
import dev.bws.announcer.core.TimeAnnouncer
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun formatMinute(m: Int): String =
    LocalTime.of(m / 60 % 24, m % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

fun quietSummary(q: QuietHours): String =
    if (!q.enabled) "Off" else "${formatMinute(q.startMinute)} – ${formatMinute(q.endMinute)}"

// ================================================================ Quiet hours

@Composable
fun QuietHoursScreen() {
    val ctx = LocalContext.current
    val c by app.settings.config.collectAsStateWithLifecycle()
    val q = c.quiet
    fun set(f: (QuietHours) -> QuietHours) = app.settings.update { it.copy(quiet = f(it.quiet)) }
    fun pick(initial: Int, onPick: (Int) -> Unit) =
        TimePickerDialog(ctx, { _, h, m -> onPick(h * 60 + m) }, initial / 60, initial % 60, DateFormat.is24HourFormat(ctx)).show()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NavBar("Quiet Hours", back = "Settings")
        IosSection(footer = "Nothing is read during quiet hours. Priority people can still come through if you allow it.") {
            IosToggleRow("Quiet Hours", q.enabled, icon = Icons.Filled.Lock, iconColor = ios.indigo, divider = false) { v -> set { it.copy(enabled = v) } }
        }
        if (q.enabled) {
            IosSection("Schedule") {
                IosRow("From", value = formatMinute(q.startMinute), onClick = { pick(q.startMinute) { m -> set { it.copy(startMinute = m) } } })
                IosRow("To", value = formatMinute(q.endMinute) + if (q.endMinute <= q.startMinute) " (next day)" else "",
                    divider = false, onClick = { pick(q.endMinute) { m -> set { it.copy(endMinute = m) } } })
            }
            IosSection("Repeat", footer = "Days are when quiet hours begin. An overnight window that starts Friday ends Saturday morning.") {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, l ->
                        val day = i + 1
                        val on = day in q.days
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(if (on) ios.indigo else ios.fill)
                                .clickable { set { it.copy(days = if (on) it.days - day else it.days + day) } },
                            contentAlignment = Alignment.Center,
                        ) { Text(l, color = if (on) Color.White else ios.label, fontWeight = FontWeight.SemiBold) }
                    }
                }
                IosDivider()
                Row(Modifier.padding(horizontal = 4.dp)) {
                    IosButton("Every day") { set { it.copy(days = (1..7).toSet()) } }
                    IosButton("School nights") { set { it.copy(days = setOf(7, 1, 2, 3, 4)) } }
                    IosButton("Weekends") { set { it.copy(days = setOf(5, 6)) } }
                }
            }
            IosSection {
                IosToggleRow("Let Priority People Through", c.priorityBypass, divider = false) { v -> app.settings.update { it.copy(priorityBypass = v) } }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ================================================================ Devices

@Composable
fun DevicesScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val c by app.settings.config.collectAsStateWithLifecycle()
    val voices by app.speaker.voices.collectAsStateWithLifecycle()
    var connected by remember { mutableStateOf(ListenerService.deviceState(ctx).deviceName) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        connected = ListenerService.deviceState(ctx).deviceName
        connected?.let { app.settings.noteDevice(it) }
    }
    val list = c.devices.entries.sortedByDescending { it.value.lastSeen }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NavBar("Devices", back = "Settings")
        IosSection(footer = "Headphones, earbuds and car stereos are added here automatically the first time they connect.") {
            if (list.isEmpty()) Text("No devices yet. Connect headphones or your car.", Modifier.padding(16.dp), color = ios.secondary)
            list.forEachIndexed { i, (name, r) ->
                val v = voices.firstOrNull { it.id == r.voiceName }?.displayName
                IosRow(
                    name,
                    subtitle = listOfNotNull(if (name == connected) "Connected" else null, v?.let { "Voice: $it" }).joinToString(" · ").ifEmpty { null },
                    icon = if (name.contains("car", true) || name.contains("sync", true)) Icons.Filled.Place else Icons.Filled.Notifications,
                    iconColor = if (name == connected) ios.green else ios.gray,
                    chevron = true, divider = i < list.lastIndex,
                    onClick = { nav.push(Route.DeviceDetail(name)) },
                ) { IosSwitch(r.enabled) { e -> app.settings.setDeviceRule(name, r.copy(enabled = e)) } }
            }
        }
        IosSection(footer = "Off means a new device stays silent until you switch it on here.") {
            IosToggleRow("Read on New Devices", c.newDevicesEnabled, divider = false) { v -> app.settings.update { it.copy(newDevicesEnabled = v) } }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun DeviceDetailScreen(name: String) {
    val nav = LocalNav.current
    val c by app.settings.config.collectAsStateWithLifecycle()
    val voices by app.speaker.voices.collectAsStateWithLifecycle()
    val r = c.devices[name] ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NavBar(name, back = "Devices")
        IosSection {
            IosToggleRow("Read Notifications", r.enabled, icon = Icons.Filled.Notifications, iconColor = ios.red) { e ->
                app.settings.setDeviceRule(name, r.copy(enabled = e))
            }
            IosRow("Voice", icon = Icons.Filled.Face, iconColor = ios.purple,
                value = voices.firstOrNull { it.id == r.voiceName }?.displayName ?: "Main voice", chevron = true, divider = false,
                onClick = { nav.push(Route.VoicePicker(null, name, device = name)) })
        }
        Text("A device voice is used whenever this device is connected, unless the app has its own voice.",
            Modifier.padding(horizontal = 32.dp), style = IosType.footnote, color = ios.secondary)
        IosSection {
            IosRow("Forget This Device", titleColor = ios.red, divider = false, onClick = {
                app.settings.update { it.copy(devices = it.devices - name) }
                nav.pop()
            })
        }
    }
}

// ================================================================ Priority people

@Composable
fun PriorityScreen() {
    val ctx = LocalContext.current
    val c by app.settings.config.collectAsStateWithLifecycle()
    var entry by remember { mutableStateOf("") }
    fun add(name: String) {
        val n = name.trim()
        if (n.isNotEmpty() && c.priorityPeople.none { it.equals(n, true) })
            app.settings.update { it.copy(priorityPeople = it.priorityPeople + n) }
    }
    // The system picker grants one-off access to the chosen contact, so no contacts permission is needed.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) add(cur.getString(0))
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NavBar("Priority People", back = "Settings")
        IosSection(footer = "Matched against the sender's name in chats, texts and email. Use the name exactly as it shows in notifications.") {
            c.priorityPeople.forEach { p ->
                IosRow(p, leading = {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(ios.red)
                        .clickable { app.settings.update { it.copy(priorityPeople = it.priorityPeople - p) } },
                        contentAlignment = Alignment.Center) { Box(Modifier.size(10.dp, 2.dp).background(Color.White)) }
                }) { Icon(Icons.Filled.Star, null, tint = ios.orange) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(start = 16.dp).size(22.dp).clip(RoundedCornerShape(11.dp)).background(ios.green), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                IosTextField(entry, { entry = it }, "Add a name", Modifier.weight(1f))
                if (entry.isNotBlank()) IosButton("Add", bold = true) { add(entry); entry = "" }
            }
            IosDivider()
            IosRow("Choose from Contacts…", titleColor = ios.tint, divider = false, onClick = { picker.launch(null) })
        }
        IosSection("Always read", footer = "Priority messages ignore app mutes, quiet hours, Do Not Disturb, the screen-on rule and word filters.") {
            IosToggleRow("Override Filters & Quiet Hours", c.priorityBypass) { v -> app.settings.update { it.copy(priorityBypass = v) } }
            IosToggleRow("Use Speaker Without Headphones", c.prioritySpeaker, divider = false) { v -> app.settings.update { it.copy(prioritySpeaker = v) } }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ================================================================ Time announcements

@Composable
fun TimeAnnouncementsScreen() {
    val ctx = LocalContext.current
    val c by app.settings.config.collectAsStateWithLifecycle()
    var exact by remember { mutableStateOf(TimeAnnouncer.canBeExact(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exact = TimeAnnouncer.canBeExact(ctx)
        TimeAnnouncer.schedule(ctx)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NavBar("Time Announcements", back = "Settings")
        IosSection(footer = "Says the time on the clock (e.g. on the hour). Follows your output, device and quiet-hours settings.") {
            TimeAnnouncer.intervals.forEachIndexed { i, m ->
                IosRow(TimeAnnouncer.label(m), divider = i < TimeAnnouncer.intervals.lastIndex,
                    onClick = { app.settings.update { it.copy(timeInterval = m) } }) {
                    if (c.timeInterval == m) Icon(Icons.Filled.Check, null, tint = ios.tint)
                }
            }
        }
        if (c.timeInterval > 0 && !exact && Build.VERSION.SDK_INT >= 31) {
            IosSection(footer = "Without this, Android may announce a few minutes late to save battery.") {
                IosRow("Allow On-Time Announcements", icon = Icons.Filled.Warning, iconColor = ios.orange, chevron = true, divider = false,
                    onClick = {
                        ctx.startActivity(Intent(SysSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                    })
            }
        }
        IosSection {
            IosRow("Say the Time Now", titleColor = ios.tint, divider = false, onClick = {
                app.speaker.preview(TimeAnnouncer.spokenTime(ctx), c.voiceName)
            })
        }
        Spacer(Modifier.height(24.dp))
    }
}
