package com.eljaviluki.easytv

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Playlists loaded at runtime (not hardcoded, not packaged in the APK).
 *
 * Priority:
 * 1. App external files: Android/data/com.eljaviluki.easytv/files/playlists.json (adb seed)
 * 2. App internal filesDir/playlists.json
 *
 * Local working copy for the seeder: secrets/playlists.json (gitignored, outside app/).
 * Seed: python scripts/seed_playlists.py path/to/export.json
 * JSON providers do not supply ids — derived from baseUrl+username.
 */
object PlaylistStore {
    const val FILE_NAME = "playlists.json"

    @Volatile
    private var sources: List<PlaylistSource> = emptyList()

    @Volatile
    private var loadError: String? = null

    @Volatile
    private var loadedFrom: String? = null

    fun sources(): List<PlaylistSource> = sources

    fun byId(id: String): PlaylistSource? = sources.find { it.id == id }

    fun errorMessage(): String? = loadError

    fun loadedFrom(): String? = loadedFrom

    fun stableId(baseUrl: String, username: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$baseUrl\u0000$username".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { b -> "%02x".format(b) }
    }

    fun load(context: Context) {
        try {
            val text = readPlaylistText(context)
                ?: run {
                    sources = emptyList()
                    loadedFrom = null
                    loadError = "Falta playlists.json. Ejecuta: python scripts/seed_playlists.py <export.json>"
                    return
                }
            sources = parse(text)
            loadError = if (sources.isEmpty()) {
                "playlists.json no tiene servidores"
            } else {
                null
            }
        } catch (e: Exception) {
            sources = emptyList()
            loadedFrom = null
            loadError = e.message ?: "Error leyendo playlists.json"
        }
    }

    private fun readPlaylistText(context: Context): String? {
        val external = context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }
        if (external != null && external.isFile && external.length() > 0L) {
            loadedFrom = external.absolutePath
            return external.readText(Charsets.UTF_8)
        }

        val internal = File(context.filesDir, FILE_NAME)
        if (internal.isFile && internal.length() > 0L) {
            loadedFrom = internal.absolutePath
            return internal.readText(Charsets.UTF_8)
        }

        return null
    }

    fun parse(text: String): List<PlaylistSource> {
        val root = JSONObject(text)
        if (root.has("sources")) {
            return parseSourcesArray(root.getJSONArray("sources"))
        }
        if (root.has("urls")) {
            return parseIbUrls(root.getJSONArray("urls"))
        }
        error("JSON no reconocido: falta \"sources\" o \"urls\"")
    }

    private fun parseSourcesArray(arr: JSONArray): List<PlaylistSource> {
        val out = ArrayList<PlaylistSource>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val baseUrl = o.getString("baseUrl").trimEnd('/')
            val username = o.getString("username")
            out += PlaylistSource(
                id = stableId(baseUrl, username),
                name = o.getString("name"),
                baseUrl = baseUrl,
                username = username,
                password = o.getString("password"),
                hint = o.optString("hint").ifBlank { o.optString("name") }
            )
        }
        return out
    }

    private fun parseIbUrls(arr: JSONArray): List<PlaylistSource> {
        val out = ArrayList<PlaylistSource>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val portal = o.getString("url")
            val uri = Uri.parse(portal)
            val user = uri.getQueryParameter("username").orEmpty()
            val pass = uri.getQueryParameter("password").orEmpty()
            val base = buildString {
                append(uri.scheme ?: "http")
                append("://")
                append(uri.host.orEmpty())
                if (uri.port != -1) append(":").append(uri.port)
            }
            if (user.isBlank() || pass.isBlank() || uri.host.isNullOrBlank()) continue
            out += PlaylistSource(
                id = stableId(base, user),
                name = o.optString("name").ifBlank { "Servidor ${i + 1}" },
                baseUrl = base,
                username = user,
                password = pass,
                hint = uri.host.orEmpty()
            )
        }
        return out
    }
}
