package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.os.postDelayed
import androidx.core.view.SoftwareKeyboardControllerCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.Streams
import com.github.libretube.api.obj.Subtitle
import com.github.libretube.compat.PictureInPictureCompat
import com.github.libretube.compat.PictureInPictureParamsCompat
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentPlayerBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.enums.PlayerEvent
import com.github.libretube.enums.ShareObjectType
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.parcelableList
import com.github.libretube.extensions.serializableExtra
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.togglePlayPauseState
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.IntentHelper
import com.github.libretube.helpers.NavBarHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PlayerHelper.checkForSegments
import com.github.libretube.helpers.PlayerHelper.isInSegment
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.helpers.WindowHelper
import com.github.libretube.obj.ShareData
import com.github.libretube.obj.VideoResolution
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.services.VideoOnlinePlayerService
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.adapters.VideosAdapter
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.dialogs.AddToPlaylistDialog
import com.github.libretube.ui.dialogs.PlayOfflineDialog
import com.github.libretube.ui.dialogs.ShareDialog
import com.github.libretube.ui.extensions.animateDown
import com.github.libretube.ui.extensions.setOnBackPressed
import com.github.libretube.ui.extensions.setupSubscriptionButton
import com.github.libretube.ui.interfaces.OnlinePlayerOptions
import com.github.libretube.ui.listeners.SeekbarPreviewListener
import com.github.libretube.ui.models.ChaptersViewModel
import com.github.libretube.ui.models.CommentsViewModel
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.models.PlayerViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.sheets.CommentsSheet
import com.github.libretube.ui.sheets.StatsSheet
import com.github.libretube.util.OnlineTimeFrameReceiver
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.TextUtils
import com.github.libretube.util.TextUtils.toTimeInSeconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil


