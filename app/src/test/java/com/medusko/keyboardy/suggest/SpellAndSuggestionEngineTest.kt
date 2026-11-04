package com.medusko.keyboardy.suggest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpellAndSuggestionEngineTest {

    @Test
    fun testDefaultSuggestionsWhenBlank() {
        val suggestions = SpellAndSuggestionEngine.getInstantSuggestions("")
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.any { it.text == "excited" })
        assertTrue(suggestions.any { it.text == "dancing" })
    }

    @Test
    fun testTypoCorrectionDirectMap() {
        val receiptSuggestions = SpellAndSuggestionEngine.getInstantSuggestions("reciept")
        assertTrue(receiptSuggestions.isNotEmpty())
        assertEquals("receipt", receiptSuggestions.first().text)
        assertTrue(receiptSuggestions.first().isSpellCorrection)

        val danceSuggestions = SpellAndSuggestionEngine.getInstantSuggestions("dancin")
        assertTrue(danceSuggestions.isNotEmpty())
        assertEquals("dancing", danceSuggestions.first().text)
        assertTrue(danceSuggestions.first().isSpellCorrection)
    }

    @Test
    fun testPrefixAutocomplete() {
        val laughSuggestions = SpellAndSuggestionEngine.getInstantSuggestions("laug")
        assertTrue(laughSuggestions.isNotEmpty())
        assertTrue(laughSuggestions.any { it.text.startsWith("laugh") })
    }

    @Test
    fun testFuzzyLevenshteinTypoCorrection() {
        val haplySuggestions = SpellAndSuggestionEngine.getInstantSuggestions("haply")
        assertTrue(haplySuggestions.isNotEmpty())
        assertTrue(haplySuggestions.any { it.isSpellCorrection })
    }
}
