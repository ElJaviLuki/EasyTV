package com.eljaviluki.easytv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

/**
 * Resolves a natural-language live query (match, channel name, or number)
 * against the local Xtream catalog + short EPG.
 *
 * Agents (Gemini) should pass [channelHints] when they already know likely
 * broadcasters from the web (e.g. "DAZN LaLiga", "M+ LaLiga").
 */
object LiveProgramResolver {
    private const val EPG_CANDIDATE_LIMIT = 48
    private const val EPG_PARALLELISM = 8
    private const val MIN_ACCEPT_SCORE = 0.42

    data class Match(
        val channel: CatalogItem,
        val programTitle: String?,
        val score: Double,
        val reason: String
    )

    suspend fun find(
        context: Context,
        sourceId: String,
        query: String,
        channelHints: List<String> = emptyList()
    ): Match? = withContext(Dispatchers.IO) {
        val source = PlaylistStore.byId(sourceId) ?: return@withContext null
        val channels = loadLiveChannels(context, source)
        if (channels.isEmpty()) return@withContext null

        val q = normalize(query)
        if (q.isBlank() && channelHints.isEmpty()) return@withContext null

        q.toIntOrNull()?.let { number ->
            channels.firstOrNull { it.number == number }?.let {
                return@withContext Match(
                    channel = it,
                    programTitle = null,
                    score = 1.0,
                    reason = "channel_number"
                )
            }
        }

        val hintNorms = channelHints.map { normalize(it) }.filter { it.isNotBlank() }
        val scoredChannels = channels.map { ch ->
            val nameScore = textScore(normalize(ch.name), q)
            val groupScore = textScore(normalize(ch.group), q) * 0.35
            val hintScore = hintNorms.maxOfOrNull { hint ->
                textScore(normalize(ch.name), hint).coerceAtLeast(
                    textScore(normalize(ch.group), hint) * 0.5
                )
            } ?: 0.0
            val sportsBoost =
                if (hintNorms.isNotEmpty() && looksSports(ch) && hintScore >= 0.35) 0.08 else 0.0
            ChannelScore(
                channel = ch,
                score = maxOf(nameScore, groupScore, hintScore) + sportsBoost,
                fromHint = hintScore >= 0.45
            )
        }.sortedByDescending { it.score }

        // Strong channel-only hit (e.g. "Antena 3", "DAZN 1") — tune without EPG.
        val bestChannel = scoredChannels.firstOrNull()
        if (bestChannel != null && bestChannel.score >= 0.78 && !looksLikeProgramQuery(q)) {
            return@withContext Match(
                channel = bestChannel.channel,
                programTitle = null,
                score = bestChannel.score,
                reason = if (bestChannel.fromHint) "channel_hint" else "channel_name"
            )
        }

        val epgCandidates = pickEpgCandidates(scoredChannels, hintNorms.isNotEmpty())
        val epgMatches = fetchEpgScores(source, epgCandidates, q)

        val best = epgMatches.maxByOrNull { it.score }
            ?: bestChannel?.takeIf { it.score >= MIN_ACCEPT_SCORE }?.let {
                Match(
                    channel = it.channel,
                    programTitle = null,
                    score = it.score,
                    reason = if (it.fromHint) "channel_hint" else "channel_name"
                )
            }

        best?.takeIf { it.score >= MIN_ACCEPT_SCORE }
    }

    suspend fun findByChannelNameOrNumber(
        context: Context,
        sourceId: String,
        nameOrNumber: String
    ): CatalogItem? = withContext(Dispatchers.IO) {
        val source = PlaylistStore.byId(sourceId) ?: return@withContext null
        val channels = loadLiveChannels(context, source)
        if (channels.isEmpty()) return@withContext null

        val raw = nameOrNumber.trim()
        raw.toIntOrNull()?.let { number ->
            channels.firstOrNull { it.number == number }?.let { return@withContext it }
        }

        val q = normalize(raw)
        channels
            .map { it to textScore(normalize(it.name), q) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= 0.55 }
            ?.first
    }

    private suspend fun loadLiveChannels(
        context: Context,
        source: PlaylistSource
    ): List<CatalogItem> {
        var live = PlaylistRepository.memoryCached(source.id, ContentKind.LIVE)
        if (live.isEmpty()) {
            live = PlaylistRepository.diskCached(context, source.id, ContentKind.LIVE)
        }
        if (live.isEmpty()) {
            runCatching {
                live = PlaylistRepository.loadCatalog(context, source, ContentKind.LIVE)
            }
        }
        val playable = live.filter { it.url.isNotBlank() }
        if (playable.isNotEmpty()) ZapPlaylist.set(playable)
        return playable
    }

