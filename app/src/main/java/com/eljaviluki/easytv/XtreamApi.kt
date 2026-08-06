package com.eljaviluki.easytv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object CatalogCache {
    private fun file(context: Context, sourceId: String, kind: ContentKind): File =
        File(context.filesDir, "catalog_${kind.apiKey}_$sourceId.tsv")

    fun write(context: Context, sourceId: String, kind: ContentKind, items: List<CatalogItem>) {
        file(context, sourceId, kind).bufferedWriter().use { out ->
            for (c in items) {
                out.append(c.number.toString()).append('\t')
                    .append(c.group.replace('\t', ' ').replace('\n', ' ')).append('\t')
                    .append(c.name.replace('\t', ' ').replace('\n', ' ')).append('\t')
                    .append(c.logo.orEmpty().replace('\t', ' ')).append('\t')
                    .append(c.url.replace('\t', ' ')).append('\t')
                    .append(c.seriesId?.toString().orEmpty()).append('\t')
                    .append(c.streamId?.toString().orEmpty())
                    .append('\n')
            }
        }
    }

    fun read(context: Context, sourceId: String, kind: ContentKind): List<CatalogItem> {
        val f = file(context, sourceId, kind)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 5) return@mapNotNull null
                    val url = p[4]
                    CatalogItem(
                        number = p[0].toIntOrNull() ?: return@mapNotNull null,
                        group = p[1],
                        name = p[2],
                        logo = p[3].ifBlank { null },
                        url = url,
                        seriesId = p.getOrNull(5)?.toIntOrNull(),
                        streamId = p.getOrNull(6)?.toIntOrNull() ?: streamIdFromUrl(url)
                    )
                }.toList()
            }
        }.getOrDefault(emptyList())
    }
}

private fun streamIdFromUrl(url: String): Int? {
    val m = Regex("""/(?:live|movie)/[^/]+/[^/]+/(\d+)\.""").find(url)
    return m?.groupValues?.getOrNull(1)?.toIntOrNull()
}

object XtreamApi {
    fun parseCategories(json: String): Map<String, String> {
        val arr = JSONArray(json)
        val map = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("category_id")
            val name = o.optString("category_name").ifBlank { "Sin categoría" }
            if (id.isNotBlank()) map[id] = name
        }
        return map
    }

    fun parseLiveStreams(json: String, source: PlaylistSource, categories: Map<String, String>): List<CatalogItem> {
        val arr = JSONArray(json)
        val items = ArrayList<CatalogItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val streamId = o.optInt("stream_id", -1)
            if (streamId < 0) continue
            val catId = o.optString("category_id")
            items += CatalogItem(
                number = o.optInt("num", i + 1),
                name = o.optString("name").ifBlank { "Canal $streamId" },
                group = categories[catId] ?: "Sin categoría",
                logo = o.optString("stream_icon").ifBlank { null },
                url = source.liveStreamUrl(streamId),
                streamId = streamId
            )
        }
        return items
    }

    fun parseVodStreams(json: String, source: PlaylistSource, categories: Map<String, String>): List<CatalogItem> {
        val arr = JSONArray(json)
        val items = ArrayList<CatalogItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val streamId = o.optInt("stream_id", -1)
            if (streamId < 0) continue
            val catId = o.optString("category_id")
            val ext = o.optString("container_extension").ifBlank { "mp4" }
            items += CatalogItem(
                number = o.optInt("num", i + 1),
                name = o.optString("name").ifBlank { "Película $streamId" },
                group = categories[catId] ?: "Sin categoría",
                logo = o.optString("stream_icon").ifBlank { null },
                url = source.movieUrl(streamId, ext),
                streamId = streamId
            )
        }
        return items
    }

    fun parseSeriesList(json: String, categories: Map<String, String>): List<CatalogItem> {
        val arr = JSONArray(json)
        val items = ArrayList<CatalogItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val seriesId = o.optInt("series_id", -1)
            if (seriesId < 0) continue
            val catId = o.optString("category_id")
            items += CatalogItem(
                number = o.optInt("num", i + 1),
                name = o.optString("name").ifBlank { "Serie $seriesId" },
                group = categories[catId] ?: "Sin categoría",
                logo = o.optString("cover").ifBlank { null },
                url = "",
                seriesId = seriesId
            )
        }
        return items
    }

    fun parseEpisodes(json: String, source: PlaylistSource): List<CatalogItem> {
        val root = JSONObject(json)
        val episodesObj = root.optJSONObject("episodes") ?: return emptyList()
        val items = ArrayList<CatalogItem>()
        val seasonKeys = episodesObj.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }
        var n = 1
        for (seasonKey in seasonKeys) {
            val seasonNum = seasonKey.toIntOrNull() ?: continue
            val arr = episodesObj.optJSONArray(seasonKey) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id").ifBlank { o.optInt("id", -1).takeIf { it >= 0 }?.toString().orEmpty() }
                if (id.isBlank()) continue
                val ext = o.optJSONObject("info")?.optString("container_extension")
                    ?.ifBlank { null }
                    ?: o.optString("container_extension").ifBlank { "mp4" }
                val epNum = o.optInt("episode_num", i + 1)
                val title = o.optString("title").ifBlank { "Episodio $epNum" }
                items += CatalogItem(
                    number = n++,
                    name = "T$seasonNum E$epNum · $title",
                    group = "Temporada $seasonNum",
                    logo = null,
                    url = source.seriesEpisodeUrl(id, ext)
                )
            }
        }
        return items
    }

    fun parseShortEpg(json: String): NowProgram? {
        val root = JSONObject(json)
        val listings = root.optJSONArray("epg_listings") ?: return null
        if (listings.length() == 0) return null
        val now = System.currentTimeMillis()
        var fallback: NowProgram? = null
        for (i in 0 until listings.length()) {
            val o = listings.getJSONObject(i)
            val title = decodeXtreamText(o.optString("title"))
            if (title.isBlank()) continue
            val startMs = parseEpgTime(o, start = true) ?: continue
            val endMs = parseEpgTime(o, start = false) ?: continue
            val program = NowProgram(title = title, startMs = startMs, endMs = endMs)
            if (fallback == null) fallback = program
            if (now in startMs until endMs) return program
        }
        return fallback
    }
}

private fun decodeXtreamText(raw: String): String {
    if (raw.isBlank()) return ""
    return try {
        val decoded = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
        String(decoded, Charsets.UTF_8).trim().ifBlank { raw }
    } catch (_: Exception) {
        raw.trim()
    }
}

private fun parseEpgTime(o: JSONObject, start: Boolean): Long? {
    val tsKey = if (start) "start_timestamp" else "stop_timestamp"
    val ts = o.optString(tsKey).toLongOrNull()
    if (ts != null && ts > 0L) {
        // Xtream usually uses seconds; treat large values as ms already.
        return if (ts > 10_000_000_000L) ts else ts * 1000L
    }
    val textKey = if (start) "start" else "end"
    val text = o.optString(textKey)
    if (text.isBlank()) return null
    val patterns = arrayOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyyMMddHHmmss Z",
        "yyyyMMddHHmmss"
    )
    for (p in patterns) {
        try {
            val fmt = java.text.SimpleDateFormat(p, java.util.Locale.US)
            fmt.isLenient = true
            return fmt.parse(text)?.time
        } catch (_: Exception) {
            // try next
        }
    }
    return null
}
