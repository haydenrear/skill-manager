package com.hayden.testgraphsdk

/** A runtime adapter that knows how to execute a node script. */
sealed interface ValidationRuntime {
    val name: String
    val entryFile: String

    data class JBang(override val entryFile: String) : ValidationRuntime {
        override val name = "jbang"
    }

    data class Uv(override val entryFile: String) : ValidationRuntime {
        override val name = "uv"
    }
}

enum class NodeKind { TESTBED, FIXTURE, ACTION, ASSERTION, EVIDENCE, REPORT }
