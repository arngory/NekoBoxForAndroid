package io.nekohasekai.sagernet.group

import okio.ByteString.Companion.decodeBase64

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
