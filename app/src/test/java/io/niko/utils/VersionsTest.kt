package io.niko.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {

    @Test
    fun ordersReleasesAndPreReleases() {
        assertTrue(isNewerVersion("v1.5.1", "1.5.0"))
        assertTrue(isNewerVersion("v1.5.10", "1.5.1"))
        assertTrue(isNewerVersion("v1.5.1-beta.10", "1.5.1-beta.1"))
        assertTrue(isNewerVersion("v1.5.1", "1.5.1-beta.3"))
        assertTrue(isNewerVersion("v1.5.1-beta.1", "1.5.0"))
        assertTrue(isNewerVersion("v1.5.1-rc.1", "1.5.1-beta.9"))
        assertTrue(isNewerVersion("v2.0", "1.9.9"))
    }

    @Test
    fun neverOffersSameOrOlder() {
        assertFalse(isNewerVersion("v1.5.0", "1.5.0"))
        assertFalse(isNewerVersion("v1.5.0", "1.5.1-beta.1"))
        assertFalse(isNewerVersion("v1.5.0-beta.3", "1.5.1"))
        assertFalse(isNewerVersion("v1.5.1-beta.1", "1.5.1"))
        assertFalse(isNewerVersion("v1.5.0", "1.5.0.0"))
    }
}
