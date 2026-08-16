///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES ArtifactDagSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ARTI-04: an installer that cannot say what it built from cannot be asked
 * whether it is stale.
 *
 * <p>Before this epic exactly one backend recorded a fingerprint over its
 * declared inputs — {@code skill-script} — and the other five recorded
 * nothing, so five ninths of a home answered "unverifiable" forever. The fix
 * was to make every backend grade its own inputs into the same three fields,
 * and the regression floor is that <b>no row is exempt</b>.
 *
 * <h2>What is asserted</h2>
 *
 * <p>Install a fixture that declares a CLI dependency, then read
 * {@code cli-lock.toml} and require of EVERY row it holds:
 *
 * <ul>
 *   <li>{@code install_fingerprint} is present and non-blank;</li>
 *   <li>{@code install_fingerprint_kind} grades it — a fingerprint whose
 *       provenance is unstated cannot be told from a placeholder, which is
 *       0d6cb97's finding;</li>
 *   <li>{@code install_fingerprint_basis} names WHAT was hashed, in words,
 *       because the freshness verdict quotes that sentence back at the
 *       operator and a basis that overstates its coverage is 98e4d94.</li>
 * </ul>
 *
 * <p>And the census has to agree with the lock: the {@code cli-shim} artifact
 * for each row reports the same fingerprint the row holds. Two readers of one
 * fact that never compare are the epic's recurring shape (#24).
 *
 * <h2>The limit on this node, stated rather than hidden</h2>
 *
 * <p>ARTI-13 asks for "one dep per backend". <b>This node cannot install one
 * per backend and does not claim to.</b> {@code brew}, {@code npm}, {@code pip}
 * and {@code uv} either resolve a package over the network or find a tool
 * already on the host's PATH, and {@code tar} fetches a URL; a graph in the CI
 * core set that needs any of those is a graph that fails for the network's
 * reasons. A {@code skill-script} dep is the only one that installs INTO the
 * home from bytes the graph itself wrote.
 *
 * <p>So the quantifier here is "every row this home holds", over a home whose
 * rows are all {@code skill-script}, plus an explicit floor that the home
 * holds the rows the fixture declared — without that floor, a home whose lock
 * failed to be written at all would satisfy "every row carries a fingerprint"
 * vacuously, which is the instrument-reports-success-because-it-could-not-look
 * failure this epic keeps paying for. The other five backends are covered by
 * {@code src/test/java/dev/skillmanager/cli/} unit tests; what is NOT covered
 * anywhere is a live install of them, and that is a known gap rather than a
 * silent one.
 */
public class EveryBackendFingerprinted {

    static final NodeSpec SPEC = NodeSpec.of("every.backend.fingerprinted")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("artifact-dag", "cli", "fingerprint")
            .timeout("180s")
            .output("home", "string");

