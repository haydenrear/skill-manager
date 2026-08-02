//SOURCES ../lib/SmEnv.java
//SOURCES ../home-sync/HomeSyncSupport.java
//SOURCES ../ticket-lifecycle/TicketLifecycleSupport.java

import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared machinery for the {@code onboarding} graph — the walk a fresh
 * repository takes from "no home" to "a launchable agent with the skills it
 * declared", transcribed from a hand-run enumeration of that path.
 *
 * <h2>Every assertion here carries a companion that can make it fail</h2>
 *
 * <p>That is not a style preference, it is the specific defect this graph was
 * commissioned against. Four instruments in this project reported clean while
 * measuring nothing: a free-space check the host decided, a zsh
 * word-splitting bug that made every result vacuous, a fixture that froze a
 * path which was never a home, and four exit codes read off the wrong process
 * because {@code cmd | head; echo $?} reports {@code head}'s status. The
 * onboarding walk itself then found three MORE of them inside the product:
 * {@code bootstrap-home.sh}'s {@code verified: N skill(s) servable} counts the
 * store rather than the agent-visible links, {@code install} exits 0 over
 * printed violations, and {@code ADDED claude (<path>)} names a file the agent
 * does not read.
 *
 * <p>So the rule for this graph, stated once: <b>an assertion that could pass
 * by not looking must be accompanied, in the same run, by a check that proves
 * it can fail.</b> Each node names its companion in its own javadoc.
 *
 * <h2>Exit codes are captured, never piped</h2>
 *
 * <p>{@link ProcessRecord#exitCode()} is the child's status, taken from
 * {@code Process.waitFor}. Nothing in this graph reads an exit code through a
 * pipeline, because the shell transcription this graph replaces produced five
 * false readings that way.
 *
 * <h2>The source home is small ON PURPOSE, and that is a liability</h2>
 *
 * <p>The hand run used the operator's real {@code ~/.skill-manager}: 18 skills,
 * <b>855 MB</b> copied per clone. A graph that makes four homes would need
 * ~3.5 GB and several minutes. This fixture builds a source home from local
 * unit sources instead — but shrinking it is exactly how half these nodes
 * would go vacuous, because the properties under test are properties of a
 * REALISTIC home:
 *
 * <ul>
 *   <li>a unit resolved <em>transitively</em> ({@code ob-umbrella} →
 *       {@code ob-transitive} via {@code skill_references}),</li>
 *   <li>a CLI dep on the {@code skill-script} backend, which lands a
 *       <em>regular file</em> at {@code bin/cli/<dep>} — the shape that used to
 *       throw on re-projection,</li>
 *   <li>a shim dangling into a clone-skipped directory ({@code venvs/}),</li>
 *   <li>binding-ledger and {@code child-homes/} records naming checkouts
 *       OUTSIDE the home.</li>
 * </ul>
 *
 * <p>{@link #assertSourceHomeIsRealistic} states all four as preconditions, and
 * {@code onboarding.fixture.built} FAILS rather than skips when one is missing.
 * A fixture that is clean by construction is why the inherited-claims defect
 * survived four evals.
 */
final class OnboardingSupport {

    private OnboardingSupport() {}

    // ------------------------------------------------------------- the units

    /** Plain units, installed into the source home. */
    static final String ALPHA = "ob-alpha";
    static final String BETA = "ob-beta";
    static final String GAMMA = "ob-gamma";
    /** Declares {@code skill_references} — proves transitive resolution. */
    static final String UMBRELLA = "ob-umbrella";
    /** Reached only through {@link #UMBRELLA}. */
    static final String TRANSITIVE = "ob-transitive";
    /** Carries a {@code skill-script} CLI dep → a regular file in {@code bin/cli}. */
    static final String SCRIPT_UNIT = "ob-script";
    /** The shim that dep lands. */
    static final String SCRIPT_SHIM = "ob-script-shim";

    /** A unit whose two markdown skill-imports are both VALID. */
    static final String LINT = "acme-lint";
    /** A unit with exactly two INVALID imports: one missing unit, one missing path. */
    static final String BROKEN = "acme-broken";

    /**
     * The four {@code .gitignore} rules {@code references/skill-homes.md}
     * prescribes under "Ignoring the homes". Exactly these, and no fifth: the
     * omission of {@code /.claude.json} is the subject of the work-tree
     * cleanliness assertion, so the fixture must not pre-fix it.
     */
    static final List<String> DOCUMENTED_IGNORES =
            List.of("/.skill-manager/", "/.claude/", "/.codex/", "/.gemini/");

    /**
     * A build under test must be compiled from source, not a release.
     *
     * <p>{@code 0.20.0} alone means the launcher resolved a released jar and the
     * run measured the wrong binary; {@code +g<sha>} is the marker that it came
     * from this checkout. The fixture FAILS on a mismatch rather than skipping.
     */
    static final Pattern SOURCE_BUILD =
            Pattern.compile("^skill-manager \\d+\\.\\d+\\.\\d+\\+g[0-9a-f]+", Pattern.MULTILINE);

    // --------------------------------------------------------------- process

    /**
     * Run the pinned CLI against {@code home}, with the agent roots derived
     * from {@code agentRoot} rather than from {@code env.prepared}.
     *
     * <p>The derivation is the point for half this graph. {@code bootstrap-home.sh}
     * gives a checkout at {@code <root>} the agent roots {@code <root>/.claude},
     * {@code <root>/.codex}, {@code <root>/.gemini}, and the assertions about
     * where a projection landed, where the MCP entry landed and where the
     * launch env points are all assertions about THOSE paths. A node that ran
     * with {@code env.prepared}'s sandbox roots instead would be measuring a
     * directory the workflow never addresses.
     *
     * <p>The variables themselves come from {@link SmEnv} — this file must never
     * spell them, and {@code sandbox.env.contract} fails the build if it does.
     */
    static ProcessRecord sm(NodeContext ctx, String label, Path home, Path agentRoot,
                            String... args) {
        return smWith(ctx, label, home, agentRoot, pb -> {}, args);
    }

    /**
     * {@link #sm} with a hook that mutates the child environment last — for the
     * PATH-sanitizer node, which must plant entries on the INHERITED PATH, and
     * for the agent-root-derivation node, which must UNSET the agent variables
     * to prove the derivation is the CLI's rather than the harness's.
     */
    static ProcessRecord smWith(NodeContext ctx, String label, Path home, Path agentRoot,
                                Consumer<ProcessBuilder> tweak, String... args) {
        ProcessBuilder pb = cliProcess(home, agentRoot, args);
        applySandbox(ctx, pb, home, agentRoot);
        tweak.accept(pb);
        return Procs.run(ctx, label, pb);
    }

    /**
     * Run the home's OWN pinned shim ({@code <home>/bin/cli/skill-manager}).
     *
     * <p>{@code SKILL_MANAGER_CLI} is scrubbed rather than set, and that is not
     * hygiene. Since skill-manager #61 the pin resolves its own target as
     * {@code cli="${SKILL_MANAGER_CLI:-<absolute path>}"}, so naming the pin in
     * that variable makes it exec ITSELF forever — 7:03 of CPU over 13:06 of
     * wall clock in one measured teardown, silent throughout. On a graph run a
     * hang is indistinguishable from slow work, so it is prevented rather than
     * detected.
     */
    static ProcessRecord pinned(NodeContext ctx, String label, Path home, Path agentRoot,
                                String... args) {
        return pinnedWith(ctx, label, home, agentRoot, pb -> {}, args);
    }

    /** {@link #pinned} with an environment hook. */
    static ProcessRecord pinnedWith(NodeContext ctx, String label, Path home, Path agentRoot,
                                    Consumer<ProcessBuilder> tweak, String... args) {
        List<String> command = new ArrayList<>();
        command.add(home.resolve("bin/cli/skill-manager").toString());
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        applySandbox(ctx, pb, home, agentRoot);
        pb.environment().remove("SKILL_MANAGER_CLI");
        tweak.accept(pb);
        return Procs.run(ctx, label, pb);
    }

    /** Run one of the {@code git-integration-repo} scripts under test. */
    static ProcessRecord script(NodeContext ctx, String label, Path cwd, Path script,
                                Path ambientHome, String... args) {
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(script.toString());
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile());
        applySandbox(ctx, pb, ambientHome, ambientHome.getParent() == null
                ? ambientHome : ambientHome.resolveSibling("ambient-agents"));
        return Procs.run(ctx, label, pb);
    }

    /**
     * {@link #script} with a hook — used for the two refusal cases, which need
     * {@code HOME} pointed at a fixture directory that has no home in it at all.
     */
    static ProcessRecord scriptWith(NodeContext ctx, String label, Path cwd, Path script,
                                    Path ambientHome, Consumer<ProcessBuilder> tweak,
                                    String... args) {
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(script.toString());
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile());
        applySandbox(ctx, pb, ambientHome, ambientHome.resolveSibling("ambient-agents"));
        tweak.accept(pb);
        return Procs.run(ctx, label, pb);
    }

    /** A plain (non-skill-manager) command with the same sandboxed environment. */
    static ProcessRecord plain(NodeContext ctx, String label, Path cwd, Path home, Path agentRoot,
                               List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (cwd != null) pb.directory(cwd.toFile());
        applySandbox(ctx, pb, home, agentRoot);
        return Procs.run(ctx, label, pb);
    }

    /**
     * {@link #plain} with the four agent variables stripped — the probe that
     * makes "they were unset" a measurement rather than a claim.
     */
    static ProcessRecord plainWithUnsetAgentVars(NodeContext ctx, String label, Path cwd,
                                                 Path home, Path agentRoot,
                                                 List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (cwd != null) pb.directory(cwd.toFile());
        applySandbox(ctx, pb, home, agentRoot);
        unsetAgentVars(pb);
        return Procs.run(ctx, label, pb);
    }

    private static ProcessBuilder cliProcess(Path home, Path agentRoot, String... args) {
        List<String> command = new ArrayList<>();
        command.add(SmEnv.cli().toString());
        command.addAll(List.of(args));
        return new ProcessBuilder(command);
    }

    /**
     * The whole environment contract, in one place: {@link SmEnv}'s six managed
     * variables derived from {@code agentRoot}, a redirected POSIX {@code HOME}
     * so {@code bootstrap-home.sh}'s {@code GLOBAL_HOME=$HOME/.skill-manager}
     * lands inside the sandbox, and the CLI pin so the scripts do not resolve a
     * released build off PATH.
     */
    private static void applySandbox(NodeContext ctx, ProcessBuilder pb, Path home,
                                     Path agentRoot) {
        SmEnv.apply(pb, home.toString(), SmEnv.repoRoot().toString(),
                SmEnv.sandboxUnder(agentRoot));
        sandboxRoot(ctx).ifPresent(root -> SmEnv.alsoRedirectPosixHome(pb, root));
        pb.environment().put("SKILL_MANAGER_CLI", SmEnv.cli().toString());
    }

    /** The run's sandbox root — the {@code $HOME} every child in this graph gets. */
    static java.util.Optional<String> sandboxRoot(NodeContext ctx) {
        return ctx == null ? java.util.Optional.empty() : ctx.get("env.prepared", "home");
    }

    /**
     * {@code SKILL_MANAGER_HOME}, in a list so it can be removed without a
     * statement that both names it and touches {@code environment()} — which
     * {@code sandbox.env.contract} reads, correctly, as a second writer of the
     * env contract.
     */
    private static final List<String> STORE_HOME_VAR = List.of(SmEnv.SKILL_MANAGER_HOME);

    /**
     * Strip {@code SKILL_MANAGER_HOME} from a child environment.
     *
     * <p>For the one case that needs it: the refusal a genuinely fresh machine
     * hits, where no home has been named anywhere and the script must fall back
     * to {@code $HOME/.skill-manager} and find nothing. With the variable set,
     * the script resolves THAT home instead and takes a different refusal path
     * entirely — measured: exit 5 (an empty home) rather than exit 1 (no home),
     * which is a true statement about a state the fixture accidentally created.
     */
    static void unsetStoreHomeVar(ProcessBuilder pb) {
        for (String v : STORE_HOME_VAR) pb.environment().remove(v);
    }

    /**
     * Strip the four agent variables from a child environment.
     *
     * <p>Spelled through {@link SmEnv#AGENT_VARS} rather than by name because
     * {@code sandbox.env.contract} reads any statement that names a managed
     * variable alongside {@code environment()} as a second writer of the
     * contract — correctly, since a by-name spelling here IS a fourth copy.
     */
    static void unsetAgentVars(ProcessBuilder pb) {
        for (String v : SmEnv.AGENT_VARS) pb.environment().remove(v);
    }

    // ------------------------------------------------------------------ logs

    /** The merged stdout+stderr of a recorded process. Never piped. */
    static String log(NodeContext ctx, ProcessRecord proc) {
        if (proc == null || proc.logPath() == null) return "";
        try {
            return Files.readString(ctx.reportDir().resolve(proc.logPath()));
        } catch (IOException e) {
            return "";
        }
    }

    /** Lines of {@code text}, stripped, with blanks kept — order preserved. */
    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\n", -1)) out.add(raw.strip());
        return out;
    }

    /**
     * The value of one {@code KEY=value} line of {@code exec --print-env}.
     *
     * <p>Anchored at line start. The ambient PATH and the computed PATH differ,
     * and a substring search over the whole log would happily find the wrong
     * one — that is vacuous-pass risk 3 of the PATH-sanitizer assertion.
     */
    static String envValue(String printEnvLog, String key) {
        for (String line : lines(printEnvLog)) {
            if (line.startsWith(key + "=")) return line.substring(key.length() + 1);
        }
        return null;
    }

    /** How many times {@code needle} occurs as a whole line-substring. */
    static int count(String text, String needle) {
        int n = 0;
        int i = text.indexOf(needle);
        while (i >= 0) {
            n++;
            i = text.indexOf(needle, i + needle.length());
        }
        return n;
    }

    /**
     * Lines that look like a Java stack frame.
     *
     * <p>{@code at dev.skillmanager.app.RemoveUseCase.buildProgram(RemoveUseCase.java:77)}.
     * Deliberately structural rather than a search for the word "Exception",
     * because the useful half of a refusal — the message — legitimately names
     * exception types.
     */
    static final Pattern STACK_FRAME =
            Pattern.compile("^\\s*at [\\w.$]+\\(.*\\.java:\\d+\\)\\s*$");

    static List<String> stackFrames(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (STACK_FRAME.matcher(line).matches()) out.add(line.strip());
        }
        return out;
    }

    /**
     * Lines of {@code wt} contract output keyed by an anchored {@code ^KEY\s}.
     *
     * <p>Anchored because {@code CLOSE} is a substring of {@code CLOSED} and a
     * contains-check cannot tell the success contract from the failure one —
     * which is the whole assertion.
     */
    static boolean hasContractKey(String text, String key) {
        Pattern p = Pattern.compile("^" + Pattern.quote(key) + "\\s", Pattern.MULTILINE);
        return p.matcher(text).find();
    }

    // ------------------------------------------------------------ filesystem

    /** Entries of {@code dir}, sorted; empty when it does not exist. */
    static List<String> names(Path dir) {
        return HomeSyncSupport.names(dir);
    }

    /** The three agent directories a home at {@code <root>/.skill-manager} serves. */
    static List<String> AGENTS = List.of(".claude", ".codex", ".gemini");

    static Path agentSkills(Path agentRoot, String agent) {
        return agentRoot.resolve(agent).resolve("skills");
    }

    /**
     * Whether {@code <agentRoot>/<agent>/skills/<unit>} exists, is a link or a
     * directory, RESOLVES (i.e. is not dangling) and resolves INSIDE
     * {@code agentRoot}.
     *
     * <p>All four clauses matter and each one is a companion for the others. A
     * dangling symlink is still a directory entry, so a count of entries passes
     * on a home no agent can read; and a link that resolves OUTSIDE the root is
     * the cross-checkout leak this whole layout exists to prevent.
     */
    static boolean servable(Path agentRoot, String agent, String unit) {
        Path link = agentSkills(agentRoot, agent).resolve(unit);
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.exists(link)) return false;
        try {
            Path real = link.toRealPath();
            return real.startsWith(agentRoot.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    /** Units the home's STORE holds — {@code <home>/skills/<u>/SKILL.md}. */
    static List<String> storeUnits(Path home) {
        List<String> out = new ArrayList<>();
        for (String name : names(home.resolve("skills"))) {
            if (Files.isRegularFile(home.resolve("skills").resolve(name).resolve("SKILL.md"))) {
                out.add(name);
            }
        }
        return out;
    }

    /** Units servable on ALL THREE agents, i.e. what an agent launched here sees. */
    static List<String> servableUnits(Path home, Path agentRoot) {
        List<String> out = new ArrayList<>();
        for (String unit : storeUnits(home)) {
            boolean all = true;
            for (String agent : AGENTS) all &= servable(agentRoot, agent, unit);
            if (all) out.add(unit);
        }
        return out;
    }

    /** Every symlink under the three agent directories whose target escapes {@code root}. */
    static List<String> escapingLinks(Path agentRoot) {
        List<String> out = new ArrayList<>();
        for (String agent : AGENTS) {
            Path dir = agentRoot.resolve(agent);
            if (!Files.isDirectory(dir)) continue;
            try (var walk = Files.walk(dir, 4)) {
                walk.filter(p -> Files.isSymbolicLink(p)).forEach(p -> {
                    try {
                        Path target = Files.readSymbolicLink(p);
                        Path resolved = target.isAbsolute()
                                ? target : p.getParent().resolve(target);
                        if (!resolved.normalize().startsWith(agentRoot.normalize())) {
                            out.add(p + " -> " + target);
                        }
                    } catch (IOException ignored) {
                        // an unreadable link is reported by servable(), not here
                    }
                });
            } catch (IOException ignored) {
                // a directory that vanished mid-walk is not a finding
            }
        }
        return out;
    }

    // --------------------------------------------------- the polluted source

    /**
     * Plant the inherited-state pollution a realistic source home carries, and
     * that a purpose-built fixture home never does.
     *
     * <p>Two {@code installed/<unit>.projections.json} bindings and two
     * {@code child-homes/project_<name>} records naming checkouts OUTSIDE the
     * home. Synthetic paths under {@code foreign/} rather than real repository
     * paths: the property under test is "outside the home being created", not
     * "in {@code /Users/…/IdeaProjects}", and a literal-prefix predicate would
     * pass trivially in CI where every path is a temp dir.
     *
     * <p>Returns the foreign roots it planted, so the assertion can name them.
     */
    static List<Path> plantForeignClaims(Path srcHome, Path foreignBase, List<String> units)
            throws IOException {
        List<Path> planted = new ArrayList<>();
        Path childHomes = srcHome.resolve("child-homes");
        Files.createDirectories(childHomes);
        int i = 0;
        for (String unit : units) {
            String other = "foreign-checkout-" + (++i);
            Path checkout = foreignBase.resolve(other);
            Files.createDirectories(checkout.resolve(".skill-manager"));
            planted.add(checkout);

            // The ledger half — the half with projection TARGETS, i.e. the half
            // that says where an unbind or a rebind would write.
            Path ledger = srcHome.resolve("installed").resolve(unit + ".projections.json");
            String bindingId = "project:" + other + ":unit:" + unit;
            String block = """
                    , {
                      "bindingId" : "%s",
                      "unitName" : "%s",
                      "unitKind" : "SKILL",
                      "targetRoot" : "%s/.claude/skills",
                      "conflictPolicy" : "OVERWRITE",
                      "createdAt" : "2026-01-01T00:00:00Z",
                      "source" : "PROJECT",
                      "projections" : [ {
                        "bindingId" : "%s",
                        "sourcePath" : "$SKILL_MANAGER_HOME/skills/%s",
                        "destPath" : "%s/.claude/skills/%s",
                        "kind" : "SYMLINK"
                      } ]
                    } ]
                    }
                    """.formatted(bindingId, unit, checkout, bindingId, unit, checkout, unit);
            String existing = Files.readString(ledger);
            int close = existing.lastIndexOf("} ]");
            if (close < 0) throw new IOException("unexpected ledger shape: " + ledger);
            Files.writeString(ledger, existing.substring(0, close) + "}" + block);

            // The child-home half — the half whose "remove the child home
            // first" remedy, followed literally, points at someone else's repo.
            Files.writeString(childHomes.resolve("project_" + other),
                    """
                    {
                      "projectName" : "%s",
                      "childHome" : "%s/.skill-manager",
                      "units" : [ "%s" ]
                    }
                    """.formatted(other, checkout, unit));
        }
        return planted;
    }

    /** Directories {@code home clone} deliberately does not copy. */
    static final List<String> CLONE_SKIPPED =
            List.of("cache", "logs", "npm", "tmp", "tools", "venvs");

    /**
     * Plant a shim that will dangle AFTER the clone, the way a real home's do.
     *
     * <p>{@code home clone} deliberately does not copy {@code venvs/},
     * {@code cache/}, {@code tools/} or {@code npm/}, so a link into one of them
     * arrives broken. That is a known-open item rather than a defect, and the
     * fixture must carry one or the "verify tolerates historical dangling
     * links" guard has nothing to tolerate.
     *
     * <p><b>Relative, not absolute — and that distinction cost a run.</b> An
     * ABSOLUTE link into the source home's {@code venvs/} is not a dangling
     * shim, it is a path that reaches back into another home, and the clone
     * correctly refuses it: {@code ✗ FOREIGN_HOME bin/cli/ob-dangling …
     * resolves into the home at <src>}, {@code error: home clone failed}. A real
     * home's shims are relative and are re-anchored by the clone; they dangle
     * only because the directory they point INTO was skipped. The fixture has to
     * reproduce that shape rather than a superficially similar one, or it tests
     * the refusal instead of the tolerance.
     */
    static Path plantDanglingShim(Path srcHome) throws IOException {
        Path venv = srcHome.resolve("venvs").resolve("ob-tool").resolve("bin").resolve("ob-tool");
        Files.createDirectories(venv.getParent());
        Files.writeString(venv, "#!/bin/sh\nexit 0\n");
        Path shim = srcHome.resolve("bin").resolve("cli").resolve("ob-dangling");
        Files.createDirectories(shim.getParent());
        Files.deleteIfExists(shim);
        Files.createSymbolicLink(shim, Path.of("../../venvs/ob-tool/bin/ob-tool"));
        return shim;
    }

    /**
     * The four §6.2 realism preconditions, each answered separately so a
     * failing fixture names WHICH one it failed.
     *
     * <p>These exist because the hand run used an 855 MB source home and this
     * graph uses a small one. Shrinking a fixture is the cheapest way to make
     * half a graph vacuous, so the shrink is compensated by stating what the
     * big home supplied and checking that the small one supplies it too.
     */
    record Realism(boolean transitivelyResolvedUnit, boolean regularFileCliShim,
                   boolean danglingShim, boolean foreignClaims, int foreignBindingRecords,
                   int foreignChildHomes) {
        boolean ok() {
            return transitivelyResolvedUnit && regularFileCliShim && danglingShim && foreignClaims;
        }
    }

    static Realism assertSourceHomeIsRealistic(Path srcHome) throws IOException {
        boolean transitive = Files.isRegularFile(
                srcHome.resolve("skills").resolve(TRANSITIVE).resolve("SKILL.md"));
        boolean regularShim = false;
        Path binCli = srcHome.resolve("bin").resolve("cli");
        for (String name : names(binCli)) {
            if (Files.isRegularFile(binCli.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                regularShim = true;
                break;
            }
        }
        // A shim whose target lies under a directory the clone skips. It
        // RESOLVES in the source (so the source is a coherent home) and will
        // dangle in the copy — which is the state the tolerance guard is about.
        boolean dangling = false;
        for (String name : names(binCli)) {
            Path p = binCli.resolve(name);
            if (!Files.isSymbolicLink(p)) continue;
            String target = Files.readSymbolicLink(p).toString();
            for (String skipped : CLONE_SKIPPED) {
                if (target.contains("/" + skipped + "/")) { dangling = true; break; }
            }
            if (dangling) break;
        }
        int bindings = 0;
        Path installed = srcHome.resolve("installed");
        for (String name : names(installed)) {
            if (!name.endsWith(".projections.json")) continue;
            String text = Files.readString(installed.resolve(name));
            bindings += count(text, "\"source\" : \"PROJECT\"");
        }
        int children = names(srcHome.resolve("child-homes")).size();
        return new Realism(transitive, regularShim, dangling,
                bindings >= 2 && children >= 2, bindings, children);
    }

    // ------------------------------------------------- the ledger, on disk

    /** {@code "destPath" : "…"} / {@code "targetRoot" : "…"} in a ledger file. */
    private static final Pattern LEDGER_TARGET =
            Pattern.compile("\"(?:destPath|targetRoot)\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Binding records read from {@code installed/*.projections.json} whose
     * target lies outside {@code root}.
     *
     * <h2>Why the files and not {@code bindings list}</h2>
     *
     * <p>Every skill-manager command reconciles against
     * {@code $SKILL_MANAGER_HOME} before doing anything else, and that pass
     * <b>writes</b>: it rewrites the projection ledger from live state. So a
     * node that asked the CLI "what bindings does this home hold?" would first
     * destroy the inherited records it was about to ask about. Measured — the
     * planted records were present when the fixture finished, gone by the time
     * the first {@code bindings list} returned, and the assertion read zero
     * foreign rows from a home that had two. That is the vacuous pass this node
     * exists to prevent, arrived at from inside the instrument.
     *
     * <p>The ledger is therefore read as bytes, at the one moment it holds the
     * state under test: immediately after the clone, before any command has run
     * against either home.
     */
    static List<String> ledgerTargetsOutside(Path home, Path root) {
        List<String> out = new ArrayList<>();
        Path base = root.toAbsolutePath().normalize();
        Path installed = home.resolve("installed");
        for (String name : names(installed)) {
            if (!name.endsWith(".projections.json")) continue;
            Matcher m = LEDGER_TARGET.matcher(read(installed.resolve(name)));
            while (m.find()) {
                String target = m.group(1);
                if (!target.startsWith("/")) continue;
                if (!Path.of(target).toAbsolutePath().normalize().startsWith(base)) {
                    out.add(name + " -> " + target);
                }
            }
        }
        return out;
    }

    /** {@code child-homes/} records naming an absolute path outside {@code root}. */
    static List<String> childHomesOutside(Path home, Path root) {
        List<String> out = new ArrayList<>();
        Path base = root.toAbsolutePath().normalize();
        Path dir = home.resolve("child-homes");
        for (String name : names(dir)) {
            for (String token : read(dir.resolve(name)).split("[\"\\s,]+")) {
                if (!token.startsWith("/")) continue;
                if (!Path.of(token).toAbsolutePath().normalize().startsWith(base)) {
                    out.add(name + " -> " + token);
                    break;
                }
            }
        }
        return out;
    }

    // --------------------------------------------------------- bindings list

    /** One row of {@code skill-manager bindings list}. */
    record Binding(String id, String unit, String target) {}

    /**
     * Parse {@code bindings list}'s table.
     *
     * <p>The output is wide and it APPENDS the persisted-error banner, so the
     * parser stops at the first blank line after the header rather than trying
     * to classify every trailing line. The caller asserts the row count is at
     * least {@code installed units × 3} before filtering, so a truncated or
     * mis-parsed listing cannot read as "no foreign rows".
     */
    static List<Binding> bindings(String listOutput) {
        List<Binding> out = new ArrayList<>();
        boolean started = false;
        for (String raw : listOutput.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("ID") && line.contains("TARGET")) {
                started = true;
                continue;
            }
            if (!started) continue;
            if (line.isEmpty()) break;
            String[] parts = line.split("\\s{2,}");
            if (parts.length < 3) continue;
            // id unit [sub-element] target policy managed-by — the target is the
            // first field that looks like an absolute path.
            String target = null;
            for (String part : parts) {
                if (part.startsWith("/")) { target = part; break; }
            }
            if (target == null) continue;
            out.add(new Binding(parts[0], parts[1], target));
        }
        return out;
    }

    /** Rows whose TARGET is not under {@code root}, by real path, not by prefix string. */
    static List<Binding> foreignBindings(List<Binding> rows, Path root) {
        Path base = root.toAbsolutePath().normalize();
        List<Binding> out = new ArrayList<>();
        for (Binding b : rows) {
            if (!Path.of(b.target()).toAbsolutePath().normalize().startsWith(base)) out.add(b);
        }
        return out;
    }

    // ------------------------------------------------------------- fixtures

    /** A skill unit source directory, optionally with markdown skill-imports. */
    static Path mkUnit(Path parent, String name, String description, String imports)
            throws IOException {
        Path dir = parent.resolve(name);
        Files.createDirectories(dir.resolve("references"));
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n"
                        + (imports == null ? "" : imports) + "---\n\n# " + name + "\n");
        Files.writeString(dir.resolve("skill-manager.toml"),
                "[skill]\nname = \"" + name + "\"\nversion = \"0.1.0\"\n"
                        + "description = \"" + description + "\"\n");
        Files.writeString(dir.resolve("references").resolve("page.md"),
                "# " + name + " reference\n");
        return dir;
    }

    /** A {@code skill-imports:} frontmatter block. */
    static String imports(String... entries) {
        return "skill-imports:\n" + String.join("", entries);
    }

    static String entry(String unit, String path, String reason) {
        return "  - unit: " + unit + "\n"
                + "    path: " + path + "\n"
                + "    reason: " + reason + "\n";
    }

    /** Run git, recording the command when it fails so setup cannot fail silently. */
    static void git(List<String> failures, Path dir, String... args) {
        HomeSyncSupport.Capture capture = HomeSyncSupport.git(dir, args);
        if (!capture.ok()) failures.add(String.join(" ", args) + " -> " + capture.trimmed());
    }

    // ----------------------------------------------------------- the scripts

    /**
     * {@code git-integration-repo}'s scripts — {@code bootstrap-home.sh},
     * {@code new-change.sh}, {@code close-change.sh}, {@code wt}.
     *
     * <p>Delegated to {@code ticket-lifecycle}'s locator rather than restated:
     * it already resolves the four routes correctly, including the awkward one
     * where this repository is worked on from a linked worktree placed OUTSIDE
     * the integration repo so no ancestor of the working tree holds
     * {@code integration.toml}.
     */
    static TicketLifecycleSupport.Scripts scripts(NodeContext ctx) {
        return TicketLifecycleSupport.scripts(ctx);
    }

    /** The skill root the scripts live in — {@code constituents/git-integration-repo}. */
    static Path skillRoot(TicketLifecycleSupport.Scripts scripts) {
        return scripts.dir().getParent();
    }

    // --------------------------------------------------- a tiny path helper

    /** Read a file, or "" — a missing file is a finding for the caller, not here. */
    static String read(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** The first regex group of the first match, or null. */
    static String firstGroup(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** A shallow ordered map of a file tree's entry → digest, for change detection. */
    static Map<String, String> snapshot(Path root) throws IOException {
        if (!Files.exists(root)) return new LinkedHashMap<>();
        return HomeSyncSupport.entryDigests(root);
    }
}
