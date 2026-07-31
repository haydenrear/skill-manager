///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES TripwireSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the tripwire: re-reads the operator's real homes and asserts nothing
 * this graph did reached them.
 *
 * <h2>Two assertions of different strength, on purpose</h2>
 *
 * <p>The DIFF assertions ({@code metadata}, {@code content}) are the broad net.
 * They catch a write nobody predicted, anywhere under the four roots — which is
 * exactly how the shell version found four leak paths that no targeted assertion
 * had. Their weakness is attributability: a concurrent {@code claude} session, a
 * foreign worktree's Gradle daemon (#21), or a helm dependency re-pull can move
 * these lines without this graph having done anything wrong.
 *
 * <p>So there is a second, narrower assertion that no concurrent session can
 * perturb: THIS graph's probe unit, by name, must appear in no real agent home.
 * A leak from this graph is attributable to this graph. Keeping both is
 * deliberate — the broad one finds the unknown, the narrow one survives a busy
 * machine, and reporting them separately tells a reader which kind of finding
 * they are looking at.
 *
 * <h2>The vacuity guard</h2>
 *
 * <p>An empty baseline compared against an empty re-read is CLEAN, and it means
 * nothing. Both baselines are asserted non-empty here as well as at arm time,
 * because the failure being guarded against is the baseline going missing
 * BETWEEN the two nodes, which an assertion at arm time cannot see.
 */
public class HomeTripwireChecked {
    static final NodeSpec SPEC = NodeSpec.of("home.tripwire.checked")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.tripwire.workload")
            .tags("tripwire", "sandbox", "home")
            .timeout("300s");

    /** How many differing lines to put in the failure message before eliding. */
    private static final int SHOWN = 40;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String metadataBaseline = ctx.get("home.tripwire.armed", "metadataBaseline").orElse(null);
            String contentBaseline = ctx.get("home.tripwire.armed", "contentBaseline").orElse(null);
            String realHome = ctx.get("home.tripwire.armed", "realHome").orElse(null);
            String unit = ctx.get("home.tripwire.workload", "unitName").orElse(null);
            if (metadataBaseline == null || contentBaseline == null || realHome == null || unit == null) {
                // Every abort still carries the full assertion roster. The
                // point of naming each claim is that a reader learns WHICH one
                // failed; a node that bails out with a bare sentence teaches
                // them nothing and — worse — teaches the aggregate report that
                // this run made no claims at all, which reads exactly like a
                // run that made them and passed.
                return unproven(NodeResult.fail("home.tripwire.checked",
                        "missing upstream context: metadataBaseline=" + metadataBaseline
                                + " contentBaseline=" + contentBaseline
                                + " realHome=" + realHome + " unit=" + unit));
            }
            Path home = Path.of(realHome);

