///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES ../lib/IntentionalDamage.java

import com.hayden.testgraphsdk.sdk.ContextItem;
import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * THE FIXPOINT LAW, as one post-condition shared by every graph that mutates a
 * home rather than as a node somebody remembered to write.
 *
 * <p>For every Skill Manager home this graph produced:
 *
 * <pre>
 *   home verify --home &lt;h&gt;   must exit 0
 *   and where it refuses, the remedy IT PRINTED must clear it, first try.
 * </pre>
 *
 * <h2>Why a law and not another assertion</h2>
 *
 * <p>Six defects in a row have had the same shape: a state {@code home verify}
 * refuses on, and a repair path that could not clear it. Each was found by
 * hand, on one home, after somebody noticed a tool failing at exec time —
 * {@code jinja2} through a foreign home's PATH entry, then the same through a
 * symlink, then every generated WRAPPER because the backend asked
 * {@code isExecutable} and a wrapper execing a missing target is executable.
 * Every one of them would have been caught the first time any graph asked this
 * question of a home it had just built.
 *
 * <p>So the question is asked of ALL of them, everywhere, by one
 * implementation. A per-graph bespoke check is how the previous five got
 * through: the graph that would have caught them was always the one nobody had
 * added the check to.
 *
 * <h2>The remedy is PARSED, never reconstructed</h2>
 *
 * <p>The refusal prints a runnable command. This node extracts that exact
 * string from stdout and runs it through {@code sh -c}. It does NOT rebuild
 * {@code env SKILL_MANAGER_HOME=… skill-manager build --stale} from
 * parts — a test that rebuilds the remedy is asserting against a COPY of the
 * production logic and passes happily while the real printed sentence is
 * un-runnable, which is defect #142 exactly (see 69ad2ac). The string the
 * operator would paste is the string under test.
 *
 * <h2>Homes are discovered structurally, and finding none is a FAILURE</h2>
 *
 * <p>Graphs publish home paths under a dozen different keys — {@code home},
 * {@code storeDir}, {@code projectHome}, {@code rootHome},
 * {@code worktreeHome}, {@code sandboxGlobalHome}, {@code ambientHome},
 * {@code workspace} — so a key-based lookup would be a list that goes stale
 * silently. Instead every upstream value that is an existing directory is
 * offered to {@code home verify}, and PRODUCTION decides what a home is: exit
 * {@code 2} is {@code NotAHomeException}, i.e. "not a home, skip". No second
 * spelling of {@code looksLikeStoreRoot} lives here.
 *
 * <p>And a run that finds zero homes FAILS. A law that quietly checks nothing
 * is the exact failure mode this epic keeps paying for — an instrument
 * reporting success because it could not look. If this node is wired into a
 * graph, that graph is asserted to produce at least one home.
 */
public final class HomeFixpointLaw {

    static final NodeSpec SPEC = NodeSpec.of("home.fixpoint.law")
            .kind(NodeSpec.Kind.ASSERTION)
            .timeout("900s")
            .output("homesChecked", "string")
            .output("homesRepaired", "string");

    /** Exit code of {@code NotAHomeException} — "that path is not a home". */
    private static final int NOT_A_HOME = 2;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path cli = SmEnv.cli();

            Set<Path> all = candidateHomes(ctx.context());
            Set<Path> candidates = new LinkedHashSet<>();
            List<String> outsideSandbox = new ArrayList<>();
            for (Path c : all) {
                if (insideSandbox(c)) candidates.add(c); else outsideSandbox.add(c.toString());
            }
            List<String> checked = new ArrayList<>();
            List<String> repaired = new ArrayList<>();
            List<String> violations = new ArrayList<>();
            List<String> log = new ArrayList<>();

