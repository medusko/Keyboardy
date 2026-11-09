package com.medusko.keyboardy.suggest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.min

data class SuggestionItem(
    val text: String,
    val isSpellCorrection: Boolean = false,
    val isDefaultKeyword: Boolean = false
)

object SpellAndSuggestionEngine {

    // Default trending GIF & meme search terms shown when search bar is blank
    val DEFAULT_SUGGESTIONS = listOf(
        SuggestionItem("excited", isDefaultKeyword = true),
        SuggestionItem("dancing", isDefaultKeyword = true),
        SuggestionItem("happy", isDefaultKeyword = true),
        SuggestionItem("crying", isDefaultKeyword = true),
        SuggestionItem("facepalm", isDefaultKeyword = true),
        SuggestionItem("mind blown", isDefaultKeyword = true),
        SuggestionItem("applause", isDefaultKeyword = true),
        SuggestionItem("sponge bob", isDefaultKeyword = true),
        SuggestionItem("laughing", isDefaultKeyword = true),
        SuggestionItem("thumbs up", isDefaultKeyword = true),
        SuggestionItem("popcorn", isDefaultKeyword = true),
        SuggestionItem("cheers", isDefaultKeyword = true)
    )

    // Direct typo corrections for frequent typing mistakes
    private val COMMON_TYPO_MAP = mapOf(
        "teh" to "the",
        "reciept" to "receipt",
        "definately" to "definitely",
        "dancin" to "dancing",
        "danc" to "dance",
        "dacing" to "dancing",
        "runnin" to "running",
        "exicted" to "excited",
        "excite" to "excited",
        "haply" to "happily",
        "hapiness" to "happiness",
        "thier" to "their",
        "tommorow" to "tomorrow",
        "tomorow" to "tomorrow",
        "seperate" to "separate",
        "untill" to "until",
        "occured" to "occurred",
        "wierd" to "weird",
        "beleive" to "believe",
        "freind" to "friend",
        "becuase" to "because",
        "truely" to "truly",
        "suprise" to "surprise",
        "embarass" to "embarrass",
        "patrik" to "patrick",
        "spongebob" to "spongebob",
        "shrek" to "shrek",
        "congrats" to "congratulations",
        "congradulations" to "congratulations",
        "celebrat" to "celebrate",
        "lauging" to "laughing",
        "laghing" to "laughing",
        "cryin" to "crying",
        "crrying" to "crying",
        "suprised" to "surprised",
        "confused" to "confused",
        "confusd" to "confused",
        "facepal" to "facepalm",
        "faceplam" to "facepalm",
        "mindblown" to "mind blown",
        "applaus" to "applause",
        "aplaud" to "applaud",
        "thx" to "thanks",
        "ty" to "thank you",
        "omg" to "oh my god",
        "omfg" to "omg",
        "bruhh" to "bruh",
        "aww" to "aww",
        "yea" to "yeah",
        "nah" to "no"
    )

    // Curated high-frequency GIF/meme and English vocabulary
    private val VOCABULARY = listOf(
        "excited", "dancing", "dance", "happy", "happiness", "sad", "crying", "tears",
        "laughing", "laughter", "lol", "lmao", "rofl", "smile", "smiling", "grin",
        "angry", "rage", "furious", "shocked", "shock", "nervous", "anxious", "sweating",
        "bored", "sleepy", "tired", "yawning", "facepalm", "mind blown", "confused",
        "what", "shrug", "eye roll", "wink", "kiss", "love", "heart", "hug",
        "applause", "clapping", "thumbs up", "thumbs down", "cheers", "celebrate", "party",
        "congratulations", "win", "winning", "victory", "fail", "epic fail", "awkward",
        "cringe", "bye", "hello", "hi", "welcome", "please", "thanks", "thank you", "sorry",
        "agree", "yes", "nod", "no", "disagree", "run", "running", "walk", "walking",
        "jump", "jumping", "fall", "falling", "eat", "eating", "food", "pizza", "coffee",
        "popcorn", "cat", "cats", "kitten", "dog", "dogs", "puppy", "animal", "animals",
        "spongebob", "patrick", "squidward", "shrek", "pikachu", "pokemon", "minions",
        "disney", "marvel", "spiderman", "batman", "anime", "dramatic", "mic drop",
        "waiting", "fire", "explosion", "money", "rich", "poor", "work", "lazy",
        "morning", "night", "goodnight", "ok", "okay", "alright", "great", "awesome",
        "cool", "nice", "relatable", "mood", "vibe", "vibing", "bruh", "wow", "omg",
        "receipt", "definitely", "tomorrow", "separate", "special", "beautiful", "amazing"
    )

