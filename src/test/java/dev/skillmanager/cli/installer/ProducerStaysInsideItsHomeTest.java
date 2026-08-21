package dev.skillmanager.cli.installer;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.store.WriteOutsideHomeException;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The producer boundary: a producer that would write outside the home is
 * refused, and one that legitimately shares a parent's toolchain is not.
 *
 * <h2>The measurement this stands for</h2>
 *
 * <p>HIS-7, twice, on the operator's real root home, the second time while
 * measuring the first. A producer in a cloned home ran
 * {@code cat > "$SKILL_MANAGER_BIN_DIR/computeq"}, that slot was an inherited
 * symlink into {@code ~/.skill-manager/bin/cli}, the redirection FOLLOWED it,
 * and the root home's shim was rewritten with the clone's scratchpad paths. It
 * had to be repaired by hand. {@code tlc2} survived only because its shim body
 * happens to be {@code BIN_DIR}-relative.
 *
 * <h2>Why the producer here is a stub and not a real skill-script</h2>
 *
 * <p>Because the defect is not in any particular producer — it is in what the
 * boundary hands one. The stub does the single thing every real producer does
 * and the single thing that matters: {@code Files.writeString} to
 * {@code $SKILL_MANAGER_BIN_DIR/<name>}, which follows a symlink exactly the way
 * a shell redirection does. Running a real script would add a fork, a
 * fingerprint and a platform key to a case about none of those, and would make
 * the test's failure modes wider than the thing under test.
 *
 * <p>It also lets each case assert something an end-to-end run cannot: whether
 * the producer <b>ran at all</b>. "Refused before a byte moved" and "refused
 * after the damage" are different outcomes, and this file distinguishes them.
 */
public final class ProducerStaysInsideItsHomeTest {

    public static int run() throws Exception {
        return Tests.suite("ProducerStaysInsideItsHomeTest")

                .test("a producer aimed at an UNSANCTIONED link into another home is refused, "
                        + "before it runs", () -> {
                    Fixture fx = Fixture.build("unsanctioned");
                    // No claim, no descent record: these two homes are strangers.
                    Path slot = fx.home.resolve("bin/cli/tool");
                    Files.createSymbolicLink(slot, fx.other.resolve("bin/cli/tool"));
                    String before = Files.readString(fx.other.resolve("bin/cli/tool"));

                    String refusal = refused(() ->
                            fx.registry.installOne(dep(), new SkillStore(fx.home), "demo"));

                    assertTrue(!fx.producer.ran, "the producer never ran — no byte moved");
                    assertEquals(before, Files.readString(fx.other.resolve("bin/cli/tool")),
                            "the other home's artifact is byte-identical to what it was");
                    assertTrue(refusal.contains(slot.toString()),
                            "the refusal names the path handed to the producer; got:\n" + refusal);
                    assertTrue(refusal.contains(fx.home.toString())
                                    || refusal.contains(real(fx.home).toString()),
                            "and the home it was given; got:\n" + refusal);
                })

                .test("a producer aimed at a home whose bin/cli IS a link out is refused", () -> {
                    Fixture fx = Fixture.build("bin-cli-link");
                    Path binCli = fx.home.resolve("bin/cli");
                    dev.skillmanager.shared.util.Fs.deleteRecursive(binCli);
                    Files.createSymbolicLink(binCli, fx.other.resolve("bin/cli"));
                    String before = Files.readString(fx.other.resolve("bin/cli/tool"));

                    String refusal = refused(() ->
                            fx.registry.installOne(dep(), new SkillStore(fx.home), "demo"));

                    assertTrue(!fx.producer.ran,
                            "$SKILL_MANAGER_BIN_DIR itself pointed at the other home, and the "
                                    + "producer was never handed it");
                    assertEquals(before, Files.readString(fx.other.resolve("bin/cli/tool")),
                            "so the other home is untouched");
                    assertTrue(refusal.contains("outside the home"), "got:\n" + refusal);
                })

                .test("a SANCTIONED mirror is not refused, and the producer is left to decline",
                        () -> {
                    // The control that keeps this from being a blanket refusal.
                    // A child home sharing its parent's provisioned toolchain is
                    // the arrangement the whole three-tier design rests on; a
                    // guard that broke it would be a worse defect than the one
                    // it closes.
                    Fixture fx = Fixture.build("sanctioned");
                    fx.claimHomeAsChildOfOther();
                    Path mirror = fx.home.resolve("bin/cli/tool");
                    Files.createSymbolicLink(mirror, fx.other.resolve("bin/cli/tool"));
                    String before = Files.readString(fx.other.resolve("bin/cli/tool"));

                    fx.producer.declineWhenPresent = true;
                    InstallOutcome outcome =
                            fx.registry.installOne(dep(), new SkillStore(fx.home), "demo");

                    assertEquals(InstallOutcome.ALREADY_PRESENT, outcome,
                            "the backend decided, not the guard");
                    assertTrue(Files.isSymbolicLink(mirror),
                            "and the mirror is still there — sharing survives the guard");
                    assertEquals(before, Files.readString(fx.other.resolve("bin/cli/tool")),
                            "with the parent's bytes untouched");
                })

                .test("a FORCED run over a sanctioned mirror owns the slot instead of "
                        + "writing through it", () -> {
                    // The hole the pre-check deliberately walks past: `force` is
                    // this home saying it has a reason to own the artifact, which
                    // is the same signal a rebuild carries. HIS-7's mechanism
                    // covers it, reused rather than copied.
                    Fixture fx = Fixture.build("forced-sanctioned");
                    fx.claimHomeAsChildOfOther();
                    Path mirror = fx.home.resolve("bin/cli/tool");
                    Files.createSymbolicLink(mirror, fx.other.resolve("bin/cli/tool"));
                    String before = Files.readString(fx.other.resolve("bin/cli/tool"));

                    fx.registry.installOne(dep(), new SkillStore(fx.home), "demo", true);

                    assertTrue(fx.producer.ran, "a forced run does run");
                    assertEquals(before, Files.readString(fx.other.resolve("bin/cli/tool")),
                            "and the PARENT's artifact is byte-identical — this is the "
                                    + "measured HIS-7 damage, not reproduced");
                    assertTrue(!Files.isSymbolicLink(mirror),
                            "because this home took the slot first");
                    assertTrue(Files.readString(mirror).contains(PRODUCED),
                            "and the producer's output landed HERE: " + mirror);
                })

                .test("an intact home installs exactly as before", () -> {
                    // The other half of every guard: it must not be an
                    // off-switch. This epic has shipped a shim that ALWAYS
                    // refused and passed a one-sided assertion for two releases.
                    Fixture fx = Fixture.build("intact");

                    InstallOutcome outcome =
                            fx.registry.installOne(dep(), new SkillStore(fx.home), "demo");

                    assertEquals(InstallOutcome.INSTALLED, outcome, "it installed");
                    assertTrue(fx.producer.ran, "the producer ran");
                    Path produced = fx.home.resolve("bin/cli/tool");
                    assertTrue(Files.exists(produced, LinkOption.NOFOLLOW_LINKS)
                                    && !Files.isSymbolicLink(produced),
                            "and this home holds a real file at " + produced);
                })

                .test("an artifact linked OUTSIDE every home is left alone — brew's case", () -> {
                    // The named exception. brew links its cellar into bin/cli and
                    // CliPresence.providedOutsideEveryHome already owns "this is a
                    // system tool". A guard that refused those would break every
                    // brew-backed dep in every home, which is not a subtle
                    // regression but it is one no other case here would catch.
                    Fixture fx = Fixture.build("system-tool");
                    Path cellar = Files.createDirectories(fx.base.resolve("opt/cellar"));
                    Path binary = cellar.resolve("tool");
                    Files.writeString(binary, "#!/usr/bin/env sh\nexit 0\n");
                    binary.toFile().setExecutable(true, false);
                    Files.createSymbolicLink(fx.home.resolve("bin/cli/tool"), binary);
                    fx.producer.declineWhenPresent = true;

                    InstallOutcome outcome =
                            fx.registry.installOne(dep(), new SkillStore(fx.home), "demo");

                    assertEquals(InstallOutcome.ALREADY_PRESENT, outcome,
                            "no refusal: that directory is not another Skill Manager home");
                })

                .runAll();
    }

