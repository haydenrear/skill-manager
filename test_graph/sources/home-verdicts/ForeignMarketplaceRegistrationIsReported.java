///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeVerdictsSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;
import java.util.List;

/**
 * #352 shape 4 (OHV-6), both sides. Codex: {@code [marketplaces.skill-manager]}
 * whose source is ANOTHER home's marketplace, with a plugin enabled from it next
 * to the home's own — measured in 9 homes' {@code .codex/config.toml}, the root's
 * included. Claude (DEF-OHV-005): {@code enabledPlugins} naming another home's
 * marketplace, which the same config registers — the root's
 * {@code skt@skill-manager-919db26e}. Plugins load twice, from two homes.
 *
 * <p>{@code home repair} names {@code FOREIGN_MARKETPLACE_REGISTRATION} on each,
 * {@code home verify} exits 1 naming it, and {@code --fix} removes exactly those
 * entries: the home's own marketplace, its own plugin, an unrelated plugin and the
 * operator's own setting and comment survive byte for byte.
 */
public class ForeignMarketplaceRegistrationIsReported {

    static final NodeSpec SPEC = NodeSpec.of("home.verdicts.foreign.marketplace.registration")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(HomeVerdictsSupport.FIXTURE)
            .tags("home", "verdicts", "marketplace", "ohv-6")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> HomeVerdictsSupport.runShape(ctx, SPEC,
                new HomeVerdictsSupport.Shape("foreign-marketplace-registration",
                        "FOREIGN_MARKETPLACE_REGISTRATION", 1, true),
                (Path subject, Path neighbour) -> {
                    String id = HomeVerdictsSupport.identityOf(subject);
                    String theirs = HomeVerdictsSupport.identityOf(neighbour);
                    HomeVerdictsSupport.writeAgentFile(subject, ".codex/config.toml", """
                            model = "gpt-5"   # the operator's own setting

                            [marketplaces.%1$s]
                            source_type = "local"
                            source = %2$s

                            [marketplaces.skill-manager]
                            source_type = "local"
                            source = %3$s

                            [plugins."hv-plugin@%1$s"]
                            enabled = true

                            [plugins."hv-plugin@skill-manager"]
                            enabled = true

                            [plugins."github@openai-curated"]
                            enabled = true
                            """.formatted(id,
                            HomeVerdictsSupport.jsonString(HomeVerdictsSupport.marketplaceRoot(subject)),
                            HomeVerdictsSupport.jsonString(HomeVerdictsSupport.marketplaceRoot(neighbour))));
                    HomeVerdictsSupport.writeAgentFile(subject, ".claude/plugins/known_marketplaces.json", """
                            {
                              %1$s: {
                                "source": {
                                  "source": "directory",
                                  "path": %2$s
                                },
                                "installLocation": %2$s,
                                "lastUpdated": "2026-09-13T00:00:00.000Z"
                              }
                            }""".formatted(HomeVerdictsSupport.jsonString(theirs),
                            HomeVerdictsSupport.jsonString(HomeVerdictsSupport.marketplaceRoot(neighbour))));
                    HomeVerdictsSupport.writeAgentFile(subject, ".claude/settings.json", """
                            {
                              "enabledPlugins": {
                                %s: true
                              }
                            }""".formatted(HomeVerdictsSupport.jsonString("hv-plugin@" + theirs)));
                    return ".codex/config.toml:marketplaces.skill-manager";
                },
                (ctx2, subject, rel) -> {
                    String id = HomeVerdictsSupport.identityOf(subject);
                    String theirs = HomeVerdictsSupport.identityOf(subject.getParent().getParent()
                            .resolve("neighbour").resolve(".skill-manager"));
                    String codex = HomeVerdictsSupport.readAgentText(subject, ".codex/config.toml");
                    var settings = HomeVerdictsSupport.readJson(subject, ".claude/settings.json");
                    return List.of(
                            new HomeVerdictsSupport.Check("the_foreign_codex_marketplace_and_its_plugin_are_gone",
                                    !codex.contains("[marketplaces.skill-manager]")
                                            && !codex.contains("hv-plugin@skill-manager\""),
                                    "config.toml after --fix:\n" + codex),
                            new HomeVerdictsSupport.Check("the_home_s_own_and_unrelated_codex_entries_survive",
                                    codex.startsWith("model = \"gpt-5\"   # the operator's own setting\n")
                                            && codex.contains("[marketplaces." + id + "]")
                                            && codex.contains("[plugins.\"hv-plugin@" + id + "\"]")
                                            && codex.contains("[plugins.\"github@openai-curated\"]"),
                                    "config.toml after --fix:\n" + codex),
                            new HomeVerdictsSupport.Check("the_claude_enablement_of_another_home_is_gone",
                                    !settings.path("enabledPlugins").has("hv-plugin@" + theirs),
                                    "settings.json after --fix: " + settings));
                }));
    }
}
