package com.hayden.testgraphsdk.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Aggregates per-node JSON envelopes in the most recent run dir into a
 * single summary.json. Keeps the aggregator dependency-free for v0 —
 * the envelopes are already well-formed JSON the SDKs emitted, and v0
 * only needs a flat concatenation keyed by nodeId.
 */
abstract class ValidationReportTask : DefaultTask() {
    @get:Internal abstract val reportRoot: DirectoryProperty

    init {
        group = "validation"
        description = "Aggregate per-node envelopes in the latest run into summary.json."
    }

    @TaskAction
    fun report() {
        val root = reportRoot.get().asFile
        if (!root.isDirectory) {
            logger.lifecycle("no reports dir at ${root.absolutePath}")
            return
        }
        val latest: File = root.listFiles { f -> f.isDirectory }
            ?.maxByOrNull { it.lastModified() }
            ?: run {
                logger.lifecycle("no runs found under ${root.absolutePath}")
                return
            }

        val envelopes = File(latest, "envelope").listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.name } ?: emptyList()

        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"runId\":\"").append(latest.name).append("\",")
        sb.append("\"nodes\":[")
        envelopes.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append(f.readText().trim())
        }
        sb.append("]}")

        val out = File(latest, "summary.json")
        out.writeText(sb.toString())
        logger.lifecycle("wrote ${out.absolutePath} (${envelopes.size} node envelopes)")
    }
}