    private val VOCABULARY_SET: Set<String> = VOCABULARY.toHashSet()
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Synchronously returns instant suggestions (0ms latency, runs locally on Main thread).
     * Includes typo auto-corrections and prefix completions.
     */
    fun getInstantSuggestions(query: String): List<SuggestionItem> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) {
            return DEFAULT_SUGGESTIONS
        }

        val lastWord = trimmed.split(WHITESPACE_REGEX).lastOrNull()?.filter { it.isLetter() }.orEmpty()
        if (lastWord.isEmpty()) {
            return emptyList()
        }

        val results = mutableListOf<SuggestionItem>()
        val seen = mutableSetOf<String>()

        // 1. Direct typo correction
        COMMON_TYPO_MAP[lastWord]?.let { correction ->
            if (seen.add(correction.lowercase())) {
                results.add(SuggestionItem(text = correction, isSpellCorrection = true))
            }
        }

        // 2. Exact match in vocabulary (if user typed a known word)
        if (VOCABULARY_SET.contains(lastWord) && seen.add(lastWord)) {
            results.add(SuggestionItem(text = lastWord, isSpellCorrection = false))
        }

        // 3. Prefix matches in vocabulary
        for (word in VOCABULARY) {
            if (word.startsWith(lastWord) && word != lastWord) {
                if (seen.add(word)) {
                    results.add(SuggestionItem(text = word, isSpellCorrection = false))
                    if (results.size >= 4) break
                }
            }
        }

        // 4. Fuzzy Levenshtein spell check for words with edit distance 1 or 2
        if (results.isEmpty() || !results.any { it.isSpellCorrection }) {
            var bestCorrection: String? = null
            var bestDistance = Int.MAX_VALUE

            for (candidate in VOCABULARY) {
                if (kotlin.math.abs(candidate.length - lastWord.length) > 2) continue
                val dist = levenshteinDistance(lastWord, candidate)
                if (dist in 1..2 && dist < bestDistance) {
                    bestDistance = dist
                    bestCorrection = candidate
                }
            }

            bestCorrection?.let { correction ->
                if (seen.add(correction)) {
                    results.add(0, SuggestionItem(text = correction, isSpellCorrection = true))
                }
            }
        }

        return results.take(5)
    }

    /**
     * Asynchronously fetches online Google query suggestions and completions (~40ms).
     * Automatically handles misspellings, multi-word phrases, and popular meme queries.
     */
    suspend fun fetchEnhancedSuggestions(query: String): List<SuggestionItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext DEFAULT_SUGGESTIONS

        val instantList = getInstantSuggestions(trimmed)

        val onlineList = withTimeoutOrNull(900L) {
            try {
                val encoded = URLEncoder.encode(trimmed, "UTF-8")
                val url = URL("https://suggestqueries.google.com/complete/search?client=firefox&q=$encoded")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 800
                conn.readTimeout = 800
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                try {
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val jsonArray = JSONArray(body)
                        val suggestionsArray = jsonArray.optJSONArray(1) ?: JSONArray()
                        val items = mutableListOf<String>()
                        for (i in 0 until min(suggestionsArray.length(), 6)) {
                            val text = suggestionsArray.optString(i, "").trim()
                            if (text.isNotEmpty() && !text.equals(trimmed, ignoreCase = true)) {
                                items.add(text)
                            }
                        }
                        items
                    } else {
                        emptyList()
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                emptyList()
            }
        }.orEmpty()

        if (onlineList.isEmpty()) {
            return@withContext instantList
        }

        val combined = mutableListOf<SuggestionItem>()
        val seen = mutableSetOf<String>()

        // Prioritize any local spell correction first
        for (item in instantList) {
            if (item.isSpellCorrection && seen.add(item.text.lowercase())) {
                combined.add(item)
            }
        }

        // Add online suggestions
        for (str in onlineList) {
            val clean = str.removeSuffix(" synonym").removeSuffix(" in spanish").removeSuffix(" meaning").trim()
            if (seen.add(clean.lowercase())) {
                combined.add(SuggestionItem(text = clean, isSpellCorrection = false))
                if (combined.size >= 5) break
            }
        }

        // Fill remaining with local prefix matches
        for (item in instantList) {
            if (seen.add(item.text.lowercase())) {
                combined.add(item)
                if (combined.size >= 5) break
            }
        }

        combined.take(5)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        var prev = IntArray(s2.length + 1) { it }
        var curr = IntArray(s2.length + 1)

        for (i in 1..s1.length) {
            curr[0] = i
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                curr[j] = min(
                    min(prev[j] + 1, curr[j - 1] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[s2.length]
    }
}
