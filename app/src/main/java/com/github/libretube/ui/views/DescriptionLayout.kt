package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.text.util.Linkify
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.text.parseAsHtml
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.github.libretube.R
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.Streams
import com.github.libretube.databinding.DescriptionLayoutBinding
import com.github.libretube.enums.SbSkipOptions
import com.github.libretube.extensions.formatShort
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.ui.activities.VideoTagsAdapter
import com.github.libretube.util.HtmlParser
import com.github.libretube.util.LinkHandler
import com.github.libretube.util.TextUtils
import java.util.Locale

class DescriptionLayout(
    context: Context,
    attributeSet: AttributeSet?
) : LinearLayout(context, attributeSet) {
    val binding = DescriptionLayoutBinding.inflate(LayoutInflater.from(context), this, true)
    private var streams: Streams? = null
    var handleLink: (link: String) -> Unit = {}

    private val videoTagsAdapter = VideoTagsAdapter()

    init {
        binding.playerTitleLayout.setOnClickListener {
            toggleDescription()
        }
        binding.playerTitleLayout.setOnLongClickListener {
            streams?.title?.let { ClipboardHelper.save(context, text = it) }
            true
        }

        binding.tagsRecycler.adapter = videoTagsAdapter
    }

    fun setSegments(segments: List<Segment>) {
        if (PlayerHelper.getSponsorBlockCategories()[SB_SPONSOR_CATEGORY] == SbSkipOptions.OFF) {
            // only show the badge if the user requested to show sponsors
           return
        }

        val segment = segments.filter { it.actionType == Segment.TYPE_FULL }.firstNotNullOfOrNull {
            when (it.category) {
                "sponsor" -> context.getString(R.string.category_sponsor)
                "exclusive_access" -> context.getString(R.string.category_exclusive_access)
                else -> null
            }
        }
        binding.playerSponsorBadge.isVisible = segment != null
        binding.playerSponsorBadge.text = segment
    }

    @SuppressLint("SetTextI18n")
    fun setStreams(streams: Streams) {
        this.streams = streams

        val views = streams.views.formatShort()
        val date = TextUtils.formatRelativeDate(context, streams.uploaded ?: -1L)
        binding.run {
            playerViewsInfo.text = context.getString(R.string.normal_views, views, TextUtils.SEPARATOR + date)

            textLike.text = streams.likes.formatShort()
            textDislike.isVisible = streams.dislikes >= 0
            textDislike.text = streams.dislikes.formatShort()

            playerTitle.text = streams.title
            playerDescription.text = streams.description

            metaInfo.isVisible = streams.metaInfo.isNotEmpty()
            // generate a meta info text with clickable links using html
            val metaInfoText = streams.metaInfo.joinToString("\n\n") { info ->
                val text = info.description.takeIf { it.isNotBlank() } ?: info.title
                val links = info.urls.mapIndexed { index, url ->
                    "<a href=\"$url\">${info.urlTexts.getOrNull(index).orEmpty()}</a>"
                }.joinToString(", ")
                "$text $links"
            }
            metaInfo.text = metaInfoText.parseAsHtml()

            val visibility = when (streams.visibility) {
                "public" -> context?.getString(R.string.visibility_public)
                "unlisted" -> context?.getString(R.string.visibility_unlisted)
                // currently no other visibility could be returned, might change in the future however
                else -> streams.visibility.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
            }.orEmpty()
            additionalVideoInfo.text =
                "${context?.getString(R.string.category)}: ${streams.category}\n" +
                "${context?.getString(R.string.license)}: ${streams.license}\n" +
                "${context?.getString(R.string.visibility)}: $visibility"

            if (streams.tags.isNotEmpty()) {
                videoTagsAdapter.submitList(streams.tags)
            }
            binding.tagsRecycler.isVisible = streams.tags.isNotEmpty()

            setupDescription(streams.description)
        }
    }

    /**
     * Set up the description text with video links and timestamps
     */
    private fun setupDescription(description: String) {
        val descTextView = binding.playerDescription
        // detect whether the description is html formatted
        if (description.contains("<") && description.contains(">")) {
            descTextView.movementMethod = LinkMovementMethodCompat.getInstance()
            descTextView.text = description.replace("</a>", "</a> ")
                .parseAsHtml(tagHandler = HtmlParser(LinkHandler(handleLink)))
        } else {
            // Links can be present as plain text
            descTextView.autoLinkMask = Linkify.WEB_URLS
            descTextView.text = description
        }
    }

    private fun toggleDescription() {
        val streams = streams ?: return

        val isNewStateExpanded = binding.descLinLayout.isGone
        if (!isNewStateExpanded) {
            // show a short version of the view count and date
            val formattedDate = TextUtils.formatRelativeDate(context, streams.uploaded ?: -1L)
            binding.playerViewsInfo.text = context.getString(R.string.normal_views, streams.views.formatShort(),  TextUtils.SEPARATOR + formattedDate)

            // limit the title height to two lines
            binding.playerTitle.maxLines = 2
        } else {
            // show the full view count and upload date
            val formattedDate = streams.uploadTimestamp?.let { TextUtils.localizeInstant(it) }.orEmpty()
            binding.playerViewsInfo.text = context.getString(R.string.normal_views, "%,d".format(streams.views),  TextUtils.SEPARATOR + formattedDate)

            // show the whole title
            binding.playerTitle.maxLines = Int.MAX_VALUE
        }

        binding.playerDescriptionArrow.animate()
            .rotation(if (isNewStateExpanded) 180F else 0F)
            .setDuration(ANIMATION_DURATION)
            .start()

        binding.playerDescription.isVisible = isNewStateExpanded
        binding.descLinLayout.isVisible = isNewStateExpanded
    }

    companion object {
        private const val ANIMATION_DURATION = 250L
        private const val SB_SPONSOR_CATEGORY = "sponsor_category"
    }
}
