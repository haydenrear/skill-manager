package dev.skillmanager.commands;

import dev.skillmanager.model.Skill;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code upgrade [name|--all] [--self] [--merge]} — version bump for
 * git-tracked skills via {@link SyncCommand}, plus optional Homebrew
 * upgrade of the CLI itself.
 *
 * <p>Always delegates to sync per skill (no version-match short-circuit) so
 * MCP / CLI / agent state converges to the post-merge manifests every time —
 * a previous {@code sync} may have already moved the content but skipped a
 * step. Sync handles the registry → git_sha lookup, github-direct fallback,
 * and dirty-state guards.
 *
 * <p>Non-git installs are not supported — convert them to a git source first.
 */
@Command(name = "upgrade",
        description = "Upgrade installed git-tracked skills (and optionally skill-manager itself).")
public final class UpgradeCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Skill name to upgrade")
    String name;

    @Option(names = "--all", description = "Upgrade every installed skill.")
    boolean all;

    @Option(names = {"--self", "--skill-manager"},
            description = "Upgrade the skill-manager CLI via Homebrew.")
    boolean self;

    @Option(names = "--registry",
            description = "Registry URL override for this invocation (persisted).")
    String registryUrl;

    @Option(names = "--merge",
            description = "Forwarded to sync: when local edits exist, run a 3-way merge against "
                    + "the new version's git_sha instead of refusing.")
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

        int worstRc = 0;
        for (Skill s : targets) {
            int rc;
            try { rc = upgradeOne(store, s); }
            catch (Exception e) {
                Log.error("upgrade %s failed: %s", s.name(), e.getMessage());
                rc = 5;
            }
            if (rc > worstRc) worstRc = rc;
        }
        return worstRc > 0 ? worstRc : selfRc;
    }

    private int upgradeOne(SkillStore store, Skill installed) throws Exception {
        if (!GitOps.isGitRepo(store.skillDir(installed.name())) || !GitOps.isAvailable()) {
            Log.error("%s: not git-tracked — only git-tracked installs can be upgraded. "
                    + "Reinstall from a github source to enable upgrades.", installed.name());
            return 5;
        }
        SyncCommand sync = new SyncCommand();
        sync.name = installed.name();
        sync.merge = merge;
        sync.registryUrl = registryUrl;
        Integer rc = sync.call();
        return rc == null ? 5 : rc;
    }

    private static final String BREW_TAP = "haydenrear/skill-manager";
    private static final String BREW_FORMULA = BREW_TAP + "/skill-manager";

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
            Log.error("brew not found on PATH: %s", e.getMessage());
            return 6;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }
}
