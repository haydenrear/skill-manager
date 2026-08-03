///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java

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
 * {@code env SKILL_MANAGER_HOME=… skill-manager sync --force-scripts} from
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

            Set<Path> candidates = candidateHomes(ctx.context());
            List<String> checked = new ArrayList<>();
            List<String> repaired = new ArrayList<>();
            List<String> violations = new ArrayList<>();
            List<String> log = new ArrayList<>();

            for (Path candidate : candidates) {
                Run first = verify(cli, candidate);
                if (first.exit == NOT_A_HOME) continue;          // not a home; not our business
                checked.add(candidate.toString());
                if (first.exit == 0) {
                    log.add("PASS  " + candidate);
                    continue;
                }

                String remedy = remedyFrom(first.out + "\n" + first.err);
                if (remedy == null) {
                    violations.add(candidate + ": verify exit " + first.exit
                            + " and printed no runnable remedy");
                    log.add("FAIL  " + candidate + " — refused with no remedy\n" + first.tail());
                    continue;
                }
                log.add("REFUSED " + candidate + "\n  remedy as printed: " + remedy);

                Run fix = shell(cli, remedy);
                Run second = verify(cli, candidate);
                if (second.exit == 0) {
                    repaired.add(candidate.toString());
                    log.add("REPAIRED " + candidate + " (remedy exit " + fix.exit + ")");
                } else {
                    violations.add(candidate + ": the remedy it printed did not clear it"
                            + " (remedy exit " + fix.exit + ", re-verify exit " + second.exit + ")");
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
                    .publish("homesChecked", String.join(",", checked))
                    .publish("homesRepaired", String.join(",", repaired))
                    .log(String.join("\n", log));
        });
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
        for (String raw : output.split("\n")) {
            int at = raw.indexOf("complete it with: ");
            if (at < 0) continue;
            String rest = raw.substring(at + "complete it with: ".length()).trim();
            int tail = rest.indexOf(", then re-run this check");
            if (tail >= 0) rest = rest.substring(0, tail);
            rest = rest.trim();
            if (!rest.isEmpty()) return rest;
        }
        return null;
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
        return exec(List.of(cli.toString(), "home", "verify", "--home", home.toString()), cli);
    }

    private static Run shell(Path cli, String command) {
        return exec(List.of("/bin/sh", "-c", command), cli);
    }

    private static Run exec(List<String> command, Path cli) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            Map<String, String> env = pb.environment();
            // Pin WHICH BUILD the law is about. Without this,
            // HomeDescriptor.resolveCli falls through to a PATH walk, and on a
            // developer machine that finds an older released skill-manager —
            // so the printed remedy would name a different program than the one
            // under test and no-op. That is a real defect (filed separately);
            // it is not the property this node measures, and letting it decide
            // the outcome would make the law a test of the host's PATH.
            env.put("SKILL_MANAGER_CLI", cli.toString());
            env.put(SmEnv.SKILL_MANAGER_INSTALL_DIR, SmEnv.repoRoot().toString());
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
