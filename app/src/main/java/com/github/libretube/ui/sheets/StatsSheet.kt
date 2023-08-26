package com.github.libretube.ui.sheets

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.media3.exoplayer.ExoPlayer
import com.github.libretube.databinding.DialogStatsBinding
import com.github.libretube.util.TextUtils

class StatsSheet(
    private val player: ExoPlayer,
    private val videoId: String
) : ExpandedBottomSheet() {
    private var _binding: DialogStatsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogStatsBinding.inflate(layoutInflater)
        return binding.root
    }

    @SuppressLint("SetTextI18n")
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding

        binding.videoId.setText(videoId)
        binding.videoInfo.setText(
            "${player.videoFormat?.codecs.orEmpty()} ${
                TextUtils.formatBitrate(
                    player.videoFormat?.bitrate
                )
            }"
        )
        binding.audioInfo.setText(
            "${player.audioFormat?.codecs.orEmpty()} ${
                TextUtils.formatBitrate(
                    player.audioFormat?.bitrate
                )
            }"
        )
        binding.videoQuality.setText(
            "${player.videoFormat?.width}x${player.videoFormat?.height} ${player.videoFormat?.frameRate?.toInt()}fps"
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
