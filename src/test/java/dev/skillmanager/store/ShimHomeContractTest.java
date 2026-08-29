package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.artifacts.ColdArtifactShim;
import dev.skillmanager.launch.LauncherShims;
import dev.skillmanager.shared.util.Fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@link ShimHomeContract} against every shim generator this repository ships.
 *
 * <h2>What this asserts, and what it deliberately does not</h2>
 *
 * <p>It asserts on the BYTES each generator writes. It never runs a shim and
 * never compares output, because in the measured defect the wrong copy
 * produces the right answer: the project home holds its own
 * {@code tla_spec_dev.py} (38288 B), its shim execs the ROOT home's copy, and
 * the two files are byte-identical. Every behavioural assertion passes over
 * that. A behavioural test here would be the exact mistake this epic exists to
 * fix, mechanised into CI.
 *
 * <h2>How bytes can answer a question about resolution: relocation</h2>
 *
 * <p>Two homes are built. Every generator is run against the FIRST one, and
 * the bytes it produced are then placed in the SECOND, which holds its own
 * copy of the same unit. That is the whole discriminator, and it needs no
 * execution:
 *
 * <ul>
 *   <li>A conformant generator writes a body that derives its home from the
 *       shim's own location ({@code ${BASH_SOURCE[0]}} → {@code $home}), so
 *       there is no absolute home path in it at all and moving it changes what
 *       it names.</li>
 *   <li>A frozen generator resolved the path once, at install time, against
 *       whichever home happened to be installing, and baked the answer in. Its
 *       bytes still name the FIRST home after the move — with a local copy
 *       sitting unused beside them, which is the defect exactly.</li>
 * </ul>
 *
 * <h2>One case, every generator</h2>
 *
 * <p>Deliberately not one case per generator. The metric is per-pair and one
 * bad pair is the whole defect, so the failure has to list every offender at
 * once rather than stop at the first: an agent fixing this needs the whole set,
 * and a suite that reports "1 of 6 failed" hides that the other five are the
 * ones proving the rule is satisfiable.
 *
 * <h2>Not covered, on purpose</h2>
 *
 * <p>{@code skill-dev-skill/skill-scripts/install.sh} delegates to
 * {@code uv tool install}, so the wrapper's bytes are written by uv and
 * reproducing them needs uv and a network. It is a real fourth generator and
 * it is recorded in the epic's deferred backlog rather than pretended about
 * here.
 */
public final class ShimHomeContractTest {

    /** Enough of a PATH for {@code /usr/bin/env bash} and coreutils. */
    private static final String SYSTEM_PATH = "/usr/bin" + File.pathSeparator + "/bin";

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ShimHomeContractTest");

