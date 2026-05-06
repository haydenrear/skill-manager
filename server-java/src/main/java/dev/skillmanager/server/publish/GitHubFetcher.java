package dev.skillmanager.server.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillmanager.shared.util.BundleMetadata;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the {@code skill-manager.toml} + {@code SKILL.md} out of a GitHub
 * repo at a given ref using the public REST API. No clone, no .git dir, no
 * disk-writes — the tarball stream from {@code repos/{o}/{r}/tarball/{ref}}
 * is parsed in-memory and only the two interesting files are kept.
 *
 * <p>An optional {@code GITHUB_TOKEN} env var raises the rate limit from
 * 60/hr to 5000/hr; absent, public repos still work anonymously.
 */
public final class GitHubFetcher {

    /** Result of a successful fetch — everything the registry needs to index a version. */
    public record SkillMetadata(
            String name,
            String version,
            String description,
            List<String> skillReferences,
            String gitSha,
            /** {@code "skill"} or {@code "plugin"} — detected from the github tarball layout. */
            String unitKind
    ) {}

    /** Recoverable failure: the repo / ref / toml is missing or malformed. Server should 422 the caller. */
    public static final class FetchException extends RuntimeException {
        public FetchException(String message) { super(message); }
        public FetchException(String message, Throwable cause) { super(message, cause); }
    }

    private static final Pattern GITHUB_URL = Pattern.compile(
            "^(?:https?://github\\.com/|git@github\\.com:|github:)([^/\\s]+)/([^/\\s]+?)(?:\\.git)?/?$"
    );

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private GitHubFetcher() {}

    /** Parse {@code https://github.com/owner/repo} (and variants) into {@code [owner, repo]}. */
    public static String[] parseOwnerRepo(String githubUrl) {
        if (githubUrl == null) throw new FetchException("github_url is required");
        Matcher m = GITHUB_URL.matcher(githubUrl.trim());
        if (!m.matches()) throw new FetchException("not a github URL: " + githubUrl);
        return new String[]{ m.group(1), m.group(2) };
    }

    /**
     * Fetch + parse skill metadata at the given ref. The ref can be a tag, a branch,
     * or a SHA — GitHub's API resolves all three the same way. The returned
     * {@code gitSha} is the canonical commit SHA so downstream installs are
     * reproducible even if the ref is a moving target.
     */
    public static SkillMetadata fetch(String githubUrl, String ref) throws IOException {
        String[] or = parseOwnerRepo(githubUrl);
        String owner = or[0];
        String repo = or[1];
        String resolvedRef = (ref == null || ref.isBlank()) ? "HEAD" : ref;

        String gitSha = resolveSha(owner, repo, resolvedRef);
        byte[] skillToml = null;       // standalone skill: skill-manager.toml at root
        byte[] pluginToml = null;      // plugin: skill-manager-plugin.toml at root
        byte[] pluginJson = null;      // plugin: .claude-plugin/plugin.json
        byte[] skillMd = null;         // either layout: SKILL.md (root for skill, contained for plugin)

        // GitHub's tarball endpoint redirects to a codeload URL with a
        // top-level prefix dir like "<owner>-<repo>-<sha>/". We accept both
        // skill-only repos (root-level SKILL.md + skill-manager.toml) and
        // plugin repos (root-level .claude-plugin/plugin.json plus optional
        // skill-manager-plugin.toml plus contained skills/<name>/SKILL.md).
        URI tarballUri = URI.create(
                "https://api.github.com/repos/" + owner + "/" + repo + "/tarball/" + gitSha);
        HttpRequest req = withGitHubAuth(HttpRequest.newBuilder(tarballUri))
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching GitHub tarball", ie);
        }
        if (resp.statusCode() == 404) {
            throw new FetchException("github tarball not found: " + owner + "/" + repo + "@" + resolvedRef);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new FetchException(
                    "github tarball failed: HTTP " + resp.statusCode() + " for " + tarballUri);
        }

