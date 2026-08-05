package tv.facil.abuelo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object CatalogCache {
    private fun file(context: Context, sourceId: String, kind: ContentKind): File =
        File(context.filesDir, "catalog_${kind.apiKey}_$sourceId.tsv")

    fun read(context: Context, sourceId: String, kind: ContentKind): List<CatalogItem> {
        val f = file(context, sourceId, kind)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 6) return@mapNotNull null
                    CatalogItem(
                        number = p[0].toIntOrNull() ?: return@mapNotNull null,
                        group = p[1],
                        name = p[2],
                        logo = p[3].ifBlank { null },
                        url = p[4],
                        seriesId = p[5].toIntOrNull()
                    )
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    fun write(context: Context, sourceId: String, kind: ContentKind, items: List<CatalogItem>) {
        file(context, sourceId, kind).bufferedWriter().use { out ->
            for (c in items) {
                out.append(c.number.toString()).append('\t')
                    .append(c.group.replace('\t', ' ').replace('\n', ' ')).append('\t')
                    .append(c.name.replace('\t', ' ').replace('\n', ' ')).append('\t')
                    .append(c.logo.orEmpty().replace('\t', ' ')).append('\t')
                    .append(c.url.replace('\t', ' ')).append('\t')
                    .append(c.seriesId?.toString().orEmpty())
                    .append('\n')
            }
        }
    }
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
                url = source.liveStreamUrl(streamId)
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
                url = source.movieUrl(streamId, ext)
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
}
