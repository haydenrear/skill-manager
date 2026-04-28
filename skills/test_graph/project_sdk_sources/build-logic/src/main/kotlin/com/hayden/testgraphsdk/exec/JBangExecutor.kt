package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ValidationRuntime

/**
 * @param jbangPath absolute path to the jbang binary resolved by Toolchain.
 *
 * The spawned node-process has its merged stdout+stderr redirected to
 * {@code invocation.stdoutLog} so it survives past the live console
 * scroll and forensics are recoverable when the SDK never gets a
 * chance to populate {@code --result-out} (jbang compile error, JVM
 * crash, OOM at fork time, etc.). PlanExecutor stamps the path onto
 * the canonical envelope as {@code capturedStdoutLog}.
 */
class JBangExecutor(private val jbangPath: String) : ValidationExecutor {
    override val runtimeName: String = "jbang"

    override fun execute(invocation: NodeInvocation): Int {
        val rt = invocation.spec.runtime as? ValidationRuntime.JBang
            ?: error("JBangExecutor cannot run a ${invocation.spec.runtime.name} node (${invocation.spec.id})")

        val argv = mutableListOf<String>()
        argv += jbangPath
        argv += rt.entryFile
        argv += standardArgs(invocation)

        invocation.stdoutLog.parentFile?.mkdirs()
        val process = ProcessBuilder(argv)
            .directory(invocation.projectDir.asFile)
            .redirectErrorStream(true)
            .redirectOutput(invocation.stdoutLog)
            .start()
        return process.waitFor()
    }
}
