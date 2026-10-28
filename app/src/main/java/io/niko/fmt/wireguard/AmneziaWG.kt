package io.niko.fmt.wireguard

/**
 * AmneziaWG parameters of a WireGuard profile, kept as the "Key = value" lines of an
 * AmneziaWG .conf [Interface] (Jc, S1, H1, I1, HeaderProtectionKey, ...), and their
 * sing-box endpoint fields (from sing-box-lx docs-lx/amneziawg-explained.md §7).
 */
object AmneziaWG {

    // .conf key (lower case) -> endpoint field
    private val NUMBER_KEYS = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4").associateWith { it }
    private val RANGE_KEYS = listOf("h1", "h2", "h3", "h4").associateWith { it } + mapOf(
        "contentpaddingaddition" to "content_padding_addition",
        "rekeyaftertime" to "rekey_after_time",
        "rekeytimeout" to "rekey_timeout",
        "rejectaftertime" to "reject_after_time",
        "keepalivetimeout" to "keepalive_timeout",
        "maxhandshakeattempts" to "max_handshake_attempts",
    )
    private val STRING_KEYS = listOf("i1", "i2", "i3", "i4", "i5", "id", "ip", "ib").associateWith { it } +
            mapOf("headerprotectionkey" to "header_protection_key")
    private val SWITCH_KEYS = mapOf(
        "randomtrailers" to "random_trailers",
        "disablecookies" to "disable_cookies",
    )

    // a peer keepalive range (AWG 3.x) travels in the same text
    const val KEEPALIVE_KEY = "persistentkeepalive"

    /** Server-side knobs a client .conf may carry; the client does not need them. */
    private val IGNORED_KEYS = setOf("advancedsecurity")

    private const val UINT32_MAX = 0xFFFFFFFFL
    private val RANGE = Regex("""^(\d+)\s*(?:-\s*(\d+))?$""")

    private fun normalize(key: String) = key.trim().lowercase().replace("_", "").replace("-", "")

    /** Whether an [Interface] or [Peer] .conf key belongs to AmneziaWG. */
    fun isKey(key: String): Boolean {
        val normalized = normalize(key)
        return normalized in NUMBER_KEYS || normalized in RANGE_KEYS || normalized in STRING_KEYS ||
                normalized in SWITCH_KEYS || normalized in IGNORED_KEYS
    }

    class Parsed(val fields: Map<String, Any>, val keepalive: String?) {
        /** s4 junk is prepended to every data packet: such a profile needs a smaller MTU. */
        val hasTransportJunk get() = ((fields["s4"] as? Long) ?: 0L) > 0
    }

    /**
     * Parses the "Key = value" lines. A wrong key or value is an error naming the line:
     * a typo must not silently turn an AmneziaWG profile into plain WireGuard.
     */
    fun parse(text: String?): Parsed {
        val fields = linkedMapOf<String, Any>()
        var keepalive: String? = null
        for (rawLine in text.orEmpty().lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            val separator = line.indexOf('=')
            if (separator <= 0) throw IllegalArgumentException("AmneziaWG: expected \"Key = value\": $line")
            val rawKey = line.substring(0, separator)
            val key = normalize(rawKey)
            // I1..I5 are case-sensitive tag strings: take the value verbatim
            val value = line.substring(separator + 1).trim()
            if (value.isEmpty()) continue
            when (key) {
                in NUMBER_KEYS -> fields[NUMBER_KEYS.getValue(key)] =
                    value.toLongOrNull()?.takeIf { it in 0..UINT32_MAX }
                        ?: throw IllegalArgumentException("AmneziaWG: ${rawKey.trim()} must be a number: $value")

                in RANGE_KEYS -> fields[RANGE_KEYS.getValue(key)] = range(rawKey, value)
                KEEPALIVE_KEY -> keepalive = range(rawKey, value).toString()
                in STRING_KEYS -> fields[STRING_KEYS.getValue(key)] = value
                in SWITCH_KEYS -> when (value.lowercase()) {
                    "on", "true", "1", "yes" -> fields[SWITCH_KEYS.getValue(key)] = true
                    "off", "false", "0", "no" -> {}
                    else -> throw IllegalArgumentException("AmneziaWG: ${rawKey.trim()} must be on or off: $value")
                }

                in IGNORED_KEYS -> {}
                else -> throw IllegalArgumentException("AmneziaWG: unknown parameter ${rawKey.trim()}")
            }
        }
        return Parsed(fields, keepalive)
    }

    /** "N" as a number, "N-M" as the string sing-box takes for a range. */
    private fun range(rawKey: String, value: String): Any {
        val match = RANGE.matchEntire(value)
            ?: throw IllegalArgumentException("AmneziaWG: ${rawKey.trim()} must be a number or min-max: $value")
        val start = match.groupValues[1].toLongOrNull()?.takeIf { it <= UINT32_MAX }
        val end = match.groupValues[2].ifEmpty { null }?.let { it.toLongOrNull()?.takeIf { e -> e <= UINT32_MAX } }
        if (start == null || match.groupValues[2].isNotEmpty() && (end == null || end < start)) {
            throw IllegalArgumentException("AmneziaWG: ${rawKey.trim()} must be a number or min-max: $value")
        }
        return if (end == null || end == start) start else "$start-$end"
    }
}