        suite.test("every shim generator in this tree resolves its unit from the home the "
                + "shim lives in", () -> {
            Path repo = repoRoot();
            Path originRoot = Files.createTempDirectory("hbr0-origin-");
            Path movedRoot = Files.createTempDirectory("hbr0-moved-");
            Path origin = home(originRoot);
            Path moved = home(movedRoot);

            // A build outside every home. `bin/cli/skill-manager` pins the
            // build that wrote it and that pin is sanctioned by the rule's
            // second clause — a pin is not another home's copy of a unit.
            Path pin = originRoot.resolve("build/bin/skill-manager");
            Fs.ensureDir(pin.getParent());
            Files.writeString(pin, "#!/bin/sh\nexit 0\n");
            pin.toFile().setExecutable(true);

            // Stands in for the interpreter an installer probes for. Outside
            // every home, like the real one.
            Path fakePython = originRoot.resolve("fake-python3");
            Files.writeString(fakePython, "#!/bin/sh\nexit 0\n");
            fakePython.toFile().setExecutable(true);

            List<Generator> generators = List.of(
                    new Generator("LauncherShims.script (bin/launch template)", null,
                            () -> write(origin.resolve("bin/launch/claude"),
                                    LauncherShims.script("claude"))),
                    new Generator("LauncherShims.cliScript (the CLI pin)", null,
                            () -> write(origin.resolve("bin/cli/skill-manager"),
                                    LauncherShims.cliScript(pin))),
                    new Generator("ColdArtifactShim.write", null,
                            () -> ColdArtifactShim.write(origin.resolve("bin/cli/cold-tool"),
                                    "hayden/cold-tool",
                                    "its unit declares an artifact that is not built")),
                    new Generator("skill-publisher-skill/skill-scripts/install-skt.sh", "skt",
                            () -> {
                                Path src = origin.resolve("skills/skt/src/skt/cli.py");
                                Fs.ensureDir(src.getParent());
                                Files.writeString(src, "# skt entrypoint\n");
                                runInstaller(repo.resolve(
                                                "skill-publisher-skill/skill-scripts/install-skt.sh"),
                                        origin, "skt",
                                        Map.of("SKT_PYTHON", fakePython.toString()));
                            }),
                    new Generator("test_graph/fixtures/skill-script-skill/skill-scripts/install.sh",
                            "skill-script-skill",
                            () -> runInstaller(repo.resolve("test_graph/fixtures/"
                                            + "skill-script-skill/skill-scripts/install.sh"),
                                    origin, "skill-script-skill", Map.of())),
                    new Generator("test_graph/fixtures/skill-script-umbrella-skill/inner/"
                            + "skill-scripts/install.sh", "skill-script-umbrella-skill",
                            () -> runInstaller(repo.resolve("test_graph/fixtures/"
                                            + "skill-script-umbrella-skill/inner/skill-scripts/"
                                            + "install.sh"),
                                    origin, "skill-script-umbrella-skill", Map.of())));

            List<ShimHomeContract.Violation> violations = new ArrayList<>();
            Map<String, List<String>> shimsSeen = new LinkedHashMap<>();

            for (Generator gen : generators) {
                Set<String> before = entriesUnderBin(origin);
                gen.emit().run();
                Set<String> produced = new LinkedHashSet<>(entriesUnderBin(origin));
                produced.removeAll(before);

                // THE HOME THE SHIM MOVES INTO HAS ITS OWN COPY OF THE UNIT.
                // Without this the crossing would be the rule's sanctioned
                // fallback and the test would be asserting nothing.
                if (gen.unit() != null) {
                    copyTree(origin.resolve("skills").resolve(gen.unit()),
                            moved.resolve("skills").resolve(gen.unit()));
                }
                for (String rel : produced) {
                    Path there = moved.resolve(rel.replace('/', File.separatorChar));
                    Fs.ensureDir(there.getParent());
                    Files.copy(origin.resolve(rel.replace('/', File.separatorChar)), there,
                            StandardCopyOption.REPLACE_EXISTING);
                    violations.addAll(ShimHomeContract.check(gen.name(), moved, there));
                }
                shimsSeen.put(gen.name(), List.copyOf(produced));
            }

            Tests.assertFalse(shimsSeen.values().stream().allMatch(List::isEmpty),
                    "the sweep produced shims at all — an empty sweep would pass vacuously");

            // EMPTY, AS OF HBR-1, and the emptiness is the ticket's whole
            // signal. HBR-0 landed two pinned violations here — install-skt.sh
            // freezing `$SKILL_DIR` into bin/cli/skt, and the skill-script
            // graph fixture freezing `$SKILL_MANAGER_CACHE_DIR` into
            // bin/cli/skill-script-touched — and HBR-1 fixed both installers
            // and removed both pins in the same change, because the guard
            // below fails in BOTH directions and a stale pin is as loud as a
            // new offender:
            //   - a violation NOT in this set fails -- a new offender, or a
            //     known one that changed shape, is caught immediately;
            //   - a pinned entry that STOPS firing also fails -- so a
            //     generator cannot be fixed while leaving behind a pin that
            //     claims a violation which no longer exists.
            //
            // Keep it empty. An entry added here is a generator this
            // repository ships that sends a home off to run another home's
            // copy, and it is charged to GOAL-a-home-runs-its-own-copy.
            Set<String> pinned = new TreeSet<>(Set.of());

            Set<String> seen = new TreeSet<>();
            List<ShimHomeContract.Violation> unexpected = new ArrayList<>();
            for (ShimHomeContract.Violation v : violations) {
                String key = v.generator() + "|" + v.shimRel() + "|" + v.kind();
                seen.add(key);
                if (!pinned.contains(key)) unexpected.add(v);
            }
            Set<String> stale = new TreeSet<>(pinned);
            stale.removeAll(seen);

            if (!unexpected.isEmpty()) {
                throw new AssertionError("a shim generator violates the contract"
                        + (pinned.isEmpty()
                                ? " (the pinned set is empty — every generator in this tree "
                                        + "satisfied the rule before this change)"
                                : " and is NOT one of the " + pinned.size() + " pinned")
                        + ":\n\n" + report(unexpected, shimsSeen));
            }
            if (!stale.isEmpty()) {
                throw new AssertionError("these violations are pinned but no longer fire, so "
                        + "the pin is now claiming something untrue — remove them from `pinned` "
                        + "in this test as part of the change that fixed them:\n  "
                        + String.join("\n  ", stale));
            }
        });

