///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeVerdictsSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;
import java.util.List;

/**
 * #352 shape 3 (OHV-6): a home's {@code marketplace.json} carries ANOTHER home's
 * identity because it was copied. Measured on 13 homes (seven worktrees carrying
 * commit-diff-context-parent's {@code 919db26e}, six carrying deploy-cdc's). The
 * harness CLIs read the name from that file, so every agent registers the copy
 * under its source's name.
 *
 * <p>Planted by writing the NEIGHBOUR's derived identity into the subject's
 * manifest. {@code home repair} names {@code MARKETPLACE_IDENTITY_COPIED},
 * {@code home verify} exits 1 naming it, and {@code --fix} regenerates the
 * marketplace so the manifest names the subject's own identity.
 */
public class CopiedMarketplaceIdentityIsReported {

    static final NodeSpec SPEC = NodeSpec.of("home.verdicts.copied.marketplace.identity")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(HomeVerdictsSupport.FIXTURE)
            .tags("home", "verdicts", "marketplace", "ohv-6")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> HomeVerdictsSupport.runShape(ctx, SPEC,
                new HomeVerdictsSupport.Shape("copied-marketplace-identity",
                        "MARKETPLACE_IDENTITY_COPIED", 1, true),
                (Path subject, Path neighbour) -> {
                    HomeVerdictsSupport.writeManifest(subject, HomeVerdictsSupport.identityOf(neighbour));
                    return "plugin-marketplace/.claude-plugin/marketplace.json";
                },
                (ctx2, subject, rel) -> {
                    String id = HomeVerdictsSupport.identityOf(subject);
                    var manifest = HomeVerdictsSupport.readStoreJson(subject, rel);
                    return List.of(new HomeVerdictsSupport.Check(
                            "the_manifest_names_this_home_s_derived_identity",
                            id.equals(manifest.path("name").asText()),
                            "expected " + id + ", manifest after --fix: " + manifest));
                }));
    }
}
