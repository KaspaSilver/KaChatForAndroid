package com.kachat.app.models

/**
 * Per-contact override for whether incoming photo messages auto-decode and render, or stay
 * hidden behind a "Show Photo" tap. Mirrors iOS's `PhotoAutoDisplayMode` (`Models.swift`).
 * Stored on [ContactEntity.photoAutoDisplayOverride] as this enum's `name`, null meaning
 * [AUTOMATIC] (no override yet).
 */
enum class PhotoAutoDisplayMode(val displayName: String) {
    AUTOMATIC("Automatic"),
    ALWAYS_SHOW("Always Show"),
    ALWAYS_HIDE("Always Hide");

    companion object {
        fun fromName(name: String?): PhotoAutoDisplayMode =
            entries.firstOrNull { it.name == name } ?: AUTOMATIC
    }
}
