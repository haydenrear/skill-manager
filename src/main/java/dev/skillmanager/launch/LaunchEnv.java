package dev.skillmanager.launch;

import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.commands.HomeCommand;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.store.HomeDescriptor;
import dev.skillmanager.store.SkillStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a process needs to be bound to one Skill Manager home: the
 * environment to export, the {@code PATH} to run with, and the check that the
 * two of them really do point the agent away from the operator's global home.
 *
 * <h2>Environment alone does not isolate a home</h2>
 *
 * <p>The env block in {@code home.runtime.json} redirects everything that
 * <em>reads</em> an environment variable. It does not redirect anything
 * resolved through {@code PATH}, and skill-manager installs
 * {@code skill-script:} CLI dependencies as generated shell scripts with the
 * home's absolute path baked into the body:
 *
 * <pre>
 * $ cat ~/.skill-manager/bin/cli/tla-spec-dev
 * #!/usr/bin/env bash
 * exec python3 "/Users/me/.skill-manager/skills/spec-double-compiler/scripts/tla_spec_dev.py" "$@"
 * </pre>
 *
 * <p>No variable redirects that. So an agent working in a project with a fully
 * isolated home still ran the <em>global</em> home's scripts, and one of them
 * was observed writing {@code __pycache__} back into the shared home. The
 * premise of a per-project home — copy it, override the variable, get
 * isolation — is false for every CLI dependency unless {@code PATH} is handled
 * too.
 *
 * <p>Two things are therefore done to {@code PATH}, and the second matters as
 * much as the first:
 *
 * <ol>
 *   <li>The active home's {@code bin/} directories go <b>first</b>, so a tool
 *       this home provides wins over the same tool in any other home.</li>
 *   <li>{@code bin/} directories belonging to a <b>different</b> Skill Manager
 *       home are <b>removed</b>. Precedence alone would still fall through to
 *       the global home for any tool the project home does not have, and that
 *       fall-through executes a script that reads and writes the other home.
 *       "Not installed here" must fail, not silently run somewhere else.</li>
 * </ol>
 *
 * <h2>The Claude check</h2>
 *
 * <p>See {@link #requireClaudeRedirected()}. It is the one condition that is
 * fatal rather than advisory, because {@code CLAUDE_CONFIG_DIR} is where
 * skills live.
 */
public final class LaunchEnv {

    /** {@code <store>/bin/launch} — where the generated agent shims live. */
    public static final String LAUNCHER_DIR = "launch";

    private final Path homeRoot;
    private final SkillStore store;
    private final Map<String, String> env;
    private final List<Path> pathPrefix;
    private final List<Path> pathEntries;

    private LaunchEnv(Path homeRoot, SkillStore store, Map<String, String> env,
                      List<Path> pathPrefix, List<Path> pathEntries) {
        this.homeRoot = homeRoot;
        this.store = store;
        this.env = Map.copyOf(env);
        this.pathPrefix = List.copyOf(pathPrefix);
        this.pathEntries = List.copyOf(pathEntries);
    }

    public Path homeRoot() { return homeRoot; }

    public SkillStore store() { return store; }

    /** The variables to export, in descriptor order, contributions last. */
    public Map<String, String> env() { return env; }

    /** The home's own {@code bin/} directories, in the order they are prepended. */
    public List<Path> pathPrefix() { return pathPrefix; }

    /** The full effective {@code PATH}, prefix first, foreign homes removed. */
    public List<Path> pathEntries() { return pathEntries; }

    public String pathValue() {
        List<String> parts = new ArrayList<>();
        for (Path p : pathEntries) parts.add(p.toString());
        return String.join(File.pathSeparator, parts);
    }

    /** {@link #env()} plus the computed {@code PATH}, ready for a subprocess. */
    public Map<String, String> exportedEnv() {
        Map<String, String> out = new LinkedHashMap<>(env);
        out.put("PATH", pathValue());
        return out;
    }

    // ------------------------------------------------------------- assembly

    /**
     * Assemble the launch environment for {@code store}.
     *
     * @param homeRootOverride the directory holding {@code .claude}/{@code
     *        .codex}/{@code .gemini}; derived from the store when null
     * @param inheritedPath the {@code PATH} to sanitize; the process's own
     *        when null. Passed explicitly so the sanitizer is testable without
     *        mutating the test JVM's environment.
     * @param bootstrap persist a freshly derived {@code home.runtime.json} when
     *        the home has none. Ignored on a frozen home — bootstrapping is a
     *        write, and a frozen home refuses writes even helpful ones.
     */
    public static LaunchEnv of(SkillStore store, Path homeRootOverride,
                               String inheritedPath, boolean bootstrap) throws IOException {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        HomePolicy policy = HomePolicy.load(store);
        Optional<HomeDescriptor> existing = HomeDescriptor.read(store.root());

        Path root;
        Map<String, String> assembled = new LinkedHashMap<>();
        if (existing.isPresent()) {
            HomeDescriptor descriptor = existing.get();
            root = homeRootOverride != null
                    ? homeRootOverride.toAbsolutePath().normalize()
                    : descriptor.homeRoot();
            if (descriptor.env() != null) assembled.putAll(descriptor.env().asMap());
            // Contributions last, and allowed to win. They are the operator's
            // explicit statement about this home. One of them pointing
            // CLAUDE_CONFIG_DIR back at ~/.claude is precisely what
            // requireClaudeRedirected() exists to catch, so silently ignoring
            // an override here would hide the misconfiguration rather than
            // report it.
            assembled.putAll(descriptor.envContributions());
        } else {
            root = homeRootOverride != null
                    ? homeRootOverride.toAbsolutePath().normalize()
                    : HomeDescriptor.homeRootFor(store.root());
            assembled.putAll(HomeDescriptor.envFor(root, store.root()).asMap());
            if (bootstrap && !policy.frozen()) {
                store.init();
                HomeDescriptor derived = HomeCommand.describe(store, root, Map.of());
                derived.write(store.root());
            }
        }

        List<Path> prefix = binPrefix(store);
        List<Path> entries = effectivePath(prefix, inheritedPath, store.root());
        return new LaunchEnv(root, store, assembled, prefix, entries);
    }

    /**
     * The home's own executable directories, highest precedence first.
     *
     * <p>{@code bin/cli} leads because that is where the hardcoded
     * {@code skill-script} shims live and therefore where a collision with
     * another home is actually possible.
     */
    public static List<Path> binPrefix(SkillStore store) {
        return List.of(
                store.cliBinDir().toAbsolutePath().normalize(),
                store.mcpBinDir().toAbsolutePath().normalize(),
                launcherDir(store));
    }

    /** {@code <store>/bin/launch}. */
    public static Path launcherDir(SkillStore store) {
        return store.binDir().resolve(LAUNCHER_DIR).toAbsolutePath().normalize();
    }

    /**
     * {@code prefix} followed by {@code inheritedPath} with duplicates and
     * foreign-home {@code bin/} directories removed.
     */
    static List<Path> effectivePath(List<Path> prefix, String inheritedPath, Path activeStoreRoot) {
        LinkedHashSet<Path> ordered = new LinkedHashSet<>(prefix);
        if (inheritedPath == null || inheritedPath.isBlank()) return List.copyOf(ordered);
        Path active = activeStoreRoot == null ? null : activeStoreRoot.toAbsolutePath().normalize();
        for (String raw : inheritedPath.split(File.pathSeparator, -1)) {
            if (raw == null || raw.isBlank()) continue;
            Path entry;
            try {
                entry = Path.of(raw.trim()).toAbsolutePath().normalize();
            } catch (RuntimeException malformed) {
                continue;
            }
            if (isForeignHomeBin(entry, active)) continue;
            ordered.add(entry);
        }
        return List.copyOf(ordered);
    }

    /**
     * True when {@code entry} is an executable directory belonging to some
     * Skill Manager home other than {@code activeStoreRoot}.
     *
     * <p>Recognition is by layout rather than by name: the store root above the
     * entry either carries a {@code home.runtime.json} or has the
     * {@code installed/} + {@code skills/} pair every store has. Matching on
     * the literal name {@code .skill-manager} would miss a home that was
     * cloned to a differently named directory, which is the normal case for a
     * per-project home.
     */
    static boolean isForeignHomeBin(Path entry, Path activeStoreRoot) {
        if (entry == null) return false;
        // This walk was bounded to three levels, on the reasoning that
        // <store>/bin and <store>/bin/{cli,mcp,launch} were "the whole shape".
        // They are not: <store>/plugin-marketplace/plugins/<name>/bin is four
        // levels down, so a foreign home's plugin bin survived on every launch
        // PATH — the isolation this class exists to provide, silently absent.
        //
        // The bound is gone rather than raised. Raising it to four would be the
        // same mistake with a bigger number, and this epic has now hit
        // "enumeration correct for imagined shapes" often enough to stop
        // enumerating: a skip list of directory names that missed a cache file,
        // a shim relativizer that assumed symlinks and met scripts, a binary
        // sniffer reading only the first 8 KB.
        //
        // Walking to the filesystem root is affordable — this runs once per
        // PATH entry when a launch environment is built, and a typical entry is
        // a handful of levels deep — and it cannot be wrong about a shape
        // nobody thought of. Recognition stays strict (looksLikeStoreRoot wants
        // a descriptor, or the installed/ + skills/ pair), so an unrelated
        // ancestor is not mistaken for a home; and if some ancestor really does
        // carry a store's layout, treating its bin directories as foreign is
        // the correct answer, not a false positive.
        for (Path parent = entry.getParent(); parent != null; parent = parent.getParent()) {
            if (looksLikeStoreRoot(parent)) {
                return activeStoreRoot == null || !parent.equals(activeStoreRoot);
            }
        }
        return false;
    }

    private static boolean looksLikeStoreRoot(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        if (Files.isRegularFile(dir.resolve(HomeDescriptor.FILENAME))) return true;
        return Files.isDirectory(dir.resolve("installed")) && Files.isDirectory(dir.resolve("skills"));
    }

    // ---------------------------------------------------------------- gate

    /**
     * Where this launch environment would put Claude's config directory,
     * resolved by {@link AgentHomes#claude(Map)} so the launcher and the
     * reader cannot disagree.
     */
    public AgentHomes.ClaudeHome claudeHome() {
        return AgentHomes.claude(env);
    }

    /**
     * Refuse a launch whose Claude config directory is not inside this home.
     *
     * <p>The condition is "inside the home root", not "not equal to
     * {@code ~/.claude}": for the global home at {@code ~/.skill-manager} the
     * home root <em>is</em> the user's home and {@code ~/.claude} is the
     * correct answer. What must never happen is a launch against an isolated
     * home that leaves Claude reading a config directory somewhere else — which
     * is what {@link AgentHomes#claude(Map)} returns whenever the env block
     * carries neither {@code CLAUDE_CONFIG_DIR} nor {@code CLAUDE_HOME},
     * because its last fallback is {@code user.home}.
     *
     * @throws UnredirectedLaunchException before any child process is created
     */
    public void requireClaudeRedirected() throws UnredirectedLaunchException {
        Path configDir = claudeHome().configDir().toAbsolutePath().normalize();
        if (configDir.startsWith(homeRoot)) return;
        String declared = env.get(AgentHomes.CLAUDE_CONFIG_DIR);
        throw new UnredirectedLaunchException(homeRoot, configDir,
                "refusing to launch: " + AgentHomes.CLAUDE_CONFIG_DIR + " resolves to "
                        + configDir + ", which is outside the home at " + homeRoot
                        + (declared == null
                            ? " (the home's env block declares neither "
                                    + AgentHomes.CLAUDE_CONFIG_DIR + " nor "
                                    + AgentHomes.CLAUDE_HOME + ")"
                            : " (declared as " + declared + ")")
                        + ". Skills load from that directory, so the launch would read and write "
                        + "the wrong home. Repair it with `skill-manager home describe --home "
                        + store.root() + " --write`, or drop the "
                        + AgentHomes.CLAUDE_CONFIG_DIR + " entry from the descriptor's "
                        + "envContributions.");
    }

    // ----------------------------------------------------------- resolution

    /**
     * Locate {@code command} on the effective {@code PATH}.
     *
     * <p>A command containing a path separator is used as given, so a caller
     * can name an exact binary. Otherwise the effective {@code PATH} is
     * searched in order, <b>skipping generated launcher directories</b>: the
     * shims in {@code bin/launch} exist to route back into this very code
     * path, so resolving {@code claude} to the shim would make a launch call
     * itself forever.
     *
     * @return the executable, or empty when the effective PATH has no such command
     */
    public Optional<Path> resolveBinary(String command) {
        if (command == null || command.isBlank()) return Optional.empty();
        if (command.contains("/") || command.contains(File.separator)) {
            Path direct = Path.of(command).toAbsolutePath().normalize();
            return Files.isExecutable(direct) && !Files.isDirectory(direct)
                    ? Optional.of(direct)
                    : Optional.empty();
        }
        for (Path dir : pathEntries) {
            if (isLauncherDir(dir)) continue;
            Path candidate = dir.resolve(command);
            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    /** True for any {@code .../bin/launch} directory, in this home or another. */
    public static boolean isLauncherDir(Path dir) {
        if (dir == null) return false;
        Path name = dir.getFileName();
        Path parent = dir.getParent();
        Path parentName = parent == null ? null : parent.getFileName();
        return name != null && LAUNCHER_DIR.equals(name.toString())
                && parentName != null && "bin".equals(parentName.toString());
    }
}
