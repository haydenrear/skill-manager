//SOURCES ../home-sync/HomeSyncSupport.java

import com.hayden.testgraphsdk.sdk.NodeContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The home tripwire: a snapshot/diff oracle over the OPERATOR'S REAL agent
 * homes, and the executable witness for two properties that
 * {@code specs/desired_program_model/spec_manifest.yaml} states but that
 * nothing in the graph previously checked:
 *
 * <ul>
 *   <li>{@code WritesThroughOneHomeReachNoOtherHome} — a write through one home
 *       lands in that home and in no other, "not a sibling project's, not the
 *       home it was copied from", and above all not the operator's.</li>
 *   <li>{@code SourceHomeIsByteIdenticalToItsCloneTimeSelf} — the source home is
 *       read once and still holds exactly the bytes it held then.</li>
 * </ul>
 *
 * <h2>Why this exists as a node and not as a shell script</h2>
 *
 * It began as two scratchpad scripts run by hand around a batch of work, and it
 * caught four leak paths that no assertion did — including issue #18, where a
 * projection reached the real {@code ~/.claude} and appeared in a live agent's
 * available-skills list. An oracle that only fires when someone remembers to run
 * it is not a regression guard. Every fact those scripts checked is a named
 * assertion here.
 *
 * <h2>One mechanism, two fidelities</h2>
 *
 * The two scripts were the same idea at different resolutions, so they are one
 * mechanism here rather than a third and fourth idiom (issue #24):
 *
 * <ul>
 *   <li>{@link Fidelity#METADATA} — kind, path, size, mtime and symlink target
 *       across all four roots. Cheap enough to bracket every run.</li>
 *   <li>{@link Fidelity#CONTENT} — SHA-256 of every file's bytes, scoped to the
 *       content and ledger surfaces of {@code ~/.skill-manager}. "Byte-identical"
 *       has to be an assertion about content; metadata cannot express it.</li>
 * </ul>
 *
 * METADATA also records symlink targets, which the shell version did not. The
 * #18 residue was dangling symlinks into deleted temp directories; a retargeted
 * link is the exact shape of that defect, so the target belongs in the cheap
 * fidelity rather than only in the expensive one.
 *
 * <h2>{@code .git} is watched at a third granularity, and that is the point</h2>
 *
 * Neither fidelity walks a {@code .git} directory, and neither one skips it. It
 * used to be skipped outright, which made every write into a {@code .git} inside
 * a watched home invisible — issue #47, where {@code home close-out} registered
 * a worktree in the operator's home and the tripwire ran seventeen minutes later
 * and reported all four assertions passing. It contributes a registration
 * summary instead: see {@link #gitRegistrations}. Ordinary git churn moves none
 * of those lines; a {@code git worktree add} or {@code remove} moves both.
 *
 * <h2>Scope is declared, not implied</h2>
 *
 * CONTENT covers {@link #CONTENT_SURFACES} and not {@code venvs/}, {@code tools/},
 * {@code npm/} or {@code cache/}. That is a principled cut, not a performance
 * one: those are provisioned toolchain roots that {@code home clone} deliberately
 * does not carry ({@code ToolchainRootsAreNeverShared}), so their bytes are not
 * something any home promises to preserve. The surfaces that ARE covered are
 * exactly the ones {@code AuthoredContentIsNeverRewritten} and the binding ledger
 * invariants speak about. Both scopes are published as node metrics so a reader
 * never has to infer what a clean report covered.
 *
 * <h2>The tree walk watches what skill-manager WRITES, not the whole agent home</h2>
 *
 * <p>The walk used to emit a line for every entry under all four roots, and it
 * went red on machines where anything was running: a live {@code claude} or
 * {@code codex} session writes {@code .claude/sessions/<pid>.json},
 * {@code .claude/plugins/**}{@code /.in_use/<pid>}, {@code .codex/tmp/path/*}
 * (634 per-invocation shims on this machine), {@code .claude/debug/latest},
 * rotating sqlite WALs and a dozen counters, on its own schedule. Each new
 * agent release added another one, and each was silenced by name — which is the
 * enumerate-and-miss-one shape this project keeps paying for, applied to the
 * silencer instead of the check.
 *
 * <p>So the scope is stated the other way round, from the product: an agent home
 * has exactly THREE surfaces skill-manager writes, and they are declared by
 * {@code dev.skillmanager.agent.Agent} itself — {@code skillsDir()},
 * {@code pluginsDir()} and {@code mcpConfigPath()}. The tree walk covers the
 * first ({@link #AGENT_SURFACES}), which is a pure projection; the other two are
 * covered by {@link #ownedConfig}, which reads the registration out of them and
 * leaves the harness's own materialization and timestamps alone. Under
 * {@code ~/.skill-manager} the walk still covers EVERYTHING, because
 * skill-manager is the only thing that writes there and any movement in it is a
 * fact about who is writing.
 *
 * <p><b>This is a narrowing, so it is proved rather than asserted.</b> The
 * incident it must not be able to hide is a documented remedy that repointed 24
 * of the operator's {@code ~/.claude/skills/<unit>} links at a foreign store.
 * That is a symlink retarget inside {@code skillsDir()} — the first watched
 * surface — and {@code home.tripwire.sensitive} M4 plus the planted-link
 * mutation in both {@code *.global.home.untouched} nodes fire on exactly it.
 */
final class TripwireSupport {

    /** How closely a snapshot looks. */
    enum Fidelity { METADATA, CONTENT }

    /** The four roots a skill-manager write could escape into. */
    static final List<String> ROOTS = List.of(".skill-manager", ".claude", ".codex", ".gemini");

    /** The store root, watched in full: nothing but skill-manager writes there. */
    static final String STORE_ROOT = ".skill-manager";

    /** The three agent homes, whose watched surface is narrower than the root. */
    static final List<String> AGENT_ROOTS = List.of(".claude", ".codex", ".gemini");

    /**
     * The part of an agent home the TREE WALK covers.
     *
     * <p>{@code dev.skillmanager.agent.Agent} declares three surfaces —
     * {@code skillsDir()}, {@code pluginsDir()}, {@code mcpConfigPath()} — and
     * this is the one of them that is a pure projection: skill-manager creates
     * the links under {@code <agent>/skills}, and nothing else writes there, so
     * every byte and every link target in it is attributable.
     *
     * <p>The other two are watched by {@link #ownedConfig} instead, and that is
     * a statement about who OWNS the bytes rather than a convenience.
     * {@code <agent>/plugins} is a materialization the harness manages —
     * {@code cache/} payloads it re-extracts, {@code marketplaces/} clones it
     * re-pulls, {@code .in_use/<pid>} liveness markers it drops and sweeps, and
     * {@code lastUpdated} stamps it rewrites on refresh. What skill-manager
     * contributes to it is a REGISTRATION: that its marketplace is known, and
     * which plugins were installed from it. A registration is a fact about
     * content, so it is read as content with the harness's timestamps left out,
     * rather than watched as mtimes that move whenever the harness runs.
     */
    static final List<String> AGENT_SURFACES = List.of("skills");

    /**
     * Surfaces of {@code ~/.skill-manager} whose BYTES are covered by
     * {@link Fidelity#CONTENT}. Toolchain roots are deliberately absent; see the
     * class comment.
     */
    static final List<String> CONTENT_SURFACES =
            List.of("skills", "plugins", "docs", "harnesses", "installed");

    private TripwireSupport() {}

    // ------------------------------------------------------------ real home

    /**
     * The operator's real home, or an exception.
     *
     * <p>The guard is the point. A tripwire that a stray {@code HOME} export can
     * redirect into the sandbox it is supposed to be watching reports CLEAN
     * forever — the vacuous-check failure mode, arrived at by configuration. The
     * self-test deliberately watches a decoy instead, and it does that by calling
     * {@link #collect} on a path directly rather than by overriding anything
     * here, so there is no bypass to leave switched on by accident.
     */
    static Path realHome() {
        String raw = System.getProperty("user.home");
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("tripwire: user.home is not set");
        }
        Path home = Path.of(raw);
        String s = home.toString();
        if (!(s.startsWith("/Users/") || s.startsWith("/home/"))) {
            throw new IllegalStateException("tripwire: refusing suspicious user.home=" + s);
        }
        if (s.contains("claude-501") || s.contains("scratchpad") || s.contains("/tmp/")) {
            throw new IllegalStateException("tripwire: user.home looks like a sandbox: " + s);
        }
        return home;
    }

    /** The roots that exist, in declaration order. */
    static List<Path> presentRoots(Path home) {
        List<Path> out = new ArrayList<>();
        for (String name : ROOTS) {
            Path root = home.resolve(name);
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) out.add(root);
        }
        return out;
    }

    // ------------------------------------------------------------- pruning

    /**
     * Whether {@code path} is inside the surface this tripwire CLAIMS to watch.
     *
     * <p>Two rules, and the asymmetry between them is the whole design:
     *
     * <ul>
     *   <li>{@code ~/.skill-manager} — <b>everything</b>. Skill-manager is the
     *       only thing that writes there, so any line that moves is a fact about
     *       who is writing, and the tripwire wants all of them.</li>
     *   <li>{@code ~/.claude}, {@code ~/.codex}, {@code ~/.gemini} — only
     *       {@link #AGENT_SURFACES}. The rest of an agent home is that agent's
     *       own state, which it rewrites constantly and which skill-manager
     *       never touches; watching it made the oracle fire on the runtime of
     *       the very session running it, and the fix of naming each new state
     *       directory as it appeared was a denylist racing an agent's release
     *       notes.</li>
     * </ul>
     *
     * <p>The narrowing is deliberately blind to nothing skill-manager writes.
     * The incident this oracle exists for — a documented remedy repointing 24 of
     * the operator's {@code ~/.claude/skills/<unit>} links — is a retarget
     * inside the first rule's agent-home surface, and it is planted and asserted
     * detected in both {@code *.global.home.untouched} nodes.
     */
    private static boolean watched(Path home, Path path) {
        if (path.startsWith(home.resolve(STORE_ROOT))) return true;
        for (String agent : AGENT_ROOTS) {
            Path root = home.resolve(agent);
            for (String surface : AGENT_SURFACES) {
                if (path.startsWith(root.resolve(surface))) return true;
            }
        }
        return false;
    }

    /** Whether {@code path} is one of the four roots itself. */
    private static boolean declaredRoot(Path home, Path path) {
        for (String name : ROOTS) {
            if (path.equals(home.resolve(name))) return true;
        }
        return false;
    }

    /**
     * Derived state that is churned by whatever BUILT it, inside a surface that
     * is otherwise watched in full.
     *
     * <p>Not the same rule as {@link #watched}. That one says which surfaces
     * belong to skill-manager; this one says which entries inside them are
     * nobody's authored content — the shape {@code __pycache__} was already
     * pruned for, extended to the two that were measured firing:
     *
     * <ul>
     *   <li><b>{@code .gradle/}</b> — a unit in the store may itself be a Gradle
     *       project ({@code skills/test-graph} is), and any build of it, from any
     *       worktree on the machine, rewrites
     *       {@code .gradle/**}{@code /*.lock} and {@code outputFiles.bin} in the
     *       operator's store. Measured: three such lines, with an empty content
     *       diff and no leak, on a run whose every substantive oracle passed.
     *       It is the {@code .git}-churn case of issue #47 with a different
     *       build tool, and {@code AuthoredContentIsNeverRewritten} does not
     *       speak about derived output.</li>
     *   <li><b>sqlite databases and their WAL/SHM sidecars</b> — generalised
     *       from the two name-prefixed rules that were here ({@code logs*},
     *       {@code state*}) because {@code .codex/goals_1.sqlite-wal} and
     *       {@code memories_1.sqlite-shm} moved and neither prefix caught them.
     *       Nothing skill-manager writes anywhere is a sqlite file, so the
     *       general rule is the honest one and the prefixes were an
     *       accident of which database happened to be open that day.</li>
     * </ul>
     *
     * <p>The remaining entries are the agent-session state the old broad walk
     * needed pruned. They are kept — they are still correct, and
     * {@code ~/.claude/projects} is 833 MB of transcripts that there is no
     * reason to walk — but they are no longer load-bearing: {@link #watched}
     * already excludes every one of them.
     */
    private static boolean pruned(Path home, Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        if (name.equals("__pycache__") || name.equals(".gradle")) return true;
        if (name.contains(".sqlite")) return true;
        if (name.equals("models_cache.json") || name.equals("history.jsonl")) return true;
        if (name.equals("cache") && path.getParent() != null
                && ROOTS.contains(path.getParent().getFileName().toString())) {
            return true;
        }
        Path claude = home.resolve(".claude");
        for (String churn : new String[] {
                "projects", "todos", "statsig", "shell-snapshots",
                "file-history", "paste-history", "logs", "tasks", "backups" }) {
            if (path.equals(claude.resolve(churn))) return true;
        }
        Path codex = home.resolve(".codex");
        return path.equals(codex.resolve("sessions")) || path.equals(codex.resolve("archived_sessions"));
    }

    // --------------------------------------------------------- git, coarsely

    /** Marks a line describing a {@code .git} directory rather than its contents. */
    static final String GIT_MARK = "G";

    /**
     * What a {@code .git} directory contributes to a snapshot: its WORKTREE
     * REGISTRATIONS, and nothing else.
     *
     * <h2>Why a special case rather than a prune or a walk</h2>
     *
     * <p>{@code .git} used to be in {@link #pruned}, alongside session
     * transcripts and sqlite WALs, for a real reason: every git command rewrites
     * {@code index}, {@code logs/HEAD}, {@code ORIG_HEAD} and a fresh loose
     * object or two, so a file-level walk of it fires on a {@code git status}.
     * The cost of that prune was that EVERY write into a {@code .git} directory
     * inside a watched home was invisible — and issue #47 is exactly that write:
     * {@code home close-out} registered a worktree at
     * {@code ~/.skill-manager/skills/test-graph/.git/worktrees/test-graph-5747232},
     * a home that was neither {@code --home} nor {@code --into}, and the tripwire
     * ran seventeen minutes later and reported all four assertions passing.
     *
     * <p>Walking it at file level is not the answer either; that is the churn
     * the prune existed to silence, and an oracle that cries wolf gets switched
     * off. So the resolution is neither prune nor walk but a THIRD granularity:
     * one line naming the {@code .git} and its registration COUNT, plus one line
     * per registration. Ordinary git bookkeeping — commits, fetches, index
     * rewrites, new loose objects, moved refs — changes none of those lines.
     * {@code git worktree add} changes both, and {@code git worktree remove} or
     * {@code prune} changes them the other way, so a registration that appears
     * and one that vanishes are equally visible.
     *
     * <p>The count line is emitted even when there are zero registrations. That
     * is the non-vacuity half: without it, a {@code .git} the walk never reached
     * and a {@code .git} with no worktrees would both contribute nothing, and
     * "no new registration appeared" would be indistinguishable from "nothing
     * looked".
     */
    private static void gitRegistrations(Path base, Path gitPath, List<String> out) {
        String rel = base.equals(gitPath) ? "." : base.relativize(gitPath).toString();
        if (!Files.isDirectory(gitPath, LinkOption.NOFOLLOW_LINKS)) {
            // A `.git` FILE is the gitdir pointer a linked worktree carries. Its
            // presence at a path that had none is itself the finding.
            out.add(GIT_MARK + "\t" + rel + "\tgitfile");
            return;
        }
        Path worktrees = gitPath.resolve("worktrees");
        if (!Files.isDirectory(worktrees, LinkOption.NOFOLLOW_LINKS)) {
            out.add(GIT_MARK + "\t" + rel + "\tworktrees=0");
            return;
        }
        List<Path> registered;
        try (var entries = Files.list(worktrees)) {
            registered = entries.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            // NOT "worktrees=0". A directory that could not be listed is the
            // §7.4 shape exactly — a zero meaning "could not look" reported as
            // "looked and found nothing" — and this one is load-bearing, because
            // "no new registration appeared" would then be true of a home whose
            // registrations had become unreadable. It is recorded as its own
            // value, so the state is visible in the snapshot AND a transition
            // into or out of it is a difference like any other.
            out.add(GIT_MARK + "\t" + rel + "\tworktrees=unreadable:" + e.getClass().getSimpleName());
            return;
        }
        out.add(GIT_MARK + "\t" + rel + "\tworktrees=" + registered.size());
        for (Path entry : registered) {
            out.add(GIT_MARK + "\t" + rel + "/worktrees/" + entry.getFileName());
        }
    }

    /**
     * The worktree registrations that CHANGED between the two sides of a
     * {@link #difference} — appeared, marked {@code +}, and vanished, marked
     * {@code -}.
     *
     * <p>Both directions, and that is a correction rather than generosity. The
     * first version of this filtered {@code +} only, which made the assertion it
     * feeds narrower than its own name and narrower than the claim made for it:
     * {@code git worktree remove} and {@code git worktree prune} are writes into
     * the watched home too, and a residue quietly disappearing from the
     * operator's home is exactly as much a fact about who is writing there as
     * one appearing. The broad metadata diff saw both all along; the named
     * assertion saw one.
     *
     * <p>Narrower than the whole metadata diff on purpose, and for the reason
     * {@code HomeTripwireChecked} keeps a narrow assertion beside a broad one: a
     * concurrent agent session moves metadata lines all the time, but nothing on
     * this machine registers or unregisters a git worktree inside the operator's
     * home by accident. This one is attributable.
     */
    static List<String> worktreeRegistrationChanges(List<String> diff) {
        List<String> out = new ArrayList<>();
        for (String line : diff) {
            if (!line.contains("/worktrees/")) continue;
            if (line.startsWith("+" + GIT_MARK + "\t") || line.startsWith("-" + GIT_MARK + "\t")) {
                out.add(line);
            }
        }
        return out;
    }

    /** How many {@code .git} directories a snapshot actually looked at. */
    static int gitDirectoriesWatched(List<String> snapshot) {
        int n = 0;
        for (String line : snapshot) {
            if (line.startsWith(GIT_MARK + "\t") && line.contains("\tworktrees=")) n++;
        }
        return n;
    }

    // ------------------------------------------------------------ collection

    /**
     * A sorted, stable description of {@code root} at the requested fidelity.
     *
     * <p>Paths are framed relative to {@code root}'s PARENT, so a line names
     * {@code .claude/skills/foo} rather than an absolute path. That keeps a
     * baseline comparable across the decoy used by the self-test and the real
     * home used in anger — the self-test would otherwise be proving the checker
     * works on a string shape the real nodes never produce.
     */
    static List<String> collect(Path root, Path home, Fidelity fidelity) throws IOException {
        List<String> out = new ArrayList<>();
        Path base = root.getParent() == null ? root : root.getParent();
        walk(base, root, home, fidelity, out);
        out.sort(String::compareTo);
        return out;
    }

    /** {@link #collect} over several roots, concatenated and sorted as one. */
    static List<String> collectAll(List<Path> roots, Path home, Fidelity fidelity) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path root : roots) out.addAll(collect(root, home, fidelity));
        out.sort(String::compareTo);
        return out;
    }

    private static void walk(Path base, Path current, Path home, Fidelity fidelity, List<String> out)
            throws IOException {
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
        if (pruned(home, current)) return;
        String rel = base.equals(current) ? "." : base.relativize(current).toString();
        // A path outside the declared surface contributes NO line, and the walk
        // still descends through it — `~/.claude` itself is not watched but
        // `~/.claude/skills` is, and the only way to reach the second is through
        // the first. Descending-but-silent rather than pruning is what keeps the
        // scope one predicate instead of a prune list that has to know the shape
        // of every path leading to a watched one.
        //
        // The one exception is a DECLARED ROOT, which always contributes its own
        // line even when nothing inside it is watched. That is the non-vacuity
        // floor, the same one `worktrees=0` is: without it, an agent home that
        // exists but holds no projection and an agent home the walk never
        // reached would both contribute nothing, and "the root is covered" would
        // be indistinguishable from "the root was never looked at".
        boolean report = watched(home, current) || declaredRoot(home, current);

        if (Files.isSymbolicLink(current)) {
            // Never recurse through a link: a link into a large tree would make
            // the walk quadratic, and the target string is the fact that matters.
            if (report) out.add("L\t" + rel + "\t" + Files.readSymbolicLink(current));
            return;
        }
        // Coarse, at BOTH fidelities: a `.git` inside a content surface is
        // hashed no more deeply than one outside it, because the question asked
        // of it is the same question. See gitRegistrations.
        if (".git".equals(current.getFileName() == null ? "" : current.getFileName().toString())) {
            if (report) gitRegistrations(base, current, out);
            return;
        }
        if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            if (report) out.add("D\t" + rel);
            for (Path child : listSorted(current)) walk(base, child, home, fidelity, out);
            return;
        }
        if (!report) return;
        if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) return;

        if (fidelity == Fidelity.CONTENT) {
            out.add("F\t" + rel + "\t" + sha256(current));
            return;
        }
        BasicFileAttributes attrs =
                Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        out.add("F\t" + rel + "\t" + attrs.size() + "\t" + attrs.lastModifiedTime().toMillis());
    }

    private static String sha256(Path file) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            return "sha256-unavailable";
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
        } catch (IOException e) {
            // An unreadable file is a fact worth recording, not a reason to
            // abort the sweep: the next run comparing "unreadable" to a hash is
            // itself a change the checker should surface.
            return "unreadable:" + e.getClass().getSimpleName();
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static List<Path> listSorted(Path dir) {
        List<Path> children = new ArrayList<>();
        try (var entries = Files.list(dir)) {
            entries.forEach(children::add);
        } catch (IOException e) {
            return children;
        }
        children.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return children;
    }

    // ----------------------------------------------------------- comparison

    /**
     * Lines present on one side only, marked {@code -} (was there, now gone or
     * changed) and {@code +} (appeared or changed). Both sides are sorted, so
     * this is a set difference, not a positional diff.
     */
    static List<String> difference(List<String> before, List<String> after) {
        Set<String> b = new java.util.LinkedHashSet<>(before);
        Set<String> a = new java.util.LinkedHashSet<>(after);
        List<String> out = new ArrayList<>();
        for (String line : before) if (!a.contains(line)) out.add("-" + line);
        for (String line : after) if (!b.contains(line)) out.add("+" + line);
        return out;
    }

    // -------------------------------------------------- the owned config half

    /**
     * The registration blocks skill-manager owns inside the agent CONFIG files,
     * as {@code <label>\t<sha256>} lines.
     *
     * <h2>Why this side exists at all</h2>
     *
     * <p>{@code ~/.claude.json} is a SIBLING of the four watched roots, not a
     * child of one, so no tree walk rooted at them reaches it — and it is
     * exactly the file this product writes Claude MCP registrations into. A
     * hand-run eval's first isolation filter excluded it BY NAME and would have
     * missed a write to it.
     *
     * <h2>Why it is BLOCKS and not whole files</h2>
     *
     * <p>The specification asked for the {@code mcpServers} /
     * {@code extraKnownMarketplaces} blocks. Whole-file hashing was a later
     * implementer's choice, on the argument that "a parser bug can only make the
     * check weaker", and it made the oracle flaky in a way that is worse than
     * weaker: a live agent session rewrites {@code promptQueueUseCount},
     * {@code lastCost}, {@code lastSessionId} and a dozen other counters in
     * these files while the graph runs, and an oracle that goes red for reasons
     * nobody caused gets switched off — after which it is not weaker, it is
     * absent. The parser argument is answered instead by PLANTS: the two
     * {@code *.global.home.untouched} nodes mutate a decoy config through every
     * shape a registration can take and assert each one is detected, alongside a
     * churn key they assert is NOT.
     *
     * <h2>What is covered</h2>
     *
     * <ul>
     *   <li>{@code ~/.claude.json} — top-level {@code mcpServers} and
     *       {@code extraKnownMarketplaces}, and the per-project MCP keys inside
     *       {@code projects}. A project entry is session bookkeeping plus a
     *       project-scoped MCP registration; only the second is a surface
     *       anything can project into, and leaving it out would let a
     *       {@code --scope project} write through.</li>
     *   <li>{@code ~/.codex/config.toml} — the {@code [mcp_servers…]} tables.</li>
     *   <li>{@code ~/.gemini/settings.json} — {@code mcpServers}.</li>
     *   <li>{@code ~/.claude/plugins/known_marketplaces.json} and
     *       {@code installed_plugins.json} — the marketplace and plugin
     *       registrations {@code claude plugin marketplace add} writes on
     *       skill-manager's behalf. They live in a directory the tree walk no
     *       longer covers, because the harness re-materialises the rest of it;
     *       the registration itself is a fact about content and is read here.</li>
     *   <li>{@code ~/.claude/settings.json} — whole file. Not a registration
     *       surface and not written by this product, which is the point: it is
     *       the operator's own configuration and nothing should be rewriting
     *       it.</li>
     * </ul>
     *
     * <p>A file that is absent is {@code ABSENT} and a file that is present and
     * cannot be read is {@code UNPARSEABLE}. Distinct values, both stable, and a
     * transition into or out of either is a difference like any other — never a
     * zero that means "could not look" reported as "looked and found nothing".
     */
    static List<String> ownedConfig(Path home) {
        List<String> out = new ArrayList<>();
        Object claudeJson = readJson(home.resolve(".claude.json"));
        out.add(block("/.claude.json#mcpServers", pick(claudeJson, "mcpServers")));
        out.add(block("/.claude.json#extraKnownMarketplaces",
                pick(claudeJson, "extraKnownMarketplaces")));
        out.add(block("/.claude.json#projects.mcp", projectMcp(claudeJson)));

        out.add(block("/.codex/config.toml#mcp_servers",
                tomlTables(home.resolve(".codex/config.toml"), "mcp_servers")));
        out.add(block("/.gemini/settings.json#mcpServers",
                pick(readJson(home.resolve(".gemini/settings.json")), "mcpServers")));

        out.add(block("/.claude/plugins/known_marketplaces.json",
                readJson(home.resolve(".claude/plugins/known_marketplaces.json"))));
        out.add(block("/.claude/plugins/installed_plugins.json",
                readJson(home.resolve(".claude/plugins/installed_plugins.json"))));

        Path settings = home.resolve(".claude/settings.json");
        out.add("/.claude/settings.json\t"
                + (Files.isRegularFile(settings) ? sha256(settings) : "ABSENT"));
        return out;
    }

    /** The label a run's config fingerprint uses for the sibling Claude config. */
    static final String CLAUDE_JSON_LABEL = "/.claude.json#";

    /** Markers for a file that is not there, and one that is but cannot be read. */
    private static final String ABSENT = "ABSENT";
    private static final String UNPARSEABLE = "UNPARSEABLE";

    /**
     * Values the agents stamp on their own registrations.
     *
     * <p>Matched by SHAPE rather than by key name, because the names differ per
     * file ({@code lastUpdated}, {@code installedAt}, {@code fetchedAt}) and a
     * list of them is the enumerate-and-miss-one trap again. Nothing
     * skill-manager registers anywhere IS an ISO instant, so dropping every
     * value that looks like one costs no coverage and survives the next field
     * the harness decides to stamp.
     */
    private static final Pattern ISO_INSTANT =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");

    private static String block(String label, Object value) {
        if (value == ABSENT || value == UNPARSEABLE) return label + "\t" + value;
        return label + "\t" + sha256Of(canon(value));
    }

    /** One key of a parsed object, preserving the ABSENT/UNPARSEABLE markers. */
    private static Object pick(Object root, String key) {
        if (root == ABSENT || root == UNPARSEABLE) return root;
        if (!(root instanceof Map<?, ?> map)) return UNPARSEABLE;
        return map.get(key);
    }

    /**
     * Every project entry's MCP keys, and nothing else that lives beside them.
     *
     * <p>A project with none of them contributes NOTHING, rather than an empty
     * entry under its path. That is the difference between watching a
     * registration and watching a directory listing: {@code claude} adds a
     * {@code projects["/some/dir"]} entry the first time a session runs anywhere,
     * and an entry keyed by path alone would make the fingerprint move every
     * time the operator opened a new repository. What is watched is which
     * projects have an MCP registration and what it says.
     */
    private static Object projectMcp(Object root) {
        if (root == ABSENT || root == UNPARSEABLE) return root;
        if (!(root instanceof Map<?, ?> map)) return UNPARSEABLE;
        Map<String, Object> out = new TreeMap<>();
        Object projects = map.get("projects");
        if (projects == null) return out;
        if (!(projects instanceof Map<?, ?> byPath)) return UNPARSEABLE;
        for (Map.Entry<?, ?> entry : byPath.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> project)) continue;
            Map<String, Object> mcp = new TreeMap<>();
            for (String key : List.of("mcpServers", "enabledMcpjsonServers",
                    "disabledMcpjsonServers", "mcpContextUris")) {
                if (project.containsKey(key)) mcp.put(key, project.get(key));
            }
            if (!mcp.isEmpty()) out.put(String.valueOf(entry.getKey()), mcp);
        }
        return out;
    }

    /** A parsed JSON file, or {@link #ABSENT} / {@link #UNPARSEABLE}. */
    private static Object readJson(Path file) {
        if (!Files.isRegularFile(file)) return ABSENT;
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            return UNPARSEABLE;
        }
        Object parsed = HomeSyncSupport.MiniJson.parse(text);
        return parsed == null ? UNPARSEABLE : parsed;
    }

    /**
     * The lines of every TOML table whose name is {@code prefix} or starts with
     * {@code prefix.}, headers included, blanks and comments dropped.
     *
     * <p>Line-scoped and deliberately coarse: a line it fails to attribute lands
     * inside or outside the block wholesale, never silently half-in. The
     * alternative was a TOML parser in a jbang node, and the mutation plants
     * below are what would catch either one going blind.
     */
    private static Object tomlTables(Path file, String prefix) {
        if (!Files.isRegularFile(file)) return ABSENT;
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return UNPARSEABLE;
        }
        List<Object> kept = new ArrayList<>();
        boolean inside = false;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                String name = line.replaceAll("^\\[+", "").replaceAll("\\]+.*$", "").strip();
                inside = name.equals(prefix) || name.startsWith(prefix + ".");
            }
            if (inside) kept.add(line);
        }
        return kept;
    }

    /**
     * A deterministic rendering of a parsed value, with every agent-stamped
     * instant dropped. Map keys are sorted, so a rewrite that only reorders
     * them is correctly not a difference.
     */
    private static String canon(Object value) {
        StringBuilder sb = new StringBuilder();
        canonInto(value, sb);
        return sb.toString();
    }

    private static void canonInto(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() instanceof String s && ISO_INSTANT.matcher(s).matches()) continue;
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            sb.append('{');
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                sb.append(e.getKey()).append('=');
                canonInto(e.getValue(), sb);
                sb.append(';');
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (Object item : list) {
                canonInto(item, sb);
                sb.append(';');
            }
            sb.append(']');
        } else {
            sb.append(value);
        }
    }

    private static String sha256Of(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "sha256-unavailable";
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- baseline

    /** Where a baseline for {@code fidelity} lives inside this run's report dir. */
    static Path baselineFile(NodeContext ctx, Fidelity fidelity) {
        return ctx.reportDir().resolve("tripwire-" + fidelity.name().toLowerCase() + ".txt");
    }

    static void writeLines(Path file, List<String> lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, lines);
    }

    static List<String> readLines(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }
}
