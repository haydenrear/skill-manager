package dev.skillmanager.effects;

import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.util.Log;

import java.nio.file.Path;

/**
 * Walks a freshly-committed {@link ResolvedGraph} and writes
 * {@code sources/<name>.json} for every skill, capturing the git remote
 * (if any), HEAD hash, install ref, and the {@link
 * InstalledUnit.InstallSource} routing the user used. Per-skill failures
 * are logged but never propagate — provenance is best-effort.
 */
public final class SourceProvenanceRecorder {

    private SourceProvenanceRecorder() {}

    public static void run(ResolvedGraph graph, EffectContext ctx) {
        UnitStore sources = ctx.sourceStore();
        String now = UnitStore.nowIso();
        for (ResolvedGraph.Resolved r : graph.resolved()) {
            try {
                // Kind-aware dir lookup — plugins live under plugins/<name>,
                // skills under skills/<name>. The pre-ticket-11 path always
                // hit skillDir, which left every plugin's installed-record
                // both inspecting the wrong dir for git/hash/ref AND
                // hard-coding {@code unitKind=SKILL}. The latter made the
                // RemoveUseCase (which reads {@code unitKind} from the
                // record) treat every plugin as a skill at uninstall time —
                // RemoveUnitFromStore then no-oped silently because
                // {@code skills/<plugin-name>} didn't exist.
                dev.skillmanager.model.UnitKind unitKind = r.unit().kind();
                Path unitDir = ctx.store().unitDir(r.name(), unitKind);
                InstalledUnit.Kind kind;
                String origin;
                String hash = null;
                String gitRef = null;
                if (GitOps.isGitRepo(unitDir)) {
                    kind = InstalledUnit.Kind.GIT;
                    String resolvedUrl = gitUrlFromSource(r.source());
                    if (resolvedUrl != null) {
                        GitOps.setOrigin(unitDir, resolvedUrl);
                        origin = resolvedUrl;
                    } else {
                        String filePath = filePathFromSource(r.source());
                        if (filePath != null && GitOps.isGitRepo(Path.of(filePath))) {
                            GitOps.setOrigin(unitDir, filePath);
                            origin = filePath;
                        } else {
                            origin = GitOps.originUrl(unitDir);
                        }
                    }
                    hash = GitOps.headHash(unitDir);
                    gitRef = GitOps.detectInstallRef(unitDir);
                } else {
                    kind = InstalledUnit.Kind.LOCAL_DIR;
                    origin = r.source();
                }
                InstalledUnit.InstallSource installSource = mapInstallSource(r.sourceKind());
                sources.write(new InstalledUnit(
                        r.name(), r.version(), kind, installSource,
                        origin, hash, gitRef, now, null,
                        unitKind));
            } catch (Exception ex) {
                Log.warn("could not record source provenance for %s: %s", r.name(), ex.getMessage());
            }
        }
    }

    private static InstalledUnit.InstallSource mapInstallSource(ResolvedGraph.SourceKind sk) {
        if (sk == null) return InstalledUnit.InstallSource.UNKNOWN;
        return switch (sk) {
            case REGISTRY -> InstalledUnit.InstallSource.REGISTRY;
            case GIT -> InstalledUnit.InstallSource.GIT;
            case LOCAL -> InstalledUnit.InstallSource.LOCAL_FILE;
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
