package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.EnvironmentRepositorySpec
import com.hayden.testgraphsdk.NodeKind
import com.hayden.testgraphsdk.ValidationNodeSpec
import com.hayden.testgraphsdk.ValidationRuntime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ProvisioningStateTest {

    @Test
    fun provisionWritesBranchScopedMarker() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val state = state(projectDir)
        val spec = node("environment.provision", "environment:provision")
        val prepared = state.prepare(spec)
        val record = state.recordSuccessful(spec, prepared, "passed")

        val marker = assertNotNull(record?.provisionedMarker)
        assertTrue(marker.isFile)
        val body = marker.readText()
        assertTrue(body.contains("\"environmentId\": \"branch-environment__feature-a__local-preview__local\""))
        assertTrue(body.contains("\"state\": \"provisioned\""))
    }

    @Test
    fun branchEnvironmentIdsPreserveDistinctBranchNames() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val slashState = state(
            projectDir,
            env = baseEnv() + ("TEST_GRAPH_FEATURE_BRANCH" to "feature/foo"),
        )
        val dashState = state(
            projectDir,
            env = baseEnv() + ("TEST_GRAPH_FEATURE_BRANCH" to "feature-foo"),
        )
        val provision = node("environment.provision", "environment:provision")

        val slashId = assertNotNull(slashState.prepare(provision)).identity.id
        val dashId = assertNotNull(dashState.prepare(provision)).identity.id

        assertTrue(slashId.contains("~666561747572652f666f6f"))
        assertTrue(dashId.contains("feature-foo"))
        assertFalse(slashId == dashId)
    }

    @Test
    fun failedProvisionDoesNotWriteMarker() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val state = state(projectDir)
        val spec = node("environment.provision", "environment:provision")
        val prepared = state.prepare(spec)

        val record = state.recordSuccessful(spec, prepared, "failed")

        assertTrue(record == null)
        assertFalse(
            projectDir.resolve("build/testgraph-provisioning-state/provisioned")
                .walkTopDown()
                .any { it.isFile }
        )
    }

    @Test
    fun resetKeepsProvisionedMarker() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val state = state(projectDir)
        val provision = node("environment.provision", "environment:provision")
        val deploy = node("environment.deploy", "environment:deploy")
        val reset = node("environment.reset", "environment:reset")

        val provisionRecord = state.recordSuccessful(provision, state.prepare(provision), "passed")
        val provisionedMarker = assertNotNull(provisionRecord?.provisionedMarker)
        val deployRecord = state.recordSuccessful(deploy, state.prepare(deploy), "passed")
        val deployedMarker = assertNotNull(deployRecord?.deployedMarker)
        val resetRecord = state.recordSuccessful(reset, state.prepare(reset), "passed")

        assertTrue(provisionedMarker.isFile)
        assertFalse(deployedMarker.exists())
        assertTrue(assertNotNull(resetRecord?.resetMarker).isFile)
        assertTrue(provisionedMarker.isFile)
    }

    @Test
    fun runScopedResetMarkerUsesShortFilenameButKeepsFullAuditBody() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val state = ProvisioningState(
            projectDir = projectDir,
            graphName = "environmentRepositoryGithubActionLifecycle",
            runId = "20260628-200941",
            env = baseEnv() + ("TEST_GRAPH_FEATURE_BRANCH" to "feature/tg-5-branch-environment-repositories"),
        )
        val reset = environmentRepositoryNode(
            "tg6.github-action.lifecycle.reset",
            EnvironmentRepositorySpec(
                source = "git@example.invalid:env.git",
                template = "templates/branch-preview",
                target = "local-github-action",
                backend = "github-action",
            ),
            "environment:reset",
        )

        val record = assertNotNull(state.recordSuccessful(reset, state.prepare(reset), "passed"))
        val marker = assertNotNull(record.resetMarker)
        val body = marker.readText()

        assertTrue(marker.name.length < 255)
        assertTrue(marker.name.startsWith("${record.identity.id}__run-"))
        assertTrue(body.contains("\"runId\": \"20260628-200941\""))
        assertTrue(body.contains("\"nodeId\": \"tg6.github-action.lifecycle.reset\""))
        assertTrue(body.contains("\"environmentId\": \"${record.identity.id}\""))
    }

    @Test
    fun destroyRequiresExplicitIntentAndRemovesProvisionedMarkerOnlyOnSuccess() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val provisionState = state(projectDir)
        val provision = node("environment.provision", "environment:provision")
        val provisionRecord = provisionState.recordSuccessful(provision, provisionState.prepare(provision), "passed")
        val marker = assertNotNull(provisionRecord?.provisionedMarker)
        val deploy = node("environment.deploy", "environment:deploy")
        val deployRecord = provisionState.recordSuccessful(deploy, provisionState.prepare(deploy), "passed")
        val deployedMarker = assertNotNull(deployRecord?.deployedMarker)

        assertFailsWith<IllegalArgumentException> {
            provisionState.prepare(node("environment.destroy", "environment:destroy"))
        }
        assertTrue(marker.isFile)
        assertTrue(deployedMarker.isFile)

        val destroyState = state(
            projectDir,
            env = baseEnv() + ("TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT" to "true"),
        )
        val destroy = node("environment.destroy", "environment:destroy")
        val prepared = destroyState.prepare(destroy)
        val failedDestroy = destroyState.recordSuccessful(destroy, prepared, "failed")

        assertTrue(failedDestroy == null)
        assertTrue(marker.isFile)

        val destroyRecord = assertNotNull(destroyState.recordSuccessful(destroy, prepared, "passed"))
        assertNotNull(destroyRecord.destroyRequestMarker)
        assertNotNull(destroyRecord.destroyedMarker)
        assertFalse(marker.exists())
        assertFalse(deployedMarker.exists())
    }

    @Test
    fun destroyIntentAliasesUseAnyTruthyValue() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val destroyState = state(
            projectDir,
            env = baseEnv() + mapOf(
                "TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT" to "false",
                "TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT" to "true",
            ),
        )

        assertNotNull(destroyState.prepare(node("environment.destroy", "environment:destroy")))
    }

    @Test
    fun reprovisionClearsDestroyedMarker() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val provisionState = state(projectDir)
        val provision = node("environment.provision", "environment:provision")
        val provisionRecord = provisionState.recordSuccessful(provision, provisionState.prepare(provision), "passed")
        val provisionedMarker = assertNotNull(provisionRecord?.provisionedMarker)

        val destroyState = state(
            projectDir,
            env = baseEnv() + ("TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT" to "true"),
        )
        val destroy = node("environment.destroy", "environment:destroy")
        val destroyedMarker = assertNotNull(
            destroyState.recordSuccessful(destroy, destroyState.prepare(destroy), "passed")?.destroyedMarker
        )

        assertFalse(provisionedMarker.exists())
        assertTrue(destroyedMarker.isFile)

        val reprovision = node("environment.reprovision", "environment:provision")
        provisionState.recordSuccessful(reprovision, provisionState.prepare(reprovision), "passed")

        assertTrue(provisionedMarker.isFile)
        assertFalse(destroyedMarker.exists())
    }

    @Test
    fun awsTargetRequiresExplicitSelectionAndCredentials() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val state = state(
            projectDir,
            env = baseEnv() + mapOf(
                "TEST_GRAPH_ENVIRONMENT_TARGET" to "aws-preview",
                "AWS_PROFILE" to "test",
            ),
        )

        val selectionError = assertFailsWith<IllegalArgumentException> {
            state.prepare(node("environment.provision", "environment:provision"))
        }
        assertTrue(selectionError.message?.contains("TEST_GRAPH_RUN_AWS_LIFECYCLE") == true)

        val selectedWithoutCredentials = state(
            projectDir,
            env = baseEnv() + mapOf(
                "TEST_GRAPH_ENVIRONMENT_TARGET" to "aws-preview",
                "TEST_GRAPH_RUN_AWS_LIFECYCLE" to "true",
            ),
        )

        val credentialsError = assertFailsWith<IllegalArgumentException> {
            selectedWithoutCredentials.prepare(node("environment.provision", "environment:provision"))
        }
        assertTrue(credentialsError.message?.contains("AWS credentials") == true)

        val credentialed = state(
            projectDir,
            env = baseEnv() + mapOf(
                "TEST_GRAPH_ENVIRONMENT_TARGET" to "aws-preview",
                "TEST_GRAPH_RUN_AWS_LIFECYCLE" to "true",
                "AWS_PROFILE" to "test",
            ),
        )

        assertNotNull(credentialed.prepare(node("environment.provision", "environment:provision")))
    }

    @Test
    fun awsSelectionAliasesUseAnyTruthyValue() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val credentialed = state(
            projectDir,
            env = baseEnv() + mapOf(
                "TEST_GRAPH_ENVIRONMENT_TARGET" to "aws-preview",
                "TEST_GRAPH_RUN_AWS_LIFECYCLE" to "false",
                "TESTGRAPH_RUN_AWS_LIFECYCLE" to "true",
                "AWS_PROFILE" to "test",
            ),
        )

        assertNotNull(credentialed.prepare(node("environment.provision", "environment:provision")))
    }

    @Test
    fun fixedEnvironmentRepositoryBranchSelectorOverridesCiBranchEnv() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val state = state(
            projectDir,
            env = baseEnv() + mapOf(
                "GITHUB_HEAD_REF" to "feature-from-ci",
                "GITHUB_REF_NAME" to "feature-from-ref",
            ),
        )
        val spec = environmentRepositoryNode(
            "environment.provision",
            EnvironmentRepositorySpec(
                source = "git@example.invalid:env.git",
                template = "templates/branch-preview",
                branch = "staging",
            ),
            "environment:provision",
        )

        val prepared = assertNotNull(state.prepare(spec))

        assertEquals("staging", prepared.identity.branch)
        assertTrue(prepared.identity.id.contains("__staging__"))
        assertFalse(prepared.identity.id.contains("feature-a"))
        assertFalse(prepared.identity.id.contains("feature-from-ci"))
    }

    private fun state(
        projectDir: java.io.File,
        env: Map<String, String> = baseEnv(),
    ): ProvisioningState =
        ProvisioningState(
            projectDir = projectDir,
            graphName = "branch-environment",
            runId = "run-1",
            env = env,
        )

    private fun baseEnv(): Map<String, String> =
        mapOf("TEST_GRAPH_FEATURE_BRANCH" to "feature-a")

    private fun node(id: String, vararg sideEffects: String): ValidationNodeSpec =
        ValidationNodeSpec(
            id = id,
            kind = NodeKind.ACTION,
            runtime = ValidationRuntime.Uv("sources/$id.py"),
            sideEffects = sideEffects.toSet(),
        )

    private fun environmentRepositoryNode(
        id: String,
        repository: EnvironmentRepositorySpec,
        vararg sideEffects: String,
    ): ValidationNodeSpec =
        ValidationNodeSpec(
            id = id,
            kind = NodeKind.ACTION,
            runtime = ValidationRuntime.Uv("sources/$id.py"),
            sideEffects = sideEffects.toSet(),
            environmentRepository = repository,
        )
}
