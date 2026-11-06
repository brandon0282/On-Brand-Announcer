package dev.bws.announcer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private class Topic(val title: String, val icon: ImageVector, val color: @Composable () -> Color, val body: List<String>)

/** "•" lines render as bullets; everything else as a paragraph. */
private val chapters: List<Pair<String, List<Topic>>> = listOf(
    "Getting started" to listOf(
        Topic("Turning it on", Icons.Filled.CheckCircle, { ios.green }, listOf(
            "Announcer reads your notifications aloud. It needs Notification Access to see them: open Summary and tap Grant Access, then switch Announcer on in the Android screen that opens.",
            "Use the switch on the Summary card, the Quick Settings tile, or the home-screen widget to pause and resume at any time.",
            "If you use another notification-reading app, turn it off. Otherwise both apps will read every notification.",
        )),
        Topic("Headphones or speaker", Icons.Filled.Notifications, { ios.tint }, listOf(
            "By default Announcer only talks when headphones, earbuds or a car stereo are connected, so it never reads your messages out loud in a room by surprise.",
            "To also use the phone speaker: Settings → Output → Headphones & speaker.",
        )),
        Topic("Privacy", Icons.Filled.Lock, { ios.gray }, listOf(
            "Announcer has no internet permission, so it physically cannot send anything off your phone. History and statistics are stored only on this device.",
            "It doesn't ask for contacts, location or phone permissions. Caller ID uses your phone app's own call notification, and Priority People uses the system contact picker, which shares only the contact you choose.",
            "Settings → Privacy decides when message text is spoken. Shared audio only (the default): full messages in your earbuds or headphones, but only \"Notification from WhatsApp\" on the phone speaker, in the car or on a Bluetooth speaker. Always: app name only, everywhere. Off: full messages everywhere.",
            "Protect Sensitive Messages: verification codes, banking alerts and password or sign-in alerts are read in full only on private devices. Elsewhere you hear \"Verification code from Chase\".",
            "Note: since Android 15, Android itself hides one-time codes from apps like Announcer, so you'll hear that a code arrived but not the digits.",
        )),
    ),
    "The tabs" to listOf(
        Topic("Summary", Icons.Filled.Home, { ios.tint }, listOf(
            "Your dashboard. The top card shows whether Announcer is listening, paused, in quiet hours or waiting for headphones, and which device and voice are active.",
            "• Tiles: notifications read and skipped today, words spoken, and this week's total.",
            "• Last 7 days and Today by hour: tap any bar to see its exact number.",
            "• Top apps and Why things were skipped: tells you what to change if something important was missed.",
        )),
        Topic("Apps", Icons.Filled.Notifications, { ios.red }, listOf(
            "Every app that has notified you, newest first. Use the switch to mute or unmute an app.",
            "Tap an app to:",
            "• Announce as: its real name, a custom name (e.g. \"Work\" for Outlook), or Silent to skip the name and read only the message.",
            "• Voice: give the app its own voice, for example a calm voice for email and a brisk one for chats.",
            "• Max words: shorten or lengthen what's read for that app.",
        )),
        Topic("Voice", Icons.Filled.Face, { ios.purple }, listOf(
            "Pick the main voice and a speaking style.",
            "• Change Voice: browse every voice installed on your phone, filter by Female or Male, and tap ▶ to hear one before choosing it. Download More Voices adds extra voices and languages from Google.",
            "• Style: Natural, Brisk, Calm, Deep, Bright or Robot. Styles change speed and pitch and work with any voice. Fine-tune sets exact values.",
            "• Lower Other Audio: music and podcasts dip while a notification is read.",
            "Which voice is used: the app's own voice first, then the connected device's voice, then the main voice.",
        )),
        Topic("Attention tone", Icons.Filled.Notifications, { ios.orange }, listOf(
            "Voice → Attention tone. A short, soft tone plays right before an announcement so the voice doesn't startle you. Choose Soft Bell, Two Tone, Rising Chime, Droplet, Marimba, Glass, or None. Tap one to hear it.",
            "A burst of notifications gets one tone, not one each. Tone volume sets how loud it is compared with the voice.",
        )),
        Topic("Activity", Icons.Filled.DateRange, { ios.teal }, listOf(
            "A log of the last 200 notifications: exactly what was said, or why each one was skipped. Tap an entry to expand long messages. Filter to Read or Skipped with the control at the top.",
        )),
    ),
    "Smart features" to listOf(
        Topic("Quiet Hours", Icons.Filled.Lock, { ios.indigo }, listOf(
            "Settings → Quiet Hours. Choose a start and end time and the days. Overnight windows work: 10 PM–7 AM on Friday covers Friday night into Saturday morning.",
            "Nothing is read during quiet hours, except Priority People if Let Priority People Through is on.",
        )),
        Topic("Priority People", Icons.Filled.Star, { ios.orange }, listOf(
            "Settings → Priority People. Add names by typing them or with Choose from Contacts.",
            "A notification is a priority if the sender's name matches, for example \"Mom\" matches \"Mom\" and \"Mom in Family Chat\" but not \"Momo\".",
            "• Override Filters & Quiet Hours: priority messages are read even if the app is muted, during quiet hours or Do Not Disturb, while the screen is on, and regardless of block/allow words.",
            "• Use Speaker Without Headphones: priority messages are read out loud even when nothing is connected.",
        )),
        Topic("Devices", Icons.Filled.Place, { ios.teal }, listOf(
            "Settings → Devices. Each headphone, earbud or car stereo is remembered by name the first time it connects.",
            "• Switch a device off to keep Announcer quiet on it, e.g. work earbuds.",
            "• Tap a device to give it its own voice, e.g. a deeper voice in the car.",
            "• Only I Can Hear This: private devices hear full messages, shared ones only the app name. Announcer guesses from the name: earbuds and headphones (e.g. Soundcore Liberty, AirPods, Galaxy Buds) are private; cars and speakers are shared. Tap a device to change it.",
            "• Turn off Read on New Devices if you want new devices to stay silent until you enable them.",
        )),
        Topic("Combining bursts", Icons.Filled.Email, { ios.tint }, listOf(
            "When several messages from the same person arrive within a few seconds, they're combined into one announcement: \"WhatsApp. 3 new messages from Mom. Latest: …\".",
            "Set the window in Settings → Timing → Combine bursts within (0 turns it off). Combined messages still count as read on the dashboard.",
        )),
        Topic("Caller ID", Icons.Filled.Call, { ios.green }, listOf(
            "Settings → Caller ID → Announce Callers. When your phone rings, Announcer says \"Call from …\" using the name your phone app shows. Unknown numbers are read digit by digit.",
            "• Repeat While Ringing: repeats every 7 seconds until you answer or decline.",
            "• Use Speaker Without Headphones: announce even when nothing is connected.",
        )),
        Topic("Time Announcements", Icons.Filled.DateRange, { ios.purple }, listOf(
            "Settings → Time Announcements. Hear the time every 15 or 30 minutes, every hour, or every 2 or 3 hours, on the clock (e.g. 3:00, 3:15).",
            "They follow your output, device and quiet-hours settings. If Android asks, tap Allow On-Time Announcements; without it the time may be spoken a few minutes late to save battery.",
        )),
        Topic("Widget & Quick Settings", Icons.Filled.Settings, { ios.gray }, listOf(
            "Long-press your home screen → Widgets → Announcer. The widget shows today's count and has an On/Paused button. Tap the rest of the widget to open the app.",
            "Pull down Quick Settings, tap the pencil, and drag the Announcer tile in for a one-tap on/off.",
        )),
    ),
    "Other settings" to listOf(
        Topic("When to speak", Icons.Filled.Info, { ios.tint }, listOf(
            "• While Screen Is On: off means Announcer stays quiet while you're looking at your phone.",
            "• During Calls: off by default so it never talks over a call.",
            "• Respect Do Not Disturb: follows Android's DND rules.",
            "• Skip Silent Notifications: ignores low-importance notifications that make no sound.",
            "• Read Newly Seen Apps: turn off to make every new app silent until you enable it in Apps.",
        )),
        Topic("Timing", Icons.Filled.Refresh, { ios.orange }, listOf(
            "• Delay: waits a few seconds so the voice doesn't talk over the notification sound. If you dismiss the notification during the delay, it isn't read.",
            "• Ignore repeats within: the same text from the same app is read only once in this window.",
        )),
        Topic("What to say", Icons.Filled.Create, { ios.pink }, listOf(
            "• Say App Name, Privacy Mode, Skip Emojis, and Shorten Links (\"link to youtube.com\").",
            "• Max words: long messages are cut off with \"… and more\".",
            "• Block & Allow Words: block words mute any notification containing them. If you add allow words, only notifications containing one of them are read.",
        )),
        Topic("Updates", Icons.Filled.Refresh, { ios.green }, listOf(
            "Settings → Updates → Check for Updates shows your version and opens the latest release on GitHub in your browser. If the version there is newer, download the .apk and install it over the current app; your settings, history and voices are kept.",
            "Announcer can't check by itself because it has no internet permission. That's what guarantees your notifications never leave the phone.",
        )),
        Topic("Backup", Icons.Filled.Share, { ios.tint }, listOf(
            "Export Settings saves everything (apps, voices, words, schedules, devices) to a file. Import Settings restores it, e.g. on a new phone.",
        )),
    ),
    "Troubleshooting" to listOf(
        Topic("A notification wasn't read", Icons.Filled.Warning, { ios.orange }, listOf(
            "Open Activity and find it. The orange line says exactly why it was skipped, for example No headphones connected, App is muted, Quiet hours, Screen was on, or Silent notification. Change the matching setting.",
            "If it isn't in Activity at all, check that Notification Access is still on (Settings → System → Notification Access).",
        )),
        Topic("It stopped working after a while", Icons.Filled.Build, { ios.gray }, listOf(
            "Some phones put background apps to sleep. Open Android Settings → Apps → Announcer → Battery and choose Unrestricted.",
            "Then open Notification Access and toggle Announcer off and back on.",
        )),
        Topic("The voice sounds wrong or is silent", Icons.Filled.Face, { ios.purple }, listOf(
            "Try Voice → Change Voice → System Default. If a voice says Online, it needs a data connection on Google's side; choose an On-device voice for reliable offline speech.",
            "Make sure the media volume is up. Announcements use the media/navigation volume, not the ringer.",
        )),
    ),
)

