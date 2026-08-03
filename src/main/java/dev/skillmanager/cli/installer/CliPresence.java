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
 * <p>The second half of that pair is exactly {@link #providedByThisHome}, and it
 * is all these four backends need. A fingerprint exists because a skill-script's
 * INPUT can change under a fixed version; {@code pip:jinja2-cli[yaml]==0.8.2} is
 * pinned by its own spec, and {@link dev.skillmanager.lock.CliLock} already
 * re-fires on a version change. So no new lock column: the evidence is the
 * artifact on disk, re-derived from this home on every pass, which cannot go
 * stale and clears itself the moment the artifact is missing or broken.
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
     * <p>Both declared spellings are tried — {@code on_path} and the dep's own
     * name — because a manifest may give either and {@code CliDependencyCleaner}
     * already treats both as filenames under {@code bin/cli}. The two halves of
     * install and uninstall have to agree about which files are this dep's, or
     * one of them leaks.
     *
     * <p>{@link Files#isExecutable} resolves the link, so a shim whose target a
     * clone did not carry answers false. That is not incidental: it is the
     * entire repair path, and it is why this is a filesystem question rather
     * than a lock lookup.
     */
    public static Path providedByThisHome(CliDependency dep, SkillStore store) {
        if (dep == null || store == null) return null;
        Path binDir = store.cliBinDir();
        for (String name : new String[]{dep.onPath(), dep.name()}) {
            Path candidate = executableAt(binDir, name);
            if (candidate != null) return candidate;
        }
        return null;
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
