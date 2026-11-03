package com.medusko.keyboardy.prefs

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.time.LocalDate
import java.time.YearMonth

enum class SearchEngineMode(val displayName: String) {
    HYBRID("Hybrid (Google + Instant)"),
    GOOGLE_WEBVIEW("Google WebView"),
    INSTANT_HTTP("Instant Web");

    fun next(): SearchEngineMode {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }
}

enum class KeyboardScale(
    val label: String,
    val keyHeightDp: Int,
    val keyTextSp: Float,
    val gridItemHeightDp: Int,
    val topBarHeightDp: Int
) {
    COMPACT(
        label = "100%",
        keyHeightDp = 40,
        keyTextSp = 15f,
        gridItemHeightDp = 112,
        topBarHeightDp = 48
    ),
    NORMAL(
        label = "115%",
        keyHeightDp = 46,
        keyTextSp = 16.5f,
        gridItemHeightDp = 124,
        topBarHeightDp = 52
    ),
    LARGE(
        label = "130%",
        keyHeightDp = 52,
        keyTextSp = 18.5f,
        gridItemHeightDp = 138,
        topBarHeightDp = 56
    ),
    XLARGE(
        label = "145% XL",
        keyHeightDp = 58,
        keyTextSp = 20f,
        gridItemHeightDp = 152,
        topBarHeightDp = 60
    );
}

class KeyboardyPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("keyboardy_prefs", Context.MODE_PRIVATE)

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    var isDarkMode: Boolean
        get() = prefs.getBoolean(KEY_DARK_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_DARK_MODE, value).apply()

    var keyboardScale: KeyboardScale
        get() {
            val raw = prefs.getString(KEY_KEYBOARD_SCALE, KeyboardScale.NORMAL.name)
            return KeyboardScale.entries.firstOrNull { it.name == raw }
                ?: KeyboardScale.NORMAL
        }
        set(value) = prefs.edit().putString(KEY_KEYBOARD_SCALE, value.name).apply()

    var bottomNavClearanceDp: Int
        get() = prefs.getInt(KEY_BOTTOM_CLEARANCE_DP, 54)
        set(value) = prefs.edit().putInt(KEY_BOTTOM_CLEARANCE_DP, value.coerceIn(0, 160)).apply()

    var autoReturnToPreviousKeyboard: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RETURN, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RETURN, value).apply()

    var autoReadAndReplaceChatQuery: Boolean
        get() = prefs.getBoolean(KEY_AUTO_READ_CHAT_V2, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_READ_CHAT_V2, value).apply()

    var nsfwModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_NSFW_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_NSFW_MODE, value).apply()

    var engineMode: SearchEngineMode
        get() {
            val raw = prefs.getString(KEY_ENGINE_MODE, SearchEngineMode.HYBRID.name)
            return SearchEngineMode.entries.firstOrNull { it.name == raw }
                ?: SearchEngineMode.HYBRID
        }
        set(value) = prefs.edit().putString(KEY_ENGINE_MODE, value.name).apply()

    var optionalApiKey: String
        get() = prefs.getString(KEY_OPTIONAL_API_KEY, "")?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_OPTIONAL_API_KEY, value.trim()).apply()

    // --- Search History (up to 25 recent queries) ---

    fun getSearchHistory(): List<String> {
        val raw = prefs.getString(KEY_SEARCH_HISTORY_JSON, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i).trim()
                if (item.isNotEmpty()) list.add(item)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addSearchHistoryQuery(query: String) {
        val cleaned = query.trim()
        if (cleaned.length < 2) return
        val existing = getSearchHistory().toMutableList()
        existing.removeAll { it.equals(cleaned, ignoreCase = true) }
        existing.add(0, cleaned)
        val trimmed = existing.take(25)
        saveSearchHistory(trimmed)
    }

    fun removeSearchHistoryQuery(query: String) {
        val existing = getSearchHistory().toMutableList()
        existing.removeAll { it.equals(query, ignoreCase = true) }
        saveSearchHistory(existing)
    }

    fun clearSearchHistory() {
        saveSearchHistory(emptyList())
    }

    private fun saveSearchHistory(list: List<String>) {
        val arr = JSONArray()
        for (item in list) arr.put(item)
        prefs.edit().putString(KEY_SEARCH_HISTORY_JSON, arr.toString()).apply()
    }

    // --- Quantified Search & Image Insertion Usage Tracking ---

    private fun ensureQuotaDatesCurrent() {
        val todayStr = LocalDate.now().toString()
        val monthStr = YearMonth.now().toString()
        val savedDay = prefs.getString(KEY_QUOTA_DAY_DATE, "")
        val savedMonth = prefs.getString(KEY_QUOTA_MONTH_DATE, "")

        val editor = prefs.edit()
        var changed = false
        if (savedDay != todayStr) {
            editor.putString(KEY_QUOTA_DAY_DATE, todayStr)
            editor.putInt(KEY_SEARCHES_TODAY, 0)
            editor.putInt(KEY_INSERTS_TODAY, 0)
            changed = true
        }
        if (savedMonth != monthStr) {
            editor.putString(KEY_QUOTA_MONTH_DATE, monthStr)
            editor.putInt(KEY_SEARCHES_MONTH, 0)
            changed = true
        }
        if (changed) editor.apply()
    }

    val searchesToday: Int
        get() {
            ensureQuotaDatesCurrent()
            return prefs.getInt(KEY_SEARCHES_TODAY, 0)
        }

    val searchesThisMonth: Int
        get() {
            ensureQuotaDatesCurrent()
            return prefs.getInt(KEY_SEARCHES_MONTH, 0)
        }

    val imagesInsertedToday: Int
        get() {
            ensureQuotaDatesCurrent()
            return prefs.getInt(KEY_INSERTS_TODAY, 0)
        }

    val imagesInsertedTotal: Int
        get() = prefs.getInt(KEY_INSERTS_TOTAL, 0)

    fun recordSearchExecuted() {
        ensureQuotaDatesCurrent()
        val dayCount = prefs.getInt(KEY_SEARCHES_TODAY, 0) + 1
        val monthCount = prefs.getInt(KEY_SEARCHES_MONTH, 0) + 1
        prefs.edit()
            .putInt(KEY_SEARCHES_TODAY, dayCount)
            .putInt(KEY_SEARCHES_MONTH, monthCount)
            .apply()
    }

    fun recordImageInserted() {
        ensureQuotaDatesCurrent()
        val dayInserts = prefs.getInt(KEY_INSERTS_TODAY, 0) + 1
        val totalInserts = prefs.getInt(KEY_INSERTS_TOTAL, 0) + 1
        prefs.edit()
            .putInt(KEY_INSERTS_TODAY, dayInserts)
            .putInt(KEY_INSERTS_TOTAL, totalInserts)
            .apply()
    }

    companion object {
        private const val KEY_DARK_MODE = "is_dark_mode"
        private const val KEY_KEYBOARD_SCALE = "keyboard_scale"
        private const val KEY_BOTTOM_CLEARANCE_DP = "bottom_clearance_dp"
        private const val KEY_AUTO_RETURN = "auto_return_gboard"
        private const val KEY_AUTO_READ_CHAT_V2 = "auto_read_chat_query_v2"
        private const val KEY_NSFW_MODE = "nsfw_mode_enabled"
        private const val KEY_ENGINE_MODE = "engine_mode"
        private const val KEY_OPTIONAL_API_KEY = "optional_api_key"
        private const val KEY_SEARCH_HISTORY_JSON = "search_history_json"

        private const val KEY_QUOTA_DAY_DATE = "quota_day_date"
        private const val KEY_QUOTA_MONTH_DATE = "quota_month_date"
        private const val KEY_SEARCHES_TODAY = "searches_today"
        private const val KEY_SEARCHES_MONTH = "searches_month"
        private const val KEY_INSERTS_TODAY = "inserts_today"
        private const val KEY_INSERTS_TOTAL = "inserts_total"
    }
}
