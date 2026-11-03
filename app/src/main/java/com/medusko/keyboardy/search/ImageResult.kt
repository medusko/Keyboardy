package com.medusko.keyboardy.search

data class ImageResult(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val fullImageUrl: String,
    val isAnimatedGif: Boolean,
    val sourceLabel: String
)

enum class SearchFilter(
    val querySuffix: String,
    val animatedOnly: Boolean,
    val transparentOnly: Boolean = false
) {
    ALL(querySuffix = "", animatedOnly = false),
    GIF(querySuffix = "gif", animatedOnly = true),
    MEME(querySuffix = "meme", animatedOnly = false),
    REACTION(querySuffix = "reaction gif", animatedOnly = true),
    TRANSPARENT(querySuffix = "transparent png sticker", animatedOnly = false, transparentOnly = true);

    fun buildEffectiveQuery(rawQuery: String): String {
        val base = rawQuery.trim()
        if (base.isEmpty()) return ""
        if (querySuffix.isEmpty()) return base
        val lower = base.lowercase()
        return if (lower.contains(querySuffix.lowercase())) {
            base
        } else {
            "$base $querySuffix"
        }
    }
}
