package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ValidationRuntime

/**
 * @param uvPath absolute path to the uv binary resolved by Toolchain.
 *
 * Same stdout-capture contract as {@link JBangExecutor}: merged
 * stdout+stderr redirected to {@code invocation.stdoutLog} so a node
 * that crashes before populating {@code --result-out} (uv resolution
 * error, ImportError, segfault) still leaves a forensics trail.
 */
class UvExecutor(private val uvPath: String) : ValidationExecutor {
    override val runtimeName: String = "uv"

    override fun execute(invocation: NodeInvocation): Int {
        val rt = invocation.spec.runtime as? ValidationRuntime.Uv
            ?: error("UvExecutor cannot run a ${invocation.spec.runtime.name} node (${invocation.spec.id})")

        val argv = mutableListOf<String>()
        argv += uvPath
        argv += "run"
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
