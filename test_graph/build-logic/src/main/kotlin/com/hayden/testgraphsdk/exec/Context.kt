package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.MiniJson
import com.hayden.testgraphsdk.requireValidNodeId
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Collections

/** Plugin-side mirror of the SDK ContextItem. */
data class ContextItem(val nodeId: String, val data: Map<String, String>) {
    init {
        requireValidNodeId(nodeId, "context nodeId")
    }
}

/**
 * One verified, in-memory replay source.  Replay code receives this value
 * instead of the source directory so validation and use cannot be separated
 * by another pathname lookup.
 *
 * The closure is an integrity boundary for ordinary/protocol mutation.  It is
 * not an authenticity boundary against an owner who can rewrite both the
 * evidence and the closure; that stronger threat requires an external trust
 * anchor (for example a signature, MAC, or WORM store).
 */
internal data class ReplaySourceSnapshot(
    val sourceBuild: File,
    val graphName: String,
    val selectedNodeId: String,
    val sourceExpectedNodeIds: List<String>,
    val traceId: String,
    val carrierJson: String,
    val carrier: Map<String, String>,
    val selectedContextJson: String,
    val selectedContext: List<ContextItem>,
    val closureSha256: String,
    val selectedContextSha256: String,
) {
    init {
        require(sourceBuild.isAbsolute) { "replay source snapshot path must be absolute" }
        requireValidNodeId(selectedNodeId, "replay source snapshot nodeId")
        require(sourceExpectedNodeIds.isNotEmpty()) {
            "replay source snapshot execution scope must not be empty"
        }
        require(sourceExpectedNodeIds.distinct().size == sourceExpectedNodeIds.size) {
            "replay source snapshot execution scope must contain unique node ids"
        }
        sourceExpectedNodeIds.forEach { requireValidNodeId(it, "replay source scope nodeId") }
        require(selectedNodeId in sourceExpectedNodeIds) {
            "replay source snapshot scope does not contain '$selectedNodeId'"
        }
        require(SHA256_HEX.matches(closureSha256)) {
            "replay source closure SHA-256 must be lowercase hexadecimal"
        }
        require(SHA256_HEX.matches(selectedContextSha256)) {
            "replay source context SHA-256 must be lowercase hexadecimal"
        }
    }

    companion object {
        fun immutable(
            sourceBuild: File,
            graphName: String,
            selectedNodeId: String,
            sourceExpectedNodeIds: List<String>,
            traceId: String,
            carrierJson: String,
            carrier: Map<String, String>,
            selectedContextJson: String,
            selectedContext: List<ContextItem>,
            closureSha256: String,
            selectedContextSha256: String,
        ): ReplaySourceSnapshot {
            val frozenCarrier = Collections.unmodifiableMap(LinkedHashMap(carrier))
            val frozenExpectedNodeIds = Collections.unmodifiableList(sourceExpectedNodeIds.toList())
            val frozenContext = Collections.unmodifiableList(
                selectedContext.map { item ->
                    ContextItem(
                        item.nodeId,
                        Collections.unmodifiableMap(LinkedHashMap(item.data)),
                    )
                }
            )
            return ReplaySourceSnapshot(
                sourceBuild = sourceBuild.canonicalFile,
                graphName = graphName,
                selectedNodeId = selectedNodeId,
                sourceExpectedNodeIds = frozenExpectedNodeIds,
                traceId = traceId,
                carrierJson = carrierJson,
                carrier = frozenCarrier,
                selectedContextJson = selectedContextJson,
                selectedContext = frozenContext,
                closureSha256 = closureSha256,
                selectedContextSha256 = selectedContextSha256,
            )
        }
    }
}

internal data class BoundedUtf8File(
    val text: String,
    val sha256: String,
    val size: Int,
)

internal data class BoundedFileDigest(
    val sha256: String,
    val size: Long,
)

/**
 * Threshold (in characters of serialized JSON) below which the Context[]
 * rides inline as the --context arg. Above it, we spill to a file in
 * reportDir and pass --context=@<path>.
 *
 * 8 KB is well under the common ARG_MAX headroom while still letting
 * small graphs avoid a file write per step.
 */
