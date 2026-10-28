package moe.matsuri.nb4a

import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import moe.matsuri.nb4a.SingBoxOptions.RuleSet
import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleSetTest {

    private val domainSet = "rule-set:https://example.com/geosite-ru-blocked.srs"
    private val ipSet = "rule-set:https://example.com/geoip-ru-blocked.srs"

    @Test
    fun routeRuleKeepsRuleSetsOfBothLists() {
        val rule = Rule_DefaultOptions()
        rule.makeSingBoxRule(listOf("geosite:ru", domainSet, "example.org"), false)
        rule.makeSingBoxRule(listOf("geoip:ru", ipSet, "10.0.0.0/8"), true)
        assertEquals(listOf("geosite:ru", domainSet, "geoip:ru", ipSet), rule.rule_set)
        assertEquals(listOf("example.org"), rule.domain_suffix)
        assertEquals(listOf("10.0.0.0/8"), rule.ip_cidr)
    }

    @Test
    fun dnsRuleSkipsIPRuleSets() {
        val rule = DNSRule_DefaultOptions()
        rule.makeSingBoxRule(listOf(domainSet, ipSet, "example.org"))
        assertEquals(listOf(domainSet), rule.rule_set)
        assertEquals(listOf("example.org"), rule.domain_suffix)

        val onlyIP = DNSRule_DefaultOptions()
        onlyIP.makeSingBoxRule(listOf(ipSet))
        assertNull(onlyIP.rule_set)
    }

    @Test
    fun remoteRuleSetDefinition() {
        val sets = mutableListOf<RuleSet>()
        generateRuleSet(
            listOf(domainSet, "rule-set:https://example.com/list.json?v=2", "geosite:ru"), sets, 72
        )
        val remote = sets[0]
        assertEquals("remote", remote.type)
        assertEquals(domainSet, remote.tag)
        assertEquals("https://example.com/geosite-ru-blocked.srs", remote.url)
        assertEquals("binary", remote.format)
        assertEquals("72h", remote.update_interval)
        assertEquals("source", sets[1].format)
        assertEquals("local", sets[2].type)
    }
}
