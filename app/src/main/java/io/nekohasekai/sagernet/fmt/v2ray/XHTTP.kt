package io.nekohasekai.sagernet.fmt.v2ray

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
        options[key] = when {
            key == "xmux" && value is JSONObject -> buildMap {
                for (rawXmuxKey in value.keys()) {
                    val xmuxKey = xhttpSnakeCase(rawXmuxKey)
                    if (xmuxKey in XMUX_OPTION_KEYS) put(xmuxKey, jsonValue(value.get(rawXmuxKey)))
                }
            }

            key in RANGE_KEYS && value is JSONObject -> "${value.opt("from")}-${value.opt("to")}"
            key in RANGE_KEYS && value is Number -> value.toString()
            else -> jsonValue(value)
        }
    }
    return options
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
    return buildMap {
        putAll(extra)
        if (!bean.host.isNullOrBlank()) put("host", bean.host)
        put("path", bean.path?.takeIf { it.isNotBlank() } ?: "/")
        bean.xhttpMode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
    }
}
