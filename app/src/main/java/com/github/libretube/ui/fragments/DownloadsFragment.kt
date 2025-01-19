package com.github.libretube.ui.fragments

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentDownloadContentBinding
import com.github.libretube.databinding.FragmentDownloadsBinding
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.db.obj.filterByTab
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.extensions.formatAsFileSize
import com.github.libretube.extensions.serializable
import com.github.libretube.extensions.setOnDismissListener
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.NavBarHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.obj.DownloadStatus
import com.github.libretube.receivers.DownloadReceiver
import com.github.libretube.services.DownloadService
import com.github.libretube.ui.adapters.DownloadsAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.extensions.setupFragmentAnimation
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.viewholders.DownloadsViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.path.fileSize

enum class DownloadTab {
    VIDEO,
    AUDIO
}

class DownloadsFragment : Fragment(R.layout.fragment_downloads) {
    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentDownloadsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.downloadsPager.adapter = DownloadsFragmentAdapter(this)

        TabLayoutMediator(binding.tabLayout, binding.downloadsPager) { tab, position ->
            tab.text = when (position) {
                DownloadTab.VIDEO.ordinal -> getString(R.string.video)
                DownloadTab.AUDIO.ordinal -> getString(R.string.audio)
                else -> throw IllegalArgumentException()
            }
        }.attach()

