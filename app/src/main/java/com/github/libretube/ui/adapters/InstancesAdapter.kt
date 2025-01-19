package com.github.libretube.ui.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.github.libretube.R
import com.github.libretube.api.obj.PipedInstance
import com.github.libretube.databinding.InstanceRowBinding
import com.github.libretube.ui.viewholders.InstancesViewHolder

class InstancesAdapter(
    initialSelectionApiIndex: Int?,
    private val onSelectInstance: (index: Int) -> Unit
) : ListAdapter<PipedInstance, InstancesViewHolder>(object: DiffUtil.ItemCallback<PipedInstance>() {
    override fun areItemsTheSame(oldItem: PipedInstance, newItem: PipedInstance): Boolean {
        return oldItem == newItem
    }

    override fun areContentsTheSame(oldItem: PipedInstance, newItem: PipedInstance): Boolean {
        return oldItem == newItem
    }

}) {
    private var selectedInstanceIndex = initialSelectionApiIndex?.takeIf { it >= 0 }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InstancesViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = InstanceRowBinding.inflate(layoutInflater)
        return InstancesViewHolder(binding)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: InstancesViewHolder, position: Int) {
        val instance = getItem(holder.bindingAdapterPosition)

        holder.binding.apply {
            var instanceText = "${instance.name} ${instance.locations}"
            if (instance.cdn) {
                instanceText += " (\uD83C\uDF10 CDN)"
            }
            if (instance.registrationDisabled) {
                instanceText +=
                    " (${root.context.getString(R.string.registration_disabled)})"
            }
            if (instance.uptimeMonth != null) {
                instanceText += ", " + root.context.getString(R.string.uptime, instance.uptimeMonth)
            }
            radioButton.text = instanceText

            radioButton.alpha = if (instance.isCurrentlyDown) 0.5f else 1f

            radioButton.setOnCheckedChangeListener(null)
            radioButton.isChecked = selectedInstanceIndex == position
            radioButton.setOnCheckedChangeListener { _, isChecked ->
                val oldIndex = selectedInstanceIndex
                selectedInstanceIndex = holder.absoluteAdapterPosition
                if (isChecked) onSelectInstance(position)
                oldIndex?.let { notifyItemChanged(oldIndex) }
                notifyItemChanged(position)
            }
        }
    }
}
