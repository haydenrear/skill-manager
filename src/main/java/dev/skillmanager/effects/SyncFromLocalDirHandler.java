package dev.skillmanager.effects;

import dev.skillmanager.model.SkillParser;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.SkillSource;
import dev.skillmanager.source.SkillSourceStore;
import dev.skillmanager.store.SkillStore;

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
 * <p>User-facing output is the renderer's job — the diff lines and the
 * confirm prompt are direct stdin/stdout flow, not Log.* calls. Outcomes
 * surface via {@link ContextFact.SyncGitMerged} / {@link
 * ContextFact.SyncGitConflicted} / {@link ContextFact.SyncGitRefused} /
 * {@link ContextFact.SyncGitFailed} on the receipt.
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
            return EffectReceipt.failed(e, "--from is not a directory: " + src);
        }
        if (!Files.isRegularFile(src.resolve(SkillParser.SKILL_FILENAME))) {
            return EffectReceipt.failed(e, "--from " + src + " is not a skill directory (missing "
                    + SkillParser.SKILL_FILENAME + ")");
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
                        new ContextFact.SyncGitConflicted(skillName, mr.conflictedFiles()));
                default -> EffectReceipt.failed(e,
                        java.util.List.of(new ContextFact.SyncGitFailed(skillName, "merge rc=" + mr.rc())),
                        "merge failed");
            };
        }
        if (storeIsGit && GitOps.isAvailable()) {
            String baseline = sourceHash(store, skillName);
            boolean dirty = GitOps.isDirty(storeDir, baseline);
            if (dirty && !e.merge()) {
                return EffectReceipt.partial(e, "extra local changes — re-run with --merge",
                        new ContextFact.SyncGitRefused(skillName, src.toString(), false));
            }
            if (e.merge() && !srcIsGit && dirty) {
                return EffectReceipt.partial(e, "source not git, store dirty",
                        new ContextFact.SyncGitRefused(skillName, src.toString(), false));
            }
        }

        StringBuilder summary = new StringBuilder();
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "--no-index", "--name-status",
                "--", storeDir.toString(), src.toString())
                .redirectErrorStream(true);
        Process p;
        try { p = pb.start(); }
        catch (IOException ex) {
            return EffectReceipt.failed(e, "`git` not available on PATH: " + ex.getMessage());
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
            return EffectReceipt.ok(e);
        }
        if (rc != 1) {
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
        // Refresh the source record so its gitHash matches what's actually
        // on disk now. Without this, a future `sync` would compare against
        // the pre-copy baseline and report bogus drift / refuse the merge.
        if (GitOps.isGitRepo(storeDir)) {
            String newHash = GitOps.headHash(storeDir);
            if (newHash != null) {
                ctx.source(skillName).ifPresent(old -> {
                    try {
                        ctx.writeSource(old.withGitMoved(newHash, SkillSourceStore.nowIso()));
                    } catch (IOException ignored) {}
                });
            }
        }
        return EffectReceipt.ok(e);
    }

    private static String sourceHash(SkillStore store, String skillName) {
        return new SkillSourceStore(store).read(skillName)
                .map(SkillSource::gitHash).orElse(null);
    }
}
