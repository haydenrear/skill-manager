package dev.skillmanager.cli.installer;

import com.sun.net.httpserver.HttpServer;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.HomeCloner;
import dev.skillmanager.store.SkillStore;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * A clone's dangling CLI shims stayed dangling, and {@code sync} was not a
 * fixpoint.
 *
 * <h2>The measurement</h2>
 *
 * <p>{@code home clone} skips {@code venvs/} — a pip console script's shebang is
 * an absolute interpreter path the kernel resolves literally — but copies
 * {@code bin/}, so the copy carries
 * {@code bin/cli/jinja2 -> ../../venvs/jinja2-cli/bin/jinja2} pointing at
 * nothing. {@code home verify} refuses the copy and names {@code sync
 * --force-scripts} as the repair. Run in the copy, with the operator's global
 * {@code ~/.skill-manager/bin/cli} on PATH, that command reported
 * {@code cli: 25 already present} and repaired nothing; with that one directory
 * removed from PATH the identical command reported {@code cli: installed
 * jinja2-cli[yaml]==0.8.2}. The clone's provisioning was decided by a DIFFERENT
 * HOME's copy of the tool, so {@code verify} refused forever and its printed
 * remedy could not clear what it named — the #142 defect class, a remedy that
 * does not work.
 *
 * <h2>What is asserted, and why each case can fail</h2>
 *
 * <p>The condition is planted rather than described: a foreign home whose
 * {@code bin/cli} really does hold a working executable of that name, ahead of
 * everything on the PATH the installer searches, plus a really dangling shim in
 * the home under test. Every case that turns on the fix asserts, in the same
 * breath, that the OLD oracle still answers "present"
 * ({@link CliPresence#onProcessPath} is the process-PATH walk the backends used
 * to open with) — so none of them can pass by the condition having quietly
 * stopped holding.
 *
 * <p>The last case is the fixpoint itself, end to end and over a loopback HTTP
 * server so it needs no network: {@code verify} refuses, the install runs, and
 * {@code verify} passes. Against the old behaviour the install is skipped and
 * the second {@code verify} still refuses.
 */
public final class CliPresenceTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("CliPresenceTest");

        suite.test("a foreign home's copy of the tool is not this home's provisioning", () -> {
            SkillStore store = newStore("cli-presence-foreign-");
            Path foreignBin = foreignHomeBin("cli-presence-foreign-home-", "jinja2");
            danglingShim(store, "jinja2");

            withPath(foreignBin, () -> {
                // The old check, verbatim: it still says yes, so this case
                // cannot pass by the fixture having drifted.
                assertTrue(CliPresence.onProcessPath("jinja2") != null,
                        "precondition: the foreign home's jinja2 is on PATH and executable");
                assertFalse(CliPresence.alreadyProvided(dep("jinja2"), store),
                        "another home having jinja2 does not provision this one");

                // Through the production dispatch, not the predicate alone. A
                // tar dep with no target for this platform SKIPs after deciding
                // to install — which is the observable difference from
                // ALREADY_PRESENT, and needs no network to reach.
                assertEquals(InstallOutcome.SKIPPED,
                        new InstallerRegistry().installOne(dep("jinja2"), store, "spec-doubles"),
                        "the backend proceeds to install instead of short-circuiting");
            });
        });

        suite.test("a tool the system provides outside any home still suppresses the install",
                () -> {
                    // The other half of what on_path means, and the reason the
                    // check is scoped rather than deleted: `brew:jq` with
                    // on_path = "jq" must not install over the distro's jq.
                    SkillStore store = newStore("cli-presence-system-");
                    Path systemBin = Files.createTempDirectory("cli-presence-usr-local-");
                    executable(systemBin.resolve("jq"));

                    withPath(systemBin, () -> {
                        assertTrue(CliPresence.alreadyProvided(dep("jq"), store),
                                "a directory belonging to no home is the system's answer");
                        assertEquals(InstallOutcome.ALREADY_PRESENT,
                                new InstallerRegistry().installOne(dep("jq"), store, "some-unit"),
                                "and the backend still declines to install over it");
                    });
                });

        suite.test("a shim in this home counts only while it resolves", () -> {
            SkillStore store = newStore("cli-presence-shim-");
            Path shim = store.cliBinDir().resolve("jinja2");

            danglingShim(store, "jinja2");
            assertTrue(Files.isSymbolicLink(shim), "precondition: the shim is there as a link");
            assertFalse(Files.exists(shim), "precondition: and it resolves to nothing");
            withPath(Files.createTempDirectory("cli-presence-empty-"), () ->
                    assertFalse(CliPresence.alreadyProvided(dep("jinja2"), store),
                            "a dangling shim is absent, not present"));

            Path target = store.venvsDir().resolve("jinja2-cli/bin/jinja2");
            Files.createDirectories(target.getParent());
            executable(target);
            withPath(Files.createTempDirectory("cli-presence-empty2-"), () ->
                    assertTrue(CliPresence.alreadyProvided(dep("jinja2"), store),
                            "the same shim, once its target exists, is this home's provisioning"));
        });

        suite.test("`home verify` refuses a dangling shim and the install clears it", () -> {
            // The fixpoint. Everything above establishes that the decision
            // changed; this asserts that the decision is the one standing
            // between `home verify`'s refusal and its own printed remedy.
            SkillStore store = newStore("cli-presence-fixpoint-");
            Path foreignBin = foreignHomeBin("cli-presence-fixpoint-home-", "hello");
            danglingShim(store, "hello");

            assertFalse(HomeCloner.verify(store.root(), false).unresolved().isEmpty(),
                    "verify refuses the home while the shim points at nothing");

            HttpServer server = serve("#!/bin/sh\necho hello\n");
            try {
                String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hello";
                CliDependency tarDep = new CliDependency(
                        "hello", "tar:hello", null, null, "hello", true,
                        Map.of("any", new CliDependency.InstallTarget(
                                url, null, "hello", List.of(), null)));
                withPath(foreignBin, () -> {
                    assertTrue(CliPresence.onProcessPath("hello") != null,
                            "precondition: the foreign home's hello is what PATH answers with");
                    assertEquals(InstallOutcome.INSTALLED,
                            new InstallerRegistry().installOne(tarDep, store, "some-unit"),
                            "the remedy the refusal names actually installs");
                });
            } finally {
                server.stop(0);
            }

            assertTrue(Files.isExecutable(store.cliBinDir().resolve("hello")),
                    "the shim now resolves");
            assertTrue(HomeCloner.verify(store.root(), false).unresolved().isEmpty(),
                    "and verify passes — the remedy is a fixpoint");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- fixture

    /** A dep declaring {@code on_path}, which is the shape that used to short-circuit. */
    private static CliDependency dep(String name) {
        return new CliDependency(name, "tar:" + name, null, null, name, false, Map.of());
    }

    private static SkillStore newStore(String prefix) throws Exception {
        SkillStore store = new SkillStore(
                Files.createTempDirectory(prefix).toRealPath());
        store.init();
        return store;
    }

    /**
     * A directory that {@code LaunchEnv.looksLikeStoreRoot} recognizes as a
     * home, holding a working executable in its {@code bin/cli}. Built by
     * layout rather than by name, because that is how the recognition works.
     */
    private static Path foreignHomeBin(String prefix, String tool) throws Exception {
        Path home = Files.createTempDirectory(prefix).toRealPath();
        Files.createDirectories(home.resolve("installed"));
        Files.createDirectories(home.resolve("skills"));
        Path bin = Files.createDirectories(home.resolve("bin/cli"));
        executable(bin.resolve(tool));
        return bin;
    }

    /** The shape a clone leaves behind: a link into the {@code venvs/} it skipped. */
    private static void danglingShim(SkillStore store, String name) throws Exception {
        Path shim = Files.createDirectories(store.cliBinDir()).resolve(name);
        Files.deleteIfExists(shim);
        Files.createSymbolicLink(shim, Path.of("../../venvs/" + name + "-cli/bin/" + name));
    }

    private static void executable(Path file) throws Exception {
        Files.writeString(file, "#!/bin/sh\necho " + file.getFileName() + "\n");
        file.toFile().setExecutable(true, false);
    }

    private static void withPath(Path only, Tests.Body body) throws Exception {
        CliPresence.setPathOverride(only.toString() + File.pathSeparator + "/nonexistent-bin");
        try {
            body.run();
        } finally {
            CliPresence.clearPathOverride();
        }
    }

    /** A loopback server so the tar path is exercised without a network. */
    private static HttpServer serve(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/hello", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
