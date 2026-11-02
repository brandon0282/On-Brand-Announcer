package dev.bws.announcer.core

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

enum class Gender { FEMALE, MALE, UNKNOWN }

data class VoiceInfo(
    val id: String,          // engine voice name, e.g. en-us-x-iom-local
    val displayName: String, // friendly, e.g. "Marcus"
    val gender: Gender,
    val accent: String,      // e.g. "American English"
    val language: String,    // ISO language, e.g. "en"
    val offline: Boolean,
    val installed: Boolean,
    val highQuality: Boolean,
)

/** A speaking style = speed + pitch on top of any voice. */
data class VoiceStyle(val name: String, val blurb: String, val rate: Float, val pitch: Float)

object VoiceCatalog {
    val styles = listOf(
        VoiceStyle("Natural", "As recorded", 1.0f, 1.0f),
        VoiceStyle("Brisk", "Quick, to the point", 1.3f, 1.0f),
        VoiceStyle("Calm", "Slower and softer", 0.85f, 0.95f),
        VoiceStyle("Deep", "Lower, radio-style", 0.95f, 0.8f),
        VoiceStyle("Bright", "Higher and lively", 1.05f, 1.2f),
        VoiceStyle("Robot", "Flat and very low", 0.9f, 0.5f),
    )

    /**
     * Android's TTS API doesn't expose gender, so this maps Google Speech Services voice codes
     * (the "x-iom" part of "en-us-x-iom-local") to genders. Unlisted voices show without one.
     */
    private val googleGender = mapOf(
        // en-US
        "iob" to Gender.FEMALE, "iog" to Gender.FEMALE, "iol" to Gender.MALE, "iom" to Gender.MALE,
        "sfg" to Gender.FEMALE, "tpc" to Gender.FEMALE, "tpd" to Gender.MALE, "tpf" to Gender.FEMALE,
        // en-GB
        "gba" to Gender.FEMALE, "gbb" to Gender.MALE, "gbc" to Gender.FEMALE, "gbd" to Gender.MALE,
        "gbg" to Gender.FEMALE, "rjs" to Gender.MALE, "fis" to Gender.FEMALE,
        // en-AU
        "afh" to Gender.FEMALE, "aua" to Gender.FEMALE, "aub" to Gender.MALE, "auc" to Gender.FEMALE, "aud" to Gender.MALE,
        // en-IN
        "ahp" to Gender.FEMALE, "cxx" to Gender.FEMALE, "ene" to Gender.FEMALE, "enc" to Gender.FEMALE, "end" to Gender.MALE,
    )

    private val femaleNames = listOf(
        "Ava", "Nora", "Grace", "Isla", "Maya", "Clara", "Ruby", "Zoe", "Hazel", "Iris", "Lena", "Mia", "Stella", "Violet",
        "Elena", "Josie", "Naomi", "Freya", "Lucy", "Paige", "Tessa", "Willa", "Alice", "Daisy", "Esme", "Ivy", "Julia", "Kate",
        "Lila", "Margot", "Olive", "Rosa", "Sadie", "Vera", "Wren", "Amara", "Bianca", "Celia", "Delia", "Eden",
    )
    private val maleNames = listOf(
        "Marcus", "Theo", "Owen", "Felix", "Jasper", "Leo", "Miles", "Hugo", "Silas", "Ezra", "Arlo", "Reid", "Calvin", "Dexter",
        "Elliot", "Gideon", "Harvey", "Isaac", "Jonah", "Kai", "Louis", "Nolan", "Oscar", "Porter", "Rafael", "Simon", "Tobias",
        "Victor", "Wesley", "Xavier", "Adrian", "Bennett", "Caleb", "Damon", "Emmett", "Flynn", "Graham", "Henry", "Ian", "Jude",
    )
    private val neutralNames = listOf(
        "Sky", "River", "Quinn", "Rowan", "Sage", "Remy", "Jordan", "Avery", "Blake", "Casey", "Drew", "Emery", "Finley", "Harper",
        "Indigo", "Jules", "Kendall", "Lane", "Morgan", "Noel", "Oakley", "Parker", "Reese", "Shiloh", "Taylor", "Wynn",
    )

    fun describe(voices: Set<Voice>): List<VoiceInfo> {
        // The user's own locale gets first pick of names, so its voices are never numbered.
        val mine = Locale.getDefault()
        val sorted = voices
            .filter { it.locale != null && it.name.isNotBlank() }
            .sortedWith(compareBy(
                { if (it.locale.language == mine.language && it.locale.country == mine.country) 0 else if (it.locale.language == mine.language) 1 else 2 },
                { it.locale.toLanguageTag() }, { it.name },
            ))
        val counters = IntArray(3)
        val byCode = HashMap<String, String>() // same speaker code (local/network) keeps one name
        return sorted.map { v ->
            val code = Regex("""-x-([a-z]{3})""").find(v.name)?.groupValues?.get(1)
            val g = code?.let { googleGender[it] } ?: Gender.UNKNOWN
            val nameKey = "${v.locale.toLanguageTag()}|${code ?: v.name}"
            val friendly = byCode.getOrPut(nameKey) {
                val (list, i) = when (g) { Gender.FEMALE -> femaleNames to 0; Gender.MALE -> maleNames to 1; Gender.UNKNOWN -> neutralNames to 2 }
                val n = counters[i]++
                list[n % list.size] + if (n >= list.size) " ${n / list.size + 1}" else ""
            }
            val features = v.features.orEmpty()
            VoiceInfo(
                id = v.name,
                displayName = friendly,
                gender = g,
                accent = accentName(v.locale),
                language = v.locale.language,
                offline = !v.isNetworkConnectionRequired,
                installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features,
                highQuality = v.quality >= Voice.QUALITY_HIGH,
            )
        }
    }

    fun accentName(l: Locale): String {
        val lang = l.getDisplayLanguage(Locale.getDefault())
        return when {
            l.language == "en" && l.country == "US" -> "American English"
            l.language == "en" && l.country == "GB" -> "British English"
            l.language == "en" && l.country == "AU" -> "Australian English"
            l.language == "en" && l.country == "IN" -> "Indian English"
            l.language == "en" && l.country == "NG" -> "Nigerian English"
            l.country.isNotEmpty() -> "$lang (${l.getDisplayCountry(Locale.getDefault())})"
            else -> lang
        }
    }
}
