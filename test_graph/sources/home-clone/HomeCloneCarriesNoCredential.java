///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeCloneSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * #281 / DEF-282: a copy of a home is not a copy of its login.
 *
 * <h2>Why the unit test is not enough</h2>
 *
 * <p>{@code HomeCloneTest} drives {@link dev.skillmanager.store.HomeCloner}
 * directly and asserts the copy holds no {@code auth.token}. What it cannot
 * show is the operator's experience: a whole {@code home clone} — copy,
 * re-anchor, verify, report — ending <b>exit 0</b>, "clean", with a working
 * refresh token in the copy and nothing in the output about it.
 *
 * <p>That output is the part that mattered. Issue #281 was filed as an
 * "unverified inference" and sat open, because the reasoning ran from the
 * ABSENCE of an exclusion rather than from cloning a home and looking. This
 * node looks.
 *
 * <h2>The blast radius it is standing in for</h2>
 *
 * <p>Every project home and every worktree home is a clone, so before the fix
 * the token was in every per-checkout home on the machine. The case that
 * forced it is sharper: a home copied into a container image shipped a working
 * refresh token for the operator's registry account, in an 881-byte file, in a
 * home that otherwise looked exactly right.
 *
 * <h2>The controls</h2>
 *
 * <ul>
 *   <li><b>the source keeps its own</b> — this drops a copy, it does not log
 *       the operator out. A node that only checked the copy would pass against
 *       an implementation that deleted the original.</li>
 *   <li><b>an ordinary root file still travels</b> — otherwise "no auth.token
 *       in the copy" is also what a clone that stopped copying root files
 *       would produce, and the assertion would ride along on a much larger
 *       breakage.</li>
 *   <li><b>the clone SAYS so</b> — a credential that silently fails to arrive
 *       is a confusing "not logged in" later. Omission and disclosure are
 *       different fixes and only one of them was asked for.</li>
 * </ul>
 */
public class HomeCloneCarriesNoCredential {

    static final NodeSpec SPEC = NodeSpec.of("home.clone.carries.no.credential")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.clone.no.agent.home.leak")
            .tags("home-clone", "credentials", "issue-281")
            .timeout("300s")
            .output("credentialCloneHome", "string");

    private static final String TOKEN = "auth.token";
    /** Deliberately unmistakable in a diff or a log if it ever escapes. */
    private static final String BODY =
            "{\"access_token\":\"FAKE-ACCESS-NOT-REAL\","
                    + "\"refresh_token\":\"FAKE-REFRESH-NOT-REAL\","
                    + "\"expires_at\":\"2099-01-01T00:00:00Z\"}\n";
    private static final String ORDINARY = "home.policy.toml";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            try {
                return check(ctx);
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }
        });
    }

    private static NodeResult check(NodeContext ctx) throws IOException {
        String fixture = ctx.get("home.clone.fixture.built", "fixtureHome").orElse(null);
        if (fixture == null) {
            return NodeResult.fail(SPEC.id(), "UNPROVEN: no fixture home in context");
        }
        Path source = Path.of(fixture);
        Path token = source.resolve(TOKEN);
        Path dest = Files.createTempDirectory("home-clone-credential-").resolve("copy");

        Files.writeString(token, BODY);
        try {
            Files.setPosixFilePermissions(token,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // a filesystem without POSIX modes does not change the claim
        }
        boolean ordinaryPlanted = Files.isRegularFile(source.resolve(ORDINARY));

        ProcessRecord clone;
        boolean sourceKeptItsOwn;
        try {
            clone = HomeCloneSupport.sm(ctx, "credential-clone", source.toString(),
                    "home", "clone", "--from", source.toString(), "--to", dest.toString());
            // Read BEFORE the cleanup below, or this asserts nothing.
            sourceKeptItsOwn = Files.isRegularFile(token)
                    && Files.readString(token).contains("FAKE-REFRESH-NOT-REAL");
        } finally {
            // The fixture is shared with later nodes. Take the planted token
            // back out whatever happened, so a failure here cannot leave a
            // token-shaped file in a home other assertions read.
            Files.deleteIfExists(token);
        }

        boolean cloneSucceeded = clone.exitCode() == 0;
        boolean copyHasNoToken = !Files.exists(dest.resolve(TOKEN));
        String out = readLog(ctx.reportDir(), clone);
        boolean saidSo = out.contains(TOKEN) && out.toLowerCase().contains("not copied");
        boolean ordinaryTravelled = !ordinaryPlanted
                || Files.isRegularFile(dest.resolve(ORDINARY));

        boolean pass = cloneSucceeded && copyHasNoToken && saidSo
                && ordinaryTravelled && sourceKeptItsOwn;

        return (pass ? NodeResult.pass(SPEC.id())
                : NodeResult.fail(SPEC.id(),
                        "cloneExit=" + clone.exitCode()
                                + " copyHasNoToken=" + copyHasNoToken
                                + " saidSo=" + saidSo
                                + " ordinaryTravelled=" + ordinaryTravelled
                                + " sourceKeptItsOwn=" + sourceKeptItsOwn))
                .process(clone)
                .assertion("a_clone_does_not_carry_the_registry_credential", copyHasNoToken)
                .assertion("and_the_clone_SAYS_it_did_not_rather_than_omitting_it", saidSo)
                .assertion("CONTROL_the_source_keeps_its_own_token", sourceKeptItsOwn)
                .assertion("CONTROL_an_ordinary_root_file_still_travels", ordinaryTravelled)
                .assertion("CONTROL_the_clone_still_succeeds", cloneSucceeded)
                .publish("credentialCloneHome", dest.toString())
                .log("#281 sat open as an 'unverified inference' because it was reasoned "
                        + "from the absence of an exclusion. Measured 2026-09-05, before "
                        + "the fix: the copy had the token, mode 0600, contents intact.");
    }

    private static String readLog(Path reportDir, ProcessRecord rec) {
        try {
            if (rec.logPath() == null) return "";
            return Files.readString(reportDir.resolve(rec.logPath()));
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
