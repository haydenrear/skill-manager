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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A unit carrying a dereferenced in-unit store link stays syncable — and a sync
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
 *   has extra local changes (working tree edits, or commits ahead of the
 *   installed baseline) — sync would overwrite them.
 *   re-run with: skill-manager sync &lt;unit&gt; --merge
 * </pre>
 *
 * <p>and, when that remedy was run,
 *
 * <pre>
 *   is not stale — its store is mid-merge (MERGE_CONFLICT): unmerged paths
 *   remain. Local work is preserved at stash@{0}.
 *   resolve with: git -C .../skills/&lt;unit&gt; status  # resolve, then: git add + git commit
 * </pre>
 *
 * <p>Neither remedy cleared either state. Every agent in every worktree cloned
 * from that home inherited both. Releasing them took removing the scaffold
 * trees by hand and then {@code sync --merge}, which is not a remedy anything
 * can print.
 *
 * <h2>The chain, which is four links and not one</h2>
 *
 * <ol>
 *   <li><b>The trigger.</b> The test-graph scaffolder writes
 *       {@code test_graph/build-logic}, {@code sdk} and {@code standard-nodes}
 *       into a consuming unit as symlinks into the test-graph store copy, and
 *       ALSO generates the {@code .gitignore} that declares all three
 *       not-content ({@code ensure_provider_binding_ignores},
 *       {@code skills/test_graph/scripts/_common.py:318-346}).
 *       {@code ChildHomeMaterializer} dereferences them into real directories
 *       so the child home is independent (CHM-5) — correct — and the unit's
 *       repository still tracks those paths at mode {@code 120000}.</li>
 *   <li><b>The mechanism.</b> {@code git status} reads the dereference as a
 *       DELETION, so every sync refuses. Forced through with {@code --merge},
 *       sync stashes, merges — <em>the merge commit lands</em> — then pops, and
 *       the pop conflicts, because the working tree cannot hold both a
 *       directory and a symlink at one path.</li>
 *   <li><b>The permanence, and the load-bearing link.</b> The merge is already
 *       committed but {@code installed/<unit>.json} was never updated. Measured:
 *       record {@code gitHash c72d03a6} written 2026-07-25, store {@code HEAD}
 *       at {@code eab28837}. HEAD is now ahead of the baseline <em>forever</em>,
 *       so every later sync reports "commits ahead of the installed baseline".
 *       Fixing links 1 and 2 alone does not release a home already in this
 *       state.</li>
 *   <li><b>The invisibility.</b> The {@code errors[{kind: MERGE_CONFLICT}]}
 *       entry carries no {@code resolvedAt}, and the residue it names — an
 *       abandoned {@code stash@{0}} and a baseline behind HEAD — is not what
 *       its own probe asks about.</li>
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
 * <h2>THE TIER IS THE FIXTURE. Read this before changing it.</h2>
 *
 * <p>An earlier version of this node drove a <b>root-tier</b> {@code install}
 * then {@code sync} and <b>passed</b>, which was worse than a red node: a green
 * {@code sync-settles} in a nightly summary reads as "the drag is covered". It
 * passed because <b>the root tier never dereferences anything</b>. Only
 * {@code ChildHomeMaterializer} does, and it only runs when a home is
 * materialized FROM another home. Two reproductions were tried at root tier and
 * ruled out by measurement — an ordinary gitignored directory that never
 * existed upstream (git ignores it: no stash, no pop, no conflict), and a
 * tracked upstream symlink dereferenced by hand with upstream re-pointing it
 * (merges cleanly) — and they are recorded so nobody re-spends them.
 *
 * <p>So this fixture is a PROJECT-tier one and every ingredient below is
 * load-bearing:
 *
 * <ul>
 *   <li>A <b>provider unit</b> installed alongside the consumer, because the
 *       link has to point at something that is really in the parent store.
 *       {@code ChildHomeMaterializer.walk} dereferences only links whose target
 *       resolves INSIDE the parent store and OUTSIDE the unit; a link to
 *       anywhere else is copied as a link and nothing happens.</li>
 *   <li>The link is <b>tracked at mode {@code 120000}</b>. {@code .gitignore}
 *       has no effect on an already-tracked path, which is exactly the real
 *       units' state — the ignore block arrived with the managed-bindings
 *       migration, after the links were already in history.</li>
 *   <li>{@code project resolve} does the dereference, not the node. A fixture
 *       that mkdir'd the directory itself would be asserting over state the
 *       test wrote, and could never observe the product being fixed.</li>
 *   <li>Upstream <b>re-points</b> a store link the unit's {@code .gitignore}
 *       does NOT cover, which is what turns the refusal into a stash-pop
 *       conflict when the remedy is run. Measured: with only the ignored path
 *       present, {@code --merge} "succeeds" and silently REVERTS the
 *       materialization instead.</li>
 * </ul>
 *
 * <h2>The negative control is half the node</h2>
 *
 * <p>The cheap fix for all of this — "a deleted symlink is never a local
 * change" — silently discards an author's work, which is the CHM-24 shape this
 * epic has already been bitten by once. So phase 2 writes a REAL edit into the
 * child copy and asserts the sync still refuses. A green phase 1 with a green
 * phase 2 is the only combination that means anything.
 *
 * <p>Hermetic: the unit's origin is a local bare clone, so nothing this node
 * declares is fetched. As {@code HomeIntegrityFixture} already records, that
 * does not make it network-free end to end — {@code install} runs
 * {@code EnsureGateway} — and overstating it would be the kind of comfortable
 * claim this epic keeps having to retract.
 */
