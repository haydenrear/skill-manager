///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ChildHomeSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Throwaway project fixture for the child-home materialization graph.
 *
 * <p>Scaffolds four installable skill units plus one extra unit that is
 * installed into the parent store but never claimed by the project. That extra
 * unit is the target of a symlink injected later into two parent-store units,
 * so the graph can exercise the dereference path (Core.tla {@code LinkedSourceA}).
 */
public class ChildHomeProjectFixture {
    static final NodeSpec SPEC = NodeSpec.of("child.home.project.fixture")
            .kind(NodeSpec.Kind.FIXTURE)
            .dependsOn("env.prepared")
            .tags("child-home", "project", "fixture")
            .timeout("180s")
            .output("projectDir", "string")
            .output("unitsDir", "string")
            .output("childHome", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("child.home.project.fixture", "missing env.prepared.home");
            }
            Path root = Path.of(home, "chm-fixture");
            Path unitsDir = root.resolve("units");
            Path projectDir = root.resolve("project");
            Files.createDirectories(unitsDir);
            Files.createDirectories(projectDir);

            for (String unit : new String[] {
                    ChildHomeSupport.UNIT_A, ChildHomeSupport.UNIT_B,
                    ChildHomeSupport.UNIT_C, ChildHomeSupport.UNIT_D }) {
                ChildHomeSupport.scaffoldSkill(unitsDir, unit, "rev1");
            }
            ChildHomeSupport.scaffoldSkill(unitsDir, ChildHomeSupport.LINKED_SOURCE, "rev1");
            Files.writeString(unitsDir.resolve(ChildHomeSupport.LINKED_SOURCE).resolve("NOTE.md"),
                    "linked rev1\n");

            Files.writeString(projectDir.resolve("skill-project.toml"),
                    ChildHomeSupport.projectManifest(unitsDir,
                            ChildHomeSupport.UNIT_A, ChildHomeSupport.UNIT_B,
                            ChildHomeSupport.UNIT_C, ChildHomeSupport.UNIT_D));

            // The link target must exist in the parent store before anything can
            // point at it, and it must NOT be a project dependency: a unit only
            // reachable through an in-unit symlink is what makes the
            // dereference-and-converge behavior observable.
            ProcessRecord install = ChildHomeSupport.sm(ctx, "install-linked-source", home,
                    "install", unitsDir.resolve(ChildHomeSupport.LINKED_SOURCE).toString(), "--yes");

            boolean unitsScaffolded = Files.isRegularFile(unitsDir.resolve(
                    ChildHomeSupport.UNIT_A).resolve("SKILL.md"))
                    && Files.isRegularFile(unitsDir.resolve(ChildHomeSupport.UNIT_D).resolve("SKILL.md"));
            boolean manifestWritten = ChildHomeSupport.read(projectDir.resolve("skill-project.toml"))
                    .contains(ChildHomeSupport.PROJECT_NAME);
            Path linkedInStore = ChildHomeSupport.parentUnit(Path.of(home), ChildHomeSupport.LINKED_SOURCE);
            boolean linkedInstalled = install.exitCode() == 0
                    && Files.isRegularFile(linkedInStore.resolve("NOTE.md"));

            boolean pass = unitsScaffolded && manifestWritten && linkedInstalled;
            return (pass
                    ? NodeResult.pass("child.home.project.fixture")
                    : NodeResult.fail("child.home.project.fixture",
                            "unitsScaffolded=" + unitsScaffolded
                                    + " manifestWritten=" + manifestWritten
                                    + " linkedInstalled=" + linkedInstalled
                                    + " installExit=" + install.exitCode()))
                    .process(install)
                    .assertion("fixture_units_scaffolded", unitsScaffolded)
                    .assertion("project_manifest_written", manifestWritten)
                    .assertion("link_target_unit_installed_in_parent_store", linkedInstalled)
                    .metric("installExitCode", install.exitCode())
                    .publish("projectDir", projectDir.toString())
                    .publish("unitsDir", unitsDir.toString())
                    .publish("childHome", ChildHomeSupport.childHome(projectDir).toString());
        });
    }
}
