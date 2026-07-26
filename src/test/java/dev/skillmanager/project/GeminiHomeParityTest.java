package dev.skillmanager.project;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.agent.Agent;
import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.ChildHomeHarnessInstaller;
import dev.skillmanager.bindings.HarnessInstantiator;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.HarnessParser;
import dev.skillmanager.model.HarnessUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Gemini is a first-class agent home, not one derived from Codex's.
 *
 * <p>Skill projection reached parity with Claude and Codex before this
 * suite existed — {@link GeminiProjector} is in
 * {@link ProjectorRegistry#defaultRegistry()},
 * {@link HarnessInstantiator} plans a Gemini projection per skill,
 * {@code ProjectDependencyResolver} projects into {@code .gemini/skills},
 * and {@code ProjectRemoveUseCase} tears it down again. What was missing
 * was any test pinning that as an <em>invariant</em>, so nothing stopped a
 * new agent-enumerating code path from quietly dropping Gemini the way the
 * legacy {@link HarnessInstantiator} overload had.
 *
 * <p>So these assert the shape rather than a single call: every agent
 * skill-manager knows about must be projected, and a home derived rather
 * than declared must still land where the layout says it does.
 */
public final class GeminiHomeParityTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("GeminiHomeParityTest");

        suite.test("every known agent has a projector in the default registry", () -> {
            List<String> agents = Agent.all().stream().map(Agent::id).sorted().toList();
            List<String> projectors = ProjectorRegistry.defaultRegistry().projectors()
                    .stream().map(Projector::agentId).sorted().toList();
            assertEquals(agents, projectors,
                    "the projector set must track the agent set — a home with no projector "
                            + "gets an empty skills dir and no error");
            assertTrue(agents.contains("gemini"), "gemini is one of them");
        });

        suite.test("Gemini plans the same skill projection shape as Codex", () -> {
            Path store = Files.createTempDirectory("gemini-parity-store-");
            SkillStore skillStore = new SkillStore(store);
            skillStore.init();
            AgentUnit unit = installSkill(skillStore, "widget");
            Path codexRoot = Files.createTempDirectory("gemini-parity-codex-");
            Path geminiRoot = Files.createTempDirectory("gemini-parity-gemini-");

            List<Projection> codex = new CodexProjector(
                    codexRoot.resolve("skills"), codexRoot.resolve("plugins"))
                    .planProjection(unit, skillStore);
            List<Projection> gemini = new GeminiProjector(
                    geminiRoot.resolve("skills"), geminiRoot.resolve("plugins"))
                    .planProjection(unit, skillStore);

            assertEquals(codex.size(), gemini.size(), "same projection count");
            assertEquals(1, gemini.size(), "one skill projection");
            assertEquals(codex.get(0).source(), gemini.get(0).source(),
                    "both project the same store bytes");
            assertEquals(geminiRoot.resolve("skills").resolve("widget"), gemini.get(0).target(),
                    "into the Gemini home's own skills dir");
            assertEquals("gemini", gemini.get(0).agentId(), "attributed to gemini");
        });

        suite.test("the child-home layout declares a dotted .gemini beside .claude/.codex", () -> {
            Path target = Files.createTempDirectory("gemini-parity-layout-");
            ChildHomeHarnessInstaller.Layout layout = ChildHomeHarnessInstaller.layout(target);
            assertEquals(target.resolve(".claude"), layout.claudeHome(), "claude home");
            assertEquals(target.resolve(".codex"), layout.codexHome(), "codex home");
            assertEquals(target.resolve(".gemini"), layout.geminiHome(),
                    "gemini home is dotted like its siblings");
        });

        suite.test("a derived Gemini home follows its sibling's naming convention", () -> {
            // The legacy HarnessInstantiator.plan overload derives the Gemini
            // home from the Codex one. It used to do that with a flat
            // resolveSibling("gemini"), which is right for the harness sandbox
            // layout (<sandbox>/<id>/codex) and wrong for the child-home
            // layout (<target>/.codex): it produced <target>/gemini, a
            // directory no agent reads and no teardown deletes, while
            // <target>/.gemini stayed empty.
            Fixture fix = fixture("gemini-derived-dotted-");
            Path target = Files.createTempDirectory("gemini-derived-target-");

            HarnessInstantiator.Plan dotted = HarnessInstantiator.plan(
                    fix.harness, "derived-dotted",
                    target.resolve(".claude"), target.resolve(".codex"),
                    target.resolve("project"), fix.store);

            assertEquals(target.resolve(".gemini").resolve("skills").resolve("widget"),
                    geminiTarget(dotted, target),
                    "a dotted Codex home yields a dotted Gemini home");

            Path sandbox = Files.createTempDirectory("gemini-derived-sandbox-");
            HarnessInstantiator.Plan plain = HarnessInstantiator.plan(
                    fix.harness, "derived-plain",
                    sandbox.resolve("claude"), sandbox.resolve("codex"),
                    sandbox.resolve("project"), fix.store);

            assertEquals(sandbox.resolve("gemini").resolve("skills").resolve("widget"),
                    geminiTarget(plain, sandbox),
                    "and the undotted sandbox layout is unchanged");
        });

        suite.test("an explicitly declared Gemini home is used verbatim", () -> {
            Fixture fix = fixture("gemini-explicit-");
            Path sandbox = Files.createTempDirectory("gemini-explicit-sandbox-");
            Path gemini = sandbox.resolve("somewhere/else/gemini-home");

            HarnessInstantiator.Plan plan = HarnessInstantiator.plan(
                    fix.harness, "explicit",
                    sandbox.resolve(".claude"), sandbox.resolve(".codex"), gemini,
                    sandbox.resolve("project"), fix.store);

            Binding skill = plan.bindings().stream()
                    .filter(b -> b.unitKind() == UnitKind.SKILL)
                    .findFirst().orElseThrow();
            assertEquals(3, skill.projections().size(),
                    "one projection per agent home — claude, codex, gemini");
            assertTrue(skill.projections().stream()
                            .anyMatch(p -> p.destPath()
                                    .equals(gemini.resolve("skills").resolve("widget"))),
                    "the declared Gemini home is not second-guessed");
        });

        return suite.runAll();
    }

    /** The Gemini skill destination in {@code plan}: the one under neither claude nor codex. */
    private static Path geminiTarget(HarnessInstantiator.Plan plan, Path root) {
        List<Path> candidates = new ArrayList<>();
        for (Binding b : plan.bindings()) {
            if (b.unitKind() != UnitKind.SKILL) continue;
            for (var p : b.projections()) {
                String rel = root.relativize(p.destPath()).toString();
                if (rel.startsWith(".claude") || rel.startsWith("claude")) continue;
                if (rel.startsWith(".codex") || rel.startsWith("codex")) continue;
                candidates.add(p.destPath());
            }
        }
        assertEquals(1, candidates.size(), "exactly one non-claude, non-codex skill destination");
        return candidates.get(0);
    }

    private record Fixture(SkillStore store, HarnessUnit harness) {}

    /** A store with one skill and a harness template that references it. */
    private static Fixture fixture(String prefix) throws IOException {
        Path home = Files.createTempDirectory(prefix);
        SkillStore store = new SkillStore(home);
        store.init();
        installSkill(store, "widget");

        Path template = Files.createTempDirectory(prefix + "template-");
        Files.writeString(template.resolve("harness.toml"), """
                [harness]
                name = "gemini-parity-harness"
                version = "0.1.0"
                units = ["skill:widget"]
                """);
        Path dst = store.unitDir("gemini-parity-harness", UnitKind.HARNESS);
        Fs.copyRecursive(template, dst);
        new UnitStore(store).write(installedRecord("gemini-parity-harness", UnitKind.HARNESS));
        return new Fixture(store, HarnessParser.load(dst));
    }

    private static AgentUnit installSkill(SkillStore store, String name) throws IOException {
        Path tmp = Files.createTempDirectory("gemini-parity-skill-");
        AgentUnit unit = UnitFixtures.scaffoldSkill(tmp, name, DepSpec.empty()).asUnit();
        Fs.ensureDir(store.skillsDir());
        Fs.copyRecursive(unit.sourcePath(), store.unitDir(name, UnitKind.SKILL));
        new UnitStore(store).write(installedRecord(name, UnitKind.SKILL));
        return unit;
    }

    private static InstalledUnit installedRecord(String name, UnitKind kind) {
        return new InstalledUnit(
                name, "0.1.0", InstalledUnit.Kind.LOCAL_DIR,
                InstalledUnit.InstallSource.LOCAL_FILE, "fixture", null, null,
                UnitStore.nowIso(), List.of(), kind);
    }
}
