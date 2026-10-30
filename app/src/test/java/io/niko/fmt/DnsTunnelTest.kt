package io.niko.fmt

import io.niko.fmt.socks.SOCKSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsTunnelTest {

    private val key = "a".repeat(64)

    @Test
    fun validation() {
        assertNull(DnsTunnel("", "", "").validate()) // off
        assertNull(DnsTunnel("https://dns.example/dns-query", "t.example.com", key).validate())
        assertNull(DnsTunnel("udp://8.8.8.8", "t.example.com", key).validate())
        assertNull(DnsTunnel("8.8.8.8:53", "t.example.com", key).validate())
        assertNotNull(DnsTunnel("", "t.example.com", key).validate())
        assertNotNull(DnsTunnel("ftp://x", "t.example.com", key).validate())
        assertNotNull(DnsTunnel("udp://8.8.8.8", "t.example.com", "abc").validate())
        assertNotNull(DnsTunnel("udp://8.8.8.8", "https://t.example.com", key).validate())
    }

    @Test
    fun beanRoundTrip() {
        val bean = SOCKSBean().apply {
            initializeDefaultValues()
            dnsTunnel = DnsTunnel.DNSTT
            dnsTunnelResolver = "tls://1.1.1.1"
            dnsTunnelDomain = "t.example.com"
            dnsTunnelKey = key
        }
        val copy = KryoConverters.deserialize(SOCKSBean(), KryoConverters.serialize(bean))
        assertEquals(DnsTunnel.of(bean), DnsTunnel.of(copy))
        assertEquals(DnsTunnel.DNSTT, copy.dnsTunnel)
        assertEquals("dnstt", DnsTunnel.of(copy).outboundOptions()["type"])
    }
}
