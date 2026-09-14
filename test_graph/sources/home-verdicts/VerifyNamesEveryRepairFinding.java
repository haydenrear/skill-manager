///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeVerdictsSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * OHV-2 (a), #339: <b>{@code home verify} fails on whatever {@code home repair}
 * finds, and names every finding.</b>
 *
 * <p>GOAL-one-verdict clause (1) as a set equality rather than one shape at a
 * time: five kinds planted in ONE home (frozen shim, unstamped pm tree,
 * misanchored agent link, dangling agent link, orphaned projection record).
 * Every {@code (kind, subject)} pair that {@code home repair --json} reports —
 * parsed, not planted-list-derived, so a finding nobody planted is held to the
 * same rule — must appear in {@code home verify}'s output as
 * {@code "<KIND> <subject>"}, and verify must exit 1.
 *
 * <p>Then the remedy verify PRINTS is the one that clears it: the
 * {@code complete it with:} span is the same line {@code HomeFixpointLaw} runs.
 * This node checks that span names {@code home repair --home <subject> --fix},
 * runs that repair, and requires both readers to come back clean.
 *
 * <p>CONTROL: verify on the unplanted home exits 0. Without it, "verify exits 1"
 * would be satisfied by a verify that fails every home.
 */
public class VerifyNamesEveryRepairFinding {

    static final NodeSpec SPEC = NodeSpec.of("home.verdicts.verify.names.every.repair.finding")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(HomeVerdictsSupport.FIXTURE)
            .tags("home", "verdicts", "verify", "ohv-2")
            .timeout("600s");