internal const val CONTEXT_INLINE_LIMIT = 8 * 1024
internal const val CONTEXT_JSON_MAX_UTF8_BYTES = 16 * 1024 * 1024
internal const val CONTEXT_JSON_MAX_DEPTH = 64
internal const val CONTEXT_JSON_MAX_STRUCTURAL_TOKENS = 500_000
internal const val CONTEXT_JSON_MAX_STRING_CHARS = 8 * 1024 * 1024

object ContextSerde {

    fun toJson(items: List<ContextItem>): String {
        val serializedUtf8Bytes = serializedContextUtf8Bytes(items)
        val sb = StringBuilder(serializedUtf8Bytes)
        sb.append("{\"items\":[")
        for ((i, it) in items.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"nodeId\":").append(quote(it.nodeId))
            sb.append(",\"data\":{")
            var k = 0
            for ((key, value) in it.data) {
                if (k++ > 0) sb.append(',')
                sb.append(quote(key)).append(':').append(quote(value))
            }
            sb.append("}}")
        }
        sb.append("]}")
        val json = sb.toString()
        // Keep writer and reader contracts identical, including the structural
        // token limit as well as the precomputed byte cap.
        validateBoundedJson(json, "serialized context")
        return json
    }

    /**
     * Parse the Context[] wire format emitted by [toJson]. This is used by
     * build-directory resume so the executor can seed a run from the exact
     * input context snapshot captured before the selected node's prior attempt.
     */
    fun fromJson(json: String): List<ContextItem> {
        return contextItems(parseBoundedJsonObject(json, "context"))
    }

    internal fun fromParsed(root: Map<String, Any?>): List<ContextItem> =
        contextItems(root)

    internal fun fromJson(file: File): List<ContextItem> {
        val (_, root) = readBoundedJsonObject(file, "context file ${file.name}")
        return contextItems(root)
    }

    internal fun validateSnapshot(file: File): Int {
        val (json, root) = readBoundedJsonObject(file, "context file ${file.name}")
        contextItems(root)
        return json.toByteArray(Charsets.UTF_8).size
    }

    private fun contextItems(root: Map<String, Any?>): List<ContextItem> {
        require(root.keys == setOf("items")) {
            "context root must contain exactly the 'items' field"
        }
        val rawItems = root["items"] as? List<*>
            ?: throw IllegalArgumentException("context items must be a JSON array")
        val seenNodeIds = mutableSetOf<String>()
        return rawItems.mapIndexed { index, raw ->
            val obj = raw as? Map<*, *>
                ?: throw IllegalArgumentException("context item $index must be a JSON object")
            require(obj.keys == setOf("nodeId", "data")) {
                "context item $index must contain exactly nodeId and data"
            }
            val nodeId = obj["nodeId"] as? String
                ?: throw IllegalArgumentException("context item $index must have a string nodeId")
            if (!seenNodeIds.add(nodeId)) {
                throw IllegalArgumentException("context contains duplicate nodeId '$nodeId'")
            }
            ContextItem(nodeId, strictStringMap(obj["data"], "context item $index data"))
        }
    }

    /**
     * Extract the `published` block from a node envelope as an exact flat
     * string/string map. A non-recursive, JSON-string-aware preflight bounds
     * input and the existing parser's maximum call depth before it retains any
     * object/list. Malformed, oversized, over-deep, or non-string published
     * data fails closed instead of producing partial downstream context.
     */
    fun extractPublished(envelopeJson: String): Map<String, String> {
        val root = parseBoundedJsonObject(envelopeJson, "node envelope")
        return published(root)
    }

    internal fun extractPublished(envelopeFile: File): Map<String, String> {
        val (_, root) = readBoundedJsonObject(
            envelopeFile,
            "node envelope ${envelopeFile.name}",
        )
        return published(root)
    }

    private fun published(root: Map<String, Any?>): Map<String, String> {
        if (!root.containsKey("published")) return emptyMap()
        return strictStringMap(root["published"], "published")
    }

    private fun quote(s: String): String {
        val b = StringBuilder(s.length + 2)
        b.append('"')
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c.code < 0x20) b.append("\\u%04x".format(c.code)) else b.append(c)
            }
        }
        b.append('"')
        return b.toString()
    }

}

