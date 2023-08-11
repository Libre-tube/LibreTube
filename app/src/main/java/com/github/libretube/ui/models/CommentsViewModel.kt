package com.github.libretube.ui.models

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.obj.CommentsPage
import com.github.libretube.extensions.TAG
import com.github.libretube.ui.extensions.filterNonEmptyComments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CommentsViewModel : ViewModel() {
    val commentsPage = MutableLiveData<CommentsPage?>()
    val commentSheetExpand = MutableLiveData<Boolean?>()

    var videoId: String? = null
    private var nextPage: String? = null
    private var isLoading = false
    var maxHeight = 0
    var currentCommentsPosition = 0
    var commentsSheetDismiss: (() -> Unit)? = null
    var handleLink: ((url: String) -> Unit)? = null

    fun setCommentSheetExpand(value: Boolean?) {
        if (commentSheetExpand.value != value) {
            commentSheetExpand.value = value
        }
    }

    fun fetchComments() {
        videoId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            val response = try {
                RetrofitInstance.api.getComments(videoId!!)
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                return@launch
            }
            nextPage = response.nextpage
            response.comments = response.comments.filterNonEmptyComments()
            commentsPage.postValue(response)
            isLoading = false
        }
    }

    fun fetchNextComments() {
        if (isLoading || nextPage == null || videoId == null) return
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            val response = try {
                RetrofitInstance.api.getCommentsNextPage(videoId!!, nextPage!!)
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                return@launch
            }

            val updatedPage = commentsPage.value?.apply {
                val combinedComments = this.comments + response.comments.filterNonEmptyComments()

                // filtering out the comments that have the same id (duplicated)
                this.comments = combinedComments.groupBy { it.commentId }
                    .filter { it.value.size == 1 }
                    .flatMap { it.value }
            }

            nextPage = response.nextpage
            commentsPage.postValue(updatedPage)
            isLoading = false
        }
    }

    fun reset() {
        isLoading = false
        nextPage = null
        commentsPage.value = null
        videoId = null
        setCommentSheetExpand(null)
        currentCommentsPosition = 0
    }
}
