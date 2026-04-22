package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ValidationRuntime

class UvExecutor : ValidationExecutor {
    override val runtimeName: String = "uv"

    override fun execute(invocation: NodeInvocation): Int {
        val rt = invocation.spec.runtime as? ValidationRuntime.Uv
            ?: error("UvExecutor cannot run a ${invocation.spec.runtime.name} node (${invocation.spec.id})")

        val argv = mutableListOf<String>()
        argv += "uv"
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
