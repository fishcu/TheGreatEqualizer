package com.thegreatequalizer.app

object BuiltInPresets {
    val all: List<Preset> = listOf(
        Preset(
            name = "Simple B&W",
            author = "",
            settings = linkedMapOf(
                "light.shadows" to PresetSettingValue.scalar(0.4f),
                "light.highlights" to PresetSettingValue.scalar(0.6f),
                "color.lift" to PresetSettingValue.scalar(0.0f),
                "color.gain" to PresetSettingValue.scalar(-1.0f)
            )
        ),
        Preset(
            name = "Purple Rain",
            author = "",
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
                ),
                "vignette.amount" to PresetSettingValue.scalar(0.166f),
                "grain.amount" to PresetSettingValue.scalar(0.05f)
            )
        )
    )
}
