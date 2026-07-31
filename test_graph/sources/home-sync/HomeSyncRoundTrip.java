///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeSyncSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * CHM-12: the ordinary three-tier round trip, with TWO ticket worktrees, run
 * end to end through the real CLI.
 *
 * <h2>The sequence, and why no shorter one finds it</h2>
 *
 * <pre>
 *   root home holds skills/u/{SKILL.md, shared.md = "ORIGINAL"}
 *   home sync --from R  --to P             new
 *   home sync --from P  --to W1            new
 *   home sync --from P  --to W2            new
 *   (W1 edits SKILL.md; W2 writes shared.md = "WT2-EDIT")
 *   home sync --from W1 --to P --merge     updated  files=[SKILL.md]
 *   home sync --from W2 --to P --merge     merged   files=[shared.md]
 *   home close-out --home W1 --into P      exit 0
 *   home close-out --home W2 --into P      exit 0
 *   rm -rf W1 W2                           the point of no return
 *   home sync --from P  --to R --merge
 *   home sync --from R  --to P --merge     &lt;-- destroyed WT2-EDIT
 * </pre>
 *
 * <p>Every command in that list reported success and {@code clean: true}, and
 * the last one described itself as <em>"1 file(s) taken from the source; local
 * work kept"</em> while deleting the local work. Afterwards {@code WT2-EDIT}
 * existed in NO home. No operator error is involved anywhere in it: it is the
 * documented flow with two tickets instead of one.
 *
 * <h2>The cause, and why this node exists beside the unit test</h2>
 *
 * <p>{@code writeCopyRecord} wrote the SOURCE's entire tree as the
 * destination's new {@code entryDigests} — including paths the reconciliation
 * had DECLINED to write. So after {@code P -> R --merge}, R's record claimed
 * that R and P last shared {@code WT2-EDIT} for {@code shared.md}, a byte R had
 * never held; the next {@code R -> P --merge} read {@code d == b} as "the
 * destination is standing on the base" and overwrote it. That is CHM-10's
 * failure mode reintroduced through the record-WRITE side rather than the
 * base-selection side, and it violates the same one-sentence rule: a
 * reconciliation may destroy bytes in the destination only where it can show
 * the SOURCE passed through them.
 *
 * <p>{@code HomeSyncMergeTest} pins the invariant on the record directly, which
 * is the cheap and general witness. It could not have found this: the unit test
 * would have had to guess the six-command ordering, across four homes with a
 * teardown in the middle, that turns a bad record into a lost byte. So the
 * sequence is asserted where it happens — real homes, real subprocesses, real
 * {@code rm -rf} — and the assertions are about BYTES read back off disk, never
 * about a status string, because "merged" over a reverted file and "merged"
 * over a kept one are the same string.
 *
 * <h2>What is asserted, and what is only measured</h2>
 *
 * <p>Asserted: both worktrees' edits reach the project home; the gate clears
 * for both; and after the full round trip through the root home and back, both
 * edits are still there. Also asserted: no home ever ends up holding a
 * baseline it never stood on — the invariant, restated over the records on disk
 * rather than over a return value.
 *
 * <p>Measured, not asserted: whether the upward pass to the root home settled
 * or reported a conflict. Losing the ability to fast-forward a partially-shared
 * baseline upward is the price of the fix and it is a real one, but pinning
 * today's answer as the expected one would make a later improvement fail this
 * node — and a graph should never be the reason a defect stays.
 */
public class HomeSyncRoundTrip {

