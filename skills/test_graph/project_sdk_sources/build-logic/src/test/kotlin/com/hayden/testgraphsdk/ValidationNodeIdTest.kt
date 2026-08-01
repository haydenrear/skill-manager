package com.hayden.testgraphsdk

import com.hayden.testgraphsdk.exec.ContextSerde
import com.hayden.testgraphsdk.exec.inputContextSnapshotFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValidationNodeIdTest {

    @Test
    fun rejectsFilesystemUnsafeAndPreviouslyCollidingNodeIds() {
        val reportRoot = Files.createTempDirectory("test-graph-node-id").toFile()
        for (nodeId in listOf("a:b", "a?b", "../", "safe\n", "Node")) {
            assertFailsWith<IllegalArgumentException> {
                ValidationNodeSpec(
                    id = nodeId,
                    kind = NodeKind.ASSERTION,
                    runtime = ValidationRuntime.Uv("node.py"),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                inputContextSnapshotFile(reportRoot, nodeId)
            }
        }
    }

    @Test
    fun rejectsUnsafeNodeIdsImportedFromSavedContext() {
        assertFailsWith<IllegalArgumentException> {
            ContextSerde.fromJson(
                """{"items":[{"nodeId":"../","data":{"value":"unsafe"}}]}"""
            )
        }
    }

    @Test
    fun acceptsOnlyTheBoundedAsciiFilesystemSafeGrammar() {
        val lowercase = ValidationNodeSpec(
            id = "node",
            kind = NodeKind.ASSERTION,
            runtime = ValidationRuntime.Uv("node.py"),
        )
        val valid = "a".repeat(128)
        val spec = ValidationNodeSpec(
            id = valid,
            kind = NodeKind.ASSERTION,
            runtime = ValidationRuntime.Uv("node.py"),
        )

        assertEquals("node", lowercase.id)
        assertEquals(valid, spec.id)
        assertFailsWith<IllegalArgumentException> {
            ValidationNodeSpec(
                id = "a".repeat(129),
                kind = NodeKind.ASSERTION,
                runtime = ValidationRuntime.Uv("node.py"),
            )
        }
    }
}
