package com.hayden.testgraphsdk

import java.io.File

/**
 * Node discovery by self-describe.
 *
 * Every script the plugin cares about is invoked with
 * `--describe-out=<tmp>`; the SDK writes its spec JSON to <tmp> and exits
 * without running the body. We parse that JSON into a ValidationNodeSpec.
 *
 * Runtime is inferred from the file extension:
 *   .java → jbang     .py → uv
 */
internal object NodeDescribeLoader {

    /** Describe one script file. */
    fun describe(file: File, projectDir: File): ValidationNodeSpec {
        require(file.isFile) { "node script not found: ${file.path}" }
        val runtime = runtimeFor(file, projectDir)
        val out = File.createTempFile("validation-describe-", ".json").apply { deleteOnExit() }
        val argv = invocationFor(runtime, file) + "--describe-out=${out.absolutePath}"

        val process = ProcessBuilder(argv)
            .directory(projectDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        val code = process.waitFor()
        if (code != 0) {
            error("describe failed for ${file.name} (exit=$code). " +
                    "Run directly to debug: ${argv.joinToString(" ")}")
        }
        if (!out.isFile || out.length() == 0L) {
            error("describe produced no output for ${file.name}. " +
                    "Did the script call Node.run(args, spec, body)?")
        }
        return parseSpec(out.readText(), runtime)
    }

    /**
     * Describe every .java / .py script under a directory.
     * Returns a map keyed by the spec id.
     */
    fun indexDir(dir: File, projectDir: File): Map<String, ValidationNodeSpec> {
        require(dir.isDirectory) { "sources dir not found: ${dir.path}" }
        val out = linkedMapOf<String, ValidationNodeSpec>()
        dir.listFiles { f -> f.isFile && f.extension in setOf("java", "py") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val spec = describe(file, projectDir)
                if (out.containsKey(spec.id)) {
                    error("duplicate node id '${spec.id}' in ${dir.path} " +
                            "(second source: ${file.name})")
                }
                out[spec.id] = spec
            }
        return out
    }

    private fun runtimeFor(file: File, projectDir: File): ValidationRuntime {
        val rel = file.relativeTo(projectDir).path.replace(File.separatorChar, '/')
        return when (file.extension) {
            "java" -> ValidationRuntime.JBang(rel)
            "py"   -> ValidationRuntime.Uv(rel)
            else   -> error("no runtime for file extension '.${file.extension}' (${file.path})")
        }
    }

    private fun invocationFor(runtime: ValidationRuntime, file: File): List<String> =
        when (runtime) {
            is ValidationRuntime.JBang -> listOf("jbang", file.absolutePath)
            is ValidationRuntime.Uv    -> listOf("uv", "run", file.absolutePath)
        }

    private fun parseSpec(json: String, runtime: ValidationRuntime): ValidationNodeSpec {
        val root = MiniJson.obj(MiniJson.parse(json))
        val id = MiniJson.str(root["id"]) ?: error("describe output missing id")
        val kind = NodeKind.valueOf((MiniJson.str(root["kind"]) ?: "action").uppercase())
        val reports = MiniJson.obj(root["reports"] ?: emptyMap<String, Any?>())
        return ValidationNodeSpec(
            id = id,
            kind = kind,
            runtime = runtime,
            dependsOn = MiniJson.stringList(root["dependsOn"]),
            tags = MiniJson.stringList(root["tags"]).toSet(),
            timeout = MiniJson.str(root["timeout"]) ?: "60s",
            cacheable = MiniJson.bool(root["cacheable"]),
            sideEffects = MiniJson.stringList(root["sideEffects"]).toSet(),
            inputs = MiniJson.stringMap(root["inputs"]),
            outputs = MiniJson.stringMap(root["outputs"]),
            reports = ReportsSpec(
                structuredJson = (reports["structuredJson"] as? Boolean) ?: true,
                junitXml = MiniJson.bool(reports["junitXml"]),
                cucumber = MiniJson.bool(reports["cucumber"]),
            ),
        )
    }
}
