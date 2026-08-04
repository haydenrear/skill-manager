package dev.skillmanager.cli.installer;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.lock.RequestedVersion;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The enumeration nobody wrote, and where all six defects of this class live:
 * <b>every artifact SHAPE × every installer BACKEND</b>.
 *
 * <h2>Why a matrix rather than more cases</h2>
 *
 * <p>Six times now, a backend has decided "already installed" by asking
 * something cheaper than the question it meant, and each time the wrong answer
 * arrived through a shape or a backend that the previous fix's tests did not
 * enumerate:
 *
 * <ul>
 *   <li>{@code isOnPath} answered for a FOREIGN HOME's copy (pip/npm/brew/tar)</li>
 *   <li>…and still did when that home's bin was reached through a SYMLINK</li>
 *   <li>{@code Files.exists} called a NON-EXECUTABLE regular file installed (tar)</li>
 *   <li>{@code Files.isExecutable} called a re-anchored WRAPPER installed
 *       (skill-script) while correctly rejecting a dangling SYMLINK in the same
 *       home on the same sync</li>
 * </ul>
 *
 * <p>Every one of those is a single cell of this table. Enumerating the table
 * is the only way to stop discovering them one clone at a time: a fix aimed at
 * the cell that was reported leaves the cells nobody thought of, and "the cells
 * nobody thought of" is the entire history of this defect.
 *
 * <h2>The law</h2>
 *
 * <p>For every backend and every shape: <b>the backend declines to install if
 * and only if the artifact would actually run from this home.</b> No backend
 * gets its own notion of "present", and no shape gets a different answer from a
 * different backend.
 *
 * <p>PATH is pinned to an empty directory for every cell, so the only thing
 * that can answer is the home. That is deliberate: with PATH left alone, the
 * operator's own {@code ~/.skill-manager/bin/cli} would answer for half of
 * these and the table would pass while measuring nothing — which is exactly how
 * the original defect survived.
 */
public final class CliArtifactMatrixTest {

    /** The tool name every cell installs, plants, and asks about. */
    private static final String TOOL = "matrix-tool";

    /**
     * What can be sitting at {@code bin/cli/<name>}, and whether it works.
     *
     * <p>The four unusable shapes are the four that have each been called
     * "installed" by some version of this code. The three usable ones are the
     * can-fail companions: a predicate that answers "broken" for everything
     * would satisfy the first four and reinstall on every sync forever.
     */
    private enum Shape {
        ABSENT(false) {
            void plant(SkillStore store, Path at) throws Exception {
                Files.deleteIfExists(at);
            }
        },
        DANGLING_SYMLINK(false) {
            void plant(SkillStore store, Path at) throws Exception {
                // What a clone leaves for every uv/npm tool: the link is
                // copied, the tree it points into is skipped.
                Files.createSymbolicLink(at, Path.of("../../cache/uv-tools/gone/bin/" + TOOL));
            }
        },
        WRAPPER_TARGET_MISSING(false) {
            void plant(SkillStore store, Path at) throws Exception {
                // What a clone leaves for every skill-script tool: the wrapper
                // is re-anchored to the NEW home and is itself a fine
                // executable. This is the cell that shipped broken.
                wrapper(at, store.root().resolve(
                        "cache/skill-script-unit-" + TOOL + "/venv/bin/" + TOOL));
            }
        },
        NON_EXECUTABLE_FILE(false) {
            void plant(SkillStore store, Path at) throws Exception {
                Files.writeString(at, "#!/bin/sh\necho " + TOOL + "\n");
                at.toFile().setExecutable(false, false);
            }
        },
        HEALTHY_SYMLINK(true) {
            void plant(SkillStore store, Path at) throws Exception {
                Path target = store.venvsDir().resolve("t/bin/" + TOOL);
                Files.createDirectories(target.getParent());
                executable(target);
                Files.createSymbolicLink(at, target);
            }
        },
        HEALTHY_WRAPPER(true) {
            void plant(SkillStore store, Path at) throws Exception {
                Path target = store.root().resolve(
                        "cache/skill-script-unit-" + TOOL + "/venv/bin/" + TOOL);
                Files.createDirectories(target.getParent());
                executable(target);
                wrapper(at, target);
            }
        },
        REAL_BINARY(true) {
            void plant(SkillStore store, Path at) throws Exception {
                executable(at);
            }
        };

