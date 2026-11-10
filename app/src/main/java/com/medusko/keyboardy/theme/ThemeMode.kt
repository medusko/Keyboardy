package com.medusko.keyboardy.theme

enum class ThemeMode(val title: String, val description: String) {
    SYSTEM(
        title = "System Default",
        description = "Follows Android system-wide dark or light mode setting"
    ),
    DARK(
        title = "Dark Mode",
        description = "Deep navy backgrounds with warm gold/amber accents"
    ),
    LIGHT(
        title = "Light Mode",
        description = "High-contrast clean surfaces with preserved status bar icons"
    )
}
