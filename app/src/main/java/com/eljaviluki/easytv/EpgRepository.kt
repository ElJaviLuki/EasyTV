package com.eljaviluki.easytv

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

    /** Present programs only — ConcurrentHashMap forbids null values. */
    private val cache = ConcurrentHashMap<String, NowProgram>()

    /** Stream ids we already tried and found empty/failed. */
    private val empty = ConcurrentHashMap.newKeySet<String>()

    private fun key(sourceId: String, streamId: Int) = "${sourceId}_$streamId"

    suspend fun nowPlaying(source: PlaylistSource, streamId: Int): NowProgram? =
        withContext(Dispatchers.IO) {
            val cacheKey = key(source.id, streamId)
            cache[cacheKey]?.let { return@withContext it }
            if (cacheKey in empty) return@withContext null

            val program = runCatching {
                val url =
                    "${source.baseUrl}/player_api.php?username=${source.username}&password=${source.password}&action=get_short_epg&stream_id=$streamId&limit=3"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "EasyTV/1.0")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) null else XtreamApi.parseShortEpg(body)
                }
            }.getOrNull()

            if (program != null) {
                cache[cacheKey] = program
            } else {
                empty.add(cacheKey)
            }
            program
        }
}