@Composable
fun ManualScreen() {
    LazyColumn(Modifier.fillMaxSize()) {
        item { NavBar("User Guide", back = "Settings") }
        item { LargeTitle("User Guide", "Everything Announcer can do, and how to set it up") }
        chapters.forEach { (chapter, topics) ->
            item(key = chapter) {
                IosSection(chapter) {
                    topics.forEachIndexed { i, t -> TopicRow(t, divider = i < topics.lastIndex) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TopicRow(t: Topic, divider: Boolean) {
    var open by rememberSaveable(t.title) { mutableStateOf(false) }
    val angle by animateFloatAsState(if (open) 90f else 0f, label = "chevron")
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(t.icon, t.color())
            Spacer(Modifier.width(14.dp))
            Text(t.title, Modifier.weight(1f), style = if (open) IosType.headline else IosType.body)
            Icon(Icons.Filled.KeyboardArrowRight, null, tint = ios.gray.copy(alpha = 0.6f), modifier = Modifier.size(22.dp).rotate(angle))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 59.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                t.body.forEach { line ->
                    if (line.startsWith("• ")) {
                        Row {
                            Text("•", Modifier.width(14.dp), style = IosType.subhead, color = ios.secondary)
                            Text(line.removePrefix("• "), style = IosType.subhead, color = ios.label)
                        }
                    } else Text(line, style = IosType.subhead, color = ios.label)
                }
            }
        }
        if (divider) IosDivider(59.dp)
    }
}

