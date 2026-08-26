package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.launch.LauncherShims;
import dev.skillmanager.launch.RunningCli;
import dev.skillmanager.shared.util.Fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Issue #161 and DEF-002 — <b>a remedy is an instruction, and these ones used
 * to edit a different home than the one you were in.</b>
 *
 * <p>Three properties, each asserted rather than described:
 *
 * <ol>
 *   <li><b>The stated precedence is the real precedence.</b>
 *       {@link HomeDescriptor#locateCli} is walked step by step with every
 *       candidate present and then removed one at a time, and the javadoc's own
 *       {@code <ol>} is parsed out of the source file and checked against the
 *       order the walk observed. The step this replaced — "the running
 *       process's own command, when it really is a skill-manager launcher" —
 *       could not fire in ANY shipped launcher, because every distribution
 *       {@code exec}s a JVM and the process basename is {@code java} or
 *       {@code jbang}; that is asserted too, against the predicate the old step
 *       used, so the reason it was replaced stays measured rather than
 *       remembered.</li>
 *   <li><b>A remedy about home X names X.</b> DEF-002's measured shape: a sync
 *       run against the PROJECT home printed a remedy naming the ROOT home's
 *       pinned entrypoint, and following it verbatim edits the root home. A
 *       home's {@code bin/cli/skill-manager} exports its own location as
 *       {@code SKILL_MANAGER_HOME} and lets that win, so a FOREIGN one cannot
 *       be redirected by any prefix — it is refused as a candidate, at every
 *       step.</li>
 *   <li><b>A PATH-resolved remedy says so.</b> #142 made remedies absolute,
 *       which made the PATH case READ authoritative while leaving it exactly as
 *       wrong. The caveat is what distinguishes them.</li>
 * </ol>
 *
 * <p>Every candidate here is a real executable file in a temp directory and
 * every lookup is injected, because {@code System.getenv} cannot be set from
 * inside a JVM — which is precisely how a resolution step nobody could drive
 * stayed dead across three releases with a javadoc describing it.
 */
public final class HomeDescriptorCliRemedyTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("HomeDescriptorCliRemedyTest");

        // ------------------------------------------------------- precedence

        suite.test("locateCli walks all four steps, in the order it documents", () -> {
            Fixture f = Fixture.create("cli-precedence-");

            HomeDescriptor.ResolvedCli all = f.locate(f.pin, f.running, f.pathDir);
            assertEquals(HomeDescriptor.CliSource.PINNED_ENV, all.source(),
                    "step 1: an explicit SKILL_MANAGER_CLI wins over everything");
            assertEquals(f.pin, all.path(), "and it is the pinned path that is returned");

            HomeDescriptor.ResolvedCli noPin = f.locate(null, f.running, f.pathDir);
            assertEquals(HomeDescriptor.CliSource.HOME_ENTRYPOINT, noPin.source(),
                    "step 2: the home's own bin/cli entrypoint, before anything global");
            assertEquals(f.ownEntrypoint, noPin.path(), "and it is this home's own file");

            Files.delete(f.ownEntrypoint);
            HomeDescriptor.ResolvedCli noOwn = f.locate(null, f.running, f.pathDir);
            assertEquals(HomeDescriptor.CliSource.RUNNING_BUILD, noOwn.source(),
                    "step 3: the build that is running right now");
            assertEquals(f.running, noOwn.path(), "and it is the running launcher");

            HomeDescriptor.ResolvedCli noRunning = f.locate(null, null, f.pathDir);
            assertEquals(HomeDescriptor.CliSource.PATH_FALLBACK, noRunning.source(),
                    "step 4: a raw PATH walk, and it is reported AS a fallback");
            assertEquals(f.onPath, noRunning.path(), "and it is what PATH found");

            HomeDescriptor.ResolvedCli nothing = f.locate(null, null, null);
            assertEquals(HomeDescriptor.CliSource.UNRESOLVED, nothing.source(),
                    "nothing resolves rather than something being invented");
            assertTrue(nothing.path() == null, "and the path is null, not a plausible guess");
        });

        suite.test("the javadoc's stated precedence is the precedence the walk observed", () -> {
            List<String> steps = documentedPrecedence();
            assertEquals(4, steps.size(),
                    "locateCli's javadoc states exactly four steps: " + steps);
            assertContains(steps.get(0), "SKILL_MANAGER_CLI", "step 1 is the explicit pin");
            assertContains(steps.get(1), "bin/cli/skill-manager",
                    "step 2 is the home's own entrypoint");
            assertContains(steps.get(2), "RunningCli",
                    "step 3 is the running build, located by RunningCli");
            assertContains(steps.get(3), "PATH", "step 4 is the PATH fallback");
            // The step the javadoc used to claim second. It is not merely
            // absent from the list: the code no longer consults ProcessHandle
            // at all, which is what makes the list true rather than tidy.
            String source = Files.readString(homeDescriptorSource());
            int cliSection = source.indexOf(
                    "// ------------------------------------------------------- cli discovery");
            assertTrue(cliSection > 0, "the cli-discovery section is findable");
            String code = stripComments(source.substring(cliSection));
            assertContains(source.substring(cliSection), "ProcessHandle",
                    "the javadoc still explains WHY the step was replaced");
            assertFalse(code.contains("ProcessHandle"),
                    "but no code consults it: the dead step's implementation is gone, "
                            + "not just its documentation");
        });

        suite.test("the step that was replaced could not fire in any shipped launcher", () -> {
            // The old step 2 was `ProcessHandle.current().info().command()`
            // filtered on the basename `skill-manager`. Every distribution of
            // this CLI execs a JVM, so that command is java or jbang. Asserted
            // through RunningCli, which applies the identical basename filter
            // to the identical input.
            Path tmp = Files.createTempDirectory("cli-dead-step-");
            for (String launcher : List.of("java", "jbang")) {
                Path fake = executable(tmp.resolve(launcher));
                Exception refused = null;
                try {
                    RunningCli.locate(key -> null, fake.toString(), null);
                } catch (RunningCli.UnknownLocationException e) {
                    refused = e;
                }
                assertTrue(refused != null,
                        "a process named " + launcher + " is not a skill-manager launcher");
                assertContains(refused.getMessage(), "not named skill-manager",
                        "and the reason is the basename filter the dead step used");
            }
            // The replacement IS live for exactly that process, because a
            // shipped launcher exports SKILL_MANAGER_INSTALL_DIR before it
            // execs the JVM.
            Path install = Files.createDirectories(tmp.resolve("install"));
            Path launcher = executable(install.resolve("skill-manager"));
            Path java = executable(tmp.resolve("java2"));
            assertEquals(launcher,
                    RunningCli.locate(key -> RunningCli.INSTALL_DIR.equals(key)
                            ? install.toString() : null, java.toString(), null),
                    "the same JVM process resolves to its launcher through INSTALL_DIR");
        });

        // -------------------------------------------- a remedy names its home

        suite.test("a remedy about home X names X, whichever step resolved the CLI", () -> {
            Fixture f = Fixture.create("cli-names-home-");
            record Case(String label, Path pin, Path running, Path pathDir) {}
            List<Case> cases = List.of(
                    new Case("pinned", f.pin, f.running, f.pathDir),
                    new Case("own entrypoint", null, f.running, f.pathDir),
                    new Case("running build", null, f.running, f.pathDir),
                    new Case("path fallback", null, null, f.pathDir),
                    new Case("unresolved", null, null, null));
            for (Case c : cases) {
                if ("running build".equals(c.label())) Files.deleteIfExists(f.ownEntrypoint);
                HomeDescriptor.CliSpelling spelling = f.spell(c.pin(), c.running(), c.pathDir());
                // The binding is in the ARGUMENT, for EVERY source including
                // the home's own entrypoint. #229's first attempt exempted
                // HOME_ENTRYPOINT on the reasoning that the shim binds its own
                // home; HIS-9 makes that shim REFUSE a mismatch with exit 79
                // and then recommend the home the operator was not in, so the
                // exemption's premise is gone. A class-2/3 verb carries --home
                // whatever build is running it, and HIS-9's guard exempts a
                // command line that already carries one.
                assertEquals("--home " + f.home, spelling.homeArg(),
                        "the " + c.label() + " remedy names the home it is about");
                assertFalse(spelling.binary().contains("SKILL_MANAGER_HOME"),
                        "and the binding is NOT in the head token, which every consumer of a "
                                + "printed remedy substitutes: " + spelling.binary());
            }
        });

        suite.test("DEF-002: another home's entrypoint is never the remedy", () -> {
            Fixture f = Fixture.create("cli-foreign-");
            // The measured shape: the ROOT home's bin/cli on the operator's
            // PATH, while the command runs against the PROJECT home.
            Path foreign = Fixture.home(Files.createTempDirectory("cli-foreign-root-"));
            Path foreignEntrypoint = executable(foreign.resolve("bin/cli/skill-manager"));
            Files.delete(f.ownEntrypoint);

            assertTrue(HomeDescriptor.isForeignHomeEntrypoint(f.home, foreignEntrypoint),
                    "the root home's entrypoint is foreign to the project home");
            assertFalse(HomeDescriptor.isForeignHomeEntrypoint(foreign, foreignEntrypoint),
                    "and it is NOT foreign to the home it belongs to");

            HomeDescriptor.ResolvedCli viaPath =
                    f.locate(null, null, foreignEntrypoint.getParent());
            assertEquals(HomeDescriptor.CliSource.UNRESOLVED, viaPath.source(),
                    "a foreign entrypoint on PATH is skipped rather than returned");

            HomeDescriptor.ResolvedCli viaPin = f.locate(foreignEntrypoint, null, f.pathDir);
            assertEquals(HomeDescriptor.CliSource.PATH_FALLBACK, viaPin.source(),
                    "and it is skipped as an explicit pin too — no prefix can redirect it");

            // Its own home still gets it: the rule is about WHOSE home, not
            // about the shape of the path.
            HomeDescriptor.CliSpelling ownSide = HomeDescriptor.cliSpelling(
                    foreign, key -> null, () -> null);
            assertEquals(HomeDescriptor.CliSource.HOME_ENTRYPOINT, ownSide.source(),
                    "the foreign home resolves its own entrypoint normally");
            assertEquals(foreignEntrypoint.toString(), ownSide.binary(),
                    "and it is the head token, unadorned");
        });

        suite.test("DEF-002 survives a SYMLINK unless the candidate is resolved first", () -> {
            // The most ordinary way to put a CLI on PATH. Before the candidate
            // was resolved, this shape walked straight past the predicate: the
            // link's parents are /usr/local/bin and /usr/local, not bin/cli
            // under a home, so `isForeignHomeEntrypoint` returned false and the
            // root home's entrypoint became the remedy again — DEF-002 intact,
            // inside the fix for DEF-002.
            Fixture f = Fixture.create("cli-foreign-link-");
            Files.delete(f.ownEntrypoint);
            Path foreign = Fixture.home(Files.createTempDirectory("cli-foreign-link-root-"));
            Path foreignEntrypoint = executable(foreign.resolve("bin/cli/skill-manager"));

            Path usrLocalBin = Files.createDirectories(
                    Files.createTempDirectory("cli-usr-local-").resolve("bin"));
            Path link = usrLocalBin.resolve("skill-manager");
            Files.createSymbolicLink(link, foreignEntrypoint);
            assertTrue(Files.isSymbolicLink(link), "the fixture really is a symlink");
            assertFalse(link.toString().contains("bin/cli"),
                    "and its own spelling carries none of the shape the predicate reads");

            assertTrue(HomeDescriptor.isForeignHomeEntrypoint(f.home, link),
                    "a symlink to a foreign home's entrypoint IS that entrypoint");
            assertEquals(HomeDescriptor.CliSource.UNRESOLVED,
                    f.locate(null, null, usrLocalBin).source(),
                    "so PATH finding it resolves nothing rather than the wrong home");

            // And the same link, for the home it actually belongs to, is not
            // foreign — spelling-invariance has to hold in both directions or
            // it is just a refusal.
            assertFalse(HomeDescriptor.isForeignHomeEntrypoint(foreign, link),
                    "the same link is not foreign to the home it points into");

            // A home reached through a symlinked ROOT is likewise not foreign
            // to its own entrypoint. This is GOAL-one-home-one-answer clause 2.
            Path aliasParent = Files.createTempDirectory("cli-home-alias-");
            Path alias = aliasParent.resolve("home-link");
            Files.createSymbolicLink(alias, foreign);
            assertFalse(HomeDescriptor.isForeignHomeEntrypoint(alias, foreignEntrypoint),
                    "the home reached through a symlinked root owns its own entrypoint");
        });

        suite.test("the head token is the build and nothing else, for every source", () -> {
            // The property that keeps every existing consumer working. Both
            // `home-sync`'s remedyArgs and close-change.sh's run_cli DROP token
            // 0 and re-run the rest through their own CLI; remedyArgs guards
            // that strip with endsWith("skill-manager"), so a head token that
            // is anything else is not dropped but passed through as arguments.
            Fixture f = Fixture.create("cli-binding-");
            record Case(String label, Path pin, Path running, Path pathDir) {}
            List<Case> cases = List.of(
                    new Case("pinned", f.pin, f.running, f.pathDir),
                    new Case("own entrypoint", null, f.running, f.pathDir),
                    new Case("running build", null, f.running, f.pathDir),
                    new Case("path fallback", null, null, f.pathDir));
            for (Case c : cases) {
                if ("running build".equals(c.label())) Files.deleteIfExists(f.ownEntrypoint);
                String head = f.spell(c.pin(), c.running(), c.pathDir()).binary();
                assertEquals(1, head.split("\\s+").length,
                        "the " + c.label() + " head token is ONE token: " + head);
                assertTrue(head.endsWith("skill-manager"),
                        "and it ends in skill-manager, which is the predicate every consumer "
                                + "strips on: " + head);
                assertTrue(Files.isExecutable(Path.of(head)),
                        "and it is executable: " + head);
            }
        });

        // ------------------------------------------- the fallback admits it

        suite.test("a PATH-resolved remedy says so; a verified one has nothing to admit", () -> {
            Fixture f = Fixture.create("cli-caveat-");

            assertTrue(f.spell(f.pin, f.running, f.pathDir).caveat() == null,
                    "an explicit pin has nothing to admit");
            assertTrue(f.spell(null, f.running, f.pathDir).caveat() == null,
                    "the home's own entrypoint has nothing to admit");
            Files.delete(f.ownEntrypoint);
            assertTrue(f.spell(null, f.running, f.pathDir).caveat() == null,
                    "the running build has nothing to admit");

            HomeDescriptor.CliSpelling fallback = f.spell(null, null, f.pathDir);
            assertFalse(fallback.verified(), "a PATH walk is not a verified answer");
            String caveat = fallback.caveat();
            assertTrue(caveat != null, "and it must say so");
            assertContains(caveat, "PATH", "the caveat names where the build came from");
            assertContains(caveat, f.onPath.toString(), "and which build it is");
            assertContains(caveat, "home shims", "and how to stop guessing");
            // H3 of #229's review: the caveat's own instruction is a remedy,
            // and it used to be built from the binary alone — so a pasted
            // `<build> home shims` with no SKILL_MANAGER_HOME re-pinned the
            // operator's ROOT home. DEF-002, reintroduced inside its own fix.
            assertContains(caveat, "home shims --home " + f.home,
                    "and the instruction it gives is itself bound to this home");

            HomeDescriptor.CliSpelling none = f.spell(null, null, null);
            assertEquals("skill-manager", none.binary(),
                    "the bare token remains the floor");
            assertContains(none.caveat(), "no skill-manager could be located",
                    "and an unresolved remedy admits that too");
        });

        // ------------------------------------------- DEF-012, resolution half

        suite.test("a home entrypoint whose pin was deleted by an upgrade is not the remedy",
                () -> {
            Fixture f = Fixture.create("cli-dangling-pin-");
            // The measured shape: `brew upgrade` 0.23.0 -> 0.24.0 removed the
            // Cellar directory the pin names. The SHIM is still there and
            // still executable, which is why every reader that tests -x on it
            // called the home healthy.
            Path cellar = Files.createTempDirectory("cli-cellar-");
            Path oldBuild = executable(cellar.resolve("0.23.0/libexec/bin/skill-manager"));
            Files.writeString(f.ownEntrypoint,
                    dev.skillmanager.launch.LauncherShims.cliScript(oldBuild));
            Fs.makeExecutable(f.ownEntrypoint);

            assertEquals(oldBuild, dev.skillmanager.launch.LauncherShims
                            .pinnedCliIn(f.ownEntrypoint).orElse(null),
                    "the pin is readable while the build it names is still there");
            assertEquals(HomeDescriptor.CliSource.HOME_ENTRYPOINT,
                    f.locate(null, f.running, f.pathDir).source(),
                    "and a live pin makes the entrypoint the answer, as it should");

            // The upgrade.
            Fs.deleteRecursive(cellar.resolve("0.23.0"));
            assertTrue(Files.isExecutable(f.ownEntrypoint),
                    "the shim itself is untouched — this is why -x could not see the defect");

            HomeDescriptor.ResolvedCli after = f.locate(null, f.running, f.pathDir);
            assertEquals(HomeDescriptor.CliSource.RUNNING_BUILD, after.source(),
                    "a remedy falls through rather than naming a front door that cannot open");
            assertEquals(oldBuild, after.danglingHomePin(),
                    "and it carries WHICH build went missing");

            HomeDescriptor.CliSpelling spelling = f.spell(null, f.running, f.pathDir);
            assertContains(spelling.caveat(), oldBuild.toString(),
                    "the caveat names the deleted build");
            assertContains(spelling.caveat(), "home shims --home " + f.home,
                    "and how to re-pin the home — bound to THIS home, because the branch that "
                            + "fires here is the one where the front door is already broken");
            assertEquals("--home " + f.home, spelling.homeArg(),
                    "and the remedy still names the home it is about");
        });

        suite.test("a pin that is not a literal path, or names a directory, is not dangling",
                () -> {
            // M2 of #229's review. Each of these made a HEALTHY home read as
            // broken, and the caller's response to "broken" is to push that
            // home off its own working front door — so a false positive here
            // is worse than no check at all.
            Fixture f = Fixture.create("cli-pin-shapes-");
            Path real = executable(Files.createTempDirectory("cli-pin-real-")
                    .resolve("skill-manager"));

            // (a) a computed pin. The text is not what gets exec'd.
            Files.writeString(f.ownEntrypoint, """
                    #!/usr/bin/env bash
                    # skill-manager:cli-pin
                    cli="${SKILL_MANAGER_CLI:-$SM_PREFIX/bin/skill-manager}"
                    exec "$cli" "$@"
                    """);
            Fs.makeExecutable(f.ownEntrypoint);
            assertTrue(LauncherShims.pinnedCliIn(f.ownEntrypoint).isEmpty(),
                    "a pin carrying $ is computed at run time and cannot be read literally");
            assertEquals(HomeDescriptor.CliSource.HOME_ENTRYPOINT,
                    f.locate(null, f.running, f.pathDir).source(),
                    "so the home keeps its own entrypoint");

            // (b) a pin naming a DIRECTORY. Directories carry the execute bit;
            // that is what it means for one, so isExecutable alone says yes.
            Path dir = Files.createTempDirectory("cli-pin-dir-");
            assertTrue(Files.isExecutable(dir),
                    "a directory is 'executable' — which is why isExecutable alone was wrong");
            Files.writeString(f.ownEntrypoint, LauncherShims.cliScript(dir));
            Fs.makeExecutable(f.ownEntrypoint);
            assertEquals(dir, LauncherShims.danglingPinIn(f.ownEntrypoint).orElse(null),
                    "a pin naming a directory is not a live build");

            // (c) two assignment lines that disagree: cannot tell.
            Files.writeString(f.ownEntrypoint, """
                    #!/usr/bin/env bash
                    # skill-manager:cli-pin
                    cli="${SKILL_MANAGER_CLI:-/nowhere/one/skill-manager}"
                    cli="${SKILL_MANAGER_CLI:-/nowhere/two/skill-manager}"
                    exec "$cli" "$@"
                    """);
            Fs.makeExecutable(f.ownEntrypoint);
            assertTrue(LauncherShims.pinnedCliIn(f.ownEntrypoint).isEmpty(),
                    "two disagreeing pins are unreadable, not first-one-wins");

            // (d) a continued line. The pin is not on the line the prefix is.
            Files.writeString(f.ownEntrypoint, """
                    #!/usr/bin/env bash
                    # skill-manager:cli-pin
                    cli="${SKILL_MANAGER_CLI:-\\
                    /nowhere/wrapped/skill-manager}"
                    exec "$cli" "$@"
                    """);
            Fs.makeExecutable(f.ownEntrypoint);
            assertTrue(LauncherShims.pinnedCliIn(f.ownEntrypoint).isEmpty(),
                    "a continued line is unreadable, not a truncated path — the closing }\" "
                            + "is on the NEXT line, so the search fails rather than returning "
                            + "the truncated remainder");

            // And the control: a real, live pin still reads as live.
            Files.writeString(f.ownEntrypoint, LauncherShims.cliScript(real));
            Fs.makeExecutable(f.ownEntrypoint);
            assertEquals(real, LauncherShims.pinnedCliIn(f.ownEntrypoint).orElse(null),
                    "a plain live pin is still read");
            assertTrue(LauncherShims.danglingPinIn(f.ownEntrypoint).isEmpty(),
                    "and is not dangling");
        });

        suite.test("a remedy survives a home path a shell would otherwise interpret", () -> {
            // H5 of #229's review. Quoting on a space alone left `$` raw, so
            // the reader's shell EXPANDED it and the remedy bound a different
            // home — DEF-002's failure mode, produced by DEF-002's fix. A `'`
            // gave `unexpected EOF`; `;` and `&` are an injection surface whose
            // input is a filesystem path.
            for (String hostile : List.of(
                    "/tmp/home $HOME/x", "/tmp/home'x", "/tmp/home;rm -rf .",
                    "/tmp/home&background", "/tmp/home\ttab", "/tmp/home`id`",
                    "/tmp/home(paren)", "/tmp/home*glob", "/tmp/home\nnewline")) {
                String quoted = HomeDescriptor.shellQuote(hostile);
                assertEquals(hostile, unquoteWithShell(quoted),
                        "a shell reading " + quoted + " gets back exactly the path");
            }
            // The common case still costs no quotes at all.
            assertEquals("/Users/x/.skill-manager",
                    HomeDescriptor.shellQuote("/Users/x/.skill-manager"),
                    "an ordinary path is left alone");
            assertEquals("''", HomeDescriptor.shellQuote(""), "and the empty string is quoted");
        });

        suite.test("a hand-written entrypoint is left alone: cannot tell is not broken", () -> {
            Fixture f = Fixture.create("cli-handwritten-");
            // Deliberately shaped like ours where it matters and NOT ours
            // where it counts: it carries the assignment line the pin
            // extractor keys on, naming a path that does not exist, and it
            // carries no `skill-manager:cli-pin` marker. Without the marker
            // guard this file would be judged dangling and its home pushed off
            // its own entrypoint — which is the failure "cannot tell is not
            // broken" exists to prevent, so the fixture has to be able to
            // produce it.
            Files.writeString(f.ownEntrypoint, """
                    #!/usr/bin/env bash
                    # hand-rolled by an operator; not generated by `home shims`.
                    cli="${SKILL_MANAGER_CLI:-/nowhere/skill-manager}"
                    exec "$cli" "$@"
                    """);
            Fs.makeExecutable(f.ownEntrypoint);

            assertFalse(Files.isExecutable(Path.of("/nowhere/skill-manager")),
                    "the path it names really is absent — otherwise this proves nothing");
            assertTrue(dev.skillmanager.launch.LauncherShims
                            .pinnedCliIn(f.ownEntrypoint).isEmpty(),
                    "a file without the pin marker carries no pin this may judge, even though "
                            + "its assignment line parses");
            HomeDescriptor.ResolvedCli resolved = f.locate(null, f.running, f.pathDir);
            assertEquals(HomeDescriptor.CliSource.HOME_ENTRYPOINT, resolved.source(),
                    "so it is still this home's entrypoint — only a pin that is present AND "
                            + "names something absent is a finding");
            assertTrue(resolved.danglingHomePin() == null, "and nothing is reported dangling");
        });

        // ------------------------------- the contract, guarded mechanically

        suite.test("every printed remedy that names a class-2/3 verb also names its home", () -> {
            // THE GUARD THE CONTRACT NEEDS, and the reason it is a source scan.
            //
            // "Bind per verb" is a rule with N call sites, and this repository
            // has paid for that shape twice: `home close-out`'s fix went into
            // close-change.sh and left the human path un-runnable, and #142's
            // fix reached six refusals one at a time. The CLI resolution was
            // centralised for exactly that reason -- but the ARGUMENT cannot
            // be, because only the call site knows the verb. So the rule is
            // enforced by reading the sources.
            //
            // MEASURED NEED, not hypothetical: HIS-4 (#216) routed its new
            // merge-conflict remedies through HomeDescriptor.cliInvocation --
            // correctly, and that closed a finding -- and those remedies read
            // `<cli> sync <name>`, a class-3 verb with no --home. HIS-4
            // promotes before HIS-12. Without this, that lands unbound and
            // nobody finds out until an operator's root home is edited.
            List<String> unbound = new ArrayList<>();
            int checked = 0;
            for (Path file : javaSourcesUnder(mainSourceRoot())) {
                String src = Files.readString(file);
                Matcher m = REMEDY_VERB.matcher(src);
                while (m.find()) {
                    checked++;
                    // The rest of the statement: a remedy is built in one
                    // expression, and the binding has to be inside it.
                    int end = src.indexOf(";", m.end());
                    String statement = src.substring(m.start(), end < 0 ? src.length() : end);
                    if (statement.contains("--home") || statement.contains("homeArg")) continue;
                    unbound.add(file.getFileName() + ": " + oneLine(statement));
                }
            }
            // A scan that reads nothing proves nothing -- the failure mode this
            // epic keeps meeting.
            assertTrue(checked >= 4,
                    "the scan found class-2/3 remedy sites to check; found " + checked);
            assertEquals(List.of(), unbound,
                    "every remedy naming a verb that takes --home carries one");
        });

        suite.test("the merge-conflict remedy names the home it was asked about", () -> {
            // THE BEHAVIOURAL HALF OF DEF-026. The guard above reads SOURCE
            // TEXT: it proves the expression mentions --home, not that the
            // printed string carries the right home. Those are different
            // claims, and this epic has been bitten by exactly that gap.
            //
            // It also covers a real hole in the inherited tests: every existing
            // case for `mergeConflictRemedy` passes homeRoot = null, so with
            // `homeArg(null)` returning "" and the result stripped, the output
            // is byte-identical with or without the fix. Those tests could not
            // have failed on it.
            Path home = Fixture.home(
                    Files.createTempDirectory("merge-remedy-").resolve("proj/.skill-manager"));
            Path storeDir = Files.createDirectories(home.resolve("skills/u"));

            String bound = dev.skillmanager.app.ReportUseCase
                    .mergeConflictRemedy(storeDir, "u", home, 3);
            assertContains(bound, "sync u --home " + home,
                    "the remedy names the home it was asked about, in the argument");

            // And the null case still renders exactly as before — the flag is
            // additive, and a caller with no home to name must not get a
            // dangling `--home` or a trailing space.
            String unbound = dev.skillmanager.app.ReportUseCase
                    .mergeConflictRemedy(storeDir, "u", null, 3);
            assertContains(unbound, "`skill-manager sync u`",
                    "with no home to name, the remedy is unchanged and well-formed");
            assertFalse(unbound.contains("--home"),
                    "and carries no dangling flag: " + unbound);
        });

        return suite.runAll();
    }

    /**
     * A resolved CLI interpolated into a remedy, immediately followed by a verb
     * that TAKES {@code --home}.
     *
     * <p>The verb list is explicit and closed, and that is the whole
     * reliability of this check. The first version let the verb be any
     * lowercase word, which made {@code %s} match nearly every log format
     * string in the project -- ~180 false positives, a check satisfiable only
     * by weakening it. Naming the verbs makes each match a real remedy site.
     *
     * <p>{@code home sync} is CLASS 1 and is deliberately absent: it names its
     * target with {@code --from} / {@code --to} and has no {@code --home} at
     * all -- probed against the built CLI, not assumed. The negative lookbehind
     * keeps bare {@code sync} from matching it.
     *
     * <p>Three spellings the sources use: {@code %s <verb>} in a format string,
     * {@code " + cli + " <verb>} in the middle of a concatenation, and
     * {@code = cli + " <verb>} where the remedy is assigned to a variable
     * first.
     *
     * <p>That third form was a HOLE, found by vacuity-checking this guard: the
     * pattern required a {@code +} BEFORE the CLI token, so reverting
     * {@code ReportUseCase}'s fix to {@code String syncRemedy = cli + " sync "
     * + name} slipped past it while the behavioural assertion caught it. A
     * guard whose own vacuity check finds a way around it is a guard with a
     * gap, not a passing check.
     */
    private static final Pattern REMEDY_VERB = Pattern.compile(
            "(?:%s|[+=(,]\\s*(?:cli|rePin|spelling\\.binary\\(\\)|binary\\(\\))\\s*\\+\\s*\")"
                    + "\\s+(?<!home )(home drift|home shims|home close-out|unit publish"
                    + "|project sync|sync)(?![a-z-])");

    private static String oneLine(String s) {
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + " …";
    }

    private static List<Path> javaSourcesUnder(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static Path mainSourceRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path candidate = p.resolve("src/main/java/dev/skillmanager");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new AssertionError("cannot find src/main/java/dev/skillmanager from " + dir);
    }

    // ----------------------------------------------------------------- setup

    /**
     * One project home with every candidate CLI present as a real executable,
     * plus injectable {@code SKILL_MANAGER_CLI} / {@code PATH} / running-build
     * lookups.
     */
    private static final class Fixture {
        final Path home;
        final Path ownEntrypoint;
        final Path pin;
        final Path running;
        final Path pathDir;
        final Path onPath;

        private Fixture(Path home, Path ownEntrypoint, Path pin, Path running,
                        Path pathDir, Path onPath) {
            this.home = home;
            this.ownEntrypoint = ownEntrypoint;
            this.pin = pin;
            this.running = running;
            this.pathDir = pathDir;
            this.onPath = onPath;
        }

        static Fixture create(String prefix) throws Exception {
            Path tmp = Files.createTempDirectory(prefix);
            Path home = home(tmp.resolve("project/.skill-manager"));
            Path pathDir = Files.createDirectories(tmp.resolve("usr-local-bin"));
            return new Fixture(
                    home,
                    executable(home.resolve("bin/cli/skill-manager")),
                    executable(tmp.resolve("pinned/skill-manager")),
                    executable(tmp.resolve("running/skill-manager")),
                    pathDir,
                    executable(pathDir.resolve("skill-manager")));
        }

        /** A directory that {@code LaunchEnv.looksLikeStoreRoot} recognises. */
        static Path home(Path root) throws Exception {
            Files.createDirectories(root.resolve("installed"));
            Files.createDirectories(root.resolve("skills"));
            return root;
        }

        HomeDescriptor.ResolvedCli locate(Path pinned, Path runningBuild, Path path) {
            return HomeDescriptor.locateCli(home, env(pinned, path), supplier(runningBuild));
        }

        HomeDescriptor.CliSpelling spell(Path pinned, Path runningBuild, Path path) {
            return HomeDescriptor.cliSpelling(home, env(pinned, path), supplier(runningBuild));
        }

        private static Function<String, String> env(Path pinned, Path path) {
            Map<String, String> values = new LinkedHashMap<>();
            if (pinned != null) values.put("SKILL_MANAGER_CLI", pinned.toString());
            if (path != null) values.put("PATH", path.toString());
            return values::get;
        }

        private static Supplier<Path> supplier(Path runningBuild) {
            return () -> runningBuild;
        }
    }

    /**
     * What {@code /bin/sh} actually sees when it reads {@code quoted}.
     *
     * <p>A real shell, not a re-implementation of one. The defect being closed
     * is "the string we emit is not the string the reader's shell receives",
     * and an oracle that parsed the quoting itself would agree with whichever
     * model produced the bug.
     */
    private static String unquoteWithShell(String quoted) throws Exception {
        Process p = new ProcessBuilder("/bin/sh", "-c", "printf %s " + quoted)
                .redirectErrorStream(false)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        String err = new String(p.getErrorStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) {
            throw new AssertionError("/bin/sh refused " + quoted + ": exit " + rc + " " + err);
        }
        return out;
    }

    private static Path executable(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\nexit 0\n");
        Fs.makeExecutable(file);
        return file.toAbsolutePath().normalize();
    }

    // ---------------------------------------------- the javadoc as an oracle

    /**
     * The {@code <li>} bodies of the {@code <ol>} in {@code locateCli}'s
     * javadoc, in order, with markup and whitespace flattened.
     *
     * <p>Read from the source file on purpose. The whole defect this test
     * covers is a javadoc that stated a precedence the code did not have, and
     * an oracle that paraphrased the javadoc into the test would have agreed
     * with the wrong one.
     */
    private static List<String> documentedPrecedence() throws Exception {
        String source = Files.readString(homeDescriptorSource());
        int at = source.indexOf("public static ResolvedCli locateCli(Path storeRoot)");
        if (at < 0) throw new AssertionError("locateCli(Path) not found in HomeDescriptor");
        String javadoc = source.substring(0, at);
        int ol = javadoc.lastIndexOf("<ol>");
        int close = javadoc.indexOf("</ol>", ol);
        if (ol < 0 || close < 0) throw new AssertionError("locateCli's javadoc has no <ol>");
        String list = javadoc.substring(ol, close);
        List<String> steps = new ArrayList<>();
        Matcher m = Pattern.compile("<li>(.*?)</li>", Pattern.DOTALL).matcher(list);
        while (m.find()) {
            steps.add(m.group(1)
                    .replaceAll("(?m)^\\s*\\*\\s?", " ")
                    .replaceAll("\\s+", " ")
                    .strip());
        }
        return steps;
    }

    /**
     * {@code java} with every block and line comment blanked out.
     *
     * <p>Needed because this file's javadoc deliberately NAMES the dead step's
     * implementation in order to explain why it went. Scanning the raw text
     * would find the explanation and call it the defect — an oracle that fires
     * on its own documentation.
     */
    private static String stripComments(String java) {
        StringBuilder out = new StringBuilder(java.length());
        int i = 0;
        while (i < java.length()) {
            if (java.startsWith("/*", i)) {
                int end = java.indexOf("*/", i + 2);
                i = end < 0 ? java.length() : end + 2;
            } else if (java.startsWith("//", i)) {
                int end = java.indexOf('\n', i);
                i = end < 0 ? java.length() : end;
            } else {
                out.append(java.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static Path homeDescriptorSource() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path candidate = p.resolve("src/main/java/dev/skillmanager/store/HomeDescriptor.java");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        // Loud rather than vacuous: a scan that reads nothing proves nothing.
        throw new AssertionError("cannot find HomeDescriptor.java from " + dir
                + " — run the suite from the repository root");
    }
}
