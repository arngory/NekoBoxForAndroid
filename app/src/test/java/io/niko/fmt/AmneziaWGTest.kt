package io.niko.fmt

import io.niko.fmt.wireguard.AmneziaWG
import io.niko.fmt.wireguard.buildSingBoxEndpointWireguardBean
import io.niko.group.RawUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmneziaWGTest {

    // an AmneziaWG 3 export, as in sing-box-lx docs-lx/amneziawg-explained.md §7
    private val conf = """
        [Interface]
        PrivateKey = 8NiwxNoAeRziKOybax3GfIMfoaNCnmvaIkeA2YrAkns=
        Address = 10.8.1.7/32
        Jc = 4
        Jmin = 10
        Jmax = 50
        S1 = 55
        S2 = 42
        S3 = 40
        S4 = 12
        H1 = 1
        H2 = 43613244-384550127
        H3 = 3
        H4 = 4
        I1 = <b 0xc6000000010843290a47ba8ba2ed000044d0e3efd9326adb60561baa3bc4b52471b2d459ddcc9a508dffddc97e4d40d811>
        HeaderProtectionKey = QOzasG3b8oTTSIbsfZqzejt8KXNIFDFwHXnvRYYyKy4=
        ContentPaddingAddition = 10-100
        RandomTrailers = on
        AdvancedSecurity = on

        [Peer]
        PublicKey = 2mPF9x26FrDB4KFS9p/ZEg6MuEv9mP3eceA5zecq/2g=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 203.0.113.30:30565
        PersistentKeepalive = 25-35
    """.trimIndent()

    @Test
    fun confImportAndEndpoint() {
        val bean = RawUpdater.parseWireGuard(conf).single()
        // s4 junk without an MTU in the .conf: the import picks one that fits
        assertEquals(1280, bean.mtu)
        assertEquals(0, bean.persistentKeepalive)
        assertTrue(bean.amneziaWG.contains("I1 = <b 0xc600"))
        assertTrue(bean.amneziaWG.contains("PersistentKeepalive = 25-35"))

        val endpoint = buildSingBoxEndpointWireguardBean(bean).asMap()
        assertEquals(4.0, (endpoint["jc"] as Number).toDouble(), 0.0)
        assertEquals(12.0, (endpoint["s4"] as Number).toDouble(), 0.0)
        assertEquals("43613244-384550127", endpoint["h2"])
        assertEquals("10-100", endpoint["content_padding_addition"])
        assertEquals(true, endpoint["random_trailers"])
        assertEquals("QOzasG3b8oTTSIbsfZqzejt8KXNIFDFwHXnvRYYyKy4=", endpoint["header_protection_key"])
        // I1 is a case-sensitive tag string and stays verbatim
        assertTrue((endpoint["i1"] as String).startsWith("<b 0xc6000000"))
        assertFalse(endpoint.containsKey("advancedsecurity") || endpoint.containsKey("advanced_security"))
        val peer = (endpoint["peers"] as List<*>).single() as Map<*, *>
        assertEquals("25-35", peer["persistent_keepalive_interval"])
    }

    @Test
    fun plainWireGuardHasNoAmneziaFields() {
        val bean = RawUpdater.parseWireGuard(
            conf.lines().filterNot { AmneziaWG.isKey(it.substringBefore("=")) }.joinToString("\n")
                .replace("PersistentKeepalive = 25-35", "PersistentKeepalive = 25")
        ).single()
        assertEquals("", bean.amneziaWG)
        assertEquals(1420, bean.mtu)
        assertEquals(25, bean.persistentKeepalive)
        val endpoint = buildSingBoxEndpointWireguardBean(bean).asMap()
        assertFalse(endpoint.containsKey("jc"))
    }

    @Test
    fun badParametersAreErrors() {
        for (text in listOf("Jc = four", "H1 = 9-3", "Jx = 1", "RandomTrailers = maybe", "just text")) {
            try {
                AmneziaWG.parse(text)
                throw AssertionError("accepted: $text")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(mapOf("jc" to 3L), AmneziaWG.parse("# comment\njc=3\n\nS1 =").fields)
    }
}
