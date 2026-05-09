package dev.skillmanager.cli.installer;

import dev.skillmanager.model.CliDependency;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import dev.skillmanager.util.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        if (dep.onPath() != null && isOnPath(dep.onPath())) {
            Log.ok("cli: %s already on PATH", dep.onPath());
            return;
        }
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
