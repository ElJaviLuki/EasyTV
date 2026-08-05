package tv.facil.abuelo

/**
 * In-memory zap list for live TV (CH+/CH-).
 * Set from CatalogActivity when opening a live channel.
 */
object ZapPlaylist {
    @Volatile
    var items: List<CatalogItem> = emptyList()
        private set

    fun set(items: List<CatalogItem>) {
        this.items = items.filter { it.url.isNotBlank() }
    }

    fun clear() {
        items = emptyList()
    }

    fun indexOfUrl(url: String): Int = items.indexOfFirst { it.url == url }

    /**
     * Next/previous channel. Respects [AppSettings.zapWrapAround]
     * (default: no wrap — returns null at the ends).
     */
    fun neighbor(url: String, delta: Int): CatalogItem? {
        val list = items
        if (list.isEmpty()) return null
        val idx = indexOfUrl(url)
        if (idx < 0) return null
        val next = idx + delta
        return when {
            next in list.indices -> list[next]
            AppSettings.zapWrapAround -> list[next.mod(list.size)]
            else -> null
        }
    }
}