        final boolean usable;

        Shape(boolean usable) { this.usable = usable; }

        abstract void plant(SkillStore store, Path at) throws Exception;

        static void wrapper(Path at, Path execTarget) throws Exception {
            Files.writeString(at, "#!/usr/bin/env bash\nexec \"" + execTarget + "\" \"$@\"\n");
            at.toFile().setExecutable(true, false);
        }

        static void executable(Path at) throws Exception {
            Files.writeString(at, "#!/bin/sh\necho " + at.getFileName() + "\n");
            at.toFile().setExecutable(true, false);
        }
    }

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("CliArtifactMatrixTest");

        // ---- the predicate itself, once per shape -------------------------
        for (Shape shape : Shape.values()) {
            suite.test("CliArtifact: " + shape + " is "
                    + (shape.usable ? "usable" : "NOT usable"), () -> {
                SkillStore store = newStore("matrix-predicate-" + shape + "-");
                Path at = store.cliBinDir().resolve(TOOL);
                shape.plant(store, at);
                CliArtifact.Verdict v = CliArtifact.inHome(store, TOOL);
                assertEquals(shape.usable, v.usable(), "verdict for " + shape + ": " + v);
                if (!shape.usable) {
                    assertTrue(v.reason() != null && !v.reason().isBlank(),
                            "an unusable artifact says why: " + v);
                }
            });
        }

        // ---- and every backend agreeing with it ---------------------------
        for (Backend backend : Backend.values()) {
            for (Shape shape : Shape.values()) {
                suite.test(backend + " × " + shape + " -> "
                        + (shape.usable ? "declines" : "installs"), () -> {
                    SkillStore store = newStore("matrix-" + backend + "-" + shape + "-");
                    backend.prepare(store);
                    Path at = store.cliBinDir().resolve(TOOL);
                    shape.plant(store, at);

                    Path empty = Files.createTempDirectory("matrix-empty-path-");
                    CliPresence.setPathOverride(empty.toString());
                    try {
                        boolean declined = backend.declinesToInstall(store);
                        assertEquals(shape.usable, declined,
                                backend + " with a " + shape + " artifact: "
                                        + (shape.usable
                                                ? "a working tool must not be reinstalled"
                                                : "a broken tool must be reinstalled"));
                    } finally {
                        CliPresence.clearPathOverride();
                    }
                });
            }
        }

