package com.thegreatequalizer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.IOException

class PresetsFragment : Fragment() {
    private lateinit var adapter: PresetAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private lateinit var emptyView: TextView
    private lateinit var newButton: MaterialButton
    private lateinit var applyButton: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_presets, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val main = requireActivity() as MainActivity
        val list = view.findViewById<RecyclerView>(R.id.preset_list)
        emptyView = view.findViewById(R.id.preset_empty)
        newButton = view.findViewById(R.id.button_new_preset)
        applyButton = view.findViewById(R.id.button_apply_preset)

        adapter = PresetAdapter(
            onSelected = { preset ->
                main.selectedPresetName = preset.name
                adapter.select(preset.name)
                updateButtons()
            },
            onCopy =(::copyPreset),
            onDelete =(::confirmDelete),
            onStartDrag = { holder -> itemTouchHelper.startDrag(holder) },
            onMove = { from, to ->
                try {
                    main.movePreset(from, to)
                } catch (error: IOException) {
                    refreshState()
                    showStorageError(error)
                }
            }
        )
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        itemTouchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                0
            ) {
                override fun isLongPressDragEnabled(): Boolean = false

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean =
                    adapter.move(
                        viewHolder.bindingAdapterPosition,
                        target.bindingAdapterPosition
                    )

                override fun onSwiped(
                    viewHolder: RecyclerView.ViewHolder,
                    direction: Int
                ) = Unit
            }
        )
        itemTouchHelper.attachToRecyclerView(list)

        newButton.setOnClickListener { showCreateDialog() }
        view.findViewById<MaterialButton>(R.id.button_paste_preset)
            .setOnClickListener { pastePreset() }
        applyButton.setOnClickListener {
            val name = main.selectedPresetName ?: return@setOnClickListener
            try {
                main.applyPreset(name)
            } catch (error: PresetFormatException) {
                showInvalidPreset(error.message!!)
            }
        }
        refreshState()
    }

    fun refreshState() {
        if (!::adapter.isInitialized) return
        val main = requireActivity() as MainActivity
        val presets = main.presets
        if (main.selectedPresetName !in presets.map(Preset::name)) {
            main.selectedPresetName = null
        }
        adapter.submit(presets, main.selectedPresetName)
        emptyView.visibility = if (presets.isEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        updateButtons()
    }

    private fun updateButtons() {
        val main = requireActivity() as MainActivity
        newButton.isEnabled = main.isImageLoaded()
        applyButton.isEnabled =
            main.isImageLoaded() && main.selectedPresetName != null
    }

    private fun showCreateDialog() {
        val main = requireActivity() as MainActivity
        if (!main.isImageLoaded()) return
        val snapshot = main.pipelineParams
        val baseline = main.presetBaseline()
        val defaultKeys = PresetSettingCatalog.changedKeys(
            snapshot,
            baseline
        )
        val dialogView = layoutInflater.inflate(
            R.layout.dialog_create_preset,
            null
        )
        val nameLayout = dialogView.findViewById<TextInputLayout>(
            R.id.preset_name_layout
        )
        val nameInput = dialogView.findViewById<TextInputEditText>(
            R.id.preset_name_input
        )
        val authorInput = dialogView.findViewById<TextInputEditText>(
            R.id.preset_author_input
        )
        val checkboxContainer = dialogView.findViewById<LinearLayout>(
            R.id.preset_setting_checkboxes
        )
        val checkboxes = linkedMapOf<String, CheckBox>()

        for (spec in PresetSettingCatalog.all) {
            val checkbox = CheckBox(requireContext()).apply {
                text = getString(
                    R.string.preset_setting_label,
                    spec.group,
                    spec.label
                )
                setTextColor(Color.WHITE)
                isChecked = spec.key in defaultKeys
                tag = spec.key
            }
            checkboxContainer.addView(checkbox)
            checkboxes[spec.key] = checkbox
        }

        dialogView.findViewById<MaterialButton>(R.id.preset_select_all)
            .setOnClickListener {
                checkboxes.values.forEach { checkbox ->
                    checkbox.isChecked = true
                }
            }
        dialogView.findViewById<MaterialButton>(R.id.preset_select_none)
            .setOnClickListener {
                checkboxes.values.forEach { checkbox ->
                    checkbox.isChecked = false
                }
            }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.preset_create_title)
            .setView(dialogView)
            .setNegativeButton(R.string.preset_cancel, null)
            .setPositiveButton(R.string.preset_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                nameLayout.error = null
                val name = try {
                    PresetNames.normalizeName(
                        nameInput.text?.toString().orEmpty()
                    )
                } catch (error: IllegalArgumentException) {
                    nameLayout.error = error.message
                    return@setOnClickListener
                }
                val author = try {
                    PresetNames.normalizeAuthor(
                        authorInput.text?.toString().orEmpty()
                    )
                } catch (error: IllegalArgumentException) {
                    showInvalidPreset(error.message!!)
                    return@setOnClickListener
                }
                val selectedKeys = checkboxes
                    .filterValues(CheckBox::isChecked)
                    .keys
                if (selectedKeys.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.preset_select_setting,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                val preset = Preset(
                    name = name,
                    author = author,
                    settings = PresetSettingCatalog.capture(
                        snapshot,
                        selectedKeys
                    )
                )
                saveWithOverwriteCheck(
                    preset,
                    R.string.preset_saved
                ) {
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun copyPreset(preset: Preset) {
        val clipboard = requireContext().getSystemService(
            ClipboardManager::class.java
        )
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "The Great Equalizer preset",
                PresetJsonCodec.encode(preset)
            )
        )
        Toast.makeText(
            requireContext(),
            R.string.preset_copied,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun pastePreset() {
        val clipboard = requireContext().getSystemService(
            ClipboardManager::class.java
        )
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(
                requireContext(),
                R.string.preset_clipboard_empty,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val text = clip.getItemAt(0).coerceToText(requireContext()).toString()
        val preset = try {
            PresetJsonCodec.decode(text)
        } catch (error: PresetFormatException) {
            showInvalidPreset(error.message!!)
            return
        }
        saveWithOverwriteCheck(preset, R.string.preset_pasted)
    }

    private fun saveWithOverwriteCheck(
        preset: Preset,
        successMessage: Int,
        onSaved: () -> Unit = {}
    ) {
        val main = requireActivity() as MainActivity
        if (main.findPreset(preset.name) == null) {
            persistPreset(preset, successMessage, onSaved)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.preset_overwrite_title)
            .setMessage(
                getString(R.string.preset_overwrite_message, preset.name)
            )
            .setNegativeButton(R.string.preset_cancel, null)
            .setPositiveButton(R.string.preset_overwrite) { _, _ ->
                persistPreset(preset, successMessage, onSaved)
            }
            .show()
    }

    private fun persistPreset(
        preset: Preset,
        successMessage: Int,
        onSaved: () -> Unit
    ) {
        val main = requireActivity() as MainActivity
        try {
            main.savePreset(preset)
        } catch (error: PresetFormatException) {
            showInvalidPreset(error.message!!)
            return
        } catch (error: IOException) {
            showStorageError(error)
            return
        }
        main.selectedPresetName = preset.name
        refreshState()
        Toast.makeText(
            requireContext(),
            successMessage,
            Toast.LENGTH_SHORT
        ).show()
        onSaved()
    }

    private fun confirmDelete(preset: Preset) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.preset_delete_title)
            .setMessage(
                getString(R.string.preset_delete_message, preset.name)
            )
            .setNegativeButton(R.string.preset_cancel, null)
            .setPositiveButton(R.string.preset_delete_action) { _, _ ->
                deletePreset(preset)
            }
            .show()
    }

    private fun deletePreset(preset: Preset) {
        val main = requireActivity() as MainActivity
        try {
            main.deletePreset(preset.name)
        } catch (error: IOException) {
            showStorageError(error)
            return
        }
        if (main.selectedPresetName == preset.name) {
            main.selectedPresetName = null
        }
        refreshState()
    }

    private fun showStorageError(error: IOException) {
        Toast.makeText(
            requireContext(),
            getString(
                R.string.preset_storage_error,
                error.message ?: error.javaClass.simpleName
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showInvalidPreset(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.preset_invalid_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
