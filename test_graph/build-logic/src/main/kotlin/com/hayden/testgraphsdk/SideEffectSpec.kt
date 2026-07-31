package com.hayden.testgraphsdk

/**
 * Typed representation of the side-effect strings declared by node SDKs and
 * Gradle DSL overlays. Execution semantics are layered on later tickets; this
 * class is the fail-fast metadata boundary.
 */
data class SideEffectSpec(
    val raw: String,
    val family: String,
    val action: String? = null,
) {
    companion object {
        private val keyPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val allowedActions = mapOf(
            "db" to setOf("writes"),
            "fs" to setOf("tmp"),
            "net" to setOf("external", "local"),
            "process" to setOf("gradle"),
            "environment" to setOf("provision", "reuse", "deploy", "reset", "destroy"),
        )

        fun parse(raw: String, owner: String = "sideEffects"): SideEffectSpec {
            val value = raw.trim()
            require(value.isNotEmpty()) { "$owner contains a blank side effect" }
            if (value == "browser") return SideEffectSpec(value, "browser")

            val separator = value.indexOf(':')
            require(separator > 0 && separator < value.lastIndex) {
                "$owner has malformed side effect '$raw'; expected a registered form like browser, net:local, or env:[KEY]"
            }

            val family = value.substring(0, separator)
            val action = value.substring(separator + 1)
            if (family == "env") {
                validateEnvAction(action, owner, raw)
                return SideEffectSpec(value, family, action)
            }

            val allowed = allowedActions[family]
            require(allowed != null && action in allowed) {
                "$owner has unsupported side effect '$raw'"
            }
            return SideEffectSpec(value, family, action)
        }

        fun parseAll(raws: Iterable<String>, owner: String = "sideEffects"): Set<SideEffectSpec> =
            raws.mapTo(linkedSetOf()) { parse(it, owner) }

        private fun validateEnvAction(action: String, owner: String, raw: String) {
            require(action.startsWith("[") && action.endsWith("]")) {
                "$owner has malformed env side effect '$raw'; expected env:[KEY] or env:[*]"
            }
            val key = action.substring(1, action.length - 1)
            require(key == "*" || keyPattern.matches(key)) {
                "$owner has malformed env side effect '$raw'; expected env:[KEY] or env:[*]"
            }
        }
    }
}
