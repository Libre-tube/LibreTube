package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.text.parseAsHtml
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentPlaylistBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.extensions.dpToPx
import com.github.libretube.extensions.setOnDismissListener
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.adapters.PlaylistAdapter
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.extensions.addOnBottomReachedListener
import com.github.libretube.ui.extensions.setupFragmentAnimation
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.sheets.PlaylistOptionsBottomSheet
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class PlaylistFragment : DynamicLayoutManagerFragment(R.layout.fragment_playlist) {
    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!
    private val args by navArgs<PlaylistFragmentArgs>()

    // general playlist information
    private lateinit var playlistId: String
    private var playlistName: String? = null
    private var playlistType = PlaylistType.PUBLIC

    // runtime variables
    private var playlistFeed = mutableListOf<StreamItem>()
    private var playlistAdapter: PlaylistAdapter? = null
    private var nextPage: String? = null
    private var isLoading = true
    private var isBookmarked = false

    // view models
    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()
    private var selectedSortOrder = PreferenceHelper.getInt(PreferenceKeys.PLAYLIST_SORT_ORDER, 0)
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.PLAYLIST_SORT_ORDER, value)
            field = value
        }
    private val sortOptions by lazy { resources.getStringArray(R.array.playlistSortOptions) }
    private var recyclerViewState: Parcelable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlistId = args.playlistId
        playlistType = args.playlistType
    }

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.playlistRecView?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentPlaylistBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.playlistProgress.isVisible = true

        isBookmarked = runBlocking(Dispatchers.IO) {
            DatabaseHolder.Database.playlistBookmarkDao().includes(playlistId)
        }
        updateBookmarkRes()

        commonPlayerViewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) {
            binding.playlistRecView.updatePadding(bottom = if (it) 64f.dpToPx() else 0)
        }

        // manually restore the recyclerview state due to https://github.com/material-components/material-components-android/issues/3473
        binding.playlistRecView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                recyclerViewState = binding.playlistRecView.layoutManager?.onSaveInstanceState()
            }
        })

        fetchPlaylist()

        setupFragmentAnimation(binding.root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateBookmarkRes() {
        binding.bookmark.setIconResource(
            if (isBookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outlined
        )
    }

    private fun fetchPlaylist() {
        lifecycleScope.launch {
            val response = try {
                withContext(Dispatchers.IO) {
                    PlaylistsHelper.getPlaylist(playlistId)
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                return@launch
            }
            val binding = _binding ?: return@launch

            playlistFeed = response.relatedStreams.toMutableList()
            nextPage = response.nextpage
            playlistName = response.name
            isLoading = false

            if (!response.thumbnailUrl.isNullOrEmpty())
                ImageHelper.loadImage(response.thumbnailUrl, binding.thumbnail)
            binding.playlistProgress.isGone = true
            binding.playlistAppBar.isVisible = true
            binding.playlistRecView.isVisible = true
            binding.playlistName.text = response.name

            binding.playlistInfo.text = getChannelAndVideoString(response, response.videos)
            binding.playlistInfo.setOnClickListener {
                NavigationHelper.navigateChannel(requireContext(), response.uploaderUrl)
            }

            binding.playlistDescription.text = response.description?.parseAsHtml()
            // hide playlist description text view if not provided
            binding.playlistDescription.isGone = response.description.orEmpty().isBlank()

            showPlaylistVideos(response)

            // show playlist options
            binding.optionsMenu.setOnClickListener {
                val sheet = PlaylistOptionsBottomSheet()
                sheet.arguments = bundleOf(
                    IntentData.playlistId to playlistId,
                    IntentData.playlistName to playlistName.orEmpty(),
                    IntentData.playlistType to playlistType
                )

                val fragmentManager = (context as BaseActivity).supportFragmentManager
                fragmentManager.setFragmentResultListener(
                    PlaylistOptionsBottomSheet.PLAYLIST_OPTIONS_REQUEST_KEY,
                    (context as BaseActivity)
                ) { _, resultBundle ->
                    val newPlaylistDescription =
                        resultBundle.getString(IntentData.playlistDescription)
                    val newPlaylistName =
                        resultBundle.getString(IntentData.playlistName)
                    val isPlaylistToBeDeleted =
                        resultBundle.getBoolean(IntentData.playlistTask)

                    newPlaylistDescription?.let {
                        binding.playlistDescription.text = it
                        response.description = it
                    }

                    newPlaylistName?.let {
                        binding.playlistName.text = it
                        playlistName = it
                    }

                    if (isPlaylistToBeDeleted) {
                        // TODO move back: navController().popBackStack() crashes
                        return@setFragmentResultListener
                    }
                }

                sheet.show(fragmentManager)
            }

            if (playlistFeed.isEmpty()) {
                binding.nothingHere.isVisible = true
                binding.playAll.isGone = true
            } else {
                binding.playAll.setOnClickListener {
                    if (playlistFeed.isEmpty()) return@setOnClickListener

                    val sortedStreams = getSortedVideos()
                    PlayingQueue.setStreams(sortedStreams)

                    NavigationHelper.navigateVideo(
                        requireContext(),
                        sortedStreams.first().url,
                        playlistId,
                        keepQueue = true
                    )
                }
            }

            if (playlistType == PlaylistType.PUBLIC) {
                binding.bookmark.setOnClickListener {
                    isBookmarked = !isBookmarked
                    updateBookmarkRes()
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (!isBookmarked) {
                            DatabaseHolder.Database.playlistBookmarkDao()
                                .deleteById(playlistId)
                        } else {
                            DatabaseHolder.Database.playlistBookmarkDao()
                                .insert(response.toPlaylistBookmark(playlistId))
                        }
                    }
                }
            } else {
                // private playlist, means shuffle is possible because all videos are received at once
                if (playlistFeed.isEmpty()) {
                    binding.bookmark.isGone = true
                } else {
                    binding.bookmark.setIconResource(R.drawable.ic_shuffle)
                    binding.bookmark.text = getString(R.string.shuffle)
                    binding.bookmark.setOnClickListener {
                        val queue = playlistFeed.shuffled()
                        PlayingQueue.resetToDefaults()
                        PlayingQueue.add(*queue.toTypedArray())
                        NavigationHelper.navigateVideo(
                            requireContext(),
                            queue.firstOrNull()?.url,
                            playlistId = playlistId,
                            keepQueue = true
                        )
                    }
                }

                if (playlistFeed.isEmpty()) {
                    binding.sortContainer.isGone = true
                } else {
                    binding.sortContainer.isVisible = true
                    binding.sortContainer.setOnClickListener {
                        BaseBottomSheet().apply {
                            setSimpleItems(sortOptions.toList()) { index ->
                                selectedSortOrder = index
                                binding.sortTV.text = sortOptions[index]
                                showPlaylistVideos(response)
                            }
                        }.show(childFragmentManager)
                    }
                }

                binding.sortTV.text = sortOptions[selectedSortOrder]

            }

            updatePlaylistBookmark(response)
        }
    }

    /**
     * If the playlist is bookmarked, update its content if modified by the uploader
     */
    private suspend fun updatePlaylistBookmark(playlist: Playlist) {
        if (!isBookmarked) return
        withContext(Dispatchers.IO) {
            // update the playlist thumbnail and title if bookmarked
            val playlistBookmark =
                DatabaseHolder.Database.playlistBookmarkDao().findById(playlistId)
                    ?: return@withContext
            if (playlistBookmark.thumbnailUrl != playlist.thumbnailUrl ||
                playlistBookmark.playlistName != playlist.name ||
                playlistBookmark.videos != playlist.videos
            ) {
                DatabaseHolder.Database.playlistBookmarkDao()
                    .update(playlist.toPlaylistBookmark(playlistBookmark.playlistId))
            }
        }
    }

    private fun getSortedVideos(): List<StreamItem> {
        return when {
            selectedSortOrder in listOf(0, 1) || playlistType == PlaylistType.PUBLIC -> {
                playlistFeed
            }

            selectedSortOrder in listOf(2, 3) -> {
                playlistFeed.sortedBy { it.duration }
            }

            selectedSortOrder in listOf(4, 5) -> {
                playlistFeed.sortedBy { it.title }
            }

            else -> throw IllegalArgumentException()
        }.let {
            if (selectedSortOrder % 2 == 0) it else it.reversed()
        }
    }

    private fun showPlaylistVideos(playlist: Playlist) {
        val videos = getSortedVideos()

        playlistAdapter = PlaylistAdapter(
            playlistFeed,
            videos.toMutableList(),
            playlistId,
            playlistType
        )
        // TODO make sure the adapter is set once in onViewCreated
        binding.playlistRecView.adapter = playlistAdapter

        // listen for playlist items to become deleted
        playlistAdapter!!.registerAdapterDataObserver(object :
            RecyclerView.AdapterDataObserver() {
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                if (positionStart == 0) {
                    ImageHelper.loadImage(
                        playlistFeed.firstOrNull()?.thumbnail.orEmpty(),
                        binding.thumbnail
                    )
                }

                binding.playlistInfo.text = getChannelAndVideoString(playlist, playlistFeed.size)
            }
        })

        binding.playlistRecView.addOnBottomReachedListener {
            if (isLoading) return@addOnBottomReachedListener

            // append more playlists to the recycler view
            if (playlistType != PlaylistType.PUBLIC) {
                isLoading = true
                playlistAdapter?.showMoreItems()
                isLoading = false
            } else {
                fetchNextPage()
            }
        }

        // listener for swiping to the left or right
        if (playlistType != PlaylistType.PUBLIC) {
            binding.playlistRecView.setOnDismissListener { position ->
                val rootView = _binding?.root ?: return@setOnDismissListener
                playlistAdapter!!.removeFromPlaylist(rootView, position)
            }
        }

        updatePlaylistDuration()
    }

    @SuppressLint("StringFormatInvalid", "StringFormatMatches")
    private fun getChannelAndVideoString(playlist: Playlist, count: Int): String {
        if (count < 0) return playlist.uploader.orEmpty()
        if (playlist.uploader == null) return getString(R.string.videoCount, count)

        return getString(R.string.uploaderAndVideoCount, playlist.uploader, count)
    }

    private fun fetchNextPage() {
        if (nextPage == null || isLoading) return
        isLoading = true

        lifecycleScope.launch {
            val response = try {
                withContext(Dispatchers.IO) {
                    // load locally stored playlists with the auth api
                    if (playlistType == PlaylistType.PRIVATE) {
                        RetrofitInstance.authApi.getPlaylistNextPage(playlistId, nextPage!!)
                    } else {
                        RetrofitInstance.api.getPlaylistNextPage(playlistId, nextPage!!)
                    }
                }
            } catch (e: Exception) {
                context?.toastFromMainDispatcher(e.localizedMessage.orEmpty())
                Log.e(TAG(), e.toString())
                return@launch
            }

            nextPage = response.nextpage
            playlistAdapter?.updateItems(response.relatedStreams)
            updatePlaylistDuration()
            isLoading = false
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updatePlaylistDuration() {
        val totalDuration = playlistAdapter?.originalFeed?.sumOf { it.duration ?: 0 } ?: return
        binding.playlistDuration.text = DateUtils.formatElapsedTime(totalDuration) +
                if (nextPage != null) "+" else ""
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manually restore the recyclerview state due to https://github.com/material-components/material-components-android/issues/3473
        binding.playlistRecView.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }
}
