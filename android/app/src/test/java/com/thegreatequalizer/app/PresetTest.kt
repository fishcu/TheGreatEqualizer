package com.thegreatequalizer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetTest {
    @Test
    fun normalizedDefaultsMapToPreviousRenderValues() {
        val defaults = PipelineParams()

        assertEquals(
            5.0f,
            ParameterRanges.vignetteFalloffToRender(
                defaults.vignetteFalloff
            ),
            0.0001f
        )
        assertEquals(
            1.25f,
            ParameterRanges.grainSizeToRender(defaults.grainSize),
            0.0001f
        )
        assertEquals(
            (Math.PI / 4.0).toFloat(),
            ParameterRanges.controlToShape(
                defaults.lightShadows,
                ParameterRanges.TOE
            ),
            0.0001f
        )
    }

    @Test
    fun shapeMappingsRoundTripNormalControlRange() {
        val ranges = listOf(
            ParameterRanges.TOE,
            ParameterRanges.SHOULDER,
            ParameterRanges.BALANCE,
            ParameterRanges.GAMMA
        )
        for (range in ranges) {
            for (control in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
                val value = ParameterRanges.controlToShape(control, range)
                assertEquals(
                    control,
                    ParameterRanges.shapeToControl(value, range),
                    0.0001f
                )
            }
        }
    }

    @Test
    fun catalogUsesVisibleControlGranularity() {
        assertEquals(21, PresetSettingCatalog.all.size)
        assertEquals(
            listOf("hue", "strength"),
            PresetSettingCatalog.specForKey(
                "tint.shadows"
            ).componentNames
        )
    }

    @Test
    fun sparsePresetOnlyChangesIncludedControls() {
        val base = PipelineParams(
            lightSmoothing = 0.2f,
            grainAmount = 0.1f
        )
        val preset = Preset(
            name = "Grain",
            author = "",
            settings = mapOf(
                "grain.amount" to PresetSettingValue.scalar(0.8f)
            )
        )

        val applied = PresetSettingCatalog.applyPreset(base, preset)

        assertEquals(0.2f, applied.lightSmoothing)
        assertEquals(0.8f, applied.grainAmount)
    }

    @Test
    fun outOfUiRangeValuesRemainCircuitBendable() {
        val hacked = PipelineParams(
            lightSmoothing = 2.0f,
            shadowTintHue = 12.5f,
            shadowTintStrength = 3.0f,
            vignetteAmount = 4.0f,
            grainAmount = 2.0f,
            grainSize = 2.0f
        )

        ParameterRanges.requireMathematicallySafe(hacked)
    }

    @Test
    fun uiBoundsRejectValuesTheRandomizerMustNotProduce() {
        ParameterRanges.requireWithinUiBounds(PipelineParams())
        assertThrows(IllegalArgumentException::class.java) {
            ParameterRanges.requireWithinUiBounds(
                PipelineParams(lightLift = -0.21f)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParameterRanges.requireWithinUiBounds(
                PipelineParams(colorGain = 0.21f)
            )
        }
    }

    @Test
    fun mathematicallyUndefinedValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ParameterRanges.requireMathematicallySafe(
                PipelineParams(grainSize = -1.0f)
            )
        }
    }

    @Test
    fun jsonRoundTripIsCompactAndPreservesHackedValues() {
        val preset = Preset(
            name = "  Bent Grain  ",
            author = " Isaac ",
            settings = linkedMapOf(
                "tint.shadows" to PresetSettingValue.compound(1.5f, 3.0f),
                "grain.amount" to PresetSettingValue.scalar(2.0f)
            )
        )

        val encoded = PresetJsonCodec.encode(preset)
        val decoded = PresetJsonCodec.decode(encoded)

        assertFalse(encoded.contains('\n'))
        assertEquals("Bent Grain", decoded.name)
        assertEquals("Isaac", decoded.author)
        assertEquals(
            PresetSettingValue.compound(1.5f, 3.0f),
            decoded.settings["tint.shadows"]
        )
        assertEquals(
            PresetSettingValue.scalar(2.0f),
            decoded.settings["grain.amount"]
        )
    }

    @Test
    fun jsonUsesOnlyFormatVersion() {
        val encoded = PresetJsonCodec.encode(
            Preset(
                name = "Simple",
                author = "",
                settings = mapOf(
                    "light.smoothing" to PresetSettingValue.scalar(0.4f)
                )
            )
        )

        assertTrue(encoded.contains("\"version\":1"))
        assertFalse(encoded.contains("appVersion"))
    }

    @Test
    fun unknownJsonFieldsAreRejected() {
        assertThrows(PresetFormatException::class.java) {
            PresetJsonCodec.decode(
                """
                {
                  "version": 1,
                  "name": "Unknown",
                  "author": "",
                  "appVersion": "1.3.0",
                  "settings": {"light.smoothing": 0.4}
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun presetLibraryPreservesOrder() {
        fun preset(name: String) = Preset(
            name = name,
            author = "",
            settings = mapOf(
                "light.smoothing" to PresetSettingValue.scalar(0.4f)
            )
        )

        val decoded = PresetJsonCodec.decodeLibrary(
            PresetJsonCodec.encodeLibrary(
                listOf(preset("Second"), preset("First"))
            )
        )

        assertEquals(listOf("Second", "First"), decoded.map(Preset::name))
    }
}
