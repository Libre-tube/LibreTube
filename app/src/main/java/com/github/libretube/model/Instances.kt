package com.github.libretube.model

data class Instances(
    var name: String? = null,
    var api_url: String? = null,
    var locations: String? = null,
    var version: String? = null,
    var up_to_date: Boolean? = null,
    var cdn: Boolean? = null,
    var registered: Long? = null,
    var last_checked: Long? = null
)
