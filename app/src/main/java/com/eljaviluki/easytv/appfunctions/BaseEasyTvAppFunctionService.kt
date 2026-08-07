package com.eljaviluki.easytv.appfunctions

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import com.eljaviluki.easytv.ContentKind
import com.eljaviluki.easytv.LiveProgramResolver
import com.eljaviluki.easytv.ModeNav
import com.eljaviluki.easytv.PlaylistStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Agent-facing actions for EasyTV (Gemini / AppFunctions).
 *
 * KDoc is metadata for the model — keep it concrete and user-phrasing oriented.
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@AppFunctionServiceEntryPoint(
    serviceName = "EasyTvAppFunctionService",
    appFunctionXmlFileName = "easytv_app_functions",
)
abstract class BaseEasyTvAppFunctionService : AppFunctionService() {

    /**
     * Find where a live TV program or match is airing and tune to that channel.
     *
     * Use for requests like "pon el Atlético vs Alavés", "pon donde echen el partido",
     * or "pon el partido del Barça". Prefer passing [channelHints] when you already
     * know likely broadcasters from the web (for example "DAZN LaLiga", "M+ LaLiga").
     * EasyTV matches those names against the user's IPTV playlist and checks EPG
     * titles, then opens the best channel automatically.
     *
     * @param query Program, match, team, or show to find (e.g. "Atlético vs Alavés").
     * @param channelHints Optional broadcaster or channel names from web search
     *   (e.g. ["DAZN LaLiga", "Movistar LaLiga"]). Empty list if unknown.
     * @return Which channel was tuned and the matching program title if known.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun playLiveByQuery(
        query: String,
        channelHints: List<String>,
    ): LiveTuneResult = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank() && channelHints.all { it.isBlank() }) {
            throw AppFunctionInvalidArgumentException(
                "query or channelHints is required"
            )
        }
        val sourceId = ModeNav.resolveActiveSourceId()
            ?: throw AppFunctionElementNotFoundException("No IPTV playlist configured")

        val match = LiveProgramResolver.find(
            context = applicationContext,
            sourceId = sourceId,
            query = q.ifBlank { channelHints.firstOrNull().orEmpty() },
            channelHints = channelHints.map { it.trim() }.filter { it.isNotBlank() }
        ) ?: throw AppFunctionElementNotFoundException(
            "No channel found for \"$q\"" +
                if (channelHints.isNotEmpty()) {
                    " (hints: ${channelHints.joinToString()})"
                } else {
                    ""
                }
        )

        ModeNav.playLive(applicationContext, sourceId, match.channel)
        LiveTuneResult(
            channelName = match.channel.name,
            channelNumber = match.channel.number,
            programTitle = match.programTitle.orEmpty(),
            matchReason = match.reason,
            message = buildTuneMessage(match)
        )
    }

    /**
     * Tune live TV to a channel by display name or guide number.
     *
     * Use for "pon Antena 3", "pon la 5", "pon DAZN 1".
     *
     * @param nameOrNumber Channel name or number as spoken by the user.
     * @return The channel that was opened.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun playChannel(
        nameOrNumber: String,
    ): LiveTuneResult = withContext(Dispatchers.IO) {
        val raw = nameOrNumber.trim()
        if (raw.isBlank()) {
            throw AppFunctionInvalidArgumentException("nameOrNumber is required")
        }
        val sourceId = ModeNav.resolveActiveSourceId()
            ?: throw AppFunctionElementNotFoundException("No IPTV playlist configured")

        val channel = LiveProgramResolver.findByChannelNameOrNumber(
            context = applicationContext,
            sourceId = sourceId,
            nameOrNumber = raw
        ) ?: throw AppFunctionElementNotFoundException("Channel not found: $raw")

        ModeNav.playLive(applicationContext, sourceId, channel)
        LiveTuneResult(
            channelName = channel.name,
            channelNumber = channel.number,
            programTitle = "",
            matchReason = "channel_direct",
            message = "Sintonizando ${channel.name}"
        )
    }

    /**
     * Open EasyTV in live TV, series catalog, or movies catalog.
     *
     * @param mode One of: "tv", "live", "series", "movies", "peliculas".
     * @return Confirmation of the mode opened.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun openMode(
        mode: String,
    ): ModeOpenResult = withContext(Dispatchers.Main) {
        val sourceId = ModeNav.resolveActiveSourceId()
            ?: throw AppFunctionElementNotFoundException("No IPTV playlist configured")
        when (normalizeMode(mode)) {
            ModeKind.TV -> {
                val channel = ModeNav.resolveLiveChannel(applicationContext, sourceId)
                    ?: run {
                        ModeNav.openCatalogFromContext(
                            applicationContext,
                            sourceId,
                            ContentKind.LIVE
                        )
                        return@withContext ModeOpenResult(
                            mode = "tv",
                            message = "Abriendo catálogo de canales"
                        )
                    }
                ModeNav.playLive(applicationContext, sourceId, channel)
                ModeOpenResult(mode = "tv", message = "Abriendo TV en directo")
            }
            ModeKind.SERIES -> {
                ModeNav.openCatalogFromContext(
                    applicationContext,
                    sourceId,
                    ContentKind.SERIES
                )
                ModeOpenResult(mode = "series", message = "Abriendo series")
            }
            ModeKind.MOVIES -> {
                ModeNav.openCatalogFromContext(
                    applicationContext,
                    sourceId,
                    ContentKind.MOVIES
                )
                ModeOpenResult(mode = "movies", message = "Abriendo películas")
            }
        }
    }

    /**
     * List configured IPTV playlist / server names available in EasyTV.
     *
     * @return Playlist names the user can watch from.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun listPlaylists(): PlaylistListResult = withContext(Dispatchers.IO) {
        val names = PlaylistStore.sources().map { it.name }
        if (names.isEmpty()) {
            throw AppFunctionElementNotFoundException("No IPTV playlist configured")
        }
        PlaylistListResult(playlists = names, message = "${names.size} listas")
    }

    private fun buildTuneMessage(match: LiveProgramResolver.Match): String {
        val program = match.programTitle?.takeIf { it.isNotBlank() }
        return if (program != null) {
            "Sintonizando ${match.channel.name}: $program"
        } else {
            "Sintonizando ${match.channel.name}"
        }
    }

    private fun normalizeMode(mode: String): ModeKind {
        val m = mode.trim().lowercase()
        return when (m) {
            "tv", "live", "en directo", "tele", "television", "televisión" -> ModeKind.TV
            "series", "serie" -> ModeKind.SERIES
            "movies", "movie", "peliculas", "películas", "cine", "vod" -> ModeKind.MOVIES
            else -> throw AppFunctionInvalidArgumentException(
                "Unknown mode \"$mode\". Use tv, series, or movies."
            )
        }
    }

    private enum class ModeKind { TV, SERIES, MOVIES }
}

/** Result of tuning a live channel for an agent. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class LiveTuneResult(
    /** Display name of the channel that was opened. */
    val channelName: String,
    /** Guide number of the channel, or 0 if unknown. */
    val channelNumber: Int,
    /** EPG program title when matched; empty if tuned by channel name only. */
    val programTitle: String,
    /** Internal match reason: epg_title, channel_hint, channel_name, channel_number, channel_direct. */
    val matchReason: String,
    /** Short human-readable confirmation in Spanish. */
    val message: String,
)

/** Result of opening a top-level EasyTV mode. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ModeOpenResult(
    /** Mode that was opened: tv, series, or movies. */
    val mode: String,
    /** Short confirmation message. */
    val message: String,
)

/** Configured playlist names. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class PlaylistListResult(
    /** Playlist / server display names. */
    val playlists: List<String>,
    /** Short summary. */
    val message: String,
)
