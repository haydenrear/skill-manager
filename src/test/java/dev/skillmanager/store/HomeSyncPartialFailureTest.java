package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeMaterializer.SyncStatus;
import dev.skillmanager.bindings.ChildHomeMaterializer.UnitSync;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * #428 R7: a {@code home sync} that fails part way still does the bookkeeping
 * for every unit it already changed — the installed record's version is
 * restated (#298) and the drift gate is armed (#403) — and a re-run converges,
 * including after a pass that died without any bookkeeping at all.
 */
public final class HomeSyncPartialFailureTest {

    private static final List<String> UNITS = List.of("pf-a", "pf-b", "pf-c");

    public static int run() throws Exception {
        return Tests.suite("HomeSyncPartialFailureTest")

                .test("the 2nd of 3 units fails: the 1st is recorded and armed, a re-run converges", () -> {
                    Fixture f = Fixture.create("mid");
                    for (String u : UNITS) skill(f.source, u, "0.2.0");
                    // Unit b's source cannot be read, so its reconcile throws.
                    Path secret = f.source.skillDir("pf-b").resolve("secret.md");
                    Files.writeString(secret, "unreadable\n");
                    Set<PosixFilePermission> original = Files.getPosixFilePermissions(secret);
                    Files.setPosixFilePermissions(secret, EnumSet.noneOf(PosixFilePermission.class));
                    assertFalse(Files.isReadable(secret), "the failure injection took effect (not root)");

                    boolean threw = false;
                    try {
                        HomeSync.run(f.source, f.dest, HomeSync.Options.defaults());
                    } catch (IOException expected) {
                        threw = true;
                    } finally {
                        Files.setPosixFilePermissions(secret, original);
                    }
                    assertTrue(threw, "the sync reported the failure");
                    assertEquals("0.2.0", manifest(f.dest, "pf-a"), "unit a's bytes moved before b failed");
                    assertEquals("0.2.0", recorded(f.dest, "pf-a"),
                            "and its installed record was restated despite the later failure");
                    assertEquals("0.1.0", recorded(f.dest, "pf-b"), "b did not move; nor did its record");
                    DriftGate gate = DriftGate.pending(f.dest).orElse(null);
                    assertTrue(gate != null, "the drift gate is armed for the change that did land");
                    assertTrue(names(gate).contains("pf-a"), "naming unit a: " + names(gate));
                    assertFalse(names(gate).contains("pf-c"), "and not a unit never reached");

                    HomeSync.Report rerun = HomeSync.run(f.source, f.dest, HomeSync.Options.defaults());
                    assertTrue(rerun.clean(), "the re-run completes");
                    for (String u : UNITS) {
                        assertEquals("0.2.0", recorded(f.dest, u), u + " converges");
                    }
                    DriftGate after = DriftGate.pending(f.dest).orElseThrow();
                    assertTrue(names(after).containsAll(UNITS), "every moved unit unread: " + names(after));
                })

                .test("a pass that died with no bookkeeping is repaired by the next no-op pass", () -> {
                    Fixture f = Fixture.create("crash");
                    HomeDigest acknowledged = HomeDigest.read(f.dest).orElseThrow();
                    skill(f.source, "pf-a", "0.2.0");
                    HomeSync.run(f.source, f.dest, HomeSync.Options.defaults());
                    // Undo everything the pass recorded, keeping only the bytes:
                    // what a process killed between the swap and the bookkeeping leaves.
                    Files.deleteIfExists(DriftGate.file(f.dest));
                    acknowledged.write(f.dest);
                    new UnitStore(f.dest).write(record("pf-a", "0.1.0"));

                    HomeSync.Report rerun = HomeSync.run(f.source, f.dest, HomeSync.Options.defaults());
                    UnitSync a = rerun.units().stream().filter(u -> u.unitName().equals("pf-a"))
                            .findFirst().orElseThrow();
                    assertEquals(SyncStatus.UNCHANGED, a.status(), "nothing left to move");
                    assertEquals("0.2.0", recorded(f.dest, "pf-a"), "the record converges");
                    DriftGate gate = DriftGate.pending(f.dest).orElse(null);
                    assertTrue(gate != null && names(gate).contains("pf-a"),
                            "the unread change is armed after all: " + (gate == null ? "none" : names(gate)));
                })

                .test("a no-op pass over an acknowledged home still arms nothing (#403 control)", () -> {
                    Fixture f = Fixture.create("noop");
                    String digestBefore = Files.readString(HomeDigest.file(f.dest));
                    HomeSync.run(f.source, f.dest, HomeSync.Options.defaults());
                    assertFalse(DriftGate.pending(f.dest).isPresent(), "no drift");
                    assertEquals(digestBefore, Files.readString(HomeDigest.file(f.dest)),
                            "and the baseline is untouched");
                })

                .runAll();
    }

    /** Source and destination holding pf-a..c at 0.1.0, records written, drift acknowledged. */
    private record Fixture(SkillStore source, SkillStore dest) {
        static Fixture create(String label) throws IOException {
            Path root = Files.createTempDirectory("home-sync-partial-" + label + "-");
            SkillStore source = store(root.resolve("source"));
            SkillStore dest = store(root.resolve("dest"));
            for (String u : UNITS) skill(source, u, "0.1.0");
            HomeSync.run(source, dest, HomeSync.Options.defaults());
            for (String u : UNITS) new UnitStore(dest).write(record(u, "0.1.0"));
            DriftGate.acknowledge(dest);
            if (!Files.isRegularFile(HomeDigest.file(dest))) HomeDigest.compute(dest).write(dest);
            return new Fixture(source, dest);
        }
    }

    private static SkillStore store(Path root) throws IOException {
        SkillStore store = new SkillStore(root);
        store.init();
        return store;
    }

    private static void skill(SkillStore home, String name, String version) throws IOException {
        Path dir = home.skillDir(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: release " + version + "\n---\nbody\n");
        Files.writeString(dir.resolve("skill-manager.toml"),
                "[skill]\nname = \"" + name + "\"\nversion = \"" + version + "\"\n"
                        + "description = \"fixture\"\n");
    }

    private static InstalledUnit record(String name, String version) {
        return new InstalledUnit(name, version, InstalledUnit.Kind.LOCAL_DIR,
                InstalledUnit.InstallSource.LOCAL_FILE, "/nowhere/" + name, null, null,
                "2026-01-01T00:00:00Z", List.of(), UnitKind.SKILL);
    }

    private static String recorded(SkillStore home, String name) {
        return new UnitStore(home).read(name).map(InstalledUnit::version).orElse(null);
    }

    private static String manifest(SkillStore home, String name) throws IOException {
        String toml = Files.readString(home.skillDir(name).resolve("skill-manager.toml"));
        var m = java.util.regex.Pattern.compile("version = \"([^\"]+)\"").matcher(toml);
        return m.find() ? m.group(1) : null;
    }

    private static List<String> names(DriftGate gate) {
        return gate.report().units().stream().map(DriftReport.UnitDrift::name).toList();
    }
}
