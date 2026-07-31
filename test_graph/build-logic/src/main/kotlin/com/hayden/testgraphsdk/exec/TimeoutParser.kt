package com.hayden.testgraphsdk.exec

/**
 * Parses the `NodeSpec.timeout(...)` string into milliseconds.
 *
 * Accepts the suffix forms NodeSpec specs in the wild use today —
 * "30s", "180s", "2m", "1h", or a bare integer treated as seconds for
 * forward-compat. Falls back to the SDK's documented 60s default on
 * anything we can't parse so a typo doesn't fail the whole graph at
 * configuration time.
 */
internal object TimeoutParser {

    private const val DEFAULT_MILLIS = 60_000L

    fun parseMillis(value: String?): Long {
        if (value.isNullOrBlank()) return DEFAULT_MILLIS
        val s = value.trim().lowercase()
        val (numPart, unitMillis) = when {
            s.endsWith("ms") -> s.dropLast(2) to 1L
            s.endsWith("s")  -> s.dropLast(1) to 1_000L
            s.endsWith("m")  -> s.dropLast(1) to 60_000L
            s.endsWith("h")  -> s.dropLast(1) to 3_600_000L
            else             -> s to 1_000L
        }
        val n = numPart.trim().toLongOrNull() ?: return DEFAULT_MILLIS
        return (n * unitMillis).coerceAtLeast(0L)
    }
}
