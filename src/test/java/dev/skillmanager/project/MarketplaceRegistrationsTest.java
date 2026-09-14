package dev.skillmanager.project;

import dev.skillmanager._lib.test.Tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * OHV-6 (#352): reading a home's agent marketplace registrations, judging them
 * against the home's identity, and editing exactly one entry.
 *
 * <p>Every fixture is a temp directory; nothing here reads or writes the
 * operator's {@code ~/.claude} or {@code ~/.codex}.
 */
public final class MarketplaceRegistrationsTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("MarketplaceRegistrationsTest");

        suite.test("the root's DEF-OHV-005 config: exactly the other home's entries are FOREIGN", () -> {
            Path tmp = Files.createTempDirectory("mr-root-");
            Path rootMp = tmp.resolve("home/.skill-manager/plugin-marketplace");
            Path cdc = tmp.resolve("cdc/.skill-manager/plugin-marketplace");
            Path tla = tmp.resolve("tla/.skill-manager/plugin-marketplace");
            Path claude = tmp.resolve("home/.claude");
            Path codex = tmp.resolve("home/.codex");
            write(claude.resolve("plugins/known_marketplaces.json"), """
                    {
                      "skill-manager": {"source": {"source": "directory", "path": "%1$s"}},
                      "skill-manager-919db26e": {"source": {"source": "directory", "path": "%2$s"}},
                      "skill-manager-0fd46eec": {"source": {"source": "directory", "path": "%3$s"}}
                    }""".formatted(rootMp, cdc, tla));
            write(claude.resolve("settings.json"), """
                    {
                      "enabledPlugins": {
                        "skt@skill-manager-919db26e": true,
                        "skt@skill-manager": true,
                        "jdtls-lsp@claude-plugins-official": true
                      }
                    }""");
            write(codex.resolve("config.toml"), """
                    [plugins."skt@skill-manager"]
                    enabled = true

                    [plugins."skt@skill-manager-919db26e"]
                    enabled = true

                    [marketplaces.skill-manager]
                    source_type = "local"
                    source = "%1$s"

                    [marketplaces.skill-manager-919db26e]
                    source_type = "local"
                    source = "%2$s"
                    """.formatted(rootMp, cdc));

            var judged = MarketplaceRegistrations.judge(
                    MarketplaceRegistrations.read(claude, codex), "skill-manager", rootMp);

            List<String> subjects = judged.stream()
                    .map(j -> j.kind() + " " + j.entry().subject(tmp.resolve("home"))).sorted().toList();
            assertEquals(List.of(
                    "FOREIGN .claude/plugins/known_marketplaces.json:known_marketplaces.skill-manager-919db26e",
                    "FOREIGN .claude/settings.json:enabledPlugins.skt@skill-manager-919db26e",
                    "FOREIGN .codex/config.toml:marketplaces.skill-manager-919db26e",
                    "FOREIGN .codex/config.toml:plugins.skt@skill-manager-919db26e"), subjects,
                    "the root's own entries and the UNENABLED tla-spec-dev registration are not findings");
        });

        suite.test("shape 1: this path under another name, registration and enablement both", () -> {
            Path tmp = Files.createTempDirectory("mr-s1-");
            Path mp = tmp.resolve(".skill-manager/plugin-marketplace");
            Path claude = tmp.resolve(".claude");
            write(claude.resolve("plugins/known_marketplaces.json"),
                    "{\"skill-manager\": {\"source\": {\"source\": \"directory\", \"path\": \"" + mp + "\"}}}");
            write(claude.resolve("settings.json"), "{\"enabledPlugins\": {\"skt@skill-manager\": true}}");

            var judged = MarketplaceRegistrations.judge(
                    MarketplaceRegistrations.read(claude, null), "skill-manager-0fd46eec", mp);

            assertEquals(2, judged.size(), "both entries: " + judged);
            assertTrue(judged.stream().allMatch(j -> j.kind() == MarketplaceRegistrations.Disagreement.UNDER_ANOTHER_NAME),
                    "both UNDER_ANOTHER_NAME: " + judged);
            assertContains(judged.get(0).detail(), "expected skill-manager-0fd46eec at " + mp,
                    "the detail names the identity it expected");
            assertContains(judged.get(0).detail(), "found skill-manager", "and what it found");
        });

        suite.test("shape 1 repair re-points the registration and migrates the enablement in place", () -> {
            Path tmp = Files.createTempDirectory("mr-migrate-");
            Path mp = tmp.resolve(".skill-manager/plugin-marketplace");
            Path claude = tmp.resolve(".claude");
            write(claude.resolve("plugins/known_marketplaces.json"),
                    "{\"a\": {\"x\": 1}, \"skill-manager\": {\"source\": {\"source\": \"directory\", \"path\": \""
                            + mp + "\"}}, \"z\": {}}");
            write(claude.resolve("settings.json"),
                    "{\"enabledPlugins\": {\"skt@skill-manager\": true, \"k@official\": true}}");
            var entries = MarketplaceRegistrations.read(claude, null);
            var reg = entries.stream().filter(e -> e.key().equals("skill-manager")).findFirst().orElseThrow();

            MarketplaceRegistrations.migrateToIdentity(reg, entries, "skill-manager-abcd1234", mp);

            String known = Files.readString(claude.resolve("plugins/known_marketplaces.json"));
            assertTrue(known.indexOf("\"a\"") < known.indexOf("\"skill-manager-abcd1234\"")
                            && known.indexOf("\"skill-manager-abcd1234\"") < known.indexOf("\"z\""),
                    "renamed in place, order kept: " + known);
            assertContains(known, "\"z\": {}", "Claude's layout: `\"k\": v`, empty object {}");
            String settings = Files.readString(claude.resolve("settings.json"));
            assertContains(settings, "\"skt@skill-manager-abcd1234\": true", "the enablement migrates with it");
            assertContains(settings, "\"k@official\": true", "an unrelated enablement stays");
            assertTrue(MarketplaceRegistrations.judge(MarketplaceRegistrations.read(claude, null),
                    "skill-manager-abcd1234", mp).isEmpty(), "and nothing disagrees afterwards");
        });

        suite.test("shape 2: a registered name that CONTAINS the identity is not the identity", () -> {
            Path tmp = Files.createTempDirectory("mr-s2-");
            Path mp = tmp.resolve("home/.skill-manager/plugin-marketplace");
            Path other = tmp.resolve("other/.skill-manager/plugin-marketplace");
            Path claude = tmp.resolve("home/.claude");
            write(claude.resolve("plugins/known_marketplaces.json"),
                    "{\"skill-manager-919db26e\": {\"source\": {\"source\": \"directory\", \"path\": \"" + other + "\"}}}");
            write(claude.resolve("settings.json"), "{\"enabledPlugins\": {\"skt@skill-manager\": true}}");

            var judged = MarketplaceRegistrations.judge(
                    MarketplaceRegistrations.read(claude, null), "skill-manager", mp);

            assertEquals(1, judged.size(), "only the enablement: " + judged);
            assertEquals(MarketplaceRegistrations.Disagreement.IDENTITY_UNREGISTERED, judged.get(0).kind(), "kind");
            assertContains(judged.get(0).detail(), "merely contains skill-manager", "names the trap");
        });

        suite.test("shape 2 repair registers the identity the way `marketplace add` does", () -> {
            Path tmp = Files.createTempDirectory("mr-register-");
            Path mp = tmp.resolve(".skill-manager/plugin-marketplace");
            Path claude = tmp.resolve(".claude");
            write(claude.resolve("settings.json"), "{\"enabledPlugins\": {\"skt@skill-manager-abcd1234\": true}}");
            Path codex = tmp.resolve(".codex");
            write(codex.resolve("config.toml"), "[plugins.\"skt@skill-manager-abcd1234\"]\nenabled = true\n");
            var judged = MarketplaceRegistrations.judge(
                    MarketplaceRegistrations.read(claude, codex), "skill-manager-abcd1234", mp);
            assertEquals(2, judged.size(), "Claude and Codex each: " + judged);

            for (var j : judged) MarketplaceRegistrations.registerIdentity(j.entry(), "skill-manager-abcd1234", mp);

            assertTrue(MarketplaceRegistrations.judge(MarketplaceRegistrations.read(claude, codex),
                    "skill-manager-abcd1234", mp).isEmpty(), "registered in both");
            assertContains(Files.readString(codex.resolve("config.toml")),
                    "[marketplaces.skill-manager-abcd1234]\nsource_type = \"local\"\nsource = \"" + mp + "\"",
                    "codex's own table shape");
            assertContains(Files.readString(claude.resolve("settings.json")), "\"extraKnownMarketplaces\"",
                    "declared in settings as well as known_marketplaces.json");
        });

        suite.test("TOML: cutting one table keeps every other byte, comments and sub-tables included", () -> {
            String text = """
                    model = "gpt-5"  # mine

                    [marketplaces.skill-manager]
                    source = "/x"

                    [marketplaces.skill-manager.extra]
                    k = 1

                    [marketplaces.skill-manager-2]
                    source = "/y"
                    # trailing comment
                    """;
            String cut = MarketplaceRegistrations.cutTable(text, "marketplaces", "skill-manager");
            assertEquals("""
                    model = "gpt-5"  # mine

                    [marketplaces.skill-manager-2]
                    source = "/y"
                    # trailing comment
                    """, cut, "a prefix-sharing name is a different table");
            assertEquals(text, MarketplaceRegistrations.cutTable(text, "marketplaces", "absent"),
                    "an absent table changes nothing");
            assertEquals("[plugins.\"skt@new\"]\nenabled = true\n",
                    MarketplaceRegistrations.renameTable("[plugins.\"skt@old\"]\nenabled = true\n",
                            "plugins", "skt@old", "skt@new"), "a quoted key is renamed quoted");
        });

        suite.test("paths compare as directories, macOS aliases included; a skill-manager marketplace by name or dir", () -> {
            Path tmp = Files.createTempDirectory("mr-path-").toRealPath();
            if (tmp.toString().startsWith("/private/var/")) {
                assertTrue(MarketplaceRegistrations.samePath(tmp.toString().substring("/private".length()), tmp),
                        "/var and /private/var are one directory");
            }
            assertFalse(MarketplaceRegistrations.samePath("/a/b-2", Path.of("/a/b")), "a prefix is not a path");
            assertTrue(MarketplaceRegistrations.skillManagerMarketplace("skill-manager-1", null), "by name");
            assertTrue(MarketplaceRegistrations.skillManagerMarketplace("custom", "/h/.skill-manager/plugin-marketplace"),
                    "by directory");
            assertFalse(MarketplaceRegistrations.skillManagerMarketplace("skill-managerx", "/elsewhere"),
                    "a name that only starts with the letters is not one");
        });

        return suite.runAll();
    }

    private static void write(Path file, String content) throws java.io.IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
