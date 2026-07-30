package dev.skillmanager.launch;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.commands.ExecCommand;
import dev.skillmanager.commands.HomeCommand;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.store.HomeDescriptor;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The kernel boundary: the profile, the parameters, and the bytes.
 *
 * <h2>Assert on BYTES, not on status</h2>
 *
 * <p>A sandbox denial reaches a shell as a message on stderr and the shell's
 * own exit status, which for {@code sh -c 'echo x > denied'} run through
 * {@code sandbox-exec} is frequently <b>0</b>. That trap was hit twice during
 * this ticket's research, once by an agent that had been warned about it in the
 * same brief, and it is the reason every enforcement assertion below is
 * {@code Files.exists(...)} on the file that must not appear and
 * {@code Files.readString(...)} on the bytes that must not change.
 *
 * <h2>The temp roots are writable, so the fixture cannot live in them</h2>
 *
 * <p>The shipped profile grants writes under {@code $TMPDIR}, {@code /tmp} and
 * {@code /var/tmp}. That is deliberate and measured — {@code /bin/bash} 3.2
 * writes here-document bodies to {@code /tmp} and ignores {@code TMPDIR}, so
 * without it every {@code bash -c 'cat <<EOF'} in an agent session dies — and
 * it is fine in production, where the four homes being defended are under
 * {@code ~}.
 *
 * <p>It is <b>not</b> fine in a test, because a JVM's
 * {@code Files.createTempDirectory} lands under {@code $TMPDIR}. A decoy built
 * there is writable, the write succeeds, and an assertion written the obvious
 * way passes for the wrong reason — this was hit while building these tests,
 * and it is the same "the instrument could not see" shape the whole ticket is
 * about. So the enforcement cases below bind {@code OP_TMPDIR}/{@code
 * OP_SYSTMP}/{@code OP_VARTMP} to a dedicated scratch subdirectory rather than
 * to the real temp roots, which leaves the sibling decoy outside every allowed
 * subtree. The graph node {@code home.clone.no.write.escapes.the.worktree}
 * covers the shipped parameterization instead, with its decoys under the repo.
 */
