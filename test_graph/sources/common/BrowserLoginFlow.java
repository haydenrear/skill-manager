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
 * Shared orchestration of the {@code skill-manager login} browser flow
 * for test-graph assertions.
 *
 * <p>{@link #run} spawns {@code skill-manager login --no-browser
 * --port 8765} (the fixed port the {@code skill-manager-cli} OAuth2
 * client is registered against), drives a headless Chrome through the
 * resulting Spring form-login, and returns a {@link Result} describing
 * whether each stage succeeded, the exit code, and — on success — the
 * bearer that lands in {@code auth.token}.
 *
 * <p>Reclaims port 8765 at entry so a zombie CLI from a previously
 * failed run can't wedge the test.
 */
final class BrowserLoginFlow {

    private BrowserLoginFlow() {}

    static final int CALLBACK_PORT = 8765;

    static final class Result {
        final boolean authorizeUrlPrinted;
        final boolean formSubmitted;
        final int cliExitCode;
        final boolean tokenCached;
        final String token;
        final int meStatus;
        final boolean usernameMatches;
        final List<String> cliOutput;

        Result(boolean authorizeUrlPrinted, boolean formSubmitted, int cliExitCode,
               boolean tokenCached, String token, int meStatus, boolean usernameMatches,
               List<String> cliOutput) {
            this.authorizeUrlPrinted = authorizeUrlPrinted;
            this.formSubmitted = formSubmitted;
            this.cliExitCode = cliExitCode;
            this.tokenCached = tokenCached;
            this.token = token;
            this.meStatus = meStatus;
            this.usernameMatches = usernameMatches;
            this.cliOutput = cliOutput;
        }

        boolean fullySucceeded() {
            return authorizeUrlPrinted && formSubmitted && cliExitCode == 0
                    && tokenCached && usernameMatches;
        }
    }

    static Result run(String smCli, String home, String registryUrl,
                      String repoRoot, String username, String password) throws Exception {
        System.err.println("[browser-login] callback port " + CALLBACK_PORT
                + " reclaim: " + reclaimPort(CALLBACK_PORT));

        ProcessBuilder pb = new ProcessBuilder(
                smCli, "login",
                "--no-browser",
                "--port", String.valueOf(CALLBACK_PORT),
                "--registry", registryUrl)
                .redirectErrorStream(true);
        pb.environment().put("SKILL_MANAGER_HOME", home);
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot);
        Process proc = pb.start();

        List<String> captured = new ArrayList<>();
        String authorizeUrl;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            authorizeUrl = readAuthorizeUrl(reader, captured, Duration.ofSeconds(30));
            if (authorizeUrl == null) {
                proc.destroyForcibly();
                return new Result(false, false, -1, false, null, -1, false, captured);
            }

            boolean formSubmitted = driveForm(authorizeUrl, username, password);
            if (!formSubmitted) {
                proc.destroyForcibly();
                return new Result(true, false, -1, false, null, -1, false, captured);
            }

            // Drain the rest; the CLI exits once the callback arrives + token exchanges.
            String line;
            while ((line = reader.readLine()) != null) captured.add(line);
        }
        boolean exited = proc.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            proc.destroyForcibly();
            return new Result(true, true, -1, false, null, -1, false, captured);
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
        return new Result(true, true, rc, tokenCached, token, meStatus, usernameMatches, captured);
    }

    private static boolean driveForm(String authorizeUrl, String username, String password) {
        ChromeOptions opts = new ChromeOptions();
        opts.addArguments("--headless=new", "--no-sandbox", "--disable-gpu",
                "--window-size=1280,900");
        WebDriver driver = new ChromeDriver(opts);
        try {
            driver.get(authorizeUrl);
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
            wait.until(ExpectedConditions.presenceOfElementLocated(By.name("username")));
            driver.findElement(By.name("username")).sendKeys(username);
            driver.findElement(By.name("password")).sendKeys(password);
            driver.findElement(By.cssSelector("button[type=submit], input[type=submit]")).click();
            wait.until(d -> d.getCurrentUrl().contains("/callback"));
            return true;
        } catch (Exception e) {
            System.err.println("[browser-login] Selenium error: " + e);
            return false;
        } finally {
            driver.quit();
        }
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
            return "probe failed";
        }
        if (pids.isEmpty()) return "in use but no lsof pids";
        for (String pid : pids.split("\\s+")) {
            try {
                new ProcessBuilder("kill", "-9", pid).redirectErrorStream(true)
                        .start().waitFor(3, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException ignored) {
                if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (isLoopbackFree(port)) return "reclaimed pid(s) " + pids.replace('\n', ',');
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
