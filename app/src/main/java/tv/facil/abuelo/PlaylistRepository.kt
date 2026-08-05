package tv.facil.abuelo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object PlaylistRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var cacheKey: String? = null

    @Volatile
    private var cacheChannels: List<Channel> = emptyList()

    suspend fun loadChannels(source: PlaylistSource, force: Boolean = false): List<Channel> =
        withContext(Dispatchers.IO) {
            if (!force && cacheKey == source.id && cacheChannels.isNotEmpty()) {
                return@withContext cacheChannels
            }
            val request = Request.Builder()
                .url(source.url)
                .header("User-Agent", "TVFacil/1.0")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) error("Lista vacía")
                val channels = M3uParser.parse(body)
                if (channels.isEmpty()) error("No hay canales en la lista")
                cacheKey = source.id
                cacheChannels = channels
                channels
            }
        }
}
