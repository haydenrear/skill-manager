package dev.skillmanager.source;

import dev.skillmanager.shared.util.Rederivable;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>"Generated, not content", on the git surface</b> (#400, DEF-457).
 *
 * <p>A local test run inside a home unit leaves Python bytecode behind:
 * {@code __pycache__/} directories and {@code *.pyc} / {@code *.pyo} files.
 * They are not anything an author wrote. Two git readers used to treat them as
 * if they were:
 *
 * <ul>
 *   <li>{@code unit publish} ({@link GitOps#commitAll}) staged them with
 *       {@code git add -A} and pushed them upstream —
 *       cdc-agent-substrate-plugin {@code 1dfc3633} published bytecode that
 *       way, and every consumer syncing from that tree then refused.</li>
 *   <li>The sync paths' local-change test
 *       ({@link DereferencedStoreLinks#worktreeChangesBeyond}) counted them as
 *       local work and refused with "extra local changes".</li>
 * </ul>
 *
 * <p>Both now ask this class, and this class does not keep a list of its own:
 * it is {@link Rederivable#isCache} — the definition {@code HomeCloner} and
 * {@code ChildHomeMaterializer} already share — translated into the two forms
 * git speaks (a {@code git status} path, and {@code git add} exclusion
 * pathspecs). Only the CACHE half of {@code Rederivable}, never its
 * {@code OUTPUT_ROOTS}: {@code build/} and {@code venv/} are ordinary words a
 * unit may legitimately commit, and a unit with such trees says so in its own
 * {@code .gitignore}, which {@code git add -A} and {@code git status} already
 * honour.
 *
 * <p>Not counting a path as a local edit is <b>not</b> a licence to destroy
 * it: the sync merge still stashes with {@code --include-untracked} and pops,
 * and {@code commitAll} never deletes anything, so the bytes survive.
 */
public final class GeneratedContent {

    private GeneratedContent() {}

    /**
     * Pathspecs that exclude every cache from a repository-wide
     * {@code git add}, anchored at the repository top ({@code top}) so they
     * apply whatever directory git is run from.
     */
    public static final List<String> EXCLUDE_PATHSPECS = excludePathspecs();

    private static List<String> excludePathspecs() {
        List<String> out = new ArrayList<>();
        for (String name : Rederivable.CACHES.stream().sorted().toList()) {
            out.add(":(top,exclude,glob)**/" + name);
            out.add(":(top,exclude,glob)**/" + name + "/**");
        }
        out.add(":(top,exclude,glob)**/*.pyc");
        out.add(":(top,exclude,glob)**/*.pyo");
        return List.copyOf(out);
    }

    /**
     * Whether the repository-relative {@code path}, as {@code git status
     * --porcelain} names it ({@code /}-separated, quoted when it holds unusual
     * characters, with a trailing {@code /} for a collapsed untracked
     * directory), is generated.
     *
     * <p>A collapsed untracked directory is generated only when it IS a cache
     * or sits under one: {@code ?? pkg/} holding bytecode next to a new
     * {@code .py} is still reported, erring toward reporting a change rather
     * than hiding one.
     */
    public static boolean isGenerated(String path) {
        if (path == null) return false;
        String p = path.trim();
        if (p.length() >= 2 && p.startsWith("\"") && p.endsWith("\"")) {
            p = p.substring(1, p.length() - 1);
        }
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return !p.isEmpty() && Rederivable.isCache(p);
    }
}
