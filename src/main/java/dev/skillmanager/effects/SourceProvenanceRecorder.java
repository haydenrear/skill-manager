package dev.skillmanager.effects;

import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.SkillSource;
import dev.skillmanager.source.SkillSourceStore;
import dev.skillmanager.util.Log;

import java.nio.file.Path;

/**
 * Walks a freshly-committed {@link ResolvedGraph} and writes
 * {@code sources/<name>.json} for every skill, capturing the git remote
 * (if any), HEAD hash, install ref, and the {@link
 * SkillSource.InstallSource} routing the user used. Per-skill failures
 * are logged but never propagate — provenance is best-effort.
 */
public final class SourceProvenanceRecorder {

    private SourceProvenanceRecorder() {}

    public static void run(ResolvedGraph graph, EffectContext ctx) {
        SkillSourceStore sources = ctx.sourceStore();
        String now = SkillSourceStore.nowIso();
        for (ResolvedGraph.Resolved r : graph.resolved()) {
            try {
                Path skillDir = ctx.store().skillDir(r.name());
                SkillSource.Kind kind;
                String origin;
                String hash = null;
                String gitRef = null;
                if (GitOps.isGitRepo(skillDir)) {
                    kind = SkillSource.Kind.GIT;
                    String resolvedUrl = gitUrlFromSource(r.source());
                    if (resolvedUrl != null) {
                        GitOps.setOrigin(skillDir, resolvedUrl);
                        origin = resolvedUrl;
                    } else {
                        String filePath = filePathFromSource(r.source());
                        if (filePath != null && GitOps.isGitRepo(Path.of(filePath))) {
                            GitOps.setOrigin(skillDir, filePath);
                            origin = filePath;
                        } else {
                            origin = GitOps.originUrl(skillDir);
                        }
                    }
                    hash = GitOps.headHash(skillDir);
                    gitRef = GitOps.detectInstallRef(skillDir);
                } else {
                    kind = SkillSource.Kind.LOCAL_DIR;
                    origin = r.source();
                }
                SkillSource.InstallSource installSource = mapInstallSource(r.sourceKind());
                sources.write(new SkillSource(
                        r.name(), r.version(), kind, installSource,
                        origin, hash, gitRef, now, null));
            } catch (Exception ex) {
                Log.warn("could not record source provenance for %s: %s", r.name(), ex.getMessage());
            }
        }
    }

    private static SkillSource.InstallSource mapInstallSource(ResolvedGraph.SourceKind sk) {
        if (sk == null) return SkillSource.InstallSource.UNKNOWN;
        return switch (sk) {
            case REGISTRY -> SkillSource.InstallSource.REGISTRY;
            case GIT -> SkillSource.InstallSource.GIT;
            case LOCAL -> SkillSource.InstallSource.LOCAL_FILE;
        };
    }

    private static String gitUrlFromSource(String source) {
        if (source == null) return null;
        String s = source.trim();
        if (s.startsWith("github:")) {
            return "https://github.com/" + s.substring("github:".length()) + ".git";
        }
        if (s.startsWith("git+")) return s.substring("git+".length());
        if (s.startsWith("ssh://") || s.startsWith("git@") || s.endsWith(".git")) return s;
        return null;
    }

    private static String filePathFromSource(String source) {
        if (source == null) return null;
        String s = source.trim();
        if (s.startsWith("file:")) s = s.substring("file:".length());
        else if (!s.startsWith("/") && !s.startsWith("./") && !s.startsWith("../")) return null;
        try {
            return Path.of(s).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return null;
        }
    }
}
