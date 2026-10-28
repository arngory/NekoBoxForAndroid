package io.niko.utils

/**
 * Semver-like comparison of release versions such as "1.5.0", "v1.5.1-beta.2".
 * A pre-release ranks below its release, numeric identifiers compare numerically.
 */
fun compareVersions(a: String, b: String): Int {
    val (coreA, preA) = splitVersion(a)
    val (coreB, preB) = splitVersion(b)
    for (i in 0 until maxOf(coreA.size, coreB.size)) {
        val diff = (coreA.getOrNull(i) ?: 0).compareTo(coreB.getOrNull(i) ?: 0)
        if (diff != 0) return diff
    }
    if (preA.isEmpty() || preB.isEmpty()) return preB.size.compareTo(preA.size).coerceIn(-1, 1)
    for (i in 0 until minOf(preA.size, preB.size)) {
        val x = preA[i]
        val y = preB[i]
        val nx = x.toLongOrNull()
        val ny = y.toLongOrNull()
        val diff = when {
            nx != null && ny != null -> nx.compareTo(ny)
            nx != null -> -1
            ny != null -> 1
            else -> x.compareTo(y)
        }
        if (diff != 0) return diff
    }
    return preA.size.compareTo(preB.size)
}

fun isNewerVersion(remote: String, current: String) = compareVersions(remote, current) > 0

private fun splitVersion(version: String): Pair<List<Long>, List<String>> {
    val v = version.trim().removePrefix("v").substringBefore('+')
    val core = v.substringBefore('-').split('.').map { it.toLongOrNull() ?: 0 }
    val pre = v.substringAfter('-', "").split('.').filter { it.isNotEmpty() }
    return core to pre
}
