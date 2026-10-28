package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionHeadersTest {

    @Test
    fun textHeaders() {
        assertEquals("My VPN", decodeSubscriptionHeader(" My VPN "))
        // "Мой VPN" in base64
        assertEquals("Мой VPN", decodeSubscriptionHeader("base64:0JzQvtC5IFZQTg=="))
        assertEquals("", decodeSubscriptionHeader(null))
        assertEquals("", decodeSubscriptionHeader("base64:@@@"))
    }

    @Test
    fun updateInterval() {
        assertEquals(12 * 60, subscriptionUpdateIntervalMinutes("12"))
        assertNull(subscriptionUpdateIntervalMinutes("0"))
        assertNull(subscriptionUpdateIntervalMinutes("soon"))
        assertNull(subscriptionUpdateIntervalMinutes(null))
    }

    @Test
    fun userinfo() {
        val usage = parseSubscriptionUserinfo("upload=100; download=900; total=10000; expire=2000000000")!!
        assertEquals(1000L, usage.used)
        assertEquals(9000L, usage.remaining)
        assertEquals(2000000000L, usage.expire)
        assertFalse(usage.runningOut(nowSeconds = 1000000000))
        assertTrue(usage.runningOut(nowSeconds = 2000000000 - 3600))

        val almostUsed = parseSubscriptionUserinfo("upload=0;download=9600;total=10000")!!
        assertTrue(almostUsed.runningOut(nowSeconds = 0))
        // "reupload" must not be read as upload
        assertEquals(5L, parseSubscriptionUserinfo("reupload=7; download=5")!!.used)
        assertNull(parseSubscriptionUserinfo("upload=0; download=0; total=0; expire=0"))
        assertNull(parseSubscriptionUserinfo(""))
    }

    @Test
    fun deviceIdPerProvider() {
        val a = subscriptionDeviceId("secret", "https://a.example/sub/1")
        assertEquals(32, a.length)
        assertEquals(a, subscriptionDeviceId("secret", "https://A.example/sub/other"))
        assertTrue(a != subscriptionDeviceId("secret", "https://b.example/sub/1"))
        assertTrue(a != subscriptionDeviceId("other", "https://a.example/sub/1"))
    }
}
