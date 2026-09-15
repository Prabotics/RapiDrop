package com.prabotics.rapidrop.ui.theme


enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    val displayLabel: String
        get() = when (this) {
            SYSTEM -> "System"
            LIGHT -> "Light"
            DARK -> "Dark"
        }
}