        suite.test("the freeze is visible BEFORE the copy, which is where an author can "
                + "still act on it", () -> {
            // HBR-1. `check` needs two homes and a relocation to see anything;
            // `frozenHomePaths` reads the same defect off one home at the
            // moment the bytes are written, which is what `SkillScriptBackend`
            // warns from. Both directions, because a detector that never says
            // "clean" and one that never says "frozen" are equally useless.
            Path root = Files.createTempDirectory("hbr1-frozen-");
            Path home = home(root);
            Fs.ensureDir(home.resolve("bin/cli"));

            Path frozen = home.resolve("bin/cli/frozen");
            Files.writeString(frozen, "#!/bin/sh\nexec \""
                    + home.resolve("cache/tool/venv/bin/tool") + "\" \"$@\"\n");
            Tests.assertEquals(List.of("cache/tool/venv/bin/tool"),
                    ShimHomeContract.frozenHomePaths(home, frozen),
                    "a wrapper naming its own home absolutely has frozen that path");

            Path conformant = home.resolve("bin/cli/conformant");
            Files.writeString(conformant, """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    rel="cache/tool/venv/bin/tool"
                    shim_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
                    home="$(cd -- "$shim_dir/../.." && pwd -P)"
                    exec "$home/$rel" "$@"
                    """);
            Tests.assertTrue(ShimHomeContract.frozenHomePaths(home, conformant).isEmpty(),
                    "the home-derived spelling of the same shim has frozen nothing");

            // The narrowness that keeps this from firing on shims that are
            // already right: a path OUTSIDE every home does not move when the
            // shim does either, so pinning one is not the defect.
            Path interpreter = home.resolve("bin/cli/interpreter");
            Files.writeString(interpreter, "#!/bin/sh\nexec /opt/homebrew/bin/python3.13 \"$@\"\n");
            Tests.assertTrue(ShimHomeContract.frozenHomePaths(home, interpreter).isEmpty(),
                    "an absolute path outside every home is a pin, not a freeze");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- reporting

    /**
     * The failure, naming every offender.
     *
     * <p>Each violation carries the GENERATOR, not just the shim: the shim is
     * a symptom and the generator is the thing a fix has to change. The
     * conformant generators are listed too — a rule no writer in the tree
     * satisfies reads as an unreasonable rule, and three of these satisfy it
     * today.
     */
    private static String report(List<ShimHomeContract.Violation> violations,
                                 Map<String, List<String>> shimsSeen) {
        List<ShimHomeContract.Violation> sorted = new ArrayList<>(violations);
        sorted.sort(Comparator.comparing(ShimHomeContract.Violation::generator)
                .thenComparing(ShimHomeContract.Violation::shimRel)
                .thenComparing(v -> v.names().toString()));
        Set<String> offenders = new LinkedHashSet<>();
        sorted.forEach(v -> offenders.add(v.generator()));
        long charged = sorted.stream().filter(ShimHomeContract.Violation::charged).count();

        StringBuilder sb = new StringBuilder();
        sb.append(ShimHomeContract.RULE).append("\n\n");
        sb.append(offenders.size()).append(" of ").append(shimsSeen.size())
                .append(" shim generators in this tree break it, in ")
                .append(sorted.size()).append(" place(s) (")
                .append(charged).append(" charged to GOAL-a-home-runs-its-own-copy):\n\n");
        for (ShimHomeContract.Violation v : sorted) {
            sb.append("  - ").append(v).append("\n");
        }
        sb.append("\nconformant generators:\n");
        shimsSeen.forEach((name, shims) -> {
            if (offenders.contains(name)) return;
            sb.append("  - ").append(name).append(" -> ").append(shims).append("\n");
        });
        sb.append("\nEach offender resolved the path once, at install time, against the "
                + "home that happened\nto be installing, and froze the answer. Relocating "
                + "the shim does not move it, so the\nhome it lands in runs the other "
                + "home's copy while holding its own.\n");
        return sb.toString();
    }

    // -------------------------------------------------------------- fixtures

    /** A generator, what it writes, and the unit whose copy it names. */
    private record Generator(String name, String unit, Tests.Body emit) {}

    private static Path home(Path root) throws Exception {
        SkillStore store = new SkillStore(root.resolve(".skill-manager"));
        store.init();
        return store.root();
    }

    /**
     * Run one {@code skill-scripts/} installer the way {@code
     * SkillScriptBackend} does: the documented env vars, and nothing else
     * inherited.
     *
     * <p>The environment is CLEARED rather than extended. This process runs
     * inside a real Skill Manager home, and an inherited
     * {@code SKILL_MANAGER_HOME} would let an installer write outside the
     * temporary homes — the failure this epic is about, committed by the test
     * for it.
     */
    private static void runInstaller(Path script, Path home, String unit,
                                     Map<String, String> extra) throws Exception {
        Path skillDir = home.resolve("skills").resolve(unit);
        Fs.ensureDir(skillDir);
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", script.toString())
                .redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("PATH", SYSTEM_PATH);
        env.put("HOME", home.getParent().toString());
        env.put("SKILL_MANAGER_HOME", home.toString());
        env.put("SKILL_MANAGER_BIN_DIR", home.resolve("bin/cli").toString());
        env.put("SKILL_MANAGER_CACHE_DIR", home.resolve("cache").toString());
        env.put("SKILL_DIR", skillDir.toString());
        env.put("SKILL_SCRIPTS_DIR", script.getParent().toString());
        env.put("SKILL_NAME", unit);
        env.putAll(extra);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IllegalStateException(
                    "installer " + script + " exited " + rc + ":\n" + out);
        }
    }

    private static Path write(Path file, String body) throws IOException {
        Fs.ensureDir(file.getParent());
        Files.writeString(file, body);
        file.toFile().setExecutable(true);
        return file;
    }

    /** Home-relative, {@code /}-separated names of every regular file under {@code bin/}. */
    private static Set<String> entriesUnderBin(Path home) throws IOException {
        Path bin = home.resolve("bin");
        if (!Files.isDirectory(bin)) return Set.of();
        try (Stream<Path> walk = Files.walk(bin)) {
            Set<String> out = new LinkedHashSet<>();
            walk.filter(Files::isRegularFile)
                    .map(p -> home.relativize(p).toString().replace(File.separatorChar, '/'))
                    .sorted()
                    .forEach(out::add);
            return out;
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.exists(from)) return;
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Fs.ensureDir(target);
                else {
                    Fs.ensureDir(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * The repository this test source lives in.
     *
     * <p>Found by walking up from the working directory for the marker files
     * the generators under test are addressed by, so the sweep reads THIS
     * tree's installers rather than an installed home's copy of them — which
     * is the whole point of regression-testing them here.
     */
    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath().normalize();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("RunTests.java"))
                    && Files.isDirectory(p.resolve("skill-publisher-skill/skill-scripts"))) {
                return p;
            }
        }
        throw new IllegalStateException(
                "cannot find the repository root from " + here
                        + " — this test reads the installers in THIS tree");
    }
}
