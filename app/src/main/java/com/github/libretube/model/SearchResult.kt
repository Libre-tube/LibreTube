package com.github.libretube.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchResult(
    val items: List<SearchItem>? = listOf(),
    val nextpage: String? = "",
    val suggestion: String? = "",
    val corrected: Boolean? = null
)
