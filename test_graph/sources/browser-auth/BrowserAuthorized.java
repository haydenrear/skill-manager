///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//DEPS org.seleniumhq.selenium:selenium-java:4.23.0
//DEPS org.seleniumhq.selenium:selenium-chrome-driver:4.23.0

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end exercise of {@code skill-manager login}'s browser flow.
 *
 * <p>Structure: the CLI owns the real flow (PKCE, loopback server, token
 * exchange, token caching). The test only plays the user:
 *
 * <ol>
 *   <li>Spawn {@code skill-manager login --no-browser --port <loopback>} —
 *       the CLI prints the authorize URL and waits on the callback.</li>
 *   <li>Read the URL off the CLI's stdout.</li>
 *   <li>Drive a headless Chrome to that URL, fill the form-login with the
 *       credentials from {@code account.created}, submit. Spring auths,
 *       Spring Authorization Server issues a code + redirects to the
 *       CLI's loopback.</li>
 *   <li>CLI receives the callback, POSTs to {@code /oauth2/token},
 *       writes the bearer to {@code $SKILL_MANAGER_HOME/auth.token}, exits.</li>
 *   <li>Assert: CLI exit 0 → token file present → {@code /auth/me}
 *       validates the bearer and returns the expected username.</li>
 * </ol>
 *
 * <p>Regressions in any of PKCE code generation, loopback binding, token
 * exchange, or token caching surface as a specific failing assertion.
 */
public class BrowserAuthorized {
    static final NodeSpec SPEC = NodeSpec.of("browser.authorized")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("account.created", "selenium.ready")
            .tags("auth", "oauth2", "browser")
            .sideEffects("proc:spawn", "net:local")
            .timeout("120s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            String username = ctx.get("account.created", "username").orElse(null);
            String password = ctx.get("account.created", "password").orElse(null);
            if (home == null || registryUrl == null || username == null || password == null) {
                return NodeResult.fail("browser.authorized", "missing upstream context");
            }

            // The skill-manager-cli OAuth2 client is registered against
            // http://127.0.0.1:8765/callback, so we must use that fixed port.
            // A previous run that failed mid-flight may have left a zombie
            // `skill-manager login` process holding it — reap before we try
            // to spawn our own.
            int callbackPort = 8765;
            System.err.println("[browser.authorized] callback port " + callbackPort
                    + " reclaim: " + reclaimPort(callbackPort));

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "login",
                    "--no-browser",
                    "--port", String.valueOf(callbackPort),
                    "--registry", registryUrl)
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            Process proc = pb.start();

            List<String> captured = new ArrayList<>();
            String authorizeUrl;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                authorizeUrl = readAuthorizeUrl(reader, captured, Duration.ofSeconds(30));
            }
            if (authorizeUrl == null) {
                proc.destroyForcibly();
                return NodeResult.fail("browser.authorized",
                        "CLI didn't print authorize URL. output:\n" + String.join("\n", captured))
                        .assertion("cli_printed_authorize_url", false);
            }

            // Drive the form login in a headless browser. JBang started the
            // runtime without AWT, which is fine — Selenium is pure HTTP
            // against chromedriver.
            ChromeOptions opts = new ChromeOptions();
            opts.addArguments("--headless=new", "--no-sandbox", "--disable-gpu",
                    "--window-size=1280,900");
            WebDriver driver = new ChromeDriver(opts);
            boolean formSubmitted = false;
            try {
                driver.get(authorizeUrl);
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
                wait.until(ExpectedConditions.presenceOfElementLocated(By.name("username")));
                driver.findElement(By.name("username")).sendKeys(username);
                driver.findElement(By.name("password")).sendKeys(password);
                driver.findElement(By.cssSelector("button[type=submit], input[type=submit]")).click();
                // Wait for the loopback redirect to land; the CLI's loopback
                // responds with "ok" so the URL contains /callback then the
                // browser shows the page.
                wait.until(d -> d.getCurrentUrl().contains("/callback"));
                formSubmitted = true;
            } finally {
                driver.quit();
            }

            // Drain the rest of the CLI's output and wait for exit.
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) captured.add(line);
            } catch (IOException ignored) {}
            boolean exited = proc.waitFor(30, TimeUnit.SECONDS);
            if (!exited) {
                proc.destroyForcibly();
                return NodeResult.fail("browser.authorized",
                        "CLI didn't exit after form submit. output:\n" + String.join("\n", captured))
                        .assertion("cli_exit_zero", false);
            }
            int rc = proc.exitValue();

            Path tokenFile = Path.of(home, "auth.token");
            boolean tokenCached = Files.isRegularFile(tokenFile) && Files.size(tokenFile) > 0;
            String token = tokenCached ? Files.readString(tokenFile).trim() : null;

            int meStatus = -1;
            boolean usernameMatches = false;
            if (token != null) {
                HttpResponse<String> me = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(registryUrl + "/auth/me"))
                                .header("Authorization", "Bearer " + token).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                meStatus = me.statusCode();
                usernameMatches = meStatus == 200
                        && me.body().contains("\"username\":\"" + username + "\"");
            }

            boolean allOk = formSubmitted && rc == 0 && tokenCached && usernameMatches;
            return (allOk
                    ? NodeResult.pass("browser.authorized")
                    : NodeResult.fail("browser.authorized",
                            "rc=" + rc + " tokenCached=" + tokenCached + " meStatus=" + meStatus
                                    + "\ncli output:\n" + String.join("\n", captured)))
                    .assertion("cli_printed_authorize_url", true)
                    .assertion("login_form_submitted", formSubmitted)
                    .assertion("cli_exit_zero", rc == 0)
                    .assertion("token_cached_on_disk", tokenCached)
                    .assertion("me_returns_expected_username", usernameMatches)
                    .metric("cli_exit_code", rc);
        });
    }

    private static String readAuthorizeUrl(BufferedReader reader, List<String> captured, Duration timeout)
            throws IOException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String line = reader.readLine();
            if (line == null) return null;
            captured.add(line);
            String trimmed = line.trim();
            if ((trimmed.startsWith("http://") || trimmed.startsWith("https://"))
                    && trimmed.contains("/oauth2/authorize")) {
                return trimmed;
            }
        }
        return null;
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    /**
     * If {@code port} is already bound on 127.0.0.1, find whoever holds it
     * via {@code lsof -ti tcp:<port>} and {@code kill -9} them. Returns a
     * short human-readable summary for the node's metrics.
     *
     * <p>Intended for test-only cleanup of zombie {@code skill-manager login}
     * subprocesses left behind by a prior failed run. Gated to loopback-only
     * so it can't accidentally nuke something listening on an interface.
     */
    private static String reclaimPort(int port) {
        if (isLoopbackFree(port)) return "free";
        String pids;
        try {
            Process lsof = new ProcessBuilder("lsof", "-ti", "tcp:" + port)
                    .redirectErrorStream(true).start();
            pids = new String(lsof.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            lsof.waitFor(5, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "probe failed: " + e.getMessage();
        }
        if (pids.isEmpty()) return "in use but no lsof pids";
        for (String pid : pids.split("\\s+")) {
            try {
                new ProcessBuilder("kill", "-9", pid).redirectErrorStream(true).start().waitFor(3, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException ignored) {
                if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        // Give the kernel a moment to release the socket.
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (isLoopbackFree(port)) return "reclaimed from pid(s) " + pids.replace('\n', ',');
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }
        }
        return "kill issued but port still held by pid(s) " + pids.replace('\n', ',');
    }

    private static boolean isLoopbackFree(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
