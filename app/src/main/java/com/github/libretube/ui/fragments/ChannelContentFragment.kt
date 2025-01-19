package com.github.libretube.ui.fragments

import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.View
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.obj.ChannelTab
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.FragmentChannelContentBinding
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.parcelableArrayList
import com.github.libretube.ui.adapters.SearchChannelAdapter
import com.github.libretube.ui.adapters.VideosAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.util.deArrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChannelContentFragment : DynamicLayoutManagerFragment(R.layout.fragment_channel_content) {
    private var _binding: FragmentChannelContentBinding? = null
    private val binding get() = _binding!!
    private var channelId: String? = null
    private var searchChannelAdapter: SearchChannelAdapter? = null
    private var channelAdapter: VideosAdapter? = null
    private var recyclerViewState: Parcelable? = null
    private var nextPage: String? = null
    private var isLoading: Boolean = false

    override fun setLayoutManagers(gridItems: Int) {
        binding.channelRecView.layoutManager = GridLayoutManager(
            requireContext(),
            gridItems.ceilHalf()
        )
    }

    private suspend fun fetchChannelNextPage(nextPage: String): String? {
        val response = withContext(Dispatchers.IO) {
            RetrofitInstance.api.getChannelNextPage(channelId!!, nextPage).apply {
                relatedStreams = relatedStreams.deArrow()
            }
        }
        channelAdapter?.insertItems(response.relatedStreams)
        return response.nextpage
    }

    private suspend fun fetchTabNextPage(nextPage: String, tab: ChannelTab): String? {
        val newContent = withContext(Dispatchers.IO) {
            RetrofitInstance.api.getChannelTab(tab.data, nextPage)
        }.apply {
            content = content.deArrow()
        }

        searchChannelAdapter?.let {
            it.submitList(it.currentList + newContent.content)
        }
        return newContent.nextpage
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manually restore the recyclerview state due to https://github.com/material-components/material-components-android/issues/3473
        binding.channelRecView.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }

    private fun loadChannelTab(tab: ChannelTab) = lifecycleScope.launch {
        val response = try {
            withContext(Dispatchers.IO) {
                RetrofitInstance.api.getChannelTab(tab.data)
            }.apply {
                content = content.deArrow()
            }
        } catch (e: Exception) {
            _binding?.progressBar?.isGone = true
            return@launch
        }
        nextPage = response.nextpage

        searchChannelAdapter?.submitList(response.content)
        val binding = _binding ?: return@launch
        binding.progressBar.isGone = true

        isLoading = false
    }

    private fun loadNextPage(isVideo: Boolean, tab: ChannelTab) {
        if (isLoading) return

        lifecycleScope.launch {
            try {
                isLoading = true
                nextPage = if (isVideo) {
                    fetchChannelNextPage(nextPage ?: return@launch)
                } else {
                    fetchTabNextPage(nextPage ?: return@launch, tab)
                }
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
            }
            isLoading = false
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentChannelContentBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        val arguments = requireArguments()
        val tabData = arguments.parcelable<ChannelTab>(IntentData.tabData)
        channelId = arguments.getString(IntentData.channelId)
        nextPage = arguments.getString(IntentData.nextPage)

        searchChannelAdapter = SearchChannelAdapter()
        binding.channelRecView.adapter = searchChannelAdapter

        binding.channelRecView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                recyclerViewState = binding.channelRecView.layoutManager?.onSaveInstanceState()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                if (_binding == null || isLoading) return

                val visibleItemCount = recyclerView.layoutManager!!.childCount
                val totalItemCount = recyclerView.layoutManager!!.getItemCount()
                val firstVisibleItemPosition =
                    (recyclerView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()

                if (firstVisibleItemPosition + visibleItemCount >= totalItemCount) {
                    loadNextPage(tabData?.data!!.isEmpty(), tabData)
                }
            }
        })

        if (tabData?.data.isNullOrEmpty()) {
            channelAdapter = VideosAdapter(
                forceMode = VideosAdapter.Companion.LayoutMode.CHANNEL_ROW
            ).also {
                it.submitList(arguments.parcelableArrayList<StreamItem>(IntentData.videoList)!!)
            }
            binding.channelRecView.adapter = channelAdapter
            binding.progressBar.isGone = true

        } else {
            loadChannelTab(tabData ?: return)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}