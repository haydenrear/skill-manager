package com.hayden.testgraphsdk

import org.gradle.testfixtures.ProjectBuilder
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StandardNodeCompositionTest {

    @Test
    fun composesShippedNodeBySemanticIdWithoutAProviderPath() {
        val projectDir = createTempDirectory("test-graph-standard-node-").toFile()
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val script = projectDir.resolve("standard-nodes/monitoring_cluster_assert_ready.py")
        script.parentFile.mkdirs()
        script.createNewFile()

        val spec = TestGraphBuilder(project, "monitoring").apply {
            standardNode("monitoring.cluster.assert.ready")
        }.build()

        assertEquals(
            listOf(script.canonicalFile),
            spec.explicitNodes.keys.map { it.canonicalFile },
        )
    }

    @Test
    fun rejectsNonDottedStandardNodeIds() {
        val project = ProjectBuilder.builder().build()

        assertFailsWith<IllegalArgumentException> {
            TestGraphBuilder(project, "invalid").standardNode("monitoring")
        }
    }

    @Test
    fun indexesTheStandardCatalogBeforeConsumerSources() {
        val projectDir = createTempDirectory("test-graph-standard-sources-").toFile()
        projectDir.resolve("standard-nodes").mkdir()
        projectDir.resolve("sources").mkdir()
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val extension = ValidationGraphExtension(project)

        extension.sourcesDir("sources")
        extension.sourcesDir("standard-nodes")

        assertEquals(
            listOf(
                projectDir.resolve("standard-nodes").canonicalFile,
                projectDir.resolve("sources").canonicalFile,
            ),
            extension.indexedSourcesDirs().map { it.canonicalFile },
        )
    }

    @Test
    fun rejectsExplicitConsumerScriptThatDeclaresAStandardId() {
        val projectDir = createTempDirectory("test-graph-standard-collision-").toFile()
        val standard = projectDir.resolve("standard-nodes/monitoring_cluster_ensure.py")
        val consumer = projectDir.resolve("sources/local_monitoring.py")
        standard.parentFile.mkdirs()
        consumer.parentFile.mkdirs()
        writeDescribingScript(standard, "monitoring.cluster.ensure")
        writeDescribingScript(consumer, "monitoring.cluster.ensure")

        val fakeUv = projectDir.resolve("fake-uv")
        fakeUv.writeText("#!/bin/sh\n[ \"\$1\" = run ] && shift\nexec python3 \"\$@\"\n")
        fakeUv.setExecutable(true)

        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val graph = TestGraphBuilder(project, "collision").apply {
            node("sources/local_monitoring.py")
        }.build()

        val failure = assertFailsWith<IllegalStateException> {
            GraphAssembler.plan(
                graph,
                listOf(projectDir.resolve("standard-nodes"), projectDir.resolve("sources")),
                projectDir,
                ToolPaths(jbang = "unused", uv = fakeUv.absolutePath),
            )
        }
        assertTrue(failure.message.orEmpty().contains("reserved by the shipped standard-node catalog"))
    }

    private fun writeDescribingScript(file: java.io.File, id: String) {
        file.writeText(
            """
            import json
            import sys

            output = next(arg.split("=", 1)[1] for arg in sys.argv if arg.startswith("--describe-out="))
            with open(output, "w", encoding="utf-8") as handle:
                json.dump({"id": "$id", "kind": "assertion"}, handle)
            """.trimIndent() + "\n"
        )
    }
}