    public static void main(String[] a) {
        Node.run(a, SPEC, ctx -> {
            Path ws = ArtifactDagSupport.workspace(ctx, "fingerprinted");
            Path store = ArtifactDagSupport.storeOf(ws);
            Path units = ws.resolve("units");
            Path alpha = ArtifactDagSupport.scaffoldUnit(units,
                    ArtifactDagSupport.UNIT_A, ArtifactDagSupport.TOOL_A);
            Path beta = ArtifactDagSupport.scaffoldUnit(units,
                    ArtifactDagSupport.UNIT_B, ArtifactDagSupport.TOOL_B);

            ProcessRecord installA = ArtifactDagSupport.sm(ctx, "install-alpha", store,
                    "install", alpha.toString(), "--yes");
            ProcessRecord installB = ArtifactDagSupport.sm(ctx, "install-beta", store,
                    "install", beta.toString(), "--yes");
            boolean both_fixtures_installed =
                    installA.exitCode() == 0 && installB.exitCode() == 0;

            List<String> rows = ArtifactDagSupport.cliLockRows(store);
            // The floor: the lock holds the rows the fixture declared. Without
            // it every quantifier below is satisfied by an empty lock.
            boolean the_lock_holds_a_row_for_every_declared_dep = rows.size() >= 2;

            List<String> withoutFingerprint = new ArrayList<>();
            List<String> withoutKind = new ArrayList<>();
            List<String> withoutBasis = new ArrayList<>();
            for (String row : rows) {
                String spec = ArtifactDagSupport.lockValue(row, "spec");
                String name = spec.isBlank() ? row.split("\n", 2)[0] : spec;
                if (ArtifactDagSupport.lockValue(row, "install_fingerprint").isBlank()) {
                    withoutFingerprint.add(name);
                }
                if (ArtifactDagSupport.lockValue(row, "install_fingerprint_kind").isBlank()) {
                    withoutKind.add(name);
                }
                if (ArtifactDagSupport.lockValue(row, "install_fingerprint_basis").isBlank()) {
                    withoutBasis.add(name);
                }
            }
            boolean every_lock_row_carries_an_install_fingerprint =
                    withoutFingerprint.isEmpty();
            boolean every_install_fingerprint_is_graded = withoutKind.isEmpty();
            boolean every_install_fingerprint_names_its_basis = withoutBasis.isEmpty();

            ProcessRecord list = ArtifactDagSupport.sm(ctx, "artifacts-list", store,
                    "artifacts", "list", "--json");
            String json = ArtifactDagSupport.log(ctx, list);

            List<String> disagreeing = new ArrayList<>();
            for (String tool : List.of(ArtifactDagSupport.TOOL_A, ArtifactDagSupport.TOOL_B)) {
                String fromLock = "";
                for (String row : rows) {
                    if (!ArtifactDagSupport.lockValue(row, "binary").equals(tool)) continue;
                    fromLock = ArtifactDagSupport.lockValue(row, "install_fingerprint");
                }
                String section = ArtifactDagSupport.sectionFor(json,
                        ArtifactDagSupport.shimId(tool));
                String fromCensus = ArtifactDagSupport.jsonString(section, "install_fingerprint");
                if (fromLock.isBlank() || !fromLock.equals(fromCensus)) {
                    disagreeing.add(tool + " lock=" + abbreviate(fromLock)
                            + " census=" + abbreviate(fromCensus));
                }
            }
            boolean the_census_reports_the_fingerprint_the_lock_holds =
                    list.exitCode() == 0 && disagreeing.isEmpty();

            boolean pass = both_fixtures_installed
                    && the_lock_holds_a_row_for_every_declared_dep
                    && every_lock_row_carries_an_install_fingerprint
                    && every_install_fingerprint_is_graded
                    && every_install_fingerprint_names_its_basis
                    && the_census_reports_the_fingerprint_the_lock_holds;

            NodeResult result = pass
                    ? NodeResult.pass("every.backend.fingerprinted")
                    : NodeResult.fail("every.backend.fingerprinted",
                            "both_fixtures_installed=" + both_fixtures_installed
                                    + " the_lock_holds_a_row_for_every_declared_dep="
                                    + the_lock_holds_a_row_for_every_declared_dep
                                    + " every_lock_row_carries_an_install_fingerprint="
                                    + every_lock_row_carries_an_install_fingerprint
                                    + " every_install_fingerprint_is_graded="
                                    + every_install_fingerprint_is_graded
                                    + " every_install_fingerprint_names_its_basis="
                                    + every_install_fingerprint_names_its_basis
                                    + " the_census_reports_the_fingerprint_the_lock_holds="
                                    + the_census_reports_the_fingerprint_the_lock_holds
                                    + " | rows=" + rows.size()
                                    + " withoutFingerprint=" + withoutFingerprint
                                    + " withoutKind=" + withoutKind
                                    + " withoutBasis=" + withoutBasis
                                    + " disagreeing=" + disagreeing
                                    + " installA=" + installA.exitCode()
                                    + " installB=" + installB.exitCode()
                                    + " listExit=" + list.exitCode());
            return result
                    .process(installA).process(installB).process(list)
                    .assertion("both_fixtures_installed", both_fixtures_installed)
                    .assertion("the_lock_holds_a_row_for_every_declared_dep",
                            the_lock_holds_a_row_for_every_declared_dep)
                    .assertion("every_lock_row_carries_an_install_fingerprint",
                            every_lock_row_carries_an_install_fingerprint)
                    .assertion("every_install_fingerprint_is_graded",
                            every_install_fingerprint_is_graded)
                    .assertion("every_install_fingerprint_names_its_basis",
                            every_install_fingerprint_names_its_basis)
                    .assertion("the_census_reports_the_fingerprint_the_lock_holds",
                            the_census_reports_the_fingerprint_the_lock_holds)
                    .metric("lockRows", rows.size())
                    .publish("home", store.toString());
        });
    }

    private static String abbreviate(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return "<none>";
        return fingerprint.length() <= 12 ? fingerprint : fingerprint.substring(0, 12) + "…";
    }
}
