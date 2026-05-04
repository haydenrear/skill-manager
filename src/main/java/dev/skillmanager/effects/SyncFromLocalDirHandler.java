package dev.skillmanager.effects;

import dev.skillmanager.model.SkillParser;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.SkillSource;
import dev.skillmanager.source.SkillSourceStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Handler for {@link SkillEffect.SyncFromLocalDir} — drives the
 * {@code sync --from <dir>} apply path. Runs the diff, prompts on stdin
 * when {@code !yes && !merge}, and either copies the directory verbatim
 * or delegates to {@link SyncGitHandler#runMerge} for the
 * {@code --merge} 3-way variant.
 *
 * <p>Receipt facts: {@link ContextFact.SyncGitMerged} on a successful
 * merge, {@link ContextFact.SyncGitConflicted} on a conflict, or no
 * specific fact on a successful copy / no-op (the OK status is enough).
 */
public final class SyncFromLocalDirHandler {

    private SyncFromLocalDirHandler() {}

    public static EffectReceipt run(SkillEffect.SyncFromLocalDir e, EffectContext ctx) {
        SkillStore store = ctx.store();
        String skillName = e.skillName();
        Path src;
        try {
            src = e.fromDir().toAbsolutePath().normalize();
        } catch (Exception ex) {
            return EffectReceipt.failed(e, "invalid --from path: " + ex.getMessage());
        }
        if (!Files.isDirectory(src)) {
            Log.error("--from is not a directory: %s", src);
            return EffectReceipt.failed(e, "--from is not a directory: " + src);
        }
        if (!Files.isRegularFile(src.resolve(SkillParser.SKILL_FILENAME))) {
            Log.error("--from %s is not a skill directory (missing %s)", src, SkillParser.SKILL_FILENAME);
            return EffectReceipt.failed(e, "missing " + SkillParser.SKILL_FILENAME);
        }
        Path storeDir = store.skillDir(skillName);
        boolean storeIsGit = GitOps.isGitRepo(storeDir);
        boolean srcIsGit = GitOps.isGitRepo(src);

        if (storeIsGit && GitOps.isAvailable() && e.merge() && srcIsGit) {
            SyncGitHandler.MergeResult mr =
                    SyncGitHandler.runMerge(ctx, storeDir, src.toString(), "HEAD", skillName);
            return switch (mr.rc()) {
                case 0 -> EffectReceipt.ok(e, new ContextFact.SyncGitMerged(skillName, mr.fetchedHash()));
                case 8 -> EffectReceipt.partial(e, "merge conflict",
                        new ContextFact.SyncGitConflicted(skillName));
                default -> EffectReceipt.failed(e,
                        java.util.List.of(new ContextFact.SyncGitFailed(skillName, "merge rc=" + mr.rc())),
                        "merge failed");
            };
        }
        if (storeIsGit && GitOps.isAvailable()) {
            String baseline = sourceHash(store, skillName);
            boolean dirty = GitOps.isDirty(storeDir, baseline);
            if (dirty && !e.merge()) {
                printMergeInstructions(skillName, storeDir, src.toString(), srcIsGit, e.merge(), false);
                return EffectReceipt.partial(e, "extra local changes — re-run with --merge",
                        new ContextFact.SyncGitRefused(skillName));
            }
            if (e.merge() && !srcIsGit && dirty) {
                printMergeInstructions(skillName, storeDir, src.toString(), false, e.merge(), false);
                return EffectReceipt.partial(e, "source not git, store dirty",
                        new ContextFact.SyncGitRefused(skillName));
            }
        }

        Log.step("git diff --no-index --name-status %s %s", storeDir, src);
        StringBuilder summary = new StringBuilder();
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "--no-index", "--name-status",
                "--", storeDir.toString(), src.toString())
                .redirectErrorStream(true);
        Process p;
        try { p = pb.start(); }
        catch (IOException ex) {
            Log.error("`git` not available on PATH: %s", ex.getMessage());
            return EffectReceipt.failed(e, ex.getMessage());
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                System.out.println(line);
                summary.append(line).append('\n');
            }
        } catch (IOException ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
        int rc;
        try { rc = p.waitFor(); }
        catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return EffectReceipt.failed(e, "interrupted");
        }
        if (rc == 0 || summary.length() == 0) {
            Log.ok("%s: store and %s are identical — nothing to apply", skillName, src);
            return EffectReceipt.ok(e);
        }
        if (rc != 1) {
            Log.error("`git diff --no-index --name-status` exited %d", rc);
            return EffectReceipt.failed(e, "git diff exit " + rc);
        }

        System.out.println();
        System.out.println("To inspect the full diff, run:");
        System.out.println();
        System.out.println("    git diff --no-index " + storeDir + " " + src);
        System.out.println();

        if (!e.yes()) {
            System.out.print("Apply these changes to " + storeDir + "? [y/N] ");
            System.out.flush();
            try {
                BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
                String input = stdin.readLine();
                if (input == null || !input.trim().toLowerCase().startsWith("y")) {
                    Log.warn("aborted; no changes applied");
                    return EffectReceipt.skipped(e, "user declined");
                }
            } catch (IOException ex) {
                return EffectReceipt.failed(e, "could not read stdin: " + ex.getMessage());
            }
        }
        try {
            Fs.deleteRecursive(storeDir);
            Fs.copyRecursive(src, storeDir);
        } catch (IOException ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
        Log.ok("%s: applied changes from %s", skillName, src);
        return EffectReceipt.ok(e);
    }

    private static String sourceHash(SkillStore store, String skillName) {
        return new SkillSourceStore(store).read(skillName)
                .map(SkillSource::gitHash).orElse(null);
    }

    private static void printMergeInstructions(String skillName, Path storeDir, String upstream,
                                               boolean upstreamIsGit, boolean userMerge,
                                               boolean gitLatest) {
        Log.error("%s has extra local changes (working tree edits or commits ahead of installed baseline).",
                skillName);
        System.err.println();
        if (upstreamIsGit) {
            System.err.println("Sync would overwrite them. Re-run with --merge:");
            System.err.println();
            String flags = " --from " + upstream + (gitLatest ? " --git-latest" : "") + " --merge";
            System.err.println("    skill-manager sync " + skillName + flags);
            System.err.println();
            System.err.println("Or merge by hand:");
            System.err.println();
            System.err.println("    cd " + storeDir);
            System.err.println("    git fetch " + upstream + " HEAD");
            System.err.println("    git merge FETCH_HEAD");
        } else {
            System.err.println("Source dir is not a git repo — no upstream branch to merge.");
            System.err.println("Inspect the diff and apply changes by hand:");
            System.err.println();
            System.err.println("    git diff --no-index " + storeDir + " " + upstream);
        }
        System.err.println();
    }
}