private fun serializedContextUtf8Bytes(items: List<ContextItem>): Int {
    var total = 0L
    fun add(bytes: Long) {
        if (bytes < 0 || total > CONTEXT_JSON_MAX_UTF8_BYTES.toLong() - bytes) {
            throw IllegalArgumentException(
                "serialized context exceeds $CONTEXT_JSON_MAX_UTF8_BYTES UTF-8 bytes"
            )
        }
        total += bytes
    }

    add("{\"items\":[".length.toLong())
    for ((itemIndex, item) in items.withIndex()) {
        if (itemIndex > 0) add(1)
        add("{\"nodeId\":".length.toLong())
        add(quotedJsonUtf8Bytes(item.nodeId))
        add(",\"data\":{".length.toLong())
        for ((entryIndex, entry) in item.data.entries.withIndex()) {
            if (entryIndex > 0) add(1)
            add(quotedJsonUtf8Bytes(entry.key))
            add(1)
            add(quotedJsonUtf8Bytes(entry.value))
        }
        add(2)
    }
    add(2)
    return total.toInt()
}

private fun quotedJsonUtf8Bytes(value: String): Long {
    var bytes = 2L
    var index = 0
    while (index < value.length) {
        val ch = value[index++]
        bytes += when {
            ch == '"' || ch == '\\' || ch == '\n' || ch == '\r' || ch == '\t' -> 2
            ch.code < 0x20 -> 6
            ch.code <= 0x7f -> 1
            ch.code <= 0x7ff -> 2
            ch.isHighSurrogate() -> {
                if (index >= value.length || !value[index].isLowSurrogate()) {
                    throw IllegalArgumentException("context string contains an unpaired high surrogate")
                }
                index++
                4
            }
            ch.isLowSurrogate() ->
                throw IllegalArgumentException("context string contains an unpaired low surrogate")
            else -> 3
        }
        if (bytes > CONTEXT_JSON_MAX_UTF8_BYTES) {
            throw IllegalArgumentException(
                "serialized context exceeds $CONTEXT_JSON_MAX_UTF8_BYTES UTF-8 bytes"
            )
        }
    }
    return bytes
}

internal class AggregateJsonStructureBudget(
    private val maxStructuralTokens: Int,
) {
    init {
        require(maxStructuralTokens > 0) { "aggregate JSON structural-token limit must be positive" }
    }

    var consumedStructuralTokens: Int = 0
        private set
    var exceeded: Boolean = false
        private set

    internal fun consume(structuralTokens: Int, label: String) {
        require(structuralTokens >= 0) { "JSON structural-token count must be non-negative" }
        if (consumedStructuralTokens > maxStructuralTokens - structuralTokens) {
            exceeded = true
            throw IllegalArgumentException(
                "$label exceeds the aggregate JSON structural-token limit of $maxStructuralTokens"
            )
        }
        consumedStructuralTokens += structuralTokens
    }
}

internal fun parseBoundedJsonObject(
    json: String,
    label: String,
    aggregateBudget: AggregateJsonStructureBudget? = null,
): Map<String, Any?> {
    val structuralTokens = validateBoundedJson(json, label)
    aggregateBudget?.consume(structuralTokens, label)
    val parsed = MiniJson.parse(json)
    @Suppress("UNCHECKED_CAST")
    return parsed as? Map<String, Any?>
        ?: throw IllegalArgumentException("$label must be a JSON object")
}

/** Read a UTF-8 JSON file without ever retaining more than the boundary cap. */
internal fun readBoundedJsonObject(
    file: File,
    label: String,
    maxUtf8Bytes: Int = CONTEXT_JSON_MAX_UTF8_BYTES,
    aggregateBudget: AggregateJsonStructureBudget? = null,
): Pair<String, Map<String, Any?>> {
    require(maxUtf8Bytes in 1..CONTEXT_JSON_MAX_UTF8_BYTES) {
        "JSON byte limit must be between 1 and $CONTEXT_JSON_MAX_UTF8_BYTES"
    }
    val json = readBoundedUtf8RegularFile(file, label, maxUtf8Bytes).text
    return json to parseBoundedJsonObject(json, label, aggregateBudget)
}

