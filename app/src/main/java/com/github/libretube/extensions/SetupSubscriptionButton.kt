package com.github.libretube.extensions

import android.view.View
import android.widget.TextView
import com.github.libretube.R
import com.github.libretube.api.SubscriptionHelper
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun TextView.setupSubscriptionButton(
    channelId: String?,
    channelName: String?,
    notificationBell: MaterialButton? = null,
    isSubscribed: Boolean? = null
) {
    if (channelId == null) return

    var subscribed: Boolean? = false

    CoroutineScope(Dispatchers.IO).launch {
        subscribed = isSubscribed ?: SubscriptionHelper.isSubscribed(channelId)
        if (subscribed == true) {
            withContext(Dispatchers.Main) {
                this@setupSubscriptionButton.text = context.getString(R.string.unsubscribe)
            }
        } else {
            notificationBell?.visibility = View.GONE
        }
    }

    notificationBell?.setupNotificationBell(channelId)
    this.setOnClickListener {
        if (subscribed == true) {
            SubscriptionHelper.handleUnsubscribe(context, channelId, channelName) {
                this.text = context.getString(R.string.subscribe)
                notificationBell?.visibility = View.GONE
                subscribed = false
            }
        } else {
            SubscriptionHelper.subscribe(channelId)
            this.text = context.getString(R.string.unsubscribe)
            notificationBell?.visibility = View.VISIBLE
            subscribed = true
        }
    }
}
