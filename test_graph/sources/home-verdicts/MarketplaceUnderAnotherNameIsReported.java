///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeVerdictsSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;
import java.util.List;

/**
 * #352 shape 1 (OHV-6): Claude registers this home's marketplace DIRECTORY under
 * a name that is not its identity. Measured in tla-spec-dev's checkout:
 * {@code .claude/plugins/known_marketplaces.json} and
 * {@code extraKnownMarketplaces} name the path {@code skill-manager}, three plugins
 * are enabled {@code @skill-manager}, and every sync fails with
 * {@code Marketplace 'skill-manager-0fd46eec' not found}.
 *
 * <p>Planted in the SUBJECT's own {@code .claude} (its structural agent dir under
 * the scratch root) — never the operator's, never the graph sandbox's.
 * {@code home repair} names {@code MARKETPLACE_REGISTERED_UNDER_ANOTHER_NAME},
 * {@code home verify} exits 1 naming it, and {@code --fix} re-points the
 * registration AND the enablement at the identity together, leaving an unrelated
 * marketplace and plugin exactly as they were.
 */
public class MarketplaceUnderAnotherNameIsReported {

    static final NodeSpec SPEC = NodeSpec.of("home.verdicts.marketplace.under.another.name")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(HomeVerdictsSupport.FIXTURE)
            .tags("home", "verdicts", "marketplace", "ohv-6")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> HomeVerdictsSupport.runShape(ctx, SPEC,
                new HomeVerdictsSupport.Shape("marketplace-under-another-name",
                        "MARKETPLACE_REGISTERED_UNDER_ANOTHER_NAME", 1, true),
                (Path subject, Path neighbour) -> {
                    String mp = HomeVerdictsSupport.marketplaceRoot(subject);
                    HomeVerdictsSupport.writeAgentFile(subject, ".claude/plugins/known_marketplaces.json", """
                            {
                              "claude-plugins-official": {
                                "source": {
                                  "source": "github",
                                  "repo": "anthropics/claude-plugins-official"
                                },
                                "installLocation": "/nowhere/claude-plugins-official",
                                "lastUpdated": "2026-09-13T00:00:00.000Z"
                              },
                              "skill-manager": {
                                "source": {
                                  "source": "directory",
                                  "path": %1$s
                                },
                                "installLocation": %1$s,
                                "lastUpdated": "2026-09-13T00:00:00.000Z"
                              }
                            }""".formatted(HomeVerdictsSupport.jsonString(mp)));
                    HomeVerdictsSupport.writeAgentFile(subject, ".claude/settings.json", """
                            {
                              "enabledPlugins": {
                                "hv-plugin@skill-manager": true,
                                "other@claude-plugins-official": true
                              },
                              "extraKnownMarketplaces": {
                                "skill-manager": {
                                  "source": {
                                    "source": "directory",
                                    "path": %s
                                  }
                                }
                              }
                            }""".formatted(HomeVerdictsSupport.jsonString(mp)));
                    return ".claude/plugins/known_marketplaces.json:known_marketplaces.skill-manager";
                },
                (ctx2, subject, rel) -> {
                    String id = HomeVerdictsSupport.identityOf(subject);
                    var known = HomeVerdictsSupport.readJson(subject, ".claude/plugins/known_marketplaces.json");
                    var settings = HomeVerdictsSupport.readJson(subject, ".claude/settings.json");
                    return List.of(
                            new HomeVerdictsSupport.Check("the_registration_is_re_pointed_at_the_identity",
                                    known.has(id) && !known.has("skill-manager")
                                            && known.path(id).path("source").path("path").asText()
                                                    .equals(HomeVerdictsSupport.marketplaceRoot(subject)),
                                    "known_marketplaces.json after --fix: " + known),
                            new HomeVerdictsSupport.Check("the_enabled_plugin_migrates_with_it",
                                    settings.path("enabledPlugins").has("hv-plugin@" + id)
                                            && !settings.path("enabledPlugins").has("hv-plugin@skill-manager")
                                            && settings.path("extraKnownMarketplaces").has(id),
                                    "settings.json after --fix: " + settings),
                            new HomeVerdictsSupport.Check("an_unrelated_marketplace_and_plugin_are_untouched",
                                    known.has("claude-plugins-official")
                                            && settings.path("enabledPlugins").has("other@claude-plugins-official"),
                                    "after --fix: " + known + " / " + settings));
                }));
    }
}
