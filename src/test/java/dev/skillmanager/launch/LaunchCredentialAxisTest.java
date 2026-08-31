package dev.skillmanager.launch;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.store.HomeDescriptor;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Issue #263 / HBR-4: <b>isolating a home must not de-authenticate the agent
 * launched into it.</b>
 *
 * <h2>The cause, measured rather than assumed</h2>
 *
 * <p>The issue reports the macOS keychain being unreachable; a later survey
 * reports a redirected {@code CLAUDE_CONFIG_DIR} that merely lacks an
 * authenticated {@code .claude.json}. Against Claude Code 2.1.251, both were
 * tested and neither holds. {@code exec} never overrides {@code HOME}, so the
 * keychain is reachable; and an {@code oauthAccount}-bearing
 * {@code .claude.json} copied into the redirected directory still produced
 * {@code Not logged in · Please run /login}.
 *
 * <p>What is actually true is that {@code CLAUDE_CONFIG_DIR} renames the
 * credential slot. The CLI's own derivation:
 *
 * <pre>
 * service = "Claude Code-credentials" + (
 *     CLAUDE_SECURESTORAGE_CONFIG_DIR set
 *       ? (empty ? "" : "-" + sha256(it)[0:8])
 *       : (CLAUDE_CONFIG_DIR unset ? "" : "-" + sha256(configDir)[0:8]))
 * </pre>
 *
 * <p>So a redirected home asks the keychain for a slot nobody has written.
 *
 * <h2>WHAT THIS TEST CAN AND CANNOT ASSERT, and why that matters</h2>
 *
 * <p>It cannot assert that an agent authenticates. Doing that needs the real
 * {@code claude} binary and the operator's real login, which is a machine
 * fact, not a repository fact — and a unit test that shelled out to it would
 * be green or red for reasons having nothing to do with this code. That claim
 * is demonstrated end to end in the ticket's evidence and decided by HBR-5's
 * graph node.
 *
 * <p>What it asserts instead is the thing this repository actually controls
 * and the thing that would silently regress: <b>the exact bytes of the launch
 * environment</b>, and the fact that the guard beside it did not move. Both of
 * those are the failure modes with teeth here — the empty string looks like a
 * placeholder and invites "tidying" into a path, which restores #263 in full;
 * and the guard invites an opt-out, which trades the isolation invariant away
 * for a symptom that no longer exists.
 */
