package com.hayden.testgraphsdk.exec

import java.io.File
import java.util.regex.Pattern

/** Plugin-side mirror of the SDK ContextItem. */
data class ContextItem(val nodeId: String, val data: Map<String, String>)

/**
 * Threshold (in characters of serialized JSON) below which the Context[]
 * rides inline as the --context arg. Above it, we spill to a file in
 * reportDir and pass --context=@<path>.
 *
 * 8 KB is well under the common ARG_MAX headroom while still letting
 * small graphs avoid a file write per step.
 */
internal const val CONTEXT_INLINE_LIMIT = 8 * 1024

object ContextSerde {

    fun toJson(items: List<ContextItem>): String {
        val sb = StringBuilder()
        sb.append("{\"items\":[")
        for ((i, it) in items.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"nodeId\":").append(quote(it.nodeId))
            sb.append(",\"data\":{")
            var k = 0
            for ((key, value) in it.data) {
                if (k++ > 0) sb.append(',')
                sb.append(quote(key)).append(':').append(quote(value))
            }
            sb.append("}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * Extract the `published` block from a node envelope as a flat
     * string/string map. Parser stays dependency-free and tolerates
     * the envelope's surrounding fields.
     */
    fun extractPublished(envelopeJson: String): Map<String, String> {
        val key = "\"published\""
        val idx = envelopeJson.indexOf(key)
        if (idx < 0) return emptyMap()
        val braceStart = envelopeJson.indexOf('{', idx)
        if (braceStart < 0) return emptyMap()
        var depth = 0
        var end = -1
        for (i in braceStart until envelopeJson.length) {
            val c = envelopeJson[i]
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) { end = i; break }
            }
        }
        if (end < 0) return emptyMap()
        val body = envelopeJson.substring(braceStart + 1, end)
        val out = linkedMapOf<String, String>()
        val m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(body)
        while (m.find()) out[m.group(1)] = unescape(m.group(2))
        return out
    }

    private fun quote(s: String): String {
        val b = StringBuilder(s.length + 2)
        b.append('"')
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c.code < 0x20) b.append("\\u%04x".format(c.code)) else b.append(c)
            }
        }
        b.append('"')
        return b.toString()
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '"', '\\', '/' -> append(n)
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    else -> { append(c); append(n) }
                }
                i += 2
            } else { append(c); i++ }
        }
    }
}

/**
 * Writes the Context[] inline or to disk depending on size, and returns
 * the string to pass as --context=<value>.
 */
internal fun encodeContextArg(
    items: List<ContextItem>,
    reportRoot: File,
    stepIndex: Int,
): String {
    val json = ContextSerde.toJson(items)
    if (json.length <= CONTEXT_INLINE_LIMIT) return json
    val dir = File(reportRoot, "context").apply { mkdirs() }
    val file = File(dir, "step-%03d.json".format(stepIndex))
    file.writeText(json)
    return "@" + file.absolutePath
}
