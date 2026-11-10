package com.medusko.keyboardy.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Enforces privacy boundaries for host editors so Keyboardy never leaks passwords,
 * sensitive fields, or incognito sessions into search queries, history, or usage counters.
 */
object ImePrivacyPolicy {

    private val SAFE_TEXT_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_NORMAL,
        InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
        InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
        InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
        InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
        InputType.TYPE_TEXT_VARIATION_FILTER
    )

    private val SENSITIVE_HINT_WORD_REGEX = Regex(
        """\b(password|passcode|pin|otp|cvv|cvc|secret|passphrase)\b"""
    )

    private val SENSITIVE_HINT_PHRASES = listOf(
        "one-time",
        "one time code",
        "verification code",
        "security code",
        "api key",
        "credit card",
        "card number"
    )

    fun isNoPersonalizedLearning(imeOptions: Int): Boolean {
        return (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
    }

    fun isNoPersonalizedLearning(info: EditorInfo?): Boolean {
        return info != null && isNoPersonalizedLearning(info.imeOptions)
    }

    fun isSensitiveInputType(inputType: Int): Boolean {
        if (inputType == InputType.TYPE_NULL) return true
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT) {
            return true
        }
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation !in SAFE_TEXT_VARIATIONS
    }

    fun hasSensitiveHint(hintText: CharSequence?): Boolean {
        val hint = hintText?.toString()?.trim()?.lowercase().orEmpty()
        if (hint.isEmpty()) return false
        if (SENSITIVE_HINT_WORD_REGEX.containsMatchIn(hint)) return true
        return SENSITIVE_HINT_PHRASES.any { hint.contains(it) }
    }

    fun shouldAllowAutoRead(
        inputType: Int,
        imeOptions: Int,
        hintText: CharSequence? = null
    ): Boolean {
        if (isNoPersonalizedLearning(imeOptions)) return false
        if (isSensitiveInputType(inputType)) return false
        if (hasSensitiveHint(hintText)) return false
        return true
    }

    fun shouldAllowAutoRead(
        info: EditorInfo?,
        selfPackageName: String? = null,
        forbiddenFieldId: Int = 0
    ): Boolean {
        if (info == null) return false
        if (selfPackageName != null &&
            forbiddenFieldId != 0 &&
            info.packageName == selfPackageName &&
            info.fieldId == forbiddenFieldId
        ) {
            return false
        }
        return shouldAllowAutoRead(info.inputType, info.imeOptions, info.hintText)
    }
}
