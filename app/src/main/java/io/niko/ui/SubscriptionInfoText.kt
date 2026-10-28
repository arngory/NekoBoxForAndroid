package io.niko.ui

import android.content.Context
import io.niko.R
import io.niko.database.ProxyGroup
import io.niko.group.SubscriptionUsage
import io.niko.group.parseSubscriptionUserinfo
import io.niko.utils.Util
import io.niko.utils.toBytesString

/** Traffic and expiry from the provider's Subscription-Userinfo, "" when it sent none. */
fun SubscriptionUsage.describe(context: Context, separator: String = "\n"): String {
    val parts = mutableListOf<String>()
    if (used > 0 || total > 0) parts += if (remaining > 0) {
        context.getString(R.string.subscription_traffic, used.toBytesString(), remaining.toBytesString())
    } else {
        context.getString(R.string.subscription_used, used.toBytesString())
    }
    if (expire > 0) parts += context.getString(R.string.subscription_expire, Util.timeStamp2Text(expire * 1000))
    return parts.joinToString(separator)
}

/** One line for the main screen: usage of the group's subscription, marked when it runs out. */
fun ProxyGroup.subscriptionStatusLine(context: Context): String? {
    val usage = parseSubscriptionUserinfo(subscription?.subscriptionUserinfo) ?: return null
    val text = usage.describe(context, " · ").ifBlank { return null }
    return if (usage.runningOut(System.currentTimeMillis() / 1000)) "⚠ $text" else text
}
