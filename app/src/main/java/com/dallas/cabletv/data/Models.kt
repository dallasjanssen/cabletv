package com.dallas.cabletv.data

data class LiveChannel(
    val id: String,
    val number: Int,
    val name: String,
    val logo: String?,
    val group: String,
    val streamUrl: String,
    val epgId: String?
)

data class Program(
    val title: String,
    val start: Long,
    val end: Long
)

data class NowNext(
    val now: Program?,
    val next: Program?
)

data class VodItem(
    val id: String,
    val name: String,
    val year: Int?,
    val overview: String?,
    val posterUrl: String,
    val streamUrl: String,
    val type: String
)

data class Settings(
    val xtreamServer: String = "",
    val xtreamUser: String = "",
    val xtreamPass: String = "",
    val m3uUrl: String = "",
    val jellyfinServer: String = "",
    val jellyfinToken: String = "",
    val jellyfinUserId: String = ""
) {
    val hasXtream: Boolean
        get() = xtreamServer.isNotBlank() && xtreamUser.isNotBlank() && xtreamPass.isNotBlank()

    val hasLiveSource: Boolean
        get() = hasXtream || m3uUrl.isNotBlank()

    val hasJellyfin: Boolean
        get() = jellyfinServer.isNotBlank() && jellyfinToken.isNotBlank() && jellyfinUserId.isNotBlank()
}
