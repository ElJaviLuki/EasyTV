package tv.facil.abuelo

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Playlists loaded at runtime from assets/playlists.json (not hardcoded).
 * Seed post-clone with: python scripts/seed_playlists.py <export.json>
 *
 * JSON providers do not supply ids — the app derives a stable id from baseUrl+username.
 */
object PlaylistStore {
    const val ASSET_FILE = "playlists.json"

    @Volatile
    private var sources: List<PlaylistSource> = emptyList()

    @Volatile
    private var loadError: String? = null

    fun sources(): List<PlaylistSource> = sources

    fun byId(id: String): PlaylistSource? = sources.find { it.id == id }

    fun errorMessage(): String? = loadError

    fun stableId(baseUrl: String, username: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$baseUrl\u0000$username".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { b -> "%02x".format(b) }
    }

    fun load(context: Context) {
        try {
            val names = context.assets.list("").orEmpty()
            if (ASSET_FILE !in names) {
                sources = emptyList()
                loadError = "Falta assets/$ASSET_FILE. Ejecuta scripts/seed_playlists.py"
                return
            }
            val text = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            sources = parse(text)
            loadError = if (sources.isEmpty()) {
                "playlists.json no tiene servidores"
            } else {
                null
            }
        } catch (e: Exception) {
            sources = emptyList()
            loadError = e.message ?: "Error leyendo playlists.json"
        }
    }

    fun parse(text: String): List<PlaylistSource> {
        val root = JSONObject(text)
        // Clean format: { "sources": [ { name, baseUrl, username, password, hint? } ] }
        if (root.has("sources")) {
            return parseSourcesArray(root.getJSONArray("sources"))
        }
        // IB Player export: { "urls": [ { name, url } ] }
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
