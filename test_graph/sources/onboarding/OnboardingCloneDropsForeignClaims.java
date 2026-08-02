///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Step 4 — a new home must not arrive holding claims on other checkouts.</b>
 *
 * <h2>The defect</h2>
 *
 * <p>The same shape as the already-fixed "clones no longer inherit project
 * registrations", one layer down: the <em>registration</em> store was cleared,
 * the <em>projection ledger</em> and {@code child-homes/} were not. A scratch
 * home under {@code /private/tmp} was measured holding <b>18</b> bindings
 * naming four of the operator's real repositories, each carrying live
 * projection targets —
 * {@code SYMLINK /Users/…/skill-manager/.claude/skills/test-graph} and its
 * {@code .codex} and {@code .gemini} siblings, with {@code policy: OVERWRITE}.
 * {@code child-homes/} was the same: five records, four naming other checkouts.
 *
 * <p>The operator-visible consequence, observed safely with {@code --dry-run}:
 * a brand-new project home REFUSES to uninstall a unit it never asked for,
 * because of a claim inherited from another machine's state — and the remedy it
 * prints, "remove the child home first", followed literally, points at someone
 * else's repository.
 *
 * <p>The clone's own success message is honest about this: it says it did not
 * check "paths outside all of those — toolchains, project checkouts, anything
 * else on this machine". That NOT-checked clause is precisely where this lives.
 *
 * <h2>Assertion</h2>
 *
 * <p>A home produced by {@code home clone} (directly, or via
 * {@code bootstrap-home.sh}, or via {@code wt new}) contains no binding record
 * and no {@code child-homes/} record whose target path lies outside the new
 * home's own root — the rule already enforced for {@code projects/}.
 *
 * <h2>Vacuous-pass risks and companions</h2>
 *
 * <ol>
 *   <li><b>The source home has no foreign claims to begin with,</b> so "zero
 *       foreign rows" holds trivially. This is the likeliest failure by far: a
 *       purpose-built fixture home is clean by construction, which is exactly
 *       why this bug survived four passes over this path.
 *       <br><b>Companion — mandatory:</b> the SOURCE must be shown to carry ≥2
 *       foreign binding records and ≥2 foreign {@code child-homes} records
 *       before the clone is looked at. If the pollution is absent, FAIL the
 *       node. Do not skip.</li>
 *   <li><b>Asking the CLI, which erases the answer first.</b> Every
 *       skill-manager command reconciles {@code $SKILL_MANAGER_HOME} before
 *       doing anything else, and that pass rewrites the ledger from live state.
 *       Measured: the planted records were present when the fixture finished and
 *       gone by the time the first {@code bindings list} returned, so the
 *       precondition above read zero on a home that had two — the instrument
 *       destroying its own subject before measuring it.
 *       <br><b>Companion:</b> both ledgers are captured as BYTES by
 *       {@code onboarding.bootstrapped}, immediately after the clone, before any
 *       command has run against either home. This node reads that capture.</li>
 *   <li><b>Matching on a literal path prefix</b> like {@code /Users/}. Under CI
 *       the foreign checkouts are temp directories too.
 *       <br><b>Companion:</b> the predicate is "not under the home's own real
 *       root", computed from real paths, and it is shown FLAGGING the planted
 *       records in the source before it is trusted to report zero for the
 *       clone.</li>
 *   <li><b>The parser finding nothing,</b> which reads identically to "no
 *       foreign rows". {@code bindings list}'s output is wide and it appends the
 *       persisted-error banner.
 *       <br><b>Companion:</b> the parser and the outside-the-root predicate are
 *       run together over a SYNTHETIC listing carrying one known foreign row —
 *       banner and all — and must report exactly that row.</li>
 * </ol>
 *
 * <h2>The second, weaker assertion — and why it cannot pass vacuously</h2>
 *
 * <p>{@code uninstall <unit> --dry-run} for a unit the clone holds must not
 * refuse with "claimed by child skill-manager home(s)" naming a home outside
 * the new root. That is the operator-visible symptom, and it either refuses or
 * it does not — there is no way for it to be true by not looking. Run with
 * {@code --dry-run} only: the ledger records are sufficient evidence of where a
 * real {@code unbind} would write, and executing one to prove it would be
 * writing into the very paths the assertion is about.
 */
