package com.hayden.testgraphsdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SideEffectSpecTest {

    @Test
    fun parsesRegisteredSideEffectForms() {
        val parsed = SideEffectSpec.parseAll(
            listOf(
                "browser",
                "db:writes",
                "fs:tmp",
                "net:external",
                "net:local",
                "process:gradle",
                "environment:provision",
                "environment:reuse",
                "environment:deploy",
                "environment:reset",
                "environment:destroy",
                "env:[KUBECONFIG]",
                "env:[*]",
            ),
            "test node sideEffects",
        )

        assertEquals(13, parsed.size)
        assertEquals("environment", parsed.first { it.raw == "environment:provision" }.family)
        assertEquals("[KUBECONFIG]", parsed.first { it.raw == "env:[KUBECONFIG]" }.action)
    }

    @Test
    fun rejectsMalformedOrUnsupportedSideEffects() {
        for (raw in listOf("", "unknown", "net:", "net:internet", "env:KUBECONFIG", "env:[BAD-KEY]")) {
            assertFailsWith<IllegalArgumentException>("expected '$raw' to be rejected") {
                SideEffectSpec.parse(raw, "test node sideEffects")
            }
        }
    }
}
