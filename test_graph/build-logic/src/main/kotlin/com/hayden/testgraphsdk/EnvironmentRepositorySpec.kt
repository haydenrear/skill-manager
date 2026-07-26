package com.hayden.testgraphsdk

import java.io.File
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Provider-neutral environment repository contract metadata.
 *
 * TG-5C only validates the declared contract. Later tickets attach Git clone,
 * OpenTofu, reset, destroy, and downstream environment propagation behavior.
 */
data class EnvironmentRepositorySpec(
    val source: String,
    val template: String,
    val target: String = "local-preview",
    val backend: String = "local",
    val branch: String = "feature",
    val outputKeys: Set<String> = REQUIRED_OUTPUT_KEYS,
) {
    fun validate(owner: String = "environmentRepository", projectDir: File? = null): EnvironmentRepositorySpec {
        require(source.isNotBlank()) { "$owner.source must be a Git URL or local Git repository path" }
        require(template.isNotBlank()) { "$owner.template must name a template directory inside the repository" }
        val templateSegments = template.split('/')
        require(template == normalizeTemplatePath(template) && templateSegments.none { it == "." || it == ".." }) {
            "$owner.template must be a relative repository path without '.', '..', or empty segments"
        }
        require(namePattern.matches(target)) { "$owner.target must use lowercase words and '-' separators" }
        require(namePattern.matches(backend)) { "$owner.backend must use lowercase words and '-' separators" }
        require(branch.isNotBlank()) { "$owner.branch must not be blank" }
        require(!archivePattern.matches(source.lowercase())) {
            "$owner.source must be an ordinary Git URL/path, not an archive or tarball"
        }
        require(outputKeys.containsAll(REQUIRED_OUTPUT_KEYS)) {
            "$owner.outputKeys must include ${REQUIRED_OUTPUT_KEYS.sorted()}"
        }
        for (key in outputKeys) {
            require(envKeyPattern.matches(key)) {
                "$owner.outputKeys contains invalid key '$key'"
            }
        }

        val localPath = projectDir?.let { localSourcePath(source, it) }
        if (localPath != null) {
            require(localPath.name != ".git") {
                "$owner.source must point at a Git repository root, not its .git control directory"
            }
            val projectPath = projectDir.canonicalFile.toPath()
            val sourcePath = localPath.toFile().canonicalFile.toPath()
            val buildPath = File(projectDir, "build").canonicalFile.toPath()
            require(!(
                sourcePath != projectPath &&
                    sourcePath.startsWith(projectPath) &&
                    !sourcePath.startsWith(buildPath) &&
                    File(sourcePath.toFile(), ".git").isDirectory
            )) {
                "$owner.source must not be a checked-in nested Git repository under this project"
            }
        }

        return this
    }

    companion object {
        val REQUIRED_OUTPUT_KEYS = setOf("EnvironmentId", "KUBECONFIG", "KUBECONTEXT")

        private val namePattern = Regex("[a-z][a-z0-9-]*")
        private val envKeyPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val archivePattern = Regex(""".*\.(tar|tar\.gz|tgz|zip)$""")

        @Suppress("UNCHECKED_CAST")
        fun parse(raw: Any?, owner: String, projectDir: File? = null): EnvironmentRepositorySpec? {
            if (raw == null) return null
            val obj = raw as? Map<String, Any?>
                ?: error("$owner must be an object")
            return EnvironmentRepositorySpec(
                source = MiniJson.str(obj["source"]) ?: "",
                template = MiniJson.str(obj["template"]) ?: "",
                target = MiniJson.str(obj["target"]) ?: "local-preview",
                backend = MiniJson.str(obj["backend"]) ?: "local",
                branch = MiniJson.str(obj["branch"]) ?: "feature",
                outputKeys = MiniJson.stringList(obj["outputKeys"]).toSet()
                    .ifEmpty { REQUIRED_OUTPUT_KEYS },
            ).validate(owner, projectDir)
        }

        private fun normalizeTemplatePath(value: String): String =
            value.split('/').filter { it.isNotEmpty() }.joinToString("/")

        private fun localSourcePath(source: String, projectDir: File): Path? {
            if (source.startsWith("git@") || source.startsWith("https://") ||
                source.startsWith("ssh://") || source.startsWith("git://")) {
                return null
            }
            val file = if (source.startsWith("file:")) {
                if (source.startsWith("file://")) File(URI(source)) else File(source.removePrefix("file:"))
            } else {
                File(source)
            }
            return if (file.isAbsolute) file.toPath() else File(projectDir, file.path).toPath()
        }
    }
}
