package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ValidationRuntime

class JBangExecutor : ValidationExecutor {
    override val runtimeName: String = "jbang"

    override fun execute(invocation: NodeInvocation): Int {
        val rt = invocation.spec.runtime as? ValidationRuntime.JBang
            ?: error("JBangExecutor cannot run a ${invocation.spec.runtime.name} node (${invocation.spec.id})")

        val argv = mutableListOf<String>()
        argv += "jbang"
        argv += rt.entryFile
        argv += standardArgs(invocation)

        val process = ProcessBuilder(argv)
            .directory(invocation.projectDir.asFile)
            .inheritIO()
            .start()
        return process.waitFor()
    }
}
