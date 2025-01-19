package com.github.libretube.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.obj.Playlists
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentLibraryBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.extensions.dpToPx
import com.github.libretube.helpers.NavBarHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.adapters.PlaylistBookmarkAdapter
import com.github.libretube.ui.adapters.PlaylistsAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.dialogs.CreatePlaylistDialog
import com.github.libretube.ui.dialogs.CreatePlaylistDialog.Companion.CREATE_PLAYLIST_DIALOG_REQUEST_KEY
import com.github.libretube.ui.extensions.setupFragmentAnimation
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryFragment : DynamicLayoutManagerFragment(R.layout.fragment_library) {
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()

    private val playlistsAdapter = PlaylistsAdapter(PlaylistsHelper.getPrivatePlaylistType())
    private val playlistBookmarkAdapter = PlaylistBookmarkAdapter()

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.bookmarksRecView?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
        _binding?.playlistRecView?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentLibraryBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.bookmarksRecView.adapter = playlistBookmarkAdapter
        // listen for playlists to become deleted
        playlistsAdapter.registerAdapterDataObserver(object :
            RecyclerView.AdapterDataObserver() {
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                _binding?.nothingHere?.isVisible = playlistsAdapter.itemCount == 0
                _binding?.sortTV?.isVisible = playlistsAdapter.itemCount > 0
                super.onItemRangeRemoved(positionStart, itemCount)
            }
        })
        binding.playlistRecView.adapter = playlistsAdapter

        // listen for the mini player state changing
        commonPlayerViewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) {
            updateFABMargin(it)
        }

        // hide watch history button of history disabled
        val watchHistoryEnabled =
            PreferenceHelper.getBoolean(PreferenceKeys.WATCH_HISTORY_TOGGLE, true)
        if (!watchHistoryEnabled) {
            binding.watchHistory.isGone = true
        } else {
            binding.watchHistory.setOnClickListener {
                findNavController().navigate(R.id.watchHistoryFragment)
            }
        }

        binding.downloads.setOnClickListener {
            findNavController().navigate(R.id.downloadsFragment)
        }

        val navBarItems = NavBarHelper.getNavBarItems(requireContext())
        if (navBarItems.filter { it.isVisible }.any { it.itemId == R.id.downloadsFragment }) {
            binding.downloads.isGone = true
        }

        fetchPlaylists()
        initBookmarks()

        binding.playlistRefresh.isEnabled = true
        binding.playlistRefresh.setOnRefreshListener {
            fetchPlaylists()
            initBookmarks()
        }

        childFragmentManager.setFragmentResultListener(
            CREATE_PLAYLIST_DIALOG_REQUEST_KEY,
            this
        ) { _, resultBundle ->
            val isPlaylistCreated = resultBundle.getBoolean(IntentData.playlistTask)
            if (isPlaylistCreated) {
                fetchPlaylists()
            }
        }
        binding.createPlaylist.setOnClickListener {
            CreatePlaylistDialog()
                .show(childFragmentManager, CreatePlaylistDialog::class.java.name)
        }

        val sortOptions = resources.getStringArray(R.array.playlistSortingOptions)
        val sortOptionValues = resources.getStringArray(R.array.playlistSortingOptionsValues)
        val order = PreferenceHelper.getString(
            PreferenceKeys.PLAYLISTS_ORDER,
            sortOptionValues.first()
        )
        val orderIndex = sortOptionValues.indexOf(order)
        binding.sortTV.text = sortOptions.getOrNull(orderIndex)

        binding.sortTV.setOnClickListener {
            BaseBottomSheet().apply {
                setSimpleItems(sortOptions.toList()) { index ->
                    binding.sortTV.text = sortOptions[index]
                    val value = sortOptionValues[index]
                    PreferenceHelper.putString(PreferenceKeys.PLAYLISTS_ORDER, value)
                    fetchPlaylists()
                }
            }.show(childFragmentManager)
        }

        if (NavBarHelper.getStartFragmentId(requireContext()) != R.id.libraryFragment) {
            setupFragmentAnimation(binding.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun initBookmarks() {
        lifecycleScope.launch {
            val bookmarks = withContext(Dispatchers.IO) {
                DatabaseHolder.Database.playlistBookmarkDao().getAll()
            }

            val binding = _binding ?: return@launch

            binding.bookmarksCV.isVisible = bookmarks.isNotEmpty()
            if (bookmarks.isNotEmpty()) {
                playlistBookmarkAdapter.submitList(bookmarks)
            }
        }
    }

    private fun updateFABMargin(isMiniPlayerVisible: Boolean) {
        // optimize CreatePlaylistFab bottom margin if miniPlayer active
        binding.createPlaylist.updateLayoutParams<MarginLayoutParams> {
            bottomMargin = (if (isMiniPlayerVisible) 64f else 16f).dpToPx()
        }
    }

    private fun fetchPlaylists() {
        _binding?.playlistRefresh?.isRefreshing = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                val playlists = try {
                    withContext(Dispatchers.IO) {
                        PlaylistsHelper.getPlaylists()
                    }
                } catch (e: Exception) {
                    Log.e(TAG(), e.toString())
                    Toast.makeText(context, R.string.unknown_error, Toast.LENGTH_SHORT).show()
                    return@repeatOnLifecycle
                }

                val binding = _binding ?: return@repeatOnLifecycle
                binding.playlistRefresh.isRefreshing = false

                if (playlists.isNotEmpty()) {
                    showPlaylists(playlists)
                } else {
                    binding.sortTV.isVisible = false
                    binding.nothingHere.isVisible = true
                }
            }
        }
    }

    private fun showPlaylists(playlists: List<Playlists>) {
        val binding = _binding ?: return

        binding.nothingHere.isGone = true
        binding.sortTV.isVisible = true
        playlistsAdapter.submitList(playlists)
    }
}
