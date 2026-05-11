package dev.skillmanager.store;

import dev.skillmanager.shared.util.Archives;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Log;
import org.eclipse.jgit.api.Git;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves a skill source to a local directory containing a {@code SKILL.md}.
 *
 * <p>Returns a {@link FetchResult} with staging path + size/digest when available
 * so the planner can show a download map before anything is committed.
 */
public final class Fetcher {

    private Fetcher() {}

    public record FetchResult(
            Path dir,
            ResolvedGraph.SourceKind kind,
            long bytesDownloaded,
            String sha256
    ) {}

    public static FetchResult fetch(String source, String version, Path workspace, SkillStore store) throws IOException {
        String trimmed = source == null ? "" : source.trim();
        if (trimmed.isEmpty()) throw new IOException("empty unit source");

        // Track whether the user explicitly named a local source — i.e.
        // anything that ISN'T a bare {@code name} for registry lookup.
        // We use this to decide whether a "directory not found" should
        // fail fast (explicit local) or fall through to the registry
        // (bare name). Without this distinction, {@code install
        // file:./typo-or-missing-dir} would silently fall through and
        // surface as a confusing "registry unreachable" or HTTP 404
        // when the directory genuinely just doesn't exist.
        boolean explicitLocal = trimmed.startsWith("file:")
                || trimmed.startsWith("./")
                || trimmed.startsWith("../")
                || trimmed.startsWith("/");

        String normalized = trimmed;
        if (normalized.startsWith("file:")) normalized = normalized.substring("file:".length());

        if (normalized.startsWith("github:")) {
            // User input "github:owner/repo" → https://github.com/owner/repo.git.
            // If they typed "github:owner/repo.git" (copy-pasted from a clone
            // URL), don't double-append — that would produce repo.git.git
            // and JGit would chase the wrong URL.
            String body = normalized.substring("github:".length());
            String url = "https://github.com/" + body;
            if (!url.endsWith(".git")) url = url + ".git";
            return new FetchResult(
                    gitClone(url, version, workspace),
                    ResolvedGraph.SourceKind.GIT, 0, null);
        }
        if (normalized.startsWith("git+")) {
            return new FetchResult(gitClone(normalized.substring("git+".length()), version, workspace),
                    ResolvedGraph.SourceKind.GIT, 0, null);
        }
        if (normalized.endsWith(".git") || normalized.startsWith("ssh://") || normalized.startsWith("git@")) {
            return new FetchResult(gitClone(normalized, version, workspace), ResolvedGraph.SourceKind.GIT, 0, null);
        }

        Path localCandidate = Path.of(normalized);
        if (Files.isDirectory(localCandidate)) {
            Path staged = workspace.resolve("staged");
            if (Files.exists(staged)) Fs.deleteRecursive(staged);
            Fs.ensureDir(staged);
            Fs.copyRecursive(localCandidate, staged);
            return new FetchResult(locateSkillRoot(staged), ResolvedGraph.SourceKind.LOCAL, 0, null);
        }

        if (explicitLocal) {
            // User asked for a local source explicitly. The registry
            // lookup is not a meaningful fallback here — it would
            // either time out, 404, or surface a misleading "registry
            // unreachable" banner that hides the actual mistake (typo
            // in path, wrong CWD, deleted dir).
            String detail = Files.exists(localCandidate)
                    ? "exists but is not a directory"
                    : "does not exist";
            throw new IOException(
                    "local source " + detail + ": " + localCandidate.toAbsolutePath()
                            + " (parsed from '" + source + "')");
        }

        return fetchFromRegistry(normalized, version, workspace, store);
    }

