package com.github.libretube

import com.github.libretube.obj.*
import retrofit2.http.*

interface PipedApi {
    @GET("trending")
    suspend fun getTrending(@Query("region") region: String): List<StreamItem>

    @GET("streams/{videoId}")
    suspend fun getStreams(@Path("videoId") videoId: String): Streams

    @GET("search")
    suspend fun getSearchResults(
        @Query("q") searchQuery: String,
        @Query("filter") filter: String
    ): SearchResult

    @GET("suggestions")
    suspend fun getSuggestions(@Query("query") query: String): List<String>

    @GET("channel/{channelId}")
    suspend fun getChannel(@Path("channelId") channelId: String): Channel

    @GET("nextpage/channel/{channelId}")
    suspend fun getChannelNextPage(@Path("channelId") channelId: String, @Query("nextpage") nextPage: String): Channel

    @GET("playlists/{playlistId}")
    suspend fun getPlaylist(@Path("playlistId") playlistId: String): Playlist

    @GET("nextpage/playlists/{playlistId}")
    suspend fun getPlaylistNextPage(@Path("playlistId") playlistId: String, @Query("nextpage") nextPage: String): Playlist

    @POST("login")
    suspend fun login(@Body login: Login): Token

    @POST("register")
    suspend fun register(@Body login: Login): Token

    @GET("feed")
    suspend fun getFeed(@Query("authToken") token: String?): List<StreamItem>

    @GET("subscribed")
    suspend fun isSubscribed(@Query("channelId") channelId: String, @Header("Authorization") token: String): Subscribed

    @GET("subscriptions")
    suspend fun subscriptions(@Header("Authorization") token: String): List<Subscription>

    @POST("subscribe")
    suspend fun subscribe(@Header("Authorization") token: String, @Body subscribe: Subscribe): Message

    @POST("unsubscribe")
    suspend fun unsubscribe(@Header("Authorization") token: String, @Body subscribe: Subscribe): Message

    @POST("import")
    suspend fun importSubscriptions(@Header("Authorization") token: String, @Body channels: List<String>): Message

    //only for fetching servers list
    @GET
    suspend fun getInstances(@Url url: String): List<Instances>



}