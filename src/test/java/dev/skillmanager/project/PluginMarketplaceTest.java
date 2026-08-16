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

            PluginMarketplace.cleanupLegacyAgentPluginEntries(agentDir, List.of("plug-a", "plug-b"));

            assertFalse(Files.exists(agentDir.resolve("plug-a"), LinkOption.NOFOLLOW_LINKS),
                    "skill-manager-managed plug-a removed");
            assertFalse(Files.exists(agentDir.resolve("plug-b"), LinkOption.NOFOLLOW_LINKS),
                    "skill-manager-managed plug-b removed");
            assertTrue(Files.exists(agentDir.resolve("third-party"), LinkOption.NOFOLLOW_LINKS),
                    "harness-installed third-party untouched");
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

        return suite.runAll();
    }

    private static JsonNode readManifest(Path manifestPath) throws Exception {
        return new ObjectMapper().readTree(Files.readString(manifestPath));
    }
}
