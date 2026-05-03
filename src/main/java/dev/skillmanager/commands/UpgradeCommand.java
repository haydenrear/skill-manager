package dev.skillmanager.commands;

import dev.skillmanager.model.Skill;
import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager upgrade [name|--all] [--self]} — version bumps with
 * rollback.
 *
 * <p>For each targeted skill:
 * <ol>
 *   <li>Look up the latest registry version. Skip if already at it.</li>
 *   <li>Move the existing skill directory to a backup under
 *       {@code <store>/cache/upgrade-backup-...}.</li>
 *   <li>Run the install flow for the new version. The install path takes
 *       care of MCP re-register and agent-symlink refresh.</li>
 *   <li>On any failure: wipe whatever the install partial-wrote, move the
 *       backup back, and re-run the per-skill sync so the gateway and
 *       agents are pointed back at the old version. Then surface the
 *       failure.</li>
 *   <li>On success: delete the backup.</li>
 * </ol>
 *
 * <p>{@code --self} shells out to {@code brew upgrade skill-manager}.
 */
@Command(name = "upgrade",
        description = "Upgrade installed skills (and optionally skill-manager itself) with rollback on failure.")
public final class UpgradeCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Skill name to upgrade")
    String name;

    @Option(names = "--all", description = "Upgrade every installed skill.")
    boolean all;

    @Option(names = {"--self", "--skill-manager"},
            description = "Upgrade the skill-manager CLI via Homebrew (`brew upgrade skill-manager`).")
    boolean self;

    @Option(names = "--registry",
            description = "Registry URL override for this invocation (persisted so the latest-version "
                    + "lookup and the inner install both target the same registry).")
    String registryUrl;

    @Option(names = "--merge",
            description = "Forwarded to sync for git-tracked skills: when local edits exist, "
                    + "snapshot them and run a 3-way merge against the new version's git_sha "
                    + "instead of refusing. On conflict, leaves the working tree for resolution. "
                    + "Has no effect on non-git skills (which always go through the destructive "
                    + "backup + reinstall path with rollback on failure).")
    boolean merge;

    @Override
    public Integer call() throws Exception {
        int selfRc = 0;
        if (self) {
            selfRc = upgradeSkillManager();
            if (!all && (name == null || name.isBlank())) return selfRc;
        }

        if (!all && (name == null || name.isBlank())) {
            Log.error("usage: skill-manager upgrade <name> | --all | --self");
            return 2;
        }

        SkillStore store = SkillStore.defaultStore();
        store.init();
        if (registryUrl != null && !registryUrl.isBlank()) {
            RegistryConfig.resolve(store, registryUrl);
        }

        List<Skill> targets;
        if (all) {
            targets = store.listInstalled();
        } else {
            if (!store.contains(name)) {
                Log.error("not installed: %s", name);
                return 1;
            }
            targets = List.of(store.load(name).orElseThrow());
        }
        if (targets.isEmpty()) {
            Log.warn("no skills installed");
            return selfRc;
        }

        RegistryClient registry = RegistryClient.authenticated(store, RegistryConfig.resolve(store, null));

        int worstRc = 0;
        for (Skill s : targets) {
            int rc;
            try {
                rc = upgradeOne(store, registry, s);
            } catch (Exception e) {
                Log.error("upgrade %s failed: %s", s.name(), e.getMessage());
                rc = 5;
            }
            if (rc > worstRc) worstRc = rc;
        }
        return worstRc > 0 ? worstRc : selfRc;
    }

    /**
     * Returns 0 on success / no-op, or a sync-style exit code on failure:
     * 5 (legacy upgrade-failure / rollback), 7 (sync refused — re-run
     * with --merge), 8 (sync merge had conflicts the user must resolve).
     */
    private int upgradeOne(SkillStore store, RegistryClient registry, Skill installed) throws Exception {
        String currentVersion = installed.version();
        String latestVersion = fetchLatestVersion(registry, installed.name());
        if (latestVersion == null) {
            Log.warn("%s: registry has no latest version — skipping", installed.name());
            return 5;
        }
        if (currentVersion != null && currentVersion.equals(latestVersion)) {
            Log.ok("%s already at latest (%s)", installed.name(), currentVersion);
            return 0;
        }

        Log.step("upgrading %s: %s → %s",
                installed.name(),
                currentVersion == null ? "?" : currentVersion,
                latestVersion);

        // Git-tracked installs upgrade through the same fetch +
        // server_git_sha + merge path that `sync <name>` uses. That
        // preserves user edits (3-way merge instead of overwrite),
        // surfaces conflicts as exit 8 with the working tree left
        // for the user to resolve, and refuses dirty installs unless
        // --merge was passed. Non-git installs (legacy tarball
        // publishes, file: of a non-git directory) fall back to the
        // destructive backup + reinstall path because there's no
        // upstream branch to merge against.
        Path skillDir = store.skillDir(installed.name());
        if (GitOps.isGitRepo(skillDir) && GitOps.isAvailable()) {
            SyncCommand sync = new SyncCommand();
            sync.name = installed.name();
            sync.merge = merge;
            sync.registryUrl = registryUrl;
            Integer rc = sync.call();
            int code = (rc == null) ? 5 : rc;
            if (code == 0) {
                Log.ok("upgraded %s to %s (via git merge)", installed.name(), latestVersion);
            }
            return code;
        }

        Log.warn("%s: not git-tracked — falling back to backup + reinstall "
                + "(local edits to the store dir will be lost)", installed.name());
        return upgradeViaBackupReinstall(store, installed, currentVersion, latestVersion);
    }

    /**
     * Pre-merge fallback: move the install dir aside, run a fresh
     * install for the new version, restore on failure. Used for
     * non-git installs where there's no upstream to merge against.
     */
    private int upgradeViaBackupReinstall(SkillStore store, Skill installed,
                                          String currentVersion, String latestVersion) throws Exception {
        Path skillDir = store.skillDir(installed.name());
        Path backup = store.cacheDir().resolve(
                "upgrade-backup-" + installed.name() + "-" + System.currentTimeMillis());
        Fs.ensureDir(backup.getParent());
        Files.move(skillDir, backup, StandardCopyOption.ATOMIC_MOVE);

        InstallCommand inst = new InstallCommand();
        inst.source = installed.name();
        inst.version = latestVersion;
        inst.registryUrl = registryUrl;
        Integer rc;
        try {
            rc = inst.call();
        } catch (Exception e) {
            rollback(store, installed.name(), skillDir, backup);
            throw new IOException("upgrade " + installed.name() + " failed mid-install — rolled back", e);
        }
        if (rc == null || rc != 0) {
            rollback(store, installed.name(), skillDir, backup);
            Log.error("upgrade %s failed (install exit %s) — rolled back to %s",
                    installed.name(), rc, currentVersion);
            return 5;
        }

        try {
            Fs.deleteRecursive(backup);
        } catch (Exception e) {
            Log.warn("upgrade %s: failed to delete backup %s — %s",
                    installed.name(), backup, e.getMessage());
        }
        Log.ok("upgraded %s to %s (via backup + reinstall)", installed.name(), latestVersion);
        return 0;
    }

    private static void rollback(SkillStore store, String skillName, Path skillDir, Path backup) {
        try {
            if (Files.exists(skillDir)) Fs.deleteRecursive(skillDir);
            Files.move(backup, skillDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.error("ROLLBACK FAILED for %s: backup is at %s, store entry at %s — manual recovery needed: %s",
                    skillName, backup, skillDir, e.getMessage());
            return;
        }
        // Re-run the per-skill sync so the gateway and agents are pointed at
        // the restored version (the failed install may have re-registered
        // things under the new spec, and agent symlinks may dangle).
        try {
            SyncCommand sync = new SyncCommand();
            sync.name = skillName;
            sync.call();
        } catch (Exception e) {
            Log.warn("rollback sync for %s failed — %s", skillName, e.getMessage());
        }
    }

    /** @return the latest published version of {@code name}, or {@code null} if the registry lookup fails. */
    private static String fetchLatestVersion(RegistryClient registry, String name) {
        try {
            Map<String, Object> meta = registry.describeVersion(name, "latest");
            Object v = meta.get("version");
            return v == null ? null : v.toString();
        } catch (IOException e) {
            Log.warn("registry: failed to look up latest %s — %s", name, e.getMessage());
            return null;
        }
    }

    private static final String BREW_TAP = "haydenrear/skill-manager";
    private static final String BREW_FORMULA = BREW_TAP + "/skill-manager";

    /**
     * Run {@code brew tap haydenrear/skill-manager} (idempotent) followed by
     * {@code brew upgrade haydenrear/skill-manager/skill-manager}. The tap
     * step makes a first-time {@code --self} on a fresh machine work the
     * same as a re-upgrade — without it, brew would fail with "no such
     * formula".
     *
     * <p>Note: this rewrites the binary {@code jbang} is currently running.
     * Homebrew installs to a versioned cellar dir and atomically swaps the
     * symlink, so the in-flight process keeps executing the old image
     * (mmap'd), and the next invocation sees the new one.
     */
    private static int upgradeSkillManager() {
        Log.step("ensuring brew tap %s is added", BREW_TAP);
        int tapRc = runBrew("tap", BREW_TAP);
        if (tapRc != 0) {
            Log.error("brew tap %s exited %d", BREW_TAP, tapRc);
            return tapRc;
        }
        Log.step("upgrading skill-manager via brew upgrade %s", BREW_FORMULA);
        int upRc = runBrew("upgrade", BREW_FORMULA);
        if (upRc == 0) Log.ok("skill-manager upgraded");
        else Log.error("brew upgrade %s exited %d", BREW_FORMULA, upRc);
        return upRc;
    }

    private static int runBrew(String... brewArgs) {
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add("brew");
        for (String a : brewArgs) argv.add(a);
        try {
            Process p = new ProcessBuilder(argv).inheritIO().start();
            return p.waitFor();
        } catch (java.io.IOException e) {
            Log.error("brew not found on PATH — install Homebrew or upgrade manually: %s", e.getMessage());
            return 6;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.error("brew %s interrupted", String.join(" ", brewArgs));
            return 130;
        }
    }
}