/**
 * Open and consume one regular file through a single no-follow descriptor.
 * The pre-open pathname check is diagnostic only; NOFOLLOW_LINKS on the open
 * is the security boundary.  A size change while that descriptor is being
 * consumed fails closed.
 */
internal fun readBoundedUtf8RegularFile(
    file: File,
    label: String,
    maxUtf8Bytes: Int = CONTEXT_JSON_MAX_UTF8_BYTES,
): BoundedUtf8File {
    require(maxUtf8Bytes in 1..CONTEXT_JSON_MAX_UTF8_BYTES) {
        "file byte limit must be between 1 and $CONTEXT_JSON_MAX_UTF8_BYTES"
    }
    val bytes = readBoundedRegularFileBytes(file, label, maxUtf8Bytes)
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: Exception) {
        throw IllegalArgumentException("$label is not valid UTF-8", e)
    }
    return BoundedUtf8File(text, sha256Hex(bytes), bytes.size)
}

internal fun digestBoundedRegularFile(
    file: File,
    label: String,
    maxBytes: Int = CONTEXT_JSON_MAX_UTF8_BYTES,
): BoundedFileDigest {
    require(maxBytes in 1..CONTEXT_JSON_MAX_UTF8_BYTES) {
        "file byte limit must be between 1 and $CONTEXT_JSON_MAX_UTF8_BYTES"
    }
    val path = file.toPath()
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        throw IllegalArgumentException("$label is not a regular file or is a symlink")
    }
    try {
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val initialSize = channel.size()
            if (initialSize > maxBytes) {
                throw IllegalArgumentException("$label exceeds $maxBytes bytes")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(64 * 1024)
            var total = 0L
            while (true) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > maxBytes) {
                    throw IllegalArgumentException("$label exceeds $maxBytes bytes")
                }
                buffer.flip()
                digest.update(buffer)
            }
            if (channel.size() != initialSize || total != initialSize) {
                throw IllegalArgumentException("$label changed while it was being read")
            }
            return BoundedFileDigest(digest.digest().toHex(), total)
        }
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("could not read $label without following links", e)
    }
}

private fun readBoundedRegularFileBytes(file: File, label: String, maxBytes: Int): ByteArray {
    val path = file.toPath()
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        throw IllegalArgumentException("$label is not a regular file or is a symlink")
    }
    try {
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val initialSize = channel.size()
            if (initialSize > maxBytes) {
                throw IllegalArgumentException("$label exceeds $maxBytes UTF-8 bytes")
            }
            val buffer = ByteBuffer.allocate(maxUtf8Allocation(initialSize, maxBytes))
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
            val total = buffer.position()
            if (total > maxBytes || channel.size() != initialSize || total.toLong() != initialSize) {
                throw IllegalArgumentException("$label changed while it was being read")
            }
            return buffer.array().copyOf(total)
        }
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("could not read $label without following links", e)
    }
}

