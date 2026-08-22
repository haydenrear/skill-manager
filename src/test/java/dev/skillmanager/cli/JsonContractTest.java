package dev.skillmanager.cli;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.sandbox.Confinement;
import dev.skillmanager.store.HomeLock;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * <b>Under {@code --json}, stdout carries only JSON — exactly one document, or
 * none, and never anything else — on every exit path.</b>
 *
 * <h2>Why this is a check over the CONVENTION and not over one command</h2>
 *
 * <p>#235 was found as a red integration graph in a later wave than the one
 * that caused it. The graph node that caught it asserts about
 * {@code home close-out} specifically, and somebody had to have written that
 * node; nothing asserted the contract itself, so all thirty-one commands
 * declaring {@code --json} were each a fresh chance to break it silently.
 *
 * <p>The evidence that this is a convention problem rather than a bug is two
 * ADJACENT CATCH BLOCKS in {@code HomeCommand.CloseOutCmd}: a
 * {@code NotAHomeException} is answered with a JSON error document, and a
 * {@code FrozenHomeException} one arm below it is answered with nothing at
 * all. Nobody decided that. The second catch was simply written later than
 * its sibling, by someone who had no reason to look at the first.
 *
 * <h2>The three rules, and why the third one is not a deferral</h2>
 *
 * <ol>
 *   <li><b>PURITY</b>, for every command: stdout holds zero or one parseable
 *       JSON document and no other bytes. This is the rule the regression
 *       broke — {@code HomeLock.announceWait} wrote a progress line to stdout
 *       on a run that <em>succeeded</em>.</li>
 *   <li><b>PRESENCE</b>, for non-zero exits: a {@code --json} run that fails
 *       emits a document saying why.</li>
 *   <li><b>THE PINNED SILENT PATHS</b>: three commands legitimately print
 *       nothing on some successful runs. Inventing an {@code {"error":…}} for
 *       them would be a lie. They are asserted to print <em>nothing</em>, by
 *       name — so a change that starts writing prose there fails this guard,
 *       and a change that starts writing a document fails it too and forces
 *       someone to decide on purpose. A gap that is merely deferred is an
 *       unasserted state, and this epic has been bitten by that shape
 *       repeatedly.</li>
 * </ol>
 *
 * <h2>The enumeration is reflective, deliberately</h2>
 *
 * <p>{@link #jsonCommands()} walks the live picocli tree. A hard-coded list
 * would go stale the day someone adds the thirty-second command — silently,
 * which is the exact failure mode this file exists to end. Adding a
 * {@code --json} command with no entry in {@link #TABLE} fails
 * {@code every_json_command_is_covered}.
 */
public final class JsonContractTest {

    /** What a command is expected to do on the invocation the table declares. */
    private enum Mode {
        /** Exits non-zero: must print exactly one JSON document. */
        FAILS,
        /** Exits zero and prints one JSON document. */
        SUCCEEDS,
        /** Exits zero and prints NOTHING — pinned, see the class javadoc. */
        SILENT
    }

    private record Expect(Mode mode, List<String> args, String why) {}

    /**
     * One entry per {@code --json} command. The args are chosen to reach the
     * declared outcome <b>without touching the network, the gateway, or any
     * home outside the sandbox</b> — every run happens against a throwaway
     * home pinned through {@link AgentHomes}.
     */
    private static final Map<String, Expect> TABLE = new LinkedHashMap<>();

    private static void expect(String path, Mode mode, String why, String... args) {
        TABLE.put(path, new Expect(mode, List.of(args), why));
    }

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("JsonContractTest");

        // ---------------------------------------------- the enumeration itself
        suite.test("every --json command is covered by this guard's table", () -> {
            Set<String> live = jsonCommands();
            assertFalse(live.isEmpty(), "the reflective walk found the --json commands at all");
            Set<String> declared = new TreeSet<>(TABLE.keySet());
            Set<String> missing = new TreeSet<>(live);
            missing.removeAll(declared);
            Set<String> stale = new TreeSet<>(declared);
            stale.removeAll(live);
            assertEquals("[]", missing.toString(),
                    "a --json command with no entry here is an unguarded contract; add one");
            assertEquals("[]", stale.toString(),
                    "an entry for a command that no longer declares --json");
        });

        // ------------------------------------------------- the mechanism itself
        suite.test("in json mode no Log level writes to stdout, and stderr still gets them", () -> {
            Streams s = capture(() -> {
                Log.setJsonMode(true);
                Log.setVerbose(true);
                try {
                    Log.info("info line");
                    Log.ok("ok line");
                    Log.step("step line");
                    Log.detail("detail line");
                    Log.warn("warn line");
                    Log.error("error line");
                } finally {
                    Log.setJsonMode(false);
                    Log.setVerbose(false);
                }
                return 0;
            });
            assertEquals("", s.out().strip(),
                    "stdout is reserved for the document — this is the byte that broke #235");
            for (String level : List.of("info", "ok", "step", "detail", "warn", "error")) {
                assertTrue(s.err().contains(level + " line"),
                        "the " + level + " line is still reported, on stderr — routing is not "
                                + "deleting the diagnostic, and a silent 120s wait was the "
                                + "reason announceWait exists");
            }
        });

        suite.test("outside json mode the same lines go to stdout, so the latch is what moved them",
                () -> {
                    Streams s = capture(() -> {
                        Log.setJsonMode(false);
                        Log.info("plain line");
                        return 0;
                    });
                    assertTrue(s.out().contains("plain line"),
                            "without --json stdout is the human's stream, unchanged");
                });

        // ---------------------------------- the regression itself, end to end
        suite.test("a CONTENDED close-out keeps stdout pure — #235's own case", () -> {
            // WITHOUT THIS CASE THE GUARD IS VACUOUS FOR THE BUG IT EXISTS
            // FOR. Every table entry above drives a command that fails through
            // Log.error, which has always gone to stderr; none of them makes
            // any code emit Log.info, so none of them would redden if the json
            // routing were reverted. The defect was a PROGRESS line on a
            // SUCCEEDING run, and that is what this reproduces: a peer thread
            // holds the destination home's lock, so HomeLock.announceWait
            // fires, and the command still exits 0.
            Path sandbox = Files.createTempDirectory("json-contended-");
            Path wt = home(sandbox.resolve("wt"));
            Path proj = home(sandbox.resolve("proj"));
            // acquireWithoutCreating only reaches the OS lock when the file is
            // already there, which is what a home that has been synced once
            // looks like. Without this the peer is never even contended with.
            Files.createDirectories(HomeLock.file(proj).getParent());
            Files.createTempFile(HomeLock.file(proj).getParent(), "seed", "");
            Files.write(HomeLock.file(proj), new byte[0]);

            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread peer = new Thread(() -> {
                try (HomeLock ignored = HomeLock.acquire(proj, "peer")) {
                    held.countDown();
                    release.await(30, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    held.countDown();
                }
            });
            peer.setDaemon(true);
            peer.start();
            assertTrue(held.await(10, TimeUnit.SECONDS), "the peer took the lock");

            Invocation run;
            try {
                run = invokeIn(sandbox, List.of("home", "close-out",
                        "--home", wt.toString(), "--into", proj.toString()));
            } finally {
                release.countDown();
            }

            assertEquals(0, run.exitCode(),
                    "fixture precondition: the contended run still SUCCEEDS — the defect was "
                            + "never on a failure path; stderr=" + preview(run.stderr()));
            // THE PRECONDITION IS DELIBERATELY STREAM-BLIND. Its job is to
            // prove contention happened at all; asking for the notice on
            // STDERR specifically would make it fail whenever the routing is
            // broken -- which is the mutation this case exists to detect. The
            // first version did exactly that, and the vacuity run caught it
            // reddening on its precondition instead of on its claim: HIS-4's
            // probe 7a, one epic later, in the guard written to prevent it.
            assertTrue(run.stdout().contains("waiting for")
                            || run.stderr().contains("waiting for"),
                    "fixture precondition: the wait really was announced somewhere, or this "
                            + "case is measuring an uncontended run and proves nothing");

            List<String> docs = documentsIn(run.stdout().strip());
            assertEquals(1, docs.size(), "exactly one document on stdout");
            assertEquals("", nonJsonRemainder(run.stdout().strip(), docs),
                    "and NOTHING else on stdout — THE CLAIM. The wait notice is a diagnostic "
                            + "and belongs on stderr");
            assertTrue(run.stderr().contains("waiting for"),
                    "and the notice is not merely deleted: it is still reported, on stderr, "
                            + "because a silent 120s wait looks like a hang");
            // WHICH operation contended, not merely that something did. HIS-14
            // gated tryReconcile on WRITES_HOME, and `home close-out` is
            // READ_ONLY, so the reconcile no longer runs before this command --
            // if this case had been measuring THAT contention it would now be
            // measuring nothing and would go green for the wrong reason. The
            // label proves the wait is the command's own dry-run sync into the
            // named project home, which is the path #235 travelled.
            assertTrue(run.stderr().contains("home sync --dry-run: waiting for"),
                    "the contended operation is close-out's own dry-run sync, not an ambient "
                            + "reconcile: " + preview(run.stderr()));
        });

        suite.test("a caught, typed refusal carries its OWN reason, not the generic one", () -> {
            // The generic envelope would already answer this path -- but with
            // error="failed", because a CAUGHT exception never reaches the
            // classifier. close-change.sh reads `safe` and `blockers`, so the
            // command answers in its own shape. This case is what keeps the
            // explicit arm from being deleted as redundant: the net makes the
            // output PARSEABLE, and only the arm makes it USEFUL.
            Path sandbox = Files.createTempDirectory("json-frozen-");
            Path wt = home(sandbox.resolve("wt"));
            Path proj = home(sandbox.resolve("proj"));
            Files.writeString(proj.resolve("home.policy.toml"), "policy = \"frozen\"\n");

            Invocation run = invokeIn(sandbox, List.of("home", "close-out",
                    "--home", wt.toString(), "--into", proj.toString()));

            assertTrue(run.exitCode() != 0,
                    "fixture precondition: a frozen destination really does refuse");
            List<String> docs = documentsIn(run.stdout().strip());
            assertEquals(1, docs.size(), "one document");
            String doc = docs.get(0);
            assertTrue(doc.contains("\"error\":\"home_frozen\""),
                    "the reason is the TYPED one, not the generic \"failed\": " + preview(doc));
            assertTrue(doc.contains("\"safe\":false") && doc.contains("\"blockers\":[]"),
                    "and it carries the fields the gate reads: " + preview(doc));
        });

        suite.test("exit 13 — HIS-14's unbindable-home refusal — emits a document too", () -> {
            // THE ARGUMENT FOR A CONVENTION CHECK, tested on the first command
            // to grow a new failure path after the check landed. HIS-14 (#232)
            // added a refusal that returns UNBINDABLE_HOME_EXIT_CODE straight
            // out of the execution strategy — the one exit path in this CLI
            // that went AROUND completeExecution, and therefore around the
            // --json envelope. Under --json it exited 13 with an empty stdout:
            // #235's exact shape, on a path that did not exist when #235 was
            // filed. An enumerated list of known-bad sites would not have
            // covered it, because nobody had written it yet.
            Path sandbox = Files.createTempDirectory("json-unbindable-");
            Path worktree = sandbox.resolve("wt");
            Path store = home(worktree.resolve(".skill-manager"));
            Path proj = home(sandbox.resolve("proj"));
            // The home's .claude is a symlink OUT of the home, so --home
            // cannot be honoured on the agent axis and the bind refuses.
            Path outside = Files.createDirectories(sandbox.resolve("outside"));
            Files.createSymbolicLink(worktree.resolve(".claude"), outside);

            Invocation run = invokeIn(sandbox, List.of("home", "close-out",
                    "--home", store.toString(), "--into", proj.toString()));

            assertEquals(13, run.exitCode(),
                    "fixture precondition: this really is HIS-14's unbindable refusal and not "
                            + "some other failure; stderr=" + preview(run.stderr()));
            List<String> docs = documentsIn(run.stdout().strip());
            assertEquals(1, docs.size(),
                    "a --json run that exits 13 says why, in JSON — got " + preview(run.stdout()));
            assertEquals("", nonJsonRemainder(run.stdout().strip(), docs),
                    "and the refusal's own prose stays on stderr");
            assertTrue(run.stderr().contains("refusing:"),
                    "the human rendering is not deleted, only moved");
        });

        // ------------------------------------------------------ the convention
        for (Map.Entry<String, Expect> entry : TABLE.entrySet()) {
            String path = entry.getKey();
            Expect expect = entry.getValue();
            suite.test("`" + path + " --json` " + label(expect.mode()) + " — " + expect.why(),
                    () -> {
                        Invocation run = invoke(path, expect.args());
                        String out = run.stdout().strip();

                        // PURITY, asserted for every mode.
                        List<String> docs = documentsIn(out);
                        assertTrue(docs.size() <= 1,
                                "stdout holds at most one JSON document, got " + docs.size()
                                        + ": " + preview(out));
                        assertEquals("", nonJsonRemainder(out, docs),
                                "stdout holds NOTHING but the document; this is the assertion "
                                        + "HomeLock.announceWait broke, on a run that exited 0");

                        switch (expect.mode()) {
                            case FAILS -> {
                                assertTrue(run.exitCode() != 0,
                                        "fixture precondition: this invocation really does fail, "
                                                + "got exit 0 with " + preview(out));
                                assertEquals(1, docs.size(),
                                        "a failing --json run says why, in JSON — exit "
                                                + run.exitCode() + " printed " + preview(out));
                            }
                            case SUCCEEDS -> {
                                assertEquals(0, run.exitCode(),
                                        "fixture precondition: this invocation really does "
                                                + "succeed; stderr=" + preview(run.stderr()));
                                assertEquals(1, docs.size(),
                                        "a succeeding --json run emits its document");
                            }
                            case SILENT -> {
                                assertEquals(0, run.exitCode(),
                                        "fixture precondition: this pinned path really does "
                                                + "succeed; stderr=" + preview(run.stderr()));
                                assertEquals("", out,
                                        "PINNED: this path prints nothing today. If you are "
                                                + "reading this because it failed, you changed "
                                                + "what it prints — decide deliberately and "
                                                + "move it out of SILENT, do not delete the case");
                            }
                        }
                    });
        }

        return suite.runAll();
    }

    private static String label(Mode mode) {
        return switch (mode) {
            case FAILS -> "fails with one document";
            case SUCCEEDS -> "succeeds with one document";
            case SILENT -> "succeeds silently (pinned)";
        };
    }

    // ------------------------------------------------------------ enumeration

    /**
     * Every command path declaring {@code --json}, from the live picocli tree.
     *
     * <p>Deduplicated by command <em>spec identity</em>, because picocli
     * exposes an alias as a second entry in the subcommand map — {@code list}
     * and {@code ls} are one command and must not be two rows here.
     */
    static Set<String> jsonCommands() {
        Set<String> out = new TreeSet<>();
        Map<CommandLine.Model.CommandSpec, String> seen = new IdentityHashMap<>();
        walk(new CommandLine(new SkillManagerCli()), "skill-manager", out, seen);
        return out;
    }

    private static void walk(CommandLine cl, String path, Set<String> out,
                             Map<CommandLine.Model.CommandSpec, String> seen) {
        CommandLine.Model.CommandSpec spec = cl.getCommandSpec();
        boolean firstSpelling = seen.putIfAbsent(spec, path) == null;
        boolean declaresJson = spec.options().stream()
                .anyMatch(o -> Arrays.asList(o.names()).contains("--json"));
        if (declaresJson && firstSpelling) out.add(path);
        for (Map.Entry<String, CommandLine> sub : cl.getSubcommands().entrySet()) {
            walk(sub.getValue(), path + " " + sub.getKey(), out, seen);
        }
    }

    // -------------------------------------------------------------- the driver

    private record Invocation(int exitCode, String stdout, String stderr) {}

    private record Streams(String out, String err) {}

    /**
     * Run one command in-process against a throwaway home.
     *
     * <p>All five home variables are pinned through {@link AgentHomes}'
     * thread-local overrides before anything runs, and the sandbox is
     * <b>asserted</b> rather than assumed: if {@code SkillStore.defaultStore()}
     * does not resolve inside the temp directory the test fails instead of
     * running a command that could write the operator's real home. This
     * repository has damaged a real home more than once during this epic.
     */
    private static Invocation invoke(String path, List<String> extra) throws Exception {
        Path sandbox = Files.createTempDirectory("json-contract-");
        List<String> argv = new ArrayList<>(Arrays.asList(path.split(" ")));
        argv.remove(0);                       // drop the "skill-manager" head
        argv.addAll(extra);
        return invokeIn(sandbox, argv);
    }

    /** A home directory shaped enough to be recognised as one. */
    private static Path home(Path root) throws Exception {
        Files.createDirectories(root.resolve("skills"));
        Files.createDirectories(root.resolve("installed"));
        return root;
    }

    private static Invocation invokeIn(Path sandbox, List<String> argv) throws Exception {
        Path home = sandbox.resolve("home");
        Files.createDirectories(home.resolve("skills"));
        Files.createDirectories(home.resolve("installed"));
        Map<String, Path> overrides = new LinkedHashMap<>();
        overrides.put(SkillStore.HOME_ENV, home);
        overrides.put(AgentHomes.CLAUDE_HOME, sandbox.resolve("agents"));
        overrides.put(AgentHomes.CLAUDE_CONFIG_DIR, sandbox.resolve("agents/.claude"));
        overrides.put(AgentHomes.CODEX_HOME, sandbox.resolve("agents/.codex"));
        overrides.put(AgentHomes.GEMINI_HOME, sandbox.resolve("agents/.gemini"));
        for (Map.Entry<String, Path> o : overrides.entrySet()) {
            Files.createDirectories(o.getValue());
            AgentHomes.setOverride(o.getKey(), o.getValue());
        }
        // DECLARE the confinement, don't just pin variables. This is the axis
        // the five overrides above cannot cover, and DEF-046 is what happens
        // without it: `project resolve` walked up from THIS repository and
        // re-realized a worktree home while the driver's own sandbox assertion
        // said everything was fine.
        AgentHomes.setOverride(Confinement.ROOT_ENV, sandbox);
        try {
            Path resolved = SkillStore.defaultStore().root().toAbsolutePath().normalize();
            assertTrue(resolved.startsWith(sandbox.toAbsolutePath().normalize()),
                    "SANDBOX: the default home must resolve inside the temp dir, got " + resolved);

            // ONE CALL, and it covers CWD. The assertion is not `confined()`:
            // a JVM cannot change its own working directory, so this driver can
            // NEVER be fully confined and asserting it would be asserting a
            // falsehood. What it asserts is the honest statement — every axis I
            // can pin IS pinned, and the only escape is the one I cannot pin.
            // A regression that unpinned CODEX_HOME would show up here as a
            // second escaped axis, which a boolean would have hidden.
            Confinement confinement = Confinement.current();
            assertTrue(confinement.declared(), "SANDBOX: a confinement is declared");
            assertEquals(List.of(Confinement.CWD), confinement.escapedAxes(),
                    "SANDBOX: the working directory is the ONLY axis outside the sandbox\n"
                            + confinement.describe());

            List<String> full = new ArrayList<>(argv);
            full.add("--json");
            String[] args = full.toArray(new String[0]);
            int[] rc = {0};
            Streams s = capture(() -> rc[0] = SkillManagerCli.execute(args));
            return new Invocation(rc[0], s.out(), s.err());
        } finally {
            AgentHomes.clearOverrides();
        }
    }

    private interface Body { int run() throws Exception; }

    private static Streams capture(Body body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            body.run();
        } finally {
            System.out.flush();
            System.err.flush();
            System.setOut(realOut);
            System.setErr(realErr);
        }
        return new Streams(out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------- the JSON scanner

    /**
     * Every complete JSON document in {@code text}, scanned by brace/bracket
     * depth outside string literals.
     *
     * <p>Not a line-based split and not a regex: a pretty-printed document
     * spans lines, and "the output starts with {" is exactly the weak check
     * that would pass on {@code {"a":1} trailing prose}. What this returns is
     * used together with {@link #nonJsonRemainder} so the two questions —
     * "how many documents" and "what else is there" — are both answered.
     */
    static List<String> documentsIn(String text) {
        List<String> docs = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> { if (depth++ == 0) start = i; }
                case '}', ']' -> {
                    if (depth > 0 && --depth == 0 && start >= 0) {
                        docs.add(text.substring(start, i + 1));
                        start = -1;
                    }
                }
                default -> { }
            }
        }
        return docs;
    }

    /** Whatever is on stdout that is not one of the documents. */
    static String nonJsonRemainder(String text, List<String> docs) {
        String rest = text;
        for (String doc : docs) rest = rest.replace(doc, "");
        return rest.strip();
    }

    private static String preview(String text) {
        String one = text.replace("\n", "\\n").strip();
        return one.length() <= 160 ? "[" + one + "]" : "[" + one.substring(0, 160) + "…]";
    }

    static {
        // ---- the pinned silent three (see the class javadoc, rule 3) --------
        expect("skill-manager harness rm", Mode.SILENT,
                "no binding matches, so there is nothing to report", "no-such-instance");
        expect("skill-manager home drift", Mode.SILENT,
                "--ack writes a record and reports through its exit code", "--ack");

        // ---- failure paths --------------------------------------------------
        expect("skill-manager home close-out", Mode.FAILS,
                "the --home is not a home at all",
                "--home", "/nonexistent/sm-guard/wt", "--into", "/nonexistent/sm-guard/proj");
        expect("skill-manager home sync", Mode.FAILS,
                "the --from is not a home",
                "--from", "/nonexistent/sm-guard/a", "--to", "/nonexistent/sm-guard/b");
        expect("skill-manager home clone", Mode.FAILS,
                "the source home does not exist",
                "--from", "/nonexistent/sm-guard/a", "--to", "/nonexistent/sm-guard/b");
        expect("skill-manager home describe", Mode.FAILS,
                "the named path is not a home", "--home", "/nonexistent/sm-guard/x");
        expect("skill-manager show", Mode.FAILS, "no such unit is installed", "no-such-unit");
        expect("skill-manager artifacts show", Mode.FAILS, "no such artifact", "no-such-unit");
        expect("skill-manager bindings show", Mode.FAILS, "no such binding", "no-such-unit");
        expect("skill-manager bind", Mode.FAILS, "no such unit to bind",
                "no-such-unit", "--to", "/nonexistent/sm-guard/target");
        expect("skill-manager build", Mode.FAILS, "no such unit to build", "no-such-unit");
        expect("skill-manager harness show", Mode.FAILS, "no such template", "no-such-harness");
        expect("skill-manager harness instantiate", Mode.FAILS, "no such template",
                "no-such-harness");
        expect("skill-manager install", Mode.FAILS,
                "a local path that is not there — no registry is contacted",
                "/nonexistent/sm-guard/unit");
        // The `project` family resolves its project by walking UP FROM THE
        // WORKING DIRECTORY, and a JVM cannot change its own working
        // directory. Run in this repository they used to find THIS project and
        // ACT on it — measured as DEF-046/DEF-047: `project resolve --json`
        // returned a real report for skill-manager itself, and re-realized the
        // worktree home, removing one unit and installing two. So these four
        // were driven on a deliberate parse error, which executes nothing.
        //
        // They are now driven on their REAL execution path. What changed is
        // production: this driver declares a Confinement over its temp
        // directory (see invokeIn), the working directory is outside it, and
        // ProjectRoot refuses rather than acting — exit 14, one JSON document,
        // and nothing written. The parse-error stand-in asserted the contract
        // on a path that reaches no code; this asserts it on the path that
        // caused the incident.
        expect("skill-manager project register", Mode.FAILS,
                "a confined process refuses a project root taken from a CWD outside it");
        expect("skill-manager project remove", Mode.FAILS,
                "a confined process refuses a project root taken from a CWD outside it",
                "no-such-project");
        expect("skill-manager project resolve", Mode.FAILS,
                "a confined process refuses a project root taken from a CWD outside it",
                "--skip-gateway");
        expect("skill-manager project sync", Mode.FAILS,
                "a confined process refuses a project root taken from a CWD outside it",
                "--skip-gateway");
        expect("skill-manager unit publish", Mode.FAILS, "no such unit is installed",
                "no-such-unit");

        // ---- success paths, which must still emit exactly one document ------
        expect("skill-manager list", Mode.SUCCEEDS, "an empty home lists nothing, in JSON");
        expect("skill-manager artifacts list", Mode.SUCCEEDS, "empty ledger, in JSON");
        expect("skill-manager artifacts stale", Mode.SUCCEEDS, "nothing stale, in JSON");
        expect("skill-manager artifacts prune", Mode.SUCCEEDS, "nothing to prune, in JSON");
        expect("skill-manager artifacts record", Mode.SUCCEEDS, "records nothing, in JSON");
        expect("skill-manager bindings list", Mode.SUCCEEDS, "no bindings, in JSON");
        expect("skill-manager harness list", Mode.SUCCEEDS, "no templates, in JSON");
        expect("skill-manager lock status", Mode.SUCCEEDS, "an empty lock, in JSON");
        expect("skill-manager home shims", Mode.FAILS,
                "refuses: nothing honest to pin when the running build cannot be named");
        expect("skill-manager home refresh-plugins", Mode.SUCCEEDS,
                "nothing installed, so nothing to refresh");
        expect("skill-manager env sync", Mode.FAILS, "no project env is declared here");
        // Exit 14, not 0: this driver's working directory is outside the
        // confinement it declares — a JVM cannot change its own — so the honest
        // answer to "am I confined?" is no, and the document says which axis.
        expect("skill-manager sandbox status", Mode.FAILS,
                "declared, but the cwd axis escapes — see Confinement's class comment");
    }
}
