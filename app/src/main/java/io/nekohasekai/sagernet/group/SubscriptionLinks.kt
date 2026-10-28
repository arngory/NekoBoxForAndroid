package io.nekohasekai.sagernet.group

import java.net.URI
import java.net.URLDecoder

data class SubscriptionImport(val url: String, val name: String?)

/**
 * The subscription URL and name of an "add subscription" deep link of another client:
 * clash/clashmeta://install-config?url=…&name=…, sing-box://import-remote-profile?url=…#name,
 * hiddify://import/<url>#name, v2rayng://install-config|install-sub?url=…&name=….
 * Null for anything else, including sn://subscription with an exported group.
 */
fun parseSubscriptionDeepLink(link: String): SubscriptionImport? {
    val uri = try {
        URI(link)
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: uri.rawAuthority?.lowercase()
    val query = uri.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }
    val fragment = uri.fragment?.takeIf { it.isNotBlank() }
    val result = when {
        scheme == "sn" && host == "subscription" ||
                scheme in setOf("clash", "clashmeta") && host == "install-config" ||
                scheme == "v2rayng" && host in setOf("install-config", "install-sub") ->
            query["url"]?.let { SubscriptionImport(it, query["name"]?.takeIf { n -> n.isNotBlank() } ?: fragment) }

        scheme == "sing-box" && host == "import-remote-profile" ->
            query["url"]?.let { SubscriptionImport(it, fragment ?: query["name"]) }

        scheme == "hiddify" && host == "import" -> {
            // the URL follows as the path, sometimes percent-encoded
            val raw = link.substringAfter("://").substringAfter('/').substringBefore('#')
            val encoded = raw.startsWith("http%3A", ignoreCase = true) || raw.startsWith("https%3A", ignoreCase = true)
            val url = if (encoded) URLDecoder.decode(raw, "UTF-8") else raw
            SubscriptionImport(url, fragment)
        }

        else -> null
    }
    return result?.takeIf {
        it.url.startsWith("https://", ignoreCase = true) || it.url.startsWith("http://", ignoreCase = true)
    }
}
