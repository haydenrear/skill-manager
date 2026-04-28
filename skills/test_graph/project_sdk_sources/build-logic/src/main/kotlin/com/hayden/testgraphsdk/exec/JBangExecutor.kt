package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ValidationRuntime

/**
 * @param jbangPath absolute path to the jbang binary resolved by Toolchain.
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

        val process = ProcessBuilder(argv)
            .directory(invocation.projectDir.asFile)
            .inheritIO()
            .start()
        return process.waitFor()
    }
}
