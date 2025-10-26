package com.rjc.merito.model

data class Photo(
    val id: String,
    val title: String,
    val thumbUrl: String,
    val fullUrl: String,
    val ownerUid: String? = null,
    val searchText: String = ""
)
