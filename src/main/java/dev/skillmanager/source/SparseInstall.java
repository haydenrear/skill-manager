package dev.skillmanager.source;

import dev.skillmanager.model.InstallExclusion;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The git half of an install exclusion, #261: excluded paths are skip-worktree
 * entries of a sparse checkout, so they are absent on disk and still in every
 * tree git writes.
 *
 * <p>The checkout uses non-cone patterns in exactly one shape, {@code /*} and
 * then one {@code !/<path>} per exclusion. {@link #managedExclusions} recognises
 * only that shape, so a sparse checkout somebody set up by hand is not mistaken
 * for one an install made.
 */
public final class SparseInstall {

    private static final String INCLUDE_ALL = "/*";

    private SparseInstall() {}

    /** Make {@code exclusions} the checkout's excluded set. */
    public static boolean apply(Path dir, List<String> exclusions) {
        List<String> argv = new ArrayList<>(List.of("git", "sparse-checkout", "set", "--no-cone", INCLUDE_ALL));
        for (String path : exclusions) argv.add("!/" + path);
        return GitOps.run(dir, argv).exit() == 0;
    }

    /**
     * The exclusions this checkout was set up with, or empty when it is not a
     * sparse checkout in the shape an install writes.
     */
    public static Optional<Set<String>> managedExclusions(Path dir) {
        if (dir == null || !Files.isDirectory(dir.resolve(".git"))) return Optional.empty();
        GitOps.Result enabled = GitOps.run(dir, List.of("git", "config", "--bool", "core.sparseCheckout"));
        if (enabled.exit() != 0 || !"true".equals(enabled.stdout().strip())) return Optional.empty();
        GitOps.Result where = GitOps.run(dir, List.of("git", "rev-parse", "--git-path", "info/sparse-checkout"));
        if (where.exit() != 0) return Optional.empty();
        Path patterns = dir.resolve(where.stdout().strip());
        List<String> lines;
        try {
            lines = Files.readAllLines(patterns);
        } catch (IOException e) {
            return Optional.empty();
        }
        Set<String> excluded = new LinkedHashSet<>();
        boolean sawInclude = false;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (!sawInclude) {
                if (!INCLUDE_ALL.equals(line)) return Optional.empty();
                sawInclude = true;
            } else if (line.startsWith("!/") && line.length() > 2) {
                excluded.add(line.substring(2));
            } else {
                return Optional.empty();
            }
        }
        return sawInclude && !excluded.isEmpty() ? Optional.of(excluded) : Optional.empty();
    }

    /** Whether {@code path} is, or is inside, one of {@code exclusions}. */
    static boolean isExcluded(String path, Set<String> exclusions) {
        for (String excluded : exclusions) {
            if (path.equals(excluded) || path.startsWith(excluded + "/")) return true;
        }
        return false;
    }

    /**
     * Objects the history names and this repository does not hold: above zero
     * for a real partial clone, zero when the remote ignored the filter. The
     * {@code remote.origin.promisor} config cannot say which, git writes it
     * either way.
     */
    public static long missingObjects(Path dir) {
        GitOps.Result r = GitOps.run(dir, List.of("git", "rev-list", "--objects", "--missing=print", "--all"));
        if (r.exit() != 0) return 0;
        return r.stdout().lines().filter(l -> l.startsWith("?")).count();
    }

    /** Say what an exclusion install actually left on disk. */
    public static void report(Path dir, Set<String> exclusions) {
        long missing = missingObjects(dir);
        if (missing > 0) {
            Log.info("excluded from install: %s (%d blobs fetched on demand from origin)",
                    String.join(", ", exclusions), missing);
        } else {
            Log.warn("excluded from the working tree only: %s. The source does not serve a partial "
                            + "clone (a local path, or uploadpack.allowFilter off), so their blobs stay in .git",
                    String.join(", ", exclusions));
        }
    }

    /**
     * Apply the manifest's exclusions to a tree that was just copied whole (a
     * local source, or a fallback clone): sparse where it is a repository, and
     * plain deletion where it is not, since there is then no history to keep.
     */
    public static void applyDeclared(Path root) throws IOException {
        List<String> exclusions = InstallExclusion.declaredIn(root);
        if (exclusions.isEmpty()) return;
        if (Files.isDirectory(root.resolve(".git"))) {
            if (!apply(root, exclusions)) {
                Log.warn("could not apply %s in %s; the excluded paths remain on disk",
                        InstallExclusion.KEY, root);
                return;
            }
            report(root, new LinkedHashSet<>(exclusions));
            return;
        }
        for (String path : exclusions) Fs.deleteRecursive(root.resolve(path));
        Log.info("excluded from install: %s", String.join(", ", exclusions));
    }

    /**
     * Follow a manifest change after the tree moved. Only a checkout an install
     * already made sparse is touched: a full clone keeps its shape until it is
     * reinstalled.
     */
    public static void reconcile(Path dir) {
        Optional<Set<String>> current = managedExclusions(dir);
        if (current.isEmpty()) return;
        try {
            List<String> declared = InstallExclusion.declaredIn(dir);
            if (new LinkedHashSet<>(declared).equals(current.get())) return;
            boolean ok = declared.isEmpty()
                    ? GitOps.run(dir, List.of("git", "sparse-checkout", "disable")).exit() == 0
                    : apply(dir, declared);
            if (!ok) Log.warn("could not update the install exclusions of %s", dir);
        } catch (IOException e) {
            Log.warn("kept the existing install exclusions of %s: %s", dir, e.getMessage());
        }
    }

    /**
     * Clone a sparse checkout, keeping it sparse. A plain clone of one would
     * materialize every excluded path, fetching each blob to do it.
     */
    static boolean cloneKeepingExclusions(Path source, Path dest, Set<String> exclusions) {
        Path parent = dest.getParent();
        if (GitOps.run(parent, List.of("git", "clone", "--quiet", "--no-checkout",
                source.toString(), dest.toString())).exit() != 0) return false;
        String upstream = GitOps.originUrl(source);
        if (upstream != null && !upstream.isBlank() && missingObjects(source) > 0) {
            // The copied packs promise blobs only the source's own remote can
            // supply, so that remote has to become this checkout's promisor.
            for (List<String> config : List.of(
                    List.of("remote", "set-url", "origin", upstream),
                    List.of("config", "core.repositoryformatversion", "1"),
                    List.of("config", "extensions.partialClone", "origin"),
                    List.of("config", "remote.origin.promisor", "true"),
                    List.of("config", "remote.origin.partialclonefilter", "blob:none"))) {
                List<String> argv = new ArrayList<>(List.of("git"));
                argv.addAll(config);
                if (GitOps.run(dest, argv).exit() != 0) return false;
            }
        }
        return apply(dest, new ArrayList<>(exclusions))
                && GitOps.run(dest, List.of("git", "checkout", "--quiet")).exit() == 0;
    }
}
