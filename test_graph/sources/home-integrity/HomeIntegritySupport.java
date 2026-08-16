// Declared here as well as on every node that includes this file, because
// `sandbox.env.contract` walks each file's OWN //SOURCES closure: this class
// resolves the CLI (SmEnv.cli()), so it has to reach the helper on its own
// terms or it is reported unrouted. HomeCloneSupport carries the same line for
// the same reason.
//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared machinery for the {@code home-integrity} graph: spawning the CLI,
 * taking a working copy of a home to damage, and reporting a check the same way
 * in every node.
 *
 * <h2>The shape every node in this graph has</h2>
 *
 * <p>Each node owns one invariant and makes the same three claims about it:
 *
 * <ol>
 *   <li>the invariant <b>holds, non-vacuously</b>, on a home this run freshly
 *       provisioned with the real CLI;</li>
 *   <li>a <b>deliberately planted</b> instance of the defect that motivated the
 *       invariant is <b>caught</b>;</li>
 *   <li><b>repairing</b> that damage makes the check pass again.</li>
 * </ol>
 *
 * <p>Claim 2 is the regression assertion — it is what stops a check from being
 * a check that cannot fail. Claim 3 is what stops it from being a check that
 * cannot pass: a detector wired to "always report something" satisfies 2 and is
 * worthless, and this epic has already found two oracles that passed vacuously
 * ({@code child_home_holds_only_claimed_or_held_back_units}) and one that
 * reported a comfortable number in only the flattering direction
 * ({@code nodes_reached}). Control, mutant, repair — all three, in the same
 * run, or the node has not established anything.
 *
 * <p>{@link #probe} is the harness for 2 and 3: it takes a fresh working copy
 * of the fixture home per mutation, so a mutation cannot leak into the next
 * node's subject and a failure cannot be blamed on ordering.
 */
final class HomeIntegritySupport {

    private HomeIntegritySupport() {}

    /** The fixture unit installed into the freshly provisioned home. */
    static final String UNIT = "hi-unit";

    /** A second fixture unit, so "one bad row" is distinguishable from "all rows". */
    static final String UNIT_B = "hi-unit-b";

    /** The CLI dependency the fixture declares, and the shim it produces. */
    static final String TOOL = "hi-tool";

    // --------------------------------------------------------------- the CLI

    /**
     * Run the repo's skill-manager against {@code home}, fully sandboxed.
     *
     * <p>Environment goes through {@link SmEnv} and nowhere else — see
     * {@code sources/sandbox/SandboxEnvContract.java}, which fails the build if
     * any file but {@code SmEnv.java} writes {@code SKILL_MANAGER_HOME} into a
     * child process.
     */
    static ProcessRecord sm(NodeContext ctx, String label, Path home, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add(SmEnv.cli().toString());
        argv.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(argv);
        SmEnv.apply(ctx, pb, home);
        return Procs.run(ctx, label, pb);
    }

    // ------------------------------------------------------------- fixtures

    /**
     * A minimal but genuinely installable skill, in its own git repository.
     *
     * <p>The git repository is the point. Three of this graph's invariants —
     * {@code RecordAgreesWithStore}, {@code UpstreamTracksWhatSyncFetched} and
     * every damaged fixture derived from them — are statements relating an
     * {@code installed/<u>.json} record to a real checkout, and a unit installed
     * from a plain directory has no checkout to relate it to. A local bare
     * remote also means the whole graph is network-free and can run on a hosted
     * runner, which #113 established is not a given here.
     */
    static Path scaffoldGitUnit(Path root, String name, boolean withCliDep) throws IOException {
        Path work = root.resolve(name);
        Files.createDirectories(work);
        Files.writeString(work.resolve("SKILL.md"), """
                ---
                name: %s
                description: home-integrity graph fixture
                ---

                A fixture unit for the home-integrity graph.
                """.formatted(name));
        StringBuilder manifest = new StringBuilder("""
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "home-integrity graph fixture"
                """.formatted(name));
        if (withCliDep) {
            // A skill-script dependency, because it is the one backend that
            // installs INTO the home unconditionally. brew/npm/pip would find
            // the tool on the machine's PATH or try to reach the network, and
            // either way the fixture would stop being hermetic.
            manifest.append("""

                    [[cli_dependencies]]
                    spec = "skill-script:%s"
                    on_path = "%s"

                    [cli_dependencies.install.any]
                    script = "install-%s.sh"
                    binary = "%s"
                    """.formatted(TOOL, TOOL, TOOL, TOOL));
            // Under skill-scripts/, not at the unit root. The resolver looks
            // only there, and a script at the root fails the install with
            // "skill-script not found" while the install itself exits 0.
            Path installer = work.resolve("skill-scripts").resolve("install-" + TOOL + ".sh");
            Files.createDirectories(installer.getParent());
            Files.writeString(installer, """
                    #!/usr/bin/env bash
                    # home-integrity fixture installer: writes the binary the
                    # manifest says it writes, and nothing else.
                    set -euo pipefail
                    out="${SKILL_MANAGER_HOME:?}/bin/cli/%s"
                    mkdir -p "$(dirname "$out")"
                    cat > "$out" <<'EOF'
                    #!/usr/bin/env bash
                    echo "%s ok"
                    EOF
                    chmod +x "$out"
                    """.formatted(TOOL, TOOL));
            installer.toFile().setExecutable(true);
        }
        Files.writeString(work.resolve("skill-manager.toml"), manifest.toString());

        run(work, "git", "init", "--initial-branch=main");
        run(work, "git", "config", "user.email", "home-integrity@test.invalid");
        run(work, "git", "config", "user.name", "home-integrity");
        run(work, "git", "add", "-A");
        run(work, "git", "commit", "-m", "fixture " + name);

        // A bare sibling as origin, so the store has a real remote and a real
        // tracking ref to be right or wrong about.
        Path bare = root.resolve(name + ".git");
        run(root, "git", "clone", "--bare", work.toString(), bare.toString());
        run(work, "git", "remote", "add", "origin", bare.toString());
        run(work, "git", "fetch", "origin");
        run(work, "git", "branch", "--set-upstream-to=origin/main", "main");
        return work;
    }

    static void run(Path cwd, String... argv) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            int rc = pb.start().waitFor();
            if (rc != 0) throw new IOException("exit " + rc + ": " + String.join(" ", argv));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    // ------------------------------------------------------- damage harness

    /** What one mutation established. */
    record Mutation(String name, boolean caught, boolean repaired, String evidence) {}

    /** A mutation to apply to a working copy of the fixture home, and its undo. */
    interface Damage {
        void plant(Path home) throws IOException;

        void repair(Path home) throws IOException;
    }

    /**
     * Take a fresh working copy of {@code source}, plant {@code damage}, run
     * {@code check}, repair, and run it again.
     *
     * <p>A fresh copy per mutation rather than plant-and-undo in place: an undo
     * that is subtly incomplete turns the next mutation's result into a lie, and
     * "the repair worked" is one of the two things being measured, so it cannot
     * also be the mechanism the measurement depends on.
     */
    static Mutation probe(Path source, Path scratchRoot, String name, Damage damage,
                          java.util.function.Function<Path, HomeIntegrity.Report> check)
            throws IOException {
        Path copy = scratchRoot.resolve(name);
        deleteRecursively(copy);
        copyTree(source, copy);

        HomeIntegrity.Report before = check.apply(copy);
        damage.plant(copy);
        HomeIntegrity.Report damaged = check.apply(copy);
        damage.repair(copy);
        HomeIntegrity.Report after = check.apply(copy);

        boolean caught = before.holds() && !damaged.holds();
        boolean repaired = after.holds();
        String evidence = "control=" + before.findings().size()
                + " damaged=" + damaged.findings().size()
                + " repaired=" + after.findings().size()
                + (damaged.findings().isEmpty() ? "" : " | " + damaged.findings().get(0));
        return new Mutation(name, caught, repaired, evidence);
    }

    /**
     * As {@link #probe}, for an invariant that does <em>not</em> hold on the
     * fixture home — the control is expected to report findings, so "caught"
     * means the damage added at least one more.
     */
    static Mutation probeOverKnownDefect(Path source, Path scratchRoot, String name, Damage damage,
                                         java.util.function.Function<Path,
                                                 HomeIntegrity.Report> check)
            throws IOException {
        Path copy = scratchRoot.resolve(name);
        deleteRecursively(copy);
        copyTree(source, copy);

        HomeIntegrity.Report before = check.apply(copy);
        damage.plant(copy);
        HomeIntegrity.Report damaged = check.apply(copy);
        damage.repair(copy);
        HomeIntegrity.Report after = check.apply(copy);

        boolean caught = damaged.findings().size() > before.findings().size();
        boolean repaired = after.findings().size() == before.findings().size();
        String evidence = "control=" + before.findings().size()
                + " damaged=" + damaged.findings().size()
                + " repaired=" + after.findings().size();
        return new Mutation(name, caught, repaired, evidence);
    }

    // ------------------------------------------------------------- reporting

    /**
     * Attach a check's outcome to a result as one assertion plus its evidence.
     *
     * <p>The description goes into the node log unconditionally, pass or fail.
     * #113 found two failures in this suite that "cannot explain themselves" —
     * an assertion reporting two opaque hashes and never naming the path that
     * changed — and named that as half of GOAL-validation-floor missing. A check
     * that knows exactly which unit violated an invariant should say so whether
     * or not anyone is currently asking.
     */
    static NodeResult withReport(NodeResult result, HomeIntegrity.Report report) {
        return result.log(report.describe())
                .metric(report.invariant() + ".examined", report.examined())
                .metric(report.invariant() + ".violations", report.findings().size());
    }

    static NodeResult withMutation(NodeResult result, Mutation m) {
        return result
                .assertion("the_planted_" + m.name() + "_is_caught", m.caught())
                .assertion("repairing_the_planted_" + m.name() + "_clears_it", m.repaired())
                .log(m.name() + ": " + m.evidence());
    }

    // ------------------------------------------------------------------ fs

    /**
     * Copy a home, links as links.
     *
     * <p>Following symlinks here would rewrite the very thing three of these
     * invariants are about — {@code bin/cli} shims and agent projections are
     * links, and a copy that resolved them would silently repair the defect it
     * was made to carry.
     */
    static void copyTree(Path source, Path dest) throws IOException {
        Files.createDirectories(dest);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(dest.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path target = dest.resolve(source.relativize(file).toString());
                Files.createDirectories(target.getParent());
                if (Files.isSymbolicLink(file)) {
                    Files.createSymbolicLink(target, Files.readSymbolicLink(file));
                } else {
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
