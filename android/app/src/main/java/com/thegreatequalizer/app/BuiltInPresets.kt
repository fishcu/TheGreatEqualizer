package com.thegreatequalizer.app

object BuiltInPresets {
    val all: List<Preset> = listOf(
        Preset(
            name = "All Sat, No Brakes",
            author = "Fishku",
            settings = linkedMapOf(
                "color.lift" to PresetSettingValue.scalar(1.0f),
                "color.gain" to PresetSettingValue.scalar(0.0f)
            )
        ),
        Preset(
            name = "Monochrome Contrast",
            author = "Fishku",
            settings = linkedMapOf(
                "light.shadows" to PresetSettingValue.scalar(0.3f),
                "light.highlights" to PresetSettingValue.scalar(0.7f),
                "color.lift" to PresetSettingValue.scalar(0.0f),
                "color.gain" to PresetSettingValue.scalar(-1.0f)
            )
        ),
        Preset(
            name = "Purple Rain",
            author = "Fishku",
            settings = linkedMapOf(
                "tint.shadows" to PresetSettingValue.compound(
                    2.0f / 3.0f,
                    0.3f
                ),
                "tint.midtones" to PresetSettingValue.compound(
                    5.0f / 6.0f,
                    0.3f
                ),
                "tint.highlights" to PresetSettingValue.compound(
                    0.0f,
                    0.3f
                )
            )
        )
    )
}
