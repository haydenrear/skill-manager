package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.SideEffectSpec

internal object EnvironmentContextProjection {
    private val envKeyPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun project(
        items: List<ContextItem>,
        sideEffects: Set<SideEffectSpec>,
    ): Map<String, String> {
        val envEffects = sideEffects.filter { it.family == "env" }
        if (envEffects.isEmpty()) return emptyMap()

        val latest = latestEligibleValues(items)
        val requested = linkedSetOf<String>()
        var includeAll = false
        for (effect in envEffects) {
            val action = effect.action ?: continue
            val key = action.removePrefix("[").removeSuffix("]")
            if (key == "*") includeAll = true else requested += key
        }

        val projected = linkedMapOf<String, String>()
        if (includeAll) projected.putAll(latest)
        for (key in requested) {
            latest[key]?.let { projected[key] = it }
        }
        return projected
    }

    private fun latestEligibleValues(items: List<ContextItem>): Map<String, String> {
        val values = linkedMapOf<String, String>()
        for (item in items) {
            for ((key, value) in item.data) {
                if (envKeyPattern.matches(key)) values[key] = value
            }
        }
        return values
    }
}