public final class LaunchCredentialAxisTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("LaunchCredentialAxisTest");

        // ------------------------------------------------- the launch environment

        suite.test("a launch exports CLAUDE_SECURESTORAGE_CONFIG_DIR", () -> {
            Path root = home("declared");
            LaunchEnv launch = LaunchEnv.of(new SkillStore(store(root)), root, "/usr/bin", false);

            assertTrue(launch.env().containsKey(AgentHomes.CLAUDE_SECURESTORAGE_CONFIG_DIR),
                    "the credential axis is declared, not left to inherit: " + launch.env());
            assertTrue(launch.exportedEnv()
                            .containsKey(AgentHomes.CLAUDE_SECURESTORAGE_CONFIG_DIR),
                    "and it survives into what a subprocess is handed");
        });

        suite.test("its value is the EMPTY STRING, not the operator's config directory", () -> {
            Path root = home("empty-value");
            LaunchEnv launch = LaunchEnv.of(new SkillStore(store(root)), root, "/usr/bin", false);

            // This is the whole ticket in one assertion. Only an empty value
            // selects the UNSUFFIXED keychain slot, which is the one an
            // operator who logged in normally actually has. A path here —
            // including the correct-looking `$HOME/.claude` — hashes into a
            // suffix and asks for a slot that does not exist. Measured on
            // 2026-08-30 with CLAUDE_CONFIG_DIR pointed at an empty scratch
            // directory:
            //
            //   CLAUDE_SECURESTORAGE_CONFIG_DIR=                 -> "OK"
            //   CLAUDE_SECURESTORAGE_CONFIG_DIR=$HOME/.claude    -> "Not logged in"
            //   (unset)                                          -> "Not logged in"
            assertEquals("", launch.env().get(AgentHomes.CLAUDE_SECURESTORAGE_CONFIG_DIR),
                    "empty selects the unsuffixed credential slot; a path does not");
        });

        suite.test("a home whose descriptor predates the fix still gets it", () -> {
            Path root = home("old-descriptor");
            Path storeRoot = store(root);
            // A descriptor written by an older build: the env block names the
            // four variables that existed then and nothing else. There are
            // dozens of these on a working machine, and a fix that only
            // reached homes re-described after it shipped would reach none of
            // them. This is why the value is a launch-time layer rather than a
            // descriptor field.
            new HomeDescriptor(root, "live", HomeDescriptor.envFor(root, storeRoot),
                    null, null, null, Map.of()).write(storeRoot);

            LaunchEnv launch = LaunchEnv.of(new SkillStore(storeRoot), root, "/usr/bin", false);
            assertEquals("", launch.env().get(AgentHomes.CLAUDE_SECURESTORAGE_CONFIG_DIR),
                    "an existing descriptor does not have to be rewritten to be fixed");
        });

        suite.test("a home can opt back OUT into its own credential slot", () -> {
            Path root = home("opt-out");
            Path storeRoot = store(root);
            Path isolated = root.resolve(AgentHomes.CLAUDE_DIR_NAME);
            Map<String, String> contributions = new LinkedHashMap<>();
            contributions.put(AgentHomes.CLAUDE_SECURESTORAGE_CONFIG_DIR, isolated.toString());
            new HomeDescriptor(root, "live", HomeDescriptor.envFor(root, storeRoot),
                    null, null, null, contributions).write(storeRoot);

            LaunchEnv launch = LaunchEnv.of(new SkillStore(storeRoot), root, "/usr/bin", false);
            // The shared default is the LOWEST-precedence layer on purpose, so
            // `home describe --set-env` is already the opt-out and no new
            // option had to be invented for it.
            assertEquals(isolated.toString(),
                    launch.env().get(AgentHomes.CLAUDE_SECURESTORAGE_CONFIG_DIR),
                    "an operator's explicit statement wins over the shared default");
        });

        // ------------------------------------------------- the guard did not move

        suite.test("GUARD: an unredirected CLAUDE_CONFIG_DIR is still refused", () -> {
            Path root = home("guard-refuses");
            Path storeRoot = store(root);
            Path elsewhere = root.getParent().resolve("operator-home").resolve(".claude");
            Map<String, String> contributions = new LinkedHashMap<>();
            contributions.put(AgentHomes.CLAUDE_CONFIG_DIR, elsewhere.toString());
            new HomeDescriptor(root, "live", HomeDescriptor.envFor(root, storeRoot),
                    null, null, null, contributions).write(storeRoot);

            LaunchEnv launch = LaunchEnv.of(new SkillStore(storeRoot), root, "/usr/bin", false);
            // The credential variable IS present in this env. The point of the
            // case is that its presence changes nothing: #263 is fixed and the
            // isolation invariant is intact, which is only true if these two
            // are on separate axes.
            assertEquals("", launch.env().get(AgentHomes.CLAUDE_SECURESTORAGE_CONFIG_DIR),
                    "precondition: the credential axis is set on this launch too");

            boolean refused = false;
            try {
                launch.requireClaudeRedirected();
            } catch (UnredirectedLaunchException expected) {
                refused = true;
                Tests.assertContains(expected.getMessage(), AgentHomes.CLAUDE_CONFIG_DIR,
                        "the refusal still names the variable it is about");
                assertFalse(expected.getMessage()
                                .contains(AgentHomes.CLAUDE_SECURESTORAGE_CONFIG_DIR),
                        "and does not blame the credential variable for a config-axis fault");
            }
            assertTrue(refused, "the gate still refuses; no opt-out was added to it");
        });

        suite.test("GUARD: the credential variable cannot satisfy the gate on its own", () -> {
            // A launch env carrying ONLY the credential variable declares no
            // Claude config directory at all, so AgentHomes#claude falls back
            // to $HOME and the gate must still fire. Asserted because the
            // cheapest wrong fix for #263 is to treat "credentials are handled"
            // as "the Claude axis is handled".
            AgentHomes.ClaudeHome resolved = AgentHomes.claude(
                    Map.of(AgentHomes.CLAUDE_SECURESTORAGE_CONFIG_DIR, "",
                            AgentHomes.HOME, "/somewhere/operator"));
            assertEquals(Path.of("/somewhere/operator/.claude"), resolved.configDir(),
                    "the credential variable is not read as a config directory");
        });

        return suite.runAll();
    }

    /** A home root with a laid-out store beside it. */
    private static Path home(String label) throws Exception {
        Path root = Files.createTempDirectory("hbr4-" + label + "-").toRealPath();
        new SkillStore(store(root)).init();
        return root;
    }

    private static Path store(Path root) {
        return root.resolve(AgentHomes.STORE_DIR_NAME);
    }

    private LaunchCredentialAxisTest() {}
}
