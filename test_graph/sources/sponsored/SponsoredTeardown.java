///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;

/** Stops the Java registry server — the only testbed the sponsored graph spins up. */
public class SponsoredTeardown {
    static final NodeSpec SPEC = NodeSpec.of("sponsored.teardown")
            .kind(NodeSpec.Kind.EVIDENCE)
            .dependsOn(
                    "sponsored.search.matches.keyword",
                    "sponsored.no.ads.suppresses",
                    "sponsored.organic.unchanged",
                    "sponsored.higher.bid.wins")
            .tags("teardown")
            .timeout("15s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) return NodeResult.fail("sponsored.teardown", "missing env.prepared context");
            Path pidFile = Path.of(home, "test-graph", "registry.pid");
            boolean stopped = killByPidFile(pidFile);
            return NodeResult.pass("sponsored.teardown")
                    .assertion("registry_stopped", stopped);
        });
    }

    private static boolean killByPidFile(Path pidFile) {
        try {
            if (!Files.isRegularFile(pidFile)) return true;
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            boolean stopped = ProcessHandle.of(pid).map(h -> {
                h.destroy();
                try { h.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception e) { h.destroyForcibly(); }
                return !h.isAlive();
            }).orElse(true);
            Files.deleteIfExists(pidFile);
            return stopped;
        } catch (Exception e) {
            return false;
        }
    }
}
