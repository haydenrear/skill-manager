package dev.skillmanager.commands;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.PluginParser;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.registry.SkillPackager;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Log;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Register a skill with the registry. Default: github-pointer publish — auto-detect
 * the local repo's {@code remote.origin.url}, find a tag matching {@code v<version>}
 * (or pass {@code --ref} for branch / SHA), POST to {@code /skills/register}. The
 * server resolves the ref to a SHA and persists the pointer.
 *
 * <p>Legacy: pass {@code --upload-tarball} to bundle the directory and POST it to
 * the multipart endpoint. Requires the server to set
 * {@code skill-registry.publish.allow-file-upload=true}.
 */
@Command(name = "publish",
        description = "Register a unit (skill or plugin) with the registry (github-pointer by "
                + "default). Kind is detected from the source directory: `.claude-plugin/"
                + "plugin.json` at the root publishes as a plugin; `SKILL.md` at the root "
                + "publishes as a skill.")
public final class PublishCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1",
            description = "Unit source directory — skill or plugin (defaults to current "
                    + "working directory). Kind detected from the directory shape.")
    Path skillDir;

    @Option(names = "--ref",
            description = "Git ref to publish (tag, branch, or SHA). "
                    + "Default: tag matching v<version> from skill-manager.toml.")
    String refOverride;

    @Option(names = "--github-url",
            description = "Override the github URL discovered from `remote.origin.url`.")
    String githubUrlOverride;

    @Option(names = "--upload-tarball",
            description = "Use the legacy multipart-upload backend (server must allow it).")
    boolean uploadTarball;

    @Option(names = "--version",
            description = "Override version for --upload-tarball backend; ignored otherwise.")
    String versionOverride;

    @Option(names = "--registry", description = "Registry base URL override")
    String registryUrl;

    @Option(names = "--dry-run", description = "Print what would be sent without registering")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();

        Path src = (skillDir == null ? Path.of(System.getProperty("user.dir")) : skillDir).toAbsolutePath();
        // Kind-aware: plugins have .claude-plugin/plugin.json, skills have
        // SKILL.md at the root. Either is publishable; the bundle inclusion
        // list switches per kind inside SkillPackager.pack.
        SkillPackager.Kind kind;
        try {
            kind = SkillPackager.detectKind(src);
        } catch (IOException ex) {
            Log.error("not a publishable unit dir (need %s or %s): %s",
                    SkillParser.SKILL_FILENAME, PluginParser.PLUGIN_JSON_PATH, src);
            return 1;
        }
        AgentUnit unit = (kind == SkillPackager.Kind.PLUGIN)
                ? PluginParser.load(src)
                : SkillParser.load(src).asUnit();
        return uploadTarball ? publishViaTarball(store, src, unit) : publishViaGithub(store, src, unit);
    }

    private int publishViaGithub(SkillStore store, Path src, AgentUnit unit) throws IOException {
        String version = unit.version();
        if (version == null || version.isBlank()) version = "0.0.1";

        String githubUrl = githubUrlOverride != null
                ? githubUrlOverride
                : detectGithubRemote(src);
        if (githubUrl == null) {
            Log.error("no github remote found in %s — set `remote.origin.url`, pass --github-url, "
                    + "or pass --upload-tarball for the legacy backend.", src);
            return 1;
        }

        String ref = refOverride != null ? refOverride : pickTagForVersion(src, version);
        if (ref == null) {
            Log.error("no git tag matching version '%s' (looked for v%s, %s). "
                    + "Tag the commit before publishing or pass --ref.", version, version, version);
            return 1;
        }

        Log.info("unit:         %s@%s (%s)", unit.name(), version, unit.kind().name().toLowerCase());
        Log.info("github_url:   %s", githubUrl);
        Log.info("git_ref:      %s", ref);

        if (dryRun) {
            Log.info("--dry-run: not registering");
            return 0;
        }

        RegistryConfig cfg = RegistryConfig.resolve(store, registryUrl);
        RegistryClient client = RegistryClient.authenticated(store, cfg);
        if (!client.ping()) {
            Log.error("registry not reachable at %s", cfg.baseUrl());
            return 2;
        }

        Map<String, Object> result = client.registerGithub(githubUrl, ref);
        Log.ok("registered %s@%s", result.get("name"), result.get("version"));
        Log.info("git_sha:      %s", result.get("git_sha"));
        return 0;
    }

    private int publishViaTarball(SkillStore store, Path src, AgentUnit unit) throws IOException {
        String effectiveVersion = versionOverride != null ? versionOverride : unit.version();
        if (effectiveVersion == null || effectiveVersion.isBlank()) {
            Log.error("unit has no version (set [skill].version / [plugin].version or pass --version)");
            return 1;
        }

        Path outDir = store.cacheDir().resolve("publish");
        Fs.ensureDir(outDir);
        Path tar = SkillPackager.pack(src, outDir);
        Log.ok("packaged %s (%d bytes) → %s", unit.name(), Files.size(tar), tar);

        if (dryRun) {
            Log.info("--dry-run: not uploading");
            return 0;
        }

        RegistryConfig cfg = RegistryConfig.resolve(store, registryUrl);
        RegistryClient client = RegistryClient.authenticated(store, cfg);
        if (!client.ping()) {
            Log.error("registry not reachable at %s", cfg.baseUrl());
            return 2;
        }

        var result = client.publish(unit.name(), effectiveVersion, tar);
        Log.ok("published %s@%s (sha256=%s, %d bytes)", result.name(), result.version(), result.sha256(), result.sizeBytes());
        Log.info("download: %s%s", cfg.baseUrl(), result.downloadUrl());
        return 0;
    }

    /** Read {@code remote.origin.url} from the .git config of the enclosing repo. */
    static String detectGithubRemote(Path skillDir) {
        try (Repository repo = new FileRepositoryBuilder()
                .findGitDir(skillDir.toFile())
                .readEnvironment()
                .setMustExist(true)
                .build()) {
            StoredConfig cfg = repo.getConfig();
            String url = cfg.getString("remote", "origin", "url");
            return normalizeGithubUrl(url);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Convert {@code git@github.com:o/r.git} / {@code https://github.com/o/r.git} → {@code https://github.com/o/r}. */
    static String normalizeGithubUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String s = url.trim();
        if (s.startsWith("git@github.com:")) {
            s = "https://github.com/" + s.substring("git@github.com:".length());
        } else if (s.startsWith("ssh://git@github.com/")) {
            s = "https://github.com/" + s.substring("ssh://git@github.com/".length());
        } else if (s.startsWith("github:")) {
            s = "https://github.com/" + s.substring("github:".length());
        }
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.startsWith("https://github.com/") && !s.startsWith("http://github.com/")) {
            return null;
        }
        return s;
    }

    /** Look for a tag matching {@code v<version>} (preferred) or {@code <version>}. */
    static String pickTagForVersion(Path skillDir, String version) {
        File gitDir = new FileRepositoryBuilder()
                .findGitDir(skillDir.toFile())
                .getGitDir();
        if (gitDir == null) return null;
        try (Repository repo = new FileRepositoryBuilder().setGitDir(gitDir).build();
             Git git = new Git(repo)) {
            List<Ref> tags = git.tagList().call();
            for (String candidate : List.of("v" + version, version)) {
                String want = "refs/tags/" + candidate;
                for (Ref t : tags) {
                    if (t.getName().equals(want)) return candidate;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }
}
