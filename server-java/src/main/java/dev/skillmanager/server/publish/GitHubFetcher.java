package dev.skillmanager.server.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
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
            String gitSha
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
        byte[] toml = null;
        byte[] skillMd = null;

        // GitHub's tarball endpoint redirects to a codeload URL with a
        // top-level prefix dir like "<owner>-<repo>-<sha>/". We only care
        // about the suffix paths "<prefix>/skill-manager.toml" and
        // "<prefix>/SKILL.md" (and any nested copies — first match wins).
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
                if (toml == null && name.endsWith("/skill-manager.toml")) toml = readEntry(tar);
                else if (skillMd == null && name.endsWith("/SKILL.md")) skillMd = readEntry(tar);
                if (toml != null && skillMd != null) break;
            }
        }
        if (toml == null) {
            throw new FetchException(
                    "skill-manager.toml not found in " + owner + "/" + repo + "@" + resolvedRef);
        }

        String tomlText = new String(toml, StandardCharsets.UTF_8);
        String name = parseTomlString(tomlText, "name");
        if (name == null || name.isBlank()) {
            throw new FetchException("skill-manager.toml missing required [skill].name");
        }
        String version = parseTomlString(tomlText, "version");
        if (version == null || version.isBlank()) version = "0.0.1";
        String description = skillMd == null ? "" : parseSkillDescription(new String(skillMd, StandardCharsets.UTF_8));
        List<String> refs = parseSkillReferences(tomlText);
        return new SkillMetadata(name, version, description, refs, gitSha);
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

    /** Extract a top-level [skill] {@code key = "value"} from a tomlj-free, line-based parse. */
    static String parseTomlString(String toml, String key) {
        boolean inSkillSection = false;
        for (String raw : toml.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                inSkillSection = line.equalsIgnoreCase("[skill]");
                continue;
            }
            if (!inSkillSection) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim();
            if (!k.equalsIgnoreCase(key)) continue;
            String v = line.substring(eq + 1).trim();
            int hash = v.indexOf('#');
            if (hash >= 0) v = v.substring(0, hash).trim();
            if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"")
                    || v.startsWith("'") && v.endsWith("'"))) {
                v = v.substring(1, v.length() - 1);
            }
            return v;
        }
        return null;
    }

    /** Pull skill_references = [...] entries out of the toml. */
    static List<String> parseSkillReferences(String toml) {
        for (String raw : toml.split("\n")) {
            String s = raw.trim();
            if (s.startsWith("skill_references") && s.contains("=") && s.contains("[")) {
                int start = s.indexOf('[') + 1;
                int end = s.lastIndexOf(']');
                if (end <= start) return List.of();
                String inside = s.substring(start, end);
                List<String> out = new ArrayList<>();
                for (String part : inside.split(",")) {
                    String p = part.strip();
                    if (p.length() >= 2 && (p.startsWith("\"") && p.endsWith("\"")
                            || p.startsWith("'") && p.endsWith("'"))) {
                        p = p.substring(1, p.length() - 1);
                    }
                    if (!p.isEmpty()) out.add(p);
                }
                return out;
            }
        }
        return List.of();
    }

    /** Pull `description: "..."` out of SKILL.md frontmatter. */
    static String parseSkillDescription(String skillMd) {
        if (skillMd == null || !skillMd.startsWith("---")) return "";
        int end = skillMd.indexOf("\n---", 3);
        if (end < 0) return "";
        String frontmatter = skillMd.substring(4, end);
        for (String line : frontmatter.split("\n")) {
            String s = line.strip();
            if (s.startsWith("description:")) {
                String v = s.substring("description:".length()).strip();
                if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"")
                        || v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        return "";
    }
}
