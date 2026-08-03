///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Dirty-state guard for {@code skill-manager sync --from <dir>}: when
 * the installed copy is git-tracked AND has uncommitted edits or
 * commits ahead of the recorded baseline, sync must refuse to
 * overwrite, exit 7, and print a re-run recipe that is <b>runnable as
 * printed and does what the refused command was going to do</b>.
 *
 * <h2>What "runnable as printed" means here, and why it is the assertion</h2>
 *
 * <p>This node used to assert the banner carried a by-hand
 * {@code git fetch <upstream> HEAD} / {@code git merge FETCH_HEAD} recipe.
 * That recipe was deliberately deleted — it is three commands any reader of
 * the first line can write, it was printed once per refused unit so a
 * five-unit refusal cost seventy lines, and {@code --merge} does the same
 * thing correctly including the stash handling the by-hand version omitted.
 *
 * <p>What went with it, and should not have, was the only part of it a reader
 * could NOT derive: <b>which source the merge would pull from</b>. For a
 * {@code --from} sync that is the directory on the command line, and the
 * printed remedy dropped it — {@code skill-manager sync <name> --merge}, run
 * as printed, merges the RECORDED ORIGIN instead. For a unit installed from
 * github and being synced from a {@code skill-dev} worktree — the flow
 * {@code skill-dev-skill} documents — that is a different source and a
 * different merge. So the assertion is now the property the deleted recipe
 * was carrying: the re-run command names the {@code --from} directory, and
 * the banner names the source it would merge.
 *
 * <p>This is the {@code rc=7} contract — automation reading the
 * {@code rc=7} banner needs the merge metadata to act on. The store's
 * dirty state is intentionally left in place; the next node
 * ({@code source.sync.refuses_without_from}) reuses it to exercise the
 * implicit-origin form of the same banner, where NO {@code --from} may
 * appear and the recorded origin is named instead.
 */
public class SourceSyncRefusesOnDirty {
    static final NodeSpec SPEC = NodeSpec.of("source.sync.refuses_on_dirty")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("source.fixture.installed")
            .tags("source-tracking", "sync", "abort")
            .timeout("30s")
            .output("dirtyStoreDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String geminiHome = ctx.get("env.prepared", "geminiHome").orElse(null);
            String fixtureDir = ctx.get("source.fixture.published", "skillDir").orElse(null);
            String skillName = ctx.get("source.fixture.published", "skillName").orElse(null);
            String storeDir = ctx.get("source.fixture.installed", "storeDir").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || geminiHome == null
                    || fixtureDir == null || skillName == null || storeDir == null) {
                return NodeResult.fail("source.sync.refuses_on_dirty", "missing upstream context");
            }

            // Drift the install — append a line so working tree is dirty.
            Path skillMd = Path.of(storeDir).resolve("SKILL.md");
            Files.writeString(skillMd,
                    "\n\nlocal-edit-from-test-graph\n",
                    StandardOpenOption.APPEND);

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "sync", skillName, "--from", fixtureDir)
                    .redirectErrorStream(true);
            SmEnv.apply(ctx, pb, home);

            StringBuilder out = new StringBuilder();
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println(line);
                    out.append(line).append('\n');
                }
            }
            int rc = p.waitFor();
            String body = out.toString();

            boolean exitedSeven = rc == 7;
            // The banner is structured so harnesses can match on it.
            boolean mentionsLocalChanges = body.contains("extra local changes");
            boolean mentionsMergeFlag = body.contains("--merge");
            // The whole recipe, contiguous: anything less would also be
            // satisfied by the flag and the path appearing in unrelated
            // lines of a long sync.
            String expectedRecipe =
                    "skill-manager sync " + skillName + " --from " + fixtureDir + " --merge";
            boolean recipeKeepsFromDir = body.contains(expectedRecipe);
            // ...and the source it would merge is named, not left implicit.
            boolean bannerNamesTheSource = body.contains(fixtureDir);
            // The store dir stays named too — it is where the reader resolves.
            boolean bannerNamesTheStore = body.contains(storeDir);
            // Local edit must still be on disk — sync mustn't have clobbered it.
            String afterMd = Files.readString(skillMd);
            boolean editPreserved = afterMd.contains("local-edit-from-test-graph");

            boolean pass = exitedSeven && mentionsLocalChanges && recipeKeepsFromDir
                    && bannerNamesTheSource && bannerNamesTheStore
                    && mentionsMergeFlag && editPreserved;
            return (pass
                    ? NodeResult.pass("source.sync.refuses_on_dirty")
                    : NodeResult.fail("source.sync.refuses_on_dirty",
                            "rc=" + rc + " local=" + mentionsLocalChanges
                                    + " recipeKeepsFrom=" + recipeKeepsFromDir
                                    + " namesSource=" + bannerNamesTheSource
                                    + " namesStore=" + bannerNamesTheStore
                                    + " mergeFlag=" + mentionsMergeFlag
                                    + " editPreserved=" + editPreserved))
                    .assertion("exited_with_rc_7", exitedSeven)
                    .assertion("banner_mentions_local_changes", mentionsLocalChanges)
                    .assertion("rerun_recipe_keeps_the_from_directory", recipeKeepsFromDir)
                    .assertion("banner_names_the_source_it_would_merge", bannerNamesTheSource)
                    .assertion("banner_names_the_store_directory", bannerNamesTheStore)
                    .assertion("banner_includes_merge_flag_recipe", mentionsMergeFlag)
                    .assertion("local_edit_preserved", editPreserved)
                    .publish("dirtyStoreDir", storeDir);
        });
    }
}
