package com.hayden.testgraphsdk

import org.gradle.api.Project
import java.io.File
import java.util.concurrent.TimeUnit

/** Absolute paths to the resolved jbang + uv binaries for this build. */
data class ToolPaths(val jbang: String, val uv: String)

/** What a single tool resolved to, and why. Logged by the tasks. */
data class ResolvedTool(
    val name: String,
    val version: String,
    val executable: File,
    val source: Source,
) {
    enum class Source { PATH, CACHED, DOWNLOADED }
}

/**
 * Resolves the jbang + uv binaries Gradle tasks should use.
 *
 * Rules, per tool:
 *
 *   1. If `override-<tool>-version` is set AND the version on PATH matches
 *      it, use PATH.
 *   2. If `override-<tool>-version` is set AND PATH differs (or tool isn't
 *      on PATH), download the override version to `<project>/.bin/`.
 *   3. If no override, use whatever's on PATH.
 *   4. If no override and not on PATH, download the default version.
 *
 * Downloaded binaries live at:
 *   <project>/.bin/jbang-<version>/bin/jbang
 *   <project>/.bin/uv-<version>/uv
 *
 * Tasks invoke the tool by absolute path, so resolved tools always take
 * precedence over anything else on PATH when the plugin drives them.
 */
object Toolchain {

    const val DEFAULT_JBANG_VERSION = "0.137.0"
    const val DEFAULT_UV_VERSION    = "0.7.2"

    fun resolve(project: Project): ToolPaths {
        val jbang = resolveJbang(project)
        val uv    = resolveUv(project)
        project.logger.lifecycle(
            "[toolchain] jbang=${jbang.version} (${jbang.source.name.lowercase()}: ${jbang.executable})"
        )
        project.logger.lifecycle(
            "[toolchain] uv=${uv.version} (${uv.source.name.lowercase()}: ${uv.executable})"
        )
        return ToolPaths(jbang.executable.absolutePath, uv.executable.absolutePath)
    }

    fun resolveJbang(project: Project): ResolvedTool {
        val override = project.findProperty("override-jbang-version")?.toString()
        return resolveOne(
            project, "jbang", override, DEFAULT_JBANG_VERSION,
            ::detectJbangVersion, ::downloadJbang, ::cachedJbangBinary,
        )
    }

    fun resolveUv(project: Project): ResolvedTool {
        val override = project.findProperty("override-uv-version")?.toString()
        return resolveOne(
            project, "uv", override, DEFAULT_UV_VERSION,
            ::detectUvVersion, ::downloadUv, ::cachedUvBinary,
        )
    }

    private fun resolveOne(
        project: Project,
        toolName: String,
        overrideVersion: String?,
        defaultVersion: String,
        detectVersion: (File) -> String?,
        download: (Project, String) -> File,
        cached: (Project, String) -> File,
    ): ResolvedTool {
        val pathBin = whichOnPath(toolName)
        val pathVersion = pathBin?.let(detectVersion)

        // Rule 1 / 3: use PATH when override matches or no override is set.
        if (pathBin != null && pathVersion != null) {
            if (overrideVersion == null || pathVersion == overrideVersion) {
                return ResolvedTool(toolName, pathVersion, pathBin, ResolvedTool.Source.PATH)
            }
        }

        // Rule 2 / 4: download (or hit cache).
        val desired = overrideVersion ?: defaultVersion
        val cachedBin = cached(project, desired)
        if (cachedBin.isFile && cachedBin.canExecute()) {
            return ResolvedTool(toolName, desired, cachedBin, ResolvedTool.Source.CACHED)
        }
        val downloaded = download(project, desired)
        return ResolvedTool(toolName, desired, downloaded, ResolvedTool.Source.DOWNLOADED)
    }

    // ---- PATH lookup --------------------------------------------------

    private fun whichOnPath(tool: String): File? {
        val path = System.getenv("PATH") ?: return null
        for (dir in path.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue
            val f = File(dir, tool)
            if (f.isFile && f.canExecute()) return f
        }
        return null
    }

    // ---- Version probing ----------------------------------------------

    private val VERSION_NUMBER = Regex("""(\d+\.\d+(?:\.\d+)?(?:[.\-][\w.]+)?)""")

    private fun detectJbangVersion(bin: File): String? =
        runCapture(bin.absolutePath, "version")
            ?.let { VERSION_NUMBER.find(it)?.value }

