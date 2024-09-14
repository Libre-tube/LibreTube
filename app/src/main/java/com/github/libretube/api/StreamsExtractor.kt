package com.github.libretube.api

import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.MetaInfo
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.PreviewFrames
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.api.obj.Subtitle
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.util.NewPipeDownloaderImpl
import kotlinx.datetime.toKotlinInstant
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream

fun VideoStream.toPipedStream(): PipedStream = PipedStream(
    url = content,
    codec = codec,
    format = format.toString(),
    height = height,
    width = width,
    quality = getResolution(),
    mimeType = format?.mimeType,
    bitrate = bitrate,
    initStart = initStart,
    initEnd = initEnd,
    indexStart = indexStart,
    indexEnd = indexEnd,
    fps = fps,
    contentLength = itagItem?.contentLength ?: 0L
)

object StreamsExtractor {
//    val npe by lazy {
//        NewPipe.getService(ServiceList.YouTube.serviceId)
//    }

    init {
        NewPipe.init(NewPipeDownloaderImpl())
    }

    suspend fun extractStreams(videoId: String): Streams {
        if (!PlayerHelper.disablePipedProxy || !PlayerHelper.localStreamExtraction) {
            return RetrofitInstance.api.getStreams(videoId)
        }

        val resp = StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
        return Streams(
            title = resp.name,
            description = resp.description.content,
            uploader = resp.uploaderName,
            uploaderAvatar = resp.uploaderAvatars.maxBy { it.height }.url,
            uploaderUrl = resp.uploaderUrl.replace("https://www.youtube.com", ""),
            uploaderVerified = resp.isUploaderVerified,
            uploaderSubscriberCount = resp.uploaderSubscriberCount,
            category = resp.category,
            views = resp.viewCount,
            likes = resp.likeCount,
            dislikes = if (PlayerHelper.localRYD) runCatching {
                RetrofitInstance.externalApi.getVotes(videoId).dislikes
            }.getOrElse { -1 } else -1,
            license = resp.licence,
            hls = resp.hlsUrl,
            dash = resp.dashMpdUrl,
            tags = resp.tags,
            metaInfo = resp.metaInfo.map {
                MetaInfo(
                    it.title,
                    it.content.content,
                    it.urls.map { url -> url.toString() },
                    it.urlTexts
                )
            },
            visibility = resp.privacy.name.lowercase(),
            duration = resp.duration,
            uploadTimestamp = resp.uploadDate.offsetDateTime().toInstant().toKotlinInstant(),
            uploaded = resp.uploadDate.offsetDateTime().toEpochSecond() * 1000,
            thumbnailUrl = resp.thumbnails.maxBy { it.height }.url,
            relatedStreams = resp.relatedItems.filterIsInstance<StreamInfoItem>().map {
                StreamItem(
                    it.url.replace("https://www.youtube.com", ""),
                    StreamItem.TYPE_STREAM,
                    it.name,
                    it.thumbnails.maxBy { image -> image.height }.url,
                    it.uploaderName,
                    it.uploaderUrl.replace("https://www.youtube.com", ""),
                    it.uploaderAvatars.maxBy { image -> image.height }.url,
                    it.textualUploadDate,
                    it.duration,
                    it.viewCount,
                    it.isUploaderVerified,
                    it.uploadDate?.offsetDateTime()?.toEpochSecond()?.times(1000) ?: 0L,
                    it.shortDescription,
                    it.isShortFormContent,
                )
            },
            chapters = resp.streamSegments.map {
                ChapterSegment(
                    title = it.title,
                    image = it.previewUrl.orEmpty(),
                    start = it.startTimeSeconds.toLong()
                )
            },
            audioStreams = resp.audioStreams.map {
                PipedStream(
                    url = it.content,
                    format = it.format?.toString(),
                    quality = "${it.averageBitrate} bits",
                    bitrate = it.bitrate,
                    mimeType = it.format?.mimeType,
                    initStart = it.initStart,
                    initEnd = it.initEnd,
                    indexStart = it.indexStart,
                    indexEnd = it.indexEnd,
                    contentLength = it.itagItem?.contentLength ?: 0L,
                    codec = it.codec,
                    audioTrackId = it.audioTrackId,
                    audioTrackName = it.audioTrackName,
                    audioTrackLocale = it.audioLocale?.toLanguageTag(),
                    audioTrackType = it.audioTrackType?.name,
                    videoOnly = false
                )
            },
            videoStreams = resp.videoOnlyStreams.map {
                it.toPipedStream().copy(videoOnly = true)
            } + resp.videoStreams.map {
                it.toPipedStream().copy(videoOnly = false)
            },
            previewFrames = resp.previewFrames.map {
                PreviewFrames(
                    it.urls,
                    it.frameWidth,
                    it.frameHeight,
                    it.totalCount,
                    it.durationPerFrame.toLong(),
                    it.framesPerPageX,
                    it.framesPerPageY
                )
            },
            subtitles = resp.subtitles.map {
                Subtitle(
                    it.content,
                    it.format?.mimeType,
                    it.displayLanguageName,
                    it.languageTag,
                    it.isAutoGenerated
                )
            }
        )
    }
}