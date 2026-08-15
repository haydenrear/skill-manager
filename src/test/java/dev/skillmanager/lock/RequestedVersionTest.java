package dev.skillmanager.lock;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.CliDependency;

import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertEquals;

/**
 * The cases the suite lacked: what {@code cli-lock.toml}'s {@code version}
 * column is allowed to contain.
 *
 * <h2>Why this exists</h2>
 *
 * <p>ARTI-01's sealed scorecard round seeded a fault into
 * {@code RequestedVersion.fromPip} — record the operand of a RANGE operator as
 * if it were a pin, so {@code pip:ruff>=0.6} writes {@code version = "0.6"} —
 * and <b>no case moved across 178 tests in 18 suites</b>. The mutant survived,
 * and the finding was routed to this ticket because ARTI-04's plan was to
 * fingerprint pip/npm/brew on a resolved version: a digest inheriting that
 * parse would report a stale artifact as current, which is the exact failure
 * this ticket closes.
 *
 * <p><b>On inspection the production code was already correct</b>, and has been
 * since the initial commit — {@code fromPip} returns {@code version = null} for
 * every operator except {@code ==}, and its javadoc says so. So the finding is
 * about the suite and not about the parse: the behaviour was undefended, which
 * is why a judge could delete it and nothing noticed. These cases defend it.
 *
 * <p>ARTI-04 also routes around the parse entirely rather than relying on it:
 * no backend's fingerprint reads {@code Requested.version()}. The pip and npm
 * schemes read the version from what was actually installed, precisely because
 * a spec's own version field is absent for a range and unreliable for npm.
 */
public final class RequestedVersionTest {

    public static int run() {
        Tests.Suite suite = Tests.suite("RequestedVersionTest");

        suite.test("pip: == is a pin and is recorded", () ->
                assertEquals("0.6.0", version("pip:ruff==0.6.0"),
                        "an exact pin is the one thing the lock may call a version"));

        // One case per operator: the seeded fault was a single `indexOf`, and a
        // sweep is what makes deleting the guard for ANY of them go red.
        for (String op : new String[] {">=", "<=", "~=", "!=", ">", "<"}) {
            suite.test("pip: " + op + " is a range, so no version is recorded", () -> {
                assertEquals(null, version("pip:ruff" + op + "0.6"),
                        op + " bounds a range; recording its operand as a pin would report "
                                + "any resolved version in the range as the one installed");
                assertEquals("ruff", tool("pip:ruff" + op + "0.6"), "the tool is still the key");
            });
        }

        suite.test("pip: extras stay with the tool and do not become a version", () -> {
            assertEquals("jinja2-cli[yaml]", tool("pip:jinja2-cli[yaml]==0.8.2"), "tool");
            assertEquals("0.8.2", version("pip:jinja2-cli[yaml]==0.8.2"), "version");
        });

        suite.test("pip: a bare package has no version", () ->
                assertEquals(null, version("pip:tb-query"), "nothing was requested"));

        suite.test("brew: a formula never carries a version — brew tracks its own", () ->
                assertEquals(null, version("brew:opentofu"), "recorded as null, deliberately"));

        suite.test("npm: a scoped package is not split at its scope", () -> {
            assertEquals("@google/gemini-cli", tool("npm:@google/gemini-cli"), "tool");
            assertEquals(null, version("npm:@google/gemini-cli"), "no version requested");
        });

        suite.test("npm: an exact version after @ is recorded", () ->
                assertEquals("5.4.5", version("npm:typescript@5.4.5"), "an npm pin"));

        return suite.runAll();
    }

    private static String tool(String spec) {
        return RequestedVersion.of(dep(spec)).tool();
    }

    private static String version(String spec) {
        return RequestedVersion.of(dep(spec)).version();
    }

    private static CliDependency dep(String spec) {
        return new CliDependency("under-test", spec, null, null, null, true, Map.of());
    }
}
