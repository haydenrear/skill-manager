package dev.skillmanager.cli.installer;

import dev.skillmanager.launch.LaunchEnv;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The one answer to "does this home already have this CLI dep, or must the
 * backend install it".
 *
 * <h2>The question the backends used to ask, and why it was the wrong one</h2>
 *
 * <p>{@code pip}, {@code npm}, {@code brew} and {@code tar} all opened with
 *
 * <pre>{@code if (dep.onPath() != null && isOnPath(dep.onPath())) return ALREADY_PRESENT;}</pre>
 *
 * where {@code isOnPath} walked the entire process {@code PATH} for any
 * executable with that basename. In a single-home world that is a reasonable
 * shorthand. In a multi-home world it answers a different question than the one
 * asked: <b>"does ANY home on this machine have jinja2"</b>, not "does THIS home
 * have it".
 *
 * <p>Measured. {@code home clone} deliberately skips {@code venvs/} — a pip
 * console script's shebang is an absolute interpreter path the kernel resolves
 * literally, so it cannot be re-anchored — but it does copy {@code bin/}, so a
 * fresh clone carries {@code bin/cli/jinja2 -> ../../venvs/jinja2-cli/bin/jinja2}
 * pointing at nothing. The documented repair is {@code sync}. With the operator's
 * global {@code ~/.skill-manager/bin/cli} on PATH — which it is, for anybody who
 * has ever installed skill-manager — {@code sync} found the GLOBAL home's working
 * {@code jinja2}, reported {@code cli: 25 already present}, and left the clone's
 * dangling shim exactly where it was. Remove that one directory from PATH and the
 * identical command installs. So the copy stayed broken, {@code home verify} kept
 * refusing it, and its printed remedy could not repair what it named: a remedy
 * that does not work, which is the defect class #142 closed.
 *
 * <h2>The two things {@code on_path} means, kept apart</h2>
 *
 * <p>Deleting the check outright would be wrong. {@code on_path} carries two
 * meanings and only one of them was broken:
 *
 * <ol>
 *   <li><b>"this home already provisioned it"</b> — the meaning the PATH walk
 *       was standing in for, and the one it got wrong. Answered here from
 *       {@link SkillStore#cliBinDir()} alone, so it is a fact about this home
 *       and no other. A dangling shim counts as ABSENT, which falls out of
 *       {@link Files#isExecutable} following the link rather than being a
 *       special case anybody has to remember.</li>
 *   <li><b>"the system may already provide this, do not install it"</b> — real,
 *       and the reason {@code brew:} deps declare it: {@code jq} from the
 *       distro, {@code git} from Xcode. Still answered from PATH, with every
 *       directory belonging to a Skill Manager home removed first. What is left
 *       is the operating system's answer, which is what that meaning was always
 *       about.</li>
 * </ol>
 *
 * <h2>Prior art</h2>
 *
 * <p>This is {@link SkillScriptBackend}'s fix, applied to the four backends that
 * did not get it. That backend hit the same wall — "the {@code on_path}-only
 * check that lived here before would short-circuit forever after the first
 * install, so a script edit never rebuilt" — and replaced the PATH question with
 * post-install evidence recorded by
 * {@link dev.skillmanager.lock.CliInstallRecorder}: a content fingerprint, plus
 * "is the declared binary still where the script left it".
 *
 * <p>The second half of that pair is exactly {@link #providedByThisHome}: the
 * evidence is the artifact on disk, re-derived from this home on every pass,
 * which cannot go stale and clears itself the moment the artifact is missing or
 * broken. No new lock column is added here.
 *
 * <h2>What this does NOT fix, stated so nobody reads a guarantee into it</h2>
 *
 * <p>An earlier draft of this javadoc justified the absent fingerprint with
 * "{@code CliLock} already re-fires on a version change". <b>That is false, and
 * it was checked.</b> {@code PlanBuilder} consults the lock in exactly one
 * place, to raise a {@code CliVersionConflict}, and that branch is guarded by
 * {@code !existing.requestedBy().contains(u.name())} — so when the SAME unit
 * bumps its own pin no conflict fires, {@code RunCliInstall} is emitted, and
 * this presence check is the only gate left. A unit moving
 * {@code pip:cowsay==6.1} to {@code ==7.0} therefore keeps the 6.1 binary for
 * as long as {@code bin/cli/cowsay} is executable.
 *
 * <p>That gap is PRE-EXISTING and unchanged by this class: the old
 * {@code isOnPath} check found the same binary — this home's own
 * {@code bin/cli} being on PATH — and skipped just as hard. It is not repaired
 * here because repairing it means recording the installed version per artifact
 * and re-firing on a mismatch, which changes what {@code cli-lock.toml} means
 * and what {@code CliDependencyCleaner} reads beside it. It is written down so
 * the next reader does not re-derive "the lock handles it" from an empty
 * {@code install_fingerprint} column.
 */
public final class CliPresence {

    /**
     * Test-only {@code PATH} override, same shape and same reason as
     * {@link dev.skillmanager.agent.AgentHomes#setOverride}: a Java process
     * cannot mutate its own environment, and the property under test is
     * literally "which directory on PATH answered". A test that could not plant
     * a foreign home's {@code bin/cli} ahead of this home could not fail
     * against the old behaviour, and a regression test that passes before the
     * fix is not a regression test.
     */
    private static final ThreadLocal<String> PATH_OVERRIDE = new ThreadLocal<>();

    private CliPresence() {}

    /** Install a thread-local {@code PATH}. Production code never calls this. */
    public static void setPathOverride(String value) {
        if (value == null) PATH_OVERRIDE.remove(); else PATH_OVERRIDE.set(value);
    }

    /** Drop the thread-local {@code PATH}. Pair with {@link #setPathOverride}. */
    public static void clearPathOverride() {
        PATH_OVERRIDE.remove();
    }

    /** The {@code PATH} this class searches: the override, else the process's. */
    static String path() {
        String override = PATH_OVERRIDE.get();
        return override != null ? override : System.getenv("PATH");
    }

    /**
     * True when {@code dep} needs no install for the home rooted at
     * {@code store}, with the reason on the run log.
     *
     * <p>Both halves are logged as a STATE ("already …"), never as an event —
     * see {@link InstallOutcome}. The distinction between them is on the line
     * because they are different facts and only one of them is repairable by
     * this program.
     */
    public static boolean alreadyProvided(CliDependency dep, SkillStore store) {
        Path inHome = providedByThisHome(dep, store);
        if (inHome != null) {
            Log.detail("✓ cli: %s already provisioned in this home (%s)", dep.name(), inHome);
            return true;
        }
        Path external = providedOutsideEveryHome(dep.onPath());
        if (external != null) {
            Log.detail("✓ cli: %s already provided by the system (%s), outside any Skill "
                    + "Manager home", dep.onPath(), external);
            return true;
        }
        return false;
    }

    /**
     * The executable in <em>this</em> home's {@code bin/cli} that satisfies
     * {@code dep}, or null.
     *
     * <p>{@code on_path} is the declared spelling and is tried for every
     * backend. {@link Files#isExecutable} resolves the link, so a shim whose
     * target a clone did not carry answers false. That is not incidental: it is
     * the entire repair path, and it is why this is a filesystem question
     * rather than a lock lookup.
     *
     * <h2>Why {@code dep.name()} is tried for {@code tar:} and nothing else</h2>
     *
     * <p>Because for {@code tar:} it is the PRE-EXISTING check, and for the
     * other three it would be a new one with a bad consequence.
     * {@code TarBackend} always had a second gate,
     * {@code Files.exists(bin/cli/<name>)}, which is this clause; folding it in
     * here changed nothing about that backend. {@code pip}, {@code npm} and
     * {@code brew} opened with {@code dep.onPath() != null && isOnPath(...)}, so
     * a dep declaring NO {@code on_path} was never suppressed and reached the
     * backend on every pass.
     *
     * <p>Applying the name fallback to those three took that away. Measured with
     * {@code spec = "pip:cowsay==6.1"} and no {@code on_path}: uv was
     * bootstrapped and then never invoked, because {@code bin/cli/cowsay} was
     * executable — with no escape hatch, since the four-argument {@code install}
     * default drops {@code force} and {@code PlanBuilder} sets it only for
     * {@code skill-script:}. Combined with the {@code CliVersionConflict} gap in
     * this class's header javadoc, that turns "no {@code on_path} declared" into
     * "never reinstalled, ever" — strictly worse than the defect this class
     * exists to fix, in a case the defect never touched.
     *
     * <p>So the fallback stays where it was earned. Widening it is a real
     * question — a dep with no {@code on_path} re-runs its installer every pass,
     * which for {@code pip} is a {@code uv tool install --force} on every sync —
     * but the answer to that is idempotence in the backend or a per-artifact
     * version record, not a presence check that can never be cleared.
     */
    public static Path providedByThisHome(CliDependency dep, SkillStore store) {
        if (dep == null || store == null) return null;
        Path binDir = store.cliBinDir();
        Path declared = executableAt(binDir, dep.onPath());
        if (declared != null) return declared;
        if (!"tar".equals(dep.backend())) return null;
        return executableAt(binDir, dep.name());
    }

    /**
     * The {@code executable} found on {@code PATH} in a directory that belongs
     * to no Skill Manager home, or null.
     *
     * <p>The filter is {@link LaunchEnv#isAnyHomeBin}, which is the same
     * structural recognition the launch PATH sanitizer uses. A fourth spelling
     * of "is this a home" would eventually disagree about exactly the homes
     * that matter (#24).
     */
    public static Path providedOutsideEveryHome(String executable) {
        return firstOnPath(executable, true);
    }

    /**
     * The {@code executable} found anywhere on {@code PATH}, or null.
     *
     * <p>For questions genuinely about the process's own toolchain —
     * {@code BrewBackend.available()} asking whether {@code brew} can be run at
     * all. <b>Not</b> for deciding whether a dep is provisioned; that is
     * {@link #alreadyProvided}, and conflating the two is the defect this class
     * exists to close.
     */
    public static Path onProcessPath(String executable) {
        return firstOnPath(executable, false);
    }

    private static Path firstOnPath(String executable, boolean skipHomeBins) {
        if (executable == null || executable.isBlank()) return null;
        String path = path();
        if (path == null || path.isBlank()) return null;
        for (String raw : path.split(File.pathSeparator, -1)) {
            if (raw == null || raw.isBlank()) continue;
            Path dir;
            try {
                dir = Path.of(raw.trim()).toAbsolutePath().normalize();
            } catch (RuntimeException malformed) {
                continue;
            }
            if (skipHomeBins && LaunchEnv.isAnyHomeBin(dir)) continue;
            Path candidate = executableAt(dir, executable);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private static Path executableAt(Path dir, String name) {
        if (name == null || name.isBlank()) return null;
        Path candidate = dir.resolve(name);
        if (!Files.isExecutable(candidate) || Files.isDirectory(candidate)) return null;
        return candidate;
    }
}
