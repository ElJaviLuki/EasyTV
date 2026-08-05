package tv.facil.abuelo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object PlaylistRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var cacheKey: String? = null

    @Volatile
    private var cacheChannels: List<Channel> = emptyList()

    fun memoryCached(sourceId: String): List<Channel> =
        if (cacheKey == sourceId) cacheChannels else emptyList()

    fun diskCached(context: Context, sourceId: String): List<Channel> =
        ChannelCache.read(context, sourceId)

    suspend fun loadChannels(
        context: Context,
        source: PlaylistSource,
        force: Boolean = false
    ): List<Channel> = withContext(Dispatchers.IO) {
        if (!force && cacheKey == source.id && cacheChannels.isNotEmpty()) {
            return@withContext cacheChannels
        }

        val channels = try {
            loadViaXtreamApi(source)
        } catch (apiError: Exception) {
            try {
                loadViaM3u(source)
            } catch (m3uError: Exception) {
                throw Exception("API: ${apiError.message} · M3U: ${m3uError.message}")
            }
        }

        if (channels.isEmpty()) error("No hay canales en la lista")
        cacheKey = source.id
        cacheChannels = channels
        ChannelCache.write(context.applicationContext, source.id, channels)
        channels
    }

    private suspend fun loadViaXtreamApi(source: PlaylistSource): List<Channel> = coroutineScope {
        val catsDeferred = async {
            httpGet("${source.baseUrl}/player_api.php?username=${source.username}&password=${source.password}&action=get_live_categories")
        }
        val streamsDeferred = async {
            httpGet("${source.baseUrl}/player_api.php?username=${source.username}&password=${source.password}&action=get_live_streams")
        }
        XtreamApi.parseLiveStreams(streamsDeferred.await(), source, XtreamApi.parseCategories(catsDeferred.await()))
    }

    private fun loadViaM3u(source: PlaylistSource): List<Channel> =
        M3uParser.parse(httpGet(source.m3uUrl))

    private fun httpGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TVFacil/1.1")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) error("respuesta vacía")
            return body
        }
    }
}
