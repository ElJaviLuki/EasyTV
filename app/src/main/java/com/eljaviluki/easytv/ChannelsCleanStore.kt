package com.eljaviluki.easytv

import android.content.Context
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * TV channel guide seeded from bundled [channels_clean.json] (family-curated).
 * Each canal has ordered [LiveEndpoint] fallbacks (server/url).
 */
object ChannelsCleanStore {
    private const val ASSET = "channels_clean.json"
    private val STREAM_ID = Pattern.compile("/(\\d+)\\.ts(?:\\?|$)")

    @Volatile
    private var channels: List<CatalogItem> = emptyList()

    @Volatile
    private var loadError: String? = null

    fun channels(): List<CatalogItem> = channels

    fun errorMessage(): String? = loadError

    fun byChannelId(id: String): CatalogItem? =
        channels.find { it.channelId == id }

    fun byNumber(number: Int): CatalogItem? =
        channels.find { it.number == number }

    fun ensureLoaded(context: Context): List<CatalogItem> {
        if (channels.isNotEmpty()) return channels
        return load(context)
    }

    fun load(context: Context): List<CatalogItem> {
        return try {
            val text = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parsed = parse(text)
            channels = parsed
            loadError = if (parsed.isEmpty()) "channels_clean.json vacío" else null
            parsed
        } catch (e: Exception) {
            channels = emptyList()
            loadError = e.message ?: "Error leyendo channels_clean.json"
            emptyList()
        }
    }

    fun parse(text: String): List<CatalogItem> {
        val root = JSONObject(text)
        val arr = root.getJSONArray("canales")
        val out = ArrayList<CatalogItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val livesArr = o.optJSONArray("lives") ?: continue
            val lives = ArrayList<LiveEndpoint>(livesArr.length())
            for (j in 0 until livesArr.length()) {
                val live = livesArr.getJSONObject(j)
                val url = live.optString("url").trim()
                if (url.isBlank()) continue
                lives += LiveEndpoint(
                    server = live.optString("server").ifBlank { "?" },
                    url = url
                )
            }
            if (lives.isEmpty()) continue

            val channelId = o.optString("id").ifBlank { lives.first().url }
            val visible = o.optString("visible_name").ifBlank {
                o.optJSONArray("name")?.optString(0).orEmpty()
            }.ifBlank { "Canal" }
            val groups = o.optJSONArray("group")
            val group = when {
                groups != null && groups.length() > 0 -> groups.optString(0)
                else -> "TV"
            }
            val url = lives.first().url
            out += CatalogItem(
                number = o.optInt("number", 0),
                name = visible,
                group = group,
                logo = o.optString("logo").ifBlank { null },
                url = url,
                streamId = streamIdFromUrl(url),
                channelId = channelId,
                lives = lives
            )
        }
        out.sortBy { it.number }
        return out
    }

    fun streamIdFromUrl(url: String): Int? {
        val m = STREAM_ID.matcher(url)
        return if (m.find()) m.group(1)?.toIntOrNull() else null
    }

    /** Resolve playable URL: last successful for channel, else first live. */
    fun preferred(item: CatalogItem): CatalogItem {
        if (item.lives.isEmpty()) return item
        val cached = AppSettings.successfulLiveUrl(item.channelId)
        val url = when {
            cached != null && item.lives.any { it.url == cached } -> cached
            else -> item.lives.first().url
        }
        return item.copy(
            url = url,
            streamId = streamIdFromUrl(url) ?: item.streamId
        )
    }
}
