package com.github.libretube.ui.fragments

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.View
import androidx.core.os.postDelayed
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentWatchHistoryBinding
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.WatchHistoryItem
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.extensions.dpToPx
import com.github.libretube.extensions.setOnDismissListener
import com.github.libretube.helpers.NavBarHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.adapters.WatchHistoryAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.extensions.addOnBottomReachedListener
import com.github.libretube.ui.extensions.setupFragmentAnimation
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.util.PlayingQueue
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.ceil

class WatchHistoryFragment : DynamicLayoutManagerFragment(R.layout.fragment_watch_history) {
    private var _binding: FragmentWatchHistoryBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler(Looper.getMainLooper())
    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()
    private var isLoading = false
    private var recyclerViewState: Parcelable? = null

    private val watchHistoryAdapter = WatchHistoryAdapter()

    private var selectedStatusFilter = PreferenceHelper.getInt(
        PreferenceKeys.SELECTED_HISTORY_STATUS_FILTER,
        0
    )
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.SELECTED_HISTORY_STATUS_FILTER, value)
            field = value
        }
    private var selectedTypeFilter = PreferenceHelper.getInt(
        PreferenceKeys.SELECTED_HISTORY_TYPE_FILTER,
        0
    )
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.SELECTED_HISTORY_TYPE_FILTER, value)
            field = value
        }

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.watchHistoryRecView?.layoutManager =
            GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentWatchHistoryBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        commonPlayerViewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) {
            _binding?.watchHistoryRecView?.updatePadding(bottom = if (it) 64f.dpToPx() else 0)
        }

        binding.watchHistoryRecView.setOnDismissListener { position ->
            watchHistoryAdapter.removeFromWatchHistory(position)
        }

        // observe changes to indicate if the history is empty
        watchHistoryAdapter.registerAdapterDataObserver(object :
            RecyclerView.AdapterDataObserver() {
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                if (watchHistoryAdapter.itemCount == 0) {
                    binding.historyContainer.isGone = true
                    binding.historyEmpty.isVisible = true
                }
            }
        })

        binding.watchHistoryRecView.adapter = watchHistoryAdapter

        // manually restore the recyclerview state due to https://github.com/material-components/material-components-android/issues/3473
        binding.watchHistoryRecView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                recyclerViewState = binding.watchHistoryRecView.layoutManager?.onSaveInstanceState()
            }
        })

        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) {
                DatabaseHelper.getWatchHistoryPage(1, HISTORY_PAGE_SIZE)
            }

            if (history.isEmpty()) return@launch

            binding.filterTypeTV.text =
                resources.getStringArray(R.array.filterOptions)[selectedTypeFilter]
            binding.filterStatusTV.text =
                resources.getStringArray(R.array.filterStatusOptions)[selectedStatusFilter]

            val watchPositionItem = arrayOf(getString(R.string.also_clear_watch_positions))
            val selected = booleanArrayOf(false)

            binding.clear.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.clear_history)
                    .setMultiChoiceItems(watchPositionItem, selected) { _, index, newValue ->
                        selected[index] = newValue
                    }
                    .setPositiveButton(R.string.okay) { _, _ ->
                        binding.historyContainer.isGone = true
                        binding.historyEmpty.isVisible = true
                        lifecycleScope.launch(Dispatchers.IO) {
                            Database.withTransaction {
                                Database.watchHistoryDao().deleteAll()
                                if (selected[0]) Database.watchPositionDao().deleteAll()
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            binding.filterTypeTV.setOnClickListener {
                val filterOptions = resources.getStringArray(R.array.filterOptions)

                BaseBottomSheet().apply {
                    setSimpleItems(filterOptions.toList()) { index ->
                        binding.filterTypeTV.text = filterOptions[index]
                        selectedTypeFilter = index
                        showWatchHistory(history)
                    }
                }.show(childFragmentManager)
            }

            binding.filterStatusTV.setOnClickListener {
                val filterOptions = resources.getStringArray(R.array.filterStatusOptions)

                BaseBottomSheet().apply {
                    setSimpleItems(filterOptions.toList()) { index ->
                        binding.filterStatusTV.text = filterOptions[index]
                        selectedStatusFilter = index
                        showWatchHistory(history)
                    }
                }.show(childFragmentManager)
            }

            showWatchHistory(history)
        }

        if (NavBarHelper.getStartFragmentId(requireContext()) != R.id.watchHistoryFragment) {
            setupFragmentAnimation(binding.root)
        }
    }

    private fun showWatchHistory(history: List<WatchHistoryItem>) {
        val watchHistory = history.filterByStatusAndWatchPosition()

        binding.playAll.setOnClickListener {
            PlayingQueue.resetToDefaults()
            PlayingQueue.add(
                *watchHistory.reversed().map(WatchHistoryItem::toStreamItem).toTypedArray()
            )
            NavigationHelper.navigateVideo(
                requireContext(),
                watchHistory.last().videoId,
                keepQueue = true
            )
        }
        watchHistoryAdapter.submitList(history)
        binding.historyEmpty.isGone = true
        binding.historyContainer.isVisible = true

        // add a listener for scroll end, delay needed to prevent loading new ones the first time
        handler.postDelayed(200) {
            if (_binding == null) return@postDelayed

            binding.watchHistoryRecView.addOnBottomReachedListener {
                if (isLoading) return@addOnBottomReachedListener
                isLoading = true

                lifecycleScope.launch {
                    val newHistory = withContext(Dispatchers.IO) {
                        val currentPage = ceil(watchHistoryAdapter.itemCount.toFloat() / HISTORY_PAGE_SIZE).toInt()
                        DatabaseHelper.getWatchHistoryPage( currentPage + 1, HISTORY_PAGE_SIZE)
                    }.filterByStatusAndWatchPosition()

                    watchHistoryAdapter.insertItems(newHistory)
                    isLoading = false
                }
            }
        }
    }

    private fun List<WatchHistoryItem>.filterByStatusAndWatchPosition(): List<WatchHistoryItem> {
        val watchHistoryItem = this.filter {
            val isLive = (it.duration ?: -1L) < 0L
            when (selectedTypeFilter) {
                0 -> true
                1 -> !it.isShort && !isLive
                2 -> it.isShort // where is the StreamItem converted to watchHistoryItem?
                3 -> isLive
                else -> throw IllegalArgumentException()
            }
        }

        if (selectedStatusFilter == 0) {
            return watchHistoryItem
        }

        return runBlocking {
            when (selectedStatusFilter) {
                1 -> DatabaseHelper.filterByWatchStatus(watchHistoryItem)
                2 -> DatabaseHelper.filterByWatchStatus(watchHistoryItem, false)
                else -> throw IllegalArgumentException()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manually restore the recyclerview state due to https://github.com/material-components/material-components-android/issues/3473
        binding.watchHistoryRecView.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val HISTORY_PAGE_SIZE = 10
    }
}