            List<String> beforeMetadata;
            List<String> beforeContent;
            List<String> afterMetadata;
            List<String> afterContent;
            try {
                beforeMetadata = TripwireSupport.readLines(Path.of(metadataBaseline));
                beforeContent = TripwireSupport.readLines(Path.of(contentBaseline));

                List<Path> roots = TripwireSupport.presentRoots(home);
                List<Path> contentRoots = new ArrayList<>();
                for (String surface : TripwireSupport.CONTENT_SURFACES) {
                    Path path = home.resolve(".skill-manager").resolve(surface);
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) contentRoots.add(path);
                }
                afterMetadata = TripwireSupport.collectAll(roots, home, TripwireSupport.Fidelity.METADATA);
                afterContent = TripwireSupport.collectAll(contentRoots, home, TripwireSupport.Fidelity.CONTENT);
            } catch (Exception e) {
                // Same rule on the exception path. A tripwire that could not
                // read the homes has PROVEN NOTHING about them, and saying so
                // in the assertion rows is the only way a reader can tell that
                // from a tripwire that read them and found them clean.
                return unproven(NodeResult.error("home.tripwire.checked", e));
            }

            boolean theBaselinesWereActuallyRead = !beforeMetadata.isEmpty() && !beforeContent.isEmpty();

            List<String> metadataDiff = TripwireSupport.difference(beforeMetadata, afterMetadata);
            List<String> contentDiff = TripwireSupport.difference(beforeContent, afterContent);
            boolean theRealHomesAreUnchanged = metadataDiff.isEmpty();
            boolean theWatchedContentSurfacesAreByteIdentical = contentDiff.isEmpty();

            List<String> leaked = new ArrayList<>();
            for (String agentDir : new String[] {".claude/skills", ".codex/skills", ".gemini/skills"}) {
                Path candidate = home.resolve(agentDir).resolve(unit);
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) leaked.add(agentDir + "/" + unit);
            }
            Path storeUnit = home.resolve(".skill-manager").resolve("skills").resolve(unit);
            if (Files.exists(storeUnit, LinkOption.NOFOLLOW_LINKS)) leaked.add(".skill-manager/skills/" + unit);
            boolean thisGraphsProbeUnitIsInNoRealHome = leaked.isEmpty();

            // Issue #47's second defect. The broad metadata diff above now
            // covers this too — `.git` is no longer skipped — but a registration
            // gets its own named assertion for the same reason the probe unit
            // does: the broad diff is perturbed by any concurrent session and
            // will sometimes be red for reasons no one caused, while nothing on
            // this machine registers a git worktree inside the operator's home
            // by accident. When both go red, this one says which finding it is.
            List<String> registrationChanges =
                    TripwireSupport.worktreeRegistrationChanges(metadataDiff);
            boolean noGitWorktreeRegistrationChangedUnderAWatchedHome =
                    registrationChanges.isEmpty();

            boolean pass = theBaselinesWereActuallyRead && theRealHomesAreUnchanged
                    && theWatchedContentSurfacesAreByteIdentical && thisGraphsProbeUnitIsInNoRealHome
                    && noGitWorktreeRegistrationChangedUnderAWatchedHome;

            return (pass
                    ? NodeResult.pass("home.tripwire.checked")
                    : NodeResult.fail("home.tripwire.checked",
                            "leaked=" + leaked
                                    + " worktreeRegistrationChanges=" + registrationChanges
                                    + " metadataDiff=" + elide(metadataDiff)
                                    + " contentDiff=" + elide(contentDiff)))
                    .assertion("the_baselines_were_actually_read", theBaselinesWereActuallyRead)
                    .assertion("the_real_agent_homes_are_unchanged", theRealHomesAreUnchanged)
                    .assertion("the_watched_content_surfaces_are_byte_identical",
                            theWatchedContentSurfacesAreByteIdentical)
                    .assertion("this_graphs_probe_unit_is_in_no_real_home",
                            thisGraphsProbeUnitIsInNoRealHome)
                    .assertion("no_git_worktree_registration_changed_under_a_watched_home",
                            noGitWorktreeRegistrationChangedUnderAWatchedHome)
                    .metric("metadataDifferences", metadataDiff.size())
                    .metric("contentDifferences", contentDiff.size())
                    .metric("metadataEntries", afterMetadata.size())
                    .metric("contentEntries", afterContent.size())
                    // Non-vacuity, published rather than asserted: a machine
                    // whose homes hold no git checkout would legitimately watch
                    // zero, and a red build for that would teach the reader
                    // nothing. Zero here means the assertion above proved
                    // nothing, and the number says so out loud.
                    .metric("gitDirectoriesWatched",
                            TripwireSupport.gitDirectoriesWatched(afterMetadata))
                    .metric("worktreeRegistrationChanges", registrationChanges.size());
        });
    }

    /** The node's named claims, all failed, for a run that proved none of them. */
    private static NodeResult unproven(NodeResult result) {
        return result
                .assertion("the_baselines_were_actually_read", false)
                .assertion("the_real_agent_homes_are_unchanged", false)
                .assertion("the_watched_content_surfaces_are_byte_identical", false)
                .assertion("this_graphs_probe_unit_is_in_no_real_home", false)
                .assertion("no_git_worktree_registration_changed_under_a_watched_home", false);
    }

    private static String elide(List<String> lines) {
        if (lines.size() <= SHOWN) return lines.toString();
        return lines.subList(0, SHOWN) + " ...and " + (lines.size() - SHOWN) + " more";
    }
}
