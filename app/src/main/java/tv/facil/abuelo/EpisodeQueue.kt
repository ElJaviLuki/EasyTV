package tv.facil.abuelo

/**
 * In-memory episode queue for series playback (next-episode prompt).
 * Set from [EpisodesActivity] when opening an episode.
 */
object EpisodeQueue {
    @Volatile
    var episodes: List<CatalogItem> = emptyList()
        private set

    @Volatile
    var index: Int = -1
        private set

    @Volatile
    var sourceId: String = ""
        private set

    @Volatile
    var seriesId: Int? = null
        private set

    @Volatile
    var seriesName: String = ""
        private set

    val isActive: Boolean
        get() = index in episodes.indices

    fun set(
        sourceId: String,
        seriesId: Int,
        seriesName: String,
        episodes: List<CatalogItem>,
        startUrl: String
    ) {
        this.sourceId = sourceId
        this.seriesId = seriesId
        this.seriesName = seriesName
        this.episodes = episodes.filter { it.url.isNotBlank() }
        this.index = this.episodes.indexOfFirst { it.url == startUrl }.coerceAtLeast(0)
    }

    fun clear() {
        episodes = emptyList()
        index = -1
        sourceId = ""
        seriesId = null
        seriesName = ""
    }

    fun hasNext(): Boolean = isActive && index + 1 < episodes.size

    fun peekNext(): CatalogItem? = if (hasNext()) episodes[index + 1] else null

    fun advance(): CatalogItem? {
        if (!hasNext()) return null
        index += 1
        return episodes[index]
    }
}
