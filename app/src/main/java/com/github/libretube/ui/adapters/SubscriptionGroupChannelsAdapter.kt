package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.github.libretube.api.obj.Subscription
import com.github.libretube.databinding.SubscriptionGroupChannelRowBinding
import com.github.libretube.db.obj.SubscriptionGroup
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.ui.viewholders.SubscriptionGroupChannelRowViewHolder

class SubscriptionGroupChannelsAdapter(
    private val group: SubscriptionGroup,
    private val onGroupChanged: (SubscriptionGroup) -> Unit
) : ListAdapter<Subscription, SubscriptionGroupChannelRowViewHolder>(object: DiffUtil.ItemCallback<Subscription>() {
    override fun areItemsTheSame(oldItem: Subscription, newItem: Subscription): Boolean {
        return oldItem == newItem
    }

    override fun areContentsTheSame(oldItem: Subscription, newItem: Subscription): Boolean {
        return oldItem == newItem
    }

}) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): SubscriptionGroupChannelRowViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = SubscriptionGroupChannelRowBinding.inflate(layoutInflater, parent, false)
        return SubscriptionGroupChannelRowViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SubscriptionGroupChannelRowViewHolder, position: Int) {
        val channel = getItem(holder.bindingAdapterPosition)
        holder.binding.apply {
            root.setOnClickListener {
                NavigationHelper.navigateChannel(root.context, channel.url)
            }
            subscriptionChannelName.text = channel.name
            ImageHelper.loadImage(channel.avatar, subscriptionChannelImage, true)

            val channelId = channel.url.toID()
            channelIncluded.setOnCheckedChangeListener(null)
            channelIncluded.isChecked = group.channels.contains(channelId)
            channelIncluded.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) group.channels += channelId else group.channels -= channelId
                onGroupChanged(group)
            }
        }
    }
}
