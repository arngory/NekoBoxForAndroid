package io.niko.fmt

import io.niko.fmt.v2ray.VMessBean
import io.niko.fmt.v2ray.parseDuckSoft
import io.niko.fmt.v2ray.xhttpExtraOptions
import io.niko.fmt.v2ray.xhttpSnakeCase
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XhttpTest {

    @Test
    fun snakeCase() {
        assertEquals("x_padding_bytes", xhttpSnakeCase("xPaddingBytes"))
        assertEquals("no_grpc_header", xhttpSnakeCase("noGRPCHeader"))
        assertEquals("uplink_http_method", xhttpSnakeCase("uplinkHTTPMethod"))
        assertEquals("c_max_reuse_times", xhttpSnakeCase("cMaxReuseTimes"))
        assertEquals("sc_max_each_post_bytes", xhttpSnakeCase("scMaxEachPostBytes"))
    }

    @Test
    fun extraOptions() {
        val options = xhttpExtraOptions(
            """{"xPaddingBytes": {"from": 100, "to": 1000}, "noGRPCHeader": true,
                "xmux": {"maxConcurrency": "16-32", "cMaxReuseTimes": 64, "unknownKey": 1},
                "headers": {"X-Test": "1"}, "downloadSettings": {"address": "x"}}"""
        )
        assertEquals("100-1000", options["x_padding_bytes"])
        assertEquals(true, options["no_grpc_header"])
        assertEquals(mapOf("max_concurrency" to "16-32", "c_max_reuse_times" to 64L), options["xmux"])
        assertEquals(mapOf("X-Test" to "1"), options["headers"])
        assertTrue("downloadSettings" !in options && "download_settings" !in options)
        assertTrue(xhttpExtraOptions(" ").isEmpty())

        // wrong types would fail the core config, they are left out
        val typed = xhttpExtraOptions(
            """{"scMaxConcurrentPosts": "100-200", "serverMaxHeaderBytes": 8192, "noSSEHeader": "yes",
                "scMaxEachPostBytes": 1000000, "xPaddingBytes": {"from": 1}, "xmux": {"hKeepAlivePeriod": "30"}}"""
        )
        assertTrue("sc_max_concurrent_posts" !in typed && "no_sse_header" !in typed && "x_padding_bytes" !in typed)
        assertEquals(8192L, typed["server_max_header_bytes"])
        assertEquals("1000000", typed["sc_max_each_post_bytes"])
        assertEquals(emptyMap<String, Any>(), typed["xmux"])
    }

    @Test
    fun vlessLink() {
        val bean = VMessBean().apply { alterId = -1 }
        bean.parseDuckSoft(
            ("https://uuid@example.com:443?type=xhttp&security=reality&pbk=key&sni=a.example" +
                    "&host=cdn.example&path=%2Fxh&mode=stream-one&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%7D").toHttpUrl()
        )
        assertEquals("xhttp", bean.type)
        assertEquals("cdn.example", bean.host)
        assertEquals("/xh", bean.path)
        assertEquals("stream-one", bean.xhttpMode)
        assertEquals("""{"xPaddingBytes":"100-1000"}""", bean.xhttpExtra)

        val old = VMessBean().apply { alterId = -1 }
        old.parseDuckSoft("https://uuid@example.com:443?type=splithttp&path=%2Fs".toHttpUrl())
        assertEquals("xhttp", old.type)

        val unknownMode = VMessBean().apply { alterId = -1; initializeDefaultValues() }
        unknownMode.parseDuckSoft("https://uuid@example.com:443?type=xhttp&mode=none".toHttpUrl())
        assertEquals("auto", unknownMode.xhttpMode)
    }
}
