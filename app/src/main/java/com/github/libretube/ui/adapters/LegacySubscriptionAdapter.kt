package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.github.libretube.api.obj.Subscription
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.LegacySubscriptionChannelBinding
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.sheets.ChannelOptionsBottomSheet
import com.github.libretube.ui.viewholders.LegacySubscriptionViewHolder

class LegacySubscriptionAdapter : ListAdapter<Subscription, LegacySubscriptionViewHolder>(object :
    DiffUtil.ItemCallback<Subscription>() {
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
    ): LegacySubscriptionViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = LegacySubscriptionChannelBinding.inflate(layoutInflater, parent, false)
        return LegacySubscriptionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LegacySubscriptionViewHolder, position: Int) {
        val subscription = getItem(holder.bindingAdapterPosition)
        holder.binding.apply {
            channelName.text = subscription.name
            ImageHelper.loadImage(
                subscription.avatar,
                channelAvatar,
                true
            )
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
        }
    }
}
