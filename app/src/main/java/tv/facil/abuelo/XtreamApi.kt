package tv.facil.abuelo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ChannelCache {
    private fun file(context: Context, sourceId: String): File =
        File(context.filesDir, "channels_$sourceId.tsv")

    fun read(context: Context, sourceId: String): List<Channel> {
        val f = file(context, sourceId)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 5) return@mapNotNull null
                    Channel(
                        number = p[0].toIntOrNull() ?: return@mapNotNull null,
                        group = p[1],
                        name = p[2],
                        logo = p[3].ifBlank { null },
                        url = p[4]
                    )
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    fun write(context: Context, sourceId: String, channels: List<Channel>) {
        file(context, sourceId).bufferedWriter().use { out ->
            for (c in channels) {
                out.append(c.number.toString()).append('\t')
                    .append(c.group.replace('\t', ' ').replace('\n', ' ')).append('\t')
                    .append(c.name.replace('\t', ' ').replace('\n', ' ')).append('\t')
                    .append(c.logo.orEmpty().replace('\t', ' ')).append('\t')
                    .append(c.url)
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

    fun parseLiveStreams(json: String, source: PlaylistSource, categories: Map<String, String>): List<Channel> {
        val arr = JSONArray(json)
        val channels = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.getJSONObject(i)
            val streamId = o.optInt("stream_id", -1)
            if (streamId < 0) continue
            val catId = o.optString("category_id")
            channels += Channel(
                number = o.optInt("num", i + 1),
                name = o.optString("name").ifBlank { "Canal $streamId" },
                group = categories[catId] ?: "Sin categoría",
                logo = o.optString("stream_icon").ifBlank { null },
                url = source.liveStreamUrl(streamId)
            )
        }
        return channels
    }
}
