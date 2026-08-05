package tv.facil.abuelo

data class PlaylistSource(
    val id: String,
    val name: String,
    val url: String,
    val hint: String
)

data class Channel(
    val number: Int,
    val name: String,
    val group: String,
    val logo: String?,
    val url: String
)

/**
 * Local placeholders only — replace with your portals before building.
 * Real credentials must never be committed.
 */
object SeededPlaylists {
    val sources: List<PlaylistSource> = listOf(
        PlaylistSource("1", "Manolo1", "http://server1.example:80/get.php?username=YOUR_USER&password=YOUR_PASS&type=m3u_plus&output=mpegts", "Servidor principal"),
        PlaylistSource("2", "Manolo2", "http://server2.example:80/get.php?username=YOUR_USER&password=YOUR_PASS&type=m3u_plus&output=mpegts", "Alternativa 2"),
        PlaylistSource("3", "Manolo3", "http://server3.example:80/get.php?username=YOUR_USER&password=YOUR_PASS&type=m3u_plus&output=mpegts", "Alternativa 3")
    )

    fun byId(id: String): PlaylistSource? = sources.find { it.id == id }
}
