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
import java.util.Set;

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
 */
final class TripwireSupport {

    /** How closely a snapshot looks. */
    enum Fidelity { METADATA, CONTENT }

    /** The four roots a skill-manager write could escape into. */
    static final List<String> ROOTS = List.of(".skill-manager", ".claude", ".codex", ".gemini");

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
     * Runtime state that any concurrent agent session churns by existing.
     *
     * <p>Every entry is state a live {@code claude}/{@code codex} session writes
     * on its own schedule — transcripts, session rollouts, sqlite WALs, caches.
     * Leaving them in would make the tripwire fire on the presence of the very
     * agent running it, and an oracle that cries wolf gets switched off. Nothing
     * a skill or a projection lives in is pruned: {@code skills/}, {@code plugins/},
     * {@code settings.json}, {@code config.toml}, {@code agents/}, {@code commands/},
     * MCP config and the whole of {@code ~/.skill-manager} are all covered.
     */
    private static boolean pruned(Path home, Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        if (name.equals("__pycache__")) return true;
        if (name.equals("models_cache.json") || name.equals("history.jsonl")) return true;
        if (name.startsWith("logs") && name.contains(".sqlite")) return true;
        if (name.startsWith("state") && name.contains(".sqlite")) return true;
        if (name.equals("cache") && path.getParent() != null
                && ROOTS.contains(path.getParent().getFileName().toString())) {
            return true;
        }
        Path claude = home.resolve(".claude");
        for (String churn : new String[] {
                "projects", "todos", "statsig", "shell-snapshots",
                "file-history", "paste-history", "logs",
                // Same reasoning as every entry above, and added because the
                // node went red on them: a live agent session NECESSARILY
                // churns these while it runs. Claude Code writes
                // .claude/tasks/<session>/N.json as it works and rotates
                // .claude.json.backup.* into .claude/backups/ on its own
                // schedule, so the tripwire was firing on the runtime of the
                // very agent running it. Confirmed benign when it did:
                // contentDiff was empty and leaked was empty — the metadata
                // diff was entirely session state. Nothing a skill, a binding
                // or a projection lives in is pruned by these two.
                "tasks", "backups" }) {
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

        if (Files.isSymbolicLink(current)) {
            // Never recurse through a link: a link into a large tree would make
            // the walk quadratic, and the target string is the fact that matters.
            out.add("L\t" + rel + "\t" + Files.readSymbolicLink(current));
            return;
        }
        // Coarse, at BOTH fidelities: a `.git` inside a content surface is
        // hashed no more deeply than one outside it, because the question asked
        // of it is the same question. See gitRegistrations.
        if (".git".equals(current.getFileName() == null ? "" : current.getFileName().toString())) {
            gitRegistrations(base, current, out);
            return;
        }
        if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            out.add("D\t" + rel);
            for (Path child : listSorted(current)) walk(base, child, home, fidelity, out);
            return;
        }
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
