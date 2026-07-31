package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.NodeKind
import com.hayden.testgraphsdk.SideEffectSpec
import com.hayden.testgraphsdk.ValidationNodeSpec
import com.hayden.testgraphsdk.ValidationRuntime
import kotlin.test.Test
import kotlin.test.assertEquals

class EnvironmentContextProjectionTest {

    @Test
    fun projectsExplicitEnvironmentKeysFromLatestContext() {
        val items = listOf(
            ContextItem("provision.one", mapOf("KUBECONFIG" to "/old", "ignored-key" to "x")),
            ContextItem("provision.two", mapOf("KUBECONFIG" to "/new", "KUBECONTEXT" to "ctx")),
        )

        val projected = EnvironmentContextProjection.project(
            items,
            SideEffectSpec.parseAll(listOf("env:[KUBECONFIG]")),
        )

        assertEquals(mapOf("KUBECONFIG" to "/new"), projected)
    }

    @Test
    fun projectsAllEligibleEnvironmentKeys() {
        val items = listOf(
            ContextItem("provision", mapOf("EnvironmentId" to "env-1", "KUBECONFIG" to "/tmp/kube", "bad-key" to "no")),
        )

        val projected = EnvironmentContextProjection.project(
            items,
            SideEffectSpec.parseAll(listOf("env:[*]")),
        )

        assertEquals(
            mapOf("EnvironmentId" to "env-1", "KUBECONFIG" to "/tmp/kube"),
            projected,
        )
    }

    @Test
    fun callerCanScopeProjectionToTransitiveDependencyContext() {
        val plan = listOf(
            node("provision.one"),
            node("assert.one", dependsOn = listOf("provision.one")),
            node("provision.two"),
            node("deploy.one", dependsOn = listOf("assert.one"), sideEffects = setOf("env:[KUBECONFIG]")),
        )
        val dependencyClosure = dependencyClosureByNode(plan)
        val cumulative = listOf(
            ContextItem("provision.one", mapOf("KUBECONFIG" to "/one")),
            ContextItem("assert.one", emptyMap()),
            ContextItem("provision.two", mapOf("KUBECONFIG" to "/two")),
        )
        val deployDependencyContext = cumulative.filter {
            it.nodeId in dependencyClosure.getValue("deploy.one")
        }

        val projected = EnvironmentContextProjection.project(
            deployDependencyContext,
            plan.last().sideEffectSpecs(),
        )

        assertEquals(mapOf("KUBECONFIG" to "/one"), projected)
    }

    private fun node(
        id: String,
        dependsOn: List<String> = emptyList(),
        sideEffects: Set<String> = emptySet(),
    ): ValidationNodeSpec =
        ValidationNodeSpec(
            id = id,
            kind = NodeKind.ACTION,
            runtime = ValidationRuntime.Uv("sources/$id.py"),
            dependsOn = dependsOn,
            sideEffects = sideEffects,
        )
}
