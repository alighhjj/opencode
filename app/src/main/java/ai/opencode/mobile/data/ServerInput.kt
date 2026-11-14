package ai.opencode.mobile.data

import java.net.URI

/**
 * Turns whatever the user pastes into a server origin plus an optional one-time path.
 *
 * Accepted inputs:
 *  - `192.168.1.10` / `192.168.1.10:4096` / `http://box.local:4096`
 *  - `http://10.0.0.2:4096/auth/connect/<code>` (the link printed by `opencode pair`)
 *  - `{"code":"...","urls":["http://10.0.0.2:4096"]}` (the payload inside `opencode pair`'s QR code)
 */
object ServerInput {

    const val DEFAULT_PORT = 4096

    data class Target(val baseUrl: String, val path: String? = null)

    private val pairingLink = Regex("^(https?://[^/\\s]+)/auth/connect/([A-Za-z0-9_-]+)/?$")
    private val codeField = Regex("\"code\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"")
    private val urlsField = Regex("\"urls\"\\s*:\\s*\\[([^\\]]*)]")
    private val quoted = Regex("\"([^\"]+)\"")

    fun parse(input: String): Target? {
        val value = input.trim()
        if (value.isEmpty()) return null

        if (value.startsWith("{")) return parsePairingPayload(value)

        pairingLink.matchEntire(value)?.let { match ->
            val base = normalize(match.groupValues[1]) ?: return null
            return Target(base, "/auth/connect/" + match.groupValues[2])
        }

        return normalize(value)?.let { Target(it) }
    }

    private fun parsePairingPayload(value: String): Target? {
        val code = codeField.find(value)?.groupValues?.get(1) ?: return null
        val urls = urlsField.find(value)?.groupValues?.get(1) ?: return null
        val first = quoted.find(urls)?.groupValues?.get(1) ?: return null
        val base = normalize(first) ?: return null
        return Target(base, "/auth/connect/$code")
    }

    private fun normalize(value: String): String? {
        if (value.contains("://") && !value.startsWith("http://") && !value.startsWith("https://")) return null
        val withScheme = if (value.startsWith("http://") || value.startsWith("https://")) value else "http://$value"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null

        val rawHost = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val host = if (rawHost.contains(":") && !rawHost.startsWith("[")) "[$rawHost]" else rawHost
        val scheme = uri.scheme?.lowercase() ?: "http"
        if (scheme != "http" && scheme != "https") return null

        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> -1
            else -> DEFAULT_PORT
        }
        val portSuffix = when {
            port == -1 -> ""
            scheme == "http" && port == 80 -> ""
            scheme == "https" && port == 443 -> ""
            else -> ":$port"
        }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return "$scheme://$host$portSuffix$path"
    }
}
