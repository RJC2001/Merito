package com.rjc.merito.network

import retrofit2.http.GET
import retrofit2.http.Query

interface RetrofitApi {
    @GET("photos")
    suspend fun listPhotos(
        @Query("page") page: Int = 1,
        @Query("per_page") per_page: Int = 30
    ): List<ApiPhoto>
}

data class ApiPhoto(
    val id: String,
    val urls: Urls?,
    val description: String?,
    val alt_description: String?
)

data class Urls(
    val thumb: String?,
    val small: String?,
    val regular: String?,
    val full: String?
)
