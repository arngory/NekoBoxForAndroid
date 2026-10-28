package io.niko.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionLinksTest {

    private val url = "https://example.com/sub/abc?token=1&x=2"
    private val encoded = "https%3A%2F%2Fexample.com%2Fsub%2Fabc%3Ftoken%3D1%26x%3D2"

    @Test
    fun otherClientsLinks() {
        assertEquals(
            SubscriptionImport(url, "My VPN"),
            parseSubscriptionDeepLink("sing-box://import-remote-profile?url=$encoded#My%20VPN")
        )
        assertEquals(
            SubscriptionImport(url, "My VPN"),
            parseSubscriptionDeepLink("clashmeta://install-config?url=$encoded&name=My%20VPN")
        )
        assertEquals(
            SubscriptionImport(url, null),
            parseSubscriptionDeepLink("v2rayng://install-sub?url=$encoded")
        )
        assertEquals(
            SubscriptionImport(url, "My VPN"),
            parseSubscriptionDeepLink("hiddify://import/$url#My VPN".replace(" ", "%20"))
        )
        assertEquals(
            SubscriptionImport(url, null),
            parseSubscriptionDeepLink("hiddify://import/$encoded")
        )
        assertEquals(
            SubscriptionImport(url, "n"),
            parseSubscriptionDeepLink("sn://subscription?url=$encoded&name=n")
        )
    }

    @Test
    fun rejectsOthers() {
        assertNull(parseSubscriptionDeepLink("sn://subscription?eJxLz.."))
        assertNull(parseSubscriptionDeepLink("sing-box://import-remote-profile?url=file%3A%2F%2F%2Fsdcard%2Fa"))
        assertNull(parseSubscriptionDeepLink("vless://uuid@host:443"))
        assertNull(parseSubscriptionDeepLink("not a link"))
    }
}
