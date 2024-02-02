package com.github.libretube.ui.models

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.paging.Pager
import androidx.paging.PagingConfig
import com.github.libretube.ui.models.sources.CommentPagingSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest

class CommentsViewModel : ViewModel() {
    val videoIdLiveData = MutableLiveData<String>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val commentsFlow = videoIdLiveData.asFlow().flatMapLatest {
        Pager(
            PagingConfig(pageSize = 20, enablePlaceholders = false),
            pagingSourceFactory = { CommentPagingSource(it) }
        ).flow
    }
    val commentSheetExpand = MutableLiveData<Boolean?>()

    var channelAvatar: String? = null
    var handleLink: ((url: String) -> Unit)? = null

    var currentCommentsPosition = 0
    var commentsSheetDismiss: (() -> Unit)? = null

    fun setCommentSheetExpand(value: Boolean?) {
        if (commentSheetExpand.value != value) {
            commentSheetExpand.value = value
        }
    }

    fun reset() {
        setCommentSheetExpand(null)
        currentCommentsPosition = 0
    }
}
