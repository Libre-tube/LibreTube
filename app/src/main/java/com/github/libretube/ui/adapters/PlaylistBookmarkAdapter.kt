package com.github.libretube.ui.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.PlaylistBookmarkRowBinding
import com.github.libretube.databinding.PlaylistsRowBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.PlaylistBookmark
import com.github.libretube.enums.PlaylistType
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.sheets.PlaylistOptionsBottomSheet
import com.github.libretube.ui.viewholders.PlaylistBookmarkViewHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PlaylistBookmarkAdapter(
    private val bookmarkMode: BookmarkMode = BookmarkMode.FRAGMENT
) : ListAdapter<PlaylistBookmark, PlaylistBookmarkViewHolder>(object: DiffUtil.ItemCallback<PlaylistBookmark>() {
    override fun areItemsTheSame(oldItem: PlaylistBookmark, newItem: PlaylistBookmark): Boolean {
        return oldItem.playlistId == newItem.playlistId
    }

    override fun areContentsTheSame(oldItem: PlaylistBookmark, newItem: PlaylistBookmark): Boolean {
        return oldItem == newItem
    }

}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistBookmarkViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return when (bookmarkMode) {
            BookmarkMode.HOME -> PlaylistBookmarkViewHolder(
                PlaylistBookmarkRowBinding.inflate(layoutInflater, parent, false)
            )

            BookmarkMode.FRAGMENT -> PlaylistBookmarkViewHolder(
                PlaylistsRowBinding.inflate(layoutInflater, parent, false)
            )
        }
    }

    private fun showPlaylistOptions(context: Context, bookmark: PlaylistBookmark) {
        val sheet = PlaylistOptionsBottomSheet()
        sheet.arguments = bundleOf(
            IntentData.playlistId to bookmark.playlistId,
            IntentData.playlistName to bookmark.playlistName,
            IntentData.playlistType to PlaylistType.PUBLIC
        )
        sheet.show(
            (context as BaseActivity).supportFragmentManager
        )
    }

    override fun onBindViewHolder(holder: PlaylistBookmarkViewHolder, position: Int) {
        val bookmark = getItem(holder.bindingAdapterPosition)
        holder.playlistBookmarkBinding?.apply {
            ImageHelper.loadImage(bookmark.thumbnailUrl, thumbnail)
            playlistName.text = bookmark.playlistName
            uploaderName.text = bookmark.uploader

            root.setOnClickListener {
                NavigationHelper.navigatePlaylist(
                    root.context,
                    bookmark.playlistId,
                    PlaylistType.PUBLIC
                )
            }

            root.setOnLongClickListener {
                showPlaylistOptions(root.context, bookmark)
                true
            }
        }

        holder.playlistsBinding?.apply {
            var isBookmarked = true

            ImageHelper.loadImage(bookmark.thumbnailUrl, playlistThumbnail)
            playlistTitle.text = bookmark.playlistName
            playlistDescription.text = bookmark.uploader
            videoCount.text = bookmark.videos.toString()

            bookmarkPlaylist.setOnClickListener {
                isBookmarked = !isBookmarked
                bookmarkPlaylist.setImageResource(
                    if (isBookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outlined
                )
                CoroutineScope(Dispatchers.IO).launch {
                    if (!isBookmarked) {
                        DatabaseHolder.Database.playlistBookmarkDao()
                            .deleteById(bookmark.playlistId)
                    } else {
                        DatabaseHolder.Database.playlistBookmarkDao().insert(bookmark)
                    }
                }
            }
            bookmarkPlaylist.isVisible = true

            root.setOnClickListener {
                NavigationHelper.navigatePlaylist(
                    root.context,
                    bookmark.playlistId,
                    PlaylistType.PUBLIC
                )
            }

            root.setOnLongClickListener {
                showPlaylistOptions(root.context, bookmark)
                true
            }
        }
    }

    companion object {
        enum class BookmarkMode {
            HOME,
            FRAGMENT
        }
    }
}