            // Homes a node damaged ON PURPOSE, by entry (#344). See
            // IntentionalDamage: exact home, exact findings, counted and named,
            // and a declared finding verify does NOT report is a violation.
            List<String> malformed = new ArrayList<>();
            Map<Path, IntentionalDamage.Declaration> damaged =
                    IntentionalDamage.byHome(declarations(ctx.context(), malformed));
            for (String bad : malformed) {
                violations.add("malformed " + IntentionalDamage.KEY + " declaration: " + bad);
            }
            List<String> damagedOnPurpose = new ArrayList<>();

            for (Path candidate : candidates) {
                Run first = verify(cli, candidate);
                if (first.exit == NOT_A_HOME) continue;          // not a home; not our business
                checked.add(candidate.toString());
                IntentionalDamage.Declaration declared = damaged.get(candidate);
                if (declared != null) {
                    String output = first.out + "\n" + first.err;
                    Set<String> reported = IntentionalDamage.unresolvedEntries(output);
                    // OHV-2: verify also names `home repair` findings, and a
                    // planted shape may be reported only there.
                    reported.addAll(IntentionalDamage.repairSubjects(output));
                    Set<String> unseen = new LinkedHashSet<>(declared.entries());
                    unseen.removeAll(reported);
                    if (!unseen.isEmpty()) {
                        violations.add(candidate + ": declared intentionally damaged at " + unseen
                                + " but home verify (exit " + first.exit + ") does not report it — "
                                + "the declaration is stale, or verify is blind to a defect the "
                                + "fixture really planted (the #343 shape)");
                        log.add("FAIL  " + candidate + " — declared damage not reported by verify\n"
                                + first.tail());
                        continue;
                    }
                    List<String> unexplained = IntentionalDamage.unexplained(output, declared.entries());
                    if (unexplained.isEmpty()) {
                        damagedOnPurpose.add(candidate + " " + declared.entries()
                                + " — " + declared.reason());
                        log.add("DAMAGED ON PURPOSE " + candidate + ": verify reports exactly the "
                                + "declared " + declared.entries() + " and nothing else");
                        continue;
                    }
                    // Something beyond the planted damage: judged like any home,
                    // and the re-verify is held to the same "only what was
                    // declared" rule below.
                    log.add("DECLARED " + candidate + " " + declared.entries()
                            + " but verify also refuses on: " + unexplained);
                }
                if (first.exit == 0) {
                    log.add("PASS  " + candidate);
                    continue;
                }

                List<String> remedies = remediesFrom(first.out + "\n" + first.err);
                if (remedies.isEmpty()) {
                    violations.add(candidate + ": verify exit " + first.exit
                            + " and printed no runnable remedy");
                    log.add("FAIL  " + candidate + " — refused with no remedy\n" + first.tail());
                    continue;
                }
                log.add("REFUSED " + candidate + "\n  remedies as printed: " + remedies.size());
                for (String remedy : remedies) log.add("    " + remedy);

                // ALL of them, in order. verify prints one per damage class and
                // no single command clears two classes; running only the first
                // blamed it for not fixing damage it was never printed for.
                Run fix = null;
                StringBuilder fixExits = new StringBuilder();
                for (String remedy : remedies) {
                    fix = shell(cli, remedy, candidate);
                    if (fixExits.length() > 0) fixExits.append(',');
                    fixExits.append(fix.exit);
                }
                Run second = verify(cli, candidate);
                boolean onlyDeclaredRemains = declared != null
                        && IntentionalDamage.unexplained(second.out + "\n" + second.err,
                                declared.entries()).isEmpty();
                if (second.exit == 0 || onlyDeclaredRemains) {
                    repaired.add(candidate.toString());
                    log.add("REPAIRED " + candidate + " (remedy exits " + fixExits + ")");
                } else {
                    violations.add(candidate + ": the " + remedies.size()
                            + " remedy/remedies it printed did not clear it"
                            + " (remedy exits " + fixExits + ", re-verify exit " + second.exit + ")");
                    log.add("FAIL  " + candidate + " — remedy ran and verify still refuses\n"
                            + second.tail());
                }
            }