    private fun detectUvVersion(bin: File): String? =
        runCapture(bin.absolutePath, "--version")
            ?.let { VERSION_NUMBER.find(it)?.value }

    private fun runCapture(vararg argv: String): String? = try {
        val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); null }
        else if (p.exitValue() != 0) null
        else out.trim()
    } catch (_: Exception) { null }

    // ---- Cache paths --------------------------------------------------

    private fun binRoot(project: Project): File =
        project.rootDir.resolve(".bin").apply { mkdirs() }

    private fun cachedJbangBinary(project: Project, version: String): File =
        binRoot(project).resolve("jbang-$version/bin/jbang")

    private fun cachedUvBinary(project: Project, version: String): File =
        binRoot(project).resolve("uv-$version/uv")

    // ---- Download implementations -------------------------------------

    private fun downloadJbang(project: Project, version: String): File {
        val root = binRoot(project)
        val versionDir = root.resolve("jbang-$version").apply { deleteRecursively() }
        val url = "https://github.com/jbangdev/jbang/releases/download/v$version/jbang-$version.zip"
        val zipFile = File.createTempFile("jbang-$version-", ".zip").apply { deleteOnExit() }

        project.logger.lifecycle("[toolchain] downloading jbang $version from $url")
        curlDownload(url, zipFile)

        // jbang zip extracts to "jbang/" at the top level — extract into a
        // scratch dir and rename to our version-stable cache location.
        val scratch = File.createTempFile("jbang-unzip-", "").apply {
            delete(); mkdirs(); deleteOnExit()
        }
        shell("unzip", "-q", zipFile.absolutePath, "-d", scratch.absolutePath)
        val payload = scratch.listFiles()?.firstOrNull { it.isDirectory }
            ?: error("[toolchain] jbang archive had no top-level directory")
        payload.renameTo(versionDir)
        scratch.deleteRecursively()

        val bin = versionDir.resolve("bin/jbang")
        if (!bin.isFile) error("[toolchain] jbang binary not found at $bin after extract")
        bin.setExecutable(true)
        return bin
    }

    private fun downloadUv(project: Project, version: String): File {
        val root = binRoot(project)
        val versionDir = root.resolve("uv-$version").apply { deleteRecursively() }
        val target = uvTarget()
        val url = "https://github.com/astral-sh/uv/releases/download/$version/uv-$target.tar.gz"
        val tarFile = File.createTempFile("uv-$version-", ".tar.gz").apply { deleteOnExit() }

        project.logger.lifecycle("[toolchain] downloading uv $version from $url")
        curlDownload(url, tarFile)

        // uv tar extracts to "uv-<target>/" containing "uv" binary.
        val scratch = File.createTempFile("uv-untar-", "").apply {
            delete(); mkdirs(); deleteOnExit()
        }
        shell("tar", "-xzf", tarFile.absolutePath, "-C", scratch.absolutePath)
        val payload = scratch.listFiles()?.firstOrNull { it.isDirectory }
            ?: error("[toolchain] uv archive had no top-level directory")
        payload.renameTo(versionDir)
        scratch.deleteRecursively()

        val bin = versionDir.resolve("uv")
        if (!bin.isFile) error("[toolchain] uv binary not found at $bin after extract")
        bin.setExecutable(true)
        return bin
    }

    private fun uvTarget(): String {
        val os   = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            "mac" in os && arch == "aarch64"                 -> "aarch64-apple-darwin"
            "mac" in os                                       -> "x86_64-apple-darwin"
            "linux" in os && (arch == "aarch64" || arch == "arm64") -> "aarch64-unknown-linux-gnu"
            "linux" in os                                     -> "x86_64-unknown-linux-gnu"
            else -> error("[toolchain] unsupported platform: os=$os arch=$arch")
        }
    }

    // ---- Process helpers ----------------------------------------------

    private fun curlDownload(url: String, dest: File) {
        val p = ProcessBuilder("curl", "-fsSL", "-o", dest.absolutePath, url)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        if (code != 0 || !dest.isFile || dest.length() == 0L) {
            error("[toolchain] curl failed (exit=$code) for $url: $out")
        }
    }

    private fun shell(vararg argv: String) {
        val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        if (code != 0) error("[toolchain] command failed (exit=$code): ${argv.joinToString(" ")}\n$out")
    }
}
