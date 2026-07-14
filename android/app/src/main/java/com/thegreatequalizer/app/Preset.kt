package com.thegreatequalizer.app

import java.text.Normalizer

data class PresetSettingValue(
    val components: List<Float>
) {
    companion object {
        fun scalar(value: Float): PresetSettingValue =
            PresetSettingValue(listOf(value))

        fun compound(
            first: Float,
            second: Float
        ): PresetSettingValue =
            PresetSettingValue(listOf(first, second))
    }
}

data class Preset(
    val version: Int = CURRENT_VERSION,
    val name: String,
    val author: String,
    val settings: Map<String, PresetSettingValue>
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

object PresetNames {
    fun normalizeName(value: String): String {
        val normalized = normalize(value)
        require(normalized.isNotEmpty()) {
            "Preset name must not be empty"
        }
        require(normalized.none(Char::isISOControl)) {
            "Preset name must not contain control characters"
        }
        return normalized
    }

    fun normalizeAuthor(value: String): String {
        val normalized = normalize(value)
        require(normalized.none(Char::isISOControl)) {
            "Preset author must not contain control characters"
        }
        return normalized
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
}

class PresetFormatException(
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)
