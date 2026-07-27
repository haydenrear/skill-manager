package dev.skillmanager.project;

import dev.skillmanager.model.ProjectVendored;
import dev.skillmanager.model.SkillProject;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks — and, on request, repairs — the {@code [[vendored]]} paths a project
 * declares.
 *
 * <h2>The rule this enforces</h2>
 *
 * <p>A declared vendored path must be a <em>relative</em> symlink into the
 * <em>project's own</em> skill-manager home, at
 * {@code skills/<from_unit>[/<from_subpath>]/<leaf>}; or a real directory
 * holding content, which is what {@code scaffold.py --copy-sdk} produces.
 * Anything else is a finding.
 *
 * <h2>Why the check resolves rather than reads</h2>
 *
 * <p>The obvious implementation asks whether the link's stored text starts with
 * {@code /}. That is exactly the check that misses the worst case actually
 * observed here:
 *
 * <pre>
 *   deploy-helm/test_graph/sdk            -> /Users/hayde/.skill-manager/.../sdk
 *   deploy-helm/test_graph/standard-nodes -> sdk/../standard-nodes
 * </pre>
 *
 * <p>The second link's text is relative and contains no {@code /} prefix and no
 * home path, so {@code find -lname '/*'} does not list it and neither does any
 * string match on the target. But the kernel resolves {@code sdk} first, which
 * is absolute, so {@code standard-nodes} lands physically in the foreign home
 * alongside its sibling. Lexical normalization gets this wrong in the same way
 * and for the same reason: {@code Path.normalize()} deletes {@code sdk/..}
 * textually, without ever asking what {@code sdk} is.
 *
 * <p>So every judgement below is made on {@link Path#toRealPath} output, which
 * resolves each component in turn. That also makes the comparison spelling-proof
 * — {@code /var/folders/...} and {@code /private/var/folders/...} canonicalize
 * to one path — which is the trap {@code HomeLinks.storedTarget} was bitten by.
 *
 * <h2>Why the target is computed, never templated</h2>
 *
 * <p>A fixed {@code ../} count cannot express where the home is. Projects in
 * this repository sit at one integration level ({@code constituents/deploy-helm})
 * and at two ({@code constituents/meta-orchestrator/constituents/stream-lite}),
 * and a vendored path may itself be nested any number of directories below the
 * project root. The home is therefore found by walking up from <em>the link's
 * own directory</em> to the nearest enclosing {@code .skill-manager}, and the
 * link text is produced by relativizing against that same directory. Both sides
 * of that relativization come out of one upward walk, so they are literally the
 * same spelling and cannot disagree.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It never writes into a skill-manager home — the only mutation it can
 * perform is replacing a symlink (or an empty directory) inside the project's
 * own working tree, and only when explicitly asked. A frozen home is therefore
 * reported on and not touched, which is what {@code HomePolicy} asks of a
 * read-only path. It also never deletes a directory that holds content: a
 * {@code --copy-sdk} snapshot is a legitimate vendoring mode, and a half-broken
 * link is not a licence to remove somebody's files.
 */
public final class ProjectVendoredResolver {

    /** Directory name of a project-local skill-manager home. */
    public static final String HOME_DIR = ".skill-manager";

    /** The supported non-symlink vendoring mode, named in every remedy. */
    public static final String COPY_SDK_REMEDY =
            "scaffold.py <repo-root> --copy-sdk (snapshot-copies the vendored trees "
                    + "instead of symlinking them)";

    private ProjectVendoredResolver() {}

    /** What one declared path turned out to be. */
    public enum Status {
        /** Relative symlink resolving to the declared source in the project's own home. */
        OK(false),
        /** A real directory with content: a {@code --copy-sdk} snapshot. */
        COPY(false),
        /** The declared path is not present at all. */
        MISSING(true),
        /** A symlink whose target does not resolve. */
        DANGLING(true),
        /** Absolute link text resolving outside the project — the machine-specific case. */
        FOREIGN_ABSOLUTE(true),
        /** Relative link text resolving outside the project through another link. */
        FOREIGN_DISGUISED(true),
        /** Absolute link text that happens to land inside the project. */
        ABSOLUTE_IN_PROJECT(true),
        /** Resolves inside the project, but not at the declared source. */
        MISPOINTED(true),
        /** A real directory with nothing in it: a half-finished vendor. */
        EMPTY(true);

        private final boolean problem;

        Status(boolean problem) { this.problem = problem; }

        public boolean problem() { return problem; }
    }

    /**
     * One declared path's verdict.
     *
     * @param resolvedTo   physical location the path resolves to, or null when it
     *                     resolves to nothing
     * @param expectedText the relative link text a correct link would hold —
     *                     computed from this path's own directory, never templated
     */
    public record Entry(
            String declaration,
            String declaredPath,
            Path path,
            Status status,
            boolean fatal,
            String linkText,
            Path resolvedTo,
            Path expectedTarget,
            Path expectedText,
            List<String> candidates,
            String detail,
            boolean repaired
    ) {
        public Entry {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public boolean problem() { return status.problem(); }

        Entry repairedAs() {
            return new Entry(declaration, declaredPath, path, status, fatal, linkText,
                    resolvedTo, expectedTarget, expectedText, candidates, detail, true);
        }

        /** The lines this entry contributes to a report. */
        public List<String> render() {
            List<String> out = new ArrayList<>();
            out.add("vendored " + declaration + ": " + declaredPath + " " + status
                    + (repaired ? " (repaired)" : "")
                    + (problem() ? (fatal ? " [error]" : " [warn]") : ""));
            out.add("  detail:     " + detail);
            if (linkText != null) out.add("  link text:  " + linkText);
            if (resolvedTo != null) out.add("  resolves:   " + resolvedTo);
            if (expectedTarget != null) {
                out.add("  expected:   " + expectedText + "  ->  " + expectedTarget);
            }
            for (String candidate : candidates) out.add("  candidate:  " + candidate);
            if (problem()) out.add("  remedy:     " + remedy());
            return out;
        }

        private String remedy() {
            if (expectedTarget != null && Files.isDirectory(expectedTarget)) {
                return "re-point it at " + expectedText
                        + " — `skill-manager project resolve --repair-vendored` does exactly that; "
                        + "or vendor a snapshot instead: " + COPY_SDK_REMEDY;
            }
            return "the declared source does not exist in any enclosing home yet: install the unit "
                    + "into the project's own home (`skill-manager project resolve`), then re-run "
                    + "with --repair-vendored; or vendor a snapshot instead: " + COPY_SDK_REMEDY;
        }
    }

    /** Every declared path's verdict, plus whether the command may proceed. */
    public record Report(List<Entry> entries) {
        public Report {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        public static Report empty() { return new Report(List.of()); }

        public List<Entry> problems() {
            return entries.stream().filter(Entry::problem).toList();
        }

        public List<Entry> repairs() {
            return entries.stream().filter(Entry::repaired).toList();
        }

        /** Surviving problems whose declaration says {@code on_invalid = "error"}. */
        public List<Entry> fatalProblems() {
            return entries.stream().filter(e -> e.problem() && e.fatal()).toList();
        }

        public boolean clean() { return problems().isEmpty(); }

        public List<String> render() {
            List<String> out = new ArrayList<>();
            for (Entry entry : entries) {
                if (!entry.problem() && !entry.repaired()) continue;
                out.addAll(entry.render());
            }
            return out;
        }

        /** The message a failing command dies with: every surviving finding, in full. */
        public String failureMessage() {
            StringBuilder sb = new StringBuilder("project vendored paths are invalid: ")
                    .append(fatalProblems().size())
                    .append(" declared path(s) do not point at the project's own skill-manager home");
            for (String line : render()) sb.append('\n').append("  ").append(line);
            return sb.toString();
        }
    }

    /**
     * Classify every declared path, optionally repairing what can be repaired.
     *
     * <h2>Snapshot, then repair, then re-classify</h2>
     *
     * <p>Three passes rather than one, because vendored paths can depend on each
     * other. {@code standard-nodes -> sdk/../standard-nodes} resolves through its
     * sibling, so repairing {@code sdk} first silently changes what
     * {@code standard-nodes} means: in a single pass it would then classify as
     * fine and be left holding text that only works while {@code sdk} does. The
     * report would also depend on declaration order, which is not a property a
     * diagnostic should have.
     *
     * <p>So: every path is judged against one consistent snapshot of the tree;
     * every finding is then repaired against its <em>own</em> declared source,
     * never through another link; and the final statuses come from a second
     * snapshot taken after all the writing.
     *
     * @param repair replace a broken link (or an empty placeholder directory)
     *               with a correct relative link when the declared source
     *               actually exists. Never deletes anything holding content.
     */
    public static Report check(SkillProject project, boolean repair) throws IOException {
        if (project == null || project.vendored().isEmpty()) return Report.empty();
        Path projectRoot = project.projectRoot().toAbsolutePath().normalize();
        Path projectReal = realOrSame(projectRoot);

        List<Entry> before = classifyAll(project, projectRoot, projectReal);
        if (!repair) return new Report(before);

        Set<String> repaired = new LinkedHashSet<>();
        for (Entry entry : before) {
            if (entry.problem() && writeRepair(entry)) repaired.add(key(entry));
        }
        if (repaired.isEmpty()) return new Report(before);

        List<Entry> after = classifyAll(project, projectRoot, projectReal);
        List<Entry> out = new ArrayList<>(after.size());
        for (Entry entry : after) out.add(repaired.contains(key(entry)) ? entry.repairedAs() : entry);
        return new Report(out);
    }

    private static List<Entry> classifyAll(SkillProject project, Path projectRoot, Path projectReal) {
        List<Entry> entries = new ArrayList<>();
        for (ProjectVendored declaration : project.vendored()) {
            for (String declaredPath : declaration.paths()) {
                entries.add(classify(declaration, declaredPath, projectRoot, projectReal));
            }
        }
        return entries;
    }

    private static String key(Entry entry) {
        return entry.declaration() + ' ' + entry.declaredPath();
    }

    // ------------------------------------------------------------ classification

    private static Entry classify(
            ProjectVendored declaration,
            String declaredPath,
            Path projectRoot,
            Path projectReal
    ) {
        Path path = projectRoot.resolve(declaredPath);
        Path linkDir = path.getParent();
        Path home = nearestHome(linkDir);
        Path expectedTarget = home == null
                ? null
                : declaration.sourceDirIn(home).resolve(path.getFileName());
        Path expectedText = expectedTarget == null || linkDir == null
                ? null
                : linkDir.relativize(expectedTarget);
        List<String> candidates = candidates(declaration, path, linkDir);
        boolean fatal = declaration.fatal();

        boolean isLink = Files.isSymbolicLink(path);
        if (!isLink && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return new Entry(declaration.name(), declaredPath, path, Status.MISSING, fatal,
                    null, null, expectedTarget, expectedText, candidates,
                    "declared vendored path does not exist", false);
        }

        if (isLink) {
            String linkText = readLinkText(path);
            Path resolved;
            try {
                resolved = path.toRealPath();
            } catch (IOException unresolvable) {
                return new Entry(declaration.name(), declaredPath, path, Status.DANGLING, fatal,
                        linkText, null, expectedTarget, expectedText, candidates,
                        "symlink target does not resolve; the project cannot build from it at all",
                        false);
            }
            // Judged on the RESOLVED PHYSICAL path. `sdk/../standard-nodes` has
            // relative text and resolves into a foreign home through its sibling;
            // reading the text, or normalizing it lexically, calls that fine.
            boolean absolute = Path.of(linkText).isAbsolute();
            if (!resolved.startsWith(projectReal)) {
                Status status = absolute ? Status.FOREIGN_ABSOLUTE : Status.FOREIGN_DISGUISED;
                String detail = absolute
                        ? "absolute symlink resolving outside the project, into " + resolved
                        + ": it resolves on this machine only — the path is baked into a tracked "
                        + "git blob, so another checkout or CI gets a dangling link, and a "
                        + "concurrent Gradle daemon in an unrelated worktree writes build state "
                        + "into that same shared tree"
                        : "link text is relative but resolves through another link to " + resolved
                        + ", outside the project: it is the absolute case in disguise, and any "
                        + "check that reads link text instead of resolving it will call this fine";
                return new Entry(declaration.name(), declaredPath, path, status, fatal,
                        linkText, resolved, expectedTarget, expectedText, candidates, detail, false);
            }
            if (absolute) {
                return new Entry(declaration.name(), declaredPath, path, Status.ABSOLUTE_IN_PROJECT,
                        fatal, linkText, resolved, expectedTarget, expectedText, candidates,
                        "absolute symlink into this project's own tree: it resolves today but does "
                                + "not survive a move, a clone, or a worktree", false);
            }
            if (expectedTarget == null || !resolved.equals(realOrSame(expectedTarget))) {
                return new Entry(declaration.name(), declaredPath, path, Status.MISPOINTED, fatal,
                        linkText, resolved, expectedTarget, expectedText, candidates,
                        "resolves inside the project but not at the declared source"
                                + (expectedTarget == null
                                ? "; no enclosing " + HOME_DIR + " home was found"
                                : ""), false);
            }
            return new Entry(declaration.name(), declaredPath, path, Status.OK, fatal,
                    linkText, resolved, expectedTarget, expectedText, candidates,
                    "relative link into the project's own home", false);
        }

        if (Files.isDirectory(path)) {
            if (isEmptyDir(path)) {
                return new Entry(declaration.name(), declaredPath, path, Status.EMPTY, fatal,
                        null, path, expectedTarget, expectedText, candidates,
                        "vendored directory exists but holds nothing: a half-finished vendor",
                        false);
            }
            return new Entry(declaration.name(), declaredPath, path, Status.COPY, fatal,
                    null, realOrSame(path), expectedTarget, expectedText, candidates,
                    "snapshot copy in the working tree", false);
        }

        return new Entry(declaration.name(), declaredPath, path, Status.MISPOINTED, fatal,
                null, realOrSame(path), expectedTarget, expectedText, candidates,
                "vendored path is a regular file where a vendored tree was declared", false);
    }

    // ------------------------------------------------------------------- repair

    /**
     * Replace a broken vendored link with a correct relative one.
     *
     * <p>Refuses in the two cases where it would destroy something: a directory
     * holding content (that is either a {@code --copy-sdk} snapshot or somebody's
     * work), and a declared source that does not exist (repointing at nothing
     * turns one finding into another).
     */
    private static boolean writeRepair(Entry entry) {
        Path expected = entry.expectedTarget();
        if (expected == null || !Files.exists(expected)) return false;

        Path path = entry.path();
        boolean isLink = Files.isSymbolicLink(path);
        boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (exists && !isLink && !(Files.isDirectory(path) && isEmptyDir(path))) return false;

        try {
            Path linkDir = path.getParent();
            if (linkDir != null) Files.createDirectories(linkDir);
            // Deleting a symlink removes the link, not the tree it points at;
            // the only directory that reaches here is provably empty.
            Files.deleteIfExists(path);
            Files.createSymbolicLink(path, entry.expectedText());
            return true;
        } catch (IOException | UnsupportedOperationException failed) {
            return false;
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The nearest {@code .skill-manager} at or above {@code start}.
     *
     * <p>Walked from the link's own directory rather than from the project root
     * so a project nested inside another integration repository finds its own
     * home first and the outer one only as a fallback — which is the shape
     * {@code meta-orchestrator/constituents/stream-lite} actually has.
     */
    static Path nearestHome(Path start) {
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path home = dir.resolve(HOME_DIR);
            if (Files.isDirectory(home)) return home;
        }
        return null;
    }

    /** Every enclosing home, nearest first. */
    static List<Path> enclosingHomes(Path start) {
        Set<Path> homes = new LinkedHashSet<>();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path home = dir.resolve(HOME_DIR);
            if (Files.isDirectory(home)) homes.add(home);
        }
        return List.copyOf(homes);
    }

    /**
     * Where the declared source could be found. An error that only says
     * "invalid" repeats the problem, so a finding names every home above this
     * path and says whether each one actually holds the content.
     */
    private static List<String> candidates(ProjectVendored declaration, Path path, Path linkDir) {
        List<String> out = new ArrayList<>();
        for (Path home : enclosingHomes(linkDir)) {
            Path candidate = declaration.sourceDirIn(home).resolve(path.getFileName());
            out.add(candidate + (Files.isDirectory(candidate) ? "  (present)" : "  (absent)"));
        }
        if (out.isEmpty()) {
            out.add("no " + HOME_DIR + " home exists at or above " + linkDir);
        }
        return out;
    }

    private static String readLinkText(Path link) {
        try {
            return Files.readSymbolicLink(link).toString();
        } catch (IOException unreadable) {
            return "";
        }
    }

    private static boolean isEmptyDir(Path dir) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            return !entries.iterator().hasNext();
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static Path realOrSame(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException unresolvable) {
            return path.toAbsolutePath().normalize();
        }
    }
}
