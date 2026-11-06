package dev.bws.announcer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val n = Parsed(
        pkg = "com.chat", key = "k", appLabel = "Chat", title = "Mom", body = "Dinner at 6?",
        fromSelf = false, isGroupSummary = false, isOngoing = false, isSilent = false,
        passesDnd = true, isMediaOrProgress = false,
    )
    private val headphones = DeviceState(screenOn = false, inCall = false, headphones = true)
    private val c = Config()
    private val never: (String) -> Boolean = { false }

    private fun speak(d: Decision) = (d as Decision.Speak).text
    private fun reason(d: Decision) = (d as Decision.Mute).reason

    @Test fun readsAppTitleAndBody() =
        assertEquals("Chat. Mom. Dinner at 6?", speak(Rules.decide(n, headphones, c, never)))

    @Test fun headphonesOnlyMutesOnSpeaker() =
        assertTrue(reason(Rules.decide(n, headphones.copy(headphones = false), c, never)).contains("headphones"))

    @Test fun alwaysModeUsesSpeaker() =
        assertTrue(Rules.decide(n, headphones.copy(headphones = false), c.copy(outputMode = OutputMode.ALWAYS), never) is Decision.Speak)

    @Test fun ownRepliesMuted() =
        assertEquals("Your own message", reason(Rules.decide(n.copy(fromSelf = true), headphones, c, never)))

    @Test fun blockWordIsCaseInsensitive() =
        assertTrue(Rules.decide(n, headphones, c.copy(blockWords = listOf("DINNER")), never) is Decision.Mute)

    @Test fun allowListRequiresMatch() {
        assertTrue(Rules.decide(n, headphones, c.copy(allowWords = listOf("urgent")), never) is Decision.Mute)
        assertTrue(Rules.decide(n, headphones, c.copy(allowWords = listOf("mom")), never) is Decision.Speak)
    }

    @Test fun privacyModeHidesContent() =
        assertEquals("Notification from Chat", speak(Rules.decide(n, headphones, c.copy(privacy = PrivacyMode.ALWAYS), never)))

    @Test fun customBlankNameSkipsAppName() {
        val cfg = c.copy(apps = mapOf("com.chat" to AppRule(spokenName = "")))
        assertEquals("Mom. Dinner at 6?", speak(Rules.decide(n, headphones, cfg, never)))
    }

    @Test fun newAppsDefaultOff() =
        assertEquals("App is muted", reason(Rules.decide(n, headphones, c.copy(newAppsEnabled = false), never)))

    @Test fun duplicateMuted() =
        assertTrue(reason(Rules.decide(n, headphones, c) { true }).startsWith("Duplicate"))

    @Test fun textShaping() {
        assertEquals("look link to youtube.com", Text.shape("look 😀 https://www.youtube.com/watch?v=x", 0, true, true))
        assertEquals("a b… and more", Text.shape("a b c d", 2, true, true))
        assertEquals("snake case", Text.shape("snake_case", 0, true, true))
    }

    @Test fun deduperWindow() {
        var t = 0L
        val d = Deduper({ 1000L }, { t })
        assertFalse(d.checkAndRecord("p", "Hello!"))
        t = 500; assertTrue(d.checkAndRecord("p", "hello"))
        t = 5000; assertFalse(d.checkAndRecord("p", "hello"))
        assertFalse(d.checkAndRecord("other", "hello"))
    }

    // ---- v1.2 features

    @Test fun quietHoursOvernight() {
        val q = QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60, days = setOf(5)) // Friday night
        assertTrue(q.isActive(23 * 60, 5))   // Fri 11pm
        assertTrue(q.isActive(6 * 60, 6))    // Sat 6am (started Fri)
        assertFalse(q.isActive(8 * 60, 6))   // Sat 8am
        assertFalse(q.isActive(23 * 60, 4))  // Thu 11pm not selected
        assertFalse(q.isActive(6 * 60, 5))   // Fri 6am belongs to Thursday's window
    }

    @Test fun quietHoursMutesButPriorityGetsThrough() {
        val cfg = c.copy(quiet = QuietHours(enabled = true, startMinute = 0, endMinute = 24 * 60 - 1), priorityPeople = listOf("Mom"))
        assertEquals("Quiet hours", reason(Rules.decide(n.copy(title = "Boss"), headphones, cfg, never)))
        assertTrue(Rules.decide(n, headphones, cfg, never) is Decision.Speak)
    }

    @Test fun priorityMatchesWholeWordsOnly() {
        val cfg = c.copy(priorityPeople = listOf("Al"))
        assertTrue(Rules.isPriority(n.copy(title = "Al"), cfg))
        assertTrue(Rules.isPriority(n.copy(title = "Al in Family"), cfg))
        assertFalse(Rules.isPriority(n.copy(title = "Alex"), cfg))
    }

    @Test fun priorityOverridesMutedAppAndCanUseSpeaker() {
        val cfg = c.copy(apps = mapOf("com.chat" to AppRule(enabled = false)), priorityPeople = listOf("mom"), prioritySpeaker = true)
        assertTrue(Rules.decide(n, headphones.copy(headphones = false), cfg, never) is Decision.Speak)
    }

    @Test fun deviceRuleMutesAndSetsVoice() {
        val car = headphones.copy(deviceName = "Car")
        assertEquals("Turned off for Car",
            reason(Rules.decide(n, car, c.copy(devices = mapOf("Car" to DeviceRule(enabled = false))), never)))
        val d = Rules.decide(n, car, c.copy(devices = mapOf("Car" to DeviceRule(voiceName = "v-car"))), never) as Decision.Speak
        assertEquals("v-car", d.voice)
        val app = Rules.decide(n, car, c.copy(devices = mapOf("Car" to DeviceRule(voiceName = "v-car")),
            apps = mapOf("com.chat" to AppRule(voiceName = "v-app"))), never) as Decision.Speak
        assertEquals("v-app", app.voice) // app voice wins over device voice
    }

    @Test fun combinedSummary() {
        val d = Rules.decide(n.copy(sender = "Mom"), headphones, c, never) as Decision.Speak
        assertEquals("Chat. Mom. Dinner at 6?", Rules.combined(d, 1, false))
        assertEquals("Chat. 3 new messages from Mom. Latest: Dinner at 6?", Rules.combined(d, 3, false))
        assertEquals("Chat. 3 new notifications", Rules.combined(d, 3, true))
    }

    @Test fun callerText() {
        assertEquals("Call from Mom", Rules.callerText("Mom"))
        assertEquals("Call from 5 5 5 1 2 3 4", Rules.callerText("(555) 123-4"))
    }

    @Test fun nextTimeBoundary() {
        val t = java.time.LocalDateTime.of(2026, 11, 2, 14, 7)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 2, 14, 15), TimeAnnouncer.nextBoundary(t, 15))
        assertEquals(java.time.LocalDateTime.of(2026, 11, 2, 15, 0), TimeAnnouncer.nextBoundary(t, 60))
        assertEquals(java.time.LocalDateTime.of(2026, 11, 2, 15, 0), TimeAnnouncer.nextBoundary(t, 180))
        assertEquals(java.time.LocalDateTime.of(2026, 11, 3, 0, 0), TimeAnnouncer.nextBoundary(t.withHour(23).withMinute(45), 30))
    }

    // ---- v1.3: smart privacy, sensitive messages

    @Test fun guessesPrivateDevicesByName() {
        listOf("Soundcore Liberty 5 Pro", "AirPods Pro", "Galaxy Buds3 Pro", "Pixel Buds Pro", "WF-1000XM5",
            "Bose QC Ultra Earbuds", "Soundcore Life Q30", "Mystery Device").forEach {
            assertTrue("$it should be private", Privacy.guessPrivate(it))
        }
        listOf("Soundcore Motion 300", "JBL Flip 6", "Bose SoundLink Flex", "Toyota Camry", "My Car", "SYNC", "Uconnect").forEach {
            assertFalse("$it should be shared", Privacy.guessPrivate(it))
        }
    }

    private val earbuds = headphones.copy(deviceName = "Soundcore Liberty 5 Pro")
    private val car = headphones.copy(deviceName = "Toyota Camry")
    private val speaker = headphones.copy(headphones = false)
    private val loud = c.copy(outputMode = OutputMode.ALWAYS)

    @Test fun sharedOnlyPrivacyFollowsDevice() {
        assertEquals("Chat. Mom. Dinner at 6?", speak(Rules.decide(n, earbuds, c, never)))
        assertEquals("Notification from Chat", speak(Rules.decide(n, car, c, never)))
        assertEquals("Notification from Chat", speak(Rules.decide(n, speaker, loud, never)))
        assertEquals("Chat. Mom. Dinner at 6?", speak(Rules.decide(n, speaker, loud.copy(privacy = PrivacyMode.OFF), never)))
        assertEquals("Notification from Chat", speak(Rules.decide(n, earbuds, c.copy(privacy = PrivacyMode.ALWAYS), never)))
    }

    @Test fun deviceOverrideBeatsGuess() {
        val trusted = c.copy(devices = mapOf("Toyota Camry" to DeviceRule(private = true)))
        assertEquals("Chat. Mom. Dinner at 6?", speak(Rules.decide(n, car, trusted, never)))
    }

    @Test fun sensitiveMessagesOnlyInFullOnPrivateDevices() {
        val otp = n.copy(appLabel = "Chase", title = "Chase", body = "Your verification code is 482913")
        val off = c.copy(privacy = PrivacyMode.OFF)
        assertEquals("Verification code from Chase", speak(Rules.decide(otp, car, off, never)))
        assertTrue(speak(Rules.decide(otp, earbuds, off, never)).contains("482913"))
        assertTrue(speak(Rules.decide(otp, car, off.copy(protectSensitive = false), never)).contains("482913"))
        val bank = n.copy(appLabel = "Chase", title = "Deposit", body = "$1,250.00 was deposited to account ending 1234")
        assertEquals("Banking alert from Chase", speak(Rules.decide(bank, car, off, never)))
        val chat = n.copy(body = "See you at 6, bring the code book")
        assertTrue(Privacy.sensitiveKind(chat.body) == null)
    }

    @Test fun androidRedactedCodesAreNamedNotRead() {
        val hidden = n.copy(appLabel = "Chase", title = "Chase", body = "Sensitive notification content hidden")
        assertEquals("Verification code from Chase", speak(Rules.decide(hidden, earbuds, c, never)))
    }

    @Test fun legacyPrivacyFlagMigrates() {
        val old = org.json.JSONObject().put("privacyMode", true)
        assertEquals(PrivacyMode.ALWAYS, Settings.fromJson(old).privacy)
        assertEquals(PrivacyMode.SHARED_ONLY, Settings.fromJson(org.json.JSONObject()).privacy)
    }

    @Test fun everyToneHasAResource() {
        Chime.entries.filter { it != Chime.OFF }.forEach { assertTrue("$it", java.io.File("src/main/res/raw/chime_${it.name.lowercase()}.wav").length() > 1000) }
    }
}
