package io.niko.fmt

/**
 * A DNS tunnel a profile dials through (experimental): the core's dnstt outbound carries
 * the profile's connections through DNS resolvers to a dnstt server, which forwards them
 * to its one upstream, this profile's server. Empty domain = off.
 */
data class DnsTunnel(val resolver: String, val domain: String, val key: String) {

    companion object {
        const val DNSTT = "dnstt"
        private val KEY = Regex("^[0-9a-fA-F]{64}$")
        private val RESOLVER = Regex("^(https://\\S+|(tls|udp)://\\S+|[^\\s/:]+(:\\d+)?|\\[[0-9a-fA-F:]+](:\\d+)?)$")

        fun of(bean: AbstractBean) = DnsTunnel(
            bean.dnsTunnelResolver.orEmpty(), bean.dnsTunnelDomain.orEmpty(), bean.dnsTunnelKey.orEmpty()
        )
    }

    val enabled get() = domain.isNotBlank()

    /** null when usable or off, otherwise what is wrong */
    fun validate(): String? {
        if (!enabled) return null
        return when {
            resolver.isBlank() -> "DNS tunnel: the resolver is missing"
            !RESOLVER.matches(resolver) -> "DNS tunnel: the resolver must be https://…, tls://host or udp://host"
            !KEY.matches(key) -> "DNS tunnel: the public key must be 64 hex digits"
            domain.contains("://") || domain.contains("/") -> "DNS tunnel: the domain is a name, like t.example.com"
            else -> null
        }
    }

    /** The core's dnstt outbound. */
    fun outboundOptions(): Map<String, Any> = mapOf(
        "type" to DNSTT,
        "domain" to domain,
        "public_key" to key.lowercase(),
        "resolver" to resolver,
    )
}
