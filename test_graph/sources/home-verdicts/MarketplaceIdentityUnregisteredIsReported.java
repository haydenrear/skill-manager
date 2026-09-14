///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeVerdictsSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;
import java.util.List;

/**
 * #352 shape 2 (OHV-6): "already added" was a substring test. The home enables a
 * plugin under its OWN identity, and its Claude config registers only a
 * marketplace whose name CONTAINS that identity ({@code <identity>-old}, at the
 * neighbour's directory). {@code list.stdout().contains(identity)} was true, so the
 * old {@code ensureMarketplaceAdded} skipped the add and the plugin named a
 * marketplace that was not there — the root's {@code skill-manager} inside every
 * {@code skill-manager-<hash>}, planted in a scratch home.
 *
 * <p>{@code home repair} names {@code MARKETPLACE_IDENTITY_UNREGISTERED} on the
 * enablement, {@code home verify} exits 1 naming it, and {@code --fix} registers
 * the identity at this home's directory — in the form {@code claude plugin
 * marketplace add} writes — leaving the unenabled {@code -old} entry alone.
 */
public class MarketplaceIdentityUnregisteredIsReported {

    static final NodeSpec SPEC = NodeSpec.of("home.verdicts.marketplace.identity.unregistered")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(HomeVerdictsSupport.FIXTURE)
            .tags("home", "verdicts", "marketplace", "ohv-6")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> HomeVerdictsSupport.runShape(ctx, SPEC,
                new HomeVerdictsSupport.Shape("marketplace-identity-unregistered",
                        "MARKETPLACE_IDENTITY_UNREGISTERED", 1, true),
                (Path subject, Path neighbour) -> {
                    String id = HomeVerdictsSupport.identityOf(subject);
                    HomeVerdictsSupport.writeManifest(subject, id);
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
                            }""".formatted(HomeVerdictsSupport.jsonString(id + "-old"),
                            HomeVerdictsSupport.jsonString(HomeVerdictsSupport.marketplaceRoot(neighbour))));
                    HomeVerdictsSupport.writeAgentFile(subject, ".claude/settings.json", """
                            {
                              "enabledPlugins": {
                                %s: true
                              }
                            }""".formatted(HomeVerdictsSupport.jsonString("hv-plugin@" + id)));
                    return ".claude/settings.json:enabledPlugins.hv-plugin@" + id;
                },
                (ctx2, subject, rel) -> {
                    String id = HomeVerdictsSupport.identityOf(subject);
                    var known = HomeVerdictsSupport.readJson(subject, ".claude/plugins/known_marketplaces.json");
                    var settings = HomeVerdictsSupport.readJson(subject, ".claude/settings.json");
                    return List.of(
                            new HomeVerdictsSupport.Check("the_identity_is_registered_at_this_home",
                                    known.path(id).path("source").path("path").asText()
                                            .equals(HomeVerdictsSupport.marketplaceRoot(subject)),
                                    "known_marketplaces.json after --fix: " + known),
                            new HomeVerdictsSupport.Check("the_enabled_plugin_is_kept",
                                    settings.path("enabledPlugins").has("hv-plugin@" + id),
                                    "settings.json after --fix: " + settings),
                            new HomeVerdictsSupport.Check("the_name_that_merely_contains_it_is_untouched",
                                    known.has(id + "-old"),
                                    "known_marketplaces.json after --fix: " + known));
                }));
    }
}
