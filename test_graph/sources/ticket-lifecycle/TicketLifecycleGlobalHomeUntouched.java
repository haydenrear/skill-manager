///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES TicketLifecycleSupport.java
//SOURCES ../tripwire/TripwireSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Step 9 — the global home is never written.</b> Epic #2's central
 * invariant, with a dedicated, always-on assertion rather than a hope.
 *
 * <h2>Two sides, because either alone is weak</h2>
 *
 * <ol>
 *   <li><b>The sandbox's global home never comes into existence.</b> Every
 *       child in this graph runs with {@code $HOME} redirected to the run's
 *       temp root, so {@code bootstrap-home.sh}'s
 *       {@code GLOBAL_HOME=$HOME/.skill-manager} — the home it refuses to write
 *       — is a path inside the sandbox. If the workflow ever fell back to "the
 *       global home", that is where it would land, and the directory would be
 *       there. This is a POSITIVE statement about where the writes went.</li>
 *   <li><b>The operator's real homes did not move.</b> The metadata baseline
 *       taken by {@code ticket.lifecycle.fixture.built}, before any of the
 *       workflow ran, compared against the same four roots now. This is the
 *       catch-all: it needs no theory about which fallback a defect would
 *       take.</li>
 * </ol>
 *
 * <h2>The companion, without which neither means anything</h2>
 *
 * <p>A diff that comes back empty is indistinguishable from a diff that could
 * not look — and this epic has already been misled by a zero that meant "could
 * not look" four separate times. So the same {@code collect}/{@code difference}
 * pair is pointed at a DECOY home tree with one write planted in it, in the
 * same run, and must report it. Three plants, one per shape a leak has actually
 * taken: a new unit directory (the {@code install} projection of #18), a new
 * symlink (the dangling {@code ~/.claude/skills} entry that turned up in a live
 * agent's skill list), and an edit to an existing file (a descriptor rewritten
 * in place). An unmutated control is asserted clean in the same run, so an
 * over-eager oracle fails here too.
 *
 * <p>{@code TripwireSupport} is reused rather than re-implemented: it is the
 * oracle {@code home-tripwire} already proves sensitive, and a second copy
 * could be killed here while the other stayed green.
 */
public class TicketLifecycleGlobalHomeUntouched {

    static final NodeSpec SPEC = NodeSpec.of("ticket.lifecycle.global.home.untouched")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("ticket.lifecycle.publish")
            .tags("ticket-lifecycle", "leak", "oracle")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String sandboxGlobal = ctx.get("ticket.lifecycle.fixture.built", "sandboxGlobalHome")
                    .orElse(null);
            String baselineRaw = ctx.get("ticket.lifecycle.fixture.built", "leakBaseline")
                    .orElse(null);
            String rootsRaw = ctx.get("ticket.lifecycle.fixture.built", "leakRoots").orElse(null);
            String workspaceRaw = ctx.get("ticket.lifecycle.fixture.built", "workspace")
                    .orElse(null);
            if (sandboxGlobal == null || baselineRaw == null || rootsRaw == null
                    || workspaceRaw == null) {
                return NodeResult.fail("ticket.lifecycle.global.home.untouched",
                        "missing upstream context");
            }

            // --- side one: the sandbox's global home was never created --------
            boolean theSandboxGlobalHomeWasNeverCreated = !Files.exists(Path.of(sandboxGlobal));

            // --- side two: the operator's real homes did not move -------------
            List<Path> roots = new ArrayList<>();
            for (String raw : rootsRaw.split(java.io.File.pathSeparator)) {
                if (!raw.isBlank()) roots.add(Path.of(raw));
            }
            Path baselineFile = Path.of(baselineRaw);
            boolean theBaselineIsReadable = Files.isRegularFile(baselineFile) && !roots.isEmpty();
            List<String> before = theBaselineIsReadable
                    ? TripwireSupport.readLines(baselineFile) : List.of();
            List<String> after = theBaselineIsReadable
                    ? TripwireSupport.collectAll(roots, TripwireSupport.realHome(),
                            TripwireSupport.Fidelity.METADATA)
                    : List.of();
            List<String> differences = TripwireSupport.difference(before, after);
            // A worktree ADDED or REMOVED inside one of the operator's own
            // repositories is git's bookkeeping, not a skill-manager write, and
            // this graph creates and removes three of them — in a temp
            // directory, but a registration can still be recorded elsewhere.
            // TripwireSupport already knows how to name those, so they are
            // subtracted rather than tolerated by a prefix rule.
            List<String> worktreeNoise = TripwireSupport.worktreeRegistrationChanges(differences);
            List<String> realDifferences = new ArrayList<>(differences);
            realDifferences.removeAll(worktreeNoise);
            boolean theOperatorsRealHomesDidNotMove =
                    theBaselineIsReadable && realDifferences.isEmpty();
            // The floor under that zero: the baseline has to have SEEN
            // something. An empty baseline diffs clean against an empty
            // present, forever.
            boolean theBaselineActuallyWatchedSomething = before.size() > 100;

            // --- the companion: the same oracle, on a planted write ------------
            Path decoyRoot = Path.of(workspaceRaw).resolve("leak-decoy");
            Path decoyHome = decoyRoot.resolve(".skill-manager");
            Path decoySkills = decoyHome.resolve("skills");
            Files.createDirectories(decoySkills.resolve("existing"));
            Files.writeString(decoySkills.resolve("existing").resolve("SKILL.md"), "decoy v1\n");
            Files.writeString(decoyHome.resolve("home.runtime.json"), "{\"decoy\":true}\n");
            List<String> decoyBefore = TripwireSupport.collect(decoyHome, decoyRoot,
                    TripwireSupport.Fidelity.METADATA);

            // The unmutated control, first: an over-eager oracle fails here.
            boolean anUnchangedTreeReportsClean = TripwireSupport.difference(decoyBefore,
                    TripwireSupport.collect(decoyHome, decoyRoot,
                            TripwireSupport.Fidelity.METADATA)).isEmpty();

            // M1 — a projected unit directory, the shape of #18.
            Files.createDirectories(decoySkills.resolve("leaked"));
            Files.writeString(decoySkills.resolve("leaked").resolve("SKILL.md"), "leaked\n");
            boolean aPlantedUnitIsDetected = !TripwireSupport.difference(decoyBefore,
                    TripwireSupport.collect(decoyHome, decoyRoot,
                            TripwireSupport.Fidelity.METADATA)).isEmpty();
            deleteTree(decoySkills.resolve("leaked"));

            // M2 — a symlink, the shape that appeared in a live agent's skill
            // list and dangled once the temp home was deleted.
            Path link = decoySkills.resolve("linked");
            Files.createSymbolicLink(link, Path.of("/nowhere/at/all"));
            boolean aPlantedSymlinkIsDetected = !TripwireSupport.difference(decoyBefore,
                    TripwireSupport.collect(decoyHome, decoyRoot,
                            TripwireSupport.Fidelity.METADATA)).isEmpty();
            Files.delete(link);

            // M3 — an in-place rewrite of a file that was already there.
            Files.writeString(decoyHome.resolve("home.runtime.json"),
                    "{\"decoy\":true,\"rewritten\":true}\n");
            boolean AnInPlaceRewriteIsDetected = !TripwireSupport.difference(decoyBefore,
                    TripwireSupport.collect(decoyHome, decoyRoot,
                            TripwireSupport.Fidelity.METADATA)).isEmpty();

            boolean pass = theSandboxGlobalHomeWasNeverCreated && theBaselineIsReadable
                    && theOperatorsRealHomesDidNotMove && theBaselineActuallyWatchedSomething
                    && anUnchangedTreeReportsClean && aPlantedUnitIsDetected
                    && aPlantedSymlinkIsDetected && AnInPlaceRewriteIsDetected;

            return (pass
                    ? NodeResult.pass("ticket.lifecycle.global.home.untouched")
                    : NodeResult.fail("ticket.lifecycle.global.home.untouched",
                            "sandboxGlobalHomeAbsent=" + theSandboxGlobalHomeWasNeverCreated
                                    + " baselineEntries=" + before.size()
                                    + " differences=" + head(realDifferences)
                                    + " worktreeNoise=" + worktreeNoise.size()
                                    + " control=" + anUnchangedTreeReportsClean
                                    + " m1=" + aPlantedUnitIsDetected
                                    + " m2=" + aPlantedSymlinkIsDetected
                                    + " m3=" + AnInPlaceRewriteIsDetected))
                    .assertion("the_workflow_never_created_a_global_home",
                            theSandboxGlobalHomeWasNeverCreated)
                    .assertion("the_leak_baseline_is_readable", theBaselineIsReadable)
                    .assertion("the_leak_baseline_watched_a_non_trivial_tree",
                            theBaselineActuallyWatchedSomething)
                    .assertion("the_operators_real_agent_homes_did_not_move",
                            theOperatorsRealHomesDidNotMove)
                    .assertion("an_unchanged_tree_reports_clean", anUnchangedTreeReportsClean)
                    .assertion("the_leak_oracle_detects_a_planted_unit_directory",
                            aPlantedUnitIsDetected)
                    .assertion("the_leak_oracle_detects_a_planted_symlink",
                            aPlantedSymlinkIsDetected)
                    .assertion("the_leak_oracle_detects_an_in_place_rewrite",
                            AnInPlaceRewriteIsDetected)
                    .metric("baselineEntries", before.size())
                    .metric("differencesFound", realDifferences.size())
                    .metric("worktreeRegistrationChanges", worktreeNoise.size());
        });
    }

    private static List<String> head(List<String> lines) {
        return lines.size() <= 20 ? lines : lines.subList(0, 20);
    }

    private static void deleteTree(Path root) throws java.io.IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ignored) {
                    // a leftover decoy entry is not a finding
                }
            });
        }
    }
}
