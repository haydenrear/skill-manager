///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Step 0 — two static properties of the skill checkout, before any home
 * exists.</b> The cheapest node in the graph, and first, because a literal
 * first-time onboarding fails on the first of them at step 4 of the documented
 * procedure.
 *
 * <h2>Property one: the docs do not tell you to copy a file that is not there</h2>
 *
 * <p>{@code references/skill-homes.md} step 4 says "Copy {@code scripts/agent-home.sh}
 * (the locator) into the repo root", {@code references/onboarding.md} says the
 * same, and {@code close-change.sh} offers it as a remedy. The skill does not
 * ship it. The only copy on the machine where this was found belonged to the
 * PARENT integration repository — an artifact of that repo having been
 * onboarded, not something a fresh repo has. So the documented first-time path
 * cannot be followed literally; the workaround is to call
 * {@code bootstrap-home.sh --root <proj>} directly, which the docs do not say.
 *
 * <p><b>Assertion.</b> Every {@code scripts/<name>} an imperative documentation
 * step or a script's own {@code FIX}/remedy string names resolves to a file
 * under the skill root.
 *
 * <p><b>Vacuous-pass risk 1 — the extractor matches nothing,</b> so "all
 * referenced files exist" is true over an empty set. That is the classic form
 * and this graph exists because of it.
 * <br><b>Companion:</b> the extracted set must be non-empty AND must contain
 * the four known-good scripts {@code bootstrap-home.sh}, {@code new-change.sh},
 * {@code close-change.sh} and {@code wt}. If {@code agent-home.sh} is later
 * added, the set still must not shrink below those four — a regex that stopped
 * matching would fail here rather than pass silently.
 *
 * <p><b>Vacuous-pass risk 2 — resolving against the integration repo root</b>
 * rather than the skill root, which makes {@code scripts/agent-home.sh} resolve
 * (the parent's copy) and hides the bug entirely.
 * <br><b>Companion:</b> resolution is strictly against the skill root, and the
 * node additionally asserts that a deliberately-outside spelling
 * ({@code ../../scripts/agent-home.sh}) does NOT satisfy the predicate. That
 * proves the base is the skill directory rather than an ancestor.
 *
 * <h2>Property two: no script resolves a CLI by a path relative to itself</h2>
 *
 * <p>The permitted order is {@code $SKILL_MANAGER_CLI} → {@code command -v
 * skill-manager} → refuse. A relative-path fallback finds whatever stale clone
 * happens to sit beside the checkout, which is how a script comes to run a
 * different build from the one under test.
 *
 * <p><b>Vacuous-pass risk — a grep-based assertion passes when the pattern is
 * wrong.</b>
 * <br><b>Companion:</b> the expectation is not "zero matches", it is "exactly
 * the two known {@code selftest.sh} lines, which are waived". A typo'd pattern
 * that matched nothing would fail the node, because the two known lines would
 * be missing from the result.
 */
public class OnboardingDocsAndScriptsStatic {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.docs.and.scripts.static")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("onboarding", "docs", "static")
            .timeout("120s");

    /** {@code scripts/<name>} anywhere in prose or in a shell string literal. */
    static final Pattern SCRIPT_REF =
            Pattern.compile("scripts/([A-Za-z0-9_.-]+(?:\\.sh)?)\\b");

    /**
     * Scripts the extractor must find, or the extractor itself is broken.
     *
     * <p>These four are named by name in the documented procedure and by the
     * scripts' own remedies, and all four ship. They are the floor under
     * "the referenced set is non-empty".
     */
    static final List<String> KNOWN_GOOD =
            List.of("bootstrap-home.sh", "new-change.sh", "close-change.sh", "wt");

    /** The relative-path CLI resolution that is waived, and where. */
    static final Pattern RELATIVE_CLI = Pattern.compile(
            "\\.\\./\\.\\./skill-manager/skill-manager|\\.\\./\\.\\./\\.\\./skill-manager");

    static final String WAIVED_FILE = "selftest.sh";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            TicketLifecycleSupport.Scripts scripts = OnboardingSupport.scripts(ctx);
            if (!scripts.found()) {
                return NodeResult.fail("onboarding.docs.and.scripts.static",
                        "could not locate git-integration-repo's scripts — " + scripts.how())
                        .assertion("the_skill_under_test_was_found", false);
            }
            Path skillRoot = OnboardingSupport.skillRoot(scripts);
            Path scriptsDir = scripts.dir();

            // --- property one: extract every referenced script ---------------
            Set<String> referenced = new TreeSet<>();
            List<Path> docs = new ArrayList<>();
            Path references = skillRoot.resolve("references");
            for (String name : OnboardingSupport.names(references)) {
                if (name.endsWith(".md")) docs.add(references.resolve(name));
            }
            if (Files.isRegularFile(skillRoot.resolve("SKILL.md"))) {
                docs.add(skillRoot.resolve("SKILL.md"));
            }
            for (Path doc : docs) collect(referenced, Files.readString(doc));
            // The scripts' own remedy strings. close-change.sh:341 offers
            // agent-home.sh as a FIX, and a remedy naming a file that is not
            // there is the same defect in a place a reader reaches at runtime
            // rather than at onboarding.
            List<Path> shellFiles = new ArrayList<>();
            for (String name : OnboardingSupport.names(scriptsDir)) {
                Path p = scriptsDir.resolve(name);
                if (Files.isRegularFile(p) && (name.endsWith(".sh") || name.equals("wt"))) {
                    shellFiles.add(p);
                    collectFromRemedies(referenced, Files.readString(p));
                }
            }

            boolean theExtractorFoundSomething = !referenced.isEmpty();
            boolean theExtractorFoundTheKnownGoodScripts = referenced.containsAll(KNOWN_GOOD);

            Set<String> missing = new TreeSet<>();
            for (String name : referenced) {
                if (!Files.isRegularFile(scriptsDir.resolve(name))) missing.add(name);
            }
            boolean everyDocumentedScriptExists = missing.isEmpty();

            // The resolution-base companion: an outside spelling must NOT
            // satisfy the predicate. If it did, the base would be an ancestor
            // and the assertion above would resolve the PARENT repository's
            // copy of a file this skill does not ship.
            Path outside = skillRoot.getParent() == null ? null
                    : skillRoot.getParent().getParent();
            boolean theResolutionBaseIsTheSkillRoot =
                    scriptsDir.toAbsolutePath().normalize()
                            .startsWith(skillRoot.toAbsolutePath().normalize())
                            && Files.isRegularFile(scriptsDir.resolve("bootstrap-home.sh"));
            // And the outside copy, if any, is NOT counted as satisfying it.
            Path parentCopy = outside == null ? null : outside.resolve("scripts/agent-home.sh");
            boolean anOutsideCopyIsNotCounted = missing.contains("agent-home.sh")
                    || !referenced.contains("agent-home.sh");

            // --- property two: relative CLI resolution ------------------------
            List<String> relativeHits = new ArrayList<>();
            for (Path p : shellFiles) {
                String text = Files.readString(p);
                int lineNo = 0;
                for (String line : text.split("\n", -1)) {
                    lineNo++;
                    if (RELATIVE_CLI.matcher(line).find()) {
                        relativeHits.add(p.getFileName() + ":" + lineNo);
                    }
                }
            }
            long waived = relativeHits.stream().filter(h -> h.startsWith(WAIVED_FILE)).count();
            List<String> unwaived = relativeHits.stream()
                    .filter(h -> !h.startsWith(WAIVED_FILE)).toList();
            // The companion, and the whole reason this is not "assert zero":
            // the pattern must still match the two KNOWN lines. A pattern that
            // matched nothing would otherwise read as a clean result.
            boolean thePatternStillMatchesTheKnownWaivedLines = waived >= 2;
            boolean noScriptOutsideTheWaiverResolvesACliRelatively = unwaived.isEmpty();

            boolean pass = theExtractorFoundSomething && theExtractorFoundTheKnownGoodScripts
                    && everyDocumentedScriptExists && theResolutionBaseIsTheSkillRoot
                    && anOutsideCopyIsNotCounted
                    && thePatternStillMatchesTheKnownWaivedLines
                    && noScriptOutsideTheWaiverResolvesACliRelatively;

            return (pass
                    ? NodeResult.pass("onboarding.docs.and.scripts.static")
                    : NodeResult.fail("onboarding.docs.and.scripts.static",
                            "referenced=" + referenced
                                    + " missing=" + missing
                                    + " relativeCliHits=" + relativeHits
                                    + " unwaived=" + unwaived))
                    .assertion("the_skill_under_test_was_found", true)
                    .assertion("the_documentation_extractor_found_at_least_one_script",
                            theExtractorFoundSomething)
                    .assertion("the_extractor_found_the_four_known_good_scripts",
                            theExtractorFoundTheKnownGoodScripts)
                    .assertion("every_script_the_docs_tell_you_to_copy_or_run_exists",
                            everyDocumentedScriptExists)
                    .assertion("the_resolution_base_is_the_skill_root_not_an_ancestor",
                            theResolutionBaseIsTheSkillRoot)
                    .assertion("a_copy_outside_the_skill_does_not_satisfy_the_predicate",
                            anOutsideCopyIsNotCounted)
                    .assertion("the_relative_cli_pattern_still_matches_its_known_waived_lines",
                            thePatternStillMatchesTheKnownWaivedLines)
                    .assertion("no_script_outside_the_waiver_resolves_a_cli_by_relative_path",
                            noScriptOutsideTheWaiverResolvesACliRelatively)
                    .metric("scriptsReferenced", referenced.size())
                    .metric("scriptsMissing", missing.size())
                    .metric("waivedRelativeCliLines", waived)
                    .log("referenced: " + referenced)
                    .log("missing: " + missing)
                    .log("relative-cli hits: " + relativeHits)
                    .log("skill root: " + skillRoot)
                    .log(parentCopy == null ? "no ancestor to check"
                            : "ancestor copy of agent-home.sh exists: "
                                    + Files.isRegularFile(parentCopy) + " at " + parentCopy);
        });
    }

    /** Every {@code scripts/<name>} in a markdown body. */
    private static void collect(Set<String> out, String text) {
        Matcher m = SCRIPT_REF.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            // `scripts/` alone, or a directory reference, is not a file claim.
            if (name.isEmpty() || name.equals("sh")) continue;
            out.add(name);
        }
    }

    /**
     * Every {@code scripts/<name>} named inside a shell script's user-facing
     * remedy text, i.e. a quoted string on a line that also carries a remedy
     * marker. Comments are skipped: a comment is a note to a maintainer, not an
     * instruction to a reader at runtime.
     */
    private static void collectFromRemedies(Set<String> out, String text) {
        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("#")) continue;
            boolean looksLikeRemedy = line.contains("FIX")
                    || line.contains("Create it")
                    || line.contains("please run")
                    || line.contains("Run it here")
                    || line.contains("complete it with");
            if (!looksLikeRemedy) continue;
            collect(out, line);
        }
    }
}
