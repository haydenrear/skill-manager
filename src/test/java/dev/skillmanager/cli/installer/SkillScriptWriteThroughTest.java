package dev.skillmanager.cli.installer;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * OHV-9 (#367, DEF-OHV-011): a skill-script install never writes through a
 * {@code bin/cli} link into another home.
 *
 * <p>The measured shape, from the operator's root home on 2026-09-14: a
 * project home's {@code bin/cli/computeq} was a symlink to
 * {@code ~/.skill-manager/bin/cli/computeq}, deploy-helm's installer ran in
 * the project home and wrote its launcher with {@code cat >"$launcher"}, and
 * {@code cat >} followed the link and rewrote the ROOT's file. Every case here
 * builds that pair in scratch directories: a parent home with a real shim, and
 * a child home whose same-named entry links to it.
 */
public final class SkillScriptWriteThroughTest {

    static final String TOOL = "wt-tool";
    static final String UNIT = "wt-unit";
    static final String PARENT_BYTES = "#!/bin/sh\necho parent-owned\n";

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("SkillScriptWriteThroughTest");

        // THE REPRODUCTION, at the backend. deploy-helm's shape: the launcher
        // is spelled through $SKILL_MANAGER_HOME/bin/cli and written with cat >.
        suite.test("OHV-9 (a): a cat > installer in a child does not rewrite the parent's shim (backend)", () -> {
            Pair pair = Pair.make("wt-backend-");
            scaffold(pair.child, catInstaller());

            new SkillScriptBackend().install(dep(), pair.child, UNIT);

            assertEquals(PARENT_BYTES, Files.readString(pair.parentShim()),
                    "the parent's shim is byte-identical after the child's install");
            Path childShim = pair.childShim();
            assertFalse(Files.isSymbolicLink(childShim), "the child's entry is no longer a link");
            assertTrue(Files.isRegularFile(childShim, LinkOption.NOFOLLOW_LINKS),
                    "the child holds its own real shim");
            assertContains(Files.readString(childShim), "child-built", "and it is the child's bytes");
        });

        // THE REPRODUCTION, through the registry, with the link SANCTIONED: the
        // parent claims the child, which is what the CDC project home had and
        // why refuseAForeignDestination walked past it.
        suite.test("OHV-9 (a): the same install through the registry over a sanctioned mirror leaves the parent byte-identical", () -> {
            Pair pair = Pair.make("wt-registry-");
            pair.claim();
            scaffold(pair.child, catInstaller());

            new InstallerRegistry().installOne(dep(), pair.child, UNIT);

            assertEquals(PARENT_BYTES, Files.readString(pair.parentShim()),
                    "the parent's shim is byte-identical after the child's install");
            Path childShim = pair.childShim();
            assertFalse(Files.isSymbolicLink(childShim), "the child's entry is its own, not a link");
            assertTrue(dev.skillmanager.store.ShimHomeContract.frozenHomeLines(pair.child.root(), childShim).isEmpty(),
                    "the child's shim is token-form: " + Files.readString(childShim));
        });

        suite.test("OHV-9 (a): a detached link the script did not replace is restored exactly", () -> {
            Pair pair = Pair.make("wt-restore-");
            Path rawTarget = Files.readSymbolicLink(pair.childShim());
            scaffold(pair.child, """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    # While the script runs, the foreign link is not there to follow.
                    if [ -e "$SKILL_MANAGER_BIN_DIR/%1$s" ] || [ -L "$SKILL_MANAGER_BIN_DIR/%1$s" ]; then
                      echo present > "$SKILL_MANAGER_CACHE_DIR/observed"
                    else
                      echo detached > "$SKILL_MANAGER_CACHE_DIR/observed"
                    fi
                    printf '#!/bin/sh\\n' > "$SKILL_MANAGER_BIN_DIR/other-tool"
                    chmod +x "$SKILL_MANAGER_BIN_DIR/other-tool"
                    """.formatted(TOOL));

            new SkillScriptBackend().install(depWithoutBinary(), pair.child, UNIT);

            assertEquals("detached\n", Files.readString(pair.child.cacheDir().resolve("observed")),
                    "the script ran with the foreign link detached");
            assertTrue(Files.isSymbolicLink(pair.childShim()), "the link is back");
            assertEquals(rawTarget, Files.readSymbolicLink(pair.childShim()), "with its exact target");
            assertEquals(PARENT_BYTES, Files.readString(pair.parentShim()), "parent unchanged");
        });

        suite.test("OHV-9 (a): a failing script still gets the link restored", () -> {
            Pair pair = Pair.make("wt-restore-fail-");
            Path rawTarget = Files.readSymbolicLink(pair.childShim());
            scaffold(pair.child, "#!/usr/bin/env bash\necho boom >&2\nexit 3\n");

            boolean threw = false;
            try {
                new SkillScriptBackend().install(dep(), pair.child, UNIT);
            } catch (IOException expected) {
                threw = true;
                assertContains(expected.getMessage(), "exited 3", "the script's failure is still the error");
            }
            assertTrue(threw, "a failing script fails the install");
            assertTrue(Files.isSymbolicLink(pair.childShim()), "the link is back after the failure");
            assertEquals(rawTarget, Files.readSymbolicLink(pair.childShim()), "with its exact target");
        });

        suite.test("OHV-9 (a): a script that writes the other home's file by its own path fails the install, naming that home", () -> {
            Pair pair = Pair.make("wt-changed-");
            scaffold(pair.child, """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    printf '#!/bin/sh\\necho overwritten\\n' > "%s"
                    """.formatted(pair.parentShim()));

            String message = null;
            try {
                new SkillScriptBackend().install(depWithoutBinary(), pair.child, UNIT);
            } catch (IOException expected) {
                message = expected.getMessage();
            }
            assertTrue(message != null, "a changed foreign target fails the install");
            assertContains(message, pair.parent.root().toRealPath().toString(), "the message names the other home: " + message);
            assertContains(message, "bin/cli/" + TOOL, "and the path: " + message);
        });

        suite.test("OHV-9 (a): a script that recreates the link back out is a foreign write and fails", () -> {
            Pair pair = Pair.make("wt-relink-");
            scaffold(pair.child, """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    ln -s "%s" "$SKILL_MANAGER_BIN_DIR/%s"
                    """.formatted(pair.parentShim(), TOOL));

            String message = null;
            try {
                new SkillScriptBackend().install(depWithoutBinary(), pair.child, UNIT);
            } catch (IOException expected) {
                message = expected.getMessage();
            }
            assertTrue(message != null, "a link recreated into the other home fails the install");
            assertContains(message, pair.parent.root().toRealPath().toString(), "naming the other home: " + message);
            assertEquals(PARENT_BYTES, Files.readString(pair.parentShim()), "parent unchanged");
        });

        suite.test("OHV-9 (a): a link resolving inside this home is left in place while the script runs", () -> {
            Pair pair = Pair.make("wt-inside-");
            Path venvTool = Files.createDirectories(pair.child.venvsDir().resolve("inner/bin")).resolve("inner");
            Files.writeString(venvTool, "#!/bin/sh\n");
            Path inner = pair.child.cliBinDir().resolve("inner");
            Files.createSymbolicLink(inner, Path.of("../../venvs/inner/bin/inner"));
            scaffold(pair.child, """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    if [ -L "$SKILL_MANAGER_BIN_DIR/inner" ]; then echo kept > "$SKILL_MANAGER_CACHE_DIR/inner"; fi
                    """);

            new SkillScriptBackend().install(depWithoutBinary(), pair.child, UNIT);

            assertTrue(Files.exists(pair.child.cacheDir().resolve("inner")), "the in-home link was visible to the script");
            assertEquals(Path.of("../../venvs/inner/bin/inner"), Files.readSymbolicLink(inner), "and is unchanged");
        });

        return suite.runAll();
    }

    // ---------------------------------------------------------------- fixture

    /** A scratch parent home with a real {@code bin/cli/wt-tool}, and a child whose entry links to it. */
    record Pair(SkillStore parent, SkillStore child) {
        static Pair make(String prefix) throws IOException {
            Path scratch = Files.createTempDirectory(prefix);
            SkillStore parent = new SkillStore(scratch.resolve("parent/.skill-manager"));
            parent.init();
            SkillStore child = new SkillStore(scratch.resolve("child/.skill-manager"));
            child.init();
            Path parentShim = parent.cliBinDir().resolve(TOOL);
            Files.writeString(parentShim, PARENT_BYTES, StandardCharsets.UTF_8);
            parentShim.toFile().setExecutable(true, false);
            Files.createSymbolicLink(child.cliBinDir().resolve(TOOL), parentShim);
            return new Pair(parent, child);
        }

        Path parentShim() { return parent.cliBinDir().resolve(TOOL); }

        Path childShim() { return child.cliBinDir().resolve(TOOL); }

        /** The parent claims the child: the child's link is a sanctioned mirror. */
        void claim() throws IOException {
            Path dir = Files.createDirectories(parent.root().resolve("child-homes/wt-child"));
            Files.writeString(dir.resolve("child-home.json"), """
                    {
                      "id" : "wt-child",
                      "parentHome" : "%s",
                      "childHome" : "%s",
                      "units" : [ ],
                      "createdAt" : "2026-09-14T00:00:00Z"
                    }
                    """.formatted(parent.root(), child.root()));
        }
    }

    /** deploy-helm's install-console-script.sh, reduced to the two lines that matter. */
    static String catInstaller() {
        return """
                #!/usr/bin/env bash
                set -euo pipefail
                launcher="${SKILL_MANAGER_HOME:?}/bin/cli/%1$s"
                venv="$SKILL_MANAGER_CACHE_DIR/skill-script-%2$s-%1$s/venv"
                mkdir -p "$venv/bin"
                printf '#!/bin/sh\\necho child-built\\n' > "$venv/bin/%1$s"
                chmod +x "$venv/bin/%1$s"
                cat >"$launcher" <<EOF
                #!/usr/bin/env bash
                # child-built
                exec "$venv/bin/%1$s" "\\$@"
                EOF
                chmod 0755 "$launcher"
                """.formatted(TOOL, UNIT);
    }

    static CliDependency dep() {
        return dep(TOOL);
    }

    static CliDependency depWithoutBinary() {
        return dep(null);
    }

    private static CliDependency dep(String binary) {
        Map<String, CliDependency.InstallTarget> install = new LinkedHashMap<>();
        install.put("any", new CliDependency.InstallTarget(
                null, null, binary, List.of(), null, "install.sh", List.of()));
        return new CliDependency(TOOL, "skill-script:" + TOOL, null, null, TOOL, true, install);
    }

    static void scaffold(SkillStore store, String script) throws IOException {
        Path scripts = Files.createDirectories(store.skillDir(UNIT).resolve(SkillScriptBackend.SCRIPTS_DIRNAME));
        Files.writeString(scripts.resolve("install.sh"), script);
    }
}
