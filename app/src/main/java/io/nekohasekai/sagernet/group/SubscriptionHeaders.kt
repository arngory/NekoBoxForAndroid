package io.nekohasekai.sagernet.group

import okio.ByteString.Companion.decodeBase64
import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A text subscription header (profile-title, announce): plain or "base64:<data>". */
fun decodeSubscriptionHeader(value: String?): String {
    val text = value?.trim().orEmpty()
    if (!text.startsWith("base64:", ignoreCase = true)) return text
    return text.substring("base64:".length).trim().decodeBase64()?.utf8()?.trim().orEmpty()
}

/** profile-update-interval (hours) as minutes for the auto update, null when missing or odd. */
fun subscriptionUpdateIntervalMinutes(value: String?): Int? =
    value?.trim()?.toIntOrNull()?.takeIf { it in 1..24 * 30 }?.times(60)

/** Subscription-Userinfo: "upload=…; download=…; total=…; expire=…" (bytes, unix seconds). */
data class SubscriptionUsage(val used: Long, val total: Long, val expire: Long) {
    val remaining get() = total - used

    /** Ends within [days] or has less than [percent] % of the traffic left. */
    fun runningOut(nowSeconds: Long, days: Int = 3, percent: Int = 5): Boolean =
        (expire > 0 && expire - nowSeconds < days * 86400L) ||
                (total > 0 && remaining * 100 < total * percent)
}

fun parseSubscriptionUserinfo(header: String?): SubscriptionUsage? {
    if (header.isNullOrBlank()) return null
    fun value(name: String) = Regex("""(?:^|[;\s])$name\s*=\s*(\d+)""").find(header)
        ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val usage = SubscriptionUsage(
        used = value("upload") + value("download"),
        total = value("total"),
        expire = value("expire"),
    )
    return usage.takeIf { it.used > 0 || it.total > 0 || it.expire > 0 }
}

/**
 * x-hwid for one provider: HMAC of the install secret and the subscription host, so providers
 * can not link the same device across subscriptions while each sees a stable id.
 */
fun subscriptionDeviceId(installSecret: String, subscriptionLink: String): String {
    val host = try {
        URI(subscriptionLink).host
    } catch (_: Exception) {
        null
    } ?: subscriptionLink
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(installSecret.toByteArray(), "HmacSHA256"))
    return mac.doFinal(host.lowercase().toByteArray()).take(16).joinToString("") { "%02x".format(it) }
}
