package com.github.libretube.ui.activities

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.github.libretube.databinding.VideoTagRowBinding
import com.github.libretube.ui.viewholders.VideoTagsViewHolder

class VideoTagsAdapter :
    ListAdapter<String, VideoTagsViewHolder>(object : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }

    }) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoTagsViewHolder {
        val binding = VideoTagRowBinding.inflate(LayoutInflater.from(parent.context))
        return VideoTagsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoTagsViewHolder, position: Int) {
        val tag = getItem(holder.bindingAdapterPosition)
        holder.binding.apply {
            tagText.text = tag
            root.setOnClickListener {
                val mainActivity = root.context as MainActivity
                mainActivity.searchView.setQuery(tag, true)
                // minimizes the player fragment to the mini player
                mainActivity.onBackPressedDispatcher.onBackPressed()
            }
        }
    }
}
