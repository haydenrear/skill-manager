package dev.skillmanager.registry;

import dev.skillmanager._lib.fixtures.ContainedSkillSpec;
import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-13 contract: {@link SkillPackager#pack} on a plugin directory
 * detects {@link SkillPackager.Kind#PLUGIN} via
 * {@code .claude-plugin/plugin.json} and produces a bundle that
 * recreates the plugin byte-for-byte — manifest dir included, contained
 * skills included, but {@code .git} / {@code .DS_Store} excluded.
 */
public final class PublishDetectsPluginTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("PublishDetectsPluginTest");

        suite.test("plugin dir → detectKind returns PLUGIN", () -> {
            Path tmp = Files.createTempDirectory("publish-plugin-detect-");
            UnitFixtures.scaffoldPlugin(tmp, "widget", DepSpec.empty(),
                    new ContainedSkillSpec("widget-impl", DepSpec.empty()));
            assertEquals(SkillPackager.Kind.PLUGIN, SkillPackager.detectKind(tmp.resolve("widget")),
                    "kind detected as PLUGIN");
        });

        suite.test("plugin bundle includes plugin.json + skill-manager-plugin.toml + contained skills", () -> {
            Path tmp = Files.createTempDirectory("publish-plugin-pack-");
            UnitFixtures.scaffoldPlugin(tmp, "widget", DepSpec.empty(),
                    new ContainedSkillSpec("widget-impl", DepSpec.empty()));
            Path out = Files.createTempDirectory("publish-plugin-out-");
            Path bundle = SkillPackager.pack(tmp.resolve("widget"), out);

            Set<String> entries = readBundleEntries(bundle);

            // Manifest + tooling files at the plugin root.
            assertTrue(entries.contains(".claude-plugin/plugin.json"),
                    ".claude-plugin/plugin.json included (the gating file)");
            assertTrue(entries.contains("skill-manager-plugin.toml"),
                    "skill-manager-plugin.toml included");

            // Contained skill.
            assertTrue(entries.contains("skills/widget-impl/SKILL.md"),
                    "contained skill SKILL.md included");
            assertTrue(entries.contains("skills/widget-impl/skill-manager.toml"),
                    "contained skill skill-manager.toml included");
        });

        suite.test("plugin bundle excludes .git and other dotfiles", () -> {
            Path tmp = Files.createTempDirectory("publish-plugin-exclude-");
            UnitFixtures.scaffoldPlugin(tmp, "widget", DepSpec.empty(),
                    new ContainedSkillSpec("widget-impl", DepSpec.empty()));
            Path pluginRoot = tmp.resolve("widget");

            // Drop in scratch files that should be excluded.
            Files.createDirectories(pluginRoot.resolve(".git"));
            Files.writeString(pluginRoot.resolve(".git/HEAD"), "ref: refs/heads/main\n");
            Files.writeString(pluginRoot.resolve(".DS_Store"), "macos junk");

            Path out = Files.createTempDirectory("publish-plugin-exclude-out-");
            Path bundle = SkillPackager.pack(pluginRoot, out);
            Set<String> entries = readBundleEntries(bundle);

            assertFalse(entries.contains(".git/HEAD"), ".git/HEAD excluded");
            assertFalse(entries.contains(".DS_Store"), ".DS_Store excluded");
            assertTrue(entries.contains(".claude-plugin/plugin.json"),
                    ".claude-plugin/plugin.json still included");
        });

        suite.test("plugin bundle includes top-level .mcp.json (Claude runtime config)", () -> {
            Path tmp = Files.createTempDirectory("publish-plugin-mcp-");
            UnitFixtures.scaffoldPlugin(tmp, "widget", DepSpec.empty(),
                    new ContainedSkillSpec("widget-impl", DepSpec.empty()));
            Path pluginRoot = tmp.resolve("widget");
            Files.writeString(pluginRoot.resolve(".mcp.json"), "{}\n");

            Path out = Files.createTempDirectory("publish-plugin-mcp-out-");
            Path bundle = SkillPackager.pack(pluginRoot, out);
            Set<String> entries = readBundleEntries(bundle);

            assertTrue(entries.contains(".mcp.json"),
                    ".mcp.json included (plugin runtime config)");
        });

        return suite.runAll();
    }

    /**
     * Read tar entries from a {@code .tar.gz} bundle and return their
     * paths as relative strings. Hand-rolled tar reader — the packager
     * also hand-rolls writing, and adding a tar dependency just for
     * testing isn't justified for this surface.
     */
    public static Set<String> readBundleEntries(Path bundle) throws Exception {
        byte[] gz = Files.readAllBytes(bundle);
        Set<String> entries = new LinkedHashSet<>();
        try (var gzIn = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            byte[] header = new byte[512];
            while (true) {
                int read = gzIn.readNBytes(header, 0, 512);
                if (read < 512) break;
                // Empty header = end-of-archive (two zero blocks).
                boolean allZero = true;
                for (byte b : header) { if (b != 0) { allZero = false; break; } }
                if (allZero) break;

                String name = new String(header, 0, indexOfNul(header, 0, 100),
                        StandardCharsets.US_ASCII).trim();
                if (!name.isEmpty()) entries.add(name);

                long size = parseOctal(header, 124, 12);
                long padded = ((size + 511) / 512) * 512;
                long skipped = 0;
                while (skipped < padded) {
                    long n = gzIn.skip(padded - skipped);
                    if (n <= 0) break;
                    skipped += n;
                }
            }
        }
        return entries;
    }

    private static int indexOfNul(byte[] buf, int off, int len) {
        for (int i = off; i < off + len; i++) if (buf[i] == 0) return i - off;
        return len;
    }

    private static long parseOctal(byte[] buf, int off, int len) {
        long n = 0;
        for (int i = off; i < off + len; i++) {
            byte b = buf[i];
            if (b == 0 || b == ' ') break;
            if (b < '0' || b > '7') continue;
            n = n * 8 + (b - '0');
        }
        return n;
    }
}
