package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentSubscriptionsBinding
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.SubscriptionGroup
import com.github.libretube.extensions.dpToPx
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.adapters.LegacySubscriptionAdapter
import com.github.libretube.ui.adapters.SubscriptionChannelAdapter
import com.github.libretube.ui.adapters.VideosAdapter
import com.github.libretube.ui.models.PlayerViewModel
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.sheets.ChannelGroupsSheet
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SubscriptionsFragment : Fragment() {
    private var _binding: FragmentSubscriptionsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SubscriptionsViewModel by activityViewModels()
    private val playerModel: PlayerViewModel by activityViewModels()
    private var channelGroups = listOf<SubscriptionGroup>()
    private var selectedFilterGroup = 0
    private var isCurrentTabSubChannels = false

    var subscriptionsAdapter: VideosAdapter? = null
    private var selectedSortOrder = PreferenceHelper.getInt(PreferenceKeys.FEED_SORT_ORDER, 0)
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.FEED_SORT_ORDER, value)
            field = value
        }
    private var selectedFilter = PreferenceHelper.getInt(PreferenceKeys.SELECTED_FEED_FILTER, 0)
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.SELECTED_FEED_FILTER, value)
            field = value
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSubscriptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val loadFeedInBackground = PreferenceHelper.getBoolean(
            PreferenceKeys.SAVE_FEED,
            false
        )

        // update the text according to the current order and filter
        binding.sortTV.text = resources.getStringArray(R.array.sortOptions)[selectedSortOrder]
        binding.filterTV.text = resources.getStringArray(R.array.filterOptions)[selectedFilter]

        binding.subRefresh.isEnabled = true
        binding.subProgress.isVisible = true

        binding.subFeed.layoutManager = VideosAdapter.getLayout(requireContext())

        if (!isCurrentTabSubChannels && (viewModel.videoFeed.value == null || !loadFeedInBackground)) {
            viewModel.videoFeed.value = null
            viewModel.fetchFeed(requireContext())
        }

        viewModel.videoFeed.observe(viewLifecycleOwner) {
            if (!isCurrentTabSubChannels && it != null) showFeed()
        }

        viewModel.subscriptions.observe(viewLifecycleOwner) {
            if (isCurrentTabSubChannels && it != null) showSubscriptions()
        }

        binding.subRefresh.setOnRefreshListener {
            viewModel.fetchSubscriptions(requireContext())
            viewModel.fetchFeed(requireContext())
        }

        binding.sortTV.setOnClickListener {
            val sortOptions = resources.getStringArray(R.array.sortOptions)

            BaseBottomSheet().apply {
                setSimpleItems(sortOptions.toList()) { index ->
                    binding.sortTV.text = sortOptions[index]
                    selectedSortOrder = index
                    showFeed()
                }
            }.show(childFragmentManager)
        }

        binding.filterTV.setOnClickListener {
            val filterOptions = resources.getStringArray(R.array.filterOptions)

            BaseBottomSheet().apply {
                setSimpleItems(filterOptions.toList()) { index ->
                    binding.filterTV.text = filterOptions[index]
                    selectedFilter = index
                    showFeed()
                }
            }.show(childFragmentManager)
        }

        binding.toggleSubs.isVisible = true

        binding.toggleSubs.setOnClickListener {
            binding.subProgress.isVisible = true
            binding.subRefresh.isRefreshing = true
            isCurrentTabSubChannels = !isCurrentTabSubChannels

            if (isCurrentTabSubChannels) {
                if (viewModel.subscriptions.value == null) {
                    viewModel.fetchSubscriptions(requireContext())
                } else {
                    showSubscriptions()
                }
            } else {
                showFeed()
            }
            binding.subChannelsContainer.isVisible = isCurrentTabSubChannels
            binding.subFeedContainer.isGone = isCurrentTabSubChannels
        }

        binding.scrollviewSub.viewTreeObserver.addOnScrollChangedListener {
            val binding = _binding
            if (binding?.scrollviewSub?.canScrollVertically(1) == false &&
                viewModel.videoFeed.value != null // scroll view is at bottom
            ) {
                binding.subRefresh.isRefreshing = true
                subscriptionsAdapter?.updateItems()
                binding.subRefresh.isRefreshing = false
            }
        }

        // add some extra margin to the subscribed channels while the mini player is visible
        // otherwise the last channel would be invisible
        playerModel.isMiniPlayerVisible.observe(viewLifecycleOwner) {
            binding.subChannelsContainer.updateLayoutParams<MarginLayoutParams> {
                val newMargin = if (it) 64 else 0
                bottomMargin = newMargin.dpToPx().toInt()
            }
        }

        lifecycleScope.launch {
            initChannelGroups()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    @SuppressLint("InflateParams")
    private suspend fun initChannelGroups() {
        channelGroups = DatabaseHolder.Database.subscriptionGroupsDao().getAll()

        val binding = _binding ?: return

        binding.chipAll.isChecked = true
        binding.channelGroups.removeAllViews()

        binding.channelGroups.addView(binding.chipAll)
        channelGroups = channelGroups.sortedBy { it.index }
        channelGroups.forEach { group ->
            val chip = layoutInflater.inflate(R.layout.filter_chip, null) as Chip
            chip.apply {
                id = View.generateViewId()
                isCheckable = true
                text = group.name
            }

            binding.channelGroups.addView(chip)
        }

        binding.channelGroups.setOnCheckedStateChangeListener { group, checkedIds ->
            selectedFilterGroup = group.children.indexOfFirst { it.id == checkedIds.first() }
            showFeed()
        }

        binding.editGroups.setOnClickListener {
            ChannelGroupsSheet(channelGroups.toMutableList()) {
                lifecycleScope.launch { initChannelGroups() }
            }.show(childFragmentManager, null)
        }
    }

    private fun showFeed() {
        val videoFeed = viewModel.videoFeed.value ?: return

        binding.subRefresh.isRefreshing = false
        val feed = videoFeed
            .filter { streamItem ->
                // filter for selected channel groups
                if (selectedFilterGroup == 0) {
                    true
                } else {
                    val channelId = streamItem.uploaderUrl.orEmpty().toID()
                    val group = channelGroups.getOrNull(selectedFilterGroup - 1)
                    group?.channels?.contains(channelId) != false
                }
            }
            .filter {
                // apply the selected filter
                val isLive = (it.duration ?: -1L) < 0L
                when (selectedFilter) {
                    0 -> true
                    1 -> !it.isShort && !isLive
                    2 -> it.isShort
                    3 -> isLive
                    else -> throw IllegalArgumentException()
                }
            }.let { streams ->

                if (!PreferenceHelper.getBoolean(
                        PreferenceKeys.HIDE_WATCHED_FROM_FEED,
                        false
                    )
                ) {
                    streams
                } else {
                    runBlocking {
                        DatabaseHelper.filterUnwatched(streams)
                    }
                }
            }

        // sort the feed
        val sortedFeed = when (selectedSortOrder) {
            0 -> feed
            1 -> feed.reversed()
            2 -> feed.sortedBy { it.views }.reversed()
            3 -> feed.sortedBy { it.views }
            4 -> feed.sortedBy { it.uploaderName }
            5 -> feed.sortedBy { it.uploaderName }.reversed()
            else -> feed
        }.toMutableList()

        // add an "all caught up item"
        if (selectedSortOrder == 0) {
            val lastCheckedFeedTime = PreferenceHelper.getLastCheckedFeedTime()
            val caughtUpIndex = feed.indexOfFirst {
                (it.uploaded ?: 0L) / 1000 < lastCheckedFeedTime
            }
            if (caughtUpIndex > 0) {
                sortedFeed.add(caughtUpIndex, StreamItem(type = "caught"))
            }
        }

        binding.subChannelsContainer.isGone = true
        binding.subProgress.isGone = true

        val notLoaded = viewModel.videoFeed.value.isNullOrEmpty()
        binding.subFeedContainer.isGone = notLoaded
        binding.emptyFeed.isVisible = notLoaded

        subscriptionsAdapter = VideosAdapter(
            sortedFeed.toMutableList(),
            showAllAtOnce = false
        )
        binding.subFeed.adapter = subscriptionsAdapter
        binding.toggleSubsText.text = getString(R.string.subscriptions)

        PreferenceHelper.updateLastFeedWatchedTime()
    }

    @SuppressLint("SetTextI18n")
    private fun showSubscriptions() {
        val subscriptions = viewModel.subscriptions.value ?: return

        val legacySubscriptions = PreferenceHelper.getBoolean(
            PreferenceKeys.LEGACY_SUBSCRIPTIONS,
            false
        )

        if (legacySubscriptions) {
            binding.subChannels.layoutManager = GridLayoutManager(
                context,
                PreferenceHelper.getString(
                    PreferenceKeys.LEGACY_SUBSCRIPTIONS_COLUMNS,
                    "4"
                ).toInt()
            )
            binding.subChannels.adapter = LegacySubscriptionAdapter(subscriptions)
        } else {
            binding.subChannels.layoutManager = LinearLayoutManager(context)
            binding.subChannels.adapter = SubscriptionChannelAdapter(subscriptions.toMutableList())
        }

        binding.subRefresh.isRefreshing = false
        binding.subProgress.isGone = true
        binding.subFeedContainer.isGone = true

        val notLoaded = viewModel.subscriptions.value.isNullOrEmpty()
        binding.subChannelsContainer.isGone = notLoaded
        binding.emptyFeed.isVisible = notLoaded

        val subCount = subscriptions.size.toLong().formatShort()
        binding.toggleSubsText.text = "${getString(R.string.subscriptions)} ($subCount)"
    }
}
