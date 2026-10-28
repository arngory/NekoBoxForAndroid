package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.ktx.Logs
import org.json.JSONArray
import org.json.JSONObject

/** Transport options of the core's XHTTP client (sing-box-lx option/v2ray_xhttp.go). */
private val XHTTP_OPTION_KEYS = setOf(
    "headers", "x_padding_bytes", "xmux", "no_grpc_header", "session_placement", "session_key",
    "seq_placement", "seq_key", "session_table", "session_length", "uplink_data_placement",
    "uplink_data_key", "uplink_chunk_size", "uplink_http_method", "x_padding_obfs_mode",
    "x_padding_key", "x_padding_header", "x_padding_placement", "x_padding_method",
    "sc_max_each_post_bytes", "sc_min_posts_interval_ms", "sc_max_concurrent_posts",
    "server_max_header_bytes", "no_sse_header", "sc_max_buffered_posts", "sc_stream_up_server_secs",
)
private val XMUX_OPTION_KEYS = setOf(
    "max_concurrency", "max_connections", "c_max_reuse_times", "h_max_request_times",
    "h_max_reusable_secs", "h_keep_alive_period",
)
// options the core takes as "min-max" strings, which Xray may write as {"from": a, "to": b}
private val RANGE_KEYS = setOf(
    "x_padding_bytes", "sc_max_each_post_bytes", "sc_min_posts_interval_ms", "sc_stream_up_server_secs",
    "uplink_chunk_size", "session_length",
)
// numbers and booleans in the core; Xray wrote some of them as ranges ("scMaxConcurrentPosts": "100-200")
private val INT_KEYS = setOf("sc_max_concurrent_posts", "server_max_header_bytes", "sc_max_buffered_posts")
private val BOOL_KEYS = setOf("no_grpc_header", "x_padding_obfs_mode", "no_sse_header")

/** xPaddingBytes -> x_padding_bytes, noGRPCHeader -> no_grpc_header, uplinkHTTPMethod -> uplink_http_method */
internal fun xhttpSnakeCase(key: String) = key
    .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
    .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
    .lowercase()

/**
 * Xray's XHTTP "extra" JSON (camelCase) as core transport options. Keys the core does not know
 * (downloadSettings, ...) are left out: sing-box rejects unknown fields.
 */
fun xhttpExtraOptions(extra: String): Map<String, Any> {
    if (extra.isBlank()) return emptyMap()
    val json = JSONObject(extra)
    val options = mutableMapOf<String, Any>()
    for (rawKey in json.keys()) {
        val key = xhttpSnakeCase(rawKey)
        if (key !in XHTTP_OPTION_KEYS) continue
        val value = json.get(rawKey)
        // a value of the wrong type would fail the whole config: leave it out like an unknown key
        options[key] = when {
            key == "xmux" -> if (value is JSONObject) xmuxOptions(value) else continue
            key == "headers" -> if (value is JSONObject) jsonValue(value) else continue
            key in INT_KEYS -> integralValue(value) ?: continue
            key in BOOL_KEYS -> value as? Boolean ?: continue
            key in RANGE_KEYS && value is JSONObject -> {
                val from = integralValue(value.opt("from") ?: continue) ?: continue
                val to = integralValue(value.opt("to") ?: continue) ?: continue
                "$from-$to"
            }

            key in RANGE_KEYS && value is Number -> integralValue(value)?.toString() ?: continue
            else -> value as? String ?: continue
        }
    }
    return options
}

private fun xmuxOptions(xmux: JSONObject) = buildMap {
    for (rawKey in xmux.keys()) {
        val key = xhttpSnakeCase(rawKey)
        val value = xmux.get(rawKey)
        when {
            key !in XMUX_OPTION_KEYS -> {}
            key == "h_keep_alive_period" -> integralValue(value)?.let { put(key, it) }
            // ranges: "min-max", a number or [min, max]
            value is String || value is JSONArray -> put(key, jsonValue(value))
            else -> integralValue(value)?.let { put(key, it) }
        }
    }
}

private fun integralValue(value: Any): Long? = when (value) {
    is Int, is Long -> (value as Number).toLong()
    is Number -> value.toDouble().takeIf { it % 1.0 == 0.0 }?.toLong()
    else -> null
}

private fun jsonValue(value: Any): Any = when (value) {
    is JSONObject -> buildMap { for (key in value.keys()) put(key, jsonValue(value.get(key))) }
    is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
    else -> value
}

/** XHTTP transport options of a profile: host, path and mode, plus its extra JSON. */
fun buildXhttpOptions(bean: StandardV2RayBean): Map<String, Any> {
    val extra = try {
        xhttpExtraOptions(bean.xhttpExtra ?: "")
    } catch (e: Exception) {
        throw Exception("XHTTP extra (${bean.displayName()}): ${e.message}")
    }
    if (bean.xhttpExtra?.contains("\"downloadSettings\"") == true) {
        // the profile still works, but downloads go to the main server instead of the separate one
        Logs.w("XHTTP extra (${bean.displayName()}): downloadSettings is not supported by the core, left out")
    }
    return buildMap {
        putAll(extra)
        if (!bean.host.isNullOrBlank()) put("host", bean.host)
        put("path", bean.path?.takeIf { it.isNotBlank() } ?: "/")
        bean.xhttpMode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
    }
}