@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerFragment : Fragment(R.layout.fragment_player), OnlinePlayerOptions {
    private var _binding: FragmentPlayerBinding? = null
    val binding get() = _binding!!

    private val playerBinding get() = binding.player.binding
    private val doubleTapOverlayBinding get() = binding.doubleTapOverlay.binding
    private val playerGestureControlsViewBinding get() = binding.playerGestureControlsView.binding

    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()
    private val viewModel: PlayerViewModel by viewModels()
    private val commentsViewModel: CommentsViewModel by activityViewModels()
    private val chaptersViewModel: ChaptersViewModel by activityViewModels()
    private lateinit var playerController: MediaController

    // Video information passed by the intent
    private lateinit var videoId: String
    private var playlistId: String? = null
    private var channelId: String? = null

    // data and objects stored for the player
    private lateinit var streams: Streams
    val isShort
        get() = run {
            val heightGreaterThanWidth =
                ::streams.isInitialized && streams.videoStreams.firstOrNull()?.let {
                    (it.height ?: 0) > (it.width ?: 0)
                } == true

            PlayingQueue.getCurrent()?.isShort == true || heightGreaterThanWidth
        }

    // if null, it's been set to automatic
    private var fullscreenResolution: Int? = null

    // the resolution to use when the video is not played in fullscreen
    // if null, use same quality as fullscreen
    private var noFullscreenResolution: Int? = null

    private var selectedAudioLanguageAndRoleFlags: Pair<String?, @C.RoleFlags Int>? = null

    private val handler = Handler(Looper.getMainLooper())

    private var seekBarPreviewListener: SeekbarPreviewListener? = null

    // True when the video was closed through the close button on PiP mode
    private var closedVideo = false

    private var autoPlayCountdownEnabled = PlayerHelper.autoPlayCountdown

    /**
     * The orientation of the `fragment_player.xml` that's currently used
     * This is needed in order to figure out if the current layout is the landscape one or not.
     */
    private var playerLayoutOrientation = Int.MIN_VALUE

    // Activity that's active during PiP, can be used for controlling its lifecycle.
    private var pipActivity: Activity? = null

    private val mainActivity get() = activity as MainActivity
    private val windowInsetsControllerCompat
        get() = WindowCompat
            .getInsetsController(mainActivity.window, mainActivity.window.decorView)

    private val fullscreenDialog by lazy {
        object : Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            @Deprecated("Deprecated in Java", ReplaceWith("onbackpressedispatcher and callback"))
            override fun onBackPressed() {
                unsetFullscreen()
            }

            override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
                if (_binding?.player?.onKeyUp(keyCode, event) == true) {
                    return true
                }

                return super.onKeyUp(keyCode, event)
            }
        }
    }

    /**
     * Receiver for all actions in the PiP mode
     */
    private val playerActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event = intent.serializableExtra<PlayerEvent>(PlayerHelper.CONTROL_TYPE) ?: return

            if (PlayerHelper.handlePlayerAction(playerController, event)) return

            when (event) {
                PlayerEvent.Next -> {
                    PlayingQueue.getNext()?.let { playNextVideo(it) }
                }

                PlayerEvent.Prev -> {
                    PlayingQueue.getPrev()?.let { playNextVideo(it) }
                }

                PlayerEvent.Background -> {
                    playOnBackground()
                    // wait some time in order for the service to get started properly
                    handler.postDelayed(500) {
                        pipActivity?.moveTaskToBack(false)
                        pipActivity = null
                    }
                }

                else -> Unit
            }
        }
    }

    private var bufferingTimeoutTask: Runnable? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (PlayerHelper.pipEnabled || PictureInPictureCompat.isInPictureInPictureMode(
                    mainActivity
                )
            ) {
                PictureInPictureCompat.setPictureInPictureParams(requireActivity(), pipParams)
            }

            if (isPlaying && PlayerHelper.sponsorBlockEnabled) {
                handler.postDelayed(
                    this@PlayerFragment::checkForSegments,
                    100
                )
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            super.onEvents(player, events)

            if (events.containsAny(
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED
                ) && _binding != null
            ) {
                updatePlayPauseButton()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // set the playback speed to one if having reached the end of a livestream
            if (playbackState == Player.STATE_BUFFERING && binding.player.isLive &&
                playerController.duration - playerController.currentPosition < 700
            ) {
                playerController.setPlaybackSpeed(1f)
            }

            // check if video has ended, next video is available and autoplay is enabled/the video is part of a played playlist.
            if (playbackState == Player.STATE_ENDED) {
                if (PlayerHelper.isAutoPlayEnabled(playlistId != null) && autoPlayCountdownEnabled) {
                    showAutoPlayCountdown()
                } else {
                    binding.player.showControllerPermanently()
                }
            }

            // listen for the stop button in the notification
            if (playbackState == PlaybackState.STATE_STOPPED && PlayerHelper.pipEnabled &&
                PictureInPictureCompat.isInPictureInPictureMode(requireActivity())
            ) {
                // finish PiP by finishing the activity
                activity?.finish()
            }

            // Buffering timeout after 10 Minutes
            if (playbackState == Player.STATE_BUFFERING) {
                if (bufferingTimeoutTask == null) {
                    bufferingTimeoutTask = Runnable {
                        playerController.pause()
                    }
                }

                handler.postDelayed(bufferingTimeoutTask!!, PlayerHelper.MAX_BUFFER_DELAY)
            } else {
                bufferingTimeoutTask?.let { handler.removeCallbacks(it) }
            }

            super.onPlaybackStateChanged(playbackState)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            super.onMediaMetadataChanged(mediaMetadata)

            val maybeStreams: Streams? = mediaMetadata.extras?.parcelable(IntentData.streams)
            maybeStreams?.let { streams ->
                this@PlayerFragment.streams = streams
                viewModel.segments.postValue(emptyList())
                setPlayerDefaults()
                updatePlayerView()
            }
        }

        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
            super.onPlaylistMetadataChanged(mediaMetadata)

            mediaMetadata.extras?.getString(IntentData.videoId)?.let {
                videoId = it
                _binding?.autoplayCountdown?.cancelAndHideCountdown()

                // fix: if the fragment is recreated, play the current video, and not the initial one
                arguments?.run {
                    val playerData =
                        parcelable<PlayerData>(IntentData.playerData)!!.copy(videoId = videoId)
                    putParcelable(IntentData.playerData, playerData)
                }
            }

            val segments: List<Segment>? = mediaMetadata.extras?.parcelableList(IntentData.segments)
            viewModel.segments.postValue(segments.orEmpty())
        }

        /**
         * Catch player errors to prevent the app from stopping
         */
        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            try {
                playerController.play()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val lockedOrientations = listOf(
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    )

    private var screenshotBitmap: Bitmap? = null
    private val openScreenshotFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            if (uri == null) {
                screenshotBitmap = null
                return@registerForActivityResult
            }

            context?.contentResolver?.openOutputStream(uri)?.use { outputStream ->
                screenshotBitmap?.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }

            screenshotBitmap = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // broadcast receiver for PiP actions
        ContextCompat.registerReceiver(
            requireContext(),
            playerActionReceiver,
            IntentFilter(PlayerHelper.getIntentActionName(requireContext())),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        fullscreenResolution = PlayerHelper.getDefaultResolution(requireContext(), true)
        noFullscreenResolution = PlayerHelper.getDefaultResolution(requireContext(), false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentPlayerBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)
        SoftwareKeyboardControllerCompat(view).hide()

        val playerData = requireArguments().parcelable<PlayerData>(IntentData.playerData)!!
        videoId = playerData.videoId
        playlistId = playerData.playlistId
        channelId = playerData.channelId

        // remember if playback already started once and only restart playback if that's the first run
        val createNewSession = !requireArguments().getBoolean(IntentData.alreadyStarted)
        requireArguments().putBoolean(IntentData.alreadyStarted, true)

        changeOrientationMode()

        playerLayoutOrientation = resources.configuration.orientation

        initializeTransitionLayout()
        initializeOnClickActions()

        if (PlayerHelper.autoFullscreenEnabled && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            setFullscreen()
        }

        chaptersViewModel.chaptersLiveData.observe(viewLifecycleOwner) {
            binding.player.setCurrentChapterName()
        }

        viewModel.segments.observe(viewLifecycleOwner) { segments ->
            binding.descriptionLayout.setSegments(segments)
            playerBinding.exoProgress.setSegments(segments)
            playerBinding.sbToggle.isVisible = segments.isNotEmpty()
            segments.firstOrNull { it.category == PlayerHelper.SPONSOR_HIGHLIGHT_CATEGORY }
                ?.let {
                    lifecycleScope.launch(Dispatchers.IO) { initializeHighlight(it) }
                }
        }

        val localDownloadVersion = runBlocking(Dispatchers.IO) {
            DatabaseHolder.Database.downloadDao().findById(videoId)
        }

        if (localDownloadVersion != null && createNewSession) {
            // the dialog must also be visible when in fullscreen, thus we need to use the activity's
            // fragment manager and not the one from [PlayerFragment]
            val fragmentManager = requireActivity().supportFragmentManager

            fragmentManager.setFragmentResultListener(
                PlayOfflineDialog.PLAY_OFFLINE_DIALOG_REQUEST_KEY, viewLifecycleOwner
            ) { _, bundle ->
                if (bundle.getBoolean(IntentData.isPlayingOffline)) {
                    // offline video playback started and thus the player fragment is no longer needed
                    killPlayerFragment()
                } else {
                    attachToPlayerService(playerData, true)
                }
            }

            val downloadInfo = DownloadHelper.extractDownloadInfoText(
                requireContext(),
                localDownloadVersion
            ).toTypedArray()

            PlayOfflineDialog().apply {
                arguments = bundleOf(
                    IntentData.videoId to videoId,
                    IntentData.videoTitle to localDownloadVersion.download.title,
                    IntentData.downloadInfo to downloadInfo
                )
            }.show(fragmentManager, null)
        } else {
            attachToPlayerService(playerData, createNewSession)
        }

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (commonPlayerViewModel.isFullscreen.value == true) unsetFullscreen()
                else {
                    binding.playerMotionLayout.setTransitionDuration(250)
                    binding.playerMotionLayout.transitionToEnd()
                    mainActivity.binding.mainMotionLayout.transitionToEnd()
                    mainActivity.requestOrientationChange()
                }
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                binding.playerMotionLayout.progress = backEvent.progress
            }

            override fun handleOnBackCancelled() {
                binding.playerMotionLayout.transitionToStart()
            }
        }
        setOnBackPressed(onBackPressedCallback)

        commonPlayerViewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) { isMiniPlayerVisible ->
            // re-add the callback on top of the back pressed dispatcher listeners stack,
            // so that it's the first one to become called while the full player is visible
            if (!isMiniPlayerVisible) {
                onBackPressedCallback.remove()
                setOnBackPressed(onBackPressedCallback)
            }

            // if the player is minimized, the fragment behind the player should handle the event
            onBackPressedCallback.isEnabled = isMiniPlayerVisible != true
        }
    }

    private fun attachToPlayerService(playerData: PlayerData, startNewSession: Boolean) {
        BackgroundHelper.startMediaService(
            requireContext(),
            VideoOnlinePlayerService::class.java,
            bundleOf(IntentData.playerData to playerData),
            sendStartCommand = startNewSession
        ) {
            if (_binding == null) {
                playerController.sendCustomCommand(
                    AbstractPlayerService.stopServiceCommand,
                    Bundle.EMPTY
                )
                playerController.release()
                return@startMediaService
            }

            playerController = it
            playerController.addListener(playerListener)

            if (!startNewSession) {
                val streams: Streams? =
                    playerController.mediaMetadata.extras?.parcelable(IntentData.streams)

                // reload the streams data and playback, metadata apparently no longer exists
                if (streams == null) {
                    playNextVideo(videoId)
                    return@startMediaService
                }

                this.streams = streams
                updatePlayerView()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeTransitionLayout() {
        mainActivity.binding.container.isVisible = true
        val mainMotionLayout = mainActivity.binding.mainMotionLayout
        mainMotionLayout.progress = 0F

        var transitionStartId = 0
        var transitionEndId = 0

        binding.playerMotionLayout.addTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int,
                progress: Float
            ) {
                if (_binding == null) return

                if (NavBarHelper.hasTabs()) {
                    mainMotionLayout.progress = abs(progress)
                }
                disableController()
                commonPlayerViewModel.setSheetExpand(false)
                transitionEndId = endId
                transitionStartId = startId
            }

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                if (_binding == null) return

                if (currentId == transitionStartId) {
                    commonPlayerViewModel.isMiniPlayerVisible.value = false
                    // re-enable captions
                    updateCurrentSubtitle(viewModel.currentSubtitle)
                    binding.player.useController = true
                    commonPlayerViewModel.setSheetExpand(true)
                    mainMotionLayout.progress = 0F
                    changeOrientationMode()
                } else if (currentId == transitionEndId) {
                    commonPlayerViewModel.isMiniPlayerVisible.value = true
                    // disable captions temporarily
                    updateCurrentSubtitle(null)
                    disableController()
                    commonPlayerViewModel.setSheetExpand(null)
                    binding.sbSkipBtn.isGone = true
                    if (NavBarHelper.hasTabs()) {
                        mainMotionLayout.progress = 1F
                    }
                    (activity as MainActivity).requestOrientationChange()
                }

                updateMaxSheetHeight()
            }
        })

        binding.playerMotionLayout
            .addSwipeUpListener {
                if (this::streams.isInitialized && PlayerHelper.fullscreenGesturesEnabled) {
                    binding.player.hideController()
                    setFullscreen()
                }
            }
            .addSwipeDownListener {
                if (commonPlayerViewModel.isMiniPlayerVisible.value == true) {
                    closeMiniPlayer()
                }
            }

        binding.playerMotionLayout.progress = 1F
        binding.playerMotionLayout.transitionToStart()

        val activity = requireActivity()
        if (PlayerHelper.pipEnabled) {
            PictureInPictureCompat.setPictureInPictureParams(activity, pipParams)
        }
    }

    private fun closeMiniPlayer() {
        binding
            .playerMotionLayout
            .animateDown(
                duration = 300L,
                dy = 500F,
                onEnd = ::killPlayerFragment
            )
    }

    // actions that don't depend on video information
    private fun initializeOnClickActions() {
        binding.closeImageView.setOnClickListener {
            killPlayerFragment()
        }
        playerBinding.closeImageButton.setOnClickListener {
            killPlayerFragment()
        }

        binding.playImageView.setOnClickListener {
            if (::playerController.isInitialized) playerController.togglePlayPauseState()
        }

        activity?.supportFragmentManager
            ?.setFragmentResultListener(
                CommentsSheet.HANDLE_LINK_REQUEST_KEY,
                viewLifecycleOwner
            ) { _, bundle ->
                bundle.getString(IntentData.url)?.let { handleLink(it) }
            }

        binding.commentsToggle.setOnClickListener {
            if (!this::streams.isInitialized) return@setOnClickListener
            // set the max height to not cover the currently playing video
            updateMaxSheetHeight()
            commentsViewModel.videoIdLiveData.updateIfChanged(videoId)
            CommentsSheet()
                .apply { arguments = bundleOf(IntentData.channelAvatar to streams.uploaderAvatar) }
                .show(childFragmentManager)
        }

        // FullScreen button trigger
        // hide fullscreen button if autorotation enabled
        playerBinding.fullscreen.setOnClickListener {
            toggleFullscreen()
        }

        // share button
        binding.relPlayerShare.setOnClickListener {
            if (!this::streams.isInitialized) return@setOnClickListener
            val bundle = bundleOf(
                IntentData.id to videoId,
                IntentData.shareObjectType to ShareObjectType.VIDEO,
                IntentData.shareData to ShareData(
                    currentVideo = streams.title,
                    currentPosition = playerController.currentPosition / 1000
                )
            )
            val newShareDialog = ShareDialog()
            newShareDialog.arguments = bundle
            newShareDialog.show(childFragmentManager, ShareDialog::class.java.name)
        }

        binding.relPlayerExternalPlayer.setOnClickListener {
            if (!this::streams.isInitialized || streams.hls == null) return@setOnClickListener

            val context = requireContext()
            lifecycleScope.launch {
                val hlsStream = withContext(Dispatchers.IO) {
                    ProxyHelper.rewriteUrlUsingProxyPreference(streams.hls!!).toUri()
                }
                IntentHelper.openWithExternalPlayer(
                    context,
                    hlsStream,
                    streams.title,
                    streams.uploader
                )
            }
        }

        binding.relPlayerBackground.setOnClickListener {
            // pause the current player
            if (::playerController.isInitialized) playerController.pause()

            // start the background mode
            playOnBackground()
        }

        binding.relPlayerPip.isVisible =
            PictureInPictureCompat.isPictureInPictureAvailable(requireContext())

        binding.relPlayerPip.setOnClickListener {
            PictureInPictureCompat.enterPictureInPictureMode(requireActivity(), pipParams)
        }

        binding.relatedRecView.layoutManager = LinearLayoutManager(
            context,
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                LinearLayoutManager.HORIZONTAL
            } else {
                LinearLayoutManager.VERTICAL
            },
            false
        )

        binding.relPlayerSave.setOnClickListener {
            if (!::streams.isInitialized) return@setOnClickListener

            AddToPlaylistDialog().apply {
                arguments = bundleOf(IntentData.videoInfo to streams.toStreamItem(videoId))
            }.show(childFragmentManager, AddToPlaylistDialog::class.java.name)
        }

        playerBinding.skipPrev.setOnClickListener {
            PlayingQueue.getPrev()?.let { prev -> playNextVideo(prev) }
        }

        playerBinding.skipNext.setOnClickListener {
            PlayingQueue.getNext()?.let { next -> playNextVideo(next) }
        }

        binding.relPlayerDownload.setOnClickListener {
            if (!this::streams.isInitialized) return@setOnClickListener

            DownloadHelper.startDownloadDialog(requireContext(), childFragmentManager, videoId)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            binding.relPlayerScreenshot.setOnClickListener {
                if (!this::streams.isInitialized) return@setOnClickListener
                val surfaceView =
                    binding.player.videoSurfaceView as? SurfaceView ?: return@setOnClickListener

                val bmp = Bitmap.createBitmap(
                    surfaceView.width,
                    surfaceView.height,
                    Bitmap.Config.ARGB_8888
                )

                PixelCopy.request(surfaceView, bmp, { _ ->
                    screenshotBitmap = bmp
                    val currentPosition =
                        playerController.currentPosition.toFloat() / 1000
                    openScreenshotFile.launch("${streams.title}-${currentPosition}.png")
                }, Handler(Looper.getMainLooper()))
            }
        } else {
            binding.relPlayerScreenshot.isGone = true
        }

        binding.playerChannel.setOnClickListener {
            if (!this::streams.isInitialized) return@setOnClickListener

            val activity = view?.context as MainActivity
            NavigationHelper.navigateChannel(requireContext(), streams.uploaderUrl)
            activity.binding.mainMotionLayout.transitionToEnd()
            binding.playerMotionLayout.transitionToEnd()
        }

        binding.descriptionLayout.handleLink = this::handleLink
    }

    private fun updateMaxSheetHeight() {
        val maxHeight = binding.root.height - binding.player.height
        commonPlayerViewModel.maxSheetHeightPx = maxHeight
        chaptersViewModel.maxSheetHeightPx = maxHeight
    }

    private fun playOnBackground() {
        val currentPosition =
            if (::playerController.isInitialized) playerController.currentPosition else 0

        BackgroundHelper.playOnBackground(
            requireContext(),
            videoId,
            currentPosition,
            playlistId,
            channelId,
            keepQueue = true,
            keepVideoPlayerAlive = true
        )
        killPlayerFragment()
        NavigationHelper.openAudioPlayerFragment(requireContext())
    }

    private fun updateFullscreenOrientation() {
        if (PlayerHelper.autoFullscreenEnabled || !this::streams.isInitialized) return

        val height = streams.videoStreams.firstOrNull()?.height
            ?: playerController.videoSize.height
        val width =
            streams.videoStreams.firstOrNull()?.width ?: playerController.videoSize.width

        mainActivity.requestedOrientation = PlayerHelper.getOrientation(width, height)
    }

    private fun setFullscreen() {
        // set status bar icon color to white
        windowInsetsControllerCompat.isAppearanceLightStatusBars = false

        commonPlayerViewModel.isFullscreen.value = true

        updateFullscreenOrientation()

        commonPlayerViewModel.setSheetExpand(null)

        updateResolution(true)
        openOrCloseFullscreenDialog(true)

        binding.player.updateMarginsByFullscreenMode()
    }

    @SuppressLint("SourceLockedOrientationActivity")
    fun unsetFullscreen() {
        if (activity == null || _binding == null) return

        commonPlayerViewModel.isFullscreen.value = false

        if (!PlayerHelper.autoFullscreenEnabled) {
            mainActivity.requestedOrientation = mainActivity.screenOrientationPref
        }

        openOrCloseFullscreenDialog(false)
        updateResolution(false)

        binding.player.updateMarginsByFullscreenMode()

        // set status bar icon color back to theme color after fullscreen dialog closed!
        windowInsetsControllerCompat.isAppearanceLightStatusBars =
            !ThemeHelper.isDarkMode(requireContext())
    }

    /**
     * Enable or disable fullscreen depending on the current state
     */
    fun toggleFullscreen() {
        binding.player.hideController()

        if (commonPlayerViewModel.isFullscreen.value == false) {
            // go to fullscreen mode
            setFullscreen()
        } else {
            // exit fullscreen mode
            unsetFullscreen()
        }
    }

    private fun openOrCloseFullscreenDialog(open: Boolean) {
        val playerView = binding.player
        (playerView.parent as ViewGroup).removeView(playerView)

        if (open) {
            fullscreenDialog.addContentView(
                binding.player,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            fullscreenDialog.show()
            playerView.currentWindow = fullscreenDialog.window
        } else {
            binding.playerMotionLayout.addView(playerView)
            playerView.currentWindow = null
            fullscreenDialog.dismiss()
        }

        WindowHelper.toggleFullscreen(fullscreenDialog.window!!, open)
    }

    override fun onPause() {
        // check whether the screen is on
        val isInteractive = requireContext().getSystemService<PowerManager>()!!.isInteractive

        // disable video stream since it's not needed when screen off
        if (!isInteractive) {
            // disable the autoplay countdown while the screen is off
            setAutoPlayCountdownEnabled(false)

            // disable loading the video track while screen is off
            setVideoTrackTypeDisabled(true)
        }

        // pause player if screen off and setting enabled
        if (!isInteractive && PlayerHelper.pausePlayerOnScreenOffEnabled) {
            playerController.pause()
        }

        super.onPause()
    }

    override fun onResume() {
        super.onResume()

        if (closedVideo) {
            closedVideo = false
        }

        // re-enable the autoplay countdown
        setAutoPlayCountdownEnabled(PlayerHelper.autoPlayCountdown)

        // re-enable and load video stream
        setVideoTrackTypeDisabled(false)
    }

    private fun setAutoPlayCountdownEnabled(enabled: Boolean) {
        if (!::playerController.isInitialized) return

        this.autoPlayCountdownEnabled = enabled

        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand, bundleOf(
                PlayerCommand.SET_AUTOPLAY_COUNTDOWN_ENABLED.name to enabled
            )
        )
    }

    private fun setVideoTrackTypeDisabled(disabled: Boolean) {
        if (!::playerController.isInitialized) return

        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand, bundleOf(
                PlayerCommand.SET_VIDEO_TRACK_TYPE_DISABLED.name to disabled
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()

        handler.removeCallbacksAndMessages(null)

        if (::playerController.isInitialized) {
            playerController.removeListener(playerListener)
            playerController.pause()

            playerController.sendCustomCommand(
                AbstractPlayerService.stopServiceCommand,
                Bundle.EMPTY
            )
            playerController.release()
        }

        if (PlayerHelper.pipEnabled) {
            // disable the auto PiP mode for SDK >= 32
            PictureInPictureCompat
                .setPictureInPictureParams(requireActivity(), pipParams)
        }

        runCatching {
            if (fullscreenDialog.isShowing) fullscreenDialog.dismiss()
        }

        runCatching {
            // unregister the receiver for player actions
            context?.unregisterReceiver(playerActionReceiver)
        }

        // restore the orientation that's used by the main activity
        (context as MainActivity).requestOrientationChange()

        _binding = null
    }

    /**
     * Manually kill the player fragment - call instead of using onDestroy directly
     */
    private fun killPlayerFragment() {
        binding.playerMotionLayout.transitionToEnd()

        commonPlayerViewModel.isMiniPlayerVisible.value = false

        if (commonPlayerViewModel.isFullscreen.value == true) {
            // wait for the mini player transition to finish
            // that guarantees that the navigation bar is shown properly
            // before we kill the player fragment
            binding.playerMotionLayout.addTransitionListener(object : TransitionAdapter() {
                override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                    super.onTransitionCompleted(motionLayout, currentId)

                    mainActivity.supportFragmentManager.commit {
                        remove(this@PlayerFragment)
                    }
                }
            })

            unsetFullscreen()
        } else {
            mainActivity.supportFragmentManager.commit {
                remove(this@PlayerFragment)
            }
        }
    }

    private fun checkForSegments() {
        if (!playerController.isPlaying || !PlayerHelper.sponsorBlockEnabled) return

        handler.postDelayed(this::checkForSegments, 100)
        if (!PlayerHelper.sponsorBlockEnabled || !viewModel.segments.value.isNullOrEmpty()) return

        playerController.checkForSegments(
            requireContext(),
            viewModel.segments.value.orEmpty(),
            viewModel.sponsorBlockConfig,
            // skipping is done by player service
            skipAutomaticallyIfEnabled = false
        )
            ?.let { segment ->
                if (commonPlayerViewModel.isMiniPlayerVisible.value == true) return@let

                binding.sbSkipBtn.isVisible = true
                binding.sbSkipBtn.setOnClickListener {
                    playerController.seekTo((segment.segmentStartAndEnd.second * 1000f).toLong())
                    segment.skipped = true
                }
                return
            }

        if (!playerController.isInSegment(viewModel.segments.value.orEmpty())) binding.sbSkipBtn.isGone =
            true
    }

    private fun setPlayerDefaults() {
        // reset the player view
        playerBinding.exoProgress.clearSegments()
        playerBinding.sbToggle.isGone = true

        // reset the comments to become reloaded later
        commentsViewModel.reset()

        // hide the button to skip SponsorBlock segments manually
        binding.sbSkipBtn.isGone = true

        // use the video's default audio track when starting playback
        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand, bundleOf(
                PlayerCommand.SET_AUDIO_ROLE_FLAGS.name to C.ROLE_FLAG_MAIN
            )
        )

        setAutoPlayCountdownEnabled(PlayerHelper.autoPlayCountdown)

        // set the default subtitle if available
        updateCurrentSubtitle(viewModel.currentSubtitle)

        // set media source and resolution in the beginning
        updateResolution(commonPlayerViewModel.isFullscreen.value == true)

        if (streams.category == Streams.CATEGORY_MUSIC) {
            playerController.setPlaybackSpeed(1f)
        }
    }

    /**
     * Manually skip to another video.
     */
    fun playNextVideo(nextId: String) {
        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            bundleOf(PlayerCommand.PLAY_VIDEO_BY_ID.name to nextId)
        )

        // close comment bottom sheet if opened for next video
        activity?.supportFragmentManager?.fragments?.filterIsInstance<CommentsSheet>()
            ?.firstOrNull()?.dismiss()
    }

    @SuppressLint("SetTextI18n")
    private fun updatePlayerView() {
        if (PreferenceHelper.getBoolean(PreferenceKeys.AUTO_FULLSCREEN_SHORTS, false) &&
            isShort && binding.playerMotionLayout.progress == 0f
        ) {
            setFullscreen()
        }

        binding.player.apply {
            useController = false
            player = playerController
        }

        // initialize the player view actions
        binding.player.initialize(
            doubleTapOverlayBinding,
            playerGestureControlsViewBinding,
            chaptersViewModel
        )
        binding.player.initPlayerOptions(
            viewModel,
            commonPlayerViewModel,
            viewLifecycleOwner,
            this
        )

        if (binding.playerMotionLayout.progress != 1.0f) {
            // show controllers when not in picture in picture mode
            val inPipMode = PlayerHelper.pipEnabled &&
                    PictureInPictureCompat.isInPictureInPictureMode(requireActivity())
            if (!inPipMode) {
                binding.player.useController = true
            }
        }

        viewModel.isOrientationChangeInProgress = false

        binding.descriptionLayout.setStreams(streams)

        binding.apply {
            ImageHelper.loadImage(streams.uploaderAvatar, binding.playerChannelImage, true)
            playerChannelName.text = streams.uploader
            titleTextView.text = streams.title
            playerChannelSubCount.text = context?.getString(
                R.string.subscribers,
                streams.uploaderSubscriberCount.formatShort()
            )
            player.isLive = streams.isLive
            relPlayerDownload.isVisible = !streams.isLive
        }
        playerBinding.exoTitle.text = streams.title

        // init the chapters recyclerview
        chaptersViewModel.chaptersLiveData.postValue(streams.chapters)

        if (PlayerHelper.relatedStreamsEnabled) {
            val relatedLayoutManager = binding.relatedRecView.layoutManager as LinearLayoutManager
            binding.relatedRecView.adapter = VideosAdapter(
                forceMode = if (relatedLayoutManager.orientation == LinearLayoutManager.HORIZONTAL) {
                    VideosAdapter.Companion.LayoutMode.RELATED_COLUMN
                } else {
                    VideosAdapter.Companion.LayoutMode.TRENDING_ROW
                }
            ).also { adapter ->
                adapter.submitList(streams.relatedStreams.filter { !it.title.isNullOrBlank() })
            }
        }

        // update the subscribed state
        binding.playerSubscribe.setupSubscriptionButton(
            this.streams.uploaderUrl.toID(),
            this.streams.uploader
        )

        // seekbar preview setup
        playerBinding.seekbarPreview.isGone = true
        seekBarPreviewListener?.let { playerBinding.exoProgress.removeListener(it) }
        seekBarPreviewListener = createSeekbarPreviewListener().also {
            playerBinding.exoProgress.addSeekBarListener(it)
        }
    }

    private fun showAutoPlayCountdown() {
        if (!PlayingQueue.hasNext()) return

        disableController()
        binding.autoplayCountdown.setHideSelfListener {
            // could fail if the video already got closed before
            runCatching {
                binding.autoplayCountdown.isGone = true
                binding.player.useController = true
            }
        }
        binding.autoplayCountdown.startCountdown {
            PlayingQueue.getNext()?.let { playNextVideo(it) }
        }
    }

    /**
     * Handle a link clicked in the description
     */
    private fun handleLink(link: String) {
        // get video id if the link is a valid youtube video link
        val uri = link.toUri()
        val videoId = TextUtils.getVideoIdFromUri(uri)

        if (videoId.isNullOrEmpty()) {
            // not a YouTube video link, thus handle normally
            val intent = Intent(Intent.ACTION_VIEW, uri)

            // start PiP mode if enabled
            onUserLeaveHint()
            startActivity(intent)

            return
        }

        // check if the video is the current video and has a valid time
        if (videoId == this.videoId) {
            // try finding the time stamp of the url and seek to it if found
            uri.getQueryParameter("t")?.toTimeInSeconds()?.let {
                playerController.seekTo(it * 1000)
            }
        } else {
            // YouTube video link without time or not the current video, thus load in player
            playNextVideo(videoId)
        }
    }

    private fun updatePlayPauseButton() {
        val playPauseAction = PlayerHelper.getPlayPauseActionIcon(playerController)
        binding.playImageView.setImageResource(playPauseAction)
    }

    private suspend fun initializeHighlight(highlight: Segment) {
        val frameReceiver = OnlineTimeFrameReceiver(requireContext(), streams.previewFrames)
        val highlightStart = highlight.segmentStartAndEnd.first.toLong()
        val frame = withContext(Dispatchers.IO) {
            frameReceiver.getFrameAtTime(highlightStart * 1000)
        }
        val highlightChapter = ChapterSegment(
            title = getString(R.string.chapters_videoHighlight),
            start = highlightStart,
            highlightDrawable = frame?.toDrawable(requireContext().resources)
        )
        chaptersViewModel.chaptersLiveData.postValue(
            chaptersViewModel.chapters.plus(highlightChapter).sortedBy { it.start }
        )
    }

    /**
     * Get all available player resolutions
     */
    private fun getAvailableResolutions(): List<VideoResolution> {
        val resolutions = playerController.currentTracks.groups.asSequence()
            .flatMap { group ->
                (0 until group.length).map {
                    group.getTrackFormat(it).height
                }
            }
            .filter { it > 0 }
            .map { VideoResolution("${it}p", it) }
            .toSortedSet(compareByDescending { it.resolution })

        resolutions.add(VideoResolution(getString(R.string.auto_quality), Int.MAX_VALUE))
        return resolutions.toList()
    }

    private fun setPlayerResolution(resolution: Int, isSelectedByUser: Boolean = false) {
        if (!::playerController.isInitialized) return

        val transformedResolution = if (!isSelectedByUser && isShort) {
            ceil(resolution * 16.0 / 9.0).toInt()
        } else {
            resolution
        }

        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand, bundleOf(
                PlayerCommand.SET_RESOLUTION.name to transformedResolution
            )
        )

        binding.player.selectedResolution = resolution
    }

    private fun updateResolution(isFullscreen: Boolean) {
        if (!isFullscreen && noFullscreenResolution != null) {
            setPlayerResolution(noFullscreenResolution!!)
        } else if (fullscreenResolution != null) {
            setPlayerResolution(fullscreenResolution!!)
        } else {
            setPlayerResolution(Int.MAX_VALUE)
        }
    }

    /**
     * Use the sensor mode if auto fullscreen is enabled
     */
    @SuppressLint("SourceLockedOrientationActivity")
    private fun changeOrientationMode() {
        if (PlayerHelper.autoFullscreenEnabled) {
            // enable auto rotation
            mainActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            // go to portrait mode
            mainActivity.requestedOrientation =
                (requireActivity() as BaseActivity).screenOrientationPref
        }
    }

    override fun onCaptionsClicked() {
        if (!this@PlayerFragment::streams.isInitialized || streams.subtitles.isEmpty()) {
            Toast.makeText(context, R.string.no_subtitles_available, Toast.LENGTH_SHORT).show()
            return
        }

        val subtitles = listOf(Subtitle(name = getString(R.string.none))).plus(streams.subtitles)

        BaseBottomSheet()
            .setSimpleItems(
                subtitles.map { it.getDisplayName(requireContext()) },
                preselectedItem = subtitles.firstOrNull { it == viewModel.currentSubtitle }
                    ?.getDisplayName(requireContext()) ?: getString(R.string.none)
            ) { index ->
                val subtitle = subtitles.getOrNull(index) ?: return@setSimpleItems
                updateCurrentSubtitle(subtitle)
                viewModel.currentSubtitle = subtitle
            }
            .show(childFragmentManager)
    }

    override fun onQualityClicked() {
        // get the available resolutions
        val resolutions = getAvailableResolutions()

        // Dialog for quality selection
        BaseBottomSheet()
            .setSimpleItems(
                resolutions.map(VideoResolution::name),
                preselectedItem = resolutions.firstOrNull {
                    it.resolution == binding.player.selectedResolution
                }?.name ?: getString(R.string.auto_quality)
            ) { which ->
                val newResolution = resolutions[which].resolution
                setPlayerResolution(newResolution, true)

                // save the selected resolution to update on fullscreen change
                if (noFullscreenResolution != null && commonPlayerViewModel.isFullscreen.value != true) {
                    noFullscreenResolution = newResolution
                } else {
                    fullscreenResolution = newResolution
                }
            }
            .show(childFragmentManager)
    }

    override fun onAudioStreamClicked() {
        val context = requireContext()
        val audioLanguagesAndRoleFlags = PlayerHelper.getAudioLanguagesAndRoleFlagsFromTrackGroups(
            playerController.currentTracks.groups,
            false
        )
        val audioLanguages = audioLanguagesAndRoleFlags.map {
            PlayerHelper.getAudioTrackNameFromFormat(context, it)
        }
        val baseBottomSheet = BaseBottomSheet()

        if (audioLanguagesAndRoleFlags.isEmpty() || (audioLanguagesAndRoleFlags.size == 1 &&
                    audioLanguagesAndRoleFlags[0].first == null &&
                    !PlayerHelper.haveAudioTrackRoleFlagSet(
                        audioLanguagesAndRoleFlags[0].second
                    ))
        ) {
            // Regardless of audio format or quality, if there is only one audio stream which has
            // no language and no role flags, it should mean that there is only a single audio
            // track which has no language or track type set in the video played
            // Consider it as the default audio track (or unknown)
            baseBottomSheet.setSimpleItems(
                listOf(getString(R.string.default_or_unknown_audio_track)),
                preselectedItem = getString(R.string.default_or_unknown_audio_track),
                listener = null
            )
        } else {
            baseBottomSheet.setSimpleItems(
                audioLanguages,
                preselectedItem = selectedAudioLanguageAndRoleFlags?.let {
                    PlayerHelper.getAudioTrackNameFromFormat(context, it)
                },
            ) { index ->
                val selectedAudioFormat = audioLanguagesAndRoleFlags[index]
                playerController.sendCustomCommand(
                    AbstractPlayerService.runPlayerActionCommand, bundleOf(
                        PlayerCommand.SET_AUDIO_ROLE_FLAGS.name to selectedAudioFormat.second
                    )
                )
                playerController.sendCustomCommand(
                    AbstractPlayerService.runPlayerActionCommand, bundleOf(
                        PlayerCommand.SET_AUDIO_LANGUAGE.name to selectedAudioFormat.first
                    )
                )
                selectedAudioLanguageAndRoleFlags = selectedAudioFormat
            }
        }

        baseBottomSheet.show(childFragmentManager)
    }

    override fun exitFullscreen() {
        unsetFullscreen()
    }

    override fun onStatsClicked() {
        if (!this::streams.isInitialized) return

        val videoStats = PlayerHelper.getVideoStats(playerController.currentTracks, videoId)
        StatsSheet()
            .apply { arguments = bundleOf(IntentData.videoStats to videoStats) }
            .show(childFragmentManager)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            // hide and disable exoPlayer controls
            disableController()

            updateCurrentSubtitle(null)

            openOrCloseFullscreenDialog(true)
            pipActivity = activity
        } else {
            binding.player.useController = true

            // close button got clicked in PiP mode
            // pause the video and keep the app alive
            if (lifecycle.currentState == Lifecycle.State.CREATED) {
                playerController.pause()
                closedVideo = true
            }

            updateCurrentSubtitle(viewModel.currentSubtitle)

            // unset fullscreen if it's not been enabled before the start of PiP
            if (commonPlayerViewModel.isFullscreen.value != true) {
                openOrCloseFullscreenDialog(false)
            }
        }
    }

    private fun updateCurrentSubtitle(subtitle: Subtitle?) {
        if (!::playerController.isInitialized) return

        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand, bundleOf(
                PlayerCommand.SET_SUBTITLE.name to subtitle
            )
        )
    }

    fun onUserLeaveHint() {
        if (shouldStartPiP()) {
            PictureInPictureCompat.enterPictureInPictureMode(requireActivity(), pipParams)
        } else if (PlayerHelper.pauseOnQuit) {
            playerController.pause()
        }
    }

    private val pipParams: PictureInPictureParamsCompat
        get() = run {
            val isPlaying = ::playerController.isInitialized && playerController.isPlaying

            PictureInPictureParamsCompat.Builder()
                .setActions(
                    PlayerHelper.getPiPModeActions(
                        requireActivity(),
                        isPlaying
                    )
                )
                .setAutoEnterEnabled(PlayerHelper.pipEnabled && isPlaying)
                .apply {
                    if (isPlaying) {
                        setAspectRatio(playerController.videoSize)
                    }
                }
                .build()
        }

    private fun createSeekbarPreviewListener(): SeekbarPreviewListener {
        return SeekbarPreviewListener(
            OnlineTimeFrameReceiver(requireContext(), streams.previewFrames),
            playerBinding,
            streams.duration * 1000
        )
    }

    /**
     * Detect whether PiP is supported and enabled
     */
    private fun shouldUsePip(): Boolean {
        return PictureInPictureCompat.isPictureInPictureAvailable(requireContext()) && PlayerHelper.pipEnabled
    }

    private fun shouldStartPiP(): Boolean {
        return shouldUsePip() && ::playerController.isInitialized && playerController.isPlaying &&
                !BackgroundHelper.isBackgroundServiceRunning(requireContext())
    }

    /**
     * Check if the activity needs to be recreated due to an orientation change
     * If true, the activity will be automatically restarted
     */
    private fun restartActivityIfNeeded() {
        if (mainActivity.screenOrientationPref in lockedOrientations || viewModel.isOrientationChangeInProgress) return

        val orientation = resources.configuration.orientation
        if (commonPlayerViewModel.isFullscreen.value != true && orientation != playerLayoutOrientation) {
            // remember the current position before recreating the activity
            playerLayoutOrientation = orientation

            viewModel.isOrientationChangeInProgress = true

            if (::playerController.isInitialized) playerController.release()

            activity?.recreate()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (_binding == null ||
            // If in PiP mode, orientation is given as landscape.
            PictureInPictureCompat.isInPictureInPictureMode(requireActivity())
        ) {
            return
        }

        if (PlayerHelper.autoFullscreenEnabled) {
            when (newConfig.orientation) {
                // go to fullscreen mode
                Configuration.ORIENTATION_LANDSCAPE -> setFullscreen()
                // exit fullscreen if not landscape
                else -> unsetFullscreen()
            }
        }

        restartActivityIfNeeded()
    }

    private fun disableController() {
        binding.player.useController = false
        binding.player.hideController()
    }

    fun onKeyUp(keyCode: Int): Boolean {
        return _binding?.player?.onKeyBoardAction(keyCode) ?: false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
