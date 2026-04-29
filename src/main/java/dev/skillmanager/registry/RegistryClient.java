package dev.skillmanager.registry;

import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the skill registry.
 *
 * <p>For authenticated calls the client auto-handles the OAuth2 refresh
 * dance: every request first runs with the cached access token; on 401 we
 * try the refresh_token at {@code /oauth2/token}, persist the new pair
 * back to {@link AuthStore}, and retry once. If refresh fails (or there
 * was nothing to refresh) we surface {@link AuthenticationRequiredException}
 * so the CLI's top-level handler can tell the agent / user to run
 * {@code skill-manager login} again.
 */
public final class RegistryClient {

    /**
     * OAuth2 client id the CLI registers under. Matches what the server's
     * {@code AuthorizationServerConfig} wires up.
     */
    public static final String CLI_CLIENT_ID = "skill-manager-cli";

    /**
     * Openly-known "secret" for the CLI client. It satisfies SAS's
     * client-auth pipeline for the refresh_token grant (SAS rejects NONE
     * method for refresh); PKCE is what actually secures the token
     * exchange. Override via {@code SKILL_REGISTRY_CLI_SECRET} on both
     * the CLI and the server to lock down a private deployment.
     */
    public static final String CLI_PUBLIC_SECRET_DEFAULT = "skill-manager-cli-public";

    private final RegistryConfig config;
    private final AuthStore authStore;           // null on anonymous clients
    private AuthStore.Tokens tokens;             // cached; mutated on refresh
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public RegistryClient(RegistryConfig config) {
        this(config, null, null);
    }

    private RegistryClient(RegistryConfig config, AuthStore authStore, AuthStore.Tokens tokens) {
        this.config = config;
        this.authStore = authStore;
        this.tokens = tokens;
    }

    /** Build a client that picks up (and writes back refreshed) tokens via {@link AuthStore}. */
    public static RegistryClient authenticated(dev.skillmanager.store.SkillStore store, RegistryConfig config) {
        AuthStore as = new AuthStore(store);
        return new RegistryClient(config, as, as.load());
    }