public class ScaffoldTreeDoesNotStrandTheBaseline {

    static final NodeSpec SPEC = NodeSpec.of("sync.settles.scaffold.tree.does.not.strand.baseline")
            .kind(NodeSpec.Kind.ASSERTION)
            .tags("sync", "home", "child-home", "rederivable", "his-4")
            .timeout("900s");

    /** The unit under test. Named for what it carries, not for what breaks. */
    private static final String UNIT = "scaffolded-unit";

    /** The unit the store links point INTO. Without it nothing is dereferenced. */
    private static final String PROVIDER = "scaffold-provider";

    /**
     * The gitignored scaffold tree, under {@code test_graph/} and named exactly
     * as the real scaffolder names it. The name matters: a fix that
     * special-cases {@code build} but not {@code build-logic} is the gap this
     * pins.
     */
    private static final String SCAFFOLD_DIR = "test_graph/build-logic";

    /**
     * A store link the unit's own {@code .gitignore} does NOT cover. This is
     * the one upstream re-points, and it is what makes the deadlock reachable:
     * git can see both sides of it, so the stash and the merge collide.
     */
    private static final String OPEN_LINK = "shared-docs";

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

        Path provider = scaffoldProvider(scratch);
        Path work = scaffoldUnit(scratch, provider);
        Path bare = scratch.resolve(UNIT + ".git");

        List<String> failures = new ArrayList<>();

        // 1. Install BOTH units into a root home. The provider is what the
        //    consumer's store links point at, so it has to be in the store for
        //    the materializer to recognise them as store links at all.
        ProcessRecord installProvider = sm(ctx, "install-provider", home, "install",
                provider.toString(), "--yes");
        ProcessRecord install = sm(ctx, "install", home, "install", "git+file://" + bare, "--yes");
        if (installProvider.exitCode() != 0 || install.exitCode() != 0) {
            return NodeResult.fail(SPEC.id(),
                    "fixture install failed (provider exit " + installProvider.exitCode()
                            + ", unit exit " + install.exitCode() + ") — the node proves nothing "
                            + "about sync if the subject was never installed");
        }

