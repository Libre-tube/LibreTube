package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.github.libretube.api.obj.Subscription
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.ChannelSubscriptionRowBinding
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.extensions.setupSubscriptionButton
import com.github.libretube.ui.sheets.ChannelOptionsBottomSheet
import com.github.libretube.ui.viewholders.SubscriptionChannelViewHolder

class SubscriptionChannelAdapter : ListAdapter<Subscription, SubscriptionChannelViewHolder>(object :
    DiffUtil.ItemCallback<Subscription>() {
    override fun areItemsTheSame(oldItem: Subscription, newItem: Subscription): Boolean {
        return oldItem == newItem
    }

    override fun areContentsTheSame(oldItem: Subscription, newItem: Subscription): Boolean {
        return oldItem == newItem
    }

}) {
    private var visibleCount = 20

    override fun getItemCount() = minOf(visibleCount, currentList.size)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): SubscriptionChannelViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = ChannelSubscriptionRowBinding.inflate(layoutInflater, parent, false)
        return SubscriptionChannelViewHolder(binding)
    }

    fun updateItems() {
        val oldSize = visibleCount
        visibleCount += minOf(10, currentList.size - oldSize)
        if (visibleCount == oldSize) return
        notifyItemRangeInserted(oldSize, visibleCount)
    }

    override fun onBindViewHolder(holder: SubscriptionChannelViewHolder, position: Int) {
        val subscription = getItem(holder.bindingAdapterPosition)

        holder.binding.apply {
            subscriptionChannelName.text = subscription.name
            ImageHelper.loadImage(subscription.avatar, subscriptionChannelImage, true)

            root.setOnClickListener {
                NavigationHelper.navigateChannel(root.context, subscription.url)
            }
            root.setOnLongClickListener {
                val channelOptionsSheet = ChannelOptionsBottomSheet()
                channelOptionsSheet.arguments = bundleOf(
                    IntentData.channelId to subscription.url.toID(),
                    IntentData.channelName to subscription.name,
                    IntentData.isSubscribed to true
                )
                channelOptionsSheet.show((root.context as BaseActivity).supportFragmentManager)
                true
            }

            subscriptionSubscribe.setupSubscriptionButton(
                subscription.url.toID(),
                subscription.name,
                notificationBell,
                true
            )
        }
    }
}
