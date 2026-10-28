package io.niko.group

import io.niko.fmt.shadowsocks.ShadowsocksBean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.error.YAMLException

class RawUpdaterTest {

    @Test
    fun clashYamlRefusesGlobalTags() {
        for (text in listOf(
            "proxies:\n  - !!java.net.URL [\"http://example.com\"]\n",
            "proxies: !!java.util.Date 2024-01-01\n",
        )) {
            try {
                loadClashYaml(text)
                throw AssertionError("loaded: $text")
            } catch (_: YAMLException) {
            }
        }
    }

    @Test
    fun clashYamlKeepsPlainValues() {
        val yaml = loadClashYaml("proxies:\n  - {name: a, port: 443, password: !str 123}\n")
        val proxy = (yaml["proxies"] as List<*>)[0] as Map<*, *>
        assertEquals("123", proxy["password"])
        assertEquals(443, (proxy["port"] as Number).toInt())
    }

    @Test
    fun sip008() {
        val json = JSONObject(
            """{"version": 1, "servers": [
                {"id": "1", "remarks": "one", "server": "a.example", "server_port": 8388,
                 "password": "p1", "method": "chacha20-ietf-poly1305"},
                {"id": "2", "remarks": "two", "server": "b.example", "server_port": 443,
                 "password": "p2", "method": "aes-128-gcm", "plugin": "v2ray-plugin",
                 "plugin_opts": "tls;host=b.example"}
            ]}"""
        )
        val beans = RawUpdater.parseJSON(json).map { it as ShadowsocksBean }
        assertEquals(listOf("one", "two"), beans.map { it.name })
        assertEquals("a.example", beans[0].serverAddress)
        assertEquals(8388, beans[0].serverPort)
        assertEquals("chacha20-ietf-poly1305", beans[0].method)
        assertTrue(beans[1].plugin.startsWith("v2ray-plugin;"))
    }
}
