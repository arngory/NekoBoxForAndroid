package io.niko.fmt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YggdrasilTest {

    @Test
    fun yggdrasilAddresses() {
        assertTrue("200:f9b3:68eb:b0a6:c6e1:912f:de84:86de".isYggdrasilAddress())
        assertTrue("[20d:1ab2:fc62:a12b:b288:d680:bc74:1930]".isYggdrasilAddress())
        assertTrue("300:1234::1".isYggdrasilAddress()) // a node's /64 subnet
        assertFalse("2001:db8::1".isYggdrasilAddress())
        assertFalse("400::1".isYggdrasilAddress())
        assertFalse("example.com".isYggdrasilAddress())
        assertFalse("1.2.3.4".isYggdrasilAddress())
        assertFalse("200:zz::1".isYggdrasilAddress())
    }
}
