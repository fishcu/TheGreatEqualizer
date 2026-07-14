package com.thegreatequalizer.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object PresetJsonCodec {
    private val presetKeys = setOf(
        "version",
        "name",
        "author",
        "settings"
    )

    fun encode(preset: Preset): String =
        encodeObject(
            PresetSettingCatalog.normalizeAndValidate(preset)
        ).toString()

    fun decode(text: String): Preset {
        val json = try {
            JSONObject(text)
        } catch (error: JSONException) {
            throw PresetFormatException("Clipboard does not contain JSON", error)
        }
        return decodeObject(json)
    }

    fun encodeLibrary(presets: List<Preset>): String {
        val array = JSONArray()
        for (preset in presets) {
            array.put(
                encodeObject(
                    PresetSettingCatalog.normalizeAndValidate(preset)
                )
            )
        }
        return array.toString()
    }

    fun decodeLibrary(text: String): List<Preset> {
        val array = try {
            JSONArray(text)
        } catch (error: JSONException) {
            throw PresetFormatException(
                "Stored preset library is not valid JSON",
                error
            )
        }
        val presets = ArrayList<Preset>(array.length())
        val names = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val json = try {
                array.getJSONObject(index)
            } catch (error: JSONException) {
                throw PresetFormatException(
                    "Stored preset at position ${index + 1} is invalid",
                    error
                )
            }
            val preset = decodeObject(json)
            if (!names.add(preset.name)) {
                throw PresetFormatException(
                    "Stored preset name is duplicated: ${preset.name}"
                )
            }
            presets.add(preset)
        }
        return presets
    }

    private fun encodeObject(preset: Preset): JSONObject {
        val settings = JSONObject()
        for (spec in PresetSettingCatalog.all) {
            val value = preset.settings[spec.key] ?: continue
            if (spec.componentNames.size == 1) {
                settings.put(spec.key, value.components.single())
            } else {
                val compound = JSONObject()
                for (index in spec.componentNames.indices) {
                    compound.put(
                        spec.componentNames[index],
                        value.components[index]
                    )
                }
                settings.put(spec.key, compound)
            }
        }
        return JSONObject()
            .put("version", preset.version)
            .put("name", preset.name)
            .put("author", preset.author)
            .put("settings", settings)
    }

    private fun decodeObject(json: JSONObject): Preset {
        requireExactKeys(json, presetKeys, "preset")
        val version = requiredInt(json, "version")
        val name = requiredString(json, "name")
        val author = requiredString(json, "author")
        val settingsJson = try {
            json.getJSONObject("settings")
        } catch (error: JSONException) {
            throw PresetFormatException(
                "Preset settings must be a JSON object",
                error
            )
        }

        val settings = linkedMapOf<String, PresetSettingValue>()
        val keys = settingsJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val spec = PresetSettingCatalog.specForKey(key)
            val raw = try {
                settingsJson.get(key)
            } catch (error: JSONException) {
                throw PresetFormatException(
                    "Preset setting $key is missing",
                    error
                )
            }
            settings[key] = if (spec.componentNames.size == 1) {
                PresetSettingValue.scalar(number(raw, key))
            } else {
                val compound = raw as? JSONObject
                    ?: throw PresetFormatException(
                        "Preset setting $key must be a JSON object"
                    )
                requireExactKeys(
                    compound,
                    spec.componentNames.toSet(),
                    key
                )
                PresetSettingValue(
                    spec.componentNames.map { component ->
                        val componentRaw = try {
                            compound.get(component)
                        } catch (error: JSONException) {
                            throw PresetFormatException(
                                "Preset setting $key is missing $component",
                                error
                            )
                        }
                        number(componentRaw, "$key.$component")
                    }
                )
            }
        }

        return PresetSettingCatalog.normalizeAndValidate(
            Preset(
                version = version,
                name = name,
                author = author,
                settings = settings
            )
        )
    }

    private fun requiredInt(json: JSONObject, key: String): Int {
        val raw = try {
            json.get(key)
        } catch (error: JSONException) {
            throw PresetFormatException(
                "Preset $key must be an integer",
                error
            )
        }
        val number = raw as? Number
            ?: throw PresetFormatException(
                "Preset $key must be an integer"
            )
        val doubleValue = number.toDouble()
        val intValue = doubleValue.toInt()
        if (!doubleValue.isFinite() || doubleValue != intValue.toDouble()) {
            throw PresetFormatException(
                "Preset $key must be an integer"
            )
        }
        return intValue
    }

    private fun requiredString(json: JSONObject, key: String): String {
        return try {
            json.getString(key)
        } catch (error: JSONException) {
            throw PresetFormatException(
                "Preset $key must be a string",
                error
            )
        }
    }

    private fun number(raw: Any, label: String): Float {
        val numeric = raw as? Number
            ?: throw PresetFormatException(
                "Preset setting $label must be a number"
            )
        val value = numeric.toDouble().toFloat()
        if (!value.isFinite()) {
            throw PresetFormatException(
                "Preset setting $label must be finite"
            )
        }
        return value
    }

    private fun requireExactKeys(
        json: JSONObject,
        expected: Set<String>,
        label: String
    ) {
        val keys = mutableSetOf<String>()
        val iterator = json.keys()
        while (iterator.hasNext()) {
            keys.add(iterator.next())
        }
        val unknown = keys - expected
        if (unknown.isNotEmpty()) {
            throw PresetFormatException(
                "Unknown $label field: ${unknown.first()}"
            )
        }
        val missing = expected - keys
        if (missing.isNotEmpty()) {
            throw PresetFormatException(
                "Missing $label field: ${missing.first()}"
            )
        }
    }
}