    private fun pickEpgCandidates(
        scored: List<ChannelScore>,
        hadHints: Boolean
    ): List<CatalogItem> {
        val byScore = scored.filter { it.score >= 0.28 || it.fromHint }.map { it.channel }
        val sports = scored.asSequence()
            .filter { looksSports(it.channel) }
            .take(if (hadHints) 16 else 32)
            .map { it.channel }
            .toList()
        return (byScore + sports)
            .distinctBy { it.streamId ?: it.url }
            .take(EPG_CANDIDATE_LIMIT)
    }

    private suspend fun fetchEpgScores(
        source: PlaylistSource,
        candidates: List<CatalogItem>,
        queryNorm: String
    ): List<Match> = coroutineScope {
        if (candidates.isEmpty() || queryNorm.isBlank()) return@coroutineScope emptyList()
        val gate = Semaphore(EPG_PARALLELISM)
        candidates.map { channel ->
            async {
                gate.withPermit {
                    val streamId = channel.streamId ?: return@withPermit null
                    val program = EpgRepository.nowPlaying(source, streamId) ?: return@withPermit null
                    val programScore = textScore(normalize(program.title), queryNorm)
                    if (programScore < 0.35) return@withPermit null
                    val channelBoost =
                        textScore(normalize(channel.name), queryNorm).coerceAtMost(0.25)
                    Match(
                        channel = channel,
                        programTitle = program.title,
                        score = (programScore * 0.85 + channelBoost).coerceAtMost(1.0),
                        reason = "epg_title"
                    )
                }
            }
        }.awaitAll().filterNotNull()
    }

    private data class ChannelScore(
        val channel: CatalogItem,
        val score: Double,
        val fromHint: Boolean
    )

    private fun looksSports(ch: CatalogItem): Boolean {
        val blob = normalize("${ch.group} ${ch.name}")
        return listOf(
            "deport", "sport", "futbol", "football", "liga", "dazn", "gol",
            "movistar", "laliga", "champion", "euro", "bein", "espn", "sky sport"
        ).any { it in blob }
    }

    /** True when the query looks like a show/match rather than a channel brand. */
    private fun looksLikeProgramQuery(q: String): Boolean {
        if (q.isBlank()) return false
        val tokens = q.split(' ').filter { it.isNotBlank() }
        if (tokens.size >= 2) return true
        return listOf("vs", "contra", "partido", "atletico", "alaves", "barca", "madrid")
            .any { it in q }
    }

    internal fun normalize(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        val stripped = Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return stripped
            .replace('ñ', 'n')
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
            .let { expandAliases(it) }
    }

    private fun expandAliases(text: String): String {
        var t = " $text "
        val aliases = listOf(
            " atletico de madrid " to " atletico ",
            " atm " to " atletico ",
            " atleti " to " atletico ",
            " athletic club " to " athletic ",
            " barca " to " barcelona ",
            " barça " to " barcelona ",
            " real madrid " to " madrid ",
            " alaves " to " alaves ",
            " deportivo alaves " to " alaves ",
            " man city " to " manchester city ",
            " man utd " to " manchester united ",
            " m plus " to " movistar "
        )
        for ((from, to) in aliases) {
            t = t.replace(from, to)
        }
        return t.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Token overlap + containment score in 0..1.
     * Prefers queries whose tokens mostly appear in the candidate text.
     */
    internal fun textScore(candidate: String, query: String): Double {
        if (candidate.isBlank() || query.isBlank()) return 0.0
        if (candidate == query) return 1.0
        if (candidate.contains(query)) {
            return (0.92 * query.length.toDouble() / candidate.length.coerceAtLeast(1))
                .coerceAtLeast(0.7)
        }
        if (query.contains(candidate) && candidate.length >= 4) {
            return 0.75
        }

        val qTokens = query.split(' ').filter { it.length >= 2 }
        val cTokens = candidate.split(' ').filter { it.length >= 2 }.toSet()
        if (qTokens.isEmpty()) return 0.0

        val hit = qTokens.count { qt ->
            cTokens.any { ct -> ct == qt || ct.startsWith(qt) || qt.startsWith(ct) }
        }
        val coverage = hit.toDouble() / qTokens.size
        val reverse = if (cTokens.isEmpty()) 0.0 else {
            cTokens.count { ct -> qTokens.any { qt -> ct == qt || ct.startsWith(qt) } }
                .toDouble() / cTokens.size
        }
        return (coverage * 0.8 + reverse * 0.2).coerceIn(0.0, 1.0)
    }
}