    static final List<String> PLANTED_KINDS = List.of(
            "FROZEN_HOME_PATH_IN_SHIM", "UNSTAMPED_PM_TREE", "MISANCHORED_AGENT_LINK",
            "DANGLING_AGENT_LINK", "ORPHANED_PROJECTION_RECORD");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String scratchStr = ctx.get(HomeVerdictsSupport.FIXTURE, "scratchRoot").orElse(null);
            if (scratchStr == null) {
                return NodeResult.fail(SPEC.id(), "missing " + HomeVerdictsSupport.FIXTURE + " context");
            }
            Path work = Path.of(scratchStr).resolve("verify-names-every-finding");
            NodeResult result;
            try {
                HomeVerdictsSupport.deleteRecursively(work);
                Path subject = HomeVerdictsSupport.layOutHome(work.resolve("subject"));
                Path neighbour = HomeVerdictsSupport.layOutHome(work.resolve("neighbour"));
                HomeVerdictsSupport.holdUnit(subject);
                HomeVerdictsSupport.holdUnit(neighbour);

                var control = HomeVerdictsSupport.verify(ctx, "control-verify", subject);

                // The five plants, each the same bytes its single-shape node plants.
                Path tool = HomeVerdictsSupport.venvTool(subject, "frozen", "frozen-tool");
                HomeVerdictsSupport.literalShim(subject, "frozen-tool", tool);
                Path pmBin = Files.createDirectories(subject.resolve("pm/node/22.9.0/bin"));
                Files.writeString(pmBin.resolve("node"), "#!/bin/sh\nexit 0\n");
                Path mis = subject.getParent().resolve(".claude/skills").resolve(HomeVerdictsSupport.UNIT);
                Files.deleteIfExists(mis);
                Files.createSymbolicLink(mis, neighbour.resolve("skills").resolve(HomeVerdictsSupport.UNIT));
                Files.createSymbolicLink(subject.getParent().resolve(".gemini/skills/hv-gone"),
                        subject.resolve("skills").resolve("hv-gone"));
                HomeVerdictsSupport.orphanRecord(subject, "hv-orphan");

                var repair = HomeVerdictsSupport.repairJson(ctx, "repair", subject);
                var verify = HomeVerdictsSupport.verify(ctx, "verify", subject);
                HomeVerdictsSupport.RepairReport report = HomeVerdictsSupport.report(repair);

                Set<String> reportedKinds = new LinkedHashSet<>();
                List<String> unnamed = new ArrayList<>();
                for (HomeVerdictsSupport.Finding f : report.findings()) {
                    reportedKinds.add(f.kind());
                    if (!verify.both().contains(f.kind() + " " + f.subject())) {
                        unnamed.add(f.kind() + " " + f.subject());
                    }
                }
                List<String> missingKinds = new ArrayList<>(PLANTED_KINDS);
                missingKinds.removeAll(reportedKinds);

                String remedy = remedyFrom(verify.both());
                boolean remedyIsRepairFix = remedy != null
                        && remedy.contains(" home repair --home ")
                        && remedy.contains(subject.toString())
                        && remedy.endsWith("--fix");

                var fix = HomeVerdictsSupport.repairJson(ctx, "fix", subject, "--fix");
                var repairAfter = HomeVerdictsSupport.repairJson(ctx, "repair-after-fix", subject);
                var verifyAfter = HomeVerdictsSupport.verify(ctx, "verify-after-fix", subject);

                boolean controlClean = control.exit() == 0;
                boolean repairRed = repair.exit() == 1 && report.object();
                boolean everyPlantedKindReported = missingKinds.isEmpty();
                boolean verifyRed = verify.exit() == 1;
                boolean verifyNamesEvery = !report.findings().isEmpty() && unnamed.isEmpty();
                boolean bothCleanAfter = repairAfter.exit() == 0
                        && HomeVerdictsSupport.report(repairAfter).parsedClean()
                        && verifyAfter.exit() == 0;

                List<String> failures = new ArrayList<>();
                if (!controlClean) failures.add("control: verify on the unplanted home exited " + control.exit());
                if (!repairRed) failures.add("`home repair --json` exited " + repair.exit());
                if (!everyPlantedKindReported) failures.add("repair did not report planted kinds " + missingKinds);
                if (!verifyRed) failures.add("`home verify` exited " + verify.exit() + " on a home repair calls damaged");
                if (!verifyNamesEvery) failures.add("`home verify` does not name " + unnamed);
                if (!remedyIsRepairFix) failures.add("verify's printed remedy is not `home repair --home <subject> --fix`: " + remedy);
                if (!bothCleanAfter) failures.add("after --fix: repair " + repairAfter.exit() + ", verify " + verifyAfter.exit());

                result = (failures.isEmpty() ? NodeResult.pass(SPEC.id())
                        : NodeResult.fail(SPEC.id(), String.join(" | ", failures)))
                        .process(control.proc()).process(repair.proc()).process(verify.proc())
                        .process(fix.proc()).process(repairAfter.proc()).process(verifyAfter.proc())
                        .assertion("control_home_verify_exits_0_on_the_unplanted_home", controlClean)
                        .assertion("home_repair_json_exits_1", repairRed)
                        .assertion("home_repair_reports_every_planted_kind", everyPlantedKindReported)
                        .assertion("home_verify_exits_1_when_home_repair_reports", verifyRed)
                        .assertion("home_verify_names_every_repair_finding_by_kind_and_subject", verifyNamesEvery)
                        .assertion("home_verify_prints_home_repair_fix_as_its_remedy", remedyIsRepairFix)
                        .assertion("after_the_fix_both_readers_are_clean", bothCleanAfter)
                        .metric("repair.findings", report.findings().size())
                        .metric("verify.unnamed", unnamed.size())
                        .log("remedy as printed: " + remedy + "\nrepair --json stdout: " + repair.stdout().strip());
            } catch (IOException | RuntimeException e) {
                result = NodeResult.error(SPEC.id(), e);
            } finally {
                try {
                    HomeVerdictsSupport.deleteRecursively(work);
                } catch (IOException ignored) {
                    // reported below
                }
            }
            return result.assertion("the_damaged_homes_are_deleted",
                    !Files.exists(work, LinkOption.NOFOLLOW_LINKS));
        });
    }

    /** The span HomeFixpointLaw.remedyFrom takes — the first one printed. */
    static String remedyFrom(String output) {
        for (String raw : output.split("\n")) {
            int at = raw.indexOf("complete it with: ");
            if (at < 0) continue;
            String rest = raw.substring(at + "complete it with: ".length()).trim();
            int tail = rest.indexOf(", then re-run this check");
            if (tail >= 0) rest = rest.substring(0, tail);
            rest = rest.trim();
            if (!rest.isEmpty()) return rest;
        }
        return null;
    }
}
