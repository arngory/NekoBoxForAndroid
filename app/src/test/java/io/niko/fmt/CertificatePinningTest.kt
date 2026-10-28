package io.niko.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificatePinningTest {

    private val base64 = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="
    private val hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun acceptsBase64AndHex() {
        val colonHex = hex.chunked(2).joinToString(":").uppercase()
        assertEquals(listOf(base64, base64, base64), parseCertificatePins("$base64\n$hex, $colonHex"))
        assertTrue(parseCertificatePins("  ").isEmpty())
    }

    @Test
    fun rejectsOtherValues() {
        for (pin in listOf("abc", "e3b0c442", "AAAA")) {
            try {
                parseCertificatePins(pin)
                throw AssertionError("accepted: $pin")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
