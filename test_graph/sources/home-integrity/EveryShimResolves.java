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
 * Every {@code bin/cli} entry runs.
 *
 * <p>The invariant is #124's fifth, with its location clause removed, and
 * {@link HomeIntegrity#everyShimResolves} carries the argument: a
 * {@code brew}-backed shim legitimately points at {@code /opt/homebrew}, so
 * "resolves inside this home or a sanctioned parent store" is false of a
 * healthy home and would forbid three of the four CLI backends from ever
 * succeeding. What survives is the part with teeth — a shim that does not run
 * is broken wherever it points.
 *
 * <h2>The three mutations, and why the third one exists</h2>
 *
 * <p>ARTI-01 found this defect in a <b>cloned</b> ticket home:
 * {@code bin/cli/jinja2 -> ../../venvs/jinja2-cli/bin/jinja2} and
 * {@code bin/cli/skill-dev -> ../../cache/uv-tools/skill-dev/bin/skill-dev},
 * dangling because {@code HomeCloner} deliberately does not carry
 * {@code venvs/} or {@code cache/}. Both targets are present in the source
 * home. So the first mutation reproduces the clone's shape directly — a
 * relative link into a tree that is not there.
 *
 * <p>The second mutation removes the executable bit rather than the file. A
 * check written as "does the target exist" passes over it, and that is exactly
 * the presence-versus-truth substitution this whole epic exists to remove. It
 * is included because it is the mutation a lazily-written version of this check
 * would survive.
 *
 * <p>The third asserts a <b>non-detection</b>: a shim pointing at a real
 * executable outside the home is fine. Without it, someone repairing this file
 * later could "strengthen" the check back into #124's wording, every fixture
 * would still pass — the fixture's only CLI dep is a skill-script one, which
 * lives inside the home — and the regression would ship. The assertion is the
 * argument, kept executable.
 */
public class EveryShimResolves {

    static final NodeSpec SPEC = NodeSpec.of("home.integrity.every.shim.resolves")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.integrity.fixture")
            .tags("home", "integrity", "cli")
            .timeout("180s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String homeStr = ctx.get("home.integrity.fixture", "integrityHome").orElse(null);
            String scratchStr = ctx.get("home.integrity.fixture", "scratchRoot").orElse(null);
            if (homeStr == null || scratchStr == null) return unproven("missing fixture context");
            Path home = Path.of(homeStr);
            Path scratch = Path.of(scratchStr);

            HomeIntegrity.Report fresh = HomeIntegrity.everyShimResolves(home);

            HomeIntegritySupport.Mutation dangling;
            HomeIntegritySupport.Mutation notExecutable;
            HomeIntegritySupport.Mutation outsideHome;
            try {
                dangling = HomeIntegritySupport.probe(home, scratch, "dangling_shim",
                        new IntoAMissingTree(), HomeIntegrity::everyShimResolves);
                notExecutable = HomeIntegritySupport.probe(home, scratch, "unexecutable_shim",
                        new StripExecuteBit(), HomeIntegrity::everyShimResolves);
                outsideHome = HomeIntegritySupport.probe(home, scratch, "shim_outside_the_home",
                        new PointAtSystemBinary(), HomeIntegrity::everyShimResolves);
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }

            boolean freshHolds = fresh.holdsNonVacuously();
            // The third mutation must NOT be caught — that is its whole point.
            boolean externalTolerated = !outsideHome.caught();

            boolean pass = freshHolds
                    && dangling.caught() && dangling.repaired()
                    && notExecutable.caught() && notExecutable.repaired()
                    && externalTolerated;

            NodeResult result = pass
                    ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(),
                            "freshHolds=" + freshHolds
                                    + " examined=" + fresh.examined()
                                    + " danglingCaught=" + dangling.caught()
                                    + " danglingRepaired=" + dangling.repaired()
                                    + " notExecutableCaught=" + notExecutable.caught()
                                    + " notExecutableRepaired=" + notExecutable.repaired()
                                    + " externalTolerated=" + externalTolerated
                                    + " | " + fresh.describe());

            result = HomeIntegritySupport.withReport(result, fresh)
                    .assertion("every_shim_in_a_fresh_home_resolves_to_an_executable", freshHolds);
            result = HomeIntegritySupport.withMutation(result, dangling);
            result = HomeIntegritySupport.withMutation(result, notExecutable);
            return result
                    .assertion("a_shim_resolving_outside_the_home_is_not_reported_as_broken",
                            externalTolerated)
                    .log(outsideHome.name() + ": " + outsideHome.evidence());
        });
    }

    /** The clone's shape: a relative link into a tree the clone does not carry. */
    private record IntoAMissingTree() implements HomeIntegritySupport.Damage {
        private static final String NAME = "hi-venv-tool";

        @Override
        public void plant(Path home) throws IOException {
            Path shim = home.resolve("bin").resolve("cli").resolve(NAME);
            Files.createDirectories(shim.getParent());
            Files.deleteIfExists(shim);
            Files.createSymbolicLink(shim, Path.of("../../venvs/hi-venv/bin/" + NAME));
        }

        @Override
        public void repair(Path home) throws IOException {
            Files.deleteIfExists(home.resolve("bin").resolve("cli").resolve(NAME));
        }
    }

    /** Present, and still does not run. */
    private record StripExecuteBit() implements HomeIntegritySupport.Damage {
        @Override
        public void plant(Path home) {
            home.resolve("bin").resolve("cli").resolve(HomeIntegritySupport.TOOL)
                    .toFile().setExecutable(false);
        }

        @Override
        public void repair(Path home) {
            home.resolve("bin").resolve("cli").resolve(HomeIntegritySupport.TOOL)
                    .toFile().setExecutable(true);
        }
    }

    /** A brew-shaped shim: correct, and outside every home. */
    private record PointAtSystemBinary() implements HomeIntegritySupport.Damage {
        private static final String NAME = "hi-external-tool";

        @Override
        public void plant(Path home) throws IOException {
            String target = HomeIntegrity.onSystemPath("sh");
            if (target == null) target = "/bin/sh";
            Path shim = home.resolve("bin").resolve("cli").resolve(NAME);
            Files.createDirectories(shim.getParent());
            Files.deleteIfExists(shim);
            Files.createSymbolicLink(shim, Path.of(target));
        }

        @Override
        public void repair(Path home) throws IOException {
            Files.deleteIfExists(home.resolve("bin").resolve("cli").resolve(NAME));
        }
    }

    private static NodeResult unproven(String why) {
        return NodeResult.fail(SPEC.id(), why)
                .assertion("every_shim_in_a_fresh_home_resolves_to_an_executable", false)
                .assertion("the_planted_dangling_shim_is_caught", false)
                .assertion("repairing_the_planted_dangling_shim_clears_it", false)
                .assertion("the_planted_unexecutable_shim_is_caught", false)
                .assertion("repairing_the_planted_unexecutable_shim_clears_it", false)
                .assertion("a_shim_resolving_outside_the_home_is_not_reported_as_broken", false);
    }
}
