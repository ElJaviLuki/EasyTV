package tv.facil.abuelo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object EpgRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, NowProgram?>()

    private fun key(sourceId: String, streamId: Int) = "${sourceId}_$streamId"

    suspend fun nowPlaying(source: PlaylistSource, streamId: Int): NowProgram? =
        withContext(Dispatchers.IO) {
            val cacheKey = key(source.id, streamId)
            if (cache.containsKey(cacheKey)) return@withContext cache[cacheKey]

            val program = runCatching {
                val url =
                    "${source.baseUrl}/player_api.php?username=${source.username}&password=${source.password}&action=get_short_epg&stream_id=$streamId&limit=3"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "TVFacil/1.3")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) null else XtreamApi.parseShortEpg(body)
                }
            }.getOrNull()

            cache[cacheKey] = program
            program
        }
}
