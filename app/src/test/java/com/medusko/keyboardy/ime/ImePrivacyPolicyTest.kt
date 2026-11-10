package com.medusko.keyboardy.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.medusko.keyboardy.prefs.KeyboardyPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImePrivacyPolicyTest {

    @Test
    fun `allows standard chat and search text fields`() {
        assertTrue(ImePrivacyPolicy.shouldAllowAutoRead(InputType.TYPE_CLASS_TEXT, 0, "Message #general"))
        assertTrue(
            ImePrivacyPolicy.shouldAllowAutoRead(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
                EditorInfo.IME_ACTION_SEND,
                "Type a message"
            )
        )
        assertTrue(
            ImePrivacyPolicy.shouldAllowAutoRead(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
                0,
                null
            )
        )
        assertTrue(
            ImePrivacyPolicy.shouldAllowAutoRead(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER,
                0,
                "Search Pinterest"
            )
        )
    }

    @Test
    fun `blocks password numeric phone datetime and sensitive text variations`() {
        assertFalse(ImePrivacyPolicy.shouldAllowAutoRead(InputType.TYPE_NULL, 0))
        assertFalse(ImePrivacyPolicy.shouldAllowAutoRead(InputType.TYPE_CLASS_NUMBER, 0))
        assertFalse(
            ImePrivacyPolicy.shouldAllowAutoRead(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                0
            )
        )
        assertFalse(ImePrivacyPolicy.shouldAllowAutoRead(InputType.TYPE_CLASS_PHONE, 0))
        assertFalse(ImePrivacyPolicy.shouldAllowAutoRead(InputType.TYPE_CLASS_DATETIME, 0))

        val sensitiveVariations = listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
        )
        for (variation in sensitiveVariations) {
            assertFalse(
                "Expected variation $variation to be blocked",
                ImePrivacyPolicy.shouldAllowAutoRead(InputType.TYPE_CLASS_TEXT or variation, 0)
            )
        }
    }

    @Test
    fun `blocks incognito IME_FLAG_NO_PERSONALIZED_LEARNING`() {
        assertTrue(
            ImePrivacyPolicy.isNoPersonalizedLearning(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        )
        assertFalse(
            ImePrivacyPolicy.shouldAllowAutoRead(
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
                "Search Google"
            )
        )
    }

    @Test
    fun `blocks credential hints without false positives on normal text`() {
        assertFalse(ImePrivacyPolicy.shouldAllowAutoRead(InputType.TYPE_CLASS_TEXT, 0, "Enter password"))
        assertFalse(ImePrivacyPolicy.shouldAllowAutoRead(InputType.TYPE_CLASS_TEXT, 0, "6-digit OTP"))
        assertFalse(ImePrivacyPolicy.shouldAllowAutoRead(InputType.TYPE_CLASS_TEXT, 0, "One-time code"))
        assertFalse(ImePrivacyPolicy.shouldAllowAutoRead(InputType.TYPE_CLASS_TEXT, 0, "Optional API Key"))
        assertFalse(ImePrivacyPolicy.shouldAllowAutoRead(InputType.TYPE_CLASS_TEXT, 0, "Security PIN"))

        assertTrue(ImePrivacyPolicy.shouldAllowAutoRead(InputType.TYPE_CLASS_TEXT, 0, "Opinion or comment"))
    }

    @Test
    fun `pref change classifier ignores history quota and unrelated keys`() {
        assertEquals(
            KeyboardyImeService.ImePrefReaction.NONE,
            KeyboardyImeService.classifyPrefChange(KeyboardyPrefs.KEY_SEARCH_HISTORY_JSON)
        )
        assertEquals(
            KeyboardyImeService.ImePrefReaction.NONE,
            KeyboardyImeService.classifyPrefChange(KeyboardyPrefs.KEY_SEARCHES_TODAY)
        )
        assertEquals(
            KeyboardyImeService.ImePrefReaction.NONE,
            KeyboardyImeService.classifyPrefChange(KeyboardyPrefs.KEY_SEARCHES_MONTH)
        )
        assertEquals(
            KeyboardyImeService.ImePrefReaction.NONE,
            KeyboardyImeService.classifyPrefChange(KeyboardyPrefs.KEY_INSERTS_TODAY)
        )
        assertEquals(
            KeyboardyImeService.ImePrefReaction.NONE,
            KeyboardyImeService.classifyPrefChange(KeyboardyPrefs.KEY_INSERTS_TOTAL)
        )
        assertEquals(
            KeyboardyImeService.ImePrefReaction.NONE,
            KeyboardyImeService.classifyPrefChange(KeyboardyPrefs.KEY_OPTIONAL_API_KEY)
        )
        assertEquals(
            KeyboardyImeService.ImePrefReaction.NONE,
            KeyboardyImeService.classifyPrefChange(KeyboardyPrefs.KEY_AUTO_RETURN)
        )
        assertEquals(
            KeyboardyImeService.ImePrefReaction.STYLE_UPDATE,
            KeyboardyImeService.classifyPrefChange(KeyboardyPrefs.KEY_THEME_MODE)
        )
        assertEquals(
            KeyboardyImeService.ImePrefReaction.STYLE_UPDATE,
            KeyboardyImeService.classifyPrefChange(KeyboardyPrefs.KEY_GRID_COLUMNS)
        )
        assertEquals(
            KeyboardyImeService.ImePrefReaction.ENGINE_CHIP_UPDATE,
            KeyboardyImeService.classifyPrefChange(KeyboardyPrefs.KEY_ENGINE_MODE)
        )
        assertEquals(
            KeyboardyImeService.ImePrefReaction.RE_RUN_SEARCH,
            KeyboardyImeService.classifyPrefChange(KeyboardyPrefs.KEY_NSFW_MODE)
        )
    }
}
