package dev.skillmanager.agent;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.Skill;
import dev.skillmanager.project.Projection;
import dev.skillmanager.project.ProjectionOwnership;
import dev.skillmanager.project.Projector;
import dev.skillmanager.project.ProjectorRegistry;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Issue #145: <b>a sync in one home must not write into another home's agent
 * directories.</b>
 *
 * <h2>What actually happened, and what actually caused it</h2>
 *
 * <p>A throwaway project was bootstrapped and synced exactly as
 * {@code bootstrap-home.sh} instructs. Afterwards <b>24</b> of the operator's
 * global agent skill links — 8 each in {@code ~/.claude}, {@code ~/.codex} and
 * {@code ~/.gemini} — pointed into the throwaway project, and would have
 * dangled the moment it was cleaned, silently removing those skills from every
 * global agent session.
 *
 * <p>The issue attributes this to the clone inheriting the source home's
 * binding ledger unrewritten. That inheritance is real and is fixed separately
 * ({@code HomeCloneTest}), but it is <b>not</b> what wrote the links.
 * Reproduced against a fixture {@code $HOME}, the hijack still happens with the
 * cloned home's ledger <em>deleted from disk</em>: {@code SyncAgents} does not
 * read destinations out of the ledger at all, it re-derives them from
 * {@link ProjectorRegistry#defaultRegistry()} on every run — and every agent
 * root's last-resort base was {@code $HOME}, which knows nothing about which
 * home skill-manager was pointed at.
 *
 * <p>So the fix is in the resolution rule ({@link AgentHomes#agentHomeRoot}),
 * and these tests drive the same projector fan-out {@code SyncAgents} drives.
 * A test that asserted on ledger contents would have passed while the links
 * were still being moved.
 *
 * <h2>The same defect, seen from the other end</h2>
 *
 * <p>{@code bootstrap-home.sh} creates {@code <project>/.claude},
 * {@code .codex} and {@code .gemini}, and {@code exec} points
 * {@code CLAUDE_CONFIG_DIR} at them — but nothing ever projected into them, so
 * a markdown skill-import naming a unit in the home had nothing to resolve
 * against. That was the operator's original report, and it is the same missing
 * rule: units went to {@code $HOME}'s agent directories instead of the active
 * home's. Both halves are asserted here, in one test, because a fix that
 * stopped writing the operator's directories without starting to write the
 * project's would satisfy the loud half and leave the quiet one.
 */
public final class AgentProjectionFollowsHomeTest {

    private static final String UNIT = "one-forty-five";
    private static final List<String> AGENT_DIRS = List.of(".claude", ".codex", ".gemini");

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("AgentProjectionFollowsHomeTest");

        suite.test("a sync in a project home leaves the operator's agent homes alone", () -> {
            AgentHomes.clearOverrides();
            Fixture fx = Fixture.create();
            try {
                // Exactly the reported invocation: SKILL_MANAGER_HOME names the
                // project's home, nothing names an agent home, and $HOME is
                // still the operator's. Nothing else is set, because nothing
                // else was set when this happened.
                AgentHomes.setOverride(AgentHomes.HOME, fx.operatorRoot);
                AgentHomes.setOverride(AgentHomes.SKILL_MANAGER_HOME, fx.projectStore.root());

                projectUnitInto(fx.projectStore);

                // THE assertion, and it is stated as "still the operator's own
                // store", not as "unchanged": a link that was deleted and
                // rewritten identically is fine, and a link repointed at any
                // other home is not, whatever else moved.
                for (String agentDir : AGENT_DIRS) {
                    Path link = fx.operatorRoot.resolve(agentDir).resolve("skills").resolve(UNIT);
                    assertTrue(Files.isSymbolicLink(link),
                            "the operator's " + agentDir + " link was removed outright: " + link);
                    assertEquals(fx.operatorStore.root().resolve("skills").resolve(UNIT),
                            Files.readSymbolicLink(link),
                            "the operator's " + agentDir + " link was repointed at another home");
                }

                // And the other half of #145: the project's own agent homes are
                // no longer the empty directories bootstrap-home.sh created.
                for (String agentDir : AGENT_DIRS) {
                    Path link = fx.projectRoot.resolve(agentDir).resolve("skills").resolve(UNIT);
                    assertTrue(Files.isSymbolicLink(link),
                            "the project's own " + agentDir + "/skills is still empty: " + link);
                    assertEquals(fx.projectStore.root().resolve("skills").resolve(UNIT),
                            Files.readSymbolicLink(link),
                            "the project's " + agentDir + " link resolves in its own home");
                }
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        suite.test("the assertion above detects a write into the operator's agent homes", () -> {
            // The companion. An assertion nobody has watched fail is a claim,
            // not a check — and this one has to survive a fix that moves the
            // resolution rule around, so it is anchored to the OUTCOME rather
            // than to any particular resolver.
            //
            // Here the agent roots are pinned at the operator's directories
            // explicitly, which is precisely the state the old fallback
            // produced. If the check above could not see this, it could not
            // have seen the defect either.
            AgentHomes.clearOverrides();
            Fixture fx = Fixture.create();
            try {
                AgentHomes.setOverride(AgentHomes.HOME, fx.operatorRoot);
                AgentHomes.setOverride(AgentHomes.SKILL_MANAGER_HOME, fx.projectStore.root());
                AgentHomes.setOverride(AgentHomes.CLAUDE_CONFIG_DIR,
                        fx.operatorRoot.resolve(".claude"));
                AgentHomes.setOverride(AgentHomes.CODEX_HOME, fx.operatorRoot.resolve(".codex"));
                AgentHomes.setOverride(AgentHomes.GEMINI_HOME, fx.operatorRoot.resolve(".gemini"));

                projectUnitInto(fx.projectStore);

                for (String agentDir : AGENT_DIRS) {
                    Path link = fx.operatorRoot.resolve(agentDir).resolve("skills").resolve(UNIT);
                    assertEquals(fx.projectStore.root().resolve("skills").resolve(UNIT),
                            Files.readSymbolicLink(link),
                            "the hijack is reproducible, so the check above has teeth ("
                                    + agentDir + ")");
                }
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        suite.test("the global home still projects into ~/.claude, ~/.codex, ~/.gemini", () -> {
            // The bound on the change. `~/.skill-manager` has home root `~`, so
            // the operator's own home must behave exactly as it always did —
            // otherwise this fix trades one population's broken links for
            // another's.
            AgentHomes.clearOverrides();
            Fixture fx = Fixture.create();
            try {
                AgentHomes.setOverride(AgentHomes.HOME, fx.operatorRoot);
                AgentHomes.setOverride(AgentHomes.SKILL_MANAGER_HOME, fx.operatorStore.root());

                projectUnitInto(fx.operatorStore);

                for (String agentDir : AGENT_DIRS) {
                    Path link = fx.operatorRoot.resolve(agentDir).resolve("skills").resolve(UNIT);
                    assertEquals(fx.operatorStore.root().resolve("skills").resolve(UNIT),
                            Files.readSymbolicLink(link),
                            "the global home still owns its own agent dirs (" + agentDir + ")");
                }
                assertFalse(Files.exists(fx.projectRoot.resolve(".claude/skills").resolve(UNIT),
                                LinkOption.NOFOLLOW_LINKS),
                        "and does not project into an unrelated project's agent dirs");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        suite.test("an explicit agent-home variable still wins over the active home", () -> {
            // The escape hatch, and the reason this fix does not need a flag:
            // an operator who genuinely wants a non-default store to project
            // into some third directory says so, and is obeyed. It is also what
            // keeps every launcher shim and every TestHarness sandbox — all of
            // which set these four variables — behaving exactly as before.
            AgentHomes.clearOverrides();
            Fixture fx = Fixture.create();
            Path elsewhere = Files.createTempDirectory("sm-145-elsewhere-").toRealPath();
            try {
                AgentHomes.setOverride(AgentHomes.HOME, fx.operatorRoot);
                AgentHomes.setOverride(AgentHomes.SKILL_MANAGER_HOME, fx.projectStore.root());
                AgentHomes.setOverride(AgentHomes.CLAUDE_CONFIG_DIR, elsewhere.resolve(".claude"));

                assertEquals(elsewhere.resolve(".claude"), AgentHomes.claude().configDir(),
                        "explicit CLAUDE_CONFIG_DIR wins");
                AgentHomes.setOverride(AgentHomes.CLAUDE_CONFIG_DIR, null);
                AgentHomes.setOverride(AgentHomes.CLAUDE_HOME, elsewhere);
                assertEquals(elsewhere.resolve(".claude"), AgentHomes.claude().configDir(),
                        "explicit CLAUDE_HOME wins");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        suite.test("undoing a COPIED default-agent row cannot delete another home's link", () -> {
            // The state `home clone` now prevents, arriving by a route it does
            // not control: an rsync, a restored backup, a container image. The
            // row still names the other home's agent directory, and the undo
            // path replays it verbatim.
            //
            // Measured with the CLI before this guard: `uninstall` in such a
            // copy removed the other home's .claude, .codex and .gemini links,
            // printed "checkmark unbound" three times, and exited 0. Nothing in
            // the ownership check objected, and correctly so — every target was
            // a symlink, which is exactly what isOurs calls disposable. The
            // targets were fine. They were in the wrong home.
            AgentHomes.clearOverrides();
            Fixture fx = Fixture.create();
            try {
                AgentHomes.setOverride(AgentHomes.HOME, fx.operatorRoot);
                AgentHomes.setOverride(AgentHomes.SKILL_MANAGER_HOME, fx.projectStore.root());

                Path victim = fx.operatorRoot.resolve(".claude/skills").resolve(UNIT);
                boolean cleared = ProjectionOwnership.clearRecorded(
                        "unproject",
                        dev.skillmanager.effects.LiveInterpreter.defaultBindingId("claude", UNIT),
                        victim,
                        fx.projectStore.root().resolve("skills").resolve(UNIT));

                assertFalse(cleared, "the recorded removal is refused");
                assertTrue(Files.isSymbolicLink(victim),
                        "and the other home's link is still there: " + victim);
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        suite.test("the guard does not fire on this home, nor on managed child homes", () -> {
            // Both bounds, and the second is the one that decides the shape of
            // the whole guard. #145 proposes refusing any operation whose
            // ledger names paths outside the home. That is not implementable: a
            // home legitimately writes into OTHER homes' agent trees, because
            // that is what `project resolve` and child homes ARE. Measured —
            // the broad form broke ProjectDependencyResolverTest's
            // "project remove clears registration child home and bindings",
            // which removes <repo>/.claude/skills/<unit> where <repo> holds a
            // child home. Those rows are BindingSource.PROFILE.
            //
            // So the guard is keyed to the one class that can never legitimately
            // name another home: default-agent, which is derived from the active
            // home's own agent roots and nowhere else.
            AgentHomes.clearOverrides();
            Fixture fx = Fixture.create();
            try {
                AgentHomes.setOverride(AgentHomes.HOME, fx.operatorRoot);
                AgentHomes.setOverride(AgentHomes.SKILL_MANAGER_HOME, fx.projectStore.root());
                Path own = fx.projectRoot.resolve(".claude/skills").resolve(UNIT);
                Files.createSymbolicLink(own, fx.projectStore.root().resolve("skills").resolve(UNIT));

                assertTrue(ProjectionOwnership.clearRecorded("unproject",
                                dev.skillmanager.effects.LiveInterpreter
                                        .defaultBindingId("claude", UNIT),
                                own, fx.projectStore.root().resolve("skills").resolve(UNIT)),
                        "this home's own agent tree is not foreign to it");

                // A PROFILE row into the operator's home — the child-home shape.
                Path managed = fx.operatorRoot.resolve(".claude/skills").resolve(UNIT);
                assertTrue(ProjectionOwnership.clearRecorded("unproject",
                                "project:demo:unit:" + UNIT, managed,
                                fx.operatorStore.root().resolve("skills").resolve(UNIT)),
                        "a project/harness row may manage another home's agent tree");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        suite.test("agentHomeRoot is the store's parent, or the store when unconventional", () -> {
            AgentHomes.clearOverrides();
            try {
                assertEquals(Path.of("/checkout"),
                        AgentHomes.homeRootFor(Path.of("/checkout/.skill-manager")),
                        "the conventional layout resolves to the checkout");
                assertEquals(Path.of("/somewhere/store"),
                        AgentHomes.homeRootFor(Path.of("/somewhere/store")),
                        "a store not named .skill-manager is its own root");
                assertEquals(
                        List.of(Path.of("/checkout/.claude"), Path.of("/checkout/.codex"),
                                Path.of("/checkout/.gemini")),
                        AgentHomes.agentDirsUnder(Path.of("/checkout")),
                        "one list of what an agent home is");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        suite.test("claude(env) does NOT derive the child's config dir from SKILL_MANAGER_HOME", () -> {
            // The bound that the launch gate depends on, and the one place this
            // fix must NOT reach. `claude()` answers "where does skill-manager
            // write"; `claude(env)` answers "where will the Claude CLI read
            // once we hand it this env" — and the Claude CLI has never heard of
            // SKILL_MANAGER_HOME. Teaching the prediction that rule would make
            // LaunchEnv#requireClaudeRedirected accept an env block that
            // redirects nothing, silencing the gate in exactly the case it
            // exists for. Caught by LauncherShimsTest's refusal case when this
            // was first written the other way; asserted here directly so the
            // reason is recorded next to the rule.
            AgentHomes.clearOverrides();
            Path launchHome = Path.of("/launch-home");
            try {
                AgentHomes.ClaudeHome predicted = AgentHomes.claude(Map.of(
                        AgentHomes.SKILL_MANAGER_HOME, "/checkout/.skill-manager",
                        AgentHomes.HOME, launchHome.toString()));
                assertEquals(launchHome.resolve(".claude"), predicted.configDir(),
                        "an env block declaring no Claude variable still predicts $HOME/.claude");
                assertFalse(predicted.configDir().startsWith("/checkout"),
                        "the child's answer is not derived from the store it was pointed at");
                // …while the ambient reader, asked the same thing, does derive it.
                AgentHomes.setOverride(AgentHomes.HOME, launchHome);
                AgentHomes.setOverride(AgentHomes.SKILL_MANAGER_HOME,
                        Path.of("/checkout/.skill-manager"));
                assertEquals(Path.of("/checkout/.claude"), AgentHomes.claude().configDir(),
                        "the two questions have two answers, on purpose");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- fixture

    /**
     * Two homes on one machine: the operator's, with the unit already
     * projected into all three of its agent directories, and a project's,
     * holding the same unit and nothing projected yet. The shape a clone
     * leaves behind, built directly so the test does not depend on the cloner
     * it is not testing.
     */
    private record Fixture(Path operatorRoot, SkillStore operatorStore,
                           Path projectRoot, SkillStore projectStore) {

        static Fixture create() throws Exception {
            Path base = Files.createTempDirectory("sm-145-").toRealPath();
            Path operatorRoot = Files.createDirectories(base.resolve("operator"));
            Path projectRoot = Files.createDirectories(base.resolve("project"));
            SkillStore operatorStore = seedStore(operatorRoot);
            SkillStore projectStore = seedStore(projectRoot);
            // The operator's agent homes already hold the unit, pointed at the
            // operator's own store. That is the state #145 destroyed.
            for (String agentDir : AGENT_DIRS) {
                Path skills = Files.createDirectories(
                        operatorRoot.resolve(agentDir).resolve("skills"));
                Files.createSymbolicLink(skills.resolve(UNIT),
                        operatorStore.root().resolve("skills").resolve(UNIT));
                // The project's are created empty, exactly as bootstrap-home.sh
                // creates them — and that emptiness is the other half of #145.
                Files.createDirectories(projectRoot.resolve(agentDir).resolve("skills"));
            }
            return new Fixture(operatorRoot, operatorStore, projectRoot, projectStore);
        }

        private static SkillStore seedStore(Path homeRoot) throws Exception {
            SkillStore store = new SkillStore(homeRoot.resolve(".skill-manager"));
            store.init();
            Path unit = Files.createDirectories(store.skillsDir().resolve(UNIT));
            Files.writeString(unit.resolve("SKILL.md"),
                    "---\nname: " + UNIT + "\ndescription: fixture for issue 145\n---\nbody\n");
            return store;
        }
    }

    /**
     * The {@code SyncAgents} fan-out, minus the ledger and the MCP writes:
     * build the default registry <em>now</em> (it reads the agent roots at
     * construction, which is the behaviour under test) and apply every
     * projector's plan.
     */
    private static List<Projection> projectUnitInto(SkillStore store) throws Exception {
        AgentUnit unit = new Skill(UNIT, "fixture for issue 145", "0.0.1",
                List.of(), List.of(), List.of(), Map.of(), "body",
                store.skillsDir().resolve(UNIT)).asUnit();
        List<Projection> applied = new ArrayList<>();
        for (Projector projector : ProjectorRegistry.defaultRegistry().projectors()) {
            for (Projection projection : projector.planProjection(unit, store)) {
                if (projector.apply(projection)) applied.add(projection);
            }
        }
        return applied;
    }
}
