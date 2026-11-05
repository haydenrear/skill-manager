package dev.skillmanager.cli.installer;

import com.sun.net.httpserver.HttpServer;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.lock.CliInstallRecorder;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.lock.CliPin;
import dev.skillmanager.model.CliDependency;
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

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * #404: a unit bumping ITS OWN declared CLI pin changes the installed binary on
 * the next install, or the install refuses — it never silently keeps the old
 * one.
 *
 * <p>Baseline these cases reddened before the fix: the second
 * {@code installOne} returned {@code ALREADY_PRESENT} because
 * {@code bin/cli/tool} was executable, and {@code bin/cli/tool} still printed
 * {@code 1.0} while {@code cli-lock.toml} recorded {@code 2.0}.
 */
public final class CliPinTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("CliPinTest");

        suite.test("tar: bumping the unit's own pin reinstalls the binary", () -> {
            SkillStore store = newStore("pin-tar-bump-");
            try (Served served = new Served()) {
                install(store, served.tarDep("1.0"));
                assertEquals("1.0", installedVersion(store), "precondition: v1 installed");

                InstallOutcome bumped = install(store, served.tarDep("2.0"));
                assertEquals(InstallOutcome.INSTALLED, bumped,
                        "a moved pin is not 'already present'");
                assertEquals("2.0", installedVersion(store),
                        "bin/cli/tool is the newly declared version");
                assertEquals("2.0", CliLock.load(store).get("tar", "tool").version(),
                        "and the lock agrees with the binary");
                assertEquals(2, served.hits.get(), "exactly one download per version");
            }
        });

        suite.test("tar: an unchanged pin is not reinstalled", () -> {
            SkillStore store = newStore("pin-tar-same-");
            try (Served served = new Served()) {
                install(store, served.tarDep("1.0"));
                assertEquals(InstallOutcome.ALREADY_PRESENT, install(store, served.tarDep("1.0")),
                        "same declaration, binary present: nothing to do");
                assertEquals(1, served.hits.get(), "no second download");
            }
        });

        suite.test("tar: a url change at the same min_version reinstalls", () -> {
            // The case recordInstall's keep-the-row-when-version-matches branch
            // used to hide: same version, so the old spec and no pin survived.
            SkillStore store = newStore("pin-tar-url-");
            try (Served served = new Served()) {
                install(store, served.tarDep("1.0"));
                CliDependency sameVersionNewUrl = served.tarDep("1.0", "/v2");
                assertEquals(InstallOutcome.INSTALLED, install(store, sameVersionNewUrl),
                        "a different download is a different pin");
                assertEquals("2.0", installedVersion(store), "the new url's bytes landed");
            }
        });

        suite.test("the lock row records the declaration it was installed from", () -> {
            SkillStore store = newStore("pin-row-");
            try (Served served = new Served()) {
                CliDependency dep = served.tarDep("1.0");
                install(store, dep);
                CliLock.Entry row = CliLock.load(store).get("tar", "tool");
                assertEquals(CliPin.of(dep), row.declaredPin(), "declared_pin round-trips");
                String text = Files.readString(store.root().resolve(CliLock.FILENAME));
                assertContains(text, "declared_pin = ", "and is written to cli-lock.toml");
            }
        });

        suite.test("a moved pin the backend cannot reinstall is refused, not recorded", () -> {
            SkillStore store = newStore("pin-refuse-");
            try (Served served = new Served()) {
                install(store, served.tarDep("1.0"));
                // Same tool, a bumped pin, and no install target this platform
                // can use: TarBackend returns SKIPPED under force.
                CliDependency unreinstallable = new CliDependency(
                        "tool", "tar:tool", "2.0", null, null, false,
                        Map.of("no-such-os-arch", new CliDependency.InstallTarget(
                                served.url("/v2"), null, "tool", List.of(), null)));
                try {
                    new InstallerRegistry().installOne(unreinstallable, store, "unit-a");
                    throw new AssertionError("expected a refusal naming the moved pin");
                } catch (IOException refused) {
                    assertContains(refused.getMessage(), "pin moved", "says why");
                    assertContains(refused.getMessage(), "old install", "says what it kept");
                }
                assertEquals("1.0", CliLock.load(store).get("tar", "tool").version(),
                        "the lock still describes the binary that is actually there");
            }
        });

        suite.test("legacy row (no declared_pin): same spec+version keeps, a bump reinstalls", () -> {
            SkillStore store = newStore("pin-legacy-");
            try (Served served = new Served()) {
                CliDependency v1 = served.tarDep("1.0");
                install(store, v1);
                stripPin(store);
                assertEquals(InstallOutcome.ALREADY_PRESENT, install(store, v1),
                        "upgrading skill-manager does not reinstall every unchanged CLI");
                stripPin(store);
                assertEquals(InstallOutcome.INSTALLED, install(store, served.tarDep("2.0")),
                        "a min_version bump is visible from the columns a legacy row has");
                assertEquals("2.0", installedVersion(store), "and lands");
            }
        });

        suite.test("pip/npm: a spec pin bump is a moved pin; brew is not governed", () -> {
            CliDependency pip61 = new CliDependency("cowsay", "pip:cowsay==6.1", null, null,
                    "cowsay", true, Map.of());
            CliDependency pip70 = new CliDependency("cowsay", "pip:cowsay==7.0", null, null,
                    "cowsay", true, Map.of());
            CliLock.Entry row = new CliLock.Entry("pip", "cowsay", "6.1", "pip:cowsay==6.1",
                    null, List.of("unit-a"), "t", "cowsay", null, CliPin.of(pip61));
            assertEquals(null, CliPin.moved(row, pip61), "unchanged");
            String why = CliPin.moved(row, pip70);
            assertNotNull(why, "6.1 -> 7.0 moved");
            assertContains(why, "pip:cowsay==7.0", "names the new pin");
            assertContains(why, "pip:cowsay==6.1", "names the old pin");

            CliDependency npm = new CliDependency("tsc", "npm:typescript@5.5.0", null, null,
                    "tsc", true, Map.of());
            CliLock.Entry npmRow = new CliLock.Entry("npm", "typescript", "5.4.5",
                    "npm:typescript@5.4.5", null, List.of("unit-a"), "t");
            assertNotNull(CliPin.moved(npmRow, npm), "legacy npm row: version moved");

            CliDependency brew = new CliDependency("fd", "brew:fd", null, null, "fd", false,
                    Map.of());
            CliLock.Entry brewRow = new CliLock.Entry("brew", "fd", null, "brew:old-fd",
                    null, List.of("unit-a"), "t");
            assertEquals(null, CliPin.moved(brewRow, brew),
                    "brew tracks its own formula version; not this check's business");
            assertTrue(CliPin.governs(pip61) && CliPin.governs(npm), "pip and npm governed");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- fixture

    /** install + record + save: the same three steps CliInstallRecorder.run takes. */
    private static InstallOutcome install(SkillStore store, CliDependency dep) throws Exception {
        InstallerRegistry registry = new InstallerRegistry();
        InstallOutcome outcome = registry.installOne(dep, store, "unit-a");
        CliLock lock = CliLock.load(store);
        CliInstallRecorder.record(lock, registry, dep, store, "unit-a");
        lock.save(store);
        return outcome;
    }

    private static String installedVersion(SkillStore store) throws Exception {
        String body = Files.readString(store.cliBinDir().resolve("tool"));
        return body.contains("echo 2.0") ? "2.0" : body.contains("echo 1.0") ? "1.0" : body;
    }

    /** Rewrite the lock as a pre-#404 skill-manager would have written it. */
    private static void stripPin(SkillStore store) throws Exception {
        Path file = store.root().resolve(CliLock.FILENAME);
        String text = Files.readString(file);
        Files.writeString(file, text.replaceAll("(?m)^declared_pin = .*\\R", ""));
        assertEquals(null, CliLock.load(store).get("tar", "tool").declaredPin(),
                "precondition: the row is legacy");
    }

    private static SkillStore newStore(String prefix) throws Exception {
        SkillStore store = new SkillStore(Files.createTempDirectory(prefix).toRealPath());
        store.init();
        return store;
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

        CliDependency tarDep(String version) {
            return tarDep(version, "/v" + version.charAt(0));
        }

        /** No on_path, so presence is decided by bin/cli/&lt;name&gt; — tar's own rule. */
        CliDependency tarDep(String version, String path) {
            return new CliDependency("tool", "tar:tool", version, null, null, true,
                    Map.of("any", new CliDependency.InstallTarget(
                            url(path), null, "tool", List.of(), null)));
        }

        @Override public void close() { server.stop(0); }
    }
}
