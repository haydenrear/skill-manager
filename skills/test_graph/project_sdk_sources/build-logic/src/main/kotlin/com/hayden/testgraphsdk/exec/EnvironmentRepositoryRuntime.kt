package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.EnvironmentRepositorySpec
import com.hayden.testgraphsdk.MiniJson
import com.hayden.testgraphsdk.ValidationNodeSpec
import java.io.File
import java.net.URI

internal data class EnvironmentRepositoryCommandRecord(
    val label: String,
    val command: List<String>,
    val exitCode: Int,
    val log: File,
    val stderrLog: File? = null,
)

internal data class EnvironmentRepositoryExecution(
    val identity: BranchEnvironmentIdentity,
    val repositoryDir: File,
    val templateDir: File,
    val reused: Boolean,
    val commands: List<EnvironmentRepositoryCommandRecord>,
    val outputs: Map<String, String>,
) {
    val environment: Map<String, String> =
        outputs + mapOf(
            "TEST_GRAPH_ENVIRONMENT_REPOSITORY_DIR" to repositoryDir.absolutePath,
            "TEST_GRAPH_ENVIRONMENT_TEMPLATE_DIR" to templateDir.absolutePath,
            "TEST_GRAPH_ENVIRONMENT_REUSED" to reused.toString(),
        )

    val publishedOutputs: Map<String, String> =
        outputs + mapOf("EnvironmentRepositoryReused" to reused.toString())
}

