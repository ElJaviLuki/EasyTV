package com.eljaviluki.easytv

/**
 * In-memory zap list for live TV (CH+/CH-).
 * Items are channels (with live fallbacks); identity is [CatalogItem.channelId] when set.
 */
object ZapPlaylist {
    @Volatile
    var items: List<CatalogItem> = emptyList()
        private set

    fun set(items: List<CatalogItem>) {
        this.items = items.filter { it.url.isNotBlank() || it.lives.isNotEmpty() }
            .map { ChannelsCleanStore.preferred(it) }
    }

    fun clear() {
        items = emptyList()
    }

    fun indexOf(channelId: String, url: String): Int {
        if (channelId.isNotBlank()) {
            val byId = items.indexOfFirst { it.channelId == channelId }
            if (byId >= 0) return byId
        }
        return items.indexOfFirst { item ->
            item.url == url || item.lives.any { it.url == url }
        }
    }

    /**
     * Next/previous channel. Respects [AppSettings.zapWrapAround]
     * (default: no wrap — returns null at the ends).
     */
    fun neighbor(channelId: String, url: String, delta: Int): CatalogItem? {
        val list = items
        if (list.isEmpty()) return null
        val idx = indexOf(channelId, url)
        if (idx < 0) return null
        val next = idx + delta
        val raw = when {
            next in list.indices -> list[next]
            AppSettings.zapWrapAround -> list[next.mod(list.size)]
            else -> return null
        }
        return ChannelsCleanStore.preferred(raw)
    }

    /** First channel with the given guide number, or null. */
    fun byNumber(number: Int): CatalogItem? =
        items.firstOrNull { it.number == number }?.let { ChannelsCleanStore.preferred(it) }
}
