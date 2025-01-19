package com.github.libretube.ui.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView.LayoutManager
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.AllCaughtUpRowBinding
import com.github.libretube.databinding.TrendingRowBinding
import com.github.libretube.databinding.VideoRowBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.extensions.dpToPx
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.extensions.setFormattedDuration
import com.github.libretube.ui.extensions.setWatchProgressLength
import com.github.libretube.ui.sheets.VideoOptionsBottomSheet
import com.github.libretube.ui.viewholders.VideosViewHolder
import com.github.libretube.util.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideosAdapter(
    private val forceMode: LayoutMode = LayoutMode.RESPECT_PREF
) : ListAdapter<StreamItem, VideosViewHolder>(object: DiffUtil.ItemCallback<StreamItem>() {
    override fun areItemsTheSame(oldItem: StreamItem, newItem: StreamItem): Boolean {
        return oldItem == newItem
    }

    override fun areContentsTheSame(oldItem: StreamItem, newItem: StreamItem): Boolean {
        return oldItem == newItem
    }

}) {
    override fun getItemViewType(position: Int): Int {
        return if (currentList[position].type == CAUGHT_UP_STREAM_TYPE) CAUGHT_UP_TYPE else NORMAL_TYPE
    }

    fun insertItems(newItems: List<StreamItem>) {
        val updatedList = currentList.toMutableList().also {
            it.addAll(newItems)
        }

        submitList(updatedList)
    }

    fun removeItemById(videoId: String) {
        val index = currentList.indexOfFirst {
            it.url?.toID() == videoId
        }.takeIf { it > 0 } ?: return
        val updatedList = currentList.toMutableList().also {
            it.removeAt(index)
        }

        submitList(updatedList)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideosViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return when {
            viewType == CAUGHT_UP_TYPE -> VideosViewHolder(
                AllCaughtUpRowBinding.inflate(layoutInflater, parent, false)
            )

            forceMode in listOf(
                LayoutMode.TRENDING_ROW,
                LayoutMode.RELATED_COLUMN
            ) -> VideosViewHolder(
                TrendingRowBinding.inflate(layoutInflater, parent, false)
            )

            forceMode == LayoutMode.CHANNEL_ROW -> VideosViewHolder(
                VideoRowBinding.inflate(layoutInflater, parent, false)
            )

            PreferenceHelper.getBoolean(
                PreferenceKeys.ALTERNATIVE_VIDEOS_LAYOUT,
                false
            ) -> VideosViewHolder(VideoRowBinding.inflate(layoutInflater, parent, false))

            else -> VideosViewHolder(TrendingRowBinding.inflate(layoutInflater, parent, false))
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: VideosViewHolder, position: Int) {
        val video = getItem(holder.bindingAdapterPosition)
        val videoId = video.url.orEmpty().toID()

        val context = (
            holder.videoRowBinding ?: holder.trendingRowBinding ?: holder.allCaughtUpBinding
            )!!.root.context
        val activity = (context as BaseActivity)
        val fragmentManager = activity.supportFragmentManager

        // Trending layout
        holder.trendingRowBinding?.apply {
            // set a fixed width for better visuals
            if (forceMode == LayoutMode.RELATED_COLUMN) {
                root.updateLayoutParams {
                    width = 250f.dpToPx()
                }
            }
            watchProgress.setWatchProgressLength(videoId, video.duration ?: 0L)

            textViewTitle.text = video.title
            textViewChannel.text = TextUtils.formatViewsString(root.context, video.views ?: -1, video.uploaded, video.uploaderName)

            video.duration?.let { thumbnailDuration.setFormattedDuration(it, video.isShort, video.uploaded) }
            channelImage.setOnClickListener {
                NavigationHelper.navigateChannel(root.context, video.uploaderUrl)
            }
            ImageHelper.loadImage(video.thumbnail, thumbnail)
            ImageHelper.loadImage(video.uploaderAvatar, channelImage, true)
            root.setOnClickListener {
                NavigationHelper.navigateVideo(root.context, videoId)
            }

            root.setOnLongClickListener {
                fragmentManager.setFragmentResultListener(
                    VideoOptionsBottomSheet.VIDEO_OPTIONS_SHEET_REQUEST_KEY,
                    activity
                ) { _, _ ->
                    notifyItemChanged(position)
                }
                val sheet = VideoOptionsBottomSheet()
                sheet.arguments = bundleOf(IntentData.streamItem to video)
                sheet.show(fragmentManager, VideosAdapter::class.java.name)
                true
            }
        }

        // Normal videos row layout
        holder.videoRowBinding?.apply {
            videoTitle.text = video.title
            videoInfo.text = TextUtils.formatViewsString(root.context, video.views ?: -1, video.uploaded)

            video.duration?.let { thumbnailDuration.setFormattedDuration(it, video.isShort, video.uploaded) }
            watchProgress.setWatchProgressLength(videoId, video.duration ?: 0L)
            ImageHelper.loadImage(video.thumbnail, thumbnail)

            if (forceMode != LayoutMode.CHANNEL_ROW) {
                ImageHelper.loadImage(video.uploaderAvatar, channelImage, true)
                channelName.text = video.uploaderName

                channelContainer.setOnClickListener {
                    NavigationHelper.navigateChannel(root.context, video.uploaderUrl)
                }
            } else {
                channelImageContainer.isGone = true
            }

            root.setOnClickListener {
                NavigationHelper.navigateVideo(root.context, videoId)
            }

            root.setOnLongClickListener {
                fragmentManager.setFragmentResultListener(
                    VideoOptionsBottomSheet.VIDEO_OPTIONS_SHEET_REQUEST_KEY,
                    activity
                ) { _, _ ->
                    notifyItemChanged(position)
                }
                val sheet = VideoOptionsBottomSheet()
                sheet.arguments = bundleOf(IntentData.streamItem to video)
                sheet.show(fragmentManager, VideosAdapter::class.java.name)
                true
            }

            CoroutineScope(Dispatchers.IO).launch {
                val isDownloaded =
                    DatabaseHolder.Database.downloadDao().exists(videoId)

                withContext(Dispatchers.Main) {
                    downloadBadge.isVisible = isDownloaded
                }
            }
        }
    }

    companion object {
        enum class LayoutMode {
            RESPECT_PREF,
            TRENDING_ROW,
            VIDEO_ROW,
            CHANNEL_ROW,
            RELATED_COLUMN
        }

        fun getLayout(context: Context, gridItems: Int): LayoutManager {
            return if (PreferenceHelper.getBoolean(
                    PreferenceKeys.ALTERNATIVE_VIDEOS_LAYOUT,
                    false
                )
            ) {
                GridLayoutManager(context, gridItems.ceilHalf())
            } else {
                GridLayoutManager(context, gridItems)
            }
        }

        private const val NORMAL_TYPE = 0
        private const val CAUGHT_UP_TYPE = 1

        const val CAUGHT_UP_STREAM_TYPE = "caught"
    }
}