internal class EnvironmentRepositoryRuntime(
    private val projectDir: File,
    private val reportRoot: File,
    private val provisioningState: ProvisioningState,
    private val env: Map<String, String> = System.getenv(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    fun execute(
        spec: ValidationNodeSpec,
        prepared: PreparedProvisioningState?,
    ): EnvironmentRepositoryExecution? {
        val repository = spec.environmentRepository ?: return null
        val provisioning = prepared ?: return null
        val actions = provisioning.actions
        if (actions.none { it in ENVIRONMENT_REPOSITORY_ACTIONS }) return null

        val alreadyProvisioned = provisioningState.isProvisioned(provisioning.identity)
        val needsExistingEnvironment = actions.any { it in EXISTING_ENVIRONMENT_ACTIONS }
        if (needsExistingEnvironment && !alreadyProvisioned) {
            error("node '${spec.id}' declares ${actions.sorted()} but branch environment '${provisioning.identity.id}' is not provisioned")
        }

        val reused = "reuse" in actions || "deploy" in actions || ("provision" in actions && alreadyProvisioned)
        if ("reuse" in actions && !alreadyProvisioned) {
            error("node '${spec.id}' declares environment:reuse but branch environment '${provisioning.identity.id}' is not provisioned")
        }

        val commands = mutableListOf<EnvironmentRepositoryCommandRecord>()
        val repositoryDir = cloneOrReuseRepository(spec.id, repository, provisioning.identity, commands)
        val templateDir = File(repositoryDir, repository.template).canonicalFile
        require(templateDir.toPath().startsWith(repositoryDir.toPath())) {
            "environmentRepository.template '${repository.template}' must resolve inside ${repositoryDir.absolutePath}"
        }
        require(templateDir.isDirectory) {
            "environmentRepository.template '${repository.template}' was not found in ${repositoryDir.absolutePath}"
        }

        val commandEnv = commandEnvironment(provisioning, reused)
        val tofu = tofuBinary(repositoryDir)
        commands += runCommand(spec.id, "tofu-init", listOf(tofu, "init"), templateDir, commandEnv)

        val outputs = if ("destroy" in actions) {
            commands += runCommand(spec.id, "tofu-destroy", listOf(tofu, "destroy", "-auto-approve"), templateDir, commandEnv)
            emptyMap()
        } else {
            if (shouldApply(actions)) {
                commands += runCommand(spec.id, "tofu-apply", listOf(tofu, "apply", "-auto-approve"), templateDir, commandEnv)
            }
            val outputRecord = runCommand(
                spec.id,
                "tofu-output",
                listOf(tofu, "output", "-json"),
                templateDir,
                commandEnv,
                separateOutput = true,
            )
            commands += outputRecord
            parseOutputs(repository, outputRecord.log.readText())
        }

        return EnvironmentRepositoryExecution(
            identity = provisioning.identity,
            repositoryDir = repositoryDir,
            templateDir = templateDir,
            reused = reused,
            commands = commands,
            outputs = outputs,
        )
    }

    private fun shouldApply(actions: Set<String>): Boolean =
        "reset" in actions || "provision" in actions

    private fun cloneOrReuseRepository(
        nodeId: String,
        repository: EnvironmentRepositorySpec,
        identity: BranchEnvironmentIdentity,
        commands: MutableList<EnvironmentRepositoryCommandRecord>,
    ): File {
        val runtimeRoot = File(projectDir, "build/testgraph-environment-repositories/${identity.id}")
        val repositoryDir = File(runtimeRoot, "repo").canonicalFile
        val source = cloneSource(repository.source)
        if (File(repositoryDir, ".git").isDirectory) {
            val originRecord = runCommand(
                nodeId,
                "git-origin",
                listOf(gitBinary(), "config", "--get", "remote.origin.url"),
                repositoryDir,
                env,
                separateOutput = true,
                checkExitCode = false,
            )
            commands += originRecord
            if (originRecord.exitCode == 0 && originRecord.log.readText().trim() == source) {
                refreshRepository(nodeId, repositoryDir, commands)
                return repositoryDir
            }
            runtimeRoot.deleteRecursively()
        }

        runtimeRoot.deleteRecursively()
        runtimeRoot.mkdirs()
        require(File(source).canonicalFile != repositoryDir) {
            "environmentRepository.source must not point at the runtime clone directory"
        }
        commands += runCommand(
            nodeId,
            "git-clone",
            listOf(gitBinary(), "clone", source, repositoryDir.absolutePath),
            projectDir,
            env,
        )
        return repositoryDir
    }

    private fun refreshRepository(
        nodeId: String,
        repositoryDir: File,
        commands: MutableList<EnvironmentRepositoryCommandRecord>,
    ) {
        commands += runCommand(
            nodeId,
            "git-fetch",
            listOf(gitBinary(), "fetch", "--prune", "origin"),
            repositoryDir,
            env,
        )
        val branchRecord = runCommand(
            nodeId,
            "git-current-branch",
            listOf(gitBinary(), "rev-parse", "--abbrev-ref", "HEAD"),
            repositoryDir,
            env,
            separateOutput = true,
        )
        commands += branchRecord
        val branch = branchRecord.log.readText().trim().takeIf { it.isNotEmpty() && it != "HEAD" } ?: "HEAD"
        val resetTarget = if (branch == "HEAD") "origin/HEAD" else "origin/$branch"
        commands += runCommand(
            nodeId,
            "git-reset",
            listOf(gitBinary(), "reset", "--hard", resetTarget),
            repositoryDir,
            env,
        )
    }

    private fun commandEnvironment(
        provisioning: PreparedProvisioningState,
        reused: Boolean,
    ): Map<String, String> =
        env + provisioning.environment + mapOf(
            "TF_VAR_environment_id" to provisioning.identity.id,
            "TF_VAR_branch" to provisioning.identity.branch,
            "TF_VAR_target" to provisioning.identity.target,
            "TF_VAR_backend" to provisioning.identity.backend,
            "TEST_GRAPH_ENVIRONMENT_REUSED" to reused.toString(),
        )

    private fun parseOutputs(
        repository: EnvironmentRepositorySpec,
        raw: String,
    ): Map<String, String> {
        val root = MiniJson.obj(MiniJson.parse(raw))
        val outputs = linkedMapOf<String, String>()
        for (key in repository.outputKeys) {
            val rawOutput = root[key]
                ?: error("tofu output -json did not include required environment output '$key'")
            outputs[key] = outputValue(rawOutput)
                ?: error("tofu output '$key' did not include a scalar value")
        }
        return outputs
    }

    private fun outputValue(rawOutput: Any?): String? {
        val obj = rawOutput as? Map<*, *>
        val rawValue = obj?.get("value") ?: rawOutput
        return when (rawValue) {
            null -> null
            is String -> rawValue
            is Number, is Boolean -> rawValue.toString()
            else -> null
        }
    }

    private fun tofuBinary(repositoryDir: File): String {
        val explicit = env["TEST_GRAPH_TOFU_BIN"]?.trim()
        if (!explicit.isNullOrEmpty()) return explicit
        val fixtureLocal = File(repositoryDir, "bin/tofu")
        if (fixtureLocal.canExecute()) return fixtureLocal.absolutePath
        return "tofu"
    }

    private fun gitBinary(): String =
        env["TEST_GRAPH_GIT_BIN"]?.trim()?.takeIf { it.isNotEmpty() } ?: "git"

    private fun cloneSource(source: String): String {
        if (source.startsWith("git@") || source.startsWith("http://") ||
            source.startsWith("https://") ||
            source.startsWith("ssh://") || source.startsWith("git://")) {
            return source
        }
        val file = if (source.startsWith("file:")) {
            if (source.startsWith("file://")) File(URI(source)) else File(source.removePrefix("file:"))
        } else {
            File(source)
        }
        return if (file.isAbsolute) file.absolutePath else File(projectDir, file.path).absolutePath
    }

    private fun runCommand(
        nodeId: String,
        label: String,
        argv: List<String>,
        cwd: File,
        environment: Map<String, String>,
        separateOutput: Boolean = false,
        checkExitCode: Boolean = true,
    ): EnvironmentRepositoryCommandRecord {
        val log = logFile(nodeId, if (separateOutput) "$label.stdout" else label)
        val stderrLog = if (separateOutput) logFile(nodeId, "$label.stderr") else null
        log.parentFile.mkdirs()
        stderrLog?.parentFile?.mkdirs()
        val managedCommand = PosixProcessGroupController.wrap(argv)
        val builder = ProcessBuilder(managedCommand.arguments)
            .directory(cwd)
            .redirectErrorStream(!separateOutput)
            .redirectOutput(log)
            .also { it.environment().putAll(environment) }
        if (stderrLog != null) builder.redirectError(stderrLog)
        val process = builder.start()
        val outcome = awaitWithTimeout(process, timeoutMillis, managedCommand)
        val finished = outcome !is ExecutionOutcome.TimedOut
        val exitCode = when (outcome) {
            is ExecutionOutcome.Completed -> outcome.exitCode
            is ExecutionOutcome.ProcessContractViolation -> outcome.exitCode
            ExecutionOutcome.TimedOut -> -1
        }
        val record = EnvironmentRepositoryCommandRecord(label, argv, exitCode, log, stderrLog)
        if (!finished) {
            error("environmentRepository command '$label' timed out after ${timeoutMillis}ms; see ${commandLogReference(record)}")
        }
        if (outcome is ExecutionOutcome.ProcessContractViolation) {
            error("environmentRepository command '$label' violated the process contract: " +
                    "${outcome.reason}; see ${commandLogReference(record)}")
        }
        if (checkExitCode && exitCode != 0) {
            error("environmentRepository command '$label' failed with exit $exitCode; see ${commandLogReference(record)}")
        }
        return record
    }

    private fun commandLogReference(record: EnvironmentRepositoryCommandRecord): String =
        if (record.stderrLog == null) {
            record.log.absolutePath
        } else {
            "${record.log.absolutePath} and ${record.stderrLog.absolutePath}"
        }

    private fun logFile(nodeId: String, label: String): File {
        val safeNode = nodeId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(reportRoot, "environment-repository-logs"), "$safeNode.$safe.log")
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 5 * 60 * 1000L
        private val ENVIRONMENT_REPOSITORY_ACTIONS = setOf("provision", "reuse", "deploy", "reset", "destroy")
        private val EXISTING_ENVIRONMENT_ACTIONS = setOf("reuse", "deploy", "reset", "destroy")
    }
}
