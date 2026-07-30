package dev.skillmanager.launch;

import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Confines a launch to the tree it was given a ticket for, at the kernel,
 * using {@code /usr/bin/sandbox-exec}.
 *
 * <h2>Where this sits</h2>
 *
 * <p>{@link LaunchEnv} is <em>convention</em>: it exports the right variables
 * and orders {@code PATH}. Nothing in it <em>stops</em> a process writing to
 * {@code ~/.skill-manager}, to another project's home, or to {@code ~/.ssh}.
 * Every leak this epic fixed was a process writing where nothing stopped it,
 * and every fix was a convention that regresses when someone forgets a
 * variable. This class is the thing that does not forget.
 *
 * <p>The two are kept, not traded:
 * {@link LaunchEnv#requireClaudeRedirected()} still refuses a launch whose
 * {@code CLAUDE_CONFIG_DIR} points outside the home. <b>The env check knows
 * <em>why</em>; the sandbox knows <em>that</em>.</b> Delete the env check and a
 * missing {@code CLAUDE_CONFIG_DIR} degrades from a refusal that names the
 * variable and the repair command into a mid-session {@code EPERM} with no
 * explanation.
 *
 * <h2>One seam</h2>
 *
 * <p>{@code bin/launch/{claude,codex,gemini}} all funnel through
 * {@code skill-manager exec --home <home> --}, and {@code exec} spawns exactly
 * one {@link ProcessBuilder}. Wrapping that argv confines the harness and every
 * grandchild it will ever spawn, because a child cannot loosen an inherited
 * sandbox — measured: a nested {@code sandbox-exec} naming a root the outer
 * profile does not allow dies with {@code sandbox_apply: Operation not
 * permitted} (exit 71) and the write does not happen.
 *
 * <h2>Opt-in, per home</h2>
 *
 * <p>Both {@code SKILL_MANAGER_SANDBOX=1} and a {@code <home>/launch.sb} are
 * required, and {@code skill-manager home shims --sandbox} emits both. A home
 * that never opted in launches exactly as it does today. That is the structural
 * answer to {@code sandbox-exec(1)} having been deprecated since 2017 with no
 * successor: if it disappears, homes that opted in start refusing loudly and
 * every other home is unaffected.
 *
 * <p>The descriptor's declaration wins over the ambient environment, and the
 * ambient environment is consulted only when the descriptor is silent. A home
 * that declares itself sandboxed cannot be un-sandboxed by exporting a variable
 * before the launch.
 *
 * <h2>The writable root is the WORKTREE, not the home</h2>
 *
 * <p>An agent handed a ticket must edit the source tree the ticket is about.
 * {@code <worktree>/.skill-manager}, {@code .claude}, {@code .codex} and
 * {@code .gemini} are all inside that tree already, because
 * {@code home.runtime.json} puts {@code CLAUDE_CONFIG_DIR} at
 * {@code $SKILL_MANAGER_HOME/../.claude}. So {@link LaunchEnv#homeRoot()} is
 * the allowed root and the home comes along for free.
 *
 * <h2>Every parameter is a realpath, and that is not optional</h2>
 *
 * <p>The kernel canonicalizes the path being <em>accessed</em> but not the path
 * written in the <em>rule</em>. A rule spelled {@code /tmp/x} therefore never
 * matches a write to {@code /private/tmp/x}; a rule spelled relatively matches
 * nothing at all. In a <b>deny-listing</b> profile that is a fail-OPEN — the
 * denial silently covers nothing — and it is the {@code /var} vs
 * {@code /private/var} shape that has now defeated four checks in this
 * codebase.
 *
 * <p>This profile is an <b>allow-list</b>, which converts both mistakes from
 * fail-open to fail-closed: a mis-spelled writable root means the agent cannot
 * write to its own worktree, loudly, rather than the operator's home being
 * quietly unprotected. Measured both ways: with {@code OP_WORKTREE} spelled
 * {@code /tmp/…} against a worktree at {@code /private/tmp/…}, the write inside
 * the worktree was denied and the write to the decoy home stayed denied.
 *
 * <p>The validation below is therefore defence in depth rather than the only
 * defence — and it is still required, because the moment anyone adds a
 * {@code (deny …)} rule keyed on a parameter, the fail-open returns. It is
 * cheaper to refuse a non-canonical parameter forever than to remember that.
 *
 * <h2>What is refused, and why each one</h2>
 *
 * <ul>
 *   <li><b>A relative parameter</b> — matches nothing.</li>
 *   <li><b>A non-canonical parameter</b> — matches nothing, or the wrong
 *       thing.</li>
 *   <li><b>A parameter that is not an existing directory</b> — a
 *       {@code (subpath …)} of a non-directory is a rule about nothing.</li>
 *   <li><b>A writable root that is the user's home directory, or a filesystem
 *       root</b> — a profile that grants {@code $HOME} is a profile that
 *       reports enforcement and enforces nothing. This is exactly what
 *       sandboxing the <em>global</em> home at {@code ~/.skill-manager} would
 *       do, since its home root is {@code ~}.</li>
 *   <li><b>A profile that fails {@link SeatbeltProfile#validate}</b> — see
 *       there for last-match-wins.</li>
 * </ul>
 *
 * <h2>What it does not stop</h2>
 *
 * <p>See {@link SeatbeltProfile} — inherited write file descriptors,
 * pre-existing daemons acting as confused deputies, and hardlinks made outside
 * the sandbox. All three are reachable by ordinary code with no adversarial
 * intent. This is a filesystem boundary against accidents, not containment.
 */
public final class SeatbeltSandbox {

    private SeatbeltSandbox() {}

    /** Opt in with {@code =1} (also {@code true}/{@code yes}/{@code on}). */
    public static final String ENABLE_VAR = "SKILL_MANAGER_SANDBOX";

    /**
     * Exported into the sandboxed child, carrying the profile that confines it.
     *
     * <p>Read on the way in as well: a nested {@code skill-manager exec} inside
     * an already-sandboxed session must NOT wrap again. Re-applying buys
     * nothing (the child already cannot loosen the inherited policy) and a
     * nested apply naming a different worktree is refused by the kernel, which
     * would turn "launch codex from inside claude" into an unexplained exit 71.
     */
    public static final String ACTIVE_VAR = "SKILL_MANAGER_SEATBELT";

    /** {@code <home>/launch.sb}. */
    public static final String PROFILE_FILENAME = "launch.sb";

    /** The system tool. Present since 10.5, deprecated since 2017, still shipped. */
    public static final Path EXECUTABLE = Path.of("/usr/bin/sandbox-exec");

    /** {@code <home>/launch.sb} for {@code store}. */
    public static Path profileFile(SkillStore store) {
        return store.root().resolve(PROFILE_FILENAME).toAbsolutePath().normalize();
    }

    /**
     * A resolved decision to sandbox: the profile, the bound parameters, and
     * the extra environment the confined child needs.
     */
    public record Plan(Path profile, Map<String, String> parameters, Map<String, String> env) {

        public Plan {
            parameters = Map.copyOf(parameters);
            env = Map.copyOf(env);
        }

        /**
         * {@code argv} with {@code sandbox-exec} in front of it.
         *
         * <p>Paths travel as {@code -D NAME=VALUE}, never spliced into the
         * profile text: SBPL is a Lisp dialect and an interpolated path
         * containing a quote is an injection vector.
         */
        public List<String> wrap(List<String> argv) {
            List<String> out = new ArrayList<>();
            out.add(EXECUTABLE.toString());
            out.add("-f");
            out.add(profile.toString());
            for (Map.Entry<String, String> p : parameters.entrySet()) {
                out.add("-D");
                out.add(p.getKey() + "=" + p.getValue());
            }
            out.addAll(argv);
            return out;
        }
    }

    // --------------------------------------------------------------- plan

    /**
     * Decide whether this launch is sandboxed, and how.
     *
     * @param ambient the launching process's own environment; consulted for
     *        {@link #ACTIVE_VAR}, and for {@link #ENABLE_VAR} only when the
     *        home's descriptor does not declare it
     * @return empty when this home did not opt in, or when the process is
     *         already inside a skill-manager sandbox
     * @throws SeatbeltRefusedException when the sandbox was asked for and
     *         cannot be given — never a warning, see that class
     */
    public static Optional<Plan> planFor(SkillStore store, LaunchEnv launch,
                                         Map<String, String> ambient)
            throws SeatbeltRefusedException {
        Map<String, String> env = ambient == null ? Map.of() : ambient;
        String declared = launch.env().get(ENABLE_VAR);
        String requested = declared != null ? declared : env.get(ENABLE_VAR);
        if (!enabled(requested)) return Optional.empty();

        String already = env.get(ACTIVE_VAR);
        if (already != null && !already.isBlank()) return Optional.empty();

        if (!Files.isExecutable(EXECUTABLE)) {
            throw new SeatbeltRefusedException(
                    "refusing to launch: " + ENABLE_VAR + "=" + requested + " asks for the kernel "
                            + "sandbox, but " + EXECUTABLE + " is not executable on this machine. "
                            + "Launching unsandboxed would leave the caller believing this process "
                            + "cannot write to another home when nothing stops it. Drop "
                            + ENABLE_VAR + " from the home's descriptor "
                            + "(`skill-manager home describe --home " + store.root()
                            + " --set-env " + ENABLE_VAR + "=0 --write`) to launch without it.");
        }

        Path profile = profileFile(store);
        if (!Files.isRegularFile(profile)) {
            throw new SeatbeltRefusedException(
                    "refusing to launch: " + ENABLE_VAR + "=" + requested + " asks for the kernel "
                            + "sandbox, but " + profile + " does not exist. Write it with "
                            + "`skill-manager home shims --sandbox --home " + store.root() + "`.");
        }
        String text;
        try {
            text = Files.readString(profile);
        } catch (IOException unreadable) {
            throw new SeatbeltRefusedException("refusing to launch: cannot read " + profile
                    + " (" + unreadable.getMessage() + ")");
        }
        List<SeatbeltProfile.Problem> problems = SeatbeltProfile.validate(text);
        if (!problems.isEmpty()) {
            StringBuilder message = new StringBuilder(
                    "refusing to launch: " + profile + " would not enforce what it looks like it "
                            + "enforces. SBPL is last-match-wins, so a single broad allow anywhere "
                            + "reopens everything it names while the file still reads as "
                            + "deny-by-default:");
            for (SeatbeltProfile.Problem problem : problems) {
                message.append("\n  ").append(problem);
            }
            message.append("\n  Restore the shipped profile with `skill-manager home shims "
                    + "--sandbox --home ").append(store.root()).append("`.");
            throw new SeatbeltRefusedException(message.toString());
        }

        Path worktree = writableRoot("OP_WORKTREE", launch.homeRoot());
        Path storeRoot = writableRoot("OP_STORE", store.root());
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("OP_WORKTREE", worktree.toString());
        parameters.put("OP_STORE", storeRoot.toString());
        parameters.put("OP_TMPDIR", canonicalDirectory("OP_TMPDIR", tmpdir(env)).toString());
        parameters.put("OP_SYSTMP", canonicalDirectory("OP_SYSTMP", Path.of("/tmp")).toString());
        parameters.put("OP_VARTMP", canonicalDirectory("OP_VARTMP", Path.of("/var/tmp")).toString());

        // The post-condition, not a re-run of the work above. Everything in the
        // map was canonicalized on the way in, so this can only fire if some
        // future caller assembles a parameter another way — which is the case
        // worth catching, because the mistake is invisible in the profile, in
        // the argv, and in the exit code.
        requireCanonicalParameters(parameters);
        return Optional.of(new Plan(profile, parameters, childEnv(profile, worktree)));
    }

    private static boolean enabled(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    private static Path tmpdir(Map<String, String> env) {
        String declared = env.get("TMPDIR");
        return declared == null || declared.isBlank() ? Path.of("/tmp") : Path.of(declared);
    }

    // ------------------------------------------------------------- checks

    /**
     * REFUSE a parameter map that is not fit to be passed as {@code -D} values.
     *
     * <p>Every value must be absolute and its own realpath. A relative value is
     * accepted by {@code sandbox-exec} in silence and matches <b>nothing</b>; a
     * value spelled through a symlink matches nothing either, because the
     * kernel canonicalizes the path being <em>accessed</em> and not the path
     * written in the <em>rule</em>. Measured: {@code -D OP_WORKTREE=/tmp/x}
     * against a worktree that really is {@code /private/tmp/x} denies the
     * write inside the worktree — the rule covers no path at all.
     *
     * <p>In a rule that <b>denies</b> by parameter that same mistake is a
     * fail-OPEN: the denial silently covers nothing and the write succeeds,
     * exit 0, file present. The shipped profile only ever <em>grants</em> by
     * parameter, which turns it into a fail-CLOSED — but the check stays
     * unconditional, because the day someone adds a parameterized
     * {@code (deny …)} is not the day anyone will remember this.
     *
     * <p>Public because a check nobody can call from a test is a check nobody
     * has proven sensitive.
     */
    public static void requireCanonicalParameters(Map<String, String> parameters)
            throws SeatbeltRefusedException {
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                throw new SeatbeltRefusedException("refusing to launch: sandbox parameter "
                        + name + " has no value.");
            }
            Path path = Path.of(value);
            if (!path.isAbsolute()) {
                throw new SeatbeltRefusedException("refusing to launch: sandbox parameter " + name
                        + "=" + value + " is relative. sandbox-exec accepts it silently and it "
                        + "matches NOTHING, so the rule it appears in grants and denies nothing.");
            }
            Path real;
            try {
                real = path.toRealPath();
            } catch (IOException missing) {
                throw new SeatbeltRefusedException("refusing to launch: sandbox parameter " + name
                        + "=" + value + " does not resolve (" + missing.getMessage() + "). A "
                        + "(subpath …) of a path that is not there is a rule about nothing.");
            }
            if (!real.equals(path.normalize())) {
                throw new SeatbeltRefusedException("refusing to launch: sandbox parameter " + name
                        + "=" + value + " is not canonical — it really is " + real + ". The kernel "
                        + "canonicalizes the path being ACCESSED but not the path written in the "
                        + "RULE, so a rule spelled through a symlink matches neither spelling. "
                        + "This is the /var vs /private/var shape that has defeated four checks "
                        + "in this codebase; pass the realpath.");
            }
        }
    }

    /**
     * The realpath of {@code value}, refused unless it resolves to a directory.
     *
     * <p>Canonicalizing rather than refusing a non-canonical input is
     * deliberate here: a worktree under {@code /var/folders/…} or a
     * {@code /tmp} symlink is an ordinary, correct thing for a caller to hand
     * over, and refusing it would be a boundary that only works for people who
     * already know about {@code /private}. What must never happen is a
     * non-canonical value reaching the argv, and
     * {@link #requireCanonicalParameters} is the post-condition that makes
     * that impossible.
     */
    public static Path canonicalDirectory(String name, Path value)
            throws SeatbeltRefusedException {
        if (value == null) {
            throw new SeatbeltRefusedException("sandbox parameter " + name + " is null");
        }
        Path real;
        try {
            real = value.toAbsolutePath().toRealPath();
        } catch (IOException missing) {
            throw new SeatbeltRefusedException("refusing to launch: sandbox parameter " + name
                    + "=" + value + " does not resolve (" + missing.getMessage() + "). A "
                    + "(subpath …) of a path that is not there is a rule about nothing.");
        }
        if (!Files.isDirectory(real)) {
            throw new SeatbeltRefusedException("refusing to launch: sandbox parameter " + name
                    + "=" + real + " is not a directory.");
        }
        return real;
    }

    /**
     * {@link #canonicalDirectory} plus: a writable root may not be the
     * user's home directory or a filesystem root.
     *
     * <p>Without this the <em>global</em> home would sandbox itself into
     * uselessness — its store is {@code ~/.skill-manager}, so its home root is
     * {@code ~}, so {@code OP_WORKTREE} would be {@code $HOME} and the profile
     * would grant write access to every home it exists to protect while
     * reporting that a sandbox is in force. A boundary that reports success and
     * bounds nothing is worse than none, because someone will rely on it.
     */
    public static Path writableRoot(String name, Path value)
            throws SeatbeltRefusedException {
        Path real = canonicalDirectory(name, value);
        if (real.getParent() == null) {
            throw new SeatbeltRefusedException("refusing to launch: sandbox parameter " + name
                    + "=" + real + " is a filesystem root — that grants everything.");
        }
        Path userHome = userHome();
        if (userHome != null && real.equals(userHome)) {
            throw new SeatbeltRefusedException("refusing to launch: sandbox parameter " + name
                    + "=" + real + " is the user's home directory, so the profile would grant "
                    + "writes to ~/.skill-manager, ~/.claude, ~/.codex and ~/.gemini — the four "
                    + "homes it exists to protect — while reporting that a sandbox is in force. "
                    + "The global home has this shape by construction; sandbox a per-worktree "
                    + "home instead, or pass --home-root.");
        }
        return real;
    }

    private static Path userHome() {
        String home = System.getenv("HOME");
        if (home == null || home.isBlank()) home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return null;
        try {
            return Path.of(home).toRealPath();
        } catch (IOException | RuntimeException unresolvable) {
            return Path.of(home).toAbsolutePath().normalize();
        }
    }

    // ---------------------------------------------------------- child env

    /**
     * What the confined child needs beyond {@link LaunchEnv#exportedEnv()}.
     *
     * <h2>The build caches nobody had redirected</h2>
     *
     * <p>There is no {@code GRADLE_USER_HOME}, {@code UV_CACHE_DIR},
     * {@code npm_config_cache} or {@code XDG_CACHE_HOME} anywhere in these
     * sources, so every one of those tools writes under {@code $HOME} and a
     * strict profile breaks every {@code test_graph} run.
     *
     * <p>They are <b>redirected into the home rather than allowed out of it</b>
     * — the same argument that produced {@code CLAUDE_CONFIG_DIR}. Widening the
     * profile to {@code ~/.gradle} etc. would reintroduce, one directory at a
     * time, exactly the shared mutable state the three-tier home model exists
     * to remove; redirecting makes a home more reproducible instead, because
     * what a build resolved is now inside the tier that was copied.
     *
     * <p>The cost is real and is accepted: the first sandboxed build in a fresh
     * home re-downloads its dependencies, because reads of the operator's cache
     * are allowed but writes are not, and a half-usable cache is worse than a
     * cold one. This is applied <b>only when the sandbox is engaged</b>, so an
     * ordinary launch is byte-for-byte unaffected.
     *
     * <p>{@code ~/.m2} is NOT redirected: Maven has no cache environment
     * variable, only {@code -Dmaven.repo.local}. A sandboxed Maven build must
     * pass it. Recorded here rather than half-solved in silence.
     */
    private static Map<String, String> childEnv(Path profile, Path worktree) {
        Path cache = worktree.resolve(".cache");
        Map<String, String> env = new LinkedHashMap<>();
        env.put(ACTIVE_VAR, profile.toString());
        env.put("GRADLE_USER_HOME", cache.resolve("gradle").toString());
        env.put("UV_CACHE_DIR", cache.resolve("uv").toString());
        env.put("npm_config_cache", cache.resolve("npm").toString());
        env.put("XDG_CACHE_HOME", cache.toString());
        env.put("JBANG_DIR", cache.resolve("jbang").toString());
        // Created here, not by the child: a tool handed a cache directory it
        // cannot create is a tool that fails inside the sandbox for a reason
        // that looks like the sandbox and is not.
        for (String key : List.of("GRADLE_USER_HOME", "UV_CACHE_DIR", "npm_config_cache",
                "XDG_CACHE_HOME", "JBANG_DIR")) {
            try {
                Fs.ensureDir(Path.of(env.get(key)));
            } catch (IOException uncreatable) {
                // Non-fatal: the tool will report it, and refusing the launch
                // over a cache directory would be a worse trade than the
                // refusals above, which are about the boundary itself.
                dev.skillmanager.util.Log.warn("could not create %s for the sandboxed launch (%s)",
                        env.get(key), uncreatable.getMessage());
            }
        }
        return env;
    }
}
