package dev.skillmanager.cli.installer;

import com.sun.net.httpserver.HttpServer;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectContext;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * #428 R3-R5: the shared-CLI pin guard (#404 / HP-1) on EVERY install path,
 * stale requesters pruned, and refusals that are not exit-0 warnings.
 *
 * <p>Unlike {@link CliPinTest}, units A and B are real installed units, with
 * manifests on disk, and every path is driven through the interpreter's own
 * handler: {@code InstallCli} (sync's bulk pass), {@code RunCliInstall} (the
 * per-dependency install effect) and {@code RebuildCliArtifact} ({@code build}).
 *
 * <p>Red on the epic tip before the fix (b9dd09b0's guard in
 * {@code CliInstallRecorder.run} only):
 * <ul>
 *   <li>R3: build and the per-dependency path reinstalled 2.0 over B's 1.0 and
 *       recorded {@code requested_by = ["A"]}, dropping B.</li>
 *   <li>R4: B dropped / uninstalled, A's bump was still refused, because B
 *       stayed in requested_by forever.</li>
 *   <li>R5: the bulk refusal came back as an OK receipt.</li>
 * </ul>
 */
public final class CliSharedPinPathsTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("CliSharedPinPathsTest");

        suite.test("R3: build cannot bump a CLI another installed unit still claims", () -> {
            try (Fixture f = Fixture.shared("r3-build-")) {
                f.declare("A", "2.0");
                EffectReceipt r = f.run(new SkillEffect.RebuildCliArtifact(
                        "cli-shim:tar/tool", "A", f.dep("2.0"), false));
                f.assertUnmoved("build");
                assertTrue(r.status() != EffectStatus.OK, "build reports the refusal: " + r.status());
                assertTrue(hasRefusal(r), "with a CliRefused fact");
            }
        });

        suite.test("R3: the per-dependency install cannot bump a shared CLI either", () -> {
            try (Fixture f = Fixture.shared("r3-perdep-")) {
                f.declare("A", "2.0");
                EffectReceipt r = f.run(new SkillEffect.RunCliInstall("A", f.dep("2.0"), false));
                f.assertUnmoved("per-dep install");
                assertTrue(r.status() != EffectStatus.OK, "reported: " + r.status());
                assertTrue(r.status() != EffectStatus.FAILED,
                        "PARTIAL, not FAILED: FAILED would roll the unit's install back");
                assertTrue(hasRefusal(r), "with a CliRefused fact");
            }
        });

        suite.test("R3: a bump the claimants agree on keeps every claimant", () -> {
            try (Fixture f = Fixture.shared("r3-agree-")) {
                f.declare("A", "2.0");
                f.declare("B", "2.0");
                EffectReceipt r = f.run(new SkillEffect.RunCliInstall("A", f.dep("2.0"), false));
                assertEquals(EffectStatus.OK, r.status(), "B declares 2.0 too: no conflict");
                assertEquals("2.0", f.installed(), "bin/cli moved");
                assertEquals(List.of("A", "B"), f.row().requestedBy(),
                        "B was not dropped by A's record");
            }
        });

        suite.test("R4: a unit that dropped the dependency no longer blocks a bump (sync)", () -> {
            try (Fixture f = Fixture.shared("r4-dropped-")) {
                f.declare("A", "2.0");
                f.declare("B", null);
                EffectReceipt r = f.run(new SkillEffect.InstallCli(f.units()));
                assertEquals(EffectStatus.OK, r.status(), "nothing to refuse: " + r.errorMessage());
                assertEquals("2.0", f.installed(), "A's bump landed");
                CliLock.Entry row = f.row();
                assertEquals("2.0", row.version(), "the lock moved with it");
                assertEquals(List.of("A"), row.requestedBy(), "B was pruned from requested_by");
            }
        });

        suite.test("R4: an uninstalled unit no longer blocks a bump (sync)", () -> {
            try (Fixture f = Fixture.shared("r4-uninstalled-sync-")) {
                f.uninstallRecordOnly("B");
                f.declare("A", "2.0");
                EffectReceipt r = f.run(new SkillEffect.InstallCli(f.units()));
                assertEquals(EffectStatus.OK, r.status(), "nothing to refuse: " + r.errorMessage());
                assertEquals("2.0", f.installed(), "A's bump landed");
                assertEquals(List.of("A"), f.row().requestedBy(), "B was pruned");
            }
        });

        suite.test("R4: an uninstalled unit no longer blocks a bump (per-dep install)", () -> {
            try (Fixture f = Fixture.shared("r4-uninstalled-")) {
                f.uninstallRecordOnly("B");
                f.declare("A", "2.0");
                EffectReceipt r = f.run(new SkillEffect.RunCliInstall("A", f.dep("2.0"), false));
                assertEquals(EffectStatus.OK, r.status(), "nothing to refuse: " + r.errorMessage());
                assertEquals("2.0", f.installed(), "A's bump landed");
                assertEquals(List.of("A"), f.row().requestedBy(), "B was pruned");
            }
        });

        suite.test("R4: a unit that still declares the row is kept and still blocks", () -> {
            try (Fixture f = Fixture.shared("r4-control-")) {
                f.declare("A", "2.0");
                f.run(new SkillEffect.InstallCli(f.units()));
                f.assertUnmoved("sync with B still on 1.0");
            }
        });

        suite.test("R5: a refused bulk install is not an OK receipt", () -> {
            try (Fixture f = Fixture.shared("r5-bulk-")) {
                f.declare("A", "2.0");
                EffectReceipt r = f.run(new SkillEffect.InstallCli(f.units()));
                f.assertUnmoved("sync");
                assertTrue(r.status() != EffectStatus.OK,
                        "an OK receipt is what let sync exit 0: " + r.status());
                assertTrue(r.status() != EffectStatus.FAILED,
                        "PARTIAL, so the deps that did install are not rolled back");
                assertTrue(hasRefusal(r), "and it carries a CliRefused fact for --json and the exit code");
            }
        });

        suite.test("R5: a refusal makes install exit non-zero", () -> {
            dev.skillmanager.app.InstallUseCase.Report clean = new dev.skillmanager.app.InstallUseCase.Report(
                    0, 0, List.of("A"), Map.of(), List.of(), 0, List.of(), List.of());
            dev.skillmanager.app.InstallUseCase.Report refused = new dev.skillmanager.app.InstallUseCase.Report(
                    1, 0, List.of("A"), Map.of(), List.of(), 0, List.of(), List.of("cli: tool: ..."));
            assertEquals(0, clean.commandExitCode(), "control: a clean install exits 0");
            assertTrue(refused.commandExitCode() != 0, "a refused CLI install does not exit 0");
        });

        return suite.runAll();
    }

    private static boolean hasRefusal(EffectReceipt r) {
        for (ContextFact f : r.facts()) {
            if (f.getClass().getSimpleName().equals("CliRefused")) return true;
        }
        return false;
    }

    /** A home where installed units A and B both claim tar:tool @ 1.0. */
    private static final class Fixture implements AutoCloseable {
        final SkillStore store;
        final Served served;

        private Fixture(SkillStore store, Served served) {
            this.store = store;
            this.served = served;
        }

        static Fixture shared(String prefix) throws Exception {
            SkillStore store = new SkillStore(Files.createTempDirectory("cli428-" + prefix).toRealPath());
            store.init();
            Fixture f = new Fixture(store, new Served());
            f.declare("A", "1.0");
            f.declare("B", "1.0");
            EffectReceipt r = f.run(new SkillEffect.InstallCli(f.units()));
            assertEquals(EffectStatus.OK, r.status(), "setup: both install 1.0");
            assertEquals("1.0", f.installed(), "setup: bin/cli is 1.0");
            assertEquals(List.of("A", "B"), f.row().requestedBy(), "setup: both claim it");
            return f;
        }

        /** Write unit {@code name} declaring tar:tool at {@code version}, or no CLI when null. */
        void declare(String name, String version) throws Exception {
            Path dir = store.root().resolve("skills/" + name);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"),
                    "---\nname: " + name + "\ndescription: fixture\n---\nbody\n");
            String cli = version == null ? "" : """

                    [[cli_dependencies]]
                    name = "tool"
                    spec = "tar:tool"
                    min_version = "%s"
                    platform_independent = true

                    [cli_dependencies.install.any]
                    url = "%s"
                    binary = "tool"
                    """.formatted(version, served.url("/v" + version.charAt(0)));
            Files.writeString(dir.resolve("skill-manager.toml"), """
                    [skill]
                    name = "%s"
                    version = "0.1.0"
                    description = "fixture"
                    """.formatted(name) + cli);
            new UnitStore(store).write(new InstalledUnit(name, "0.1.0",
                    InstalledUnit.Kind.LOCAL_DIR, InstalledUnit.InstallSource.LOCAL_FILE,
                    null, null, null, "2026-01-01T00:00:00Z", List.of(), UnitKind.SKILL));
        }

        /** Remove B's installed record, the way an uninstall that skipped the cleaner leaves it. */
        void uninstallRecordOnly(String name) throws Exception {
            try (var s = Files.list(store.installedDir())) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.getFileName().toString().startsWith(name + ".")) Files.delete(p);
                }
            }
            Path dir = store.root().resolve("skills/" + name);
            try (var w = Files.walk(dir)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }

        CliDependency dep(String version) {
            return new CliDependency("tool", "tar:tool", version, null, null, true,
                    Map.of("any", new CliDependency.InstallTarget(
                            served.url("/v" + version.charAt(0)), null, "tool", List.of(), null)));
        }

        List<AgentUnit> units() throws IOException {
            return store.listInstalledUnits().units();
        }

        EffectReceipt run(SkillEffect effect) {
            return new LiveInterpreter(store).runOne(effect, new EffectContext(store, null));
        }

        CliLock.Entry row() throws IOException {
            return CliLock.load(store).get("tar", "tool");
        }

        String installed() throws IOException {
            String body = Files.readString(store.cliBinDir().resolve("tool"));
            return body.contains("echo 2.0") ? "2.0" : body.contains("echo 1.0") ? "1.0" : body;
        }

        void assertUnmoved(String path) throws IOException {
            assertEquals("1.0", installed(), path + ": bin/cli keeps the shared 1.0");
            CliLock.Entry row = row();
            assertEquals("1.0", row.version(), path + ": the lock still says 1.0");
            assertTrue(row.requestedBy().contains("B"), path + ": B was not dropped from requested_by: "
                    + row.requestedBy());
        }

        @Override public void close() { served.close(); }
    }

    /** A loopback server publishing two versions of one raw-binary CLI. */
    private static final class Served implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger hits = new AtomicInteger();

        Served() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            for (String v : List.of("1.0", "2.0")) {
                byte[] bytes = ("#!/bin/sh\necho " + v + "\n").getBytes(StandardCharsets.UTF_8);
                server.createContext("/v" + v.charAt(0), exchange -> {
                    hits.incrementAndGet();
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
            }
            server.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        @Override public void close() { server.stop(0); }
    }
}
