package com.thegreatequalizer.app

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

class PresetRepository(context: Context) {
    private val storageFile = File(context.filesDir, "presets.json")
    private val atomicFile = AtomicFile(storageFile)
    private val items = mutableListOf<Preset>()

    init {
        if (storageFile.exists()) {
            val text = atomicFile.openRead()
                .bufferedReader(StandardCharsets.UTF_8)
                .use { reader -> reader.readText() }
            items.addAll(PresetJsonCodec.decodeLibrary(text))
        } else {
            persist(BuiltInPresets.all)
            items.addAll(BuiltInPresets.all)
        }
    }

    val presets: List<Preset>
        get() = items.toList()

    fun findByName(name: String): Preset? =
        items.firstOrNull { preset -> preset.name == name }

    fun save(preset: Preset) {
        val valid = PresetSettingCatalog.normalizeAndValidate(preset)
        val updated = items.toMutableList()
        val index = updated.indexOfFirst { item -> item.name == valid.name }
        if (index >= 0) {
            updated[index] = valid
        } else {
            updated.add(valid)
        }
        persist(updated)
        replaceItems(updated)
    }

    fun delete(name: String) {
        val updated = items.toMutableList()
        val index = updated.indexOfFirst { preset -> preset.name == name }
        require(index >= 0) { "Unknown preset: $name" }
        updated.removeAt(index)
        persist(updated)
        replaceItems(updated)
    }

    fun move(fromIndex: Int, toIndex: Int) {
        require(fromIndex in items.indices) {
            "Invalid preset source position: $fromIndex"
        }
        require(toIndex in items.indices) {
            "Invalid preset destination position: $toIndex"
        }
        if (fromIndex == toIndex) return
        val updated = items.toMutableList()
        val preset = updated.removeAt(fromIndex)
        updated.add(toIndex, preset)
        persist(updated)
        replaceItems(updated)
    }

    private fun persist(presets: List<Preset>) {
        val bytes = PresetJsonCodec.encodeLibrary(presets)
            .toByteArray(StandardCharsets.UTF_8)
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (error: IOException) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun replaceItems(updated: List<Preset>) {
        items.clear()
        items.addAll(updated)
    }
}
