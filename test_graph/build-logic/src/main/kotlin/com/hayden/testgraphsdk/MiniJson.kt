package com.hayden.testgraphsdk

/**
 * Tiny JSON parser for describe output. Scope is narrow: primitives,
 * string-keyed objects, and lists — exactly what NodeSpec emits.
 * Keeps the plugin dependency-free now that we no longer need snakeyaml.
 */
internal object MiniJson {

    fun parse(s: String): Any? = Parser(s).readValue()

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?> = v as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun list(v: Any?): List<Any?> = (v as? List<Any?>) ?: emptyList()

    fun str(v: Any?): String? = v as? String
    fun bool(v: Any?): Boolean = (v as? Boolean) ?: false

    fun stringList(v: Any?): List<String> = list(v).mapNotNull { it as? String }

    fun stringMap(v: Any?): Map<String, String> {
        val m = v as? Map<*, *> ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        for ((k, raw) in m) if (k is String && raw != null) out[k] = raw.toString()
        return out
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun readValue(): Any? {
            skipWs()
            return when (peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBool()
                'n' -> readNull()
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val out = linkedMapOf<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = readString()
                skipWs(); expect(':')
                out[key] = readValue()
                skipWs()
                val c = s[i++]
                if (c == '}') return out
                if (c != ',') error("expected , or } at $i")
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = mutableListOf<Any?>()
            skipWs()
            if (peek() == ']') { i++; return out }
            while (true) {
                out += readValue()
                skipWs()
                val c = s[i++]
                if (c == ']') return out
                if (c != ',') error("expected , or ] at $i")
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    when (val n = s[i++]) {
                        '"', '\\', '/' -> sb.append(n)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(n)
                    }
                } else sb.append(c)
            }
            error("unterminated string")
        }

        private fun readBool(): Boolean =
            when {
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                else -> error("expected bool at $i")
            }

        private fun readNull(): Any? =
            if (s.startsWith("null", i)) { i += 4; null } else error("expected null at $i")

        private fun readNumber(): Any {
            val start = i
            while (i < s.length && s[i] !in ",}]" && !s[i].isWhitespace()) i++
            val lit = s.substring(start, i)
            return lit.toLongOrNull() ?: lit.toDoubleOrNull() ?: lit
        }

        private fun expect(c: Char) {
            skipWs()
            if (i >= s.length || s[i] != c) error("expected '$c' at $i")
            i++
        }

        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peek(): Char { skipWs(); return s[i] }
    }
}