        // 2. MATERIALIZE. `project resolve` builds the child home, and CHM-5's
        //    dereference is what puts a real directory where the unit's own
        //    repository tracks a symlink. The product does this, not the node.
        Path project = scratch.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("skill-project.toml"), """
                [project]
                name = "sync-settles-project"

                [skills.provider]
                source = "%s"

                [skills.consumer]
                source = "%s"
                """.formatted(home.resolve("skills").resolve(PROVIDER),
                        home.resolve("skills").resolve(UNIT)));

        ProcessRecord resolve = sm(ctx, "project-resolve", home, "project", "resolve",
                "--skip-gateway", "--project-dir", project.toString());
        if (resolve.exitCode() != 0) {
            return NodeResult.fail(SPEC.id(),
                    "project resolve exited " + resolve.exitCode() + "; without a materialized "
                            + "child home there is no dereference and this node is about "
                            + "nothing");
        }

        Path child = project.resolve(".skill-manager");
        Path store = child.resolve("skills").resolve(UNIT);

        // THE VACUITY GUARD. Every assertion below is about a dereferenced
        // store link, so the node fails loudly if the materializer did not make
        // one -- rather than passing over a fixture that reproduced nothing,
        // which is what the root-tier version of this node did.
        for (String rel : List.of(SCAFFOLD_DIR, OPEN_LINK)) {
            Path at = store.resolve(rel);
            if (Files.isSymbolicLink(at) || !Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS)) {
                return NodeResult.fail(SPEC.id(),
                        "the child home did not dereference " + rel + " into a real directory, "
                                + "so the defect this node is named for is not present in the "
                                + "fixture and a pass would mean nothing");
            }
        }
        if (!capture(store, "git", "status", "--porcelain").contains(SCAFFOLD_DIR)) {
            return NodeResult.fail(SPEC.id(),
                    "git does not read the dereference of " + SCAFFOLD_DIR + " as a change, so "
                            + "there is no divergence to be wrong about");
        }

        String baselineBefore = recordedGitHash(child);

        // 3. Upstream moves, exactly as a skill repo does between syncs -- and
        //    re-points the open store link, which is an ordinary scaffolder
        //    change and the thing the child home has dereferenced.
        Files.writeString(work.resolve("SKILL.md"), """
                ---
                name: %s
                description: sync-settles graph fixture, second revision
                ---

                Upstream moved after the scaffold tree was materialized.
                """.formatted(UNIT));
        Files.createDirectories(provider.resolve("shared-docs-v2"));
        Files.writeString(provider.resolve("shared-docs-v2").resolve("NOTE.md"), "shared v2\n");
        Files.delete(work.resolve(OPEN_LINK));
        Files.createSymbolicLink(work.resolve(OPEN_LINK),
                Path.of("..", PROVIDER, "shared-docs-v2"));
        git(work, "add", "-A");
        git(work, "commit", "-m", "upstream moves after the scaffold tree exists");
        git(work, "push", "origin", "main");
        String upstreamHead = capture(work, "git", "rev-parse", "HEAD");

        // 4. The sync an agent runs. NOT --merge: --merge is the remedy the
        //    product PRINTS once it is already stuck, and a node that reaches
        //    for it is asserting the workaround rather than the behaviour.
        ProcessRecord sync = sm(ctx, "sync", child, "sync", UNIT, "--yes");

        if (sync.exitCode() != 0) {
            failures.add("sync exited " + sync.exitCode()
                    + "; a unit whose only divergence is the child home's own dereference must "
                    + "stay syncable at every tier");
        }

        String baselineAfter = recordedGitHash(child);
        if (baselineAfter == null) {
            failures.add("installed record carries no gitHash after sync");
        } else if (!baselineAfter.equals(upstreamHead)) {
            // LINK 3, the load-bearing one.
            failures.add("installed baseline was stranded: record says " + shortHash(baselineAfter)
                    + " (was " + shortHash(baselineBefore) + "), upstream HEAD is "
                    + shortHash(upstreamHead) + ". A merge that lands without its record makes "
                    + "every later sync report 'commits ahead of the installed baseline' forever");
        }

        String head = capture(store, "git", "rev-parse", "HEAD");
        if (!head.equals(baselineAfter == null ? "" : baselineAfter)) {
            failures.add("the store's HEAD (" + shortHash(head) + ") and the installed record ("
                    + shortHash(baselineAfter) + ") disagree — that gap is exactly what makes "
                    + "the state permanent rather than transient");
        }

        String errors = recordedErrors(child);
        if (errors != null && errors.contains("MERGE_CONFLICT")) {
            failures.add("sync recorded a MERGE_CONFLICT over a tree the child home dereferenced "
                    + "for its own independence: " + errors);
        }

        String stashes = capture(store, "git", "stash", "list");
        if (!stashes.isBlank()) {
            // A stash nobody will ever pop is local work the product took and
            // did not give back. It is also what the old remedy's "local
            // changes preserved at stash@{0}" was pointing at.
            failures.add("sync left a stash behind: " + stashes);
        }

        // THE OTHER FAILURE, and worth pinning: excluding a tree from the merge
        // must not become licence to delete it, or to quietly put the symlink
        // back. Both were MEASURED before the fix -- the tree was reverted to a
        // link into the store in one shape and deleted outright in another.
        for (String rel : List.of(SCAFFOLD_DIR, OPEN_LINK)) {
            Path at = store.resolve(rel);
            if (Files.isSymbolicLink(at)) {
                failures.add(rel + " is a symlink again after the sync — the child home's "
                        + "independence was undone by the very command that was supposed to "
                        + "leave it alone");
            } else if (!Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS)) {
                failures.add(rel + " was destroyed by the sync; skipping a path must make it "
                        + "invisible, not disposable");
            }
        }

        // 5. The state an agent actually meets: does the NEXT command settle?
        ProcessRecord second = sm(ctx, "sync-again", child, "sync", UNIT, "--yes");
        if (second.exitCode() != 0) {
            failures.add("a second sync with nothing changed exited " + second.exitCode()
                    + "; the home never reaches a settled state");
        }

        // ------------------------------------------------------------------
        // PHASE 2 — THE NEGATIVE CONTROL.
        //
        // Everything above is satisfied by a "fix" that answers "clean" for any
        // unit holding a dereferenced path, and that fix loses an agent's work.
        // So: put a real edit in the child copy and require the refusal back.
        // ------------------------------------------------------------------
        Files.writeString(store.resolve("SKILL.md"),
                Files.readString(store.resolve("SKILL.md")) + "\nAN AGENT WROTE THIS\n");

        Files.writeString(work.resolve("README.md"), "upstream moves again\n");
        git(work, "add", "-A");
        git(work, "commit", "-m", "upstream moves again");
        git(work, "push", "origin", "main");

        ProcessRecord guarded = sm(ctx, "sync-with-agent-edit", child, "sync", UNIT, "--yes");
        if (guarded.exitCode() == 0) {
            failures.add("a sync ran straight over an agent's edit to SKILL.md — excluding the "
                    + "materialized paths must narrow the question, not answer 'clean' whenever "
                    + "one of them is present");
        }
        if (!Files.readString(store.resolve("SKILL.md")).contains("AN AGENT WROTE THIS")) {
            failures.add("the agent's edit is gone from the child copy");
        }

        // And the remedy the product printed for THAT refusal has to work.
        ProcessRecord remedy = sm(ctx, "printed-remedy", child, "sync", UNIT, "--merge", "--yes");
        if (remedy.exitCode() != 0) {
            failures.add("the printed remedy (`sync --merge`) exited " + remedy.exitCode()
                    + " — a remedy that does not clear the state it is printed for is how this "
                    + "defect stayed alive for nine days");
        }
        if (!Files.readString(store.resolve("SKILL.md")).contains("AN AGENT WROTE THIS")) {
            failures.add("the printed remedy destroyed the agent's edit it was supposed to merge");
        }

        return failures.isEmpty()
                ? NodeResult.pass(SPEC.id())
                : NodeResult.fail(SPEC.id(), String.join(" | ", failures));
    }

    // ----------------------------------------------------------- the fixture

    /** The unit the consumer's store links point into. It only has to be in the store. */
    private static Path scaffoldProvider(Path root) throws IOException {
        Path dir = root.resolve("src").resolve(PROVIDER);
        Files.createDirectories(dir.resolve("project_sdk_sources").resolve("build-logic"));
        Files.createDirectories(dir.resolve("shared-docs"));
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: holds the scaffold source trees the consumer links to
                ---
                """.formatted(PROVIDER));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "holds the scaffold source trees the consumer links to"
                """.formatted(PROVIDER));
        Files.writeString(dir.resolve("project_sdk_sources").resolve("build-logic")
                .resolve("build.gradle.kts"), "// scaffold source, v1\n");
        Files.writeString(dir.resolve("shared-docs").resolve("NOTE.md"), "shared v1\n");
        return dir;
    }

    private static Path scaffoldUnit(Path root, Path provider)
            throws IOException, InterruptedException {
        Path work = root.resolve("src").resolve(UNIT);
        Files.createDirectories(work);

        Files.writeString(work.resolve("SKILL.md"), """
                ---
                name: %s
                description: sync-settles graph fixture
                ---

                A unit whose test_graph project carries a scaffolded store link.
                """.formatted(UNIT));
        Files.writeString(work.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "sync-settles graph fixture"
                """.formatted(UNIT));
        Files.writeString(work.resolve("README.md"), "revision 1\n");

        Path tg = work.resolve("test_graph");
        Files.createDirectories(tg);
        // The unit declares the scaffold tree not-content, in the scaffolder's
        // own words -- captured from ensure_provider_binding_ignores rather than
        // invented, because a hand-written "plausible" .gitignore would be
        // testing the fixture. Note it does NOT untrack build-logic below:
        // .gitignore has no effect on an already-tracked path, which is exactly
        // the real units' state and the reason the mode clash survives.
        Files.writeString(tg.resolve(".gitignore"), """
                **/__pycache__/**

                # TEST-GRAPH-MANAGED-BINDINGS-BEGIN
                # Generated runtime links; provider-bindings.json is the durable record.
                /build-logic
                /sdk
                /standard-nodes
                # TEST-GRAPH-MANAGED-BINDINGS-END
                """);
        Files.writeString(tg.resolve("build.gradle.kts"), "// fixture test_graph project\n");

        // THE SHAPE THAT MATTERS. Both links escape the unit and land inside
        // the PARENT STORE once installed -- `../../<provider>/...` from
        // `skills/<unit>/test_graph/` is `skills/<provider>/...` -- which is
        // precisely what ChildHomeMaterializer.walk() dereferences and what the
        // scaffolder's `os.path.relpath(source, destination.parent)` emits for
        // a workspace-relative provider.
        Files.createSymbolicLink(tg.resolve("build-logic"),
                Path.of("..", "..", PROVIDER, "project_sdk_sources", "build-logic"));
        Files.createSymbolicLink(work.resolve(OPEN_LINK),
                Path.of("..", PROVIDER, "shared-docs"));

        git(work, "init", "--initial-branch=main");
        git(work, "config", "user.email", "sync-settles@test.invalid");
        git(work, "config", "user.name", "sync-settles");
        // -f, because the generated .gitignore already covers build-logic and
        // the real units carry it TRACKED from before the managed-bindings
        // migration. That is the state the measured index stages describe.
        git(work, "add", "-A", "-f");
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
