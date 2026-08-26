package dev.skillmanager.launch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Where the {@code skill-manager} that is running right now lives on disk.
 *
 * <h2>Why this is not {@code command -v skill-manager}</h2>
 *
 * <p>Asking {@code PATH} answers a different question — "which build is
 * installed globally" — and on a developer machine that is routinely an older
 * release. The released 0.19.2 has no {@code exec} subcommand at all, and it
 * answers an unknown subcommand by printing top-level usage; some builds even
 * exit 0 doing it. So a shim that resolves its CLI from {@code PATH} does not
 * degrade gracefully, it degrades *invisibly*. That is the defect issue #61
 * names, and the only durable fix is for the command that writes the shim to
 * write down the build that wrote it.
 *
 * <h2>What "the running CLI" means, given that the process is a JVM</h2>
 *
 * <p>Every distribution of this CLI is a shell launcher that {@code exec}s a
 * JVM, so {@code ProcessHandle.current().info().command()} usually answers
 * {@code /usr/bin/java} rather than {@code skill-manager}. Each launcher does,
 * however, export {@code SKILL_MANAGER_INSTALL_DIR} before it execs — it has to,
 * because the gateway source tree is found relative to it — and that variable
 * is the reliable back-reference from the JVM to the launcher that started it:
 *
 * <ul>
 *   <li><b>Source checkout</b> ({@code <repo>/skill-manager}): exports the repo
 *       root, and the launcher is {@code <dir>/skill-manager}.</li>
 *   <li><b>Release tarball / Homebrew</b> ({@code <prefix>/bin/skill-manager}):
 *       exports {@code <prefix>/share}, and the launcher is
 *       {@code <dir>/../bin/skill-manager}.</li>
 * </ul>
 *
 * <p>Both shapes are probed, plus the jar's own location as a last resort for a
 * {@code java -jar} invocation that lost the variable. Every candidate is
 * confirmed to be an executable file named {@code skill-manager} before it is
 * returned; nothing is constructed hopefully.
 *
 * <h2>It refuses rather than guessing</h2>
 *
 * <p>When none of those find a launcher, {@link #locate()} throws. It does not
 * fall back to {@code PATH}: falling back to {@code PATH} is the bug. The
 * diagnostic names every probe that was tried and what it saw, because the
 * caller ({@code home shims}) is being asked to bake this answer into a file
 * that outlives the process.
 */
public final class RunningCli {

    private RunningCli() {}

    /** The basename every distribution of this CLI installs. */
    public static final String BINARY = "skill-manager";

    /**
     * Set by every launcher before it execs the JVM, so that bundled assets can
     * be found. Reused here as the back-reference to the launcher itself.
     */
    public static final String INSTALL_DIR = "SKILL_MANAGER_INSTALL_DIR";

    /** An explicit pin, honoured first — a harness that built its own CLI. */
    public static final String CLI_ENV = "SKILL_MANAGER_CLI";

    /** Thrown when the running CLI's own location cannot be established. */
    public static final class UnknownLocationException extends RuntimeException {
        public UnknownLocationException(String message) { super(message); }
    }

    /**
     * The absolute, normalized path of the {@code skill-manager} launcher that
     * started this process.
     *
     * @throws UnknownLocationException when no probe finds one — never a
     *         {@code PATH} fallback, and never null.
     */
    public static Path locate() {
        return locate(System::getenv,
                ProcessHandle.current().info().command().orElse(null),
                codeSource());
    }

    /**
     * {@link #locate()}, or null when the running build cannot be established.
     *
     * <p>For callers that are not writing the answer into a file. {@code home
     * shims} must refuse rather than pin a guess, which is why {@link #locate()}
     * throws; a REMEDY has a next candidate to try, so for it "not determinable"
     * is a fact to move past rather than a failure. Added for
     * {@link dev.skillmanager.store.HomeDescriptor#locateCli}, whose second step
     * used to be a {@code ProcessHandle} basename test that no shipped launcher
     * could satisfy — this class already knew the answer that step wanted (#161).
     */
    public static Path locateOrNull() {
        try {
            return locate();
        } catch (UnknownLocationException notDeterminable) {
            return null;
        }
    }

    /**
     * The testable form. Every ambient input the resolution depends on is a
     * parameter, because {@code System.getenv} cannot be set from inside a JVM
     * and a resolution rule nobody can drive is a rule nobody can test — which
     * is how the {@code PATH} fallback survived this long.
     *
     * @param env            environment lookup
     * @param processCommand {@code ProcessHandle.current().info().command()}
     * @param codeSource     the jar or class directory this class was loaded
     *                       from, or null
     */
    public static Path locate(Function<String, String> env, String processCommand,
                              Path codeSource) {
        Map<String, String> tried = new LinkedHashMap<>();

        // An explicit pin is intent, so the basename is NOT enforced on it: a
        // harness that built its own CLI may well have called it something else.
        String pinned = env.apply(CLI_ENV);
        Path fromEnv = probe(tried, CLI_ENV,
                pinned == null || pinned.isBlank() ? null : Path.of(pinned.trim()), false);
        if (fromEnv != null) return fromEnv;

        Path own = processCommand == null ? null : Path.of(processCommand);
        Path fromProcess = probe(tried, "the running process's own command", own, true);
        if (fromProcess != null) return fromProcess;

        String installDir = env.apply(INSTALL_DIR);
        if (installDir != null && !installDir.isBlank()) {
            Path dir = Path.of(installDir.trim());
            // A source checkout exports the directory the launcher sits in.
            Path beside = probe(tried, INSTALL_DIR + " (source checkout layout)",
                    dir.resolve(BINARY), true);
            if (beside != null) return beside;
            // A release tarball exports <prefix>/share, one level below bin/.
            Path sibling = probe(tried, INSTALL_DIR + " (release tarball layout)",
                    dir.resolve("..").resolve("bin").resolve(BINARY), true);
            if (sibling != null) return sibling;
        } else {
            tried.put(INSTALL_DIR, "unset");
        }

        if (codeSource != null) {
            // <prefix>/lib/skill-manager.jar -> <prefix>/bin/skill-manager.
            Path jarDir = codeSource.getParent();
            if (jarDir != null) {
                Path fromJar = probe(tried, "the jar's own location",
                        jarDir.resolve("..").resolve("bin").resolve(BINARY), true);
                if (fromJar != null) return fromJar;
            }
        } else {
            tried.put("the jar's own location", "not determinable");
        }

        throw new UnknownLocationException(diagnostic(tried));
    }

    /**
     * Record what a probe saw and return the path when it is usable.
     *
     * <p>Recorded even when it fails: the whole value of the refusal is that it
     * says which of four specific things was wrong, so an operator can fix the
     * one that applies instead of re-deriving this method from the source.
     */
    private static Path probe(Map<String, String> tried, String label, Path candidate,
                              boolean requireBinaryName) {
        if (candidate == null) {
            tried.put(label, "unset");
            return null;
        }
        Path abs = normalize(candidate);
        if (requireBinaryName && !isBinaryName(abs)) {
            tried.put(label, abs + " (not named " + BINARY + ")");
            return null;
        }
        if (!Files.isRegularFile(abs)) {
            tried.put(label, abs + " (no such file)");
            return null;
        }
        if (!Files.isExecutable(abs)) {
            tried.put(label, abs + " (not executable)");
            return null;
        }
        tried.put(label, abs.toString());
        return abs;
    }

    private static boolean isBinaryName(Path candidate) {
        Path name = candidate.getFileName();
        if (name == null) return false;
        String base = name.toString().toLowerCase(Locale.ROOT);
        if (base.endsWith(".exe")) base = base.substring(0, base.length() - 4);
        return base.equals(BINARY);
    }

    private static Path normalize(Path p) {
        return p.toAbsolutePath().normalize();
    }

    private static String diagnostic(Map<String, String> tried) {
        List<String> lines = new ArrayList<>();
        lines.add("cannot determine which skill-manager build is running, so there is nothing"
                + " honest to pin into the home's bin/cli/skill-manager.");
        tried.forEach((label, saw) -> lines.add("  " + label + ": " + saw));
        lines.add("  Set " + CLI_ENV + " to the skill-manager launcher you want this home to run,"
                + " then re-run `skill-manager home shims`.");
        lines.add("  Falling back to `command -v skill-manager` is deliberately NOT done: an older"
                + " CLI on PATH answers unknown subcommands with top-level usage, which is a"
                + " downgrade that looks like success.");
        return String.join("\n", lines);
    }

    /** The jar (or classes directory) this class was loaded from, or null. */
    public static Path codeSource() {
        try {
            var source = RunningCli.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            return Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }
}
