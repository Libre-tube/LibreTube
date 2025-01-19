package com.github.libretube.ui.fragments

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.core.view.isGone
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.databinding.FragmentTrendsBinding
import com.github.libretube.helpers.NavBarHelper
import com.github.libretube.ui.activities.SettingsActivity
import com.github.libretube.ui.adapters.VideosAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.extensions.setupFragmentAnimation
import com.github.libretube.ui.models.TrendsViewModel
import com.google.android.material.snackbar.Snackbar

class TrendsFragment : DynamicLayoutManagerFragment(R.layout.fragment_trends) {
    private var _binding: FragmentTrendsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TrendsViewModel by activityViewModels()

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.recview?.layoutManager = VideosAdapter.getLayout(requireContext(), gridItems)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentTrendsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        val adapter = VideosAdapter()
        binding.recview.adapter = adapter
        binding.recview.layoutManager?.onRestoreInstanceState(viewModel.recyclerViewState)

        viewModel.trendingVideos.observe(viewLifecycleOwner) { videos ->
            if (videos == null) return@observe

            binding.homeRefresh.isRefreshing = false
            binding.progressBar.isGone = true

            adapter.submitList(videos)

            if (videos.isEmpty()) {
                Snackbar.make(binding.root, R.string.change_region, Snackbar.LENGTH_LONG)
                    .setAction(R.string.settings) {
                        val settingsIntent = Intent(context, SettingsActivity::class.java)
                        startActivity(settingsIntent)
                    }
                    .show()
            }
        }

        binding.homeRefresh.isEnabled = true
        binding.homeRefresh.setOnRefreshListener {
            viewModel.fetchTrending(requireContext())
        }

        binding.recview.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                viewModel.recyclerViewState = _binding?.recview?.layoutManager?.onSaveInstanceState()
            }
        })

        viewModel.fetchTrending(requireContext())

        if (NavBarHelper.getStartFragmentId(requireContext()) != R.id.trendsFragment) {
            setupFragmentAnimation(binding.root)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manually restore the recyclerview state due to https://github.com/material-components/material-components-android/issues/3473
        binding.recview.layoutManager?.onRestoreInstanceState(viewModel.recyclerViewState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
