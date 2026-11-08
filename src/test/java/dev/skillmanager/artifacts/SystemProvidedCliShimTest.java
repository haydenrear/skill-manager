package dev.skillmanager.artifacts;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.cli.installer.CliPresence;
import dev.skillmanager.lock.CliInstallRecorder;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * #409 (HP-7): a CLI the system provides is not a stale cli-shim; a shim this
 * home installed and that is now gone still is.
 *
 * <p>The PATH is pinned with {@link CliPresence#setPathOverride} to one scratch
 * directory standing in for {@code /opt/homebrew/bin}, so the host's own tools
 * cannot decide a verdict either way.
 */
public final class SystemProvidedCliShimTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("SystemProvidedCliShimTest");

        suite.test("system-provided row is not stale; deleted installed shim is", () -> {
            Fixture f = Fixture.create();
            try {
                CliLock lock = CliLock.load(f.store);
                lock.put(row("brew", "hp-sys", "hp-sys", CliLock.PROVIDED_SYSTEM));
                lock.put(row("tar", "hp-own", "hp-own", CliLock.PROVIDED_HOME));
                lock.save(f.store);
                // hp-own was installed; then deleted. Nothing named hp-sys was
                // ever linked.
                ArtifactFreshness verdicts = ArtifactFreshness.of(ArtifactIndex.of(f.store), f.store);

                ArtifactFreshness.Verdict sys = verdicts.of(ArtifactIds.cliShim("brew", "hp-sys"));
                assertNotNull(sys, "the system-provided row is still an artifact");
                assertTrue(sys.freshness() != ArtifactFreshness.Freshness.STALE,
                        "system-provided CLI is not stale: " + sys.reason());
                Artifact sysArtifact = ArtifactIndex.of(f.store)
                        .byId(ArtifactIds.cliShim("brew", "hp-sys")).orElseThrow();
                assertTrue(sysArtifact.outputs().isEmpty(), "no bin/cli output is expected");
                assertContains(sysArtifact.actual().get("provided_by"), "hp-sys",
                        "names where the system provides it");

                ArtifactFreshness.Verdict own = verdicts.of(ArtifactIds.cliShim("tar", "hp-own"));
                assertEquals(ArtifactFreshness.Freshness.STALE, own.freshness(),
                        "a deleted installed shim is stale");
            } finally {
                f.close();
            }
        });

        suite.test("an installed shim stays stale when the system also has the binary", () -> {
            Fixture f = Fixture.create();
            try {
                f.onSystem("hp-own");
                CliLock lock = CliLock.load(f.store);
                lock.put(row("tar", "hp-own", "hp-own", CliLock.PROVIDED_HOME));
                lock.save(f.store);
                ArtifactFreshness verdicts = ArtifactFreshness.of(ArtifactIndex.of(f.store), f.store);
                assertEquals(ArtifactFreshness.Freshness.STALE,
                        verdicts.of(ArtifactIds.cliShim("tar", "hp-own")).freshness(),
                        "provided = home is never excused by PATH");
            } finally {
                f.close();
            }
        });

        suite.test("a row recorded system-provided whose tool left the system is stale", () -> {
            Fixture f = Fixture.create();
            try {
                Files.delete(f.system.resolve("hp-sys"));
                CliLock lock = CliLock.load(f.store);
                lock.put(row("brew", "hp-sys", "hp-sys", CliLock.PROVIDED_SYSTEM));
                lock.save(f.store);
                ArtifactFreshness verdicts = ArtifactFreshness.of(ArtifactIndex.of(f.store), f.store);
                assertEquals(ArtifactFreshness.Freshness.STALE,
                        verdicts.of(ArtifactIds.cliShim("brew", "hp-sys")).freshness(),
                        "nothing provides it any more; a sync would install it");
            } finally {
                f.close();
            }
        });

        suite.test("a pre-#409 row whose on_path the system provides is not stale", () -> {
            Fixture f = Fixture.create();
            try {
                CliLock lock = CliLock.load(f.store);
                lock.put(row("brew", "hp-sys", "hp-sys", null));
                lock.put(row("tar", "hp-own", "hp-own", null));
                lock.save(f.store);
                ArtifactFreshness verdicts = ArtifactFreshness.of(ArtifactIndex.of(f.store), f.store);
                assertTrue(verdicts.of(ArtifactIds.cliShim("brew", "hp-sys")).freshness()
                                != ArtifactFreshness.Freshness.STALE,
                        "the next sync would link nothing either, so it is not stale");
                assertEquals(ArtifactFreshness.Freshness.STALE,
                        verdicts.of(ArtifactIds.cliShim("tar", "hp-own")).freshness(),
                        "a legacy row nothing on the system provides is still stale");
            } finally {
                f.close();
            }
        });

        suite.test("provided round-trips through cli-lock.toml", () -> {
            Fixture f = Fixture.create();
            try {
                CliLock lock = CliLock.load(f.store);
                lock.put(row("brew", "hp-sys", "hp-sys", CliLock.PROVIDED_SYSTEM));
                lock.put(row("tar", "hp-own", "hp-own", null));
                lock.save(f.store);
                String text = Files.readString(f.store.root().resolve(CliLock.FILENAME));
                assertContains(text, "provided = \"system\"", "written");
                CliLock back = CliLock.load(f.store);
                assertTrue(back.get("brew", "hp-sys").providedBySystem(), "read back");
                assertEquals(null, back.get("tar", "hp-own").provided(), "absent stays absent");
                assertEquals(CliLock.PROVIDED_SYSTEM,
                        back.get("brew", "hp-sys").withRequester("other").provided(),
                        "withRequester keeps provided");
            } finally {
                f.close();
            }
        });

        suite.test("the recorder reads provenance off the disk", () -> {
            Fixture f = Fixture.create();
            try {
                CliDependency sys = new CliDependency("hp-sys", "brew:hp-sys", null, null,
                        "hp-sys", false, Map.of());
                CliDependency own = new CliDependency("hp-own", "tar:hp-own", null, null,
                        null, true, Map.of());
                CliDependency nowhere = new CliDependency("hp-none", "brew:hp-none", null, null,
                        "hp-none", false, Map.of());
                assertEquals(CliLock.PROVIDED_SYSTEM, CliInstallRecorder.provenance(sys, f.store),
                        "on PATH outside every home, nothing in bin/cli");
                assertEquals(null, CliInstallRecorder.provenance(nowhere, f.store),
                        "neither: claims nothing");
                Path shim = f.store.cliBinDir().resolve("hp-own");
                Files.createDirectories(shim.getParent());
                Files.writeString(shim, "#!/bin/sh\necho own\n");
                shim.toFile().setExecutable(true);
                assertEquals(CliLock.PROVIDED_HOME, CliInstallRecorder.provenance(own, f.store),
                        "this home holds it");
                // A shim this home holds wins even when the system has one too.
                f.onSystem("hp-sys");
                Path sysShim = f.store.cliBinDir().resolve("hp-sys");
                Files.writeString(sysShim, "#!/bin/sh\necho mine\n");
                sysShim.toFile().setExecutable(true);
                assertEquals(CliLock.PROVIDED_HOME, CliInstallRecorder.provenance(sys, f.store),
                        "home first");
            } finally {
                f.close();
            }
        });

        return suite.runAll();
    }

    private static CliLock.Entry row(String backend, String tool, String binary, String provided) {
        return new CliLock.Entry(backend, tool, "1.0.0", backend + ":" + tool, null,
                List.of("beta"), "2026-01-01T00:00:00Z", binary, null, null, provided);
    }

    /** A home whose unit {@code beta} declares {@code brew:hp-sys} and {@code tar:hp-own}. */
    private record Fixture(SkillStore store, Path system) implements AutoCloseable {

        static Fixture create() throws Exception {
            Path home = Files.createTempDirectory("hp7-home-").toRealPath();
            SkillStore store = new SkillStore(home);
            store.init();
            Path beta = home.resolve("skills/beta");
            Files.createDirectories(beta);
            Files.writeString(beta.resolve("SKILL.md"),
                    "---\nname: beta\ndescription: fixture\n---\nbody\n");
            Files.writeString(beta.resolve("skill-manager.toml"), """
                    [skill]
                    name = "beta"
                    version = "0.1.0"
                    description = "beta fixture"

                    [[cli_dependencies]]
                    name = "hp-sys"
                    spec = "brew:hp-sys"
                    on_path = "hp-sys"

                    [[cli_dependencies]]
                    name = "hp-own"
                    spec = "tar:hp-own"
                    platform_independent = true

                    [cli_dependencies.install.any]
                    url = "http://127.0.0.1:1/hp-own"
                    binary = "hp-own"
                    """);
            new UnitStore(store).write(new InstalledUnit("beta", "0.1.0",
                    InstalledUnit.Kind.LOCAL_DIR, InstalledUnit.InstallSource.LOCAL_FILE,
                    null, null, null, "2026-01-01T00:00:00Z", List.of(), UnitKind.SKILL));
            Path system = Files.createTempDirectory("hp7-system-bin-").toRealPath();
            Fixture f = new Fixture(store, system);
            f.onSystem("hp-sys");
            CliPresence.setPathOverride(system.toString());
            return f;
        }

        void onSystem(String name) throws Exception {
            Path p = system.resolve(name);
            Files.writeString(p, "#!/bin/sh\necho system\n");
            p.toFile().setExecutable(true);
        }

        @Override
        public void close() {
            CliPresence.clearPathOverride();
        }
    }
}
