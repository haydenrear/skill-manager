///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A unit carrying a gitignored, re-derivable tree stays syncable — and a sync
 * that fails partway does not strand the installed baseline behind a merge it
 * already committed.
 *
 * <h2>The drag this pins</h2>
 *
 * <p>Measured on the operator's own project home, twice, nine days apart, and
 * it is the single biggest productivity drag the home mechanism has produced.
 * Two units — {@code deploy-helm} and {@code spec-double-compiler} — sat
 * permanently unsyncable, each reporting
 *
 * <pre>
 *   is not stale — its store is mid-merge (MERGE_CONFLICT): unmerged paths
 *   remain. Local work is preserved at stash@{0}.
 *   resolve with: git -C .../skills/&lt;unit&gt; status  # resolve, then: git add + git commit
 * </pre>
 *
 * <p>and, once that was cleared by hand,
 *
 * <pre>
 *   has extra local changes (working tree edits, or commits ahead of the
 *   installed baseline) — sync would overwrite them.
 *   re-run with: skill-manager sync &lt;unit&gt; --merge
 * </pre>
 *
 * <p>Neither remedy clears either state. Every agent in every worktree cloned
 * from that home inherits both.
 *
 * <h2>The chain, which is four links and not one</h2>
 *
 * <ol>
 *   <li><b>The trigger.</b> The test-graph scaffolder writes
 *       {@code test_graph/build-logic}, {@code sdk} and {@code standard-nodes}
 *       into a consuming unit as symlinks into the test-graph store copy, and
 *       the unit's own {@code test_graph/.gitignore} declares all three
 *       not-content. {@code ChildHomeMaterializer} dereferences them into real
 *       directories so the child home is independent — correct — but
 *       {@code Rederivable} does not know these names and the digest walk does
 *       not read {@code .gitignore}, so they count as unit content. The child
 *       home therefore holds trees its parent store does not: measured 13 and
 *       12 entries under {@code test_graph/} against the root store's 10.</li>
 *   <li><b>The mechanism.</b> Sync reads that as a dirty tree, stashes it,
 *       merges upstream — <em>the merge commit lands</em> — then pops the
 *       stash. The pop conflicts, because the stash carries a deletion of a
 *       path upstream still holds at mode {@code 120000} and the working tree
 *       cannot hold both a directory and a symlink there. Sync aborts.</li>
 *   <li><b>The permanence, and the load-bearing link.</b> The merge is already
 *       committed but {@code installed/<unit>.json} was never updated. Measured:
 *       record {@code gitHash c72d03a6} written 2026-07-25, store {@code HEAD}
 *       at {@code eab28837}. HEAD is now ahead of the baseline <em>forever</em>,
 *       so every later sync reports "commits ahead of the installed baseline"
 *       and refuses. Fixing links 1 and 2 alone does not release a home already
 *       in this state.</li>
 *   <li><b>The invisibility.</b> The {@code errors[{kind: MERGE_CONFLICT}]}
 *       entry carries no {@code resolvedAt} and is never re-derived, so
 *       {@code skt check} kept reporting the conflict after the git state was
 *       clean.</li>
 * </ol>
 *
 * <h2>Why this asserts the whole chain in one node</h2>
 *
 * <p>Because any single link left in place reproduces the drag. A node that
 * only proved "the deref'd tree is excluded from the digest" would pass over a
 * home that is still stranded at link 3, which is the state the operator was
 * actually in. So the fixture drives one sync across a unit that has all four
 * conditions and asserts the outcome an operator cares about: <b>the unit is
 * still syncable afterwards</b>.
 *
 * <h2>What the fixture does NOT do</h2>
 *
 * <p>It does not plant the stranded record by hand. A pinned defect asserted
 * over state the test wrote itself can never observe the product being fixed —
 * the record would still say whatever the fixture made it say. Here the record
 * is written by the product's own install and moved, or not moved, by the
 * product's own sync. The assertion goes green the moment sync stops stranding
 * it, and not before.
 *
 * <p>Hermetic: the unit's origin is a local bare clone, so nothing this node
 * declares is fetched. As {@code HomeIntegrityFixture} already records, that
 * does not make it network-free end to end — {@code install} runs
 * {@code EnsureGateway} — and overstating it would be the kind of comfortable
 * claim this epic keeps having to retract.
 *
 * <h2>STATUS: this node is GREEN, and that is not yet the good news</h2>
 *
 * <p>Read this before trusting it. As written, the fixture drives a
 * <b>root-tier</b> {@code install} then {@code sync}, and that path handles the
 * mode clash correctly: the sync reports {@code 1 merged}, the baseline
 * advances, no {@code MERGE_CONFLICT} is recorded, and a second sync settles.
 * So this node currently guards a true and desirable property — <em>sync must
 * not strand the installed baseline</em> — and it will catch a regression that
 * breaks it.
 *
 * <p>What it does <b>not</b> yet do is reproduce the operator's failure. That
 * one happened on a <b>project-tier</b> home holding a <em>materialized child
 * copy</em>, and reaching it needs the materialization path, not a plain
 * install. Two things were ruled out along the way, both by measurement, and
 * they are recorded so the next person does not re-spend them:
 *
 * <ul>
 *   <li>An ordinary gitignored directory that never existed upstream is NOT
 *       enough — git ignores it, so there is no stash, no pop and no conflict.
 *       The first version of this fixture did that and passed vacuously.</li>
 *   <li>A tracked upstream symlink dereferenced into a real directory, with
 *       upstream then re-pointing that symlink, is ALSO not enough at root
 *       tier. It merges cleanly.</li>
 * </ul>
 *
 * <p>So the remaining variable is the tier and the materialization, and
 * pinning it is HIS-4 (#216)'s first acceptance item. Until that lands this
 * graph stays out of the CI core set — not because it is red, but because it
 * is <em>unproven against the defect it is named for</em>, and a node that
 * passes for the wrong reason is the failure mode this whole epic keeps
 * meeting. See {@code .github/scripts/select-graph-set.py}.
 */
public class ScaffoldTreeDoesNotStrandTheBaseline {

    static final NodeSpec SPEC = NodeSpec.of("sync.settles.scaffold.tree.does.not.strand.baseline")
            .kind(NodeSpec.Kind.ASSERTION)
            .tags("sync", "home", "rederivable", "his-4")
            .timeout("600s");

    /** The unit. Named for what it carries, not for what breaks. */
    private static final String UNIT = "scaffolded-unit";

    /**
     * The gitignored tree, under {@code test_graph/} and named exactly as the
     * real scaffolder names it. The name matters: a fix that special-cases
     * {@code build} but not {@code build-logic} is the gap this pins.
     */
    private static final String SCAFFOLD_DIR = "test_graph/build-logic";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            try {
                return check(ctx);
            } catch (IOException | InterruptedException e) {
                return NodeResult.error(SPEC.id(), e);
            }
        });
    }

    private static NodeResult check(NodeContext ctx) throws IOException, InterruptedException {
        Path scratch = Files.createTempDirectory("sync-settles-");
        Path home = scratch.resolve("home");
        Files.createDirectories(home);

        Path work = scaffoldUnit(scratch);
        Path bare = scratch.resolve(UNIT + ".git");

        // 1. Install from the bare remote, so the store copy has a real
        //    checkout, a real tracking ref, and a product-written record.
        ProcessRecord install = sm(ctx, "install", home, "install", "git+file://" + bare);
        if (install.exitCode() != 0) {
            return NodeResult.fail(SPEC.id(),
                    "fixture install failed (exit " + install.exitCode() + ") — the node proves "
                            + "nothing about sync if the subject was never installed");
        }

        Path store = home.resolve("skills").resolve(UNIT);
        if (!Files.isDirectory(store)) {
            return NodeResult.fail(SPEC.id(), "installed unit is not at " + store);
        }

        // 2. DEREFERENCE, exactly as ChildHomeMaterializer does when it makes a
        //    child home independent (CHM-5): the symlink upstream tracks is
        //    replaced, in the home, by a real directory holding the same bytes.
        //    Correct on its own terms -- and the moment it happens, the home
        //    disagrees with its own remote about the file MODE at that path.
        Path scaffold = store.resolve(SCAFFOLD_DIR);
        if (Files.isSymbolicLink(scaffold)) Files.delete(scaffold);
        Files.createDirectories(scaffold);
        Files.writeString(scaffold.resolve("build.gradle.kts"), "// re-derivable scaffold output\n");

        String baselineBefore = recordedGitHash(home);

        // 3. Upstream moves, exactly as a skill repo does between syncs.
        Files.writeString(work.resolve("SKILL.md"), """
                ---
                name: %s
                description: sync-settles graph fixture, second revision
                ---

                Upstream moved after the scaffold tree appeared.
                """.formatted(UNIT));
        // Upstream also moves the SYMLINK itself -- a scaffolder re-pointing
        // its tree, which is an ordinary upstream change. This is what makes
        // the merge touch the same path the home dereferenced, turning a
        // divergence into a conflict rather than a silent take.
        Path target2 = work.resolve("_scaffold-src-v2");
        Files.createDirectories(target2);
        Files.writeString(target2.resolve("build.gradle.kts"), "// scaffold source, v2\n");
        Files.delete(work.resolve(SCAFFOLD_DIR));
        Files.createSymbolicLink(work.resolve(SCAFFOLD_DIR), Path.of("..", "_scaffold-src-v2"));
        git(work, "add", "-A");
        git(work, "commit", "-m", "upstream moves after the scaffold tree exists");
        git(work, "push", "origin", "main");
        String upstreamHead = capture(work, "git", "rev-parse", "HEAD");

        // 4. The sync an agent runs. Not --merge: --merge is the remedy the
        //    product PRINTS once it is already stuck, and a node that reaches
        //    for it is asserting the workaround rather than the behaviour.
        ProcessRecord sync = sm(ctx, "sync", home, "sync", UNIT);

        List<String> failures = new ArrayList<>();

        if (sync.exitCode() != 0) {
            failures.add("sync exited " + sync.exitCode()
                    + "; a unit carrying a gitignored re-derivable tree must stay syncable");
        }

        String baselineAfter = recordedGitHash(home);
        if (baselineAfter == null) {
            failures.add("installed record carries no gitHash after sync");
        } else if (!baselineAfter.equals(upstreamHead)) {
            // LINK 3, the load-bearing one.
            failures.add("installed baseline was stranded: record says " + shortHash(baselineAfter)
                    + " (was " + shortHash(baselineBefore) + "), upstream HEAD is "
                    + shortHash(upstreamHead) + ". A merge that lands without its record makes "
                    + "every later sync report 'commits ahead of the installed baseline' forever");
        }

        String errors = recordedErrors(home);
        if (errors != null && errors.contains("MERGE_CONFLICT")) {
            failures.add("sync recorded a MERGE_CONFLICT over a tree the unit's own "
                    + ".gitignore declares is not content: " + errors);
        }

        if (!Files.isDirectory(scaffold)) {
            // The opposite failure, and worth pinning: excluding a tree from
            // the digest must not become licence to delete it. Rederivable's
            // contract is skipped on BOTH sides -- not hashed, not copied, and
            // not destroyed (carryOverUnownedTrees).
            failures.add("the re-derivable tree was destroyed by the sync; skipping a path "
                    + "must make it invisible, not disposable");
        }

        // 5. The state an agent actually meets: does the NEXT command settle?
        ProcessRecord second = sm(ctx, "sync-again", home, "sync", UNIT);
        if (second.exitCode() != 0) {
            failures.add("a second sync with nothing changed exited " + second.exitCode()
                    + "; the home never reaches a settled state");
        }

        return failures.isEmpty()
                ? NodeResult.pass(SPEC.id())
                : NodeResult.fail(SPEC.id(), String.join(" | ", failures));
    }

    // ----------------------------------------------------------- the fixture

    private static Path scaffoldUnit(Path root) throws IOException, InterruptedException {
        Path work = root.resolve(UNIT);
        Files.createDirectories(work);

        Files.writeString(work.resolve("SKILL.md"), """
                ---
                name: %s
                description: sync-settles graph fixture
                ---

                A unit whose test_graph project carries re-derivable scaffold output.
                """.formatted(UNIT));
        Files.writeString(work.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "sync-settles graph fixture"
                """.formatted(UNIT));

        // The unit declares the scaffold tree not-content, in its own words.
        Path tg = work.resolve("test_graph");
        Files.createDirectories(tg);
        Files.writeString(tg.resolve(".gitignore"), """
                # Written by the test-graph scaffolder. Re-derivable; not this
                # unit's content. Mirrors test_graph/.gitignore:30-32 in the
                # real consuming units. Note this does NOT untrack build-logic
                # below: .gitignore has no effect on an already-tracked path,
                # which is exactly the real units' state and the reason the
                # mode clash survives.
                /build-logic
                /sdk
                /standard-nodes
                """);
        Files.writeString(tg.resolve("build.gradle.kts"), "// fixture test_graph project\n");

        // THE SHAPE THAT MATTERS. Upstream tracks the scaffold path as a
        // SYMLINK -- git mode 120000 -- pointing at a tree carried elsewhere in
        // the unit. That is what the test-graph scaffolder produces, and it is
        // why the conflict is unresolvable later: the home will hold a real
        // DIRECTORY at this path, and a working tree cannot be both.
        //
        // A first version of this fixture made the path an ordinary ignored
        // directory that never existed upstream. It passed, because git simply
        // ignored it: no stash, no pop, no conflict. A green node that does not
        // reproduce is worse than no node, so the symlink is load-bearing here.
        Path target = work.resolve("_scaffold-src");
        Files.createDirectories(target);
        Files.writeString(target.resolve("build.gradle.kts"), "// scaffold source, v1\n");
        Files.createSymbolicLink(tg.resolve("build-logic"), Path.of("..", "_scaffold-src"));

        git(work, "init", "--initial-branch=main");
        git(work, "config", "user.email", "sync-settles@test.invalid");
        git(work, "config", "user.name", "sync-settles");
        git(work, "add", "-A");
        git(work, "commit", "-m", "fixture " + UNIT);

        Path bare = root.resolve(UNIT + ".git");
        git(root, "clone", "--bare", work.toString(), bare.toString());
        git(work, "remote", "add", "origin", bare.toString());
        git(work, "fetch", "origin");
        git(work, "branch", "--set-upstream-to=origin/main", "main");
        return work;
    }

    // ------------------------------------------------------------- plumbing

    private static ProcessRecord sm(NodeContext ctx, String label, Path home, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add(SmEnv.cli().toString());
        argv.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(argv);
        SmEnv.apply(ctx, pb, home.toString());
        return Procs.run(ctx, label, pb);
    }

    private static String recordedGitHash(Path home) throws IOException {
        return field(home, "gitHash");
    }

    private static String recordedErrors(Path home) throws IOException {
        return field(home, "errors");
    }

    /** Deliberately string-scraped: the node must not depend on a JSON library. */
    private static String field(Path home, String key) throws IOException {
        Path record = home.resolve("installed").resolve(UNIT + ".json");
        if (!Files.isRegularFile(record)) return null;
        String json = Files.readString(record);
        int at = json.indexOf("\"" + key + "\"");
        if (at < 0) return null;
        int colon = json.indexOf(':', at);
        if (colon < 0) return null;
        int end = json.indexOf('\n', colon);
        if (end < 0) end = json.length();
        String raw = json.substring(colon + 1, end).trim();
        // Strip a trailing comma FIRST, then the surrounding quotes. Doing it
        // in one pass with a single-character class leaves the closing quote
        // behind on `"abc",` and the value silently stops matching anything --
        // which produced a red on this node whose message printed the two
        // hashes as identical. A comparison that cannot fail loudly when its
        // own parser is wrong is not a comparison.
        if (raw.endsWith(",")) raw = raw.substring(0, raw.length() - 1).trim();
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            raw = raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) return "(none)";
        return hash.length() > 8 ? hash.substring(0, 8) : hash;
    }

    private static void git(Path cwd, String... argv) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(argv));
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile())
                .redirectErrorStream(true).start();
        if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("git " + String.join(" ", argv) + " timed out in " + cwd);
        }
        if (p.exitValue() != 0) {
            String out = new String(p.getInputStream().readAllBytes());
            throw new IOException("git " + String.join(" ", argv) + " failed in " + cwd + ": " + out);
        }
    }

    private static String capture(Path cwd, String... argv) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(List.of(argv)).directory(cwd.toFile()).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
        return out;
    }
}