    private static final String UNIT = "rt-unit";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.round.trip")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.fixture.built")
            .tags("home-sync", "round-trip", "provenance", "no-destruction")
            .timeout("420s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String workspaceRaw = ctx.get("home.sync.fixture.built", "workspace").orElse(null);
            String ambient = ctx.get("home.sync.fixture.built", "ambientHome").orElse(null);
            if (workspaceRaw == null || ambient == null) {
                return NodeResult.fail("home.sync.round.trip", "missing upstream context");
            }
            Path base = Path.of(workspaceRaw).resolve("round-trip");
            Path root = base.resolve("root");
            Path project = base.resolve("project/.skill-manager");
            Path first = base.resolve("wt1/.skill-manager");
            Path second = base.resolve("wt2/.skill-manager");
            Files.createDirectories(base);

            final String original = "ORIGINAL\n";
            final String wt1Edit = "WT1 IMPROVED THE SKILL\n";
            final String wt2Edit = "WT2-EDIT\n";

            HomeSyncSupport.mkUnit(root, UNIT, "unit v1");
            HomeSyncSupport.write(HomeSyncSupport.unitDir(root, UNIT).resolve("shared.md"),
                    original);

            ProcessRecord seedProject = HomeSyncSupport.sm(ctx, "rt-root-to-project", ambient,
                    "home", "sync", "--from", root.toString(), "--to", project.toString(), "--json");
            ProcessRecord seedFirst = HomeSyncSupport.sm(ctx, "rt-project-to-wt1", ambient,
                    "home", "sync", "--from", project.toString(), "--to", first.toString(), "--json");
            ProcessRecord seedSecond = HomeSyncSupport.sm(ctx, "rt-project-to-wt2", ambient,
                    "home", "sync", "--from", project.toString(), "--to", second.toString(), "--json");
            boolean bothWorktreesStartFromTheProjectsBytes = seedProject.exitCode() == 0
                    && seedFirst.exitCode() == 0 && seedSecond.exitCode() == 0
                    && HomeSyncSupport.read(HomeSyncSupport.unitDir(first, UNIT)
                            .resolve("shared.md")).equals(original)
                    && HomeSyncSupport.read(HomeSyncSupport.unitDir(second, UNIT)
                            .resolve("shared.md")).equals(original);

            // Two tickets, two files, no overlap. This is the ordinary case.
            HomeSyncSupport.write(HomeSyncSupport.unitDir(first, UNIT).resolve("SKILL.md"),
                    wt1Edit);
            HomeSyncSupport.write(HomeSyncSupport.unitDir(second, UNIT).resolve("shared.md"),
                    wt2Edit);

            ProcessRecord upFirst = HomeSyncSupport.sm(ctx, "rt-wt1-to-project", ambient,
                    "home", "sync", "--from", first.toString(), "--to", project.toString(),
                    "--merge", "--json");
            ProcessRecord upSecond = HomeSyncSupport.sm(ctx, "rt-wt2-to-project", ambient,
                    "home", "sync", "--from", second.toString(), "--to", project.toString(),
                    "--merge", "--json");
            Map<String, Object> secondReport = HomeSyncSupport.json(ctx, "rt-wt2-to-project");
            boolean bothTicketsReachTheProjectHome = upFirst.exitCode() == 0
                    && upSecond.exitCode() == 0
                    && HomeSyncSupport.read(HomeSyncSupport.unitDir(project, UNIT)
                            .resolve("SKILL.md")).equals(wt1Edit)
                    && HomeSyncSupport.read(HomeSyncSupport.unitDir(project, UNIT)
                            .resolve("shared.md")).equals(wt2Edit);

            // The record the second merge wrote, on disk. THE invariant: a
            // record may name a path as shared only where both homes are
            // standing on that byte. This is read before the worktrees are
            // removed, because afterwards there is nothing to compare against.
            List<String> unsharedClaims = unsharedClaims(project);
            boolean theRecordClaimsNoByteTheSecondWorktreeAndProjectDoNotBothHold =
                    unsharedClaims.isEmpty();

            ProcessRecord gateFirst = HomeSyncSupport.sm(ctx, "rt-close-out-wt1", ambient,
                    "home", "close-out", "--home", first.toString(), "--into", project.toString(),
                    "--json");
            ProcessRecord gateSecond = HomeSyncSupport.sm(ctx, "rt-close-out-wt2", ambient,
                    "home", "close-out", "--home", second.toString(), "--into", project.toString(),
                    "--json");
            boolean bothGatesClear = gateFirst.exitCode() == 0 && gateSecond.exitCode() == 0
                    && HomeSyncSupport.flag(HomeSyncSupport.json(ctx, "rt-close-out-wt1"), "safe")
                    && HomeSyncSupport.flag(HomeSyncSupport.json(ctx, "rt-close-out-wt2"), "safe");

            // The operator acts on the verdict. From here the project home is
            // the only place either ticket's bytes exist.
            HomeSyncSupport.deleteTree(base.resolve("wt1"));
            HomeSyncSupport.deleteTree(base.resolve("wt2"));
            boolean theWorktreesAreReallyGone = !Files.exists(first) && !Files.exists(second);

            ProcessRecord upRoot = HomeSyncSupport.sm(ctx, "rt-project-to-root", ambient,
                    "home", "sync", "--from", project.toString(), "--to", root.toString(),
                    "--merge", "--json");
            String upRootStatus = HomeSyncSupport.status(
                    HomeSyncSupport.json(ctx, "rt-project-to-root"), "skill:" + UNIT);
            ProcessRecord downProject = HomeSyncSupport.sm(ctx, "rt-root-to-project-again", ambient,
                    "home", "sync", "--from", root.toString(), "--to", project.toString(),
                    "--merge", "--json");
            String downStatus = HomeSyncSupport.status(
                    HomeSyncSupport.json(ctx, "rt-root-to-project-again"), "skill:" + UNIT);

            String sharedAfter = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(project, UNIT).resolve("shared.md"));
            String skillAfter = HomeSyncSupport
                    .read(HomeSyncSupport.unitDir(project, UNIT).resolve("SKILL.md"));

            // Bytes. Before the fix sharedAfter was "ORIGINAL" and the command
            // that made it so reported clean=true and "local work kept".
            boolean theSecondTicketsEditSurvivesTheRoundTrip = sharedAfter.equals(wt2Edit);
            boolean theFirstTicketsEditSurvivesTheRoundTrip = skillAfter.equals(wt1Edit);
            // Somewhere, not nowhere: the strongest form of the claim, because
            // the defect's signature was a byte that existed in no home at all.
            boolean theEditExistsInAtLeastOneSurvivingHome =
                    sharedAfter.equals(wt2Edit)
                            || HomeSyncSupport.read(HomeSyncSupport.unitDir(root, UNIT)
                                    .resolve("shared.md")).equals(wt2Edit);

            List<String> rootClaims = unsharedClaims(root);
            List<String> projectClaims = unsharedClaims(project);
            boolean noHomeEndsUpClaimingAByteItNeverStoodOn =
                    rootClaims.isEmpty() && projectClaims.isEmpty();

            boolean noStagingLeftovers = HomeSyncSupport.stagingLeftovers(project).isEmpty()
                    && HomeSyncSupport.stagingLeftovers(root).isEmpty();

            boolean pass = bothWorktreesStartFromTheProjectsBytes && bothTicketsReachTheProjectHome
                    && theRecordClaimsNoByteTheSecondWorktreeAndProjectDoNotBothHold
                    && bothGatesClear && theWorktreesAreReallyGone
                    && theSecondTicketsEditSurvivesTheRoundTrip
                    && theFirstTicketsEditSurvivesTheRoundTrip
                    && theEditExistsInAtLeastOneSurvivingHome
                    && noHomeEndsUpClaimingAByteItNeverStoodOn && noStagingLeftovers;
            return (pass
                    ? NodeResult.pass("home.sync.round.trip")
                    : NodeResult.fail("home.sync.round.trip",
                            "seeded=" + bothWorktreesStartFromTheProjectsBytes
                                    + " reachedProject=" + bothTicketsReachTheProjectHome
                                    + " unsharedClaimsAfterMerge=" + unsharedClaims
                                    + " gates=" + gateFirst.exitCode() + "/" + gateSecond.exitCode()
                                    + " upRoot=" + upRootStatus + " down=" + downStatus
                                    + " sharedAfter=" + sharedAfter.strip()
                                    + " skillAfter=" + skillAfter.strip()
                                    + " rootClaims=" + rootClaims
                                    + " projectClaims=" + projectClaims))
                    .process(seedProject).process(seedFirst).process(seedSecond)
                    .process(upFirst).process(upSecond).process(gateFirst).process(gateSecond)
                    .process(upRoot).process(downProject)
                    .log("upward pass to the root home reported: " + upRootStatus
                            + "; the return pass reported: " + downStatus
                            + ". Measured, not asserted — a partially shared baseline conflicts "
                            + "rather than fast-forwarding, which is the direction the baseline "
                            + "rule errs in on purpose.")
                    .assertion("both_worktrees_start_from_the_project_homes_bytes",
                            bothWorktreesStartFromTheProjectsBytes)
                    .assertion("both_tickets_edits_reach_the_project_home",
                            bothTicketsReachTheProjectHome)
                    .assertion("a_merge_claims_no_baseline_the_two_homes_do_not_both_hold",
                            theRecordClaimsNoByteTheSecondWorktreeAndProjectDoNotBothHold)
                    .assertion("close_out_clears_both_worktrees_for_teardown", bothGatesClear)
                    .assertion("the_worktree_homes_really_are_removed", theWorktreesAreReallyGone)
                    .assertion("the_second_tickets_edit_survives_the_whole_round_trip",
                            theSecondTicketsEditSurvivesTheRoundTrip)
                    .assertion("the_first_tickets_edit_survives_the_whole_round_trip",
                            theFirstTicketsEditSurvivesTheRoundTrip)
                    .assertion("the_edit_exists_in_at_least_one_surviving_home",
                            theEditExistsInAtLeastOneSurvivingHome)
                    .assertion("no_home_records_a_baseline_it_never_stood_on",
                            noHomeEndsUpClaimingAByteItNeverStoodOn)
                    .assertion("neither_home_carries_staging_leftovers", noStagingLeftovers)
                    .metric("upwardPassSettledWithoutAHuman", "merged".equals(upRootStatus)
                            || "updated".equals(upRootStatus) ? 1 : 0)
                    .metric("unsharedBaselineClaims",
                            unsharedClaims.size() + rootClaims.size() + projectClaims.size());
        });
    }

    /**
     * Paths {@code home}'s materialization record claims as a shared baseline
     * that {@code home} and <em>the home its own record names</em> are not both
     * standing on.
     *
     * <p>Read straight out of {@code .materialization/skill/&lt;unit&gt;.json}
     * rather than through any API, because the defect was in what got written
     * there and a reader that went back through the same code that wrote it
     * would agree with itself. The pair to compare against is taken from the
     * record's own {@code source} field rather than passed in: a record is
     * evidence about ONE pair of homes, and checking it against some other home
     * would be asking a question it never claimed to answer. Empty is the
     * invariant.
     *
     * <p>A record naming a home that has since been torn down is skipped, not
     * failed. Its claim may well have been true when it was written and there
     * is nothing left to check it against — which is precisely why the claim
     * had to be sound at write time, and why the assertion that matters most
     * runs BEFORE the teardown.
     */
    private static List<String> unsharedClaims(Path home) {
        String json = HomeSyncSupport.read(home.resolve(".materialization/skill/" + UNIT + ".json"));
        Object parsed = HomeSyncSupport.MiniJson.parse(json);
        if (!(parsed instanceof Map<?, ?> record)) return List.of();
        Object entries = record.get("entryDigests");
        Object named = record.get("source");
        if (!(entries instanceof Map<?, ?> claimed) || named == null) return List.of();
        Path sourceUnit = Path.of(String.valueOf(named));
        if (!Files.isDirectory(sourceUnit)) return List.of();
        List<String> bad = new java.util.ArrayList<>();
        for (Map.Entry<?, ?> entry : claimed.entrySet()) {
            String rel = String.valueOf(entry.getKey());
            String here = HomeSyncSupport.read(HomeSyncSupport.unitDir(home, UNIT).resolve(rel));
            String there = HomeSyncSupport.read(sourceUnit.resolve(rel));
            if (!here.equals(there)) bad.add(rel + " (this home has " + here.strip()
                    + ", the home its record names has " + there.strip() + ")");
        }
        return bad;
    }
}
