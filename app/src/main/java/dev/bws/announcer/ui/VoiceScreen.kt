package dev.bws.announcer.ui

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.bws.announcer.core.Gender
import dev.bws.announcer.core.VoiceCatalog
import dev.bws.announcer.core.VoiceInfo
import java.util.Locale
import kotlin.math.abs

private const val SAMPLE = "Messages. Alex: Running ten minutes late, save me a seat."

/** Voice tab: global voice, speaking style, fine-tuning. */
@Composable
fun VoiceScreen() {
    val c by app.settings.config.collectAsStateWithLifecycle()
    val voices by app.speaker.voices.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { app.speaker.refreshVoices() }
    val current = voices.firstOrNull { it.id == c.voiceName }

    LazyColumn(Modifier.fillMaxSize()) {
        item { LargeTitle("Voice", "Choose who reads your notifications") }
        item {
            IosSection {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(current?.gender ?: Gender.UNKNOWN, 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(current?.displayName ?: "System default", style = IosType.title2)
                        Text(current?.let(::voiceSubtitle) ?: "Your phone's text-to-speech default",
                            style = IosType.footnote, color = ios.secondary)
                    }
                    PlayButton { app.speaker.preview(SAMPLE, c.voiceName) }
                }
                IosDivider()
                IosRow("Change Voice", value = "${voices.count { it.installed }} available", chevron = true, divider = false,
                    onClick = { nav.push(Route.VoicePicker(null, null)) })
            }
        }
        item {
            IosSection("Style", footer = "Styles change speed and pitch on top of any voice. Tap one to hear it.") {
                VoiceCatalog.styles.forEachIndexed { i, s ->
                    val on = abs(c.speechRate - s.rate) < 0.01f && abs(c.pitch - s.pitch) < 0.01f
                    IosRow(s.name, subtitle = s.blurb, divider = i < VoiceCatalog.styles.lastIndex, onClick = {
                        app.settings.update { it.copy(speechRate = s.rate, pitch = s.pitch) }
                        app.speaker.preview(SAMPLE, c.voiceName, s.rate, s.pitch)
                    }) { if (on) Icon(Icons.Filled.Check, null, tint = ios.tint) }
                }
            }
        }
        item {
            IosSection("Fine-tune") {
                IosSlider("Speed", c.speechRate, 0.5f..2f, 14, "%.1f×".format(c.speechRate)) { v -> app.settings.update { it.copy(speechRate = v) } }
                IosSlider("Pitch", c.pitch, 0.5f..2f, 14, "%.1f".format(c.pitch), divider = false) { v -> app.settings.update { it.copy(pitch = v) } }
            }
        }
        item {
            IosSection(footer = "Lowers music and podcasts while a notification is read, then restores them.") {
                IosToggleRow("Lower Other Audio", c.duckOthers, icon = Icons.Filled.Notifications, iconColor = ios.pink, divider = false) { v ->
                    app.settings.update { it.copy(duckOthers = v) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Voice list. pkg == null edits the global voice; otherwise that app's override. */
@Composable
fun VoicePickerScreen(pkg: String?, label: String?, device: String? = null) {
    val ctx = LocalContext.current
    val c by app.settings.config.collectAsStateWithLifecycle()
    val voices by app.speaker.voices.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var allLanguages by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { app.speaker.refreshVoices() }

    val selected = when {
        device != null -> c.devices[device]?.voiceName
        pkg != null -> c.ruleFor(pkg).voiceName
        else -> c.voiceName
    }
    fun choose(id: String?) {
        when {
            device != null -> c.devices[device]?.let { app.settings.setDeviceRule(device, it.copy(voiceName = id)) }
            pkg != null -> app.settings.setAppRule(pkg, c.ruleFor(pkg).copy(voiceName = id))
            else -> app.settings.update { it.copy(voiceName = id) }
        }
    }
    val global = pkg == null && device == null

    val lang = Locale.getDefault().language
    // Prefer the offline copy of each voice; show online-only voices separately marked.
    val usable = voices.filter { it.installed }
        .groupBy { it.accent + "|" + it.displayName }
        .map { (_, vs) -> vs.firstOrNull { it.offline } ?: vs.first() }
        .filter { allLanguages || it.language == lang }
        .filter { when (filter) { 1 -> it.gender == Gender.FEMALE; 2 -> it.gender == Gender.MALE; else -> true } }
    val groups = usable.groupBy { it.accent }.toSortedMap(compareBy<String>({ !it.contains(Locale.getDefault().getDisplayCountry()) }, { it }))

    Column(Modifier.fillMaxSize()) {
        NavBar(if (global) "Voice" else "Voice for $label")
        IosSegmented(listOf("All", "Female", "Male"), filter, { filter = it },
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                IosSection {
                    IosRow(if (global) "System Default" else "Same as Main Voice", divider = false,
                        onClick = { choose(null); app.speaker.preview(SAMPLE, if (global) null else c.voiceName) }) {
                        if (selected == null) Icon(Icons.Filled.Check, null, tint = ios.tint)
                    }
                }
            }
            if (voices.isEmpty()) item {
                Text("Loading voices…", Modifier.padding(20.dp), color = ios.secondary)
            }
            groups.forEach { (accent, list) ->
                item(key = accent) {
                    IosSection(accent) {
                        list.forEachIndexed { i, v ->
                            VoiceRow(v, v.id == selected, divider = i < list.lastIndex,
                                onPreview = { app.speaker.preview(SAMPLE, v.id) },
                                onPick = { choose(v.id); app.speaker.preview(SAMPLE, v.id) })
                        }
                    }
                }
            }
            item {
                IosSection(footer = "Google's voice pack has more voices per language. Downloaded voices appear here automatically.") {
                    IosToggleRow("Show All Languages", allLanguages) { allLanguages = it }
                    IosRow("Download More Voices", titleColor = ios.tint, divider = false, onClick = {
                        runCatching {
                            ctx.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    })
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun VoiceRow(v: VoiceInfo, selected: Boolean, divider: Boolean, onPreview: () -> Unit, onPick: () -> Unit) {
    IosRow(
        v.displayName,
        subtitle = voiceSubtitle(v),
        leading = { Avatar(v.gender, 36.dp) },
        divider = divider,
        onClick = onPick,
    ) {
        if (selected) Icon(Icons.Filled.Check, null, tint = ios.tint, modifier = Modifier.padding(end = 8.dp))
        PlayButton(onPreview)
    }
}

private fun voiceSubtitle(v: VoiceInfo) = buildList {
    when (v.gender) { Gender.FEMALE -> add("Female"); Gender.MALE -> add("Male"); else -> {} }
    add(if (v.offline) "On-device" else "Online")
    if (v.highQuality) add("Enhanced")
}.joinToString(" · ")

@Composable
fun Avatar(g: Gender, size: androidx.compose.ui.unit.Dp) {
    val color = when (g) { Gender.FEMALE -> ios.pink; Gender.MALE -> ios.tint; Gender.UNKNOWN -> ios.gray }
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Person, null, tint = color, modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
fun PlayButton(onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(ios.fill).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.PlayArrow, "Preview", tint = ios.tint, modifier = Modifier.size(20.dp))
    }
}
