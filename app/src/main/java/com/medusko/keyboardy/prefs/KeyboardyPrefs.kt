package com.medusko.keyboardy.prefs

import android.content.Context
import android.content.SharedPreferences
import com.medusko.keyboardy.theme.ThemeMode
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

class KeyboardyPrefs(private val context: Context) {
    private val settingsPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE)
    private val privatePrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME_PRIVATE, Context.MODE_PRIVATE)

    init {
        migrateLegacyPrivateKeysIfNeeded()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        settingsPrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun registerPrivateListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        privatePrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterPrivateListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        privatePrefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun migrateLegacyPrivateKeysIfNeeded() {
        val privateStringKeys = listOf(
            KEY_OPTIONAL_API_KEY,
            KEY_SEARCH_HISTORY_JSON,
            KEY_QUOTA_DAY_DATE,
            KEY_QUOTA_MONTH_DATE
        )
        val privateIntKeys = listOf(
            KEY_SEARCHES_TODAY,
            KEY_SEARCHES_MONTH,
            KEY_INSERTS_TODAY,
            KEY_INSERTS_TOTAL
        )
        val hasAnyLegacyKey =
            privateStringKeys.any { settingsPrefs.contains(it) } ||
                privateIntKeys.any { settingsPrefs.contains(it) }
        if (!hasAnyLegacyKey) return

        val privateEditor = privatePrefs.edit()
        val settingsEditor = settingsPrefs.edit()
        var wrotePrivate = false

        for (key in privateStringKeys) {
            if (settingsPrefs.contains(key)) {
                if (!privatePrefs.contains(key)) {
                    privateEditor.putString(key, settingsPrefs.getString(key, null))
                    wrotePrivate = true
                }
                settingsEditor.remove(key)
            }
        }
        for (key in privateIntKeys) {
            if (settingsPrefs.contains(key)) {
                if (!privatePrefs.contains(key)) {
                    privateEditor.putInt(key, settingsPrefs.getInt(key, 0))
                    wrotePrivate = true
                }
                settingsEditor.remove(key)
            }
        }

        if (wrotePrivate) {
            privateEditor.apply()
        }
        settingsEditor.apply()
    }

    var themeMode: ThemeMode
        get() {
            val raw = settingsPrefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
            return ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
        }
        set(value) = settingsPrefs.edit().putString(KEY_THEME_MODE, value.name).apply()

    var isDarkMode: Boolean
        get() {
            return when (themeMode) {
                ThemeMode.SYSTEM -> {
                    val nightFlags = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                    nightFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
        }
        set(value) {
            themeMode = if (value) ThemeMode.DARK else ThemeMode.LIGHT
        }

    var keyboardScale: KeyboardScale
        get() {
            val raw = settingsPrefs.getString(KEY_KEYBOARD_SCALE, KeyboardScale.NORMAL.name)
            return KeyboardScale.entries.firstOrNull { it.name == raw }
                ?: KeyboardScale.NORMAL
        }
        set(value) = settingsPrefs.edit().putString(KEY_KEYBOARD_SCALE, value.name).apply()

    var bottomNavClearanceDp: Int
        get() = settingsPrefs.getInt(KEY_BOTTOM_CLEARANCE_DP, 38)
        set(value) = settingsPrefs.edit().putInt(KEY_BOTTOM_CLEARANCE_DP, value.coerceIn(0, 160)).apply()

    var autoReturnToPreviousKeyboard: Boolean
        get() = settingsPrefs.getBoolean(KEY_AUTO_RETURN, true)
        set(value) = settingsPrefs.edit().putBoolean(KEY_AUTO_RETURN, value).apply()

    var autoReadAndReplaceChatQuery: Boolean
        get() = settingsPrefs.getBoolean(KEY_AUTO_READ_CHAT_V2, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_AUTO_READ_CHAT_V2, value).apply()

    var nsfwModeEnabled: Boolean
        get() = settingsPrefs.getBoolean(KEY_NSFW_MODE, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_NSFW_MODE, value).apply()

    var engineMode: SearchEngineMode
        get() {
            val raw = settingsPrefs.getString(KEY_ENGINE_MODE, SearchEngineMode.HYBRID.name)
            return SearchEngineMode.entries.firstOrNull { it.name == raw }
                ?: SearchEngineMode.HYBRID
        }
        set(value) = settingsPrefs.edit().putString(KEY_ENGINE_MODE, value.name).apply()

    var optionalApiKey: String
        get() = privatePrefs.getString(KEY_OPTIONAL_API_KEY, "")?.trim().orEmpty()
        set(value) = privatePrefs.edit().putString(KEY_OPTIONAL_API_KEY, value.trim()).apply()

    var gridColumns: Int
        get() = settingsPrefs.getInt(KEY_GRID_COLUMNS, 3).coerceIn(1, 4)
        set(value) = settingsPrefs.edit().putInt(KEY_GRID_COLUMNS, value.coerceIn(1, 4)).apply()

    var isFullScreenMode: Boolean
        get() = settingsPrefs.getBoolean(KEY_FULL_SCREEN_MODE, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_FULL_SCREEN_MODE, value).apply()

    var useClassicIcon: Boolean
        get() = settingsPrefs.getBoolean(KEY_USE_CLASSIC_ICON, false)
        set(value) = settingsPrefs.edit().putBoolean(KEY_USE_CLASSIC_ICON, value).apply()

    fun cycleGridColumns(): Int {
        val next = when (gridColumns) {
            3 -> 4
            4 -> 1
            1 -> 2
            2 -> 3
            else -> 3
        }
        gridColumns = next
        return next
    }

    fun getGridItemHeightDp(columns: Int = gridColumns): Int {
        val baseScale = keyboardScale
        return when (columns) {
            1 -> (baseScale.gridItemHeightDp * 1.65f).toInt()
            2 -> (baseScale.gridItemHeightDp * 1.30f).toInt()
            3 -> baseScale.gridItemHeightDp
            4 -> (baseScale.gridItemHeightDp * 0.82f).toInt()
            else -> baseScale.gridItemHeightDp
        }
    }

    // --- Search History (up to 25 recent queries, stored in backup-excluded privatePrefs) ---

    fun getSearchHistory(): List<String> {
        val raw = privatePrefs.getString(KEY_SEARCH_HISTORY_JSON, "[]") ?: "[]"
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
        privatePrefs.edit().putString(KEY_SEARCH_HISTORY_JSON, arr.toString()).apply()
    }

    // --- Quantified Search & Image Insertion Usage Tracking ---

    private fun ensureQuotaDatesCurrent() {
        val todayStr = LocalDate.now().toString()
        val monthStr = YearMonth.now().toString()
        val savedDay = privatePrefs.getString(KEY_QUOTA_DAY_DATE, "")
        val savedMonth = privatePrefs.getString(KEY_QUOTA_MONTH_DATE, "")

        val editor = privatePrefs.edit()
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
            return privatePrefs.getInt(KEY_SEARCHES_TODAY, 0)
        }

    val searchesThisMonth: Int
        get() {
            ensureQuotaDatesCurrent()
            return privatePrefs.getInt(KEY_SEARCHES_MONTH, 0)
        }

    val imagesInsertedToday: Int
        get() {
            ensureQuotaDatesCurrent()
            return privatePrefs.getInt(KEY_INSERTS_TODAY, 0)
        }

    val imagesInsertedTotal: Int
        get() = privatePrefs.getInt(KEY_INSERTS_TOTAL, 0)

    fun recordSearchExecuted() {
        ensureQuotaDatesCurrent()
        val dayCount = privatePrefs.getInt(KEY_SEARCHES_TODAY, 0) + 1
        val monthCount = privatePrefs.getInt(KEY_SEARCHES_MONTH, 0) + 1
        privatePrefs.edit()
            .putInt(KEY_SEARCHES_TODAY, dayCount)
            .putInt(KEY_SEARCHES_MONTH, monthCount)
            .apply()
    }

    fun recordImageInserted() {
        ensureQuotaDatesCurrent()
        val dayInserts = privatePrefs.getInt(KEY_INSERTS_TODAY, 0) + 1
        val totalInserts = privatePrefs.getInt(KEY_INSERTS_TOTAL, 0) + 1
        privatePrefs.edit()
            .putInt(KEY_INSERTS_TODAY, dayInserts)
            .putInt(KEY_INSERTS_TOTAL, totalInserts)
            .apply()
    }

    companion object {
        const val PREFS_NAME_SETTINGS = "keyboardy_prefs"
        const val PREFS_NAME_PRIVATE = "keyboardy_private_prefs"

        const val KEY_THEME_MODE = "pref_theme_mode_v2"
        const val KEY_KEYBOARD_SCALE = "keyboard_scale"
        const val KEY_BOTTOM_CLEARANCE_DP = "bottom_clearance_dp_v4"
        const val KEY_AUTO_RETURN = "auto_return_gboard"
        const val KEY_AUTO_READ_CHAT_V2 = "auto_read_chat_query_v2"
        const val KEY_NSFW_MODE = "nsfw_mode_enabled"
        const val KEY_ENGINE_MODE = "engine_mode"
        const val KEY_GRID_COLUMNS = "grid_columns"
        const val KEY_FULL_SCREEN_MODE = "is_full_screen_mode"
        const val KEY_USE_CLASSIC_ICON = "use_classic_icon"

        const val KEY_OPTIONAL_API_KEY = "optional_api_key"
        const val KEY_SEARCH_HISTORY_JSON = "search_history_json"
        const val KEY_QUOTA_DAY_DATE = "quota_day_date"
        const val KEY_QUOTA_MONTH_DATE = "quota_month_date"
        const val KEY_SEARCHES_TODAY = "searches_today"
        const val KEY_SEARCHES_MONTH = "searches_month"
        const val KEY_INSERTS_TODAY = "inserts_today"
        const val KEY_INSERTS_TOTAL = "inserts_total"
    }
}