    private static FetchResult fetchFromRegistry(String coord, String version, Path workspace, SkillStore store) throws IOException {
        String name = coord;
        String resolvedVersion = version;
        int at = coord.indexOf('@');
        if (at >= 0) {
            name = coord.substring(0, at);
            String ver = coord.substring(at + 1);
            if (resolvedVersion == null || resolvedVersion.isBlank()) resolvedVersion = ver;
        }

        RegistryConfig cfg = RegistryConfig.resolve(store, null);
        RegistryClient client = RegistryClient.authenticated(store, cfg);
        Log.step("registry: resolving %s%s from %s", name,
                resolvedVersion == null ? "" : "@" + resolvedVersion, cfg.baseUrl());

        // The registry is metadata-only by default; ask which github URL +
        // SHA backs this version, then clone directly from there.
        java.util.Map<String, Object> meta = client.describeVersion(
                name, resolvedVersion == null || resolvedVersion.isBlank() ? "latest" : resolvedVersion);
        String githubUrl = (String) meta.get("github_url");
        if (githubUrl != null && !githubUrl.isBlank()) {
            String gitSha = (String) meta.get("git_sha");
            String gitRef = (String) meta.get("git_ref");
            String checkoutRef = gitSha != null && !gitSha.isBlank() ? gitSha : gitRef;
            Log.step("registry → github: clone %s @ %s", githubUrl,
                    checkoutRef == null ? "HEAD" : checkoutRef);
            return new FetchResult(
                    gitClone(githubUrl + ".git", checkoutRef, workspace),
                    ResolvedGraph.SourceKind.REGISTRY, 0, gitSha);
        }

        // Legacy tarball-published version (only reachable when the server
        // has skill-registry.publish.allow-file-upload=true and an old row
        // is being installed).
        Path tar = workspace.resolve(name + ".tar.gz");
        long bytes = client.download(name, resolvedVersion, tar);
        String sha = sha256(tar);

        Path extract = workspace.resolve("extracted");
        Fs.ensureDir(extract);
        Archives.extractTarGz(tar, extract);
        return new FetchResult(locateSkillRoot(extract), ResolvedGraph.SourceKind.REGISTRY, bytes, sha);
    }

    /**
     * Clone a git source into {@code workspace/clone/} and check out
     * {@code ref} if provided. Prefers the host's {@code git} binary
     * (which transparently honors {@code ~/.ssh/config}, ssh-agent,
     * git-credential-helper, {@code ~/.netrc}, etc.) so private repos
     * and SSH URLs Just Work without any in-JVM credential plumbing.
     * Falls back to JGit only when {@code git} isn't on PATH (CI
     * containers without a system git, etc.).
     *
     * <p>Authentication-flavored failures are surfaced as
     * {@link GitCloneAuthException} with the URL embedded, so the CLI
     * can render a stable {@code ACTION_REQUIRED}-style banner instead
     * of leaking the underlying transport stack trace.
     */
    private static Path gitClone(String url, String ref, Path workspace) throws IOException {
        Path target = workspace.resolve("clone");
        if (Files.exists(target)) Fs.deleteRecursive(target);
        Fs.ensureDir(workspace);
        Log.step("cloning %s", url);

        // SSH-shape URLs ("git@host:owner/repo" or "ssh://...") can
        // ONLY succeed via a subprocess that has access to the user's
        // SSH agent — JGit would need an explicit SshSessionFactory
        // we don't set up. So when we detect SSH, require git on PATH.
        boolean isSsh = url.startsWith("git@") || url.startsWith("ssh://");
        boolean haveGit = gitOnPath();
        if (isSsh && !haveGit) {
            throw new GitFetcherException(url,
                    "ssh-style git URL requires the `git` CLI on PATH "
                            + "(JGit cannot authenticate against your ssh-agent / "
                            + "~/.ssh/config): " + url,
                    null);
        }

        if (haveGit) {
            cloneViaSubprocess(url, ref, target);
        } else {
            cloneViaJGit(url, ref, target);
        }
        return locateSkillRoot(target);
    }

