package com.medusko.keyboardy.search

data class ImageResult(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val fullImageUrl: String,
    val isAnimatedGif: Boolean,
    val sourceLabel: String,
    val isTransparent: Boolean = false
)

enum class SearchFilter(
    val displayLabel: String,
    val querySuffix: String,
    val animatedOnly: Boolean,
    val transparentOnly: Boolean = false
) {
    ALL(displayLabel = "All Images", querySuffix = "", animatedOnly = false),
    GIF(displayLabel = "GIFs", querySuffix = "gif", animatedOnly = true),
    MEME(displayLabel = "Memes", querySuffix = "meme", animatedOnly = false),
    REACTION(displayLabel = "Reactions", querySuffix = "reaction gif", animatedOnly = true),
    TRANSPARENT(displayLabel = "Transparent", querySuffix = "transparent png", animatedOnly = false, transparentOnly = true);

    fun buildEffectiveQuery(rawQuery: String): String {
        val base = rawQuery.trim()
        if (base.isEmpty()) return ""
        if (querySuffix.isEmpty()) return base

        val existingTokens = base.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
            .toSet()
        val missingTokens = querySuffix.lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() && it !in existingTokens }

        return if (missingTokens.isEmpty()) {
            base
        } else {
            "$base ${missingTokens.joinToString(" ")}"
        }
    }
}
