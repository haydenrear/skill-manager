package dev.skillmanager.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Talks to the skill registry HTTP server. */
public final class RegistryClient {

    private final RegistryConfig config;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public RegistryClient(RegistryConfig config) { this.config = config; }

    public boolean ping() {
        try {
            URI url = URI.create(strip(config.baseUrl().toString()) + "/health");
            var resp = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }

    public List<Map<String, Object>> list() throws IOException {
        return itemsOf(get("/skills"));
    }

    public List<Map<String, Object>> search(String query, int limit) throws IOException {
        String path = "/skills/search?q=" + encode(query) + "&limit=" + limit;
        return itemsOf(get(path));
    }

    public Map<String, Object> describe(String name) throws IOException {
        return get("/skills/" + encode(name));
    }

    public Map<String, Object> describeVersion(String name, String version) throws IOException {
        return get("/skills/" + encode(name) + "/" + encode(version));
    }

    /** Download a skill package; returns the size in bytes. */
    public long download(String name, String version, Path dst) throws IOException {
        String v = version == null || version.isBlank() ? "latest" : version;
        URI url = URI.create(strip(config.baseUrl().toString()) + "/skills/" + encode(name) + "/" + encode(v) + "/download");
        try {
            var resp = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(120)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() == 404) throw new IOException("not found: " + name + "@" + v);
            if (resp.statusCode() / 100 != 2) throw new IOException("HTTP " + resp.statusCode() + " from " + url);
            Files.createDirectories(dst.getParent());
            try (var in = resp.body(); OutputStream out = Files.newOutputStream(dst)) {
                return in.transferTo(out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", e);
        }
    }

    public PublishResult publish(String name, String version, Path tarball) throws IOException {
        URI url = URI.create(strip(config.baseUrl().toString()) + "/skills/" + encode(name) + "/" + encode(version));
        String boundary = "skill-manager-" + System.nanoTime();
        byte[] body = buildMultipart(boundary, tarball);
        HttpRequest req = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("publish failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(resp.body(), Map.class);
            return new PublishResult(
                    (String) parsed.get("name"),
                    (String) parsed.get("version"),
                    (String) parsed.get("sha256"),
                    ((Number) parsed.getOrDefault("size_bytes", 0)).longValue(),
                    (String) parsed.get("download_url")
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("publish interrupted", e);
        }
    }

    private byte[] buildMultipart(String boundary, Path tarball) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        String preamble = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"package\"; filename=\"" + tarball.getFileName() + "\"\r\n"
                + "Content-Type: application/gzip\r\n\r\n";
        out.write(preamble.getBytes(StandardCharsets.UTF_8));
        out.write(Files.readAllBytes(tarball));
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> payload) {
        Object items = payload.get("items");
        return items instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private Map<String, Object> get(String path) throws IOException {
        URI url = URI.create(strip(config.baseUrl().toString()) + path);
        try {
            var resp = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) throw new IOException("not found: " + url);
            if (resp.statusCode() / 100 != 2) throw new IOException("HTTP " + resp.statusCode() + " from " + url);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(resp.body(), Map.class);
            return parsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("request interrupted", e);
        }
    }

    private static String strip(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record PublishResult(String name, String version, String sha256, long sizeBytes, String downloadUrl) {}
}
