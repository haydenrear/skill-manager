package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ValidationRuntime

/**
 * @param uvPath absolute path to the uv binary resolved by Toolchain.
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

        val process = ProcessBuilder(argv)
            .directory(invocation.projectDir.asFile)
            .inheritIO()
            .start()
        return process.waitFor()
    }
}
