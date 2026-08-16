///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeIntegrity.java
//SOURCES HomeIntegritySupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A harness instance's template is installed.
 *
 * <p>#124's defect 8, kept as written. Measured in the operator's project home:
 * five records under {@code harnesses/instances/<id>/.harness-instance.json},
 * each naming a {@code harnessName} of {@code learning-app-run-*}, and
 * {@code units.lock.toml} carrying <b>no unit of kind {@code harness}</b> at
 * all — 17 skills, 2 plugins, 1 doc. Each instance directory is otherwise
 * empty and the project tree it names lives in a different repository.
 *
 * <p>Unlike defect 7 there is no design reason for this state. It is an
 * uninstall that did not reach its instances, and an instance whose template is
 * gone can be neither re-fingerprinted, re-instantiated nor verified. The fix
 * belongs with <b>#120</b>, which owns the artifact producers that are not CLI
 * installers; ARTI-06 recorded that {@code HARNESS_INSTANCE} is inherently
 * per-item and cheap to make buildable "once #120 gives them something to
 * compare".
 *
 * <h2>Why the control is a planted instance and not the fixture home</h2>
 *
 * <p>The fixture home has no harness instances, so the check examines zero
 * subjects there and "holds" without looking at anything. That is exactly the
 * vacuous pass this graph is built to avoid, so rather than assert it, this
 * node plants a <em>valid</em> instance — one whose template really is
 * installed — and asserts that it is accepted. Then it plants the defect and
 * asserts that it is not. The healthy case and the broken case differ in one
 * field, and both are exercised.
 */
public class InstanceTemplateInstalled {

    static final NodeSpec SPEC = NodeSpec.of("home.integrity.instance.template.installed")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.integrity.fixture")
            .tags("home", "integrity", "harness")
            .timeout("180s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String homeStr = ctx.get("home.integrity.fixture", "integrityHome").orElse(null);
            String scratchStr = ctx.get("home.integrity.fixture", "scratchRoot").orElse(null);
            if (homeStr == null || scratchStr == null) return unproven("missing fixture context");
            Path home = Path.of(homeStr);
            Path scratch = Path.of(scratchStr);

            HomeIntegrity.Report fresh = HomeIntegrity.instanceTemplateInstalled(home);

            HomeIntegritySupport.Mutation valid;
            HomeIntegritySupport.Mutation orphaned;
            HomeIntegritySupport.Mutation nameless;
            try {
                // The control: a template that IS installed.
                valid = HomeIntegritySupport.probe(home, scratch, "instance_of_a_live_template",
                        new PlantInstance(HomeIntegritySupport.UNIT),
                        HomeIntegrity::instanceTemplateInstalled);
                // The defect: the operator's five, in miniature.
                orphaned = HomeIntegritySupport.probe(home, scratch,
                        "instance_of_an_uninstalled_template",
                        new PlantInstance("hi-harness-that-is-gone"),
                        HomeIntegrity::instanceTemplateInstalled);
                // The record that says nothing at all, which a check keyed on
                // "is this name installed" would skip rather than report.
                nameless = HomeIntegritySupport.probe(home, scratch, "instance_naming_no_template",
                        new PlantInstance(null), HomeIntegrity::instanceTemplateInstalled);
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }

            boolean freshHolds = fresh.holds();
            boolean validAccepted = !valid.caught();

            boolean pass = freshHolds && validAccepted
                    && orphaned.caught() && orphaned.repaired()
                    && nameless.caught() && nameless.repaired();

            NodeResult result = pass
                    ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(),
                            "freshHolds=" + freshHolds
                                    + " examined=" + fresh.examined()
                                    + " validAccepted=" + validAccepted
                                    + " orphanedCaught=" + orphaned.caught()
                                    + " orphanedRepaired=" + orphaned.repaired()
                                    + " namelessCaught=" + nameless.caught()
                                    + " namelessRepaired=" + nameless.repaired()
                                    + " | " + fresh.describe());

            result = HomeIntegritySupport.withReport(result, fresh)
                    .assertion("an_instance_whose_template_is_installed_is_accepted",
                            validAccepted);
            result = HomeIntegritySupport.withMutation(result, orphaned);
            result = HomeIntegritySupport.withMutation(result, nameless);
            return result.log(valid.name() + ": " + valid.evidence());
        });
    }

    /**
     * Write a harness instance record naming {@code template}, or naming none.
     *
     * <p>Instantiating a real harness would require a harness unit, its
     * template tree and its lifecycle — none of which this invariant is about.
     * The invariant relates one field of one record to the installed set, and
     * the record is the whole subject.
     */
    private record PlantInstance(String template) implements HomeIntegritySupport.Damage {
        private static final String ID = "hi-instance";

        @Override
        public void plant(Path home) throws IOException {
            Path dir = home.resolve("harnesses").resolve("instances").resolve(ID);
            Files.createDirectories(dir);
            String body = template == null
                    ? """
                      {
                        "instanceId" : "%s",
                        "createdAt" : "2026-08-16T00:00:00Z"
                      }
                      """.formatted(ID)
                    : """
                      {
                        "harnessName" : "%s",
                        "instanceId" : "%s",
                        "createdAt" : "2026-08-16T00:00:00Z"
                      }
                      """.formatted(template, ID);
            Files.writeString(dir.resolve(".harness-instance.json"), body);
        }

        @Override
        public void repair(Path home) throws IOException {
            HomeIntegritySupport.deleteRecursively(
                    home.resolve("harnesses").resolve("instances").resolve(ID));
        }
    }

    private static NodeResult unproven(String why) {
        return NodeResult.fail(SPEC.id(), why)
                .assertion("an_instance_whose_template_is_installed_is_accepted", false)
                .assertion("the_planted_instance_of_an_uninstalled_template_is_caught", false)
                .assertion("repairing_the_planted_instance_of_an_uninstalled_template_clears_it",
                        false)
                .assertion("the_planted_instance_naming_no_template_is_caught", false)
                .assertion("repairing_the_planted_instance_naming_no_template_clears_it", false);
    }
}
