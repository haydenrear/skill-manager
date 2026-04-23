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
    private final String bearerToken;   // may be null — public endpoints still work
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public RegistryClient(RegistryConfig config) { this(config, null); }

    public RegistryClient(RegistryConfig config, String bearerToken) {
        this.config = config;
        this.bearerToken = bearerToken;
    }

    /** Build a client that picks up the bearer token from {@link AuthStore}. */
    public static RegistryClient authenticated(dev.skillmanager.store.SkillStore store, RegistryConfig config) {
        return new RegistryClient(config, new AuthStore(store).load());
    }

    /**
     * POST /auth/register — anonymous signup. Returns the parsed user payload
     * (username, display_name, email). Throws on non-2xx including 409 name-taken
     * and 400 validation errors.
     */
    public Map<String, Object> registerAccount(String username, String email, String password,
                                               String displayName) throws IOException {
        URI url = URI.create(strip(config.baseUrl().toString()) + "/auth/register");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("username", username);
        body.put("email", email);
        body.put("password", password);
        if (displayName != null) body.put("display_name", displayName);
        String payload = json.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder(url)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        try {
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("register failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(resp.body(), Map.class);
            return parsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("register interrupted", e);
        }
    }

    /**
     * OAuth2 authorization_code + PKCE against the registry, intended for the
     * {@code skill-manager login} browser flow:
     *
     * <ol>
     *   <li>bind 127.0.0.1:{@code callbackPort}</li>
     *   <li>generate a PKCE verifier/challenge pair + random state</li>
     *   <li>open the browser to {@code /oauth2/authorize} (or print the URL
     *       if {@code openBrowser} is false — useful on headless hosts)</li>
     *   <li>wait for the callback on the loopback, grab {@code code}</li>
     *   <li>POST to {@code /oauth2/token} with {@code grant_type=authorization_code}
     *       + the verifier, return the parsed JWT response</li>
     * </ol>
     *
     * <p>Times out after 5 minutes if the user never finishes the browser dance.
     */
    public Map<String, Object> browserLogin(int callbackPort, boolean openBrowser) throws IOException {
        String redirectUri = "http://127.0.0.1:" + callbackPort + "/callback";
        String state = java.util.UUID.randomUUID().toString();
        String verifier = randomUrlsafe(64);
        String challenge;
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            challenge = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(md.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 missing", e);
        }

        java.util.concurrent.CompletableFuture<Map<String, String>> onCallback = new java.util.concurrent.CompletableFuture<>();
        com.sun.net.httpserver.HttpServer loop = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", callbackPort), 0);
        loop.createContext("/callback", ex -> {
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            String msg = q.containsKey("error")
                    ? "<h1>login failed</h1><p>" + escape(q.get("error")) + "</p>"
                    : "<h1>login complete</h1><p>you can close this tab.</p>";
            byte[] page = ("<html><body>" + msg + "</body></html>").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, page.length);
            ex.getResponseBody().write(page);
            ex.close();
            onCallback.complete(q);
        });
        loop.start();

        try {
            String authorizeUrl = strip(config.baseUrl().toString()) + "/oauth2/authorize"
                    + "?response_type=code"
                    + "&client_id=skill-manager-cli"
                    + "&redirect_uri=" + encode(redirectUri)
                    + "&scope=" + encode("skill:publish ad:manage")
                    + "&state=" + encode(state)
                    + "&code_challenge=" + challenge
                    + "&code_challenge_method=S256";
            if (openBrowser) tryOpenBrowser(authorizeUrl);
            System.out.println("[skill-manager] open this URL to continue:");
            System.out.println("  " + authorizeUrl);

            Map<String, String> params;
            try {
                params = onCallback.get(5, java.util.concurrent.TimeUnit.MINUTES);
            } catch (java.util.concurrent.TimeoutException e) {
                throw new IOException("timed out waiting for browser callback", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for browser callback", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IOException("callback handler failed", e.getCause());
            }
            if (params.containsKey("error")) {
                throw new IOException("oauth error: " + params.get("error")
                        + (params.containsKey("error_description") ? " — " + params.get("error_description") : ""));
            }
            if (!state.equals(params.get("state"))) {
                throw new IOException("state mismatch — possible CSRF");
            }
            String code = params.get("code");
            if (code == null || code.isBlank()) throw new IOException("no code in callback params");

            String form = "grant_type=authorization_code"
                    + "&code=" + encode(code)
                    + "&redirect_uri=" + encode(redirectUri)
                    + "&client_id=skill-manager-cli"
                    + "&code_verifier=" + encode(verifier);
            HttpRequest req = HttpRequest.newBuilder(URI.create(strip(config.baseUrl().toString()) + "/oauth2/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            try {
                var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    throw new IOException("token exchange failed: HTTP " + resp.statusCode() + " " + resp.body());
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = json.readValue(resp.body(), Map.class);
                return parsed;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("token exchange interrupted", e);
            }
        } finally {
            loop.stop(0);
        }
    }

    private static String randomUrlsafe(int bytes) {
        byte[] buf = new byte[bytes];
        new java.security.SecureRandom().nextBytes(buf);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (q == null || q.isEmpty()) return out;
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            out.put(java.net.URLDecoder.decode(k, StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void tryOpenBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {}
        // Fall back to common platform shells.
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac")) new ProcessBuilder("open", url).start();
            else if (os.contains("win")) new ProcessBuilder("cmd", "/c", "start", url).start();
            else new ProcessBuilder("xdg-open", url).start();
        } catch (Exception ignored) {}
    }

    /**
     * POST /oauth2/token — OAuth2 client_credentials grant against the registry's
     * embedded authorization server. Returns the parsed token response.
     */
    public Map<String, Object> clientCredentialsToken(String clientId, String clientSecret, String scope)
            throws IOException {
        URI url = URI.create(strip(config.baseUrl().toString()) + "/oauth2/token");
        StringBuilder form = new StringBuilder("grant_type=client_credentials");
        if (scope != null && !scope.isBlank()) {
            form.append("&scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
        }
        String basic = java.util.Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(url)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + basic)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();
        try {
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("token endpoint failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(resp.body(), Map.class);
            return parsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("token request interrupted", e);
        }
    }

    /** GET /auth/me — requires a bearer token. */
    public Map<String, Object> me() throws IOException {
        return get("/auth/me");
    }

    /** GET /ads/campaigns/{id}/stats — aggregate counters for a campaign. */
    public Map<String, Object> campaignStats(String id) throws IOException {
        return get("/ads/campaigns/" + encode(id) + "/stats");
    }

    private HttpRequest.Builder authed(HttpRequest.Builder b) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            b.header("Authorization", "Bearer " + bearerToken);
        }
        return b;
    }

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
        return searchWithSponsored(query, limit, false).organic();
    }

    /** Full search response: organic items + sponsored placements + counts. */
    public SearchResult searchWithSponsored(String query, int limit, boolean noAds) throws IOException {
        String path = "/skills/search?q=" + encode(query) + "&limit=" + limit
                + (noAds ? "&no_ads=true" : "");
        Map<String, Object> payload = get(path);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> organic = payload.get("items") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sponsored = payload.get("sponsored") instanceof List<?> l2
                ? (List<Map<String, Object>>) l2 : List.of();
        return new SearchResult(organic, sponsored);
    }

    public List<Map<String, Object>> listCampaigns() throws IOException {
        return itemsOf(get("/ads/campaigns"));
    }

    public Map<String, Object> createCampaign(Map<String, Object> body) throws IOException {
        URI url = URI.create(strip(config.baseUrl().toString()) + "/ads/campaigns");
        String payload = json.writeValueAsString(body);
        HttpRequest req = authed(HttpRequest.newBuilder(url)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(payload)))
                .build();
        try {
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("createCampaign failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(resp.body(), Map.class);
            return parsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("createCampaign interrupted", e);
        }
    }

    public boolean deleteCampaign(String id) throws IOException {
        URI url = URI.create(strip(config.baseUrl().toString()) + "/ads/campaigns/" + encode(id));
        HttpRequest req = authed(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).DELETE()).build();
        try {
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return false;
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("deleteCampaign failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("deleteCampaign interrupted", e);
        }
    }

    public record SearchResult(
            List<Map<String, Object>> organic,
            List<Map<String, Object>> sponsored) {}

    public Map<String, Object> describe(String name) throws IOException {
        return get("/skills/" + encode(name));
    }

    public Map<String, Object> describeVersion(String name, String version) throws IOException {
        return get("/skills/" + encode(name) + "/" + encode(version));
    }

    /** Download a skill package; returns the size in bytes. */
    public long download(String name, String version, Path dst) throws IOException {
        return download(name, version, dst, null);
    }

    /** Download with optional campaign_id attribution (records a conversion on the server). */
    public long download(String name, String version, Path dst, String campaignId) throws IOException {
        String v = version == null || version.isBlank() ? "latest" : version;
        String q = campaignId == null || campaignId.isBlank()
                ? ""
                : "?campaign_id=" + encode(campaignId);
        URI url = URI.create(strip(config.baseUrl().toString())
                + "/skills/" + encode(name) + "/" + encode(v) + "/download" + q);
        try {
            var resp = http.send(authed(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(120)).GET()).build(),
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
        HttpRequest req = authed(HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)))
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
            var resp = http.send(authed(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(15)).GET()).build(),
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