        return suite.runAll();
    }

    // ------------------------------------------------------------- backends

    /**
     * Each backend, plus the cheapest observation that distinguishes "declined"
     * from "entered the install" WITHOUT a network round trip.
     *
     * <p>pip/npm/brew are given a blank package ref: each throws on it, and
     * each throws AFTER its presence check and BEFORE it bootstraps a
     * toolchain. tar is given no install target, which it reports as SKIPPED
     * for the same reason. skill-script is driven through its fingerprint gate
     * with a script that leaves a receipt.
     */
    private enum Backend {
        PIP {
            CliDependency dep() { return simple("pip:"); }
            boolean declinesToInstall(SkillStore store) throws Exception {
                return declinedVia(dep(), store, "pip: spec missing package name");
            }
        },
        NPM {
            CliDependency dep() { return simple("npm:"); }
            boolean declinesToInstall(SkillStore store) throws Exception {
                return declinedVia(dep(), store, "npm: spec missing package name");
            }
        },
        BREW {
            CliDependency dep() { return simple("brew:"); }
            boolean declinesToInstall(SkillStore store) throws Exception {
                // Driven directly rather than through InstallerRegistry: the
                // registry consults available(), which asks whether THIS HOST
                // has brew, and the matrix must measure the presence check
                // rather than the developer's machine.
                try {
                    return new BrewBackend().install(dep(), store, "matrix-unit")
                            == InstallOutcome.ALREADY_PRESENT;
                } catch (IOException entered) {
                    assertContainsSpecRefusal(entered, "brew: spec missing package name");
                    return false;
                }
            }
        },
        TAR {
            CliDependency dep() {
                return new CliDependency(TOOL, "tar:" + TOOL, null, null, TOOL, false, Map.of());
            }
            boolean declinesToInstall(SkillStore store) throws Exception {
                // No install target for this platform: SKIPPED once it has
                // decided to install, ALREADY_PRESENT if it never got there.
                return new InstallerRegistry().installOne(dep(), store, "matrix-unit")
                        == InstallOutcome.ALREADY_PRESENT;
            }
        },
        SKILL_SCRIPT {
            CliDependency dep() {
                Map<String, CliDependency.InstallTarget> install = new LinkedHashMap<>();
                install.put("any", new CliDependency.InstallTarget(
                        null, null, TOOL, List.of(), null, "install.sh", List.of()));
                return new CliDependency(
                        TOOL, "skill-script:" + TOOL, null, null, TOOL, true, install);
            }

            /** Scaffold the script and record a MATCHING fingerprint, so the
             *  only thing left that can decide a rerun is the artifact. */
            void prepare(SkillStore store) throws Exception {
                Path scripts = store.skillDir("matrix-unit").resolve(
                        SkillScriptBackend.SCRIPTS_DIRNAME);
                Files.createDirectories(scripts);
                Files.writeString(scripts.resolve("install.sh"), """
                        #!/bin/sh
                        set -eu
                        echo ran >> "$SKILL_MANAGER_BIN_DIR/ran.log"
                        # Unlink first, exactly as a real install script must:
                        # a shell redirect FOLLOWS a symlink, so writing over a
                        # dangling one fails with ENOENT on the target's parent.
                        rm -f "$SKILL_MANAGER_BIN_DIR/%s"
                        printf '#!/bin/sh\\necho ok\\n' > "$SKILL_MANAGER_BIN_DIR/%s"
                        chmod +x "$SKILL_MANAGER_BIN_DIR/%s"
                        """.formatted(TOOL, TOOL, TOOL));
                CliDependency dep = dep();
                CliLock lock = CliLock.load(store);
                RequestedVersion.Requested req = RequestedVersion.of(dep);
                lock.recordInstall(dep.backend(), req.tool(), req.version(), dep.spec(), null,
                        "matrix-unit", SkillScriptBackend.fingerprintFor(store, "matrix-unit", dep));
                lock.save(store);
            }

            boolean declinesToInstall(SkillStore store) throws Exception {
                new InstallerRegistry().installOne(dep(), store, "matrix-unit");
                // The receipt, not the outcome enum: this backend's whole
                // question is "did the script run".
                return !Files.exists(store.cliBinDir().resolve("ran.log"));
            }
        };

        abstract CliDependency dep();

        abstract boolean declinesToInstall(SkillStore store) throws Exception;

        void prepare(SkillStore store) throws Exception {}

        CliDependency simple(String spec) {
            return new CliDependency(TOOL, spec, null, null, TOOL, false, Map.of());
        }

        boolean declinedVia(CliDependency dep, SkillStore store, String refusal) throws Exception {
            try {
                return new InstallerRegistry().installOne(dep, store, "matrix-unit")
                        == InstallOutcome.ALREADY_PRESENT;
            } catch (IOException entered) {
                assertContainsSpecRefusal(entered, refusal);
                return false;
            }
        }

        void assertContainsSpecRefusal(IOException e, String expected) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            assertTrue(message.contains(expected),
                    "entered the install and refused for the expected reason; got: " + message);
        }
    }

    // ------------------------------------------------------------- fixture

    private static SkillStore newStore(String prefix) throws Exception {
        SkillStore store = new SkillStore(Files.createTempDirectory(prefix).toRealPath());
        store.init();
        Fs.ensureDir(store.cliBinDir());
        return store;
    }
}