        if (NavBarHelper.getStartFragmentId(requireContext()) != R.id.downloadsFragment) {
            setupFragmentAnimation(binding.root)
        }
    }

    fun bindDownloadService() {
        childFragmentManager.fragments.filterIsInstance<DownloadsFragmentPage>().forEach {
            it.bindDownloadService()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class DownloadsFragmentAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
    override fun getItemCount() = DownloadTab.entries.size

    override fun createFragment(position: Int): Fragment {
        return DownloadsFragmentPage().apply {
            arguments = bundleOf(IntentData.currentPosition to DownloadTab.entries[position])
        }
    }
}

class DownloadsFragmentPage : DynamicLayoutManagerFragment(R.layout.fragment_download_content) {
    private lateinit var adapter: DownloadsAdapter
    private var _binding: FragmentDownloadContentBinding? = null
    private val binding get() = _binding!!

    private var binder: DownloadService.LocalBinder? = null
    private val downloads = mutableListOf<DownloadWithItems>()
    private val downloadReceiver = DownloadReceiver()
    private lateinit var downloadTab: DownloadTab

    private val serviceConnection = object : ServiceConnection {
        var isBound = false
        var job: Job? = null

        override fun onServiceConnected(name: ComponentName?, iBinder: IBinder?) {
            binder = iBinder as DownloadService.LocalBinder
            isBound = true
            job?.cancel()
            job = lifecycleScope.launch {
                binder?.getService()?.downloadFlow?.collectLatest {
                    updateProgress(it.first, it.second)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.downloadTab = requireArguments().serializable(IntentData.currentPosition)!!
    }

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.downloadsRecView?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentDownloadContentBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)
        adapter = DownloadsAdapter(requireContext(), downloadTab) {
            var isDownloading = false
            val ids = it.downloadItems
                .filter { item -> item.path.fileSize() < item.downloadSize }
                .map { item -> item.id }

            if (!serviceConnection.isBound) {
                DownloadHelper.startDownloadService(requireContext())
                bindDownloadService(ids.toIntArray())
                return@DownloadsAdapter true
            }

            binder?.getService()?.let { service ->
                isDownloading = ids.any { id -> service.isDownloading(id) }

                ids.forEach { id ->
                    if (isDownloading) {
                        service.pause(id)
                    } else {
                        service.resume(id)
                    }
                }
            }
            return@DownloadsAdapter isDownloading.not()
        }
        binding.downloadsRecView.adapter = adapter

        var selectedSortType =
            PreferenceHelper.getInt(PreferenceKeys.SELECTED_DOWNLOAD_SORT_TYPE, 0)
        val filterOptions = resources.getStringArray(R.array.downloadSortOptions)
        binding.sortType.text = filterOptions[selectedSortType]
        binding.sortType.setOnClickListener {
            BaseBottomSheet().setSimpleItems(filterOptions.toList()) { index ->
                binding.sortType.text = filterOptions[index]
                if (::adapter.isInitialized) {
                    sortDownloadList(index, selectedSortType)
                }
                selectedSortType = index
                PreferenceHelper.putInt(
                    PreferenceKeys.SELECTED_DOWNLOAD_SORT_TYPE,
                    index
                )
            }.show(childFragmentManager)
        }

        lifecycleScope.launch {
            val dbDownloads = withContext(Dispatchers.IO) {
                Database.downloadDao().getAll()
            }

            downloads.clear()
            downloads.addAll(dbDownloads.filterByTab(downloadTab))

            if (downloads.isEmpty()) return@launch

            sortDownloadList(selectedSortType)


            binding.downloadsRecView.setOnDismissListener { position ->
                adapter.showDeleteDialog(requireContext(), position)
                // put the item back to the center, as it's currently out of the screen
                adapter.restoreItem(position)
            }

            binding.downloadsRecView.adapter?.registerAdapterDataObserver(
                object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                        super.onItemRangeRemoved(positionStart, itemCount)
                        toggleVisibilities()
                    }
                }
            )

            toggleVisibilities()
        }

        binding.deleteAll.setOnClickListener {
            showDeleteAllDialog(binding.root.context, adapter)
        }

        binding.shuffleAll.setOnClickListener {
            BackgroundHelper.playOnBackgroundOffline(
                requireContext(),
                null,
                downloadTab,
                shuffle = true
            )

            NavigationHelper.openAudioPlayerFragment(requireContext(), offlinePlayer = true)
        }
    }

    private fun toggleVisibilities() {
        val binding = _binding ?: return

        val isEmpty = downloads.isEmpty()
        binding.downloadsEmpty.isVisible = isEmpty
        binding.downloadsContainer.isGone = isEmpty
        binding.deleteAll.isGone = isEmpty
        binding.shuffleAll.isGone = isEmpty || downloadTab != DownloadTab.AUDIO
    }

    private fun sortDownloadList(sortType: Int, previousSortType: Int? = null) {
        if (previousSortType == null && sortType == 1) {
            adapter.submitList(downloads.reversed())
        }
        if (previousSortType != null && sortType != previousSortType) {
            adapter.submitList(downloads.reversed())
        }
    }

    private fun showDeleteAllDialog(context: Context, adapter: DownloadsAdapter) {
        var onlyDeleteWatchedVideos = false

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.delete_all)
            .setMultiChoiceItems(arrayOf(getString(R.string.delete_only_watched_videos)), null) { _, _, selected ->
                onlyDeleteWatchedVideos = selected
            }
            .setPositiveButton(R.string.okay) { _, _ ->
                lifecycleScope.launch {
                    for (downloadIndex in downloads.size - 1 downTo 0) {
                        val download = adapter.currentList[downloadIndex].download
                        if (!onlyDeleteWatchedVideos || DatabaseHelper.isVideoWatched(download.videoId, download.duration ?: 0)) {
                            adapter.deleteDownload(downloadIndex)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onStart() {
        if (DownloadService.IS_DOWNLOAD_RUNNING) {
            val intent = Intent(requireContext(), DownloadService::class.java)
            context?.bindService(intent, serviceConnection, 0)
        }
        super.onStart()
    }

    override fun onResume() {
        super.onResume()

        val filter = IntentFilter().apply {
            addAction(DownloadService.ACTION_SERVICE_STARTED)
            addAction(DownloadService.ACTION_SERVICE_STOPPED)
        }
        ContextCompat.registerReceiver(
            requireContext(),
            downloadReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun bindDownloadService(ids: IntArray? = null) {
        if (serviceConnection.isBound) return

        val intent = Intent(context, DownloadService::class.java)
        intent.putExtra("ids", ids)
        context?.bindService(intent, serviceConnection, 0)
    }

    fun updateProgress(id: Int, status: DownloadStatus) {
        val index = downloads.indexOfFirst {
            it.downloadItems.any { item -> item.id == id }
        }
        val view =
            _binding?.downloadsRecView?.findViewHolderForAdapterPosition(index) as? DownloadsViewHolder

        view?.binding?.apply {
            when (status) {
                DownloadStatus.Paused -> {
                    resumePauseBtn.setImageResource(R.drawable.ic_download)
                }

                DownloadStatus.Completed -> {
                    downloadOverlay.isGone = true
                }

                DownloadStatus.Stopped -> Unit

                is DownloadStatus.Progress -> {
                    downloadOverlay.isVisible = true
                    resumePauseBtn.setImageResource(R.drawable.ic_pause)
                    if (progressBar.isIndeterminate) return
                    progressBar.incrementProgressBy(status.progress.toInt())
                    val progressInfo = progressBar.progress.formatAsFileSize() +
                            " /\n" + progressBar.max.formatAsFileSize()
                    fileSize.text = progressInfo
                }

                is DownloadStatus.Error -> {
                    resumePauseBtn.setImageResource(R.drawable.ic_restart)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        context?.unregisterReceiver(downloadReceiver)
    }

    override fun onStop() {
        super.onStop()
        runCatching {
            context?.unbindService(serviceConnection)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}