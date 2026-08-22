package dev.skillmanager.cli;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.store.HomeScaffold;
import dev.skillmanager.store.SkillStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * <b>A home has two axes, and {@code --home} pins both of them or refuses.</b>
 *
 * <p>{@code SKILL_MANAGER_HOME} says where the UNITS live.
 * {@code CLAUDE_CONFIG_DIR} / {@code CODEX_HOME} / {@code GEMINI_HOME} say
 * where the AGENT CONFIGS live, and they are a separate axis. {@code --home}
 * used to set the first only, so the store half went where it was told and the
 * agent half resolved against whatever the ambient environment exported.
 *
 * <p>Measured with the real CLI, DEF-029, and reproduced against a scratch
 * decoy before this suite was written:
 *
 * <pre>{@code
 * $ <build> sync his14-probe --merge --home <scratch>/project/.skill-manager
 *   ✓ units.lock.toml: wrote 1 unit(s) → <scratch>/project/…
 *   ✓ agents: 1 unit(s) linked into claude, codex, gemini
 *     ADDED claude (<decoy>/.claude.json)
 *     ADDED codex  (<decoy>/.codex/config.toml)
 *     ADDED gemini (<decoy>/.gemini/settings.json)
 *   <decoy>/.claude/skills/his14-probe -> <scratch>/project/.skill-manager/skills/his14-probe
 * }</pre>
 *
 * <p>Three symlinks and three edited config files in a home nobody named. On
 * the day it happened they were the operator's real {@code ~/.claude},
 * {@code ~/.codex} and {@code ~/.gemini}, the scratch directory was deleted
 * afterwards, and the unit began appearing in an unrelated agent session. That
 * is how it was found — <b>not by a check</b>. This file is the check.
 *
 * <h2>What makes these assertions non-vacuous</h2>
 *
 * <p>A fixture whose ambient environment already names the target home cannot
 * detect an unbound axis: everything lands in the right place for the wrong
 * reason. So every case here runs with the ambient agent variables naming a
 * DIFFERENT home, and asserts on that other home's bytes rather than on the
 * command's output. {@code otherHomeIsUntouched} compares a recursive
 * before/after snapshot — symlink targets included, because the damage was
 * symlinks — and the floor case proves a projection is observable at all, so
 * "nothing appeared over there" cannot pass by nothing having happened
 * anywhere.
 *
 * <p><b>No test here goes near the operator's real agent homes.</b> The root
 * tier is covered by a SYNTHETIC root-shaped fixture: a temp directory that is
 * the fixture's {@code $HOME}, holding {@code .skill-manager} beside
 * {@code .claude} / {@code .codex} / {@code .gemini} — the same shape as
 * {@code ~}, with none of the consequences. Reaching the real one is what
 * caused this.
 */
public final class HomeBindsBothAxesTest {

    /** The three agent directories a home owns. */
    private static final List<String> AGENT_DIRS = List.of(".claude", ".codex", ".gemini");

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("HomeBindsBothAxesTest");

        // ------------------------------------------------------------- floor
        suite.test("FLOOR: a projection into a home's agent dirs is observable", () -> {
            // Without this, every "the other home is untouched" case below
            // could pass because nothing was ever projected anywhere.
            Fx fx = Fx.create("floor");
            fx.installInto(fx.namedStore);

            for (String dir : AGENT_DIRS) {
                Path link = fx.namedRoot.resolve(dir).resolve("skills").resolve(Fx.UNIT);
                assertTrue(Files.isSymbolicLink(link),
                        "the unit is linked into " + dir + " of the home it was installed "
                                + "into: " + link);
            }
        });

        // ------------------------------------------------------------- claim
        suite.test("`sync --home <X>` writes nothing into the home the environment names", () -> {
            // THE REGRESSION FIXTURE. The ambient agent variables name `other`
            // throughout; only the flag names `named`. Before HIS-14 this run
            // linked the unit into `other`/.claude, .codex and .gemini and
            // edited three of its config files.
            Fx fx = Fx.create("claim");
            fx.installInto(fx.namedStore);
            Map<String, String> before = snapshot(fx.otherRoot);

            Run r = fx.runNamingOther("sync", Fx.UNIT, "--merge",
                    "--home", fx.namedStore.toString());

            assertEquals(0, r.rc, "the sync itself succeeded: " + r.output);
            assertTrue(Files.isSymbolicLink(
                            fx.namedRoot.resolve(".claude/skills").resolve(Fx.UNIT)),
                    "and it did project — into the home the flag named");
            otherHomeIsUntouched(before, fx.otherRoot,
                    "a command carrying --home writes no byte outside that home");
        });

        suite.test("the ROOT TIER, on a synthetic root-shaped home", () -> {
            // The tier the damage happened at, in the shape it happened in:
            // $HOME holding .skill-manager beside .claude/.codex/.gemini, with
            // no agent variable set. DEF-029's environment was exactly this
            // ("SKILL_MANAGER_HOME unset, the root home's bin/cli on PATH"),
            // and an unset SKILL_MANAGER_HOME resolves to <$HOME>/.skill-manager
            // — which is the value this pins, because a JVM cannot unset a
            // variable it inherited. Same resolved home, reachable in-process.
            Fx fx = Fx.createRootShaped("root-tier");
            fx.installInto(fx.namedStore);
            Map<String, String> before = snapshot(fx.otherRoot);

            Run r = fx.runNamingOther("sync", Fx.UNIT, "--merge",
                    "--home", fx.namedStore.toString());

            assertEquals(0, r.rc, "the sync succeeded: " + r.output);
            otherHomeIsUntouched(before, fx.otherRoot,
                    "the root tier is a home like any other: naming another one leaves it alone");
        });

        suite.test("EVERY verb that declares --home is bound, and the list is derived", () -> {
            // The contract is enforced at the one place the flag is read, so
            // the coverage question is "which commands declare it". The first
            // version of this case LISTED SEVEN, taken from the ticket's slice
            // -- and the CLI declares ELEVEN. The four it missed are
            // `home describe`, `home policy`, `home refresh-plugins` and
            // `home verify`, which are precisely the read-shaped ones the
            // ungated reconcile landed on. A hand-written list is a claim
            // about the tree; this walks it. Review of #234 (MED-6).
            List<String> declaring = verbsDeclaring("--home");
            assertTrue(declaring.size() >= 11,
                    "the CLI still declares --home on at least the eleven known verbs, found "
                            + declaring.size() + ": " + declaring);
            for (String verb : List.of("sync", "project sync", "home drift", "home shims",
                    "home close-out", "unit publish", "exec", "home describe", "home policy",
                    "home refresh-plugins", "home verify")) {
                assertTrue(declaring.contains(verb),
                        verb + " declares --home, so --home binds it; declaring=" + declaring);
            }
            // COMPANION, or the walk above is satisfiable by a probe that
            // always says yes: `home sync` is CLASS 1 under HIS-12's per-verb
            // contract -- it names its target with --from / --to and declares
            // no --home at all.
            assertFalse(declaring.contains("home sync"),
                    "`home sync` names its own target and takes no --home");
        });

        // -------------------------------------------------- one binding only
        suite.test("the printed env prefix is a RENDERING of the binding --home applies", () -> {
            // "One answer, not two spellings." home verify's remedy prefix was
            // the only correct statement of the two axes in the codebase; the
            // flag was the second, wrong one. They are now one map, so this
            // reads the printed line and finds that map in it.
            Fx fx = Fx.create("one-binding");
            Files.createDirectories(fx.namedStore.resolve("bin/cli"));
            Files.createSymbolicLink(fx.namedStore.resolve("bin/cli/ob-shim"),
                    fx.namedStore.resolve("venvs/ob/bin/ob-shim"));

            Run r = fx.runNamingOther("home", "verify", "--home", fx.namedStore.toString());

            assertEquals(1, r.rc, "precondition: the planted dangling shim is refused");
            Map<String, String> binding = AgentHomes.binding(fx.namedStore);
            assertEquals(4, binding.size(), "the binding is four variables");
            StringBuilder rendered = new StringBuilder("env");
            binding.forEach((k, v) -> rendered.append(' ').append(k).append('=')
                    .append(dev.skillmanager.store.HomeDescriptor.shellQuote(v)));
            assertContains(r.output, rendered.toString(),
                    "the remedy's env prefix is this binding, variable for variable and in "
                            + "the same order");
        });

        suite.test("--home makes every in-process reader answer with the named home", () -> {
            // The other half of "one answer": the flag does not merely print
            // the four variables, it RESOLVES them. Bound with the ambient
            // overrides naming `other`, so a reader that ignored the binding
            // would answer `other` and be caught.
            Fx fx = Fx.create("readers");
            Map<String, Path> displaced = null;
            try {
                fx.nameOtherAmbiently();
                assertEquals(fx.otherStore, SkillStore.defaultStore().root(),
                        "precondition: the ambient environment names the OTHER home");
                assertEquals(fx.otherRoot.resolve(".claude"),
                        AgentHomes.claude().configDir(),
                        "precondition: and the OTHER home's agent dirs");

                displaced = AgentHomes.bind(fx.namedStore);

                assertEquals(fx.namedStore, SkillStore.defaultStore().root(),
                        "the store axis names the home the flag named");
                assertEquals(fx.namedRoot.resolve(".claude"),
                        AgentHomes.claude().configDir(),
                        "and so does the agent axis");
                assertEquals(fx.namedRoot.resolve(".codex/config.toml"),
                        new dev.skillmanager.agent.CodexAgent().mcpConfigPath(),
                        "codex too");
                assertEquals(fx.namedRoot.resolve(".gemini/settings.json"),
                        new dev.skillmanager.agent.GeminiAgent().mcpConfigPath(),
                        "and gemini");
            } finally {
                if (displaced != null) AgentHomes.restoreOverrides(displaced);
                AgentHomes.clearOverrides();
            }
        });

        suite.test("an ambient CLAUDE_HOME naming another home does not survive the binding", () -> {
            // CLAUDE_HOME is the parent spelling of CLAUDE_CONFIG_DIR and is
            // deliberately absent from the binding map. That is only safe
            // because the config dir is consulted first -- so this asserts the
            // precedence rather than trusting the comment that states it.
            Fx fx = Fx.create("claude-home");
            Map<String, Path> displaced = null;
            try {
                // setUnset, not "leave it alone". This case is about the
                // CLAUDE_CONFIG_DIR-unset branch of the precedence, and its
                // precondition therefore has to STATE that the variable is
                // unset. It used to rely on the developer's shell not
                // exporting one, so with a real CLAUDE_CONFIG_DIR set the
                // suite went 9/1 on the PRECONDITION -- trap (a), inside the
                // file written to close trap (b). Review of #234 (MED-4).
                AgentHomes.setUnset(AgentHomes.CLAUDE_CONFIG_DIR);
                AgentHomes.setOverride(AgentHomes.CLAUDE_HOME, fx.otherRoot);
                assertEquals(fx.otherRoot.resolve(".claude"), AgentHomes.claude().configDir(),
                        "precondition: an ambient CLAUDE_HOME decides the config dir");

                displaced = AgentHomes.bind(fx.namedStore);

                assertEquals(fx.namedRoot.resolve(".claude"), AgentHomes.claude().configDir(),
                        "and the binding wins over it, without naming it");
            } finally {
                if (displaced != null) AgentHomes.restoreOverrides(displaced);
                AgentHomes.clearOverrides();
            }
        });

        suite.test("a home whose agent dirs are NOT beside the store keeps the ones it has", () -> {
            // HIGH-1 from the review of #234, and the sharpest finding in it:
            // the first version of this fix DERIVED all three agent dirs from
            // the store path and installed them over whatever was set. Right
            // for the default layout, wrong for every other one, and it
            // discards a correct answer to install a guess.
            //
            // A profile home is that other layout:
            // ProjectChildHomeScaffolder.layoutFor puts its agent homes at
            // <profileRoot>/agents/{claude,codex,gemini}. Measured with the
            // correct CLAUDE_CONFIG_DIR exported, sync --home <profileRoot>
            // created <profileRoot>/.claude and left agents/claude stale --
            // DEF-029's own shape, produced by DEF-029's fix.
            Path root = Files.createTempDirectory("his14-profile-");
            Path profile = Files.createDirectories(root.resolve(".skill-manager/profiles/dev"));
            Path agents = Files.createDirectories(profile.resolve("agents/claude"));
            Files.createDirectories(profile.resolve("agents/codex"));
            Files.createDirectories(profile.resolve("agents/gemini"));

            Map<String, Path> displaced = AgentHomes.snapshotOverrides();
            try {
                AgentHomes.setOverride(AgentHomes.CLAUDE_CONFIG_DIR, agents);
                AgentHomes.setOverride(AgentHomes.CODEX_HOME, profile.resolve("agents/codex"));
                AgentHomes.setOverride(AgentHomes.GEMINI_HOME, profile.resolve("agents/gemini"));

                Map<String, String> binding = AgentHomes.binding(profile);

                assertEquals(agents.toString(), binding.get(AgentHomes.CLAUDE_CONFIG_DIR),
                        "an explicitly-set agent dir INSIDE this home is a more precise "
                                + "statement about its layout than a derivation, and is kept");
                assertEquals(profile.resolve("agents/codex").toString(),
                        binding.get(AgentHomes.CODEX_HOME), "codex too");
                assertEquals(profile.toString(), binding.get(AgentHomes.SKILL_MANAGER_HOME),
                        "and the store axis is still the home that was named");

                AgentHomes.bind(profile);
                assertEquals(agents, AgentHomes.claude().configDir(),
                        "so the projector writes agents/claude, not a fresh .claude beside it");
            } finally {
                AgentHomes.restoreOverrides(displaced);
            }
        });

        suite.test("COMPANION: an explicit agent dir OUTSIDE the home is still replaced", () -> {
            // Without this the case above is satisfiable by "the environment
            // always wins", which is DEF-029 restored in full.
            Fx fx = Fx.create("explicit-outside");
            Map<String, Path> displaced = AgentHomes.snapshotOverrides();
            try {
                fx.nameOtherAmbiently();

                Map<String, String> binding = AgentHomes.binding(fx.namedStore);

                assertEquals(fx.namedRoot.resolve(".claude").toString(),
                        binding.get(AgentHomes.CLAUDE_CONFIG_DIR),
                        "an explicit variable naming ANOTHER home is a statement about that "
                                + "home, and this command is not about it");
            } finally {
                AgentHomes.restoreOverrides(displaced);
            }
        });

        suite.test("a read-shaped verb does not reconcile the home it was asked to inspect", () -> {
            // HIGH-2. `tryReconcile` ran ahead of every parsed command with no
            // gate on the read-only classification and none on the home
            // policy, so `home verify --home <X>` and `home close-out --home
            // <X>` MUTATED X: a legacy sources/ record migrated and the
            // directory deleted, on a home declared FROZEN. close-out's own
            // description reads "Writes nothing; safe to run repeatedly", and
            // close-change.sh runs it as the `wt close` gate.
            Fx fx = Fx.create("read-shaped");
            Path frozen = fx.namedStore;
            freeze(frozen);
            Path legacy = Files.createDirectories(frozen.resolve("sources"));
            Files.writeString(legacy.resolve("legacy-probe.json"),
                    "{\"name\":\"legacy-probe\",\"source\":\"file:///nowhere\"}\n");
            Map<String, String> before = snapshot(frozen);

            Run r = fx.runNamingOther("home", "verify", "--home", frozen.toString());

            assertTrue(r.rc == 0 || r.rc == 1, "verify reached a verdict: rc=" + r.rc);
            assertFalse(r.output.contains("migrated"),
                    "and migrated nothing on the way: " + r.output);
            assertTrue(Files.isDirectory(legacy),
                    "the legacy directory a reconcile would have deleted is still there");
            otherHomeIsUntouched(before, frozen,
                    "a verb that reports on a home writes nothing into it");
        });

        suite.test("a WRITING verb does not reconcile a FROZEN home either", () -> {
            // The second of HIGH-2's two gates, with its own probe because the
            // read-only gate returns first and would otherwise hide it: the
            // case above is frozen AND read-shaped, so it reddens if either
            // gate is removed and proves neither on its own.
            //
            // `exec` already refuses to reconcile a frozen home
            // (ExecCommand.refreshHome). This is the same reconciliation
            // reached by a different door, and it was not gated -- two
            // spellings of one decision, in the file that exists to remove
            // one.
            Fx fx = Fx.create("frozen-writer");
            Path frozen = fx.namedStore;
            freeze(frozen);
            Path legacy = Files.createDirectories(frozen.resolve("sources"));
            Files.writeString(legacy.resolve("legacy-probe.json"),
                    "{\"name\":\"legacy-probe\",\"source\":\"file:///nowhere\"}\n");

            Run r = fx.runNamingOther("home", "shims", "--home", frozen.toString());

            assertTrue(Files.isDirectory(legacy),
                    "a frozen home is not reconciled, whoever asked: rc=" + r.rc
                            + "\n" + r.output);
        });

        suite.test("COMPANION: a writing verb on a LIVE home still reconciles", () -> {
            // The gate must stop the read-shaped verbs and nothing else. A
            // WRITES_HOME verb against a home that is not frozen still does the
            // migration, so the gate is not simply "never reconcile".
            Fx fx = Fx.create("still-reconciles");
            Path live = fx.namedStore;
            Path legacy = Files.createDirectories(live.resolve("sources"));
            Files.writeString(legacy.resolve("legacy-probe.json"),
                    "{\"name\":\"legacy-probe\",\"source\":\"file:///nowhere\"}\n");

            fx.runNamingOther("home", "shims", "--home", live.toString());

            assertFalse(Files.isDirectory(legacy),
                    "a writing verb against a live home still migrates the legacy record");
        });

        suite.test("`--home-root` on its own binds the agent axis and NOT the store", () -> {
            // MED-3. It used to derive <root>/.skill-manager and bind that
            // too, so `home describe --home-root <r>` silently moved the store
            // to a home the operator had not named. --home-root is a statement
            // about a directory layout, not about a different home.
            Fx fx = Fx.create("home-root-only");
            Path layout = Files.createDirectories(fx.root.resolve("layout"));
            for (String dir : AGENT_DIRS) Files.createDirectories(layout.resolve(dir));
            Map<String, Path> displaced = AgentHomes.snapshotOverrides();
            try {
                fx.nameOtherAmbiently();

                AgentHomes.bindAgents(layout);

                assertEquals(layout.resolve(".claude"), AgentHomes.claude().configDir(),
                        "the agent axis moved to the stated root");
                assertEquals(fx.otherStore, SkillStore.defaultStore().root(),
                        "and the store axis is untouched — it is still whatever the "
                                + "environment named, because --home-root said nothing about it");
            } finally {
                AgentHomes.restoreOverrides(displaced);
            }
        });

        // ----------------------------------------------------------- refusal
        suite.test("REFUSAL: an agent dir that resolves out of the home is named, not bound", () -> {
            // Setting a variable is not confining a write. A .claude that is a
            // symlink into another home writes into that home however the
            // variable is set, so the binding cannot be honoured and the run
            // must not proceed on one that is true of the environment and false
            // of the filesystem.
            Fx fx = Fx.create("refusal");
            Files.createDirectories(fx.otherRoot.resolve(".claude"));
            deleteRecursively(fx.namedRoot.resolve(".claude"));
            Files.createSymbolicLink(fx.namedRoot.resolve(".claude"),
                    fx.otherRoot.resolve(".claude"));
            Map<String, String> before = snapshot(fx.otherRoot);

            Run r = fx.runNamingOther("home", "drift", "--home", fx.namedStore.toString());

            assertEquals(SkillManagerCli.UNBINDABLE_HOME_EXIT_CODE, r.rc,
                    "its own status, not 1 and not 0: " + r.output);
            assertContains(r.output, AgentHomes.CLAUDE_CONFIG_DIR,
                    "and it names the variable it could not set");
            assertContains(r.output, fx.otherRoot.resolve(".claude").toString(),
                    "and where that variable actually lands");
            otherHomeIsUntouched(before, fx.otherRoot,
                    "a refusal writes nothing, which is the point of refusing");
        });

        suite.test("REFUSAL: a DANGLING agent dir pointing out of the home is refused too", () -> {
            // MED-9. `unbindable` asked Files.exists(dir), which FOLLOWS the
            // link -- so a .claude symlinked to a path that does not exist read
            // as "nothing to judge", was declared bindable, and the sync behind
            // it then failed three ways at exit 0. A dangling link out of the
            // home makes the same statement as a live one.
            Fx fx = Fx.create("dangling");
            deleteRecursively(fx.namedRoot.resolve(".claude"));
            Files.createSymbolicLink(fx.namedRoot.resolve(".claude"),
                    fx.otherRoot.resolve("never-created/.claude"));

            Run r = fx.runNamingOther("home", "drift", "--home", fx.namedStore.toString());

            assertEquals(SkillManagerCli.UNBINDABLE_HOME_EXIT_CODE, r.rc,
                    "a link that does not resolve is still a link out of the home: " + r.output);
            assertContains(r.output, AgentHomes.CLAUDE_CONFIG_DIR, "and it is named");
            assertContains(r.output, "does not exist",
                    "and the reader is told the target is missing as well as foreign");
        });

        suite.test("COMPANION: a real agent directory inside the home is bound, not refused", () -> {
            // The refusal above must fire on the escape and on nothing else --
            // a guard that refuses every home is not a guard, and every home in
            // this suite would have failed the case above if it did.
            Fx fx = Fx.create("no-refusal");
            Files.createDirectories(fx.namedRoot.resolve(".claude"));

            Run r = fx.runNamingOther("home", "drift", "--home", fx.namedStore.toString());

            assertFalse(r.rc == SkillManagerCli.UNBINDABLE_HOME_EXIT_CODE,
                    "an ordinary home is bindable: " + r.output);
        });

        // -------------------------------------------- the remedy, EXECUTED
        suite.test("the printed remedy RUNS, in a shell whose environment names another home",
                () -> {
                    // NOT an assertion on the remedy's text. The refusal's own
                    // line is pulled out and handed to /bin/sh with the ambient
                    // agent variables naming `other` -- which is how DEF-029
                    // was produced in the first place, by an operator pasting
                    // this exact line into this exact environment.
                    ShellFx fx = ShellFx.create();
                    if (fx == null) {
                        System.out.println("      (skipped: no runnable ./skill-manager)");
                        return;
                    }
                    String remedy = fx.printedMergeRemedy();
                    assertContains(remedy, "--home", "precondition: the remedy names a home");
                    assertContains(remedy, "--merge", "and it is the merge re-run");

                    Map<String, String> before = snapshot(fx.otherRoot);
                    Result run = fx.sh(remedy);

                    assertEquals(0, run.rc, "the printed line runs: " + run.output);
                    otherHomeIsUntouched(before, fx.otherRoot,
                            "and leaves the home the shell named entirely alone");
                    assertTrue(Files.isSymbolicLink(
                                    fx.namedRoot.resolve(".claude/skills").resolve(ShellFx.UNIT)),
                            "while the home the remedy named got the projection");
                });

        return suite.runAll();
    }

    // ------------------------------------------------------------- assertions

    private static void otherHomeIsUntouched(Map<String, String> before, Path root, String what) {
        // An empty before-snapshot would make every comparison below trivially
        // true. The other home is an initialized home with three agent
        // directories, so this can only read zero if the fixture failed to
        // build -- exactly the vacuity the epic keeps finding.
        assertTrue(before.size() > 5,
                "the other home has content to be damaged (" + before.size() + " entries)");
        Map<String, String> after = snapshot(root);
        if (before.equals(after)) return;
        Map<String, String> appeared = new TreeMap<>(after);
        before.keySet().forEach(appeared::remove);
        Map<String, String> vanished = new TreeMap<>(before);
        after.keySet().forEach(vanished::remove);
        List<String> changed = new ArrayList<>();
        before.forEach((k, v) -> {
            String now = after.get(k);
            if (now != null && !now.equals(v)) changed.add(k + ": " + v + " -> " + now);
        });
        throw new AssertionError(what + "\n  appeared: " + appeared.keySet()
                + "\n  vanished: " + vanished.keySet()
                + "\n  changed:  " + changed);
    }

    /**
     * Every path under {@code root}, mapped to what it IS — a symlink's target,
     * a file's size and modification time, or "dir".
     *
     * <p>The damage this suite exists for was symlinks, so a snapshot that
     * followed them or compared only names would have missed it entirely.
     */
    private static Map<String, String> snapshot(Path root) {
        Map<String, String> out = new TreeMap<>();
        if (!Files.exists(root)) return out;
        // Files.walk does not follow symlinks by default, which is the point:
        // the damage this guards against IS symlinks, and following them would
        // report the LINK TARGET's tree instead of the fact that a link
        // appeared.
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                String key = root.relativize(p).toString();
                if (key.isEmpty()) continue;
                out.put(key, describe(p));
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("could not snapshot " + root, e);
        }
        return out;
    }

    private static String describe(Path p) {
        try {
            if (Files.isSymbolicLink(p)) return "link -> " + Files.readSymbolicLink(p);
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return "dir";
            return "file " + Files.size(p) + " @"
                    + Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (java.io.IOException e) {
            return "unreadable: " + e.getMessage();
        }
    }

    /**
     * Every command path in the tree that declares {@code option}, derived by
     * walking picocli rather than written down. See the coverage case for why
     * that distinction cost four verbs.
     */
    private static List<String> verbsDeclaring(String option) {
        List<String> found = new ArrayList<>();
        walk(new picocli.CommandLine(new SkillManagerCli()), "", option, found);
        return found;
    }

    private static void walk(picocli.CommandLine cmd, String path, String option,
                             List<String> found) {
        for (Map.Entry<String, picocli.CommandLine> child : cmd.getSubcommands().entrySet()) {
            String here = path.isEmpty() ? child.getKey() : path + " " + child.getKey();
            if (child.getValue().getCommandSpec().findOption(option) != null) found.add(here);
            walk(child.getValue(), here, option, found);
        }
    }

    // --------------------------------------------------------------- fixtures

    private record Run(int rc, String output) {}

    private record Result(int rc, String output) {}

    /**
     * Two homes: the one the flag names and the one the environment names.
     * They are never the same directory — a fixture whose ambient environment
     * happens to name the target home proves nothing.
     */
    private static final class Fx {

        static final String UNIT = "his14-probe";

        final Path root;
        final Path namedRoot;
        final Path namedStore;
        final Path otherRoot;
        final Path otherStore;
        final Path unitSource;
        /** The {@code $HOME} this fixture runs under; never the operator's. */
        final Path fixtureHome;

        private Fx(Path root, Path namedRoot, Path otherRoot, Path fixtureHome) throws Exception {
            this.root = root;
            this.namedRoot = namedRoot;
            this.namedStore = namedRoot.resolve(AgentHomes.STORE_DIR_NAME);
            this.otherRoot = otherRoot;
            this.otherStore = otherRoot.resolve(AgentHomes.STORE_DIR_NAME);
            this.fixtureHome = fixtureHome;
            this.unitSource = root.resolve("source").resolve(UNIT);
            Files.createDirectories(unitSource);
            Files.writeString(unitSource.resolve("SKILL.md"), """
                    ---
                    name: %s
                    description: A fixture unit for HIS-14. Use when proving that a command \
                    carrying --home writes nothing into a home nobody named.
                    ---
                    # %s
                    """.formatted(UNIT, UNIT));
            HomeScaffold.Access displaced = HomeScaffold.declare(HomeScaffold.Access.WRITES_HOME);
            try {
                new SkillStore(namedStore).init();
                new SkillStore(otherStore).init();
            } finally {
                HomeScaffold.restore(displaced);
            }
            for (String dir : AGENT_DIRS) {
                Files.createDirectories(namedRoot.resolve(dir));
                Files.createDirectories(otherRoot.resolve(dir));
            }
        }

        /** Two sibling homes; {@code $HOME} is a third directory owning neither. */
        static Fx create(String label) throws Exception {
            Path root = Files.createTempDirectory("his14-" + label + "-");
            return new Fx(root, Files.createDirectories(root.resolve("named")),
                    Files.createDirectories(root.resolve("other")),
                    Files.createDirectories(root.resolve("elsewhere")));
        }

        /**
         * The other home is ROOT-SHAPED: it IS {@code $HOME}, with
         * {@code .skill-manager} beside {@code .claude} / {@code .codex} /
         * {@code .gemini} — the shape of {@code ~}, synthesized in a temp
         * directory so no test can reach the real one.
         */
        static Fx createRootShaped(String label) throws Exception {
            Path root = Files.createTempDirectory("his14-" + label + "-");
            Path synthetic = Files.createDirectories(root.resolve("synthetic-home"));
            return new Fx(root, Files.createDirectories(root.resolve("project")),
                    synthetic, synthetic);
        }

        /** Point every ambient lookup at the OTHER home. */
        void nameOtherAmbiently() {
            AgentHomes.setOverride(AgentHomes.HOME, fixtureHome);
            AgentHomes.setOverride(AgentHomes.SKILL_MANAGER_HOME, otherStore);
            AgentHomes.setOverride(AgentHomes.CLAUDE_CONFIG_DIR, otherRoot.resolve(".claude"));
            AgentHomes.setOverride(AgentHomes.CLAUDE_HOME, otherRoot);
            AgentHomes.setOverride(AgentHomes.CODEX_HOME, otherRoot.resolve(".codex"));
            AgentHomes.setOverride(AgentHomes.GEMINI_HOME, otherRoot.resolve(".gemini"));
        }

        /** Install the fixture unit into {@code store}, with nothing else bound. */
        void installInto(Path store) throws Exception {
            Path root = AgentHomes.homeRootFor(store);
            Map<String, Path> displaced = AgentHomes.snapshotOverrides();
            try {
                AgentHomes.setOverride(AgentHomes.HOME, fixtureHome);
                AgentHomes.bind(store);
                Run r = capture(() -> SkillManagerCli.run(
                        new String[]{"install", unitSource.toString(), "--yes"}));
                assertEquals(0, r.rc, "fixture install into " + root + ": " + r.output);
            } finally {
                AgentHomes.restoreOverrides(displaced);
            }
        }

        /** Run the CLI with the ambient environment naming the OTHER home. */
        Run runNamingOther(String... argv) {
            Map<String, Path> displaced = AgentHomes.snapshotOverrides();
            try {
                nameOtherAmbiently();
                return capture(() -> SkillManagerCli.run(argv));
            } finally {
                AgentHomes.restoreOverrides(displaced);
            }
        }
    }

    /**
     * The subprocess fixture: a real {@code ./skill-manager}, a git-backed unit
     * with a local edit (so {@code sync} refuses and prints its remedy), and a
     * second home for the shell's environment to name.
     */
    private static final class ShellFx {

        static final String UNIT = "his14-shell-probe";

        final Path root;
        final Path namedRoot;
        final Path namedStore;
        final Path otherRoot;
        final Path cli;
        final Path unitSource;

        private ShellFx(Path root, Path cli) throws Exception {
            this.root = root;
            this.cli = cli;
            this.namedRoot = Files.createDirectories(root.resolve("named"));
            this.namedStore = namedRoot.resolve(AgentHomes.STORE_DIR_NAME);
            this.otherRoot = Files.createDirectories(root.resolve("other"));
            this.unitSource = Files.createDirectories(root.resolve("source").resolve(UNIT));
            // The other home is a POPULATED home, not three empty directories:
            // a store, and the three config files the real damage edited. A
            // before-snapshot with nothing in it cannot show a change, and
            // `otherHomeIsUntouched` refuses one for that reason.
            HomeScaffold.Access displacedAccess =
                    HomeScaffold.declare(HomeScaffold.Access.WRITES_HOME);
            try {
                new SkillStore(otherRoot.resolve(AgentHomes.STORE_DIR_NAME)).init();
            } finally {
                HomeScaffold.restore(displacedAccess);
            }
            for (String dir : AGENT_DIRS) Files.createDirectories(otherRoot.resolve(dir));
            Files.writeString(otherRoot.resolve(".claude/settings.json"), "{}\n");
            Files.writeString(otherRoot.resolve(".codex/config.toml"), "# operator's own\n");
            Files.writeString(otherRoot.resolve(".gemini/settings.json"), "{}\n");
            Files.createDirectories(otherRoot.resolve(".claude/plugins"));
            Files.writeString(otherRoot.resolve(".claude/plugins/known_marketplaces.json"), "{}\n");
            Files.writeString(unitSource.resolve("SKILL.md"), """
                    ---
                    name: %s
                    description: A fixture unit for HIS-14's shell case. Use when executing a \
                    printed remedy in a shell whose environment names another home.
                    ---
                    # %s
                    """.formatted(UNIT, UNIT));
            git("init", "-q", ".");
            git("add", "-A");
            git("-c", "user.email=his14@example.invalid", "-c", "user.name=his14",
                    "commit", "-qm", "fixture");
            Result installed = run(List.of(cli.toString(), "install", unitSource.toString(),
                    "--yes"), namedRoot);
            assertEquals(0, installed.rc, "fixture install: " + installed.output);
            Files.writeString(namedStore.resolve("skills").resolve(UNIT).resolve("SKILL.md"),
                    "\na local edit\n", java.nio.file.StandardOpenOption.APPEND);
        }

        static ShellFx create() throws Exception {
            Path cli = Path.of(System.getProperty("user.dir")).resolve("skill-manager")
                    .toAbsolutePath().normalize();
            if (!Files.isExecutable(cli)) return null;
            return new ShellFx(Files.createTempDirectory("his14-shell-"), cli);
        }

        /** The refusal's own {@code re-run with:} line, verbatim. */
        String printedMergeRemedy() throws Exception {
            Result refusal = run(List.of(cli.toString(), "sync", UNIT,
                    "--home", namedStore.toString()), otherRoot);
            for (String line : refusal.output.split("\n")) {
                int at = line.indexOf("re-run with: ");
                if (at < 0) continue;
                String rest = line.substring(at + "re-run with: ".length());
                int trailer = rest.indexOf("  (merges ");
                return (trailer < 0 ? rest : rest.substring(0, trailer)).strip();
            }
            throw new AssertionError("the refusal printed no remedy to run:\n" + refusal.output);
        }

        /** Execute {@code command} in a shell whose home is {@code otherRoot}. */
        Result sh(String command) throws Exception {
            return run(List.of("/bin/sh", "-c", command), otherRoot);
        }

        private void git(String... args) throws Exception {
            List<String> argv = new ArrayList<>(List.of("git"));
            argv.addAll(List.of(args));
            ProcessBuilder pb = new ProcessBuilder(argv)
                    .directory(unitSource.toFile()).redirectErrorStream(true);
            pb.environment().put("HOME", root.toString());
            pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
            pb.environment().put("GIT_CONFIG_SYSTEM", "/dev/null");
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, p.waitFor(), "git " + String.join(" ", args) + ": " + out);
        }

        /**
         * Run {@code argv} in a shell environment that names {@code home} on
         * BOTH axes — {@code $HOME} plus an explicit
         * {@code CLAUDE_CONFIG_DIR} / {@code CODEX_HOME} / {@code GEMINI_HOME}
         * — while {@code SKILL_MANAGER_HOME} is left unset, as DEF-029's was.
         *
         * <p>The agent variables are exported ON PURPOSE, and an earlier
         * version of this fixture that removed them proved less than it looked
         * like it did: with all four unset, the agent axis falls back to the
         * store axis, so a binding that pinned only {@code SKILL_MANAGER_HOME}
         * still landed everything in the right place and the case passed
         * against a half-fixed product. An ambient variable that names another
         * home is the case that discriminates, and it is also the case an
         * operator is actually in — every per-checkout home exports these.
         */
        private static Result run(List<String> argv, Path home) throws Exception {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            pb.environment().put("HOME", home.toString());
            pb.environment().remove("SKILL_MANAGER_HOME");
            pb.environment().remove("CLAUDE_HOME");
            pb.environment().put("CLAUDE_CONFIG_DIR", home.resolve(".claude").toString());
            pb.environment().put("CODEX_HOME", home.resolve(".codex").toString());
            pb.environment().put("GEMINI_HOME", home.resolve(".gemini").toString());
            pb.environment().put("OTEL_TRACES_EXPORTER", "none");
            pb.environment().put("OTEL_METRICS_EXPORTER", "none");
            pb.environment().put("OTEL_LOGS_EXPORTER", "none");
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new AssertionError("timed out: " + String.join(" ", argv));
            }
            return new Result(p.exitValue(), out);
        }
    }

    // ----------------------------------------------------------- console capture

    private interface Invocation { int run(); }

    private static Run capture(Invocation invocation) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        Map<String, String> otel = quietExporters();
        try {
            System.setOut(sink);
            System.setErr(sink);
            int rc = invocation.run();
            return new Run(rc, captured.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
            restore(otel);
        }
    }

    private static Map<String, String> quietExporters() {
        Map<String, String> previous = new LinkedHashMap<>();
        for (String key : List.of("otel.traces.exporter", "otel.metrics.exporter",
                "otel.logs.exporter")) {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, "none");
        }
        return previous;
    }

    private static void restore(Map<String, String> properties) {
        properties.forEach((key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
        });
    }

    /**
     * Declare {@code store} FROZEN, through {@link dev.skillmanager.policy.HomePolicy}
     * rather than by writing a file this test believes in.
     *
     * <p>Both frozen fixtures first wrote {@code policy.toml} by hand. The file
     * is {@code home.policy.toml}, so neither home was frozen and the frozen
     * half of HIGH-2 had no probe at all — the read-only gate returns first, so
     * the case passed on the other gate and looked like evidence for both.
     * Found by V8, which disabled ONLY the frozen gate and got a failure whose
     * message was the product doing exactly what the case said it must not.
     * A fixture that states a precondition through a string literal is a
     * fixture that can be wrong about it.
     */
    private static void freeze(Path store) throws Exception {
        dev.skillmanager.policy.HomePolicy.write(
                new SkillStore(store), dev.skillmanager.policy.HomePolicy.FROZEN);
        assertTrue(dev.skillmanager.policy.HomePolicy.load(new SkillStore(store)).frozen(),
                "precondition: the fixture home really is frozen");
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