    private static boolean gitOnPath() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return false;
        for (String part : path.split(java.io.File.pathSeparator)) {
            if (part.isBlank()) continue;
            Path candidate = Path.of(part, "git");
            if (Files.isExecutable(candidate)) return true;
        }
        return false;
    }

    private static void cloneViaSubprocess(String url, String ref, Path target) throws IOException {
        SubprocessResult clone = runGit(null, List.of("git", "clone", "--", url, target.toString()));
        if (clone.exit != 0) {
            if (looksLikeAuthFailure(clone.combined)) {
                throw new GitCloneAuthException(url, summarizeFirstLines(clone.combined), null);
            }
            throw new GitFetcherException(url,
                    "git clone " + url + " failed (rc=" + clone.exit + "):\n" + clone.combined,
                    null);
        }
        if (ref != null && !ref.isBlank()) {
            SubprocessResult checkout = runGit(target, List.of("git", "checkout", ref));
            if (checkout.exit != 0) {
                throw new GitFetcherException(url,
                        "git checkout " + ref + " failed in " + target
                                + " (rc=" + checkout.exit + "):\n" + checkout.combined,
                        null);
            }
        }
    }

    private static void cloneViaJGit(String url, String ref, Path target) throws IOException {
        try (Git git = Git.cloneRepository().setURI(url).setDirectory(target.toFile()).call()) {
            if (ref != null && !ref.isBlank()) {
                git.checkout().setName(ref).call();
            }
        } catch (org.eclipse.jgit.api.errors.TransportException te) {
            // JGit's TransportException is the canonical "credentials
            // refused / not provided" surface. Wrap it as
            // GitCloneAuthException so the CLI top-level handler can
            // print an actionable banner instead of leaking the stack.
            String detail = te.getMessage();
            if (looksLikeAuthFailure(detail)) {
                throw new GitCloneAuthException(url, detail, te);
            }
            throw new GitFetcherException(url,
                    "git clone " + url + " failed via JGit: " + detail, te);
        } catch (GitFetcherException gfe) {
            throw gfe;
        } catch (Exception e) {
            throw new GitFetcherException(url, "git clone " + url + " failed via JGit", e);
        }
    }

    /**
     * Heuristic match for the stderr/message patterns git + JGit
     * surface when credentials are missing or rejected. False
     * negatives (a real auth failure that didn't match any pattern)
     * just fall through to a regular {@link IOException} with the
     * full stderr embedded — still better than the pre-fix
     * propagated-TransportException, just without the dedicated
     * exit code. False positives would mis-classify a transient
     * network blip as auth — the patterns are conservative enough
     * (named auth concepts) that this is unlikely.
     */
    private static final Pattern AUTH_PATTERN = Pattern.compile(
            "(?i)\\b("
                    + "permission denied|"
                    + "authentication required|"
                    + "authentication failed|"
                    + "could not read username|"
                    + "could not read password|"
                    + "no credentialsprovider has been registered|"
                    + "publickey|"
                    + "host key verification failed|"
                    + "remote: invalid username or password|"
                    + "repository not found|"   // github 404s private repos when unauth'd
                    + "401\\b|403\\b"
                    + ")");

    static boolean looksLikeAuthFailure(String message) {
        if (message == null || message.isBlank()) return false;
        return AUTH_PATTERN.matcher(message).find();
    }

    /** First non-blank line of {@code text}, truncated for banner display. */
    private static String summarizeFirstLines(String text) {
        if (text == null || text.isBlank()) return "authentication required";
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isBlank()) return t.length() > 240 ? t.substring(0, 240) + "…" : t;
        }
        return "authentication required";
    }

    private record SubprocessResult(int exit, String combined) {}

    private static SubprocessResult runGit(Path workdir, List<String> argv) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        if (workdir != null) pb.directory(workdir.toFile());
        // Pin git into a non-interactive mode: never prompt for a
        // password (we'd hang the CLI), never trip ssh's
        // known_hosts-add prompt. ssh-agent / configured identities
        // still resolve normally.
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        // Don't clobber a user-set GIT_SSH_COMMAND — they may have
        // pointed it at a wrapper. Only add the BatchMode flag if
        // nothing's already there.
        pb.environment().putIfAbsent("GIT_SSH_COMMAND",
                "ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new");
        try {
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            int rc = p.waitFor();
            return new SubprocessResult(rc, out.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git subprocess interrupted: " + argv, e);
        }
    }

    private static Path locateSkillRoot(Path dir) throws IOException {
        // Plugin first — a plugin's bundle has .claude-plugin/plugin.json at
        // the unit root plus a skills/<contained>/SKILL.md inside. Without
        // the plugin probe we'd descend into the contained skill and
        // install it as a top-level skill, dropping the plugin manifest
        // entirely. Same fix as Resolver.resolveAll's unit-aware parse.
        if (dev.skillmanager.model.PluginParser.looksLikePlugin(dir)) return dir;
        if (Files.isRegularFile(dir.resolve(SkillParser.SKILL_FILENAME))) return dir;
        // Walk to depth 3 looking for a SKILL.md or a plugin manifest. If
        // a bundle has both at different depths (e.g. a one-extra-wrapper
        // case), prefer the plugin layout.
        try (var s = Files.walk(dir, 3)) {
            var matches = s.filter(p -> {
                String n = p.getFileName() == null ? "" : p.getFileName().toString();
                return (n.equals(SkillParser.SKILL_FILENAME) && Files.isRegularFile(p))
                        || (n.equals("plugin.json") && Files.isRegularFile(p)
                                && p.getParent() != null
                                && ".claude-plugin".equals(p.getParent().getFileName().toString()));
            }).toList();
            // Plugin match wins (parent of plugin.json's parent dir).
            for (Path m : matches) {
                if ("plugin.json".equals(m.getFileName().toString())) {
                    Path pluginRoot = m.getParent().getParent();
                    return pluginRoot != null ? pluginRoot : dir;
                }
            }
            for (Path m : matches) {
                if (SkillParser.SKILL_FILENAME.equals(m.getFileName().toString())) {
                    return m.getParent();
                }
            }
            return dir;
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }
}
