package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.github.libretube.databinding.AddChannelToGroupRowBinding
import com.github.libretube.db.obj.SubscriptionGroup
import com.github.libretube.ui.viewholders.AddChannelToGroupViewHolder

class AddChannelToGroupAdapter(
    private val channelId: String
) : ListAdapter<SubscriptionGroup, AddChannelToGroupViewHolder>(object: DiffUtil.ItemCallback<SubscriptionGroup>() {
    override fun areItemsTheSame(oldItem: SubscriptionGroup, newItem: SubscriptionGroup): Boolean {
        return oldItem == newItem
    }

    override fun areContentsTheSame(
        oldItem: SubscriptionGroup,
        newItem: SubscriptionGroup
    ): Boolean {
        return oldItem == newItem
    }

}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AddChannelToGroupViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = AddChannelToGroupRowBinding.inflate(layoutInflater, parent, false)
        return AddChannelToGroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AddChannelToGroupViewHolder, position: Int) {
        val channelGroup = getItem(holder.bindingAdapterPosition)

        holder.binding.apply {
            groupName.text = channelGroup.name
            groupCheckbox.isChecked = channelGroup.channels.contains(channelId)

            groupCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    channelGroup.channels += channelId
                } else {
                    channelGroup.channels -= channelId
                }
            }
        }
    }
}
