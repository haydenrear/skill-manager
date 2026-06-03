package dev.skillmanager.project;

import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Materializes a declared project env into project-local uv artifacts.
 */
public final class ProjectEnvMaterializer {

    private final SkillStore store;

    public ProjectEnvMaterializer(SkillStore store) {
        this.store = store;
    }

    public record Options(boolean syncUv, String uvExecutable) {
        public static Options renderOnly() { return new Options(false, null); }
        public static Options sync() { return new Options(true, null); }
    }

    public record Result(
            SkillProjectLock lock,
            SkillProjectLock.EnvRealization env,
            Path envRoot,
            Path pyprojectFile,
            Path docsFile,
            int uvExitCode
    ) {}

    public Result materialize(SkillProject project, String envName, Options options) throws IOException {
        if (project == null) throw new IllegalArgumentException("project must not be null");
        SkillProject.ProjectEnv env = findEnv(project, envName).orElseThrow(() ->
                new IOException("project env not declared: " + envName));
        SkillProjectLockStore locks = new SkillProjectLockStore(store);
        SkillProjectLock previous = locks.read(project.registryName()).orElseThrow(() ->
                new IOException("project dependencies are not resolved for " + project.registryName()
                        + "; run `skill-manager project resolve` first"));
        Path projectRoot = project.projectRoot();
        Path projectSm = project.activeProfile() == null
                ? projectRoot.resolve(".skill-manager")
                : projectRoot.resolve(".skill-manager")
                        .resolve("profiles")
                        .resolve(safeSegment(project.activeProfile()));
        Path envRoot = projectSm.resolve("envs").resolve(env.name());
        Path vendorRoot = projectSm.resolve("vendor");
        Path binRoot = projectSm.resolve("bin");
        Path docsFile = projectSm.resolve("env.md");
        Fs.ensureDir(envRoot);
        Fs.ensureDir(vendorRoot);
        Fs.ensureDir(binRoot);

        List<String> vendorUnits = vendorSkillUnits(previous);
        for (String unit : vendorUnits) {
            Path src = store.unitDir(unit, UnitKind.SKILL);
            if (!Files.isDirectory(src)) continue;
            Path dst = vendorRoot.resolve(unit);
            Fs.deleteRecursive(dst);
            Fs.copyRecursive(src, dst);
        }

        Path pyproject = envRoot.resolve("pyproject.toml");
        Files.writeString(pyproject, renderPyproject(project, env));
        renderShims(binRoot, envRoot, env.tools(), uv(options));
        renderDocs(project, env, docsFile, envRoot, pyproject, vendorUnits);

        int uvExit = 0;
        Options opts = options == null ? Options.renderOnly() : options;
        if (opts.syncUv()) {
            uvExit = run(List.of(uv(opts), "sync"), envRoot);
            if (uvExit != 0) {
                throw new IOException("uv sync failed for project env " + env.name()
                        + " with exit code " + uvExit);
            }
        }

        SkillProjectLock.EnvRealization row = new SkillProjectLock.EnvRealization(
                env.name(),
                env.python(),
                envRoot.toString(),
                pyproject.toString(),
                envRoot.resolve("uv.lock").toString(),
                envRoot.resolve(".venv").toString(),
                docsFile.toString(),
                env.dependencies(),
                env.skillPackages(),
                vendorUnits,
                env.tools(),
                Instant.now().toString());
        SkillProjectLock next = mergeEnv(previous, row);
        locks.write(next);
        return new Result(next, row, envRoot, pyproject, docsFile, uvExit);
    }

