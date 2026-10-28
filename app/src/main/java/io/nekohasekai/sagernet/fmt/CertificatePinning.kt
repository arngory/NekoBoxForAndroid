package io.nekohasekai.sagernet.fmt

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex

/**
 * SHA-256 hashes of the server's public key (SPKI), one per line or comma separated, as base64
 * or hex (colons allowed, as openssl prints them). Returns base64 for sing-box
 * certificate_public_key_sha256; throws IllegalArgumentException on anything else.
 */
fun parseCertificatePins(text: String): List<String> =
    text.split(',', '\n', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }.map { pin ->
        val hex = pin.replace(":", "")
        val bytes = if (hex.length == 64 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            hex.decodeHex()
        } else {
            pin.decodeBase64()
        }
        require(bytes != null && bytes.size == 32) { "not a SHA-256 hash: $pin" }
        bytes.base64()
    }
