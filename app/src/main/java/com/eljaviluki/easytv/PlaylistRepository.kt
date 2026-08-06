package com.eljaviluki.easytv

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
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val memory = HashMap<String, List<CatalogItem>>()

    private fun memKey(sourceId: String, kind: ContentKind) = "${sourceId}_${kind.apiKey}"

    fun memoryCached(sourceId: String, kind: ContentKind): List<CatalogItem> =
        memory[memKey(sourceId, kind)].orEmpty()

    fun diskCached(context: Context, sourceId: String, kind: ContentKind): List<CatalogItem> =
        CatalogCache.read(context, sourceId, kind)

    suspend fun loadCatalog(
        context: Context,
        source: PlaylistSource,
        kind: ContentKind,
        force: Boolean = false
    ): List<CatalogItem> = withContext(Dispatchers.IO) {
        val key = memKey(source.id, kind)
        if (!force) {
            memory[key]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        }

        val items = when (kind) {
            ContentKind.LIVE -> loadLive(source)
            ContentKind.MOVIES -> loadMovies(source)
            ContentKind.SERIES -> loadSeries(source)
        }
        if (items.isEmpty()) error("Lista vacía")
        memory[key] = items
        CatalogCache.write(context.applicationContext, source.id, kind, items)
        items
    }

    suspend fun loadEpisodes(source: PlaylistSource, seriesId: Int): List<CatalogItem> =
        withContext(Dispatchers.IO) {
            val json = httpGet(
                "${source.baseUrl}/player_api.php?username=${source.username}&password=${source.password}&action=get_series_info&series_id=$seriesId"
            )
            val items = XtreamApi.parseEpisodes(json, source)
            if (items.isEmpty()) error("Sin episodios")
            items
        }

    private suspend fun loadLive(source: PlaylistSource): List<CatalogItem> = coroutineScope {
        val cats = async {
            httpGet("${source.baseUrl}/player_api.php?username=${source.username}&password=${source.password}&action=get_live_categories")
        }
        val streams = async {
            httpGet("${source.baseUrl}/player_api.php?username=${source.username}&password=${source.password}&action=get_live_streams")
        }
        XtreamApi.parseLiveStreams(streams.await(), source, XtreamApi.parseCategories(cats.await()))
    }

    private suspend fun loadMovies(source: PlaylistSource): List<CatalogItem> = coroutineScope {
        val cats = async {
            httpGet("${source.baseUrl}/player_api.php?username=${source.username}&password=${source.password}&action=get_vod_categories")
        }
        val streams = async {
            httpGet("${source.baseUrl}/player_api.php?username=${source.username}&password=${source.password}&action=get_vod_streams")
        }
        XtreamApi.parseVodStreams(streams.await(), source, XtreamApi.parseCategories(cats.await()))
    }

    private suspend fun loadSeries(source: PlaylistSource): List<CatalogItem> = coroutineScope {
        val cats = async {
            httpGet("${source.baseUrl}/player_api.php?username=${source.username}&password=${source.password}&action=get_series_categories")
        }
        val series = async {
            httpGet("${source.baseUrl}/player_api.php?username=${source.username}&password=${source.password}&action=get_series")
        }
        XtreamApi.parseSeriesList(series.await(), XtreamApi.parseCategories(cats.await()))
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "EasyTV/1.0")
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