    public int runEnv(SkillProject project, String envName, List<String> command, String uvExecutable)
            throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IOException("missing command for project env run");
        }
        SkillProjectLock lock = new SkillProjectLockStore(store).read(project.registryName()).orElseThrow(() ->
                new IOException("project dependencies are not resolved for " + project.registryName()));
        SkillProjectLock.EnvRealization env = lock.envs().stream()
                .filter(e -> e.name().equals(envName))
                .findFirst()
                .orElseThrow(() -> new IOException("project env is not materialized: " + envName));
        List<String> argv = new ArrayList<>();
        argv.add(uvExecutable == null || uvExecutable.isBlank() ? "uv" : uvExecutable);
        argv.add("run");
        argv.add("--project");
        argv.add(env.envRoot());
        argv.addAll(command);
        return run(argv, Path.of(env.envRoot()));
    }

    private static Optional<SkillProject.ProjectEnv> findEnv(SkillProject project, String envName) {
        String selected = envName == null || envName.isBlank() ? "default" : envName;
        Optional<SkillProject.ProjectEnv> exact = project.envs().stream()
                .filter(e -> e.name().equals(selected))
                .findFirst();
        if (exact.isPresent()) return exact;
        if ((envName == null || envName.isBlank()) && project.envs().size() == 1) {
            return Optional.of(project.envs().get(0));
        }
        return Optional.empty();
    }

    private static List<String> vendorSkillUnits(SkillProjectLock lock) {
        Set<String> units = new LinkedHashSet<>();
        for (SkillProjectLock.ResolvedUnit unit : lock.resolvedUnits()) {
            if (unit.kind() == UnitKind.SKILL) units.add(unit.name());
        }
        return new ArrayList<>(units);
    }

    private static SkillProjectLock mergeEnv(SkillProjectLock lock, SkillProjectLock.EnvRealization env) {
        List<SkillProjectLock.EnvRealization> rows = new ArrayList<>();
        for (SkillProjectLock.EnvRealization existing : lock.envs()) {
            if (!existing.name().equals(env.name())) rows.add(existing);
        }
        rows.add(env);
        rows.sort(Comparator.comparing(SkillProjectLock.EnvRealization::name, String.CASE_INSENSITIVE_ORDER));
        return new SkillProjectLock(
                lock.projectName(),
                lock.profile(),
                lock.manifestFile(),
                lock.resolvedAt(),
                lock.resolvedUnits(),
                lock.bindings(),
                rows,
                lock.libs());
    }

    private static String renderPyproject(SkillProject project, SkillProject.ProjectEnv env) {
        StringBuilder sb = new StringBuilder();
        sb.append("[project]\n");
        sb.append("name = \"").append(esc(project.name())).append("-").append(esc(env.name())).append("\"\n");
        sb.append("version = \"0.1.0\"\n");
        sb.append("requires-python = \"").append(esc(requiresPython(env.python()))).append("\"\n");
        sb.append("dependencies = [\n");
        for (String dep : env.dependencies()) {
            sb.append("  \"").append(esc(dep)).append("\",\n");
        }
        for (String pkg : env.skillPackages()) {
            sb.append("  \"").append(esc(pkg)).append("\",\n");
        }
        sb.append("]\n");
        if (!env.skillPackages().isEmpty()) {
            sb.append("\n[tool.uv.sources]\n");
            for (String pkg : env.skillPackages()) {
                sb.append(escKey(pkg)).append(" = { path = \"../../vendor/")
                        .append(esc(pkg)).append("\", editable = true }\n");
            }
        }
        return sb.toString();
    }

    private static void renderShims(Path binRoot, Path envRoot, List<String> tools, String uv) throws IOException {
        for (String tool : tools) {
            if (tool == null || tool.isBlank()) continue;
            Path shim = binRoot.resolve(tool);
            Files.writeString(shim, """
                    #!/usr/bin/env sh
                    set -eu
                    exec "%s" run --project "%s" "%s" "$@"
                    """.formatted(uv, envRoot, tool));
            Fs.makeExecutable(shim);
        }
    }

    private static void renderDocs(
            SkillProject project,
            SkillProject.ProjectEnv env,
            Path docsFile,
            Path envRoot,
            Path pyproject,
            List<String> vendorUnits) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Project Environments\n\n");
        sb.append("Generated documentation for ").append(project.name()).append(". The source of truth is skill-project.toml.\n\n");
        sb.append("## ").append(env.name()).append("\n\n");
        sb.append("- env root: ").append(envRoot).append("\n");
        sb.append("- pyproject: ").append(pyproject).append("\n");
        sb.append("- sync: skill-manager env sync ").append(env.name()).append("\n");
        sb.append("- run: skill-manager env run ").append(env.name()).append(" -- <command>\n");
        if (!env.dependencies().isEmpty()) {
            sb.append("- dependencies: ").append(String.join(", ", env.dependencies())).append("\n");
        }
        if (!vendorUnits.isEmpty()) {
            sb.append("- vendored units: ").append(String.join(", ", vendorUnits)).append("\n");
        }
        if (!env.tools().isEmpty()) {
            sb.append("- shims: ").append(String.join(", ", env.tools())).append("\n");
        }
        Fs.ensureDir(docsFile.getParent());
        Files.writeString(docsFile, sb.toString());
    }

    private static int run(List<String> command, Path cwd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile()).inheritIO();
        Process p = pb.start();
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroy();
            throw new IOException("interrupted running " + command.get(0), e);
        }
    }

    private static String uv(Options options) {
        if (options != null && options.uvExecutable() != null && !options.uvExecutable().isBlank()) {
            return options.uvExecutable();
        }
        return "uv";
    }

    private static String requiresPython(String python) {
        if (python == null || python.isBlank()) return ">=3.11";
        String trimmed = python.trim();
        if (trimmed.startsWith(">") || trimmed.startsWith("=") || trimmed.startsWith("<") || trimmed.startsWith("~")) {
            return trimmed;
        }
        return ">=" + trimmed;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escKey(String key) {
        if (key.matches("[A-Za-z0-9_-]+")) return key;
        return "\"" + esc(key) + "\"";
    }

    private static String safeSegment(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
