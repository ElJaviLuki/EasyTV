package tv.facil.abuelo

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

data class CatalogItem(
    val number: Int,
    val name: String,
    val group: String,
    val logo: String?,
    val url: String,
    val seriesId: Int? = null
)

/**
 * Local placeholders only — replace before building.
 * Real credentials must never be committed.
 */
object SeededPlaylists {
    private const val USER = "YOUR_USER"
    private const val PASS = "YOUR_PASS"

    val sources: List<PlaylistSource> = listOf(
        PlaylistSource("1", "Manolo1", "http://server1.example:80", USER, PASS, "Servidor principal"),
        PlaylistSource("2", "Manolo2", "http://server2.example:80", USER, PASS, "Alternativa 2"),
        PlaylistSource("3", "Manolo3", "http://server3.example:80", USER, PASS, "Alternativa 3")
    )

    fun byId(id: String): PlaylistSource? = sources.find { it.id == id }
}
