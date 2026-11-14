package ai.opencode.mobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One OpenCode server the phone can talk to. `url` is a normalized origin such as `http://192.168.1.10:4096`. */
data class Server(
    val url: String,
    val password: String = "",
) {
    /** Human friendly form used in the UI, without the scheme. */
    val label: String
        get() = url.removePrefix("https://").removePrefix("http://").trimEnd('/')

    val host: String
        get() = url.substringAfter("://").substringBefore("/").substringBefore(":")
}

/**
 * Remembers recently used servers so the phone does not retype the address every launch.
 *
 * Passwords are stored in plain SharedPreferences because the wrapper must hand them to the WebView's
 * basic-auth handler; move this to EncryptedSharedPreferences before shipping beyond a local network.
 */
class ServerStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recents(): List<Server> {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val entry = array.optJSONObject(index) ?: continue
                    val url = entry.optString("url").takeIf { it.isNotBlank() } ?: continue
                    add(Server(url, entry.optString("password")))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun remember(server: Server) {
        val next = (listOf(server) + recents().filterNot { it.url == server.url }).take(MAX_RECENTS)
        persist(next)
    }

    fun forget(url: String) {
        persist(recents().filterNot { it.url == url })
    }

    private fun persist(servers: List<Server>) {
        val array = JSONArray()
        servers.forEach { server ->
            array.put(JSONObject().put("url", server.url).put("password", server.password))
        }
        prefs.edit().putString(KEY_RECENTS, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "opencode_mobile"
        const val KEY_RECENTS = "servers"
        const val MAX_RECENTS = 8
    }
}
