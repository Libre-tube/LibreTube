package com.github.libretube.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.github.libretube.R
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentSearchResultBinding
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.obj.SearchHistoryItem
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.hideKeyboard
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.adapters.SearchAdapter
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

class SearchResultFragment : Fragment(R.layout.fragment_search_result) {
    private val binding by viewBinding(FragmentSearchResultBinding::bind)

    private var nextPage: String? = null
    private var query: String = ""

    private lateinit var searchAdapter: SearchAdapter
    private var apiSearchFilter: String = "all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query = arguments?.getString("query").toString()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.searchRecycler.layoutManager = LinearLayoutManager(requireContext())

        // add the query to the history
        addToHistory(query)

        // filter options
        binding.filterChipGroup.setOnCheckedStateChangeListener { _, _ ->
            apiSearchFilter = when (
                binding.filterChipGroup.checkedChipId
            ) {
                R.id.chip_all -> "all"
                R.id.chip_videos -> "videos"
                R.id.chip_channels -> "channels"
                R.id.chip_playlists -> "playlists"
                R.id.chip_music_songs -> "music_songs"
                R.id.chip_music_videos -> "music_videos"
                R.id.chip_music_albums -> "music_albums"
                R.id.chip_music_playlists -> "music_playlists"
                else -> throw IllegalArgumentException("Filter out of range")
            }
            fetchSearch()
        }

        fetchSearch()

        binding.searchRecycler.viewTreeObserver.addOnScrollChangedListener {
            if (isAdded && !binding.searchRecycler.canScrollVertically(1) &&
                nextPage != null
            ) {
                fetchNextSearchItems()
            }
        }
    }

    private fun fetchSearch() {
        lifecycleScope.launchWhenCreated {
            view?.let { context?.hideKeyboard(it) }
            val response = try {
                withContext(Dispatchers.IO) {
                    RetrofitInstance.api.getSearchResults(query, apiSearchFilter)
                }
            } catch (e: IOException) {
                println(e)
                Log.e(TAG(), "IOException, you might not have internet connection $e")
                return@launchWhenCreated
            } catch (e: HttpException) {
                Log.e(TAG(), "HttpException, unexpected response")
                return@launchWhenCreated
            }
            searchAdapter = SearchAdapter()
            binding.searchRecycler.adapter = searchAdapter
            searchAdapter.submitList(response.items)
            binding.noSearchResult.isVisible = response.items.isEmpty()
            nextPage = response.nextpage
        }
    }

    private fun fetchNextSearchItems() {
        lifecycleScope.launchWhenCreated {
            val response = try {
                withContext(Dispatchers.IO) {
                    RetrofitInstance.api.getSearchResultsNextPage(
                        query,
                        apiSearchFilter,
                        nextPage!!
                    )
                }
            } catch (e: IOException) {
                println(e)
                Log.e(TAG(), "IOException, you might not have internet connection")
                return@launchWhenCreated
            } catch (e: HttpException) {
                Log.e(TAG(), "HttpException, unexpected response," + e.response())
                return@launchWhenCreated
            }
            nextPage = response.nextpage!!
            if (response.items.isNotEmpty()) {
                searchAdapter.submitList(searchAdapter.currentList + response.items)
            }
        }
    }

    private fun addToHistory(query: String) {
        val searchHistoryEnabled =
            PreferenceHelper.getBoolean(PreferenceKeys.SEARCH_HISTORY_TOGGLE, true)
        if (searchHistoryEnabled && query != "") {
            DatabaseHelper.addToSearchHistory(
                SearchHistoryItem(
                    query = query
                )
            )
        }
    }
}