    // ------------------------------------------------------------- fixture

    private static final String TOOL = "tool";
    private static final String PRODUCED = "produced-by-the-stub";

    private static CliDependency dep() {
        return new CliDependency(TOOL, "stub-producer:" + TOOL, null, null, TOOL, false,
                Map.of("any", new CliDependency.InstallTarget(
                        null, null, TOOL, List.of(), null, "install.sh", List.of())));
    }

    /**
     * A backend that does the one thing every real producer does: write to
     * {@code $SKILL_MANAGER_BIN_DIR/<name>} with a call that FOLLOWS a symlink,
     * exactly as {@code cat >} does.
     */
    private static final class StubProducer implements InstallerBackend {
        boolean ran;
        boolean declineWhenPresent;

        @Override public String id() { return "stub-producer"; }

        @Override public boolean available() { return true; }

        @Override
        public InstallOutcome install(CliDependency dep, SkillStore store, String skillName)
                throws java.io.IOException {
            Path out = store.cliBinDir().resolve(dep.onPath());
            if (declineWhenPresent && Files.exists(out)) return InstallOutcome.ALREADY_PRESENT;
            ran = true;
            Files.createDirectories(store.cliBinDir());
            Files.writeString(out, "#!/usr/bin/env sh\n# " + PRODUCED + "\nexit 0\n");
            out.toFile().setExecutable(true, false);
            return InstallOutcome.INSTALLED;
        }

        @Override
        public Fingerprint fingerprint(CliDependency dep, SkillStore store, String unitName) {
            return Fingerprint.gap("stub");
        }
    }

    private static final class Fixture {
        final Path base;
        final Path home;
        final Path other;
        final InstallerRegistry registry = new InstallerRegistry();
        final StubProducer producer = new StubProducer();

        private Fixture(Path base, Path home, Path other) {
            this.base = base;
            this.home = home;
            this.other = other;
            registry.register(producer);
        }

        static Fixture build(String label) throws Exception {
            Path base = Files.createTempDirectory("producer-confinement-" + label + "-");
            Path home = base.resolve("home");
            Path other = base.resolve("other");
            new SkillStore(home).init();
            new SkillStore(other).init();
            Files.createDirectories(home.resolve("bin/cli"));
            Path victim = Files.createDirectories(other.resolve("bin/cli")).resolve(TOOL);
            Files.writeString(victim, "#!/usr/bin/env sh\n# the other home's artifact\nexit 0\n");
            victim.toFile().setExecutable(true, false);
            return new Fixture(base, home, other);
        }

        /** Make {@code other} a genuine parent store of {@code home}. */
        void claimHomeAsChildOfOther() throws Exception {
            Path claim = Files.createDirectories(other.resolve("child-homes/home"));
            Files.writeString(claim.resolve("child-home.json"), """
                    {
                      "id" : "home",
                      "parentHome" : "%s",
                      "childHome" : "%s",
                      "units" : [ ],
                      "createdAt" : "2026-01-01T00:00:00Z"
                    }
                    """.formatted(other, home));
        }
    }

    private static Path real(Path p) {
        return dev.skillmanager.shared.util.Fs.realOrNormalized(p);
    }

    private static String refused(ThrowingRunnable body) throws Exception {
        try {
            body.run();
        } catch (WriteOutsideHomeException refusal) {
            return refusal.getMessage();
        }
        throw new AssertionError("expected a write-confinement refusal, and the install "
                + "completed — the producer was handed a destination in another home");
    }

    private interface ThrowingRunnable { void run() throws Exception; }
}
