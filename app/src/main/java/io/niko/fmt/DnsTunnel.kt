package io.niko.fmt

/**
 * A DNS tunnel a profile dials through (experimental). The core runs the tunnel client and
 * the profile reaches its server through it:
 * - dnstt: the dnstt server forwards to its one upstream, so this profile's address is unused;
 * - masterdnsvpn: in SOCKS5 mode the server connects to this profile's server, in TCP mode
 *   (fixed upstream) to its one target.
 * Empty domain = off.
 */
data class DnsTunnel(
    val engine: String,
    val resolver: String,
    val domain: String,
    val key: String,
    val method: Int = 2,
    val fixedUpstream: Boolean = false,
) {

    companion object {
        const val DNSTT = "dnstt"
        const val MASTERDNSVPN = "masterdnsvpn"
        private val DNSTT_KEY = Regex("^[0-9a-fA-F]{64}$")
        private val DNSTT_RESOLVER =
            Regex("^(https://\\S+|(tls|udp)://\\S+|[^\\s/:]+(:\\d+)?|\\[[0-9a-fA-F:]+](:\\d+)?)$")
        // 8.8.8.8, 1.1.1.1:5353, 192.168.1.0/30, [2001:db8::1]:53
        private val MDV_RESOLVER =
            Regex("^([0-9.]+(/\\d+)?(:\\d+)?|\\[[0-9a-fA-F:]+](:\\d+)?|[0-9a-fA-F:]+(/\\d+)?)$")
        private val DOMAIN = Regex("^[A-Za-z0-9.-]+$")

        fun of(bean: AbstractBean) = DnsTunnel(
            engine = bean.dnsTunnel.orEmpty().ifEmpty { DNSTT },
            resolver = bean.dnsTunnelResolver.orEmpty(),
            domain = bean.dnsTunnelDomain.orEmpty(),
            key = bean.dnsTunnelKey.orEmpty(),
            method = bean.dnsTunnelMethod ?: 2,
            fixedUpstream = bean.dnsTunnelFixedUpstream == true,
        )
    }

    val enabled get() = domain.isNotBlank()

    /** dnstt and a masterdnsvpn server in TCP mode forward to one upstream, the profile's address is unused */
    val ignoresServerAddress get() = engine == DNSTT || fixedUpstream

    private val domains get() = domain.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    private val resolvers get() = resolver.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** null when usable or off, otherwise what is wrong */
    fun validate(): String? {
        if (!enabled) return null
        if (domains.any { !DOMAIN.matches(it) }) return "DNS tunnel: the domain is a name, like t.example.com"
        return when (engine) {
            DNSTT -> when {
                domains.size != 1 -> "DNS tunnel: dnstt takes one domain"
                resolver.isBlank() -> "DNS tunnel: the resolver is missing"
                !DNSTT_RESOLVER.matches(resolver) -> "DNS tunnel: the resolver must be https://…, tls://host or udp://host"
                !DNSTT_KEY.matches(key) -> "DNS tunnel: the public key must be 64 hex digits"
                else -> null
            }

            MASTERDNSVPN -> when {
                resolvers.isEmpty() -> "DNS tunnel: no resolvers, one IP per line"
                resolvers.any { !MDV_RESOLVER.matches(it) } -> "DNS tunnel: MasterDnsVPN resolvers are IPs (or IP/prefix), with an optional :port"
                method !in 0..5 -> "DNS tunnel: the encryption method is 0-5"
                method != 0 && key.isBlank() -> "DNS tunnel: the key is missing"
                else -> null
            }

            else -> "DNS tunnel: unknown engine $engine"
        }
    }

    /** The core outbound. */
    fun outboundOptions(protectPath: String?): Map<String, Any> = when (engine) {
        MASTERDNSVPN -> buildMap {
            put("type", MASTERDNSVPN)
            put("domains", domains)
            put("resolvers", resolvers)
            put("encryption_method", method)
            if (key.isNotBlank()) put("encryption_key", key)
            put("mode", if (fixedUpstream) "tcp" else "socks5")
            // its own sockets must stay outside the VPN
            if (protectPath != null) put("protect_path", protectPath)
        }

        else -> mapOf(
            "type" to DNSTT,
            "domain" to domain.trim(),
            "public_key" to key.lowercase(),
            "resolver" to resolver.trim(),
        )
    }
}