            // A law that checked nothing is not a law. See the class javadoc.
            if (checked.isEmpty()) {
                violations.add("no Skill Manager home was found in this graph's context — "
                        + "either this node is wired into a graph that produces none, or it "
                        + "runs before the home exists. Both make the law vacuous.");
            }

            NodeResult result = violations.isEmpty()
                    ? NodeResult.pass("home.fixpoint.law")
                    : NodeResult.fail("home.fixpoint.law", String.join("; ", violations));
            return result
                    .assertion("every_home_verifies_or_its_own_remedy_repairs_it",
                            violations.isEmpty())
                    .metric("homesChecked", checked.size())
                    .metric("homesRepaired", repaired.size())
                    .metric("homesOutsideSandbox", outsideSandbox.size())
                    .metric("homesDamagedOnPurpose", damagedOnPurpose.size())
                    .publish("homesChecked", String.join(",", checked))
                    .publish("homesRepaired", String.join(",", repaired))
                    .log(String.join("\n", log)
                            + (outsideSandbox.isEmpty() ? ""
                                    : "\nSKIPPED (outside the sandbox, never mutated): "
                                            + String.join(", ", outsideSandbox))
                            + (damagedOnPurpose.isEmpty() ? ""
                                    : "\nDAMAGED ON PURPOSE (declared by entry via "
                                            + IntentionalDamage.KEY + "; every other finding "
                                            + "judged as usual): "
                                            + String.join("; ", damagedOnPurpose)));
        });
    }

    /** Every {@link IntentionalDamage#KEY} value any upstream node published. */
    private static List<IntentionalDamage.Declaration> declarations(
            List<ContextItem> context, List<String> malformed) {
        List<IntentionalDamage.Declaration> out = new ArrayList<>();
        for (ContextItem item : context) {
            String value = item.data().get(IntentionalDamage.KEY);
            if (value != null) out.addAll(IntentionalDamage.parse(value, malformed));
        }
        return out;
    }

    // ------------------------------------------------------------ discovery

    /**
     * Every distinct existing directory any upstream node published, plus each
     * one's {@code .skill-manager} child — nodes publish the home ROOT about as
     * often as they publish the store.
     */
    private static Set<Path> candidateHomes(List<ContextItem> context) {
        Set<Path> out = new LinkedHashSet<>();
        for (ContextItem item : context) {
            for (String value : item.data().values()) {
                if (value == null || value.isBlank()) continue;
                // Values are frequently comma-joined lists of paths.
                for (String part : value.split("[,\\n]")) {
                    String trimmed = part.trim();
                    if (trimmed.length() < 2 || !trimmed.startsWith("/")) continue;
                    add(out, Path.of(trimmed));
                    add(out, Path.of(trimmed).resolve(".skill-manager"));
                }
            }
        }
        return out;
    }

    /**
     * Whether a discovered home is one this graph OWNS.
     *
     * <p>Measured, and it is why this exists: on the onboarding graph the scan
     * found {@code …/skill-manager-integration-repository/constituents/
     * git-integration-repo/.skill-manager} — one of the OPERATOR'S REAL HOMES,
     * published by a node that names a real checkout. It only got verified,
     * which is read-only, so nothing was harmed. But had verify refused, this
     * node would have run {@code sync --force-scripts} against a home no test
     * created, which is precisely the global-home hijack (#145) that half this
     * epic is about, reintroduced by the check written to prevent it.
     *
     * <p>So the law acts only on homes under the JVM's temp root, where every
     * graph builds its fixtures. Anything else is skipped, COUNTED and NAMED —
     * silently ignoring it would trade one blind spot for another.
     */
    private static boolean insideSandbox(Path home) {
        try {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            return home.startsWith(tmp);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void add(Set<Path> out, Path p) {
        try {
            if (Files.isDirectory(p)) out.add(p.toRealPath());
        } catch (IOException | RuntimeException ignored) {
            // unreadable or malformed: not a home we can check
        }
    }

    // --------------------------------------------------------------- remedy

    /**
     * The remedy exactly as printed, or null.
     *
     * <p>{@code home verify} prints {@code "  complete it with: <cmd>, then
     * re-run this check"} (with a {@code ✗} prefix). Only the span between the
     * marker and the trailing clause is taken; nothing is added.
     */
    static String remedyFrom(String output) {
        List<String> all = remediesFrom(output);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * EVERY remedy the output printed, in order, without duplicates.
     *
     * <h2>Why one was not enough, and why taking one was a wrong answer</h2>
     *
     * <p>{@code home verify} reports independent classes of damage and prints a
     * remedy for each. A cloned home hits two at once: an unresolved toolchain
     * reference, whose remedy is {@code build --stale}, and frozen shims plus a
     * marketplace registered under the source home's identity, whose remedy is
     * {@code home repair --fix}. There is no single command that clears both,
     * and neither is a superset of the other.
     *
     * <p>Taking the FIRST match therefore tested something weaker than the law
     * states. `home-clone` had been red on exactly this: the law ran
     * {@code build --stale}, the three MARKETPLACE_REGISTERED_UNDER_ANOTHER_NAME
     * findings were untouched because nothing had addressed them, and the law
     * reported "the remedy it printed did not clear it" — blaming the remedy for
     * not fixing damage it was never printed for. Measured: running both, in
     * order, leaves only the damage the fixture DECLARES.
     *
     * <p>So the law now runs all of them. That is the honest reading of "or its
     * own remedy repairs it": the home's own output is the instruction set, and
     * a home that prints two instructions has not been given a fair run until
     * both have been followed.
     */
    static List<String> remediesFrom(String output) {
        List<String> found = new ArrayList<>();
        for (String raw : output.split("\n")) {
            int at = raw.indexOf("complete it with: ");
            if (at < 0) continue;
            String rest = raw.substring(at + "complete it with: ".length()).trim();
            int tail = rest.indexOf(", then re-run this check");
            if (tail >= 0) rest = rest.substring(0, tail);
            rest = rest.trim();
            if (!rest.isEmpty() && !found.contains(rest)) found.add(rest);
        }
        return found;
    }

    // -------------------------------------------------------------- process

    private record Run(int exit, String out, String err) {
        String tail() {
            String all = (out + err).strip();
            int from = Math.max(0, all.length() - 1500);
            return all.substring(from);
        }
    }

    private static Run verify(Path cli, Path home) {
        return exec(List.of(cli.toString(), "home", "verify", "--home", home.toString()), cli, home);
    }

    private static Run shell(Path cli, String command, Path home) {
        return exec(List.of("/bin/sh", "-c", command), cli, home);
    }

    private static Run exec(List<String> command, Path cli, Path home) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            // Sandboxed through SmEnv, like every other node that spawns the
            // CLI — `sandbox.env.contract` is the tripwire that says so, and it
            // caught this node writing the managed variables itself. It is the
            // right rule: a node that shells skill-manager without the four
            // agent roots pinned resolves them against the ambient environment,
            // which is the operator's real ~/.claude. The remedy string carries
            // its own `env ...` prefix and overrides these, which is the point
            // of homeEnvPrefix; this is the floor underneath it.
            SmEnv.apply(pb, home.toString(), SmEnv.sandboxUnder(home.resolve("agent-home")));
            // Pin WHICH BUILD the law is about. Not a managed variable, so it
            // stays here. Without it, HomeDescriptor.resolveCli falls through to
            // a PATH walk, and on a developer machine that finds an older
            // released skill-manager — so the printed remedy would name a
            // different program than the one under test and no-op. That is a
            // real defect (reported separately); letting it decide the outcome
            // would make this a test of the host's PATH.
            pb.environment().put("SKILL_MANAGER_CLI", cli.toString());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            String err = new String(p.getErrorStream().readAllBytes());
            if (!p.waitFor(600, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Run(-1, out, err + "\n[timed out]");
            }
            return new Run(p.exitValue(), out, err);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Run(-1, "", String.valueOf(e.getMessage()));
        }
    }

    private HomeFixpointLaw() {}
}
