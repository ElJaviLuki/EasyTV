package com.eljaviluki.easytv

enum class ContentKind(val apiKey: String, val title: String, val loadingLabel: String) {
    LIVE("live", "Canales", "canales"),
    SERIES("series", "Series", "series"),
    MOVIES("vod", "Películas", "películas");

    companion object {
        fun fromExtra(value: String?): ContentKind =
            entries.find { it.name == value } ?: LIVE
    }
}

data class PlaylistSource(
    val id: String,
    val name: String,
    val baseUrl: String,
    val username: String,
    val password: String,
    val hint: String
) {
    val m3uUrl: String
        get() = "$baseUrl/get.php?username=$username&password=$password&type=m3u_plus&output=mpegts"

    fun liveStreamUrl(streamId: Int): String =
        "$baseUrl/live/$username/$password/$streamId.ts"

    fun movieUrl(streamId: Int, ext: String): String =
        "$baseUrl/movie/$username/$password/$streamId.${ext.ifBlank { "mp4" }}"

    fun seriesEpisodeUrl(episodeId: String, ext: String): String =
        "$baseUrl/series/$username/$password/$episodeId.${ext.ifBlank { "mp4" }}"
}

/** Playable row (live/movie) or series entry (url empty, seriesId set). */
data class CatalogItem(
    val number: Int,
    val name: String,
    val group: String,
    val logo: String?,
    val url: String,
    val seriesId: Int? = null,
    val streamId: Int? = null
)

data class NowProgram(
    val title: String,
    val startMs: Long,
    val endMs: Long
) {
    fun scheduleLine(): String {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val start = fmt.format(java.util.Date(startMs))
        val end = fmt.format(java.util.Date(endMs))
        return "$start–$end  $title"
    }
}

