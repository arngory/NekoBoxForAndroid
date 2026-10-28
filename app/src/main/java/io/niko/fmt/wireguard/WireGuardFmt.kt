package io.niko.fmt.wireguard

import io.niko.SingBoxOptions
import io.niko.utils.Util
import io.niko.utils.listByLineOrComma

fun genReserved(anyStr: String): String {
    try {
        val list = anyStr.listByLineOrComma()
        val ba = ByteArray(3)
        if (list.size == 3) {
            list.forEachIndexed { index, s ->
                val i = s
                    .replace("[", "")
                    .replace("]", "")
                    .replace(" ", "")
                    .toIntOrNull() ?: return anyStr
                ba[index] = i.toByte()
            }
            return Util.b64EncodeOneLine(ba)
        } else {
            return anyStr
        }
    } catch (e: Exception) {
        return anyStr
    }
}

fun buildSingBoxEndpointWireguardBean(bean: WireGuardBean): SingBoxOptions.Endpoint_WireGuardOptions {
    val awg = try {
        AmneziaWG.parse(bean.amneziaWG)
    } catch (e: IllegalArgumentException) {
        throw Exception("${bean.displayName()}: ${e.message}")
    }
    return SingBoxOptions.Endpoint_WireGuardOptions().apply {
        type = "wireguard"
        address = bean.localAddress.listByLineOrComma()
        private_key = bean.privateKey
        mtu = bean.mtu
        peers = listOf(SingBoxOptions.EndpointWireGuardPeer().apply {
            address = bean.serverAddress
            port = bean.serverPort
            public_key = bean.peerPublicKey
            pre_shared_key = bean.peerPreSharedKey
            allowed_ips = listOf("0.0.0.0/0", "::/0")
            if (bean.reserved.isNotBlank()) reserved = genReserved(bean.reserved)
            // keeps the NAT mapping (and the server's conntrack entry) alive while idle
            if (bean.persistentKeepalive > 0) {
                persistent_keepalive_interval = bean.persistentKeepalive.coerceAtMost(65535) // uint16
            }
            // AmneziaWG 3.x: a "min-max" range re-picked at every keepalive
            awg.keepalive?.let {
                persistent_keepalive_interval = null
                _hack_config_map["persistent_keepalive_interval"] = it.toLongOrNull() ?: it
            }
        })
        // the core reads the AmneziaWG fields at the endpoint root, like an awg .conf [Interface]
        _hack_config_map.putAll(awg.fields)
    }
}
