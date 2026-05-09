package dev.skillmanager.cli.installer;

import dev.skillmanager.lock.CliLock;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import dev.skillmanager.util.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run a script bundled inside the skill itself to install a binary into
 * {@code bin/cli/}. The escape hatch for tools that can't be published to
 * pip / npm / brew / a tarball — e.g. a private CLI built from source.
 *
 * <p>Scripts must live under the skill's {@code skill-scripts/}
 * subdirectory. We pin the location so a casual reader auditing a skill
 * for "what can this thing run" only has to glance at one folder, and so
 * a path like {@code script = "../../etc/passwd"} can't exfiltrate the
 * skill root via {@code ..} traversal.
 *
 * <p>TOML form:
 * <pre>
 * [[cli_dependencies]]
 * spec = "skill-script:my-cli"
 * on_path = "my-cli"
 *
 * [cli_dependencies.install.any]
 * script = "install-my-cli.sh"           # path under &lt;skill&gt;/skill-scripts/
 * binary = "my-cli"                       # optional — verified after the script exits
 * args   = ["--prefix", "$BIN_DIR"]      # optional — passed to the script
 * </pre>
 *
 * <p>The script runs with these env vars set, and is expected to drop a
 * binary into {@code SKILL_MANAGER_BIN_DIR}:
 * <ul>
 *   <li>{@code SKILL_MANAGER_BIN_DIR} — {@link SkillStore#cliBinDir()}</li>
 *   <li>{@code SKILL_DIR}             — {@link SkillStore#skillDir(String)} (the skill's source root)</li>
 *   <li>{@code SKILL_SCRIPTS_DIR}     — {@code <SKILL_DIR>/skill-scripts/}</li>
 *   <li>{@code SKILL_NAME}            — the requesting skill's name</li>
 *   <li>{@code SKILL_MANAGER_HOME}    — the store root</li>
 *   <li>{@code SKILL_MANAGER_CACHE_DIR} — scratch space; safe to clone into</li>
 * </ul>
 *
 * <p>Runs arbitrary code from the skill, so the install plan flags this
 * backend as {@code DANGER}.
 *
 * <h3>Re-run semantics</h3>
 *
 * <p>On every {@code install} / {@code sync} / {@code upgrade} pass the
 * planner emits a {@code RunCliInstall} action for every CLI dep — but
 * we don't want to re-execute a skill-script every time a user touches
 * an unrelated skill. So this backend gates re-execution on a
 * <em>content fingerprint</em>: a SHA-256 hash of every byte under the
 * skill's {@code skill-scripts/} subtree, plus the script-relative path
 * and its declared {@code args}. The fingerprint is persisted in the
 * {@link CliLock} entry as {@code install_fingerprint}.
 *
 * <ul>
 *   <li><b>First install</b> — no prior fingerprint; the script runs.</li>
 *   <li><b>{@code sync} / {@code upgrade} with no upstream changes</b> —
 *       fingerprint matches, declared binary is still present in
 *       {@code bin/cli/} → skip.</li>
 *   <li><b>{@code sync} / {@code upgrade} after upstream advances</b> —
 *       if the merge changed any file under {@code skill-scripts/}, the
 *       fingerprint flips and the script reruns. Files outside
 *       {@code skill-scripts/} (e.g. {@code SKILL.md}) don't trigger a
 *       rerun. Add args to the fingerprint via {@code install.<platform>.args}
 *       so changing a flag re-fires too.</li>
 *   <li><b>Manual {@code rm} of the binary</b> — declared binary missing
 *       → script reruns even if the fingerprint matches (recovery
 *       path).</li>
 *   <li><b>{@code uninstall} + {@code install}</b> — note: today
 *       {@link dev.skillmanager.app.RemoveUseCase} doesn't prune
 *       {@code bin/cli/} (Executor.java compensation comment on
 *       {@code UninstallCliIfOrphan} flags it as deferred), so the
 *       binary lingers and the post-reinstall fingerprint check skips.
 *       That matches the new "only on script change" rule but differs
 *       from the historical "uninstall+install always reruns"
 *       intuition. To force a rerun, edit the script (or any file
 *       under {@code skill-scripts/}) or remove the binary.</li>
 * </ul>
 */
public final class SkillScriptBackend implements InstallerBackend {

    /**
     * Conventional directory inside a skill where scripts driven by
     * the {@code skill-script:} CLI backend live. Scripts MUST be under
     * this dir — the resolver rejects any path that escapes via
     * {@code ..}.
     */
    public static final String SCRIPTS_DIRNAME = "skill-scripts";

    @Override public String id() { return "skill-script"; }

    /**
     * Always reports available — the script handles its own toolchain
     * (we can't introspect a host shell without running it). Skipping
     * here would silently swallow the install; let the script fail
     * loudly instead so the operator can see what's missing.
     */
    @Override public boolean available() { return true; }

    @Override
    public void install(CliDependency dep, SkillStore store, String skillName) throws IOException {
        Fs.ensureDir(store.cliBinDir());

        CliDependency.InstallTarget target = pickTarget(dep);
        if (target == null || target.script() == null || target.script().isBlank()) {
            Log.warn("cli: skill-script %s has no install target with a 'script' field "
                    + "(needed under [cli_dependencies.install.<platform>])", dep.name());
            return;
        }

        // Resolve the script path under <skillRoot>/skill-scripts/.
        // skillRoot is where the bytes land after CommitUnitsToStore
        // copies the staged source to the store. Plugins fall back to
        // the plugins/ subtree so a plugin can ship CLI deps too.
        ResolvedScript resolved = resolveScript(store, skillName, target.script());
        Path script = resolved.script;
        Path skillRoot = resolved.skillRoot;
        Path scriptsDir = resolved.scriptsDir;

        // Fingerprint-based skip. Compare the current scripts-tree hash
        // against what the lock recorded last successful run. If the
        // bytes haven't changed AND the binary is still where the
        // script left it, skip. The on_path-only check that lived here
        // before would short-circuit forever after the first install
        // (since bin/cli/ is on the user's PATH), so a script edit
        // never rebuilt — see ticket comment in the class javadoc.
        String currentFingerprint = fingerprintScripts(scriptsDir, target.script(), target.args());
        CliLock lock = CliLock.load(store);
        String requestedTool = dev.skillmanager.lock.RequestedVersion.of(dep).tool();
        CliLock.Entry prev = lock.get(id(), requestedTool);
        if (prev != null
                && prev.installFingerprint() != null
                && prev.installFingerprint().equals(currentFingerprint)
                && declaredBinaryStillPresent(target, store)) {
            Log.ok("cli: skill-script %s — scripts unchanged since last install (skipping)",
                    dep.name());
            return;
        }

        Fs.makeExecutable(script);

        List<String> cmd = new ArrayList<>();
        cmd.add(script.toString());
        if (target.args() != null) cmd.addAll(target.args());

        Map<String, String> env = new LinkedHashMap<>();
        env.put("SKILL_MANAGER_BIN_DIR", store.cliBinDir().toString());
        env.put("SKILL_MANAGER_HOME", store.root().toString());
        env.put("SKILL_MANAGER_CACHE_DIR", store.cacheDir().toString());
        env.put("SKILL_DIR", skillRoot.toString());
        env.put("SKILL_SCRIPTS_DIR", scriptsDir.toString());
        env.put("SKILL_NAME", skillName);
        env.put("SKILL_PLATFORM", Platform.currentKey());

        Log.step("cli: skill-script %s — running %s", dep.name(), script);
        int rc = Shell.run(cmd, env);
        if (rc != 0) {
            throw new IOException("skill-script " + dep.name() + " exited " + rc);
        }

        // Optional verification: if the user told us which binary the
        // script produces, fail fast when it's missing rather than
        // claiming success and letting a downstream `EnsureTool`
        // surface a confusing "not on PATH" later.
        if (target.binary() != null && !target.binary().isBlank()) {
            Path produced = store.cliBinDir().resolve(target.binary());
            if (!Files.isExecutable(produced)) {
                throw new IOException("skill-script " + dep.name()
                        + " exited 0 but did not produce executable "
                        + produced + " (declared binary='" + target.binary() + "')");
            }
            Log.ok("cli: installed %s -> %s", dep.name(), produced);
        } else {
            Log.ok("cli: skill-script %s completed (no 'binary' declared — "
                    + "skipping post-run verification)", dep.name());
        }
    }

    /**
     * SHA-256 over the entire {@code skill-scripts/} tree of the unit,
     * the script's path-under-scriptsDir, and its arg list. Stable
     * across re-runs as long as no byte under the dir changes.
     *
     * <p>Public so {@link dev.skillmanager.lock.CliInstallRecorder} can
     * compute the same value after a successful install and stamp it
     * into the lock — keeping the "skip iff fingerprint matches"
     * decision in this class while keeping the persistence in the
     * recorder.
     *
     * <p>If the unit isn't installed yet (no scripts dir on disk),
     * returns {@code null} — the caller should treat that as "no prior
     * fingerprint" and run unconditionally.
     */
    public static String fingerprintFor(SkillStore store, String unitName,
                                        CliDependency dep) {
        CliDependency.InstallTarget target = pickTarget(dep);
        if (target == null || target.script() == null || target.script().isBlank()) return null;
        try {
            ResolvedScript resolved = resolveScript(store, unitName, target.script());
            return fingerprintScripts(resolved.scriptsDir, target.script(), target.args());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Hash every file under {@code scriptsDir} (recursive, lexical
     * order on relative paths) plus the {@code script} path-spec and
     * the {@code args} list. Lexical sort makes the hash deterministic
     * regardless of filesystem walk order.
     */
    static String fingerprintScripts(Path scriptsDir, String scriptRel, List<String> args)
            throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Domain-separation prefix so a future caller hashing
            // differently structured input can't collide with this scheme.
            md.update("skill-script-v1\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update(("script:" + scriptRel + "\0")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (args != null) {
                for (String a : args) {
                    md.update(("arg:" + a + "\0")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            if (Files.isDirectory(scriptsDir)) {
                List<Path> files;
                try (var s = Files.walk(scriptsDir)) {
                    files = s.filter(Files::isRegularFile).sorted().toList();
                }
                for (Path f : files) {
                    String rel = scriptsDir.relativize(f).toString().replace('\\', '/');
                    md.update(("file:" + rel + "\0")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    try (InputStream in = Files.newInputStream(f)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
                    }
                    md.update((byte) 0);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static boolean declaredBinaryStillPresent(CliDependency.InstallTarget target,
                                                      SkillStore store) {
        if (target.binary() == null || target.binary().isBlank()) {
            // No binary declared = nothing to verify; fingerprint
            // alone decides re-run.
            return true;
        }
        return Files.isExecutable(store.cliBinDir().resolve(target.binary()));
    }

    private record ResolvedScript(Path script, Path skillRoot, Path scriptsDir) {}

    /**
     * Resolve {@code script} under {@code <unit>/skill-scripts/} for
     * either a skill or a plugin install. The {@code script} value is a
     * path relative to the {@code skill-scripts/} dir — {@code "install.sh"}
     * or {@code "subdir/install.sh"} — and may not contain {@code ..}
     * segments that escape that dir.
     */
    private static ResolvedScript resolveScript(SkillStore store, String unitName, String script)
            throws IOException {
        IOException firstError = null;
        for (dev.skillmanager.model.UnitKind kind : new dev.skillmanager.model.UnitKind[]{
                dev.skillmanager.model.UnitKind.SKILL,
                dev.skillmanager.model.UnitKind.PLUGIN}) {
            Path root = store.unitDir(unitName, kind);
            Path scriptsDir = root.resolve(SCRIPTS_DIRNAME);
            // Reject paths that leave the scripts dir via `..` even
            // before checking existence — a malicious manifest with
            // `script = "../../etc/passwd"` should not be able to
            // probe the filesystem.
            Path candidate = scriptsDir.resolve(script).normalize();
            if (!candidate.startsWith(scriptsDir.normalize())) {
                throw new IOException("skill-script path escapes "
                        + SCRIPTS_DIRNAME + "/: " + script);
            }
            if (Files.isRegularFile(candidate)) {
                return new ResolvedScript(candidate, root, scriptsDir);
            }
            if (firstError == null) {
                firstError = new IOException("skill-script not found: " + candidate
                        + " (declared as '" + script + "', expected under "
                        + scriptsDir + ")");
            }
        }
        throw firstError;
    }

    private static CliDependency.InstallTarget pickTarget(CliDependency dep) {
        if (dep.install().isEmpty()) return null;
        // Platform-specific entries take precedence over `any`, matching
        // TarBackend's selection rule.
        for (var e : dep.install().entrySet()) {
            if ("any".equals(e.getKey())) continue;
            if (Platform.matches(e.getKey())) return e.getValue();
        }
        return dep.install().get("any");
    }
}
