///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeIntegrity.java
//SOURCES HomeIntegritySupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A store's tracking ref is not left behind the commit its last fetch brought.
 *
 * <p>#124's defects 2 and 3, merged and moved down a layer — the argument is on
 * {@link HomeIntegrity#upstreamTracksWhatSyncFetched}. {@code NoPhantomAhead}
 * as written constrains {@code skt check}, which is a different repository and
 * has already fixed it. The home-level fact that <em>generates</em> the phantom
 * is that {@code GitOps.fetchRef} may fetch from a URL, which writes
 * {@code FETCH_HEAD} and moves no remote-tracking ref, so every ahead/behind
 * count read from that ref afterwards is meaningless.
 *
 * <p>Measured in the operator's project home: 13 of 20 stores have HEAD equal
 * to {@code FETCH_HEAD} while {@code @}{@code {upstream}} names an earlier
 * commit. {@code git-integration-repo} reports {@code ahead=52} in exactly that
 * state.
 *
 * <p>The mutation plants that state directly — advance {@code FETCH_HEAD} past
 * the tracking ref, as a URL fetch does — and asserts it is caught. The second
 * mutation asserts the opposite direction is <b>not</b> reported: a tracking
 * ref <em>ahead</em> of {@code FETCH_HEAD} is ordinary (something fetched the
 * ref properly, later) and a check that flagged it would fire on almost every
 * healthy store, including this graph's own fixture, whose store carries a
 * {@code FETCH_HEAD} from before its last commit.
 */
public class UpstreamTracksWhatSyncFetched {

    static final NodeSpec SPEC = NodeSpec.of("home.integrity.upstream.tracks.fetch")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.integrity.fixture")
            .tags("home", "integrity", "git")
            .timeout("180s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String homeStr = ctx.get("home.integrity.fixture", "integrityHome").orElse(null);
            String scratchStr = ctx.get("home.integrity.fixture", "scratchRoot").orElse(null);
            if (homeStr == null || scratchStr == null) return unproven("missing fixture context");
            Path home = Path.of(homeStr);
            Path scratch = Path.of(scratchStr);

            HomeIntegrity.Report fresh = HomeIntegrity.upstreamTracksWhatSyncFetched(home);

            HomeIntegritySupport.Mutation behind;
            HomeIntegritySupport.Mutation ahead;
            try {
                behind = HomeIntegritySupport.probe(home, scratch, "tracking_ref_left_behind",
                        new UrlShapedFetch(true),
                        HomeIntegrity::upstreamTracksWhatSyncFetched);
                ahead = HomeIntegritySupport.probe(home, scratch, "tracking_ref_ahead_of_fetch",
                        new UrlShapedFetch(false),
                        HomeIntegrity::upstreamTracksWhatSyncFetched);
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }

            boolean freshHolds = fresh.holdsNonVacuously();
            boolean aheadTolerated = !ahead.caught();

            boolean pass = freshHolds && behind.caught() && behind.repaired() && aheadTolerated;

            NodeResult result = pass
                    ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(),
                            "freshHolds=" + freshHolds
                                    + " examined=" + fresh.examined()
                                    + " behindCaught=" + behind.caught()
                                    + " behindRepaired=" + behind.repaired()
                                    + " aheadTolerated=" + aheadTolerated
                                    + " | " + fresh.describe());

            result = HomeIntegritySupport.withReport(result, fresh)
                    .assertion("every_store_in_a_fresh_home_tracks_what_its_fetch_brought",
                            freshHolds);
            result = HomeIntegritySupport.withMutation(result, behind);
            return result
                    .assertion("a_tracking_ref_ahead_of_the_last_fetch_is_not_a_defect",
                            aheadTolerated)
                    .log(ahead.name() + ": " + ahead.evidence());
        });
    }

    /**
     * Reproduce a URL fetch: advance the store and write {@code FETCH_HEAD}
     * without moving {@code refs/remotes/origin/main}.
     *
     * <p>{@code behind=true} leaves the tracking ref at the old commit while
     * {@code FETCH_HEAD} names the new one — the defect. {@code behind=false}
     * moves the tracking ref and leaves {@code FETCH_HEAD} at the old one — the
     * ordinary case that must not be reported.
     */
    private record UrlShapedFetch(boolean behind) implements HomeIntegritySupport.Damage {

        @Override
        public void plant(Path home) throws IOException {
            Path store = home.resolve("skills").resolve(HomeIntegritySupport.UNIT);
            String old = HomeIntegrity.git(store, "rev-parse", "HEAD");

            java.nio.file.Files.writeString(
                    store.resolve("SKILL.md"),
                    java.nio.file.Files.readString(store.resolve("SKILL.md"))
                            + "\nadvanced by the home-integrity graph\n");
            HomeIntegritySupport.run(store, "git", "add", "-A");
            HomeIntegritySupport.run(store, "git", "-c", "user.email=hi@test.invalid",
                    "-c", "user.name=hi", "commit", "-m", "advance");
            String now = HomeIntegrity.git(store, "rev-parse", "HEAD");

            if (behind) {
                // What a URL fetch does: FETCH_HEAD names the new commit, the
                // remote-tracking ref never moves.
                writeFetchHead(store, now);
                HomeIntegritySupport.run(store, "git", "update-ref",
                        "refs/remotes/origin/main", old);
            } else {
                writeFetchHead(store, old);
                HomeIntegritySupport.run(store, "git", "update-ref",
                        "refs/remotes/origin/main", now);
            }
        }

        @Override
        public void repair(Path home) throws IOException {
            Path store = home.resolve("skills").resolve(HomeIntegritySupport.UNIT);
            String head = HomeIntegrity.git(store, "rev-parse", "HEAD");
            writeFetchHead(store, head);
            HomeIntegritySupport.run(store, "git", "update-ref", "refs/remotes/origin/main", head);
        }

        private static void writeFetchHead(Path store, String sha) throws IOException {
            java.nio.file.Files.writeString(store.resolve(".git").resolve("FETCH_HEAD"),
                    sha + "\t\tbranch 'main' of origin\n");
        }
    }

    private static NodeResult unproven(String why) {
        return NodeResult.fail(SPEC.id(), why)
                .assertion("every_store_in_a_fresh_home_tracks_what_its_fetch_brought", false)
                .assertion("the_planted_tracking_ref_left_behind_is_caught", false)
                .assertion("repairing_the_planted_tracking_ref_left_behind_clears_it", false)
                .assertion("a_tracking_ref_ahead_of_the_last_fetch_is_not_a_defect", false);
    }
}