private fun maxUtf8Allocation(size: Long, maxBytes: Int): Int {
    require(size >= 0 && size <= maxBytes)
    return size.toInt().coerceAtLeast(1)
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun sha256Utf8(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

private fun ByteArray.toHex(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

private fun strictStringMap(raw: Any?, label: String): Map<String, String> {
    val map = raw as? Map<*, *>
        ?: throw IllegalArgumentException("$label must be a JSON object")
    val out = linkedMapOf<String, String>()
    for ((key, value) in map) {
        if (key !is String || value !is String) {
            throw IllegalArgumentException("$label must contain only string/string entries")
        }
        out[key] = value
    }
    return out
}

/**
 * Non-recursive, string-aware guard in front of [MiniJson]. It bounds the
 * parser's eventual call depth and retained input before any object/list is
 * allocated, and rejects malformed delimiter/string structure fail-closed.
 */
private fun validateBoundedJson(json: String, label: String): Int {
    boundedUtf8Length(json, label)
    val delimiters = java.util.ArrayDeque<Char>()
    var structuralTokens = 0
    var stringChars = 0
    var inString = false
    var index = 0
    while (index < json.length) {
        val ch = json[index++]
        if (inString) {
            when (ch) {
                '"' -> {
                    inString = false
                    stringChars = 0
                }
                '\\' -> {
                    if (index >= json.length) {
                        throw IllegalArgumentException("$label has an unterminated string escape")
                    }
                    when (json[index++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> {
                            if (index + 4 > json.length) {
                                throw IllegalArgumentException("$label has an incomplete unicode escape")
                            }
                            repeat(4) {
                                if (json[index++].digitToIntOrNull(16) == null) {
                                    throw IllegalArgumentException("$label has an invalid unicode escape")
                                }
                            }
                        }
                        else -> throw IllegalArgumentException("$label has an invalid string escape")
                    }
                    stringChars += 2
                }
                else -> {
                    if (ch.code < 0x20) {
                        throw IllegalArgumentException("$label has an unescaped control character")
                    }
                    stringChars++
                }
            }
            if (stringChars > CONTEXT_JSON_MAX_STRING_CHARS) {
                throw IllegalArgumentException(
                    "$label string exceeds $CONTEXT_JSON_MAX_STRING_CHARS characters"
                )
            }
            continue
        }

        when (ch) {
            '"' -> {
                inString = true
                stringChars = 0
            }
            '{', '[' -> {
                structuralTokens++
                delimiters.addLast(ch)
                if (delimiters.size > CONTEXT_JSON_MAX_DEPTH) {
                    throw IllegalArgumentException(
                        "$label nesting exceeds $CONTEXT_JSON_MAX_DEPTH"
                    )
                }
            }
            '}' -> {
                structuralTokens++
                if (delimiters.pollLast() != '{') {
                    throw IllegalArgumentException("$label has mismatched object delimiters")
                }
            }
            ']' -> {
                structuralTokens++
                if (delimiters.pollLast() != '[') {
                    throw IllegalArgumentException("$label has mismatched array delimiters")
                }
            }
            ',', ':' -> structuralTokens++
        }
        if (structuralTokens > CONTEXT_JSON_MAX_STRUCTURAL_TOKENS) {
            throw IllegalArgumentException(
                "$label exceeds $CONTEXT_JSON_MAX_STRUCTURAL_TOKENS structural tokens"
            )
        }
    }
    if (inString) throw IllegalArgumentException("$label has an unterminated string")
    if (delimiters.isNotEmpty()) throw IllegalArgumentException("$label has unclosed delimiters")
    return structuralTokens
}

private fun boundedUtf8Length(value: String, label: String) {
    if (value.length > CONTEXT_JSON_MAX_UTF8_BYTES) {
        throw IllegalArgumentException("$label exceeds $CONTEXT_JSON_MAX_UTF8_BYTES UTF-8 bytes")
    }
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val ch = value[index++]
        bytes += when {
            ch.code <= 0x7f -> 1
            ch.code <= 0x7ff -> 2
            ch.isHighSurrogate() -> {
                if (index >= value.length || !value[index].isLowSurrogate()) {
                    throw IllegalArgumentException("$label contains an unpaired high surrogate")
                }
                index++
                4
            }
            ch.isLowSurrogate() ->
                throw IllegalArgumentException("$label contains an unpaired low surrogate")
            else -> 3
        }
        if (bytes > CONTEXT_JSON_MAX_UTF8_BYTES) {
            throw IllegalArgumentException("$label exceeds $CONTEXT_JSON_MAX_UTF8_BYTES UTF-8 bytes")
        }
    }
}

/**
 * Persist the exact Context[] input a node receives before the node process
 * starts. Unlike [encodeContextArg], this always writes a build artifact,
 * including root nodes with an empty Context[] and small contexts that still
 * ride inline on the command line.
 */
internal fun writeInputContextSnapshot(
    items: List<ContextItem>,
    reportRoot: File,
    nodeId: String,
): File {
    val file = inputContextSnapshotFile(reportRoot, nodeId)
    requireRealDirectory(reportRoot, "context snapshot report root")
    ensureRealDirectory(file.parentFile, "context snapshot directory")
    publishImmutableEvidence(
        file.toPath(),
        ContextSerde.toJson(items).toByteArray(Charsets.UTF_8),
        "input context snapshot for node '$nodeId'",
    )
    return file
}

/**
 * Load the saved Context[] input for [nodeId] from a previous build/report
 * directory. This is the canonical resume source.
 */
internal fun readInputContextSnapshot(
    reportRoot: File,
    nodeId: String,
): List<ContextItem> {
    val file = inputContextSnapshotFile(reportRoot, nodeId)
    if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        throw IllegalArgumentException(
            "saved input context for node '$nodeId' was not found as a regular non-symlink file " +
                    "at ${file.absolutePath}"
        )
    }
    return ContextSerde.fromJson(file)
}

/** Publish the already-validated raw source snapshot into a fresh attempt. */
internal fun writeCapturedInputContextSnapshot(
    snapshot: ReplaySourceSnapshot,
    reportRoot: File,
    nodeId: String,
): File {
    require(nodeId == snapshot.selectedNodeId) {
        "captured replay context belongs to '${snapshot.selectedNodeId}', not '$nodeId'"
    }
    require(sha256Utf8(snapshot.selectedContextJson) == snapshot.selectedContextSha256) {
        "captured replay context digest changed in memory"
    }
    require(ContextSerde.fromJson(snapshot.selectedContextJson) == snapshot.selectedContext) {
        "captured replay context semantic value changed in memory"
    }
    val file = inputContextSnapshotFile(reportRoot, nodeId)
    requireRealDirectory(reportRoot, "context snapshot report root")
    ensureRealDirectory(file.parentFile, "context snapshot directory")
    publishImmutableEvidence(
        file.toPath(),
        snapshot.selectedContextJson.toByteArray(Charsets.UTF_8),
        "captured input context snapshot for node '$nodeId'",
    )
    return file
}

internal fun inputContextSnapshotFile(reportRoot: File, nodeId: String): File =
    File(
        File(reportRoot, "context"),
        "${requireValidNodeId(nodeId, "snapshot nodeId")}.input.json",
    )

/**
 * Publish an already-complete evidence inode in one no-replace operation.
 * Existing regular files, symlinks (including dangling symlinks), and other
 * filesystem entries are never followed or replaced.
 */
internal fun publishImmutableEvidence(target: Path, encoded: ByteArray, label: String) {
    val parent = target.parent
        ?: throw IllegalArgumentException("$label requires a parent directory")
    require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
        "$label parent must be a real directory, not a symlink: $parent"
    }
    val temp = Files.createTempFile(parent, ".${target.fileName}-", ".tmp")
    try {
        FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
            val bytes = ByteBuffer.wrap(encoded)
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        try {
            Files.createLink(target, temp)
        } catch (e: FileAlreadyExistsException) {
            throw IllegalArgumentException(
                "$label already exists and will not be replaced: $target",
                e,
            )
        } catch (e: UnsupportedOperationException) {
            throw IllegalStateException(
                "$label publication requires same-filesystem hard-link support",
                e,
            )
        }
    } finally {
        Files.deleteIfExists(temp)
    }
}

internal fun ensureRealDirectory(directory: File, label: String) {
    try {
        Files.createDirectory(directory.toPath())
    } catch (_: FileAlreadyExistsException) {
        // Validate the existing entry below without following symlinks.
    }
    requireRealDirectory(directory, label)
}

internal fun requireRealDirectory(directory: File, label: String) {
    require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "$label must be a real directory, not a symlink: ${directory.absolutePath}"
    }
}

/**
 * Returns the Context[] inline when small, otherwise references the canonical
 * input snapshot that was already persisted before node execution.
 */
internal fun encodeContextArg(
    items: List<ContextItem>,
    inputContextFile: File,
): String {
    val json = ContextSerde.toJson(items)
    if (json.length <= CONTEXT_INLINE_LIMIT) return json
    return "@" + inputContextFile.absolutePath
}
