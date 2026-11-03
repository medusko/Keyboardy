package com.medusko.keyboardy.prefs

import android.content.Context
import android.content.SharedPreferences

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
        set(value) = prefs.edit().putInt(KEY_BOTTOM_CLEARANCE_DP, value.coerceIn(0, 120)).apply()

    var autoReturnToPreviousKeyboard: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RETURN, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RETURN, value).apply()

    /**
     * Defaults to FALSE so each search in Keyboardy starts blank by default.
     * User can enable this in the app if they want Keyboardy to auto-search text typed in the chat box.
     */
    var autoReadAndReplaceChatQuery: Boolean
        get() = prefs.getBoolean(KEY_AUTO_READ_CHAT_V2, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_READ_CHAT_V2, value).apply()

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

    companion object {
        private const val KEY_DARK_MODE = "is_dark_mode"
        private const val KEY_KEYBOARD_SCALE = "keyboard_scale"
        private const val KEY_BOTTOM_CLEARANCE_DP = "bottom_clearance_dp"
        private const val KEY_AUTO_RETURN = "auto_return_gboard"
        private const val KEY_AUTO_READ_CHAT_V2 = "auto_read_chat_query_v2"
        private const val KEY_ENGINE_MODE = "engine_mode"
        private const val KEY_OPTIONAL_API_KEY = "optional_api_key"
    }
}
