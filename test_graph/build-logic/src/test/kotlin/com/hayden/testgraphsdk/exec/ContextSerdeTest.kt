package com.hayden.testgraphsdk.exec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.io.RandomAccessFile
import java.nio.file.Files

class ContextSerdeTest {

    @Test
    fun extractsTheCdc040EscapeHeavyPublishedPayloadExactly() {
        val envelopeResource = requireNotNull(
            javaClass.getResource("/regressions/cdc040-dummy-repo-seeded-envelope.json")
        )
        val envelopeFile = Files.createTempFile("cdc040-dummy-repo-seeded", ".json")
        envelopeResource.openStream().use { input ->
            Files.copy(input, envelopeFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }

        val published = ContextSerde.extractPublished(envelopeFile.toFile())
        val expectedOracle = requireNotNull(
            javaClass.getResource("/regressions/cdc040-dummy-raw-artifact-oracle.json")
        ).readText()

        assertEquals("local:dummy-java-repo:0.1.0", published["coordinate"])
        assertEquals(
            "sha256:23a3ae505763f75578fcaae9197c6f7481d67273aeb376bbe74a69aa5dc626c8",
            published["rawArtifactOracleDigest"],
        )
        assertEquals(expectedOracle, published.getValue("rawArtifactOracle"))
    }

    @Test
    fun parsesQuotedBracesBackslashesControlsAndUnicodeExactly() {
        val envelope =
            "{\"published\":{\"value\":\"brace } quote \\\" slash \\\\ line\\n tab\\t lambda \\u03bb\"}}"

        assertEquals(
            mapOf("value" to "brace } quote \" slash \\ line\n tab\t lambda λ"),
            ContextSerde.extractPublished(envelope),
        )
    }

    @Test
    fun rejectsNonStringPublishedValuesInsteadOfReturningPartialContext() {
        assertFailsWith<IllegalArgumentException> {
            ContextSerde.extractPublished("""{"published":{"safe":"yes","unsafe":7}}""")
        }
        assertFailsWith<IllegalArgumentException> {
            ContextSerde.extractPublished("""{"published":null}""")
        }
    }

    @Test
    fun rejectsDuplicateTopLevelAndPublishedKeys() {
        assertFailsWith<IllegalStateException> {
            ContextSerde.extractPublished(
                """{"published":{"first":"one"},"published":{"second":"two"}}"""
            )
        }
        assertFailsWith<IllegalStateException> {
            ContextSerde.extractPublished(
                """{"published":{"same":"one","same":"two"}}"""
            )
        }
    }

    @Test
    fun rejectsBareTokensMalformedNumbersAndOversizedNumberTokens() {
        val invalidValues = listOf("garbage", "01", "1.", "1e", "1".repeat(1_025))
        for (invalidValue in invalidValues) {
            assertFailsWith<IllegalStateException>("accepted invalid scalar $invalidValue") {
                ContextSerde.extractPublished(
                    """{"published":{},"invalid":$invalidValue}"""
                )
            }
        }
    }

    @Test
    fun rejectsExcessiveNestingWithoutUsingTheJvmStack() {
        val overDeep = buildString {
            append("{\"published\":{},\"nested\":")
            repeat(CONTEXT_JSON_MAX_DEPTH + 1) { append('[') }
            append('0')
            repeat(CONTEXT_JSON_MAX_DEPTH + 1) { append(']') }
            append('}')
        }

        assertFailsWith<IllegalArgumentException> {
            ContextSerde.extractPublished(overDeep)
        }
    }

    @Test
    fun rejectsInputOverTheUtf8BoundaryBeforeParsing() {
        val overLimit = " ".repeat(CONTEXT_JSON_MAX_UTF8_BYTES + 1)

        assertFailsWith<IllegalArgumentException> {
            ContextSerde.extractPublished(overLimit)
        }
    }

    @Test
    fun envelopeAndSnapshotFilesAreSizeCheckedBeforeReading() {
        val reportRoot = Files.createTempDirectory("test-graph-context-file-bound").toFile()
        val envelope = reportRoot.resolve("oversized-envelope.json")
        val snapshot = inputContextSnapshotFile(reportRoot, "oversized.snapshot")
        snapshot.parentFile.mkdirs()
        for (file in listOf(envelope, snapshot)) {
            RandomAccessFile(file, "rw").use {
                it.setLength(CONTEXT_JSON_MAX_UTF8_BYTES.toLong() + 1)
            }
        }

        assertFailsWith<IllegalArgumentException> {
            ContextSerde.extractPublished(envelope)
        }
        assertFailsWith<IllegalArgumentException> {
            readInputContextSnapshot(reportRoot, "oversized.snapshot")
        }
    }

    @Test
    fun contextSerializationRejectsCumulativeOutputBeforeBuildingIt() {
        val sharedOneMiBValue = "x".repeat(1024 * 1024)
        val data = linkedMapOf<String, String>()
        repeat(17) { index -> data["key-$index"] = sharedOneMiBValue }

        assertFailsWith<IllegalArgumentException> {
            ContextSerde.toJson(listOf(ContextItem("oversized", data)))
        }
    }
}
