///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HonestPinsKit.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Record shape (f), #412: a unit whose {@code .git} is missing or partial must
 * be reported by {@code home close-out} and {@code home sync} as a DAMAGED
 * unit -- never read through the ENCLOSING repository's Git identity, whose
 * branches then pass for unpublished unit history.
 *
 * <p>Plant: an "application" repository {@code app/} with a branch
 * ({@code app-feature-unpublished}) that exists on no remote. Three homes live
 * INSIDE it -- a seed, and a project home and a worktree home both made from
 * the seed with {@code home clone} -- so every unit directory has an enclosing
 * repository for git discovery to climb to. Each unit is a real clone of a
 * bare origin outside {@code app/}.
 *
 * <ul>
 *   <li>{@code hp-f-valid}: an intact own repository, untouched (CONTROL).</li>
 *   <li>{@code hp-f-diverged}: intact on both sides, with a different
 *       unpublished commit on each (CONTROL: must stay "conflicted" on
 *       {@code .git (history)}).</li>
 *   <li>{@code hp-f-linked}: a LINKED git worktree ({@code .git} is a file),
 *       only in the worktree home (CONTROL: an own repository, not damaged).</li>
 *   <li>{@code hp-f-partial}: the worktree copy's {@code .git} lost HEAD and
 *       config; objects and refs remain (SHAPE).</li>
 *   <li>{@code hp-f-missing}: the worktree copy's {@code .git} is gone, though
 *       its clone-time record says it carried history (SHAPE).</li>
 * </ul>
 *
 * <p>HONEST: both shape units come back with status {@code damaged} from
 * close-out (as blockers, never cleared) and from a real {@code home sync};
 * no detail or remedy names the application's branch, its HEAD, a
 * {@code .git (history)} conflict, or {@code unit publish}; the sync writes
 * nothing into the project copy; the partial {@code .git} is left exactly as
 * found for recovery; the application repository's refs and every origin are
 * unchanged. At 0.28.2 a partial {@code .git} is fed to git with
 * {@code cwd = unit}, git walks up to {@code app/}, and the unit is reported
 * as a diverged history to be published.
 *
 * <p>The SYMLINK/ALIAS arm re-runs close-out through a symlinked spelling of
 * the worktree home and asks for the same per-unit answers.
 *
 * <p>Out of scope: units installed with no {@code .git} at all and no record
 * of ever having had one (#333).
 */
public class RecordFDamagedUnitGit {
    static final String ID = "honest.record-f";
    static final String APP_BRANCH = "app-feature-unpublished";
    static final String VALID = "hp-f-valid";
    static final String DIVERGED = "hp-f-diverged";
    static final String LINKED = "hp-f-linked";
    static final String PARTIAL = "hp-f-partial";
    static final String MISSING = "hp-f-missing";
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("honest-pins", "record", "home-sync", "close-out", "git-identity", "412")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path root;
            Path app;
            Path seed;
            Path project;
            Path worktree;
            Path alias;
            Path origins;
            String appHead;
            String appRefsBefore;
            String originRefsBefore;
            try {
                root = HonestPinsKit.scratch(ctx, "record-f");
                app = root.resolve("app");
                origins = root.resolve("origins");
                seed = app.resolve("homes/seed");
                project = app.resolve("homes/project");
                worktree = app.resolve("homes/worktree");
                alias = root.resolve("worktree-alias");
                Files.createDirectories(app);
                Files.createDirectories(origins);
                Files.createDirectories(seed.resolve("skills"));
                Files.createDirectories(seed.resolve("installed"));

                // The ENCLOSING application repository, with a branch on no remote.
                HonestPinsKit.git(app, "init", "--quiet", "-b", "main");
                Files.writeString(app.resolve("README.md"), "application\n");
                Files.writeString(app.resolve(".gitignore"), "homes/\n");
                HonestPinsKit.commitAll(app, "application main");
                HonestPinsKit.git(app, "checkout", "--quiet", "-b", APP_BRANCH);
                Files.writeString(app.resolve("feature.txt"), "application-only work\n");
                HonestPinsKit.commitAll(app, "application commit on no remote");
                HonestPinsKit.git(app, "checkout", "--quiet", "main");
                appHead = HonestPinsKit.gitOut(app, "rev-parse", "HEAD");

                for (String unit : List.of(VALID, DIVERGED, LINKED, PARTIAL, MISSING)) {
                    Path staging = root.resolve("staging").resolve(unit);
                    HonestPinsKit.writeSkill(staging, unit, "0.1.0", "body of " + unit);
                    HonestPinsKit.git(staging, "init", "--quiet", "-b", "main");
                    HonestPinsKit.commitAll(staging, "published " + unit);
                    HonestPinsKit.git(root, "clone", "--quiet", "--bare", staging.toString(),
                            origins.resolve(unit + ".git").toString());
                    if (!unit.equals(LINKED)) {
                        HonestPinsKit.git(root, "clone", "--quiet",
                                origins.resolve(unit + ".git").toString(),
                                seed.resolve("skills").resolve(unit).toString());
                    }
                }
                appRefsBefore = HonestPinsKit.gitOut(app, "for-each-ref", "--format=%(objectname) %(refname)");
                originRefsBefore = originRefs(origins);
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e).assertion("setup_ok", false);
            }

            ProcessRecord cloneProject = HonestPinsKit.sm(ctx, "clone_project", root, seed,
                    "home", "clone", "--from", seed.toString(), "--to", project.toString(), "--json");
            ProcessRecord cloneWorktree = HonestPinsKit.sm(ctx, "clone_worktree", root, seed,
                    "home", "clone", "--from", seed.toString(), "--to", worktree.toString(), "--json");

            Path wtPartial = worktree.resolve("skills").resolve(PARTIAL);
            Path wtMissing = worktree.resolve("skills").resolve(MISSING);
            Path prPartial = project.resolve("skills").resolve(PARTIAL);
            String projectPartialHeadBefore;
            String projectPartialSkillBefore;
            boolean plantedDamage;
            boolean enclosingDiscoveryHazard;
            boolean plantedLinked;
            boolean plantedDiverged;
            try {
                // DIVERGED: an unpublished commit on each side.
                Path wtDiv = worktree.resolve("skills").resolve(DIVERGED);
                Path prDiv = project.resolve("skills").resolve(DIVERGED);
                Files.writeString(wtDiv.resolve("worktree.txt"), "worktree-only commit\n");
                HonestPinsKit.commitAll(wtDiv, "worktree side");
                Files.writeString(prDiv.resolve("project.txt"), "project-only commit\n");
                HonestPinsKit.commitAll(prDiv, "project side");
                plantedDiverged = !HonestPinsKit.head(wtDiv).equals(HonestPinsKit.head(prDiv));

                // LINKED: a git worktree whose .git is a FILE, worktree home only.
                Path linkedMain = root.resolve("linked-main");
                HonestPinsKit.git(root, "clone", "--quiet", origins.resolve(LINKED + ".git").toString(),
                        linkedMain.toString());
                Path wtLinked = worktree.resolve("skills").resolve(LINKED);
                HonestPinsKit.git(linkedMain, "worktree", "add", "--quiet", "-b", "linked",
                        wtLinked.toString());
                plantedLinked = Files.isRegularFile(wtLinked.resolve(".git"));

                projectPartialHeadBefore = HonestPinsKit.head(prPartial);
                projectPartialSkillBefore = HonestPinsKit.read(prPartial.resolve("SKILL.md"));

                // PARTIAL: HEAD and config gone; objects and refs remain.
                Files.delete(wtPartial.resolve(".git/HEAD"));
                Files.delete(wtPartial.resolve(".git/config"));
                // MISSING: the whole .git gone.
                HonestPinsKit.deleteTree(wtMissing.resolve(".git"));
                plantedDamage = Files.isDirectory(wtPartial.resolve(".git/objects"))
                        && !Files.exists(wtPartial.resolve(".git/HEAD"))
                        && !Files.exists(wtMissing.resolve(".git"))
                        && Files.isRegularFile(wtMissing.resolve("SKILL.md"));

                // The hazard itself, measured with plain git: from the partial
                // unit, discovery answers for the application repository.
                String top = HonestPinsKit.gitOut(wtPartial, "rev-parse", "--show-toplevel");
                enclosingDiscoveryHazard = Files.isSameFile(Path.of(top), app);

                Files.createSymbolicLink(alias, worktree);
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed planting the shapes: " + e)
                        .assertion("setup_ok", false);
            }
            boolean setup = cloneProject.exitCode() == 0 && cloneWorktree.exitCode() == 0
                    && plantedDamage && plantedLinked && plantedDiverged;

            // ------------------------------------------------------ close-out
            ProcessRecord closeOut = HonestPinsKit.sm(ctx, "close_out", root, seed,
                    "home", "close-out", "--home", worktree.toString(), "--into", project.toString(), "--json");
            String coJson = HonestPinsKit.lastJsonLine(HonestPinsKit.output(ctx, closeOut));

            // ------------------------------------------- close-out via alias
            ProcessRecord closeOutAlias = HonestPinsKit.sm(ctx, "close_out_alias", root, seed,
                    "home", "close-out", "--home", alias.toString(), "--into", project.toString(), "--json");
            String aliasJson = HonestPinsKit.lastJsonLine(HonestPinsKit.output(ctx, closeOutAlias));

            // ---------------------------------------------- a real home sync
            ProcessRecord sync = HonestPinsKit.sm(ctx, "home_sync", root, seed,
                    "home", "sync", "--from", worktree.toString(), "--to", project.toString(), "--merge", "--json");
            String syncJson = HonestPinsKit.lastJsonLine(HonestPinsKit.output(ctx, sync));

            // CONTROLS
            boolean controlValid = "unchanged".equals(status(coJson, VALID))
                    && blocker(coJson, VALID) == null
                    && "unchanged".equals(status(syncJson, VALID));
            String divStatus = status(coJson, DIVERGED);
            String divUnit = unit(coJson, DIVERGED);
            boolean controlDiverged = "conflicted".equals(divStatus)
                    && divUnit != null && divUnit.contains(".git (history)")
                    && blocker(coJson, DIVERGED) != null;
            String linkedStatus = status(coJson, LINKED);
            boolean controlLinked = "new".equals(linkedStatus);
            boolean controlAlias = closeOutAlias.exitCode() == closeOut.exitCode()
                    && "unchanged".equals(status(aliasJson, VALID))
                    && "new".equals(status(aliasJson, LINKED))
                    && "conflicted".equals(status(aliasJson, DIVERGED))
                    && "damaged".equals(status(aliasJson, PARTIAL))
                    && "damaged".equals(status(aliasJson, MISSING));

            // SHAPE
            List<String> leaks = new ArrayList<>();
            for (String unitName : List.of(PARTIAL, MISSING)) {
                for (String text : new String[]{unit(coJson, unitName), blocker(coJson, unitName),
                        unit(syncJson, unitName)}) {
                    if (text == null) continue;
                    if (text.contains(APP_BRANCH)) leaks.add(unitName + ": names " + APP_BRANCH);
                    if (text.contains(appHead) || text.contains(appHead.substring(0, 7))) {
                        leaks.add(unitName + ": names the application HEAD");
                    }
                    if (text.contains(".git (history)")) leaks.add(unitName + ": .git (history) conflict");
                    if (text.contains("unit publish")) leaks.add(unitName + ": recommends unit publish");
                    if (text.contains("diverged")) leaks.add(unitName + ": reads as diverged");
                }
            }
            boolean closeOutDamaged = "damaged".equals(status(coJson, PARTIAL))
                    && "damaged".equals(status(coJson, MISSING))
                    && blocker(coJson, PARTIAL) != null && blocker(coJson, MISSING) != null
                    && coJson.contains("\"safe\":false") && closeOut.exitCode() != 0;
            boolean syncDamaged = "damaged".equals(status(syncJson, PARTIAL))
                    && "damaged".equals(status(syncJson, MISSING))
                    && sync.exitCode() != 0;
            boolean projectUntouched = projectPartialHeadBefore.equals(HonestPinsKit.head(prPartial))
                    && projectPartialSkillBefore.equals(HonestPinsKit.read(prPartial.resolve("SKILL.md")))
                    && Files.isDirectory(project.resolve("skills").resolve(MISSING).resolve(".git"));
            boolean partialPreserved = Files.isDirectory(wtPartial.resolve(".git/objects"))
                    && Files.isDirectory(wtPartial.resolve(".git/refs"))
                    && !Files.exists(wtPartial.resolve(".git/HEAD"))
                    && !Files.exists(wtPartial.resolve(".git/config"));
            String appRefsAfter;
            String originRefsAfter;
            try {
                appRefsAfter = HonestPinsKit.gitOut(app, "for-each-ref", "--format=%(objectname) %(refname)");
                originRefsAfter = originRefs(origins);
            } catch (Exception e) {
                appRefsAfter = "(unreadable: " + e + ")";
                originRefsAfter = "(unreadable)";
            }
            boolean nothingMutated = appRefsBefore.equals(appRefsAfter) && originRefsBefore.equals(originRefsAfter);
            boolean shape = closeOutDamaged && syncDamaged && leaks.isEmpty();

            String detail = "close-out rc=" + closeOut.exitCode()
                    + " partial=" + status(coJson, PARTIAL) + " missing=" + status(coJson, MISSING)
                    + " valid=" + status(coJson, VALID) + " diverged=" + divStatus + " linked=" + linkedStatus
                    + "; sync rc=" + sync.exitCode() + " partial=" + status(syncJson, PARTIAL)
                    + " missing=" + status(syncJson, MISSING) + " valid=" + status(syncJson, VALID)
                    + "; leaks=" + leaks + "; projectUntouched=" + projectUntouched
                    + " partialPreserved=" + partialPreserved + " nothingMutated=" + nothingMutated
                    + "; alias rc=" + closeOutAlias.exitCode() + " controlAlias=" + controlAlias
                    + "; setup=" + setup + " hazard=" + enclosingDiscoveryHazard
                    + "; partial unit=" + unit(coJson, PARTIAL) + " partial blocker=" + blocker(coJson, PARTIAL);
            boolean all = setup && enclosingDiscoveryHazard && controlValid && controlDiverged && controlLinked
                    && controlAlias && shape && projectUntouched && partialPreserved && nothingMutated;
            NodeResult result = all ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(cloneProject).process(cloneWorktree).process(closeOut)
                    .process(closeOutAlias).process(sync)
                    .assertion("setup_homes_cloned_and_shapes_planted", setup)
                    .assertion("setup_partial_unit_discovers_enclosing_repo", enclosingDiscoveryHazard)
                    .assertion("control_valid_nested_repo_unchanged", controlValid)
                    .assertion("control_truly_diverged_unit_still_conflicted", controlDiverged)
                    .assertion("control_linked_worktree_unit_not_damaged", controlLinked)
                    .assertion("control_symlinked_home_path_same_answers", controlAlias)
                    .assertion("shape_close_out_reports_damaged_units_as_blockers", closeOutDamaged)
                    .assertion("shape_home_sync_reports_damaged_units", syncDamaged)
                    .assertion("shape_no_enclosing_identity_or_publish_remedy", leaks.isEmpty())
                    .assertion("shape_damaged_unit_not_synced_into_project", projectUntouched)
                    .assertion("shape_partial_git_preserved_for_recovery", partialPreserved)
                    .assertion("shape_no_ref_mutation_in_app_or_origins", nothingMutated)
                    .log(detail);
        });
    }

    private static String originRefs(Path origins) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String unit : List.of(VALID, DIVERGED, LINKED, PARTIAL, MISSING)) {
            sb.append(unit).append('\n').append(HonestPinsKit.gitOut(origins.resolve(unit + ".git"),
                    "for-each-ref", "--format=%(objectname) %(refname)")).append('\n');
        }
        return sb.toString();
    }

    /** The {@code units[]} object for {@code skill:<name>}, or null. */
    private static String unit(String json, String name) {
        Matcher m = Pattern.compile("\\{\"unit\":\"skill:" + Pattern.quote(name)
                + "\",\"name\".*?\"conflicts\":\\[[^\\]]*\\]\\}").matcher(json);
        return m.find() ? m.group() : null;
    }

    /** The {@code blockers[]} object for {@code skill:<name>}, or null. */
    private static String blocker(String json, String name) {
        Matcher m = Pattern.compile("\\{\"unit\":\"skill:" + Pattern.quote(name)
                + "\",\"status\".*?\"remedy\":\"(?:[^\"\\\\]|\\\\.)*\"\\}").matcher(json);
        return m.find() ? m.group() : null;
    }

    private static String status(String json, String name) {
        String u = unit(json, name);
        return u == null ? null : HonestPinsKit.jsonString(u, "status");
    }
}
