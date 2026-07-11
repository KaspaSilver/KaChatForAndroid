package com.kachat.app.models

/**
 * Stepped photo-quality preset for chat photos — mirrors iOS's `ChatPhotoQualityPreset`
 * (`Models.swift:803-846`). A stepped preset rather than a free byte slider so behavior stays
 * predictable and easy to test; each preset maps to a target encoded-byte budget consumed by
 * [com.kachat.app.util.ImagePrep.prepareForChatMessage].
 */
enum class ChatPhotoQualityPreset(val targetBytes: Int, val displayName: String) {
    DATA_SAVER(10_000, "Data Saver"),
    BALANCED(15_000, "Balanced"),
    HIGH(31_000, "High"),
    BEST(50_000, "Best");

    val targetSizeText: String get() = "~${targetBytes / 1_000} KB"
    val summaryText: String get() = "$displayName · $targetSizeText"

    companion object {
        val default: ChatPhotoQualityPreset = BALANCED

        fun fromName(name: String?): ChatPhotoQualityPreset =
            entries.firstOrNull { it.name == name } ?: default

        fun fromSliderValue(value: Int): ChatPhotoQualityPreset =
            entries.getOrElse(value.coerceIn(0, entries.size - 1)) { default }
    }
}
