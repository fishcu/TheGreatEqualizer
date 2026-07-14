package com.thegreatequalizer.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PresetAdapter(
    private val onSelected: (Preset) -> Unit,
    private val onCopy: (Preset) -> Unit,
    private val onDelete: (Preset) -> Unit,
    private val onStartDrag: (ViewHolder) -> Unit,
    private val onMove: (Int, Int) -> Unit
) : RecyclerView.Adapter<PresetAdapter.ViewHolder>() {
    private val items = mutableListOf<Preset>()
    private var selectedName: String? = null

    fun submit(presets: List<Preset>, selected: String?) {
        items.clear()
        items.addAll(presets)
        selectedName = selected
        notifyDataSetChanged()
    }

    fun select(name: String?) {
        val previous = selectedName
        selectedName = name
        val previousIndex = items.indexOfFirst { it.name == previous }
        val selectedIndex = items.indexOfFirst { it.name == name }
        if (previousIndex >= 0) notifyItemChanged(previousIndex)
        if (selectedIndex >= 0) notifyItemChanged(selectedIndex)
    }

    fun move(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in items.indices || toIndex !in items.indices) {
            return false
        }
        val item = items.removeAt(fromIndex)
        items.add(toIndex, item)
        notifyItemMoved(fromIndex, toIndex)
        onMove(fromIndex, toIndex)
        return true
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_preset, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val row: LinearLayout = view.findViewById(R.id.preset_row)
        private val selected: RadioButton =
            view.findViewById(R.id.preset_selected)
        private val title: TextView = view.findViewById(R.id.preset_title)
        private val author: TextView = view.findViewById(R.id.preset_author)
        private val copy: ImageButton = view.findViewById(R.id.preset_copy)
        private val delete: ImageButton = view.findViewById(R.id.preset_delete)
        private val drag: ImageButton = view.findViewById(R.id.preset_drag)

        fun bind(preset: Preset) {
            val isSelected = preset.name == selectedName
            selected.isChecked = isSelected
            title.text = preset.name
            author.text = preset.author
            author.visibility = if (preset.author.isEmpty()) {
                View.GONE
            } else {
                View.VISIBLE
            }
            row.setBackgroundColor(
                if (isSelected) 0x2233B5E5 else Color.TRANSPARENT
            )
            row.setOnClickListener { onSelected(preset) }
            copy.setOnClickListener { onCopy(preset) }
            delete.setOnClickListener { onDelete(preset) }
            drag.setOnLongClickListener {
                onStartDrag(this)
                true
            }
        }
    }
}
