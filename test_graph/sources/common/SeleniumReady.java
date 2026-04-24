///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//DEPS org.seleniumhq.selenium:selenium-java:4.23.0
//DEPS org.seleniumhq.selenium:selenium-chrome-driver:4.23.0
//DEPS org.seleniumhq.selenium:selenium-remote-driver:4.23.0

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.service.DriverFinder;

/**
 * Warms the Selenium stack so the {@code browser.authorized} node doesn't
 * eat a cold-start download inside its own timeout:
 *
 * <ol>
 *   <li>{@link DriverFinder} invokes Selenium Manager to locate (and, on
 *       first run, download) a chromedriver binary compatible with the
 *       installed Chrome — the download is the whole point of keeping
 *       {@code browser-auth} in its own graph.</li>
 *   <li>A full {@link ChromeDriver} lifecycle proves the driver + browser
 *       can actually start in headless mode on this host.</li>
 * </ol>
 *
 * <p>Both paths are reported as metrics so a future failure shows which
 * binary was in play.
 */
public class SeleniumReady {
    static final NodeSpec SPEC = NodeSpec.of("selenium.ready")
            .kind(NodeSpec.Kind.FIXTURE)
            .tags("selenium", "browser")
            .sideEffects("proc:spawn", "net:download")
            .timeout("240s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            ChromeOptions opts = new ChromeOptions();
            opts.addArguments("--headless=new", "--no-sandbox", "--disable-gpu");

            // Explicit resolution via Selenium Manager. First run downloads
            // chromedriver + notes the Chrome binary path; subsequent runs
            // hit the ~/.cache/selenium cache.
            DriverFinder finder = new DriverFinder(
                    ChromeDriverService.createDefaultService(), opts);
            String driverPath = finder.getDriverPath();
            String browserPath = finder.getBrowserPath();
            boolean driverResolved = driverPath != null && !driverPath.isBlank();
            boolean browserResolved = browserPath != null && !browserPath.isBlank();

            // End-to-end lifecycle check — the driver must actually start.
            ChromeDriver driver = null;
            boolean driverStarted = false;
            try {
                driver = new ChromeDriver(opts);
                driver.get("about:blank");
                driverStarted = true;
            } finally {
                if (driver != null) driver.quit();
            }

            return (driverResolved && browserResolved && driverStarted
                    ? NodeResult.pass("selenium.ready")
                    : NodeResult.fail("selenium.ready",
                            "driverPath=" + driverPath + " browserPath=" + browserPath
                                    + " started=" + driverStarted))
                    .assertion("chromedriver_resolved", driverResolved)
                    .assertion("chrome_browser_resolved", browserResolved)
                    .assertion("chromedriver_started", driverStarted);
        });
    }
}