        try (InputStream gz = resp.body();
             GzipCompressorInputStream in = new GzipCompressorInputStream(gz);
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(e) || e.isDirectory()) continue;
                String name = e.getName();
                if (pluginJson == null && name.endsWith("/.claude-plugin/plugin.json")) {
                    pluginJson = readEntry(tar);
                } else if (pluginToml == null && name.endsWith("/skill-manager-plugin.toml")) {
                    pluginToml = readEntry(tar);
                } else if (skillToml == null && name.endsWith("/skill-manager.toml")) {
                    skillToml = readEntry(tar);
                } else if (skillMd == null && name.endsWith("/SKILL.md")) {
                    skillMd = readEntry(tar);
                }
            }
        }

        boolean isPlugin = pluginJson != null;
        String unitKind = isPlugin ? BundleMetadata.UNIT_KIND_PLUGIN : BundleMetadata.UNIT_KIND_SKILL;

        String name;
        String version;
        String description;
        List<String> refs;

        if (isPlugin) {
            // Identity precedence (matches the CLI's PluginParser): plugin.toml >
            // plugin.json. Description likewise — falls back to plugin.json's
            // description, then to the first contained SKILL.md frontmatter.
            JsonNode pj = parseJson(pluginJson);
            String pluginTomlText = pluginToml == null ? null : new String(pluginToml, StandardCharsets.UTF_8);
            name = firstNonBlank(
                    BundleMetadata.parseTomlString(pluginTomlText, "plugin", "name"),
                    pj.path("name").asText(null));
            version = firstNonBlank(
                    BundleMetadata.parseTomlString(pluginTomlText, "plugin", "version"),
                    pj.path("version").asText(null));
            description = firstNonBlank(
                    BundleMetadata.parseTomlString(pluginTomlText, "plugin", "description"),
                    pj.path("description").asText(null),
                    skillMd == null ? "" : BundleMetadata.parseSkillDescription(new String(skillMd, StandardCharsets.UTF_8)));
            // Plugin references live under [plugin] in the plugin toml.
            refs = pluginTomlText == null ? List.of() : BundleMetadata.parseSkillReferences(pluginTomlText);
        } else {
            if (skillToml == null) {
                throw new FetchException(
                        "neither skill-manager.toml nor .claude-plugin/plugin.json found in "
                                + owner + "/" + repo + "@" + resolvedRef);
            }
            String tomlText = new String(skillToml, StandardCharsets.UTF_8);
            name = BundleMetadata.parseTomlString(tomlText, "skill", "name");
            version = BundleMetadata.parseTomlString(tomlText, "skill", "version");
            description = skillMd == null ? "" : BundleMetadata.parseSkillDescription(new String(skillMd, StandardCharsets.UTF_8));
            refs = BundleMetadata.parseSkillReferences(tomlText);
        }

        if (name == null || name.isBlank()) {
            throw new FetchException(
                    (isPlugin ? "plugin manifest" : "skill-manager.toml") + " missing required name");
        }
        if (version == null || version.isBlank()) version = "0.0.1";
        if (description == null) description = "";
        return new SkillMetadata(name, version, description, refs, gitSha, unitKind);
    }

    private static JsonNode parseJson(byte[] bytes) {
        try {
            return JSON.readTree(bytes);
        } catch (IOException e) {
            throw new FetchException("failed to parse plugin.json", e);
        }
    }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) if (v != null && !v.isBlank()) return v;
        return null;
    }

    /** Resolve a ref (tag/branch/SHA/HEAD) to the canonical commit SHA. */
    private static String resolveSha(String owner, String repo, String ref) throws IOException {
        URI commitUri = URI.create(
                "https://api.github.com/repos/" + owner + "/" + repo + "/commits/" + ref);
        HttpRequest req = withGitHubAuth(HttpRequest.newBuilder(commitUri))
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted resolving SHA", ie);
        }
        if (resp.statusCode() == 404) {
            throw new FetchException("github ref not found: " + owner + "/" + repo + "@" + ref);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new FetchException(
                    "github commits API failed: HTTP " + resp.statusCode() + " for " + commitUri);
        }
        try {
            JsonNode body = JSON.readTree(resp.body());
            String sha = body.path("sha").asText(null);
            if (sha == null || sha.isBlank()) {
                throw new FetchException("github commits API returned no sha for " + ref);
            }
            return sha;
        } catch (IOException e) {
            throw new FetchException("failed to parse github commits response", e);
        }
    }

    private static HttpRequest.Builder withGitHubAuth(HttpRequest.Builder b) {
        String token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        b.header("X-GitHub-Api-Version", "2022-11-28");
        b.header("User-Agent", "skill-manager-server");
        return b;
    }

    private static byte[] readEntry(TarArchiveInputStream tar) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
        byte[] tmp = new byte[8192];
        int n;
        while ((n = tar.read(tmp)) > 0) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }
}
