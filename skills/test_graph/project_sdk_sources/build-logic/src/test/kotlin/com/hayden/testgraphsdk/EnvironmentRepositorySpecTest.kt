package com.hayden.testgraphsdk

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class EnvironmentRepositorySpecTest {

    @Test
    fun parsesNeutralGitEnvironmentRepositoryContract() {
        val spec = EnvironmentRepositorySpec.parse(
            mapOf(
                "source" to "git@github.com:example/environments.git",
                "template" to "templates/local-preview",
                "target" to "local-preview",
                "backend" to "local",
                "branch" to "feature",
                "outputKeys" to listOf("EnvironmentId", "KUBECONFIG", "KUBECONTEXT", "API_BASE_URL"),
            ),
            "test environmentRepository",
        )

        assertNotNull(spec)
        assertEquals("git@github.com:example/environments.git", spec.source)
        assertEquals("templates/local-preview", spec.template)
        assertEquals(setOf("EnvironmentId", "KUBECONFIG", "KUBECONTEXT", "API_BASE_URL"), spec.outputKeys)
    }

    @Test
    fun defaultsToLocalPreviewContractOutputs() {
        val spec = EnvironmentRepositorySpec.parse(
            mapOf(
                "source" to "file:/tmp/test-graph-env-repo",
                "template" to "templates/local-preview",
            ),
            "test environmentRepository",
        )

        assertNotNull(spec)
        assertEquals("local-preview", spec.target)
        assertEquals("local", spec.backend)
        assertEquals("feature", spec.branch)
        assertEquals(EnvironmentRepositorySpec.REQUIRED_OUTPUT_KEYS, spec.outputKeys)
    }

    @Test
    fun rejectsTarballFixtureAndInvalidTemplatePaths() {
        val base = mapOf(
            "source" to "https://example.com/env-repo.tar.gz",
            "template" to "templates/local-preview",
        )

        assertFailsWith<IllegalArgumentException> {
            EnvironmentRepositorySpec.parse(base, "test environmentRepository")
        }
        assertFailsWith<IllegalArgumentException> {
            EnvironmentRepositorySpec.parse(
                mapOf("source" to "https://example.com/env.git", "template" to "../local-preview"),
                "test environmentRepository",
            )
        }
    }

    @Test
    fun rejectsLocalGitControlDirectoryAndNestedProjectRepository() {
        val projectDir = createTempDirectory("test-graph-project-").toFile()
        val nested = File(projectDir, "environment-repository").apply { mkdirs() }
        File(nested, ".git").mkdirs()
        File(projectDir, ".git").mkdirs()

        try {
            assertFailsWith<IllegalArgumentException> {
                EnvironmentRepositorySpec.parse(
                    mapOf("source" to ".git", "template" to "templates/local-preview"),
                    "test environmentRepository",
                    projectDir,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                EnvironmentRepositorySpec.parse(
                    mapOf("source" to "environment-repository", "template" to "templates/local-preview"),
                    "test environmentRepository",
                    projectDir,
                )
            }
        } finally {
            projectDir.deleteRecursively()
        }
    }
}