public class OnboardingCloneDropsForeignClaims {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.clone.drops.foreign.claims")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.clone.is.honest")
            .tags("onboarding", "clone", "blocker", "isolation")
            .timeout("900s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path proj = path(ctx, "onboarding.fixture.built", "proj");
            Path home = path(ctx, "onboarding.bootstrapped", "projectHome");
            if (proj == null || home == null) {
                return NodeResult.fail("onboarding.clone.drops.foreign.claims",
                        "missing upstream context");
            }

            // --- the capture taken before any command could rewrite it --------
            List<String> sourceForeign = lines(ctx, "sourceForeignLedger");
            List<String> cloneForeign = lines(ctx, "cloneForeignLedger");
            List<String> sourceChildHomes = lines(ctx, "sourceForeignChildHomes");
            List<String> cloneChildHomes = lines(ctx, "cloneForeignChildHomes");

            boolean theSourceHomeCarriesForeignBindingRecords = sourceForeign.size() >= 2;
            boolean theSourceHomeCarriesForeignChildHomeRecords = sourceChildHomes.size() >= 2;
            // The predicate is shown FIRING before it is trusted to report zero.
            boolean theForeignPredicateFlagsThePlantedRecords =
                    theSourceHomeCarriesForeignBindingRecords
                            && theSourceHomeCarriesForeignChildHomeRecords;

            boolean theCloneHoldsNoBindingTargetingAnotherCheckout = cloneForeign.isEmpty();
            boolean theCloneHoldsNoChildHomeRecordNamingAnotherCheckout =
                    cloneChildHomes.isEmpty();

            // --- the parser + predicate, shown discriminating -------------------
            String synthetic = "ID                   UNIT     SUB-ELEMENT   TARGET"
                    + "   POLICY   MANAGED-BY\n"
                    + "default:claude:a     a                      " + proj
                    + "/.claude/skills   error   default-agent\n"
                    + "project:x:unit:b     b                      /nowhere/else"
                    + "/.claude/skills   error   project\n\n"
                    + "⚠ skills with outstanding errors (1) - trailing banner\n";
            List<OnboardingSupport.Binding> syntheticRows =
                    OnboardingSupport.bindings(synthetic);
            List<OnboardingSupport.Binding> syntheticForeign =
                    OnboardingSupport.foreignBindings(syntheticRows, proj);
            boolean theParserAndPredicateFlagAKnownForeignRow =
                    syntheticRows.size() == 2 && syntheticForeign.size() == 1
                            && syntheticForeign.get(0).unit().equals("b");

            // --- the operator-visible symptom, read-only -------------------------
            //
            // --dry-run only. A real uninstall of a unit carrying an inherited
            // claim would write into the very directories this assertion is
            // about, which is not a thing a test may do to prove a point.
            List<String> refusalsNamingAForeignHome = new ArrayList<>();
            List<ProcessRecord> dryRuns = new ArrayList<>();
            for (String unit : OnboardingSupport.storeUnits(home)) {
                ProcessRecord dry = OnboardingSupport.sm(ctx, "dry-uninstall-" + unit, home, proj,
                        "uninstall", unit, "--dry-run");
                dryRuns.add(dry);
                String out = OnboardingSupport.log(ctx, dry);
                if (out.contains("claimed by child skill-manager home(s)")) {
                    refusalsNamingAForeignHome.add(unit + ": " + firstLine(out));
                }
            }
            boolean noUninstallIsRefusedByAnInheritedChildHomeClaim =
                    refusalsNamingAForeignHome.isEmpty();

            boolean pass = theForeignPredicateFlagsThePlantedRecords
                    && theSourceHomeCarriesForeignBindingRecords
                    && theSourceHomeCarriesForeignChildHomeRecords
                    && theParserAndPredicateFlagAKnownForeignRow
                    && theCloneHoldsNoBindingTargetingAnotherCheckout
                    && theCloneHoldsNoChildHomeRecordNamingAnotherCheckout
                    && noUninstallIsRefusedByAnInheritedChildHomeClaim;

            NodeResult result = pass
                    ? NodeResult.pass("onboarding.clone.drops.foreign.claims")
                    : NodeResult.fail("onboarding.clone.drops.foreign.claims",
                            "sourceForeignBindings=" + sourceForeign.size()
                                    + " sourceForeignChildHomes=" + sourceChildHomes.size()
                                    + " cloneForeignBindings=" + head(cloneForeign)
                                    + " cloneForeignChildHomes=" + cloneChildHomes
                                    + " syntheticRows=" + syntheticRows.size()
                                    + " syntheticForeign=" + syntheticForeign.size()
                                    + " refusals=" + refusalsNamingAForeignHome);
            for (ProcessRecord p : dryRuns) result = result.process(p);
            return result
                    .assertion("the_source_home_carries_at_least_two_foreign_binding_records",
                            theSourceHomeCarriesForeignBindingRecords)
                    .assertion("the_source_home_carries_at_least_two_foreign_child_home_records",
                            theSourceHomeCarriesForeignChildHomeRecords)
                    .assertion("the_outside_the_root_predicate_flags_the_planted_records",
                            theForeignPredicateFlagsThePlantedRecords)
                    .assertion("the_listing_parser_and_predicate_flag_a_known_foreign_row",
                            theParserAndPredicateFlagAKnownForeignRow)
                    .assertion("the_clone_holds_no_binding_targeting_another_checkout",
                            theCloneHoldsNoBindingTargetingAnotherCheckout)
                    .assertion("the_clone_holds_no_child_home_record_naming_another_checkout",
                            theCloneHoldsNoChildHomeRecordNamingAnotherCheckout)
                    .assertion("no_uninstall_is_refused_by_an_inherited_child_home_claim",
                            noUninstallIsRefusedByAnInheritedChildHomeClaim)
                    .metric("sourceForeignBindings", sourceForeign.size())
                    .metric("sourceForeignChildHomes", sourceChildHomes.size())
                    .metric("cloneForeignBindings", cloneForeign.size())
                    .metric("cloneForeignChildHomes", cloneChildHomes.size())
                    .log("source foreign bindings: " + head(sourceForeign))
                    .log("clone foreign bindings: " + head(cloneForeign))
                    .log("clone foreign child-homes: " + cloneChildHomes)
                    .log("refusals naming a foreign home: " + refusalsNamingAForeignHome);
        });
    }

    private static List<String> lines(NodeContext ctx, String key) {
        String raw = ctx.get("onboarding.bootstrapped", key).orElse("");
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\n", -1)) {
            if (!line.isBlank()) out.add(line.strip());
        }
        return out;
    }

    private static String head(List<String> rows) {
        return rows.size() <= 10 ? rows.toString()
                : rows.subList(0, 10) + " (+" + (rows.size() - 10) + " more)";
    }

    /**
     * The first line that is actually the command's output.
     *
     * <p>A JVM launched with {@code JAVA_TOOL_OPTIONS} prints
     * {@code Picked up JAVA_TOOL_OPTIONS: …} to stderr before anything else,
     * and merged capture puts it first — so the evidence this node recorded for
     * a refusal was the sandbox's own banner rather than the refusal.
     */
    private static String firstLine(String text) {
        for (String line : text.split("\n", -1)) {
            String s = line.strip();
            if (s.isBlank() || s.startsWith("Picked up JAVA_TOOL_OPTIONS")) continue;
            return s;
        }
        return "";
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
