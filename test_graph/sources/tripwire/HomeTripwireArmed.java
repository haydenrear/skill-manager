///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES TripwireSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;
import java.util.List;

/**
 * Arms the tripwire: records what the operator's real homes look like BEFORE
 * this graph does anything, at both fidelities.
 *
 * <p>Read-only by construction — it opens files to hash them and never writes
 * outside the run's report directory. That matters here more than usual: the
 * standing constraint on issue #1 makes the operator's homes read-only for the
 * duration of the epic, and a tripwire that violated it to do its job would be
 * self-defeating.
 *
 * <p>The baseline is published by path rather than left to convention so
 * {@code home.tripwire.checked} reads exactly the file this node wrote, and a
 * missing baseline is a failure rather than a silently empty comparison.
 */
public class HomeTripwireArmed {
    static final NodeSpec SPEC = NodeSpec.of("home.tripwire.armed")
            .kind(NodeSpec.Kind.FIXTURE)
            .tags("tripwire", "sandbox", "home")
            // ~13 GB across four roots for METADATA, ~766 MB hashed for CONTENT.
            // Measured at roughly 20s cold on the development machine; the
            // margin is for a cold page cache, not for an unknown.
            .timeout("300s")
            .output("metadataBaseline", "string")
            .output("contentBaseline", "string")
            .output("realHome", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path home;
            try {
                home = TripwireSupport.realHome();
            } catch (RuntimeException e) {
                return NodeResult.fail("home.tripwire.armed", e.getMessage());
            }

            List<Path> roots = TripwireSupport.presentRoots(home);
            if (roots.isEmpty()) {
                // Not "clean" — unwatchable. A tripwire with nothing to watch
                // would pass forever, which is the failure mode this node exists
                // to make impossible.
                return NodeResult.fail("home.tripwire.armed",
                        "none of " + TripwireSupport.ROOTS + " exist under " + home);
            }

            List<Path> contentRoots = new java.util.ArrayList<>();
            for (String surface : TripwireSupport.CONTENT_SURFACES) {
                Path path = home.resolve(".skill-manager").resolve(surface);
                if (java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    contentRoots.add(path);
                }
            }

            List<String> metadata;
            List<String> content;
            Path metadataFile = TripwireSupport.baselineFile(ctx, TripwireSupport.Fidelity.METADATA);
            Path contentFile = TripwireSupport.baselineFile(ctx, TripwireSupport.Fidelity.CONTENT);
            try {
                metadata = TripwireSupport.collectAll(roots, home, TripwireSupport.Fidelity.METADATA);
                content = TripwireSupport.collectAll(contentRoots, home, TripwireSupport.Fidelity.CONTENT);
                TripwireSupport.writeLines(metadataFile, metadata);
                TripwireSupport.writeLines(contentFile, content);
            } catch (Exception e) {
                return NodeResult.error("home.tripwire.armed", e);
            }

            boolean metadataBaselineIsNonEmpty = !metadata.isEmpty();
            boolean contentBaselineIsNonEmpty = !content.isEmpty();
            boolean everyDeclaredRootThatExistsIsCovered = roots.size() == countCovered(metadata);

            boolean pass = metadataBaselineIsNonEmpty && contentBaselineIsNonEmpty
                    && everyDeclaredRootThatExistsIsCovered;
            return (pass
                    ? NodeResult.pass("home.tripwire.armed")
                    : NodeResult.fail("home.tripwire.armed",
                            "metadataLines=" + metadata.size() + " contentLines=" + content.size()
                                    + " roots=" + roots + " contentRoots=" + contentRoots))
                    .assertion("the_metadata_baseline_is_not_empty", metadataBaselineIsNonEmpty)
                    .assertion("the_content_baseline_is_not_empty", contentBaselineIsNonEmpty)
                    .assertion("every_declared_root_that_exists_is_in_the_baseline",
                            everyDeclaredRootThatExistsIsCovered)
                    .metric("metadataEntries", metadata.size())
                    .metric("contentEntries", content.size())
                    .metric("watchedRoots", roots.size())
                    .metric("contentSurfaces", contentRoots.size())
                    .publish("metadataBaseline", metadataFile.toString())
                    .publish("contentBaseline", contentFile.toString())
                    .publish("realHome", home.toString());
        });
    }

    /** Distinct top-level root names appearing in a collected baseline. */
    private static int countCovered(List<String> lines) {
        java.util.Set<String> tops = new java.util.LinkedHashSet<>();
        for (String line : lines) {
            String[] parts = line.split("\t", 3);
            if (parts.length < 2) continue;
            String rel = parts[1];
            int slash = rel.indexOf('/');
            tops.add(slash < 0 ? rel : rel.substring(0, slash));
        }
        tops.remove(".");
        return tops.size();
    }
}
