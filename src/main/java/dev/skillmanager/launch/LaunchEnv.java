package dev.skillmanager.launch;

import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.commands.HomeCommand;
import dev.skillmanager.pm.PackageCaches;
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
        // The shared package stores, first and therefore lowest precedence:
        // a descriptor that names one of these explicitly is the operator
        // speaking and wins. Declared here rather than left to inherit
        // because "it happens to be inherited today" is exactly the property
        // that regressed when a per-worktree layer started redirecting
        // UV_CACHE_DIR and nothing noticed. See PackageCaches for which
        // directories are shareable and which are not.
        assembled.putAll(PackageCaches.sharedEnv(store.venvsDir()));
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
     * True when {@code entry} is an executable directory belonging to
     * <em>any</em> Skill Manager home, this one included.
     *
     * <p>The same structural question as {@link #isForeignHomeBin}, asked with
     * no incumbent to exempt. {@code CliPresence} needs exactly this one: it
     * asks PATH only about tools the operating system provides, because "does
     * THIS home have it" is answered precisely, from this home's
     * {@code bin/cli}, and must not be answered a second and vaguer time by a
     * PATH entry that happens to be some home's.
     *
     * <p>Named rather than spelled {@code isForeignHomeBin(entry, null)} at the
     * call site: "foreign" with nothing to be foreign to reads as a pun, and
     * the next reader has to go and check which way the null falls.
     */
    public static boolean isAnyHomeBin(Path entry) {
        return isForeignHomeBin(entry, null);
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
        // A home is its store AND its agent directories, and this walk only
        // ever looked at the store half. `~/.claude` is not a store root — no
        // descriptor, no installed/ + skills/ pair — so
        // `~/.claude/plugins/cache/<marketplace>/<plugin>/<v>/bin` was invisible
        // to the loop above and survived every launch, measured at PATH
        // position 4, ahead of /usr/bin, with the foreign STORE bin beside it
        // correctly stripped. Exactly the failure the comment above names, one
        // predicate over: the structural test for the other half already exists
        // as agentDirOwnedByAHome (#145) and simply was not called here.
        Path agentDir = agentDirOwnedByAHome(entry);
        if (agentDir != null) {
            Path owner = agentDir.getParent();
            if (owner == null) return false;
            Path foreignStore = owner.resolve(AgentHomes.STORE_DIR_NAME)
                    .toAbsolutePath().normalize();
            return activeStoreRoot == null || !foreignStore.equals(activeStoreRoot);
        }
        return false;
    }

    /**
     * Whether {@code dir} is a Skill Manager home, decided by layout rather
     * than by name.
     *
     * <p>Public and shared rather than private and re-spelled. This started as
     * the PATH sanitizer's private test for "is some ancestor of this bin
     * directory a foreign home", and it is the same question {@code home sync}
     * and {@code home close-out} have to answer about the paths they are handed
     * — a question they used not to ask at all, which is how {@code home
     * close-out --home <the worktree directory>} (rather than
     * {@code <worktree>/.skill-manager}) came to exit 0 with
     * {@code "blockers": []} while naming the directory holding the only copy
     * of an agent's edit. Three spellings of "is this a home" would have
     * disagreed about exactly the homes that matter; there is one.
     *
     * <p>A name test would be wrong here: a per-project home is routinely
     * cloned to a differently named directory, and the descriptor or the
     * {@code installed/} + {@code skills/} pair is what every home has.
     */
    public static boolean looksLikeStoreRoot(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        if (Files.isRegularFile(dir.resolve(HomeDescriptor.FILENAME))) return true;
        return Files.isDirectory(dir.resolve("installed")) && Files.isDirectory(dir.resolve("skills"));
    }

    /**
     * The agent directory <em>owned by some Skill Manager home</em> that
     * contains {@code path}, or null when {@code path} is not inside one.
     *
     * <p>The companion to {@link #looksLikeStoreRoot} for the other half of a
     * home. {@code ~/.claude} is not a store root — no {@code installed/}, no
     * {@code skills/} pair, no descriptor — so every check built on that
     * predicate was blind to the directory agents actually load skills from.
     * Issue #145 is what that blindness cost: {@code home clone} reported "no
     * path in it reaches any other Skill Manager home" while the copy held live
     * instructions to delete files in the source's {@code ~/.claude}, and an
     * {@code uninstall} in the copy carried three of them out and exited 0.
     *
     * <p>The predicate is structural and both halves are load-bearing. A
     * directory qualifies when it is named {@code .claude}, {@code .codex} or
     * {@code .gemini} <b>and</b> its parent holds a Skill Manager store.
     * Without the first half this would match anything; without the second it
     * would match every {@code .claude} on the machine, including the many no
     * home manages — and a rule that fires on those is a rule somebody
     * switches off.
     *
     * <p>It returns the directory rather than a boolean because every caller
     * has to name it: a refusal that cannot say which directory it is
     * protecting is a refusal the operator cannot act on.
     */
    public static Path agentDirOwnedByAHome(Path path) {
        if (path == null) return null;
        Path abs = path.toAbsolutePath().normalize();
        for (Path parent = abs; parent != null; parent = parent.getParent()) {
            Path name = parent.getFileName();
            if (name == null) continue;
            String segment = name.toString();
            if (!AgentHomes.CLAUDE_DIR_NAME.equals(segment)
                    && !".codex".equals(segment) && !".gemini".equals(segment)) {
                continue;
            }
            Path owner = parent.getParent();
            if (owner == null) continue;
            if (looksLikeStoreRoot(owner.resolve(AgentHomes.STORE_DIR_NAME))) return parent;
        }
        return null;
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
