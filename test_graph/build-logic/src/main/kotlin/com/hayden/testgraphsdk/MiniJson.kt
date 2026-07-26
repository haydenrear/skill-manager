package com.hayden.testgraphsdk

import java.math.BigDecimal

/**
 * Tiny JSON parser for describe output. Scope is narrow: primitives,
 * string-keyed objects, and lists — exactly what NodeSpec emits.
 * Keeps the plugin dependency-free now that we no longer need snakeyaml.
 */
internal object MiniJson {

    private const val MAX_NUMBER_CHARS = 1_024

    fun parse(s: String): Any? = Parser(s).readDocument()

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

        fun readDocument(): Any? {
            val value = readValue()
            skipWs()
            if (i != s.length) error("unexpected trailing content at $i")
            return value
        }

        private fun readValue(): Any? {
            skipWs()
            return when (peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBool()
                'n' -> readNull()
                '-', in '0'..'9' -> readNumber()
                else -> error("expected JSON value at $i")
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
                if (out.containsKey(key)) error("duplicate object key '$key' at $i")
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
                    if (i >= s.length) error("unterminated string escape")
                    when (val n = s[i++]) {
                        '"', '\\', '/' -> sb.append(n)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) error("incomplete unicode escape")
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> error("invalid string escape at $i")
                    }
                } else {
                    if (c.code < 0x20) error("unescaped control character at $i")
                    sb.append(c)
                }
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

        private fun readNumber(): Number {
            val start = i
            consumeIf('-')
            when (peekRaw()) {
                '0' -> {
                    advanceNumberChar(start)
                    if (peekRaw() in '0'..'9') error("leading zero in JSON number at $i")
                }
                in '1'..'9' -> consumeDigits(start)
                else -> error("invalid JSON number at $i")
            }
            if (consumeIf('.')) {
                checkNumberLength(start)
                if (peekRaw() !in '0'..'9') error("fraction requires a digit at $i")
                consumeDigits(start)
            }
            if (peekRaw() == 'e' || peekRaw() == 'E') {
                advanceNumberChar(start)
                if (peekRaw() == '+' || peekRaw() == '-') advanceNumberChar(start)
                if (peekRaw() !in '0'..'9') error("exponent requires a digit at $i")
                consumeDigits(start)
            }
            val lit = s.substring(start, i)
            return lit.toLongOrNull() ?: try {
                BigDecimal(lit)
            } catch (_: NumberFormatException) {
                error("invalid JSON number at $start")
            }
        }

        private fun consumeDigits(start: Int) {
            while (peekRaw() in '0'..'9') advanceNumberChar(start)
        }

        private fun advanceNumberChar(start: Int) {
            i++
            checkNumberLength(start)
        }

        private fun checkNumberLength(start: Int) {
            if (i - start > MAX_NUMBER_CHARS) {
                error("JSON number exceeds $MAX_NUMBER_CHARS characters at $start")
            }
        }

        private fun consumeIf(expected: Char): Boolean {
            if (peekRaw() != expected) return false
            i++
            return true
        }

        private fun expect(c: Char) {
            skipWs()
            if (i >= s.length || s[i] != c) error("expected '$c' at $i")
            i++
        }

        private fun skipWs() {
            while (i < s.length) {
                when (s[i]) {
                    ' ', '\t', '\n', '\r' -> i++
                    else -> return
                }
            }
        }
        private fun peekRaw(): Char? = s.getOrNull(i)
        private fun peek(): Char {
            skipWs()
            if (i >= s.length) error("unexpected end of JSON")
            return s[i]
        }
    }
}