public final class SeatbeltSandboxTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("SeatbeltSandboxTest");

        // --------------------------------------------------- the profile

        suite.test("the vendored region is byte-identical to the upstream file", () -> {
            assertEquals(SeatbeltProfile.VENDORED_SHA256,
                    SeatbeltProfile.sha256(SeatbeltProfile.vendoredBase()),
                    "openai/codex seatbelt_base_policy.sbpl, vendored verbatim under Apache-2.0. "
                            + "If this fails someone edited inside the vendored markers; put the "
                            + "change in the skill-manager section below them instead");
            assertTrue(SeatbeltProfile.defaultProfile().contains(SeatbeltProfile.VENDOR_BEGIN),
                    "the vendored region is delimited so the fork is visible in one diff");
            assertContains(SeatbeltProfile.defaultProfile(),
                    "; inspired by Chrome's sandbox policy:",
                    "upstream's attribution to Chromium's common.sb (BSD-3) is kept");
            assertTrue(SeatbeltProfile.defaultProfile().indexOf("(version 1)")
                            == SeatbeltProfile.defaultProfile().lastIndexOf("(version 1)"),
                    "SBPL takes exactly one (version 1), and it is the vendored file's");
        });

        suite.test("the shipped profile validates clean", () -> {
            List<SeatbeltProfile.Problem> problems =
                    SeatbeltProfile.validate(SeatbeltProfile.defaultProfile());
            assertTrue(problems.isEmpty(), "no problems, got " + problems);
        });

        // The defect that made this validator necessary: the researcher's own
        // first draft ended in a broad allow, reported success, and enforced
        // nothing. Reproduced here on the real profile — appending this one
        // line made a previously denied decoy write succeed, file present.
        suite.test("a TRAILING broad allow is refused — SBPL is last-match-wins", () -> {
            String reopened = SeatbeltProfile.defaultProfile()
                    + "\n(allow file-write* (subpath \"/private/tmp\"))\n";
            List<SeatbeltProfile.Problem> problems = SeatbeltProfile.validate(reopened);
            assertTrue(problems.stream().anyMatch(
                            p -> p.kind() == SeatbeltProfile.Problem.Kind.BROAD_WRITE_ALLOW),
                    "a trailing broad allow must be reported, got " + problems);
            assertContains(problems.toString(), "/private/tmp", "and it must name the path");
        });

        suite.test("a broad allow in the MIDDLE is refused too — position is not the test", () -> {
            String text = SeatbeltProfile.defaultProfile()
                    .replace("(allow file-read*)",
                            "(allow file-write* (subpath \"/Users\"))\n(allow file-read*)");
            assertTrue(SeatbeltProfile.validate(text).stream().anyMatch(
                            p -> p.kind() == SeatbeltProfile.Problem.Kind.BROAD_WRITE_ALLOW),
                    "a broad allow anywhere reopens what it names");
        });

        suite.test("a write granted with no path filter at all is refused", () -> {
            String text = SeatbeltProfile.defaultProfile() + "\n(allow file-write*)\n";
            assertTrue(SeatbeltProfile.validate(text).stream().anyMatch(
                            p -> p.kind() == SeatbeltProfile.Problem.Kind.BROAD_WRITE_ALLOW),
                    "an unfiltered write grant is the broadest allow there is");
        });

        suite.test("(allow default) and a missing (deny default) are both refused", () -> {
            assertTrue(SeatbeltProfile.validate(SeatbeltProfile.defaultProfile()
                            + "\n(allow default)\n").stream()
                            .anyMatch(p -> p.kind() == SeatbeltProfile.Problem.Kind.ALLOW_DEFAULT),
                    "(allow default) undoes everything");
            assertTrue(SeatbeltProfile.validate(
                            SeatbeltProfile.defaultProfile().replace("(deny default)", ""))
                            .stream().anyMatch(p ->
                                    p.kind() == SeatbeltProfile.Problem.Kind.NOT_DENY_BY_DEFAULT),
                    "without (deny default) an unmatched operation is ALLOWED");
        });

        suite.test("the validator does not flag the vendored /dev rules", () -> {
            // The vendored base grants file-write* to /dev/ptmx and to a
            // /dev/ttys regex. A validator that flagged those would be one
            // nobody could ship, so it would be turned off, so it would protect
            // nothing — the way an over-strict check becomes no check.
            assertTrue(SeatbeltProfile.validate(SeatbeltProfile.vendoredBase()
                            + "\n(deny default)\n").isEmpty(),
                    "device writes are not broad allows");
        });

        // ------------------------------------------------------ parameters

        suite.test("a relative parameter is refused", () -> {
            assertRefused(() -> SeatbeltSandbox.requireCanonicalParameters(
                            Map.of("OP_WORKTREE", "wt")),
                    "is relative",
                    "sandbox-exec accepts a relative -D silently and it matches nothing");
        });

        suite.test("a non-canonical parameter is refused", () -> {
            Path real = Files.createTempDirectory("seatbelt-canon-").toRealPath();
            Path link = real.resolveSibling(real.getFileName() + "-link");
            Files.createSymbolicLink(link, real);
            assertRefused(() -> SeatbeltSandbox.requireCanonicalParameters(
                            Map.of("OP_WORKTREE", link.toString())),
                    "is not canonical",
                    "the kernel canonicalizes the ACCESSED path, never the RULE path");
            // And the same value, canonicalized, is accepted — without this the
            // test would also pass against a checker that refused everything.
            SeatbeltSandbox.requireCanonicalParameters(Map.of("OP_WORKTREE", real.toString()));
        });

        suite.test("a writable root that is the user's home directory is refused", () -> {
            String home = System.getenv("HOME") != null
                    ? System.getenv("HOME") : System.getProperty("user.home");
            Path userHome = Path.of(home).toRealPath();
            assertRefused(() -> SeatbeltSandbox.writableRoot("OP_WORKTREE", userHome),
                    "is the user's home directory",
                    "granting $HOME would grant the four homes the sandbox exists to protect");
        });

        suite.test("a writable root that is a filesystem root is refused", () ->
                assertRefused(() -> SeatbeltSandbox.writableRoot("OP_WORKTREE", Path.of("/")),
                        "is a filesystem root", "that grants everything"));

        suite.test("a writable root that does not exist is refused", () ->
                assertRefused(() -> SeatbeltSandbox.writableRoot("OP_WORKTREE",
                                Path.of("/nope/definitely/not/here")),
                        "does not resolve", "a (subpath …) of nothing is a rule about nothing"));

        // ------------------------------------------------------- the plan

        suite.test("a home that did not opt in is not sandboxed", () -> {
            Home home = Home.create("seatbelt-optout-");
            home.writeDescriptor(Map.of());
            LaunchEnv launch = LaunchEnv.of(home.store, null, "/usr/bin:/bin", false);
            assertTrue(SeatbeltSandbox.planFor(home.store, launch, Map.of()).isEmpty(),
                    "opt-in per home: a bad profile must not brick every launch on the machine");
        });

        suite.test("opting in with no launch.sb REFUSES rather than launching unconfined", () -> {
            Home home = Home.create("seatbelt-noprofile-");
            home.writeDescriptor(Map.of(SeatbeltSandbox.ENABLE_VAR, "1"));
            LaunchEnv launch = LaunchEnv.of(home.store, null, "/usr/bin:/bin", false);
            assertRefused(() -> SeatbeltSandbox.planFor(home.store, launch, Map.of()),
                    "does not exist",
                    "proceeding unsandboxed after being asked to sandbox is a silent divergence "
                            + "between what the operator believes and what is true");
        });

        suite.test("opting in with a widened launch.sb REFUSES", () -> {
            Home home = Home.create("seatbelt-widened-");
            home.writeDescriptor(Map.of(SeatbeltSandbox.ENABLE_VAR, "1"));
            Files.writeString(SeatbeltSandbox.profileFile(home.store),
                    SeatbeltProfile.defaultProfile()
                            + "\n(allow file-write* (subpath \"/private/tmp\"))\n");
            LaunchEnv launch = LaunchEnv.of(home.store, null, "/usr/bin:/bin", false);
            assertRefused(() -> SeatbeltSandbox.planFor(home.store, launch, Map.of()),
                    "last-match-wins",
                    "an edited profile stops launches rather than silently permitting writes");
        });

        suite.test("a plan wraps the argv in sandbox-exec with -D parameters", () -> {
            Home home = Home.create("seatbelt-plan-");
            home.writeDescriptor(Map.of(SeatbeltSandbox.ENABLE_VAR, "1"));
            SeatbeltProfile.install(SeatbeltSandbox.profileFile(home.store));
            LaunchEnv launch = LaunchEnv.of(home.store, null, "/usr/bin:/bin", false);
            SeatbeltSandbox.Plan plan = SeatbeltSandbox.planFor(home.store, launch, Map.of())
                    .orElseThrow();

            List<String> argv = plan.wrap(List.of("/bin/echo", "hi"));
            assertEquals(SeatbeltSandbox.EXECUTABLE.toString(), argv.get(0), "sandbox-exec leads");
            assertEquals("-f", argv.get(1), "the profile is a file, not an inline -p string");
            assertEquals(plan.profile().toString(), argv.get(2), "…and it is this home's");
            assertEquals("/bin/echo", argv.get(argv.size() - 2), "the command survives intact");
            assertEquals("hi", argv.get(argv.size() - 1), "and so do its arguments");
            for (String parameter : SeatbeltProfile.PARAMETERS) {
                assertTrue(plan.parameters().containsKey(parameter),
                        parameter + " is bound — an unbound (param …) is an SBPL load error");
                assertTrue(argv.contains(parameter + "=" + plan.parameters().get(parameter)),
                        parameter + " travels as -D data, never spliced into the profile text");
            }
            assertEquals(home.root.toRealPath().toString(), plan.parameters().get("OP_WORKTREE"),
                    "the writable root is the WORKTREE — an agent must edit the tree it was "
                            + "given the ticket for");
            assertEquals(home.root.toRealPath().resolve(".cache/gradle").toString(),
                    plan.env().get("GRADLE_USER_HOME"),
                    "build caches are redirected INTO the home rather than allowed out of it");
        });

        suite.test("a launch already inside a seatbelt is not wrapped again", () -> {
            Home home = Home.create("seatbelt-nested-");
            home.writeDescriptor(Map.of(SeatbeltSandbox.ENABLE_VAR, "1"));
            SeatbeltProfile.install(SeatbeltSandbox.profileFile(home.store));
            LaunchEnv launch = LaunchEnv.of(home.store, null, "/usr/bin:/bin", false);
            assertTrue(SeatbeltSandbox.planFor(home.store, launch,
                            Map.of(SeatbeltSandbox.ACTIVE_VAR, "/somewhere/launch.sb")).isEmpty(),
                    "a nested apply naming a different root dies with sandbox_apply: Operation "
                            + "not permitted, and re-applying buys nothing — the child already "
                            + "cannot loosen what it inherited");
        });

        suite.test("the descriptor's declaration outranks the ambient environment", () -> {
            Home home = Home.create("seatbelt-precedence-");
            home.writeDescriptor(Map.of(SeatbeltSandbox.ENABLE_VAR, "0"));
            LaunchEnv launch = LaunchEnv.of(home.store, null, "/usr/bin:/bin", false);
            assertTrue(SeatbeltSandbox.planFor(home.store, launch,
                            Map.of(SeatbeltSandbox.ENABLE_VAR, "1")).isEmpty(),
                    "a home that declared itself unsandboxed is not sandboxed by an export");

            Home optedIn = Home.create("seatbelt-precedence-in-");
            optedIn.writeDescriptor(Map.of(SeatbeltSandbox.ENABLE_VAR, "1"));
            SeatbeltProfile.install(SeatbeltSandbox.profileFile(optedIn.store));
            LaunchEnv inLaunch = LaunchEnv.of(optedIn.store, null, "/usr/bin:/bin", false);
            assertTrue(SeatbeltSandbox.planFor(optedIn.store, inLaunch,
                            Map.of(SeatbeltSandbox.ENABLE_VAR, "0")).isPresent(),
                    "and a home that declared itself sandboxed cannot be un-sandboxed by one");
        });

        // ------------------------------------------------------ the bytes

        suite.test("BYTES: a write inside the worktree lands and every operator-home shape "
                + "is left untouched", () -> {
            if (!Files.isExecutable(SeatbeltSandbox.EXECUTABLE)) {
                // Stated, not silent. A skip that reads as a pass is the exact
                // failure this ticket exists to remove.
                System.out.println("      SKIPPED: " + SeatbeltSandbox.EXECUTABLE
                        + " is not executable on this machine, so there is no kernel boundary to "
                        + "measure. The refusal path is covered above.");
                return;
            }
            Home home = Home.create("seatbelt-bytes-");
            home.writeDescriptor(Map.of(SeatbeltSandbox.ENABLE_VAR, "1"));
            SeatbeltProfile.install(SeatbeltSandbox.profileFile(home.store));
            LaunchEnv launch = LaunchEnv.of(home.store, null, "/usr/bin:/bin", false);
            SeatbeltSandbox.Plan plan = bounded(
                    SeatbeltSandbox.planFor(home.store, launch, Map.of()).orElseThrow());

            // Shaped like the operator's real homes, and deliberately outside
            // every allowed subtree — see the class javadoc on the temp roots.
            Path decoy = Files.createTempDirectory("seatbelt-operator-decoy-").toRealPath();
            Map<String, Path> shapes = new LinkedHashMap<>();
            for (String name : List.of(".skill-manager", ".claude", ".codex", ".gemini")) {
                Path dir = decoy.resolve(name);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("settings.json"), "ORIGINAL");
                shapes.put(name, dir);
            }

            Path inside = home.root.toRealPath().resolve("edited-by-the-agent.txt");
            StringBuilder script = new StringBuilder("echo WORKTREE > '" + inside + "'\n");
            for (Path dir : shapes.values()) {
                script.append("echo LEAK > '").append(dir.resolve("new.txt")).append("'\n");
                script.append("echo LEAK > '").append(dir.resolve("settings.json")).append("'\n");
            }
            script.append("exit 0\n");

            ProcessBuilder pb = new ProcessBuilder(
                    plan.wrap(List.of("/bin/sh", "-c", script.toString())))
                    .redirectErrorStream(true);
            pb.environment().putAll(plan.env());
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int status = process.waitFor();

            assertEquals("WORKTREE", Files.readString(inside).strip(),
                    "the agent can still edit the source tree it was given the ticket for");
            for (Map.Entry<String, Path> shape : shapes.entrySet()) {
                assertFalse(Files.exists(shape.getValue().resolve("new.txt"),
                                LinkOption.NOFOLLOW_LINKS),
                        "no file appears in a " + shape.getKey() + "-shaped home outside the "
                                + "worktree (status was " + status + "; a denial reaches a shell "
                                + "as exit 0, which is why this asserts on the FILE) — " + output);
                assertEquals("ORIGINAL", Files.readString(shape.getValue()
                                .resolve("settings.json")).strip(),
                        "and pre-existing bytes in " + shape.getKey() + " are intact");
            }
        });

        suite.test("BYTES: a symlink out of the worktree does not escape", () -> {
            if (!Files.isExecutable(SeatbeltSandbox.EXECUTABLE)) {
                System.out.println("      SKIPPED: no " + SeatbeltSandbox.EXECUTABLE);
                return;
            }
            Home home = Home.create("seatbelt-escape-");
            home.writeDescriptor(Map.of(SeatbeltSandbox.ENABLE_VAR, "1"));
            SeatbeltProfile.install(SeatbeltSandbox.profileFile(home.store));
            LaunchEnv launch = LaunchEnv.of(home.store, null, "/usr/bin:/bin", false);
            SeatbeltSandbox.Plan plan = bounded(
                    SeatbeltSandbox.planFor(home.store, launch, Map.of()).orElseThrow());

            Path decoy = Files.createTempDirectory("seatbelt-escape-decoy-").toRealPath();
            Files.createSymbolicLink(home.root.toRealPath().resolve("escape"), decoy);

            ProcessBuilder pb = new ProcessBuilder(plan.wrap(List.of("/bin/sh", "-c",
                    "echo LEAK > '" + home.root.toRealPath().resolve("escape/leak.txt") + "'; "
                            + "exit 0"))).redirectErrorStream(true);
            pb.environment().putAll(plan.env());
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();

            assertFalse(Files.exists(decoy.resolve("leak.txt"), LinkOption.NOFOLLOW_LINKS),
                    "the kernel canonicalizes the path being accessed, so a symlink out of an "
                            + "allowed subtree is still outside it — " + output);
        });

        // ---------------------------------------------------- the commands

        suite.test("home shims --sandbox writes the profile and declares the opt-in", () -> {
            Home home = Home.create("seatbelt-shims-");
            home.writeDescriptor(Map.of());
            int rc = new CommandLine(new HomeCommand.ShimsCmd(home.store)).execute("--sandbox");
            assertEquals(0, rc, "home shims --sandbox succeeds");
            Path profile = SeatbeltSandbox.profileFile(home.store);
            assertTrue(Files.isRegularFile(profile), "it wrote " + profile);
            assertTrue(SeatbeltProfile.validate(Files.readString(profile)).isEmpty(),
                    "and what it wrote validates");
            assertEquals("1",
                    HomeDescriptor.read(home.store.root()).orElseThrow()
                            .envContributions().get(SeatbeltSandbox.ENABLE_VAR),
                    "both halves of the opt-in come from one command");

            assertEquals(0, new CommandLine(new HomeCommand.ShimsCmd(home.store))
                            .execute("--no-sandbox"),
                    "and it is reversible");
            assertEquals("0",
                    HomeDescriptor.read(home.store.root()).orElseThrow()
                            .envContributions().get(SeatbeltSandbox.ENABLE_VAR),
                    "opting out is a declaration, not a deletion");
        });

        suite.test("exec --home <a path that is not a home> refuses and creates NOTHING", () -> {
            Path parent = Files.createTempDirectory("seatbelt-exec-typo-");
            Path typo = parent.resolve("skill-manger");   // the mistyped --home
            Result result = captureErr(() -> new CommandLine(new ExecCommand())
                    .execute("--home", typo.toString(), "--no-reconcile", "--", "/bin/true"));

            assertEquals(2, result.rc, "refused as an argument error");
            assertFalse(Files.exists(typo, LinkOption.NOFOLLOW_LINKS),
                    "and nothing was laid out at the typo. This used to scaffold an eleven-entry "
                            + "home plus .claude/.codex/.gemini and exit 0, and exec is what "
                            + "every bin/launch shim calls");
            assertContains(result.err, "is not a Skill Manager home", "and it says why");
        });

        suite.test("home verify --home <a path that is not a home> refuses instead of ✓", () -> {
            Home real = Home.create("seatbelt-verify-real-");
            Path absent = Files.createTempDirectory("seatbelt-verify-")
                    .resolve("never-created");
            Result result = captureErr(() -> new CommandLine(new HomeCommand.VerifyCmd())
                    .execute("--home", absent.toString(),
                            "--against", real.store.root().toString()));
            assertEquals(2, result.rc,
                    "this is the ORACLE the onboarding acceptance criterion rests on; it used to "
                            + "print ✓ and exit 0 for a home that was never there, because a "
                            + "destination with no files contributes no leaks");
            assertContains(result.err, "is not a Skill Manager home", "and it says which argument");

            Result badAgainst = captureErr(() -> new CommandLine(new HomeCommand.VerifyCmd())
                    .execute("--home", real.store.root().toString(),
                            "--against", absent.toString()));
            assertEquals(2, badAgainst.rc,
                    "both sides: verifying against a home that was never the origin also finds "
                            + "nothing and also used to say ✓");

            // The succeeding direction, so this is not a test that would pass
            // against a command that always refuses.
            Home other = Home.create("seatbelt-verify-other-");
            assertEquals(0, new CommandLine(new HomeCommand.VerifyCmd())
                            .execute("--home", real.store.root().toString(),
                                    "--against", other.store.root().toString()),
                    "two real homes that do not reference each other still verify clean");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------ plumbing

    /**
     * The same plan with the three temp roots narrowed to one scratch
     * directory, so a decoy created by {@code Files.createTempDirectory} — which
     * lands under {@code $TMPDIR} — is genuinely outside the sandbox. Without
     * this the enforcement assertions measure nothing; see the class javadoc.
     */
    private static SeatbeltSandbox.Plan bounded(SeatbeltSandbox.Plan plan) throws Exception {
        Path scratch = Files.createTempDirectory("seatbelt-scratch-tmp-").toRealPath();
        Map<String, String> parameters = new LinkedHashMap<>(plan.parameters());
        parameters.put("OP_TMPDIR", scratch.toString());
        parameters.put("OP_SYSTMP", scratch.toString());
        parameters.put("OP_VARTMP", scratch.toString());
        SeatbeltSandbox.requireCanonicalParameters(parameters);
        return new SeatbeltSandbox.Plan(plan.profile(), parameters, plan.env());
    }

    private static void assertRefused(ThrowingRun op, String needle, String why) throws Exception {
        try {
            op.run();
        } catch (SeatbeltRefusedException refused) {
            assertContains(refused.getMessage(), needle, why);
            return;
        }
        throw new AssertionError("expected a refusal (" + why + "), got none");
    }

    @FunctionalInterface
    private interface ThrowingRun { void run() throws Exception; }

    private static Result captureErr(ThrowingInt op) throws Exception {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err, true));
            return new Result(op.run(), err.toString());
        } finally {
            System.setErr(originalErr);
        }
    }

    @FunctionalInterface
    private interface ThrowingInt { int run() throws Exception; }

    private record Result(int rc, String err) {}

    private record Home(Path root, SkillStore store) {

        static Home create(String prefix) throws Exception {
            Path root = Files.createTempDirectory(prefix);
            SkillStore store = new SkillStore(root.resolve(".skill-manager"));
            store.init();
            return new Home(root, store);
        }

        void writeDescriptor(Map<String, String> contributions) throws Exception {
            new HomeDescriptor(
                    root,
                    HomePolicy.LIVE.wire(),
                    HomeDescriptor.envFor(root, store.root()),
                    null,
                    new HomeDescriptor.Gateway("http://127.0.0.1:51717", true),
                    List.of(),
                    contributions).write(store.root());
        }
    }
}
