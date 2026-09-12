package dev.skillmanager.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.model.UnitKind;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Contract for the skill-manager-owned plugin marketplace generator.
 * The harness CLIs ({@code claude}, {@code codex}) only know how to
 * install plugins from a configured marketplace, so we wrap every
 * locally-installed plugin in a single shared marketplace dir at
 * {@code <store>/plugin-marketplace/}.
 *
 * <p>Sweep: empty store, single plugin, multiple plugins, plugin
 * removal (regenerate prunes the stale symlink + manifest entry), and
 * skill-only stores (manifest with empty plugins[] but no failures).
 */
public final class PluginMarketplaceTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("PluginMarketplaceTest");

        suite.test("empty store → manifest has 0 plugins + dirs created", () -> {
            TestHarness h = TestHarness.create();
            PluginMarketplace mp = new PluginMarketplace(h.store());
            List<String> names = mp.regenerate().pluginNames();

            assertEquals(0, names.size(), "no plugins listed");
            assertTrue(Files.isRegularFile(mp.manifestPath()), "manifest written");
            assertTrue(Files.isDirectory(mp.pluginsLinkDir()), "plugins/ dir created");
            JsonNode manifest = readManifest(mp.manifestPath());
            assertEquals(mp.name(), manifest.get("name").asText(), "marketplace name");
            assertTrue(manifest.get("name").asText().startsWith(PluginMarketplace.NAME),
                    "per-home name keeps the skill-manager prefix");
            assertEquals(0, manifest.get("plugins").size(), "plugins[] empty");
        });

        suite.test("one plugin → symlink + manifest entry", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("repo-intel", UnitKind.PLUGIN);

            PluginMarketplace mp = new PluginMarketplace(h.store());
            List<String> names = mp.regenerate().pluginNames();

            assertEquals(1, names.size(), "one plugin listed");
            assertEquals("repo-intel", names.get(0), "plugin name");
            Path link = mp.pluginsLinkDir().resolve("repo-intel");
            assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "symlink created");

            JsonNode manifest = readManifest(mp.manifestPath());
            JsonNode entry = manifest.get("plugins").get(0);
            assertEquals("repo-intel", entry.get("name").asText(), "manifest name");
            assertEquals("./plugins/repo-intel", entry.get("source").asText(), "manifest source path");
        });

        suite.test("multiple plugins sorted alphabetically", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("zebra-plugin", UnitKind.PLUGIN);
            h.scaffoldUnitDir("alpha-plugin", UnitKind.PLUGIN);
            h.scaffoldUnitDir("middle-plugin", UnitKind.PLUGIN);

            PluginMarketplace mp = new PluginMarketplace(h.store());
            List<String> names = mp.regenerate().pluginNames();

            assertEquals(3, names.size(), "three plugins");
            assertEquals("alpha-plugin", names.get(0), "alphabetically first");
            assertEquals("middle-plugin", names.get(1), "alphabetically middle");
            assertEquals("zebra-plugin", names.get(2), "alphabetically last");
        });

        suite.test("regenerate after removing a plugin prunes its symlink", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("alpha", UnitKind.PLUGIN);
            h.scaffoldUnitDir("beta", UnitKind.PLUGIN);
            PluginMarketplace mp = new PluginMarketplace(h.store());
            mp.regenerate().pluginNames();
            assertTrue(Files.exists(mp.pluginsLinkDir().resolve("alpha"), LinkOption.NOFOLLOW_LINKS),
                    "alpha symlink there pre-removal");

            // Simulate removal: drop alpha from the store, regenerate.
            dev.skillmanager.shared.util.Fs.deleteRecursive(
                    h.store().unitDir("alpha", UnitKind.PLUGIN));
            List<String> names = mp.regenerate().pluginNames();

            assertEquals(1, names.size(), "only beta remains");
            assertFalse(Files.exists(mp.pluginsLinkDir().resolve("alpha"), LinkOption.NOFOLLOW_LINKS),
                    "alpha symlink pruned");
            assertTrue(Files.exists(mp.pluginsLinkDir().resolve("beta"), LinkOption.NOFOLLOW_LINKS),
                    "beta symlink kept");
        });

        suite.test("skills-only store → empty plugin list, no spurious symlinks", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("plain-skill", UnitKind.SKILL);

            PluginMarketplace mp = new PluginMarketplace(h.store());
            List<String> names = mp.regenerate().pluginNames();

            assertEquals(0, names.size(), "skills don't show up in plugin marketplace");
            JsonNode manifest = readManifest(mp.manifestPath());
            assertEquals(0, manifest.get("plugins").size(), "manifest plugins[] still empty");
        });

        suite.test("regenerate is idempotent — second call leaves disk identical", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("a", UnitKind.PLUGIN);
            h.scaffoldUnitDir("b", UnitKind.PLUGIN);

            PluginMarketplace mp = new PluginMarketplace(h.store());
            mp.regenerate().pluginNames();
            String firstManifest = Files.readString(mp.manifestPath());
            mp.regenerate().pluginNames();
            String secondManifest = Files.readString(mp.manifestPath());

            assertEquals(firstManifest, secondManifest, "manifest byte-identical across regenerate calls");
        });

        suite.test("cleanupLegacyAgentPluginEntries removes only named symlinks", () -> {
            TestHarness h = TestHarness.create();
            Path agentDir = Files.createTempDirectory("legacy-agent-plugins-");
            // Pretend an old-version skill-manager dropped two symlinks
            // at <agentDir>/<name> using the wrong namespace.
            h.scaffoldUnitDir("plug-a", UnitKind.PLUGIN);
            h.scaffoldUnitDir("plug-b", UnitKind.PLUGIN);
            Files.createSymbolicLink(agentDir.resolve("plug-a"),
                    h.store().unitDir("plug-a", UnitKind.PLUGIN));
            Files.createSymbolicLink(agentDir.resolve("plug-b"),
                    h.store().unitDir("plug-b", UnitKind.PLUGIN));
            // Plus one symlink the harness installed itself — must NOT
            // be touched.
            Files.createDirectories(agentDir.resolve("third-party"));

            List<Path> removed = PluginMarketplace.cleanupLegacyAgentPluginEntries(
                    agentDir, List.of("plug-a", "plug-b"), h.store());

            assertFalse(Files.exists(agentDir.resolve("plug-a"), LinkOption.NOFOLLOW_LINKS),
                    "skill-manager-managed plug-a removed");
            assertFalse(Files.exists(agentDir.resolve("plug-b"), LinkOption.NOFOLLOW_LINKS),
                    "skill-manager-managed plug-b removed");
            assertTrue(Files.exists(agentDir.resolve("third-party"), LinkOption.NOFOLLOW_LINKS),
                    "harness-installed third-party untouched");
            assertEquals(2, removed.size(),
                    "and it REPORTS what it deleted — silence is half of #311");
        });

        // #311. CODEX_HOME and GEMINI_HOME name the config directory itself,
        // not its parent, so CodexAgent.pluginsDir() is `$CODEX_HOME/plugins`.
        // Point either variable at a Skill Manager home and that expression is
        // the STORE's own plugins/ — and this method used to delete every
        // installed plugin out of it while the install reported success.
        suite.test("#311: the store's own plugins/ is refused, not cleaned", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("plug-a", UnitKind.PLUGIN);
            h.scaffoldUnitDir("plug-b", UnitKind.PLUGIN);
            Path storePlugins = h.store().pluginsDir();
            assertTrue(Files.isDirectory(storePlugins.resolve("plug-a")), "installed before");

            List<Path> removed = PluginMarketplace.cleanupLegacyAgentPluginEntries(
                    storePlugins, List.of("plug-a", "plug-b"), h.store());

            assertTrue(Files.isDirectory(storePlugins.resolve("plug-a")),
                    "plug-a survives — the store's plugins/ is not an agent's");
            assertTrue(Files.isDirectory(storePlugins.resolve("plug-b")), "and so does plug-b");
            assertEquals(0, removed.size(), "nothing was removed");
        });

        suite.test("#311: the store's own skills/ is refused too — the second face", () -> {
            // The same misconfiguration reaches TWO write paths. The plugin
            // cleanup deletes; the agent projection replaces each installed
            // unit with a symlink pointing at its own path. Both ask the store
            // the same question now, so both are covered by one guard.
            TestHarness h = TestHarness.create();
            assertTrue(h.store().ownsUnitDirectory(h.store().skillsDir()),
                    "the store owns its skills/");
            assertTrue(h.store().ownsUnitDirectory(h.store().pluginsDir()),
                    "and its plugins/");
            assertTrue(h.store().ownsUnitDirectory(h.store().root()),
                    "and its root");
            assertFalse(h.store().ownsUnitDirectory(
                            h.store().root().resolve(".codex").resolve("skills")),
                    "but NOT the default agent location inside the home — a guard "
                            + "that broad would disable projection in every ordinary home");
        });

        suite.test("#311: the other three store unit directories are refused too", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("plug-a", UnitKind.PLUGIN);
            for (Path owned : List.of(h.store().root(), h.store().skillsDir(),
                    h.store().docsDir(), h.store().harnessesDir())) {
                Files.createDirectories(owned);
                Files.createDirectories(owned.resolve("plug-a"));
                assertEquals(0, PluginMarketplace.cleanupLegacyAgentPluginEntries(
                                owned, List.of("plug-a"), h.store()).size(),
                        owned + " is the store's own, not an agent's");
                assertTrue(Files.isDirectory(owned.resolve("plug-a")), "still there: " + owned);
            }
        });

        // THE COMPANION. Without it, "nothing was removed" is also what a
        // cleanup that stopped working entirely would report — and the
        // DEFAULT agent directory does live inside the home
        // (<home>/.codex/plugins when CODEX_HOME is unset), so a refusal
        // written as "anywhere under the home" would disable the cleanup in
        // every ordinary home.
        suite.test("#311 companion: an agent dir INSIDE the home is still cleaned", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("plug-a", UnitKind.PLUGIN);
            Path insideHome = h.store().root().resolve(".codex").resolve("plugins");
            Files.createDirectories(insideHome);
            Files.createSymbolicLink(insideHome.resolve("plug-a"),
                    h.store().unitDir("plug-a", UnitKind.PLUGIN));

            List<Path> removed = PluginMarketplace.cleanupLegacyAgentPluginEntries(
                    insideHome, List.of("plug-a"), h.store());

            assertEquals(1, removed.size(), "the default agent location is still cleaned");
            assertFalse(Files.exists(insideHome.resolve("plug-a"), LinkOption.NOFOLLOW_LINKS),
                    "the stale entry is gone");
            assertTrue(Files.isDirectory(h.store().pluginsDir().resolve("plug-a")),
                    "and the unit the symlink POINTED AT is untouched");
        });

        suite.test("per-home marketplace identity: non-root store gets a suffixed, stable name", () -> {
            TestHarness h = TestHarness.create();
            PluginMarketplace mp = new PluginMarketplace(h.store());
            String name = mp.name();
            assertTrue(name.startsWith(PluginMarketplace.NAME + "-"),
                    "fixture store is not the operator root, so the name is suffixed: " + name);
            assertEquals(name, new PluginMarketplace(h.store()).name(), "stable across instances");
        });

        suite.test("per-home marketplace identity: two stores never share a name", () -> {
            TestHarness h1 = TestHarness.create();
            TestHarness h2 = TestHarness.create();
            String n1 = new PluginMarketplace(h1.store()).name();
            String n2 = new PluginMarketplace(h2.store()).name();
            assertTrue(!n1.equals(n2),
                    "two homes registering under one name is the codex collision this fixes: "
                    + n1 + " vs " + n2);
        });

        // ------------------------------------------------------------- ARTI-17

        suite.test("regeneration records a RESOLVED input fingerprint per entry", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("repo-intel", UnitKind.PLUGIN);
            PluginMarketplace mp = new PluginMarketplace(h.store());
            mp.regenerate();

            MarketplaceInputs inputs = MarketplaceInputs.read(mp.root()).orElseThrow(
                    () -> new AssertionError("no " + MarketplaceInputs.FILENAME + " sidecar"));
            assertEquals(mp.name(), inputs.marketplaceName(), "sidecar names this home's marketplace");
            MarketplaceInputs.Entry entry = inputs.plugin("repo-intel").orElseThrow(
                    () -> new AssertionError("no row for repo-intel"));
            assertEquals("./plugins/repo-intel", entry.source(), "source matches the manifest's");
            Fingerprint fp = entry.fingerprint().orElseThrow(
                    () -> new AssertionError("row records neither a digest nor a gap"));
            assertTrue(fp.present(), "a digest, not a gap");
            assertEquals(Fingerprint.Kind.RESOLVED, fp.kind(),
                    "the digest covers the plugin's bytes, so the producer asserts resolved");
            assertEquals(MarketplaceInputs.SCHEME, entry.inputFingerprintScheme(),
                    "the scheme is named, so a later scheme cannot silently collide with it");

            // The manifest the harness CLIs read is untouched: an unrecognised
            // key there is a bet on their leniency whose downside is every
            // plugin in the home becoming unloadable.
            JsonNode manifestEntry = readManifest(mp.manifestPath()).get("plugins").get(0);
            assertEquals(2, manifestEntry.size(),
                    "marketplace.json entry still carries name+source only");
        });

        suite.test("the entry fingerprint moves when the plugin's bytes move", () -> {
            // The whole claim of the `resolved` grade. A digest over the
            // declared plugin SET would be identical across this edit, which is
            // exactly why that grade is `declared` and this one is not.
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("repo-intel", UnitKind.PLUGIN);
            PluginMarketplace mp = new PluginMarketplace(h.store());
            mp.regenerate();
            String before = MarketplaceInputs.read(mp.root()).orElseThrow()
                    .plugin("repo-intel").orElseThrow().inputFingerprint();

            mp.regenerate();
            assertEquals(before, MarketplaceInputs.read(mp.root()).orElseThrow()
                            .plugin("repo-intel").orElseThrow().inputFingerprint(),
                    "regenerating with nothing changed must not move the digest");

            Files.writeString(h.store().unitDir("repo-intel", UnitKind.PLUGIN).resolve("NEW.md"),
                    "added after generation\n");
            mp.regenerate();
            assertFalse(before.equals(MarketplaceInputs.read(mp.root()).orElseThrow()
                            .plugin("repo-intel").orElseThrow().inputFingerprint()),
                    "adding a file to the plugin must move the entry's digest");
        });

        suite.test("a plugin's row leaves with its entry", () -> {
            // A row that outlives its entry is a record claiming an artifact
            // this home does not have — the "second copy that can disagree with
            // the disk" the artifact model exists to refuse.
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("alpha-plugin", UnitKind.PLUGIN);
            h.scaffoldUnitDir("zebra-plugin", UnitKind.PLUGIN);
            PluginMarketplace mp = new PluginMarketplace(h.store());
            mp.regenerate();
            assertEquals(2, MarketplaceInputs.read(mp.root()).orElseThrow().plugins().size(),
                    "both rows recorded");

            dev.skillmanager.shared.util.Fs.deleteRecursive(
                    h.store().unitDir("zebra-plugin", UnitKind.PLUGIN));
            mp.regenerate();

            MarketplaceInputs after = MarketplaceInputs.read(mp.root()).orElseThrow();
            assertEquals(1, after.plugins().size(), "the uninstalled plugin's row is gone");
            assertTrue(after.plugin("zebra-plugin").isEmpty(), "and it is that plugin's row");
        });

        suite.test("a symlinked store dir is digested by its BYTES, not its path string", () -> {
            // ChildHomeMaterializer.plainView emits ONE LINK entry when its root
            // is a symlink and never descends, and Files.isDirectory follows
            // links so it does not notice. Without resolving the root first the
            // digest covers the target PATH STRING while the grade still says
            // `resolved` — a resolved grade that is wrong about bytes, which is
            // the one thing it must never be.
            TestHarness h = TestHarness.create();
            Path real = Files.createTempDirectory("real-plugin-");
            Files.createDirectories(real.resolve(".claude-plugin"));
            Files.writeString(real.resolve(".claude-plugin/plugin.json"), "{ \"name\": \"linked\" }\n");
            Files.writeString(real.resolve("body.md"), "original\n");
            Path storeDir = h.store().unitDir("linked", UnitKind.PLUGIN);
            Files.createDirectories(storeDir.getParent());
            Files.createSymbolicLink(storeDir, real);

            Fingerprint before = MarketplaceInputs.fingerprintOf(
                    "linked", "./plugins/linked", "../../plugins/linked", storeDir);
            assertEquals(Fingerprint.Kind.RESOLVED, before.kind(), "graded resolved");

            // Same byte length, so nothing but the content itself differs.
            Files.writeString(real.resolve("body.md"), "modified\n");
            Fingerprint after = MarketplaceInputs.fingerprintOf(
                    "linked", "./plugins/linked", "../../plugins/linked", storeDir);
            assertFalse(before.value().equals(after.value()),
                    "editing the real bytes behind the link must move the digest");
        });

        suite.test("a link that does not resolve is a graded GAP, not a resolved digest", () -> {
            TestHarness h = TestHarness.create();
            Path storeDir = h.store().unitDir("dangling", UnitKind.PLUGIN);
            Files.createDirectories(storeDir.getParent());
            Files.createSymbolicLink(storeDir, storeDir.resolveSibling("nowhere-at-all"));
            Fingerprint fp = MarketplaceInputs.fingerprintOf(
                    "dangling", "./plugins/dangling", "../../plugins/dangling", storeDir);
            assertFalse(fp.present(), "no digest when the link does not resolve");
        });

        suite.test("a no-op regeneration leaves the INPUTS RECORD byte-identical too", () -> {
            // The idempotency case above asserts the MANIFEST is byte-identical
            // across two regenerations — and it passed while the sidecar changed
            // on every pass, because `generatedAt` is a wall clock. A record
            // whose whole purpose is answering "did anything change" must not
            // change every time it is asked; that is this epic's own defect
            // reproduced inside its fix. Asserted on BYTES rather than through a
            // comparison method, so it holds against what the file really says.
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("a", UnitKind.PLUGIN);
            h.scaffoldUnitDir("b", UnitKind.PLUGIN);
            PluginMarketplace mp = new PluginMarketplace(h.store());
            mp.regenerate();
            Path sidecar = MarketplaceInputs.file(mp.root());
            String first = Files.readString(sidecar);

            mp.regenerate();
            assertEquals(first, Files.readString(sidecar),
                    "a regeneration that changed nothing must not rewrite the record");

            // ...and a real change still lands.
            Files.writeString(h.store().unitDir("a", UnitKind.PLUGIN).resolve("NEW.md"), "x\n");
            mp.regenerate();
            assertFalse(first.equals(Files.readString(sidecar)),
                    "but a moved plugin must still be recorded");
        });

        suite.test("recording the inputs cannot fail the regeneration it describes", () -> {
            // Evidence about an operation must not be able to fail that
            // operation. A directory where the file goes makes the write throw
            // reliably, without depending on file permissions.
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("repo-intel", UnitKind.PLUGIN);
            PluginMarketplace mp = new PluginMarketplace(h.store());
            mp.regenerate();
            Path sidecar = MarketplaceInputs.file(mp.root());
            Files.deleteIfExists(sidecar);
            Files.createDirectories(sidecar);

            List<String> names = mp.regenerate().pluginNames();
            assertEquals(1, names.size(), "the marketplace still regenerated");
            assertEquals(1, readManifest(mp.manifestPath()).get("plugins").size(),
                    "and the manifest it exists to describe is correct");
        });

        return suite.runAll();
    }

    private static JsonNode readManifest(Path manifestPath) throws Exception {
        return new ObjectMapper().readTree(Files.readString(manifestPath));
    }
}
