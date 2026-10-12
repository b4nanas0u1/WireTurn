package com.wireturn.app.data.kernel

import android.net.Uri
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

// PaperFlux (external/paperflux, fork of https://github.com/Flofyyk/PaperFlux) - an OpenFlux fork
// whose exit node only serves its own profiles: an authenticated Session keyed by the profile's
// ID and access key, with the client's virtual IP - not interchangeable with plain OpenFlux. The
// fields mirror the official Android client's profile (its ProfileConfig.kt).
data class PaperFluxConfig(
    // The VPS's IP or domain - the client reaches its verification listener at 24000 + ID.
    @SerializedName("server") val server: String = "",
    // Numeric profile ID on the exit node (--profile-id).
    @SerializedName("profile_id") val profileId: String = "",
    // The profile's random access key, 32-128 characters (--profile-token) - not an SSH password.
    @SerializedName("token") val token: String = "",
    // The client's virtual IPv4 address the exit node assigned the profile (--client-ip).
    @SerializedName("client_ip") val clientIp: String = DEFAULT_CLIENT_IP,
    // "yandex" (Yandex.Docs, one or two documents), "vyandex" (Volga), "mailru", "cupsonline"
    // (the exit's room list), "boards" (Yandex Boards) or "mtslink" (MTS Link boards).
    @SerializedName("transport") val transport: String = "yandex",
    // Document links (or the Cups room list) - two parallel lanes are only for "yandex".
    @SerializedName("document_urls") val documentUrls: List<String> = emptyList(),
    // A separate empty editable Yandex document for the optional Volga fallback lane ("yandex" only).
    @SerializedName("volga_url") val volgaUrl: String = "",
    // The profile's exit is only woken up on demand - kept so a shared link round-trips; this
    // app doesn't ask for it (that needs the server's profile service before the tunnel is up).
    @SerializedName("activation_required") val activationRequired: Boolean = false
) {
    val platformDisplayName: String
        get() = when (transport) {
            "vyandex" -> "Volga Y.Docs"
            "mailru" -> "Mail.ru Docs"
            "cupsonline" -> "Cups.online"
            "boards" -> "Y.Boards"
            "mtslink" -> "MTS Link"
            else -> "Y.Docs"
        }

    /** The documents this transport takes: up to two for "yandex", exactly one otherwise. */
    val usableDocuments: List<String>
        get() = documentUrls.take(if (transport == "yandex") 2 else 1)

    val profileIdNumber: Int? get() = profileId.toIntOrNull()?.takeIf { it > 0 }

    fun isValid(): Boolean =
        profileIdNumber != null &&
            token.length in 32..128 &&
            IPV4_REGEX.matches(clientIp) &&
            usableDocuments.isNotEmpty() &&
            transport in TRANSPORTS

    fun sanitize(): PaperFluxConfig = copy(
        server = (server as Any?)?.toString()?.trim()?.take(253) ?: "",
        profileId = (profileId as Any?)?.toString()?.trim()?.filter(Char::isDigit)?.take(10) ?: "",
        token = (token as Any?)?.toString()?.trim()?.take(128) ?: "",
        clientIp = (clientIp as Any?)?.toString()?.trim()?.take(15)?.ifBlank { DEFAULT_CLIENT_IP } ?: DEFAULT_CLIENT_IP,
        transport = (transport as Any?)?.toString()?.takeIf { it in TRANSPORTS } ?: "yandex",
        documentUrls = ((documentUrls as List<*>?) ?: emptyList<Any>()).mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
            .distinct().take(2),
        volgaUrl = (volgaUrl as Any?)?.toString()?.trim()?.take(500) ?: "",
        activationRequired = (activationRequired as Any?) == true
    )

    // The official client's share link: paperflux://config?v=1&id&name&server&ip&transport&doc&token
    // [&volga][&activate=1], doc being the comma-joined documents.
    fun toUri(profileName: String? = null): String {
        val builder = Uri.Builder().scheme("paperflux").authority("config")
            .appendQueryParameter("v", "1")
            .appendQueryParameter("id", profileId)
            .appendQueryParameter("name", profileName?.takeIf { it.isNotBlank() } ?: "PaperFlux")
            .appendQueryParameter("server", server)
            .appendQueryParameter("ip", clientIp)
            .appendQueryParameter("transport", transport)
            .appendQueryParameter("doc", usableDocuments.joinToString(","))
            .appendQueryParameter("token", token)
        if (volgaUrl.isNotBlank()) builder.appendQueryParameter("volga", volgaUrl)
        if (activationRequired) builder.appendQueryParameter("activate", "1")
        return builder.build().toString()
    }

    companion object {
        const val DEFAULT_CLIENT_IP = "10.10.10.2"
        val TRANSPORTS = listOf("yandex", "vyandex", "mailru", "cupsonline", "boards", "mtslink")
        private val IPV4_REGEX = Regex("""((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)""")

        /** Splits a document field the way the official client does (commas, semicolons, lines). */
        fun documents(value: String): List<String> =
            value.split(Regex("[;,\\n\\r]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        /**
         * A paperflux://config link, or a paperflux://pool set of 2-8 servers - of which only the
         * first (primary) is taken: the standby servers it lists aren't supported here.
         */
        fun parse(url: String): PaperFluxConfig? = try {
            val trimmed = url.trim()
            when {
                trimmed.startsWith("paperflux://config", ignoreCase = true) -> fromConfigUri(Uri.parse(trimmed))
                trimmed.startsWith("paperflux://pool", ignoreCase = true) -> fromPoolUri(Uri.parse(trimmed))
                else -> null
            }?.sanitize()?.takeIf { it.isValid() }
        } catch (_: Exception) {
            null
        }

        /** The profile name a link carries: its own "name", or the pool's. */
        fun displayName(url: String): String? = try {
            val uri = Uri.parse(url.trim())
            when (uri.host) {
                "config" -> uri.getQueryParameter("name")
                "pool" -> poolJson(uri)?.get("name")?.asString
                else -> null
            }?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

        private fun fromConfigUri(uri: Uri): PaperFluxConfig? {
            if (uri.host != "config") return null
            return PaperFluxConfig(
                server = uri.getQueryParameter("server").orEmpty(),
                profileId = uri.getQueryParameter("id").orEmpty(),
                token = uri.getQueryParameter("token").orEmpty(),
                clientIp = uri.getQueryParameter("ip").orEmpty(),
                transport = uri.getQueryParameter("transport") ?: "yandex",
                documentUrls = documents(uri.getQueryParameter("doc").orEmpty()),
                volgaUrl = uri.getQueryParameter("volga").orEmpty(),
                activationRequired = uri.getQueryParameter("activate") == "1"
            )
        }

        private fun fromPoolUri(uri: Uri): PaperFluxConfig? {
            val primary = poolJson(uri)?.getAsJsonArray("profiles")?.firstOrNull()?.asJsonObject ?: return null
            fun str(vararg keys: String) = keys.firstNotNullOfOrNull { k -> primary.get(k)?.takeIf { !it.isJsonNull }?.asString }.orEmpty()
            val docs = primary.getAsJsonArray("documentUrls")?.mapNotNull { it.asString } ?: documents(str("documentUrl", "doc"))
            return PaperFluxConfig(
                server = str("server"),
                profileId = str("id"),
                token = str("token"),
                clientIp = str("clientIp", "ip"),
                transport = str("transport").ifBlank { "yandex" },
                documentUrls = docs,
                volgaUrl = str("volgaUrl"),
                activationRequired = primary.get("activationRequired")?.asBoolean == true
            )
        }

        private fun poolJson(uri: Uri): JsonObject? {
            val data = uri.getQueryParameter("data") ?: return null
            val json = String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            return JsonParser.parseString(json).asJsonObject.takeIf { it.get("v")?.asInt == 1 }
        }
    }
}
