///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Exercises {@code skill-manager upgrade} rollback: publish a deliberately
 * broken {@code v0.0.2} of a fixture skill that's already installed at
 * {@code v0.0.1}, then run upgrade and assert it fails to install the new
 * version, restores the {@code v0.0.1} backup, and cleans up the backup
 * dir.
 *
 * <p>The "broken" half is a {@code skill_references} entry pointing at a
 * skill name that doesn't exist in the registry. The resolver walks the
 * dep tree before the install plan is committed, so the fetch error
 * surfaces cleanly through {@code InstallCommand.call()} into
 * {@code UpgradeCommand}'s rollback path.
 *
 * <p>Uses a dedicated fixture skill so the rest of the smoke graph isn't
 * affected by the test's published-then-broken state in the registry.
 */
public class SkillUpgradeRolledBack {
    static final NodeSpec SPEC = NodeSpec.of("skill.upgrade.rolled_back")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("registry.up", "ci.logged.in", "jwt.valid", "gateway.up")
            .tags("cli", "upgrade", "rollback")
            .timeout("180s");

    private static final String SKILL_NAME = "upgrade-fixture-skill";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || registryUrl == null) {
                return NodeResult.fail("skill.upgrade.rolled_back", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path stagingRoot = Path.of(home).resolve("upgrade-fixture");
            Files.createDirectories(stagingRoot);

            // Stage v0.0.1 by copying examples/hello-skill, then renaming.
            // Strip transitive deps so install pulls nothing extra and runs
            // fast — we only need the manifest + SKILL.md to round-trip.
            Path src = repoRoot.resolve("examples/hello-skill");
            Path v1 = stagingRoot.resolve("v1");
            copyDir(src, v1);
            writeMinimalSkill(v1, SKILL_NAME, "0.0.1", false);

            Path v2 = stagingRoot.resolve("v2");
            copyDir(src, v2);
            writeMinimalSkill(v2, SKILL_NAME, "0.0.2", true);

            // Publish v0.0.1, install it, capture which version the store
            // believes is current.
            int pub1 = run(List.of(sm.toString(), "publish", v1.toString(),
                    "--upload-tarball", "--registry", registryUrl),
                    home, claudeHome, codexHome, repoRoot, null);
            if (pub1 != 0) {
                return NodeResult.fail("skill.upgrade.rolled_back", "publish v0.0.1 rc=" + pub1);
            }
            int inst1 = run(List.of(sm.toString(), "install", SKILL_NAME,
                    "--registry", registryUrl),
                    home, claudeHome, codexHome, repoRoot, null);
            if (inst1 != 0) {
                return NodeResult.fail("skill.upgrade.rolled_back", "install v0.0.1 rc=" + inst1);
            }
            Path storeDir = Path.of(home).resolve("skills").resolve(SKILL_NAME);
            String preVersion = readVersion(storeDir);
            if (!"0.0.1".equals(preVersion)) {
                return NodeResult.fail("skill.upgrade.rolled_back",
                        "post-install version unexpected: " + preVersion);
            }

            // Publish the broken v0.0.2.
            int pub2 = run(List.of(sm.toString(), "publish", v2.toString(),
                    "--upload-tarball", "--registry", registryUrl),
                    home, claudeHome, codexHome, repoRoot, null);
            if (pub2 != 0) {
                return NodeResult.fail("skill.upgrade.rolled_back", "publish v0.0.2 rc=" + pub2);
            }

            // Run upgrade. Expected: non-zero exit, store still has 0.0.1,
            // no leftover upgrade-backup-* directories under cache/.
            StringBuilder upgOut = new StringBuilder();
            int upgRc = run(List.of(sm.toString(), "upgrade", SKILL_NAME,
                    "--registry", registryUrl),
                    home, claudeHome, codexHome, repoRoot, upgOut);

            String postVersion = readVersion(storeDir);
            boolean storeHasV1 = "0.0.1".equals(postVersion);
            boolean noBackupLeftover = countBackups(Path.of(home).resolve("cache"), SKILL_NAME) == 0;
            boolean upgradeFailed = upgRc != 0;
            // Rollback path logs a stable "rolled back" string. Cheap signal
            // that we hit the rollback branch rather than e.g. upgrade
            // skipping with "already at latest".
            boolean rollbackLogged = upgOut.toString().contains("rolled back")
                    || upgOut.toString().contains("rolled-back")
                    || upgOut.toString().contains("ROLLBACK");

            boolean pass = upgradeFailed && storeHasV1 && noBackupLeftover && rollbackLogged;
            return (pass
                    ? NodeResult.pass("skill.upgrade.rolled_back")
                    : NodeResult.fail("skill.upgrade.rolled_back",
                            "upgRc=" + upgRc + " postVersion=" + postVersion
                                    + " noBackupLeftover=" + noBackupLeftover
                                    + " rollbackLogged=" + rollbackLogged))
                    .assertion("upgrade_exit_nonzero", upgradeFailed)
                    .assertion("store_pinned_to_v0_0_1", storeHasV1)
                    .assertion("backup_dir_cleaned", noBackupLeftover)
                    .assertion("rollback_branch_taken", rollbackLogged);
        });
    }

    private static int run(List<String> argv, String home, String claudeHome,
                           String codexHome, Path repoRoot, StringBuilder capture) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.environment().put("SKILL_MANAGER_HOME", home);
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        pb.environment().put("CLAUDE_HOME", claudeHome);
        pb.environment().put("CODEX_HOME", codexHome);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                System.out.println(line);
                if (capture != null) capture.append(line).append('\n');
            }
        }
        return p.waitFor();
    }

    private static void copyDir(Path src, Path dst) throws Exception {
        if (Files.exists(dst)) {
            try (var s = Files.walk(dst)) {
                s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
        Files.createDirectories(dst);
        try (var s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path rel = src.relativize(p);
                Path out = dst.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    /**
     * Overwrite skill-manager.toml + SKILL.md with a minimal manifest. When
     * {@code broken} is true, declares a registry skill_reference to a name
     * that does not exist — the resolver will hit a 404 and throw, which
     * is what UpgradeCommand catches to drive its rollback branch.
     */
    private static void writeMinimalSkill(Path dir, String name, String version, boolean broken) throws Exception {
        StringBuilder toml = new StringBuilder();
        toml.append("# Test fixture for SkillUpgradeRolledBack — auto-generated.\n");
        if (broken) {
            toml.append("skill_references = [\"this-skill-does-not-exist-xyz@9.9.9\"]\n");
        } else {
            toml.append("skill_references = []\n");
        }
        toml.append("[skill]\nname = \"").append(name).append("\"\nversion = \"").append(version).append("\"\n");
        Files.writeString(dir.resolve("skill-manager.toml"), toml.toString());

        String md = "---\n"
                + "name: " + name + "\n"
                + "description: Test fixture for SkillUpgradeRolledBack — auto-generated.\n"
                + "version: " + version + "\n"
                + "---\n\n# " + name + "\n\nFixture body.\n";
        Files.writeString(dir.resolve("SKILL.md"), md);
    }

    private static String readVersion(Path skillDir) {
        try {
            String md = Files.readString(skillDir.resolve("SKILL.md"));
            for (String line : md.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("version:")) {
                    return trimmed.substring("version:".length()).trim();
                }
            }
            return "<no-version>";
        } catch (Exception e) {
            return "<read-failed:" + e.getMessage() + ">";
        }
    }

    private static int countBackups(Path cacheDir, String skillName) {
        if (!Files.isDirectory(cacheDir)) return 0;
        try (var s = Files.list(cacheDir)) {
            return (int) s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("upgrade-backup-" + skillName + "-");
            }).count();
        } catch (Exception e) {
            return -1;
        }
    }
}
