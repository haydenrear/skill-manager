///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../../lib/StoredPaths.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Instantiate the smoke harness as a child Skill Manager home and verify
 * the public CLI creates a usable project-local harness root:
 *
 * <ul>
 *   <li>{@code <target>/.skill-manager} is a real SkillStore with installed records.</li>
 *   <li>{@code <target>/.codex}, {@code .claude}, and {@code .gemini} receive agent projections.</li>
 *   <li>Harness projections point at the child store, not the parent store.</li>
 *   <li>Parent CLI shims selected by the harness units are mirrored under the child home.</li>
 * </ul>
 */
public class HarnessChildHomeMaterialized {
    static final NodeSpec SPEC = NodeSpec.of("harness.child.home.materialized")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("harness.instance.materialized")
            .tags("harness", "child-home", "issue-75")
            .timeout("60s")
            .output("childHomeDir", "string")
            .output("childSkillManagerHome", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String harnessName = ctx.get("harness.transitive.installed", "harnessName").orElse(null);
            if (home == null || harnessName == null) {
                return NodeResult.fail("harness.child.home.materialized", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path target = Path.of(home, "child-harness-project");
            String instanceId = "child-smoke-instance";

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "harness", "instantiate", harnessName,
                    "--id", instanceId,
                    "--child-home-dir", target.toString());
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "instantiate-child-home", pb);
            int rc = proc.exitCode();

            Path childHome = target.resolve(".skill-manager");
            Path childSkill = childHome.resolve("skills/pip-cli-skill");
            Path childPlugin = childHome.resolve("plugins/hello-plugin");
            Path childDoc = childHome.resolve("docs/hello-doc-repo");
            Path childHarness = childHome.resolve("harnesses/" + harnessName);
            boolean childStore = Files.isDirectory(childHome)
                    && Files.isDirectory(childHome.resolve("installed"))
                    && Files.isRegularFile(childHome.resolve("installed/pip-cli-skill.json"))
                    && Files.isRegularFile(childHome.resolve("installed/hello-plugin.json"))
                    && Files.isRegularFile(childHome.resolve("installed/hello-doc-repo.json"))
                    && Files.isRegularFile(childHome.resolve("installed/" + harnessName + ".json"));
            // Presence. NOTE: Files.exists(NOFOLLOW) is true of a symlink at the
            // parent store AND of an independent copy, so this says the entry is
            // there and nothing about what it is. Independence is asserted below.
            boolean childUnits = existsNoFollow(childSkill)
                    && existsNoFollow(childPlugin)
                    && existsNoFollow(childDoc)
                    && existsNoFollow(childHarness);

            // Independence: `harness instantiate --child-home-dir` goes through
            // the same ChildHomeMaterializer as `project resolve`, so it owes
            // the same guarantee — the child home is an independent tree.
            Path parentHome = Path.of(home);
            java.util.List<Path> parentUnitRoots = parentUnitRoots(parentHome);
            java.util.List<String> notIndependent = new java.util.ArrayList<>();
            java.util.List<String> notCopyRecorded = new java.util.ArrayList<>();
            java.util.List<String> storeLinks = new java.util.ArrayList<>();
            String[][] childUnitEntries = {
                    {"skills/pip-cli-skill", "skill", "pip-cli-skill"},
                    {"plugins/hello-plugin", "plugin", "hello-plugin"},
                    {"docs/hello-doc-repo", "doc", "hello-doc-repo"},
                    {"harnesses/" + harnessName, "harness", harnessName},
            };
            for (String[] entry : childUnitEntries) {
                Path unitDir = childHome.resolve(entry[0]);
                if (!isRealDirectory(unitDir) || realPathInsideAny(unitDir, parentUnitRoots)) {
                    notIndependent.add(entry[0]);
                }
                String record = readOrEmpty(childHome.resolve(".materialization")
                        .resolve(entry[1]).resolve(entry[2] + ".json"));
                if (!record.contains("\"mode\" : \"COPY\"")) notCopyRecorded.add(entry[0]);
                // Only unit trees: bin/ shims are symlinks into the parent
                // toolchain by design (ChildHomeMaterializer#mirrorExistingShim).
                for (String link : storeLinksBelow(unitDir, parentUnitRoots)) {
                    storeLinks.add(entry[0] + "/" + link);
                }
            }
            boolean childUnitsAreIndependentCopies = notIndependent.isEmpty();
            boolean childUnitsRecordedAsCopies = notCopyRecorded.isEmpty();
            boolean noChildUnitLinksIntoParentStore = storeLinks.isEmpty();

            // Independence, empirically. The probe write is reverted so the
            // teardown nodes downstream still see an unmodified child home.
            Path childSkillMd = childSkill.resolve("SKILL.md");
            Path parentSkillMd = Path.of(home, "skills", "pip-cli-skill", "SKILL.md");
            String childSkillBefore = readOrEmpty(childSkillMd);
            String parentSkillBefore = readOrEmpty(parentSkillMd);
            boolean childEditIsolated;
            try {
                Files.writeString(childSkillMd, childSkillBefore + "AGENT-EDIT-PROBE\n");
                childEditIsolated = !childSkillBefore.isEmpty()
                        && readOrEmpty(parentSkillMd).equals(parentSkillBefore)
                        && !readOrEmpty(parentSkillMd).contains("AGENT-EDIT-PROBE")
                        && readOrEmpty(childSkillMd).contains("AGENT-EDIT-PROBE");
                Files.writeString(childSkillMd, childSkillBefore);
            } catch (Exception e) {
                childEditIsolated = false;
            }
            boolean childEditRestored = readOrEmpty(childSkillMd).equals(childSkillBefore);
            boolean childAgentHomes = Files.isDirectory(target.resolve(".codex"))
                    && Files.isDirectory(target.resolve(".claude"))
                    && Files.isDirectory(target.resolve(".gemini"));

            Path codexSkill = target.resolve(".codex/skills/pip-cli-skill");
            Path claudeSkill = target.resolve(".claude/skills/pip-cli-skill");
            Path geminiSkill = target.resolve(".gemini/skills/pip-cli-skill");
            Path claudePlugin = target.resolve(".claude/plugins/hello-plugin");
            boolean agentProjections = Files.isSymbolicLink(codexSkill)
                    && Files.isSymbolicLink(claudeSkill)
                    && Files.isSymbolicLink(geminiSkill)
                    && Files.isSymbolicLink(claudePlugin);
            boolean projectionsUseChildStore = pointsTo(codexSkill, childSkill)
                    && pointsTo(claudeSkill, childSkill)
                    && pointsTo(geminiSkill, childSkill)
                    && pointsTo(claudePlugin, childPlugin);

            Path reviewDoc = target.resolve("docs/agents/review-stance.md");
            Path buildDoc = target.resolve("docs/agents/build-instructions.md");
            String claudeMd = Files.isRegularFile(target.resolve("CLAUDE.md"))
                    ? Files.readString(target.resolve("CLAUDE.md")) : "";
            String agentsMd = Files.isRegularFile(target.resolve("AGENTS.md"))
                    ? Files.readString(target.resolve("AGENTS.md")) : "";
            boolean docsBound = Files.isRegularFile(reviewDoc)
                    && Files.isRegularFile(buildDoc)
                    && claudeMd.contains("@docs/agents/review-stance.md")
                    && claudeMd.contains("@docs/agents/build-instructions.md")
                    && agentsMd.contains("@docs/agents/review-stance.md");

            // Shims are DELIBERATELY symlinks at the parent bin entry, whatever
            // the unit materialization mode: they launch toolchains the parent
            // installed, and nothing edits a shim through the child home. Assert
            // that contract explicitly rather than leaving existsNoFollow to be
            // true of anything at all.
            Path cliShim = childHome.resolve("bin/cli/pycowsay");
            boolean cliShimMirrored = existsNoFollow(cliShim);
            boolean cliShimIsLinkIntoParent = Files.isSymbolicLink(cliShim)
                    && linkTargetIs(cliShim, parentHome.resolve("bin/cli/pycowsay"));

            Path lock = Path.of(home, "harnesses", "instances", instanceId, ".harness-instance.json");
            boolean lockPresent = Files.isRegularFile(lock);
            String lockJson = lockPresent ? Files.readString(lock) : "";
            boolean lockCarriesChildPaths =
                    StoredPaths.records(lockJson, home, target.resolve(".claude"))
                    && StoredPaths.records(lockJson, home, target.resolve(".codex"))
                    && StoredPaths.records(lockJson, home, target.resolve(".gemini"))
                    && StoredPaths.records(lockJson, home, target);

            Path childHomeRecord = Path.of(home, "child-homes", instanceId, "child-home.json");
            boolean childHomeRecordPresent = Files.isRegularFile(childHomeRecord);
            String childHomeRecordJson = childHomeRecordPresent ? Files.readString(childHomeRecord) : "";
            boolean childHomeClaimsSkill = childHomeRecordJson.contains("\"pip-cli-skill\"")
                    && childHomeRecordJson.contains("\"" + harnessName + "\"")
                    && childHomeRecordJson.contains(childHome.toString());

            Path skillLedger = Path.of(home, "installed", "pip-cli-skill.projections.json");
            String skillLedgerJson = Files.isRegularFile(skillLedger) ? Files.readString(skillLedger) : "";
            boolean parentLedgerTracksChild =
                    skillLedgerJson.contains("\"harness:" + instanceId + ":pip-cli-skill\"")
                    && StoredPaths.records(skillLedgerJson, home, target);

            ProcessBuilder removePb = new ProcessBuilder(sm.toString(), "remove", "pip-cli-skill");
            removePb.environment().put("SKILL_MANAGER_HOME", home);
            removePb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord removeProc = Procs.run(ctx, "remove-child-claimed-skill", removePb);
            String removeLog = Files.isRegularFile(Procs.logFile(ctx, "remove-child-claimed-skill"))
                    ? Files.readString(Procs.logFile(ctx, "remove-child-claimed-skill")) : "";
            boolean removeRejected = removeProc.exitCode() != 0
                    && removeLog.contains("child skill-manager home");

            boolean pass = rc == 0
                    && childStore
                    && childUnits
                    && childUnitsAreIndependentCopies
                    && childUnitsRecordedAsCopies
                    && noChildUnitLinksIntoParentStore
                    && childEditIsolated
                    && childEditRestored
                    && childAgentHomes
                    && agentProjections
                    && projectionsUseChildStore
                    && docsBound
                    && cliShimMirrored
                    && cliShimIsLinkIntoParent
                    && lockPresent
                    && lockCarriesChildPaths
                    && childHomeRecordPresent
                    && childHomeClaimsSkill
                    && parentLedgerTracksChild
                    && removeRejected;
            NodeResult result = pass
                    ? NodeResult.pass("harness.child.home.materialized")
                    : NodeResult.fail("harness.child.home.materialized",
                            "rc=" + rc
                                    + " childStore=" + childStore
                                    + " childUnits=" + childUnits
                                    + " notIndependent=" + notIndependent
                                    + " notCopyRecorded=" + notCopyRecorded
                                    + " storeLinksInChildUnits=" + storeLinks
                                    + " childEditIsolated=" + childEditIsolated
                                    + " childEditRestored=" + childEditRestored
                                    + " childAgentHomes=" + childAgentHomes
                                    + " agentProjections=" + agentProjections
                                    + " projectionsUseChildStore=" + projectionsUseChildStore
                                    + " docsBound=" + docsBound
                                    + " cliShimMirrored=" + cliShimMirrored
                                    + " cliShimIsLinkIntoParent=" + cliShimIsLinkIntoParent
                                    + " lockPresent=" + lockPresent
                                    + " lockCarriesChildPaths=" + lockCarriesChildPaths
                                    + " childHomeRecordPresent=" + childHomeRecordPresent
                                    + " childHomeClaimsSkillAndHarness=" + childHomeClaimsSkill
                                    + " parentLedgerTracksChild=" + parentLedgerTracksChild
                                    + " removeRejected=" + removeRejected);
            return result
                    .process(proc)
                    .process(removeProc)
                    .assertion("instantiate_ok", rc == 0)
                    .assertion("child_store_initialized", childStore)
                    .assertion("child_units_projected_from_parent", childUnits)
                    .assertion("child_units_are_independent_copies", childUnitsAreIndependentCopies)
                    .assertion("child_units_recorded_as_copies", childUnitsRecordedAsCopies)
                    .assertion("no_child_unit_entry_links_into_the_parent_store",
                            noChildUnitLinksIntoParentStore)
                    .assertion("child_home_edit_does_not_reach_the_parent_store", childEditIsolated)
                    .assertion("child_home_edit_probe_restored", childEditRestored)
                    .assertion("child_agent_homes_created", childAgentHomes)
                    .assertion("agent_projections_created", agentProjections)
                    .assertion("agent_projections_point_at_child_store", projectionsUseChildStore)
                    .assertion("docs_bound_into_child_project_root", docsBound)
                    .assertion("cli_shim_mirrored_into_child_home", cliShimMirrored)
                    .assertion("cli_shim_stays_a_symlink_at_the_parent_toolchain", cliShimIsLinkIntoParent)
                    .assertion("harness_instance_lock_present", lockPresent)
                    .assertion("harness_instance_lock_uses_child_paths", lockCarriesChildPaths)
                    .assertion("parent_child_home_registry_present", childHomeRecordPresent)
                    .assertion("child_home_registry_claims_skill_and_harness", childHomeClaimsSkill)
                    .assertion("parent_ledger_tracks_child_projection", parentLedgerTracksChild)
                    .assertion("plain_remove_rejects_child_home_claimed_skill", removeRejected)
                    .metric("exitCode", rc)
                    .metric("removeExitCode", removeProc.exitCode())
                    .publish("childHomeDir", target.toString())
                    .publish("childSkillManagerHome", childHome.toString());
        });
    }

    private static boolean existsNoFollow(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    /** Real directory, not a symlink — the check {@code Files.isDirectory} cannot make. */
    private static boolean isRealDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }

    /** True when {@code path}'s real location lies inside {@code root}. */
    private static boolean realPathInside(Path path, Path root) {
        try {
            return path.toRealPath().startsWith(root.toRealPath());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The parent store's four unit roots. Scoped deliberately: a child home may
     * legitimately sit UNDER the parent home (harness instantiation puts one
     * there), so "inside the parent home" is not the same question as "inside
     * the parent store's units", and only the latter is the isolation boundary.
     */
    private static java.util.List<Path> parentUnitRoots(Path parentHome) {
        return java.util.List.of(
                parentHome.resolve("skills"),
                parentHome.resolve("plugins"),
                parentHome.resolve("docs"),
                parentHome.resolve("harnesses"));
    }

    private static boolean realPathInsideAny(Path path, java.util.List<Path> roots) {
        for (Path root : roots) {
            if (realPathInside(path, root)) return true;
        }
        return false;
    }

    /**
     * The raw symlink target, not its real path: the parent bin entry is itself
     * usually a symlink into a brew/uv toolchain, so resolving through it says
     * nothing about whether the child home points at the PARENT's shim.
     */
    private static boolean linkTargetIs(Path link, Path expected) {
        try {
            Path raw = Files.readSymbolicLink(link);
            Path resolved = raw.isAbsolute()
                    ? raw.normalize()
                    : link.getParent().resolve(raw).normalize();
            return resolved.equals(expected.toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }

    private static String readOrEmpty(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Every symlink at or below {@code root} that resolves inside {@code store}:
     * each is a live write-through channel from the child home into the parent
     * store, which is what COPY materialization removes.
     */
    private static java.util.List<String> storeLinksBelow(Path root, java.util.List<Path> stores) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.List<Path> real = new java.util.ArrayList<>();
        for (Path store : stores) {
            try {
                real.add(store.toRealPath());
            } catch (Exception missing) {
                // A kind with no installed units has no directory; nothing to link into.
            }
        }
        try {
            collectStoreLinks(root, root, real, out);
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    private static void collectStoreLinks(Path base, Path current, java.util.List<Path> storeReal,
                                          java.util.List<String> out) throws Exception {
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(current)) {
            Path raw = Files.readSymbolicLink(current);
            Path resolved = raw.isAbsolute()
                    ? raw.normalize()
                    : current.getParent().resolve(raw).normalize();
            Path real;
            try {
                real = resolved.toRealPath();
            } catch (Exception broken) {
                real = resolved;
            }
            for (Path store : storeReal) {
                if (real.startsWith(store)) {
                    out.add(base.relativize(current) + " -> " + raw);
                    break;
                }
            }
            return;
        }
        if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(current)) {
                for (Path child : entries.sorted().toList()) {
                    collectStoreLinks(base, child, storeReal, out);
                }
            }
        }
    }

    private static boolean pointsTo(Path symlink, Path expected) {
        try {
            if (!Files.isSymbolicLink(symlink)) return false;
            Path raw = Files.readSymbolicLink(symlink);
            Path resolved = raw.isAbsolute()
                    ? raw.normalize()
                    : symlink.getParent().resolve(raw).normalize();
            return resolved.equals(expected.toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }
}
