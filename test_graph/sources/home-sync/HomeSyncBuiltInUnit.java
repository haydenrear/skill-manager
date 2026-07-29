///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeSyncSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>A worktree that was USED.</b> The one thing a ticket worktree exists to do
 * — run the tooling inside it — used to make its own teardown impossible.
 *
 * <h2>What was measured, on three real repositories</h2>
 *
 * <p>Running {@code discover.py} once left build output INSIDE a unit:
 * {@code skills/test-graph/project_sdk_sources/build-logic/.gradle/**} and
 * {@code scripts/__pycache__/*.pyc}, 6, 5 and 5 files respectively. The unit's
 * digest moved, so {@code home close-out} returned {@code conflicted} with a
 * remedy nobody can act on —
 * {@code home sync --merge  (then resolve: executionHistory.bin, …)} — running
 * that remedy verbatim exited 1 without clearing the gate, and
 * {@code home sync --merge} then reported that nothing was written. The unit
 * stayed unreconciled forever. A built jar
 * ({@code validation-graph-build-logic-0.1.0.jar}) was separately carried
 * worktree → project by a merge as though it were somebody's work. Issue #41.
 *
 * <h2>Why this is a graph node and not a unit test</h2>
 *
 * <p>Because the fixtures are what missed it. A unit test writes the files it
 * wants to see, so it writes the files somebody thought of; this node RUNS a
 * script and asserts about whatever the run happened to leave behind. The
 * {@code .pyc} below is authored by CPython, at a path naming a version this
 * file does not know, and the assertions never name it — they measure that the
 * unit is reported UNCHANGED and that the gate clears, whatever is in there.
 *
 * <h2>Four claims, and the last one is the guard on the first three</h2>
 *
 * <ol>
 *   <li>A worktree that has only been BUILT in is reported {@code unchanged},
 *       and {@code home close-out} clears its teardown.</li>
 *   <li>The build output does not travel: the project home never receives the
 *       jar, the {@code .gradle} tree or the {@code .pyc}.</li>
 *   <li>An upstream refresh that rewrites the unit around it does not DELETE
 *       it. "Not mine to compare" and "mine to destroy" cannot both be true of
 *       the same bytes, and a refresh that silently rebuilt a venv would train
 *       whoever hit it to stop running refreshes.</li>
 *   <li><b>A unit the agent COMMITTED in still blocks.</b> Issue #29: a
 *       {@code .git} directory churns exactly like the entries above, so every
 *       instinct that skips one says to skip the other — and the commits exist
 *       in that home and nowhere else, so a unit whose {@code .git} was skipped
 *       would read as unmodified and the teardown would take them with it. If
 *       claim 4 ever passes by accident, claims 1–3 have become a data-loss
 *       bug rather than a fix.</li>
 * </ol>
 */
public class HomeSyncBuiltInUnit {

    private static final String BUILT = "b-built";
    private static final String COMMITTED = "b-committed";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.built.in.unit")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.fixture.built")
            .tags("home-sync", "build-output", "close-out", "issue-41")
            .timeout("420s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String workspaceRaw = ctx.get("home.sync.fixture.built", "workspace").orElse(null);
            String ambient = ctx.get("home.sync.fixture.built", "ambientHome").orElse(null);
            if (workspaceRaw == null || ambient == null) {
                return NodeResult.fail("home.sync.built.in.unit", "missing upstream context");
            }
            Path base = Path.of(workspaceRaw).resolve("built-in-unit");
            Path project = base.resolve("project/.skill-manager");
            Path worktree = base.resolve("worktree/.skill-manager");
            Files.createDirectories(base);

            // A unit shaped like the one the defect was found in: a script that
            // imports a sibling module (so CPython writes bytecode beside it)
            // and an authored build-logic directory for the build tool to put
            // its cache in.
            HomeSyncSupport.mkUnit(project, BUILT, "the unit a worktree builds in");
            HomeSyncSupport.write(project.resolve("skills/" + BUILT + "/scripts/helper.py"),
                    "VALUE = 'imported'\n");
            HomeSyncSupport.write(project.resolve("skills/" + BUILT + "/scripts/discover.py"),
                    DISCOVER_PY);
            HomeSyncSupport.write(project.resolve(
                            "skills/" + BUILT + "/project_sdk_sources/build-logic/build.gradle.kts"),
                    "// authored build logic\n");
            HomeSyncSupport.mkUnit(project, COMMITTED, "the unit an agent commits in");

            // A real clone, so the worktree carries the baselines a real one has.
            ProcessRecord clone = HomeSyncSupport.sm(ctx, "built-clone", ambient,
                    "home", "clone", "--from", project.toString(), "--to", worktree.toString(),
                    "--json");
            boolean cloned = clone.exitCode() == 0;

            // ---- 1. RUN the tooling, exactly as a ticket agent would ---------
            Path builtUnit = worktree.resolve("skills/" + BUILT);
            ProcessBuilder pb = new ProcessBuilder("python3", "scripts/discover.py");
            pb.directory(builtUnit.toFile());
            // Two inherited variables would quietly make this node vacuous:
            // PYTHONDONTWRITEBYTECODE stops the .pyc being written at all, and
            // PYTHONPYCACHEPREFIX redirects it outside the unit. Either leaves
            // a node that runs a script, finds no bytecode, and asserts nothing
            // about the case it exists for. Cleared rather than tolerated.
            pb.environment().remove("PYTHONDONTWRITEBYTECODE");
            pb.environment().remove("PYTHONPYCACHEPREFIX");
            ProcessRecord discover = Procs.run(ctx, "built-discover", pb);

            List<String> derived = derivedArtifacts(builtUnit);
            // Each of the three shapes the defect was reported against, and the
            // .pyc named separately because it is the one the NODE did not
            // write: CPython chose its path and its filename.
            boolean theRunLeftBuildOutputInTheUnit = discover.exitCode() == 0
                    && derived.stream().anyMatch(p -> p.endsWith(".pyc"))
                    && derived.stream().anyMatch(p -> p.contains("/.gradle/"))
                    && derived.stream().anyMatch(p -> p.startsWith("build/"));

            LinkedHashMap<String, String> worktreeAfterBuild =
                    HomeSyncSupport.entryDigests(builtUnit);

            ProcessRecord closeOut = HomeSyncSupport.sm(ctx, "built-close-out", ambient,
                    "home", "close-out", "--home", worktree.toString(),
                    "--into", project.toString(), "--json");
            Map<String, Object> verdict = HomeSyncSupport.json(ctx, "built-close-out");
            // The defect returned `conflicted` here with a remedy that exited 1.
            boolean aBuiltInWorktreeIsReportedUnchanged =
                    HomeSyncSupport.status(verdict, "skill:" + BUILT).equals("unchanged");
            boolean theTeardownGateClears = closeOut.exitCode() == 0
                    && HomeSyncSupport.flag(verdict, "safe")
                    && HomeSyncSupport.blockerCount(verdict) == 0;

            // ---- 2. and none of it travels ----------------------------------
            ProcessRecord up = HomeSyncSupport.sm(ctx, "built-sync-up", ambient,
                    "home", "sync", "--from", worktree.toString(), "--to", project.toString(),
                    "--json");
            List<String> projectDerived = derivedArtifacts(project.resolve("skills/" + BUILT));
            boolean noBuildOutputReachesTheProjectHome =
                    up.exitCode() == 0 && projectDerived.isEmpty();

            // ---- 3. and a refresh does not delete it ------------------------
            HomeSyncSupport.append(project.resolve("skills/" + BUILT + "/SKILL.md"),
                    "improved in the project home after the branch\n");
            ProcessRecord down = HomeSyncSupport.sm(ctx, "built-sync-down", ambient,
                    "home", "sync", "--from", project.toString(), "--to", worktree.toString(),
                    "--json");
            Map<String, Object> downReport = HomeSyncSupport.json(ctx, "built-sync-down");
            boolean theUpstreamChangeArrived =
                    HomeSyncSupport.status(downReport, "skill:" + BUILT).equals("updated")
                            && HomeSyncSupport.read(builtUnit.resolve("SKILL.md"))
                                    .contains("improved in the project home after the branch");
            // Every derived path the run produced, byte for byte, after a pass
            // that rewrote the unit around it.
            List<String> derivedLost = new java.util.ArrayList<>();
            for (String rel : derived) {
                String was = worktreeAfterBuild.get(rel);
                String now = HomeSyncSupport.entryDigests(builtUnit).get(rel);
                if (was != null && !was.equals(now)) derivedLost.add(rel);
            }
            boolean theBuildOutputSurvivedTheRefresh = derivedLost.isEmpty();

            // ---- 4. THE GUARD: committed work still blocks ------------------
            Path committedUnit = worktree.resolve("skills/" + COMMITTED);
            HomeSyncSupport.git(committedUnit, "init", "-b", "main");
            HomeSyncSupport.write(committedUnit.resolve("AGENT.md"),
                    "work the agent committed and never pushed\n");
            HomeSyncSupport.git(committedUnit, "add", "-A");
            HomeSyncSupport.git(committedUnit, "-c", "user.email=graph@localhost",
                    "-c", "user.name=graph", "commit", "-m", "agent work");
            String committedHead = HomeSyncSupport.git(committedUnit, "rev-parse", "HEAD").trimmed();

            ProcessRecord guarded = HomeSyncSupport.sm(ctx, "built-close-out-committed", ambient,
                    "home", "close-out", "--home", worktree.toString(),
                    "--into", project.toString(), "--json");
            Map<String, Object> guardedVerdict =
                    HomeSyncSupport.json(ctx, "built-close-out-committed");
            boolean aCommittedUnitStillBlocksTheTeardown = guarded.exitCode() != 0
                    && !HomeSyncSupport.flag(guardedVerdict, "safe")
                    && !HomeSyncSupport.blocker(guardedVerdict, "skill:" + COMMITTED).isEmpty();
            boolean theCommitIsStillThere = !committedHead.isBlank()
                    && HomeSyncSupport.git(committedUnit, "rev-parse", "HEAD").trimmed()
                            .equals(committedHead);

            boolean pass = cloned && theRunLeftBuildOutputInTheUnit
                    && aBuiltInWorktreeIsReportedUnchanged && theTeardownGateClears
                    && noBuildOutputReachesTheProjectHome && theUpstreamChangeArrived
                    && theBuildOutputSurvivedTheRefresh && aCommittedUnitStillBlocksTheTeardown
                    && theCommitIsStillThere;

            return (pass
                    ? NodeResult.pass("home.sync.built.in.unit")
                    : NodeResult.fail("home.sync.built.in.unit",
                            "cloned=" + cloned
                                    + " discoverExit=" + discover.exitCode()
                                    + " derived=" + derived
                                    + " builtStatus="
                                    + HomeSyncSupport.status(verdict, "skill:" + BUILT)
                                    + " closeOutExit=" + closeOut.exitCode()
                                    + " blockers=" + HomeSyncSupport.blockerCount(verdict)
                                    + " projectDerived=" + projectDerived
                                    + " downStatus="
                                    + HomeSyncSupport.status(downReport, "skill:" + BUILT)
                                    + " derivedLost=" + derivedLost
                                    + " guardedExit=" + guarded.exitCode()
                                    + " guardedSafe=" + HomeSyncSupport.flag(guardedVerdict, "safe")))
                    .process(clone).process(discover).process(closeOut).process(up)
                    .process(down).process(guarded)
                    .assertion("running_the_tooling_really_does_leave_build_output_in_the_unit",
                            theRunLeftBuildOutputInTheUnit)
                    .assertion("a_worktree_that_was_only_built_in_is_reported_unchanged",
                            aBuiltInWorktreeIsReportedUnchanged)
                    .assertion("and_the_teardown_gate_clears_instead_of_conflicting",
                            theTeardownGateClears)
                    .assertion("no_build_output_reaches_the_project_home",
                            noBuildOutputReachesTheProjectHome)
                    .assertion("an_upstream_change_still_reaches_the_worktree",
                            theUpstreamChangeArrived)
                    .assertion("and_the_refresh_keeps_every_derived_byte_it_does_not_own",
                            theBuildOutputSurvivedTheRefresh)
                    .assertion("a_unit_the_agent_committed_in_still_blocks_the_teardown",
                            aCommittedUnitStillBlocksTheTeardown)
                    .assertion("and_its_commit_is_untouched", theCommitIsStillThere)
                    .metric("derivedArtifacts", derived.size());
        });
    }

    /**
     * Every derived path under {@code unit}, discovered rather than named.
     *
     * <p>Naming them would defeat the point: a {@code .pyc}'s filename carries
     * the CPython version of whatever interpreter ran, and the whole reason
     * this is a graph node is that the assertions must be about what the run
     * actually left behind.
     */
    private static List<String> derivedArtifacts(Path unit) throws java.io.IOException {
        List<String> out = new java.util.ArrayList<>();
        if (!Files.isDirectory(unit)) return out;
        for (Map.Entry<String, String> entry : HomeSyncSupport.entryDigests(unit).entrySet()) {
            String rel = entry.getKey().replace('\\', '/');
            if (!entry.getValue().startsWith("F:")) continue;
            for (String segment : rel.split("/")) {
                if (segment.equals("__pycache__") || segment.equals(".gradle")
                        || segment.equals("build") || rel.endsWith(".pyc")) {
                    out.add(rel);
                    break;
                }
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * Stands in for the repository's own {@code discover.py}: it imports a
     * sibling module, which is what makes CPython write real bytecode into
     * {@code scripts/__pycache__/}, and it writes the two artefacts a Gradle
     * build leaves behind. The SCRIPT writes them, not the node — a node that
     * wrote them itself would be asserting about its own fixture.
     */
    private static final String DISCOVER_PY = """
            import os
            import sys

            # Apple's /usr/bin/python3 ships with sys.pycache_prefix pointing at
            # ~/Library/Caches/com.apple.python, so bytecode lands OUTSIDE the
            # tree and this node would run a script, find no .pyc, and quietly
            # assert nothing about the case it exists for. Cleared here rather
            # than in the node's environment because it is not an env var: it is
            # a property of that interpreter. The repositories issue #41 was
            # reported against run a uv-managed CPython, which writes in-tree.
            sys.pycache_prefix = None

            sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
            import helper  # noqa: F401  -- the import is the point: it writes __pycache__

            unit = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
            cache = os.path.join(unit, "project_sdk_sources", "build-logic",
                                 ".gradle", "8.5", "executionHistory")
            os.makedirs(cache, exist_ok=True)
            with open(os.path.join(cache, "executionHistory.bin"), "wb") as f:
                f.write(b"\\x00gradle task input paths, absolute\\x00")

            libs = os.path.join(unit, "build", "libs")
            os.makedirs(libs, exist_ok=True)
            with open(os.path.join(libs, "validation-graph-build-logic-0.1.0.jar"), "wb") as f:
                f.write(b"PK\\x03\\x04 not really a jar")

            print("discovered", helper.VALUE)
            print("bytecode at", getattr(helper, "__cached__", None))
            """;
}