    /**
     * POST /auth/password-reset/request — anonymous. Server always returns 2xx
     * (enumeration defense), so we only check the response status and discard
     * the body unless it's an error.
     */
    public void requestPasswordReset(String email) throws IOException {
        URI url = URI.create(strip(config.baseUrl().toString()) + "/auth/password-reset/request");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("email", email);
        HttpRequest req = HttpRequest.newBuilder(url)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = sendOnce(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("password-reset request failed: HTTP " + resp.statusCode() + " " + resp.body());
        }
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
        HttpResponse<String> resp = sendOnce(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("register failed: HTTP " + resp.statusCode() + " " + resp.body());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = json.readValue(resp.body(), Map.class);
        return parsed;
    }

    /**
     * OAuth2 authorization_code + PKCE browser flow. Used by
     * {@code skill-manager login} to bootstrap a fresh token pair.
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
                    + "&client_id=" + CLI_CLIENT_ID
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
                    + "&client_id=" + CLI_CLIENT_ID
                    + "&client_secret=" + encode(cliPublicSecret())
                    + "&code_verifier=" + encode(verifier);
            HttpRequest req = HttpRequest.newBuilder(URI.create(strip(config.baseUrl().toString()) + "/oauth2/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = sendOnce(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("token exchange failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(resp.body(), Map.class);
            return parsed;
        } finally {
            loop.stop(0);
        }
    }

    /**
     * POST /oauth2/token — OAuth2 client_credentials grant. Used by first-party
     * automation (test graph, CI). Returns the parsed token response.
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
        HttpResponse<String> resp = sendOnce(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("token endpoint failed: HTTP " + resp.statusCode() + " " + resp.body());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = json.readValue(resp.body(), Map.class);
        return parsed;
    }

    /** GET /auth/me — requires a bearer token. */
    public Map<String, Object> me() throws IOException {
        return get("/auth/me");
    }

    /** GET /ads/campaigns/{id}/stats — aggregate counters for a campaign. */
    public Map<String, Object> campaignStats(String id) throws IOException {
        return get("/ads/campaigns/" + encode(id) + "/stats");
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
        HttpResponse<String> resp = sendAuthed(
                bearer -> addAuth(HttpRequest.newBuilder(url)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(payload)), bearer).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("createCampaign failed: HTTP " + resp.statusCode() + " " + resp.body());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = json.readValue(resp.body(), Map.class);
        return parsed;
    }

    public boolean deleteCampaign(String id) throws IOException {
        URI url = URI.create(strip(config.baseUrl().toString()) + "/ads/campaigns/" + encode(id));
        HttpResponse<String> resp = sendAuthed(
                bearer -> addAuth(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).DELETE(), bearer).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) return false;
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("deleteCampaign failed: HTTP " + resp.statusCode() + " " + resp.body());
        }
        return true;
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
        HttpResponse<java.io.InputStream> resp = sendAuthed(
                bearer -> addAuth(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(120)).GET(), bearer).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() == 404) throw new IOException("not found: " + name + "@" + v);
        if (resp.statusCode() / 100 != 2) throw new IOException("HTTP " + resp.statusCode() + " from " + url);
        Files.createDirectories(dst.getParent());
        try (var in = resp.body(); OutputStream out = Files.newOutputStream(dst)) {
            return in.transferTo(out);
        }
    }

    /**
     * Register a github-hosted skill with the registry. Server fetches the
     * skill-manager.toml at {@code gitRef}, derives name+version from it,
     * and persists a metadata-only row. Returns the parsed SkillVersion.
     */
    public Map<String, Object> registerGithub(String githubUrl, String gitRef) throws IOException {
        URI url = URI.create(strip(config.baseUrl().toString()) + "/skills/register");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("github_url", githubUrl);
        if (gitRef != null && !gitRef.isBlank()) body.put("git_ref", gitRef);
        String payload = json.writeValueAsString(body);
        HttpResponse<String> resp = sendAuthed(
                bearer -> addAuth(HttpRequest.newBuilder(url)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(payload)), bearer).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("register failed: HTTP " + resp.statusCode() + " " + resp.body());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = json.readValue(resp.body(), Map.class);
        return parsed;
    }

    public PublishResult publish(String name, String version, Path tarball) throws IOException {
        URI url = URI.create(strip(config.baseUrl().toString()) + "/skills/" + encode(name) + "/" + encode(version));
        String boundary = "skill-manager-" + System.nanoTime();
        byte[] body = buildMultipart(boundary, tarball);
        HttpResponse<String> resp = sendAuthed(
                bearer -> addAuth(HttpRequest.newBuilder(url)
                        .timeout(Duration.ofSeconds(120))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)), bearer).build(),
                HttpResponse.BodyHandlers.ofString());
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
    }

    // --------------------------------------------------------------- internals

    /** Builds the request with the current bearer, keyed on the {@code bearer} parameter so retries can swap it. */
    @FunctionalInterface
    private interface AuthedRequest {
        HttpRequest build(String bearer);
    }

    private HttpRequest.Builder addAuth(HttpRequest.Builder b, String bearer) {
        if (bearer != null && !bearer.isBlank()) b.header("Authorization", "Bearer " + bearer);
        return b;
    }

    /**
     * Send an authenticated request. On 401 try the refresh token once;
     * if refresh fails or the retry still 401s, throw {@link
     * AuthenticationRequiredException} with an actionable message.
     */
    private <T> HttpResponse<T> sendAuthed(AuthedRequest supplier, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        String bearer = tokens == null ? null : tokens.accessToken();
        HttpResponse<T> resp = sendOnce(supplier.build(bearer), handler);
        if (resp.statusCode() != 401) return resp;
        drainIfNeeded(resp);
        if (!tryRefresh()) {
            throw new AuthenticationRequiredException(
                    "registry rejected cached credentials and no refresh was possible");
        }
        resp = sendOnce(supplier.build(tokens.accessToken()), handler);
        if (resp.statusCode() == 401) {
            drainIfNeeded(resp);
            throw new AuthenticationRequiredException(
                    "registry rejected refreshed credentials");
        }
        return resp;
    }

    /**
     * Swap the cached token pair for a fresh one by burning the refresh
     * token at {@code /oauth2/token}. Returns whether we got a new pair;
     * callers treat {@code false} as "user must re-authenticate".
     */
    private boolean tryRefresh() {
        if (tokens == null || !tokens.hasRefresh()) return false;
        try {
            URI url = URI.create(strip(config.baseUrl().toString()) + "/oauth2/token");
            String form = "grant_type=refresh_token"
                    + "&refresh_token=" + encode(tokens.refreshToken())
                    + "&client_id=" + CLI_CLIENT_ID
                    + "&client_secret=" + encode(cliPublicSecret());
            HttpRequest req = HttpRequest.newBuilder(url)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = sendOnce(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return false;
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(resp.body(), Map.class);
            String access = (String) parsed.get("access_token");
            if (!JwtFormat.looksLikeJwt(access)) return false;
            String refresh = (String) parsed.get("refresh_token");
            if (refresh == null || refresh.isBlank()) refresh = tokens.refreshToken();
            Instant expiresAt = null;
            Object exp = parsed.get("expires_in");
            if (exp instanceof Number n) expiresAt = Instant.now().plusSeconds(n.longValue());
            AuthStore.Tokens updated = new AuthStore.Tokens(access, refresh, expiresAt);
            this.tokens = updated;
            if (authStore != null) authStore.save(updated);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private <T> HttpResponse<T> sendOnce(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(req, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("request interrupted", e);
        }
    }

    private static void drainIfNeeded(HttpResponse<?> resp) {
        if (resp.body() instanceof java.io.InputStream in) {
            try { in.close(); } catch (IOException ignored) {}
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
        HttpResponse<String> resp = sendAuthed(
                bearer -> addAuth(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(15)).GET(), bearer).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) throw new IOException("not found: " + url);
        if (resp.statusCode() / 100 != 2) throw new IOException("HTTP " + resp.statusCode() + " from " + url);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = json.readValue(resp.body(), Map.class);
        return parsed;
    }

    private static String strip(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String cliPublicSecret() {
        String env = System.getenv("SKILL_REGISTRY_CLI_SECRET");
        return env == null || env.isBlank() ? CLI_PUBLIC_SECRET_DEFAULT : env.trim();
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
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac")) new ProcessBuilder("open", url).start();
            else if (os.contains("win")) new ProcessBuilder("cmd", "/c", "start", url).start();
            else new ProcessBuilder("xdg-open", url).start();
        } catch (Exception ignored) {}
    }

    public record PublishResult(String name, String version, String sha256, long sizeBytes, String downloadUrl) {}
}
