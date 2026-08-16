package dev.skillmanager.artifacts;

import dev.skillmanager.shared.util.Fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * The entry point a declared-but-not-built artifact leaves behind, and the one
 * thing standing between demand-driven materialization and an agent losing a
 * turn to a message it cannot act on.
 *
 * <h2>The failure this replaces, measured</h2>
 *
 * <p>A cloned ticket home has never carried {@code cache/}, {@code venvs/},
 * {@code tools/} or {@code npm/} — {@link dev.skillmanager.store.HomeCloner#SKIPPED_DIRS}
 * has skipped them since the first clone, for reasons that are still right. The
 * shims that run out of those trees are copied anyway, because {@code bin/} is
 * not skipped, so every cloned home has shipped entry points that resolve to
 * nothing. ARTI-01 found two of them on all five of its probe homes
 * ({@code bin/cli/jinja2 -> ../../venvs/jinja2-cli/bin/jinja2} and
 * {@code bin/cli/skill-dev -> ../../cache/uv-tools/skill-dev/bin/skill-dev});
 * the operator's project home has eight more of the generated kind, which
 * {@code exec} an absolute path under {@code cache/skill-script-<unit>-<tool>/}.
 *
 * <p>What an agent gets from one of those is a kernel-level diagnostic about
 * the wrong thing:
 *
 * <pre>
 *   bash: .../bin/cli/jinja2: No such file or directory
 *   .../bin/cli/computeq: line 3: .../venv/bin/computeq: bad interpreter
 * </pre>
 *
 * <p>Neither names the home, the artifact, or a command. So laziness is not
 * what creates this risk — the risk is already live and unnamed. What this
 * class does is make the state <b>say what it is and what clears it</b>, which
 * is the whole mitigation ARTI-07 offers for making the state more common.
 *
 * <h2>Three properties the generated file has to have</h2>
 *
 * <ol>
 *   <li><b>It exists and is executable.</b> A missing file cannot print
 *       anything; the kernel gets there first. That is precisely why the
 *       dangling-symlink form was invisible for so long — every presence check
 *       in the system passed on it.</li>
 *   <li><b>It holds no absolute path.</b> The home root is derived from the
 *       script's own location, exactly as the launcher pin does, so the file
 *       crosses a further clone unchanged and
 *       {@link dev.skillmanager.store.HomeCloner#verify} can never find a
 *       source-home reference in it. A generated shim that baked the home in
 *       would re-introduce the leak class the clone exists to remove.</li>
 *   <li><b>It exits {@link #EXIT_CODE}, not 127.</b> 127 is the shell's code
 *       for "command not found", and this command WAS found — a caller that
 *       maps 127 to "install it" would take the wrong branch. 86 is unused by
 *       this CLI and is the code a caller can test for "declared, not built".</li>
 * </ol>
 *
 * <p>The file is a plain {@code bash} script rather than a Java entry point on
 * purpose: it has to answer in milliseconds, from any shell, with no JVM start
 * and no dependency on the home being otherwise healthy.
 */
public final class ColdArtifactShim {

    private ColdArtifactShim() {}

    /**
     * Exit status of an entry point that is declared and not built.
     *
     * <p>Distinct from 127 ("command not found") and from 126 ("found, not
     * executable"): both of those are claims about the file, and the file is
     * fine. See the class javadoc.
     */
    public static final int EXIT_CODE = 86;

    /**
     * The marker line. Second line, so it survives {@code head -2} and so a
     * reader that only ever sees the first line of a script still gets the
     * shebang it expects.
     */
    public static final String MARKER =
            "# skill-manager:cold-artifact — generated for a home that builds artifacts on demand.";

    /**
     * Write the cold entry point for {@code artifactId} at {@code entry},
     * replacing whatever is there.
     *
     * @param entry      the path under {@code bin/cli} or {@code bin/mcp}
     * @param artifactId the id {@code skill-manager build} takes, already the
     *                   real id — never a guess, because the printed command is
     *                   executed by whoever reads it
     * @param why        one clause saying what is missing, for the second line
     */
    public static void write(Path entry, String artifactId, String why) throws IOException {
        String name = entry.getFileName().toString();
        String word = ArtifactBuild.shellWord(artifactId);
        String body = """
                #!/usr/bin/env bash
                %s
                # Do not edit: `skill-manager build` replaces this file with the real
                # entry point the moment the artifact is built.
                self_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
                home="$(cd -- "$self_dir/../.." && pwd -P)"
                {
                  echo "skill-manager: '%s' is declared in this home and has not been built."
                  echo "  reason:  %s"
                  echo "  home:    $home"
                  echo "  build it:  skill-manager build %s"
                  echo "        or:  skt build %s"
                } >&2
                exit %d
                """.formatted(MARKER, name, why, word, word, EXIT_CODE);
        Fs.ensureDir(entry.getParent());
        Files.deleteIfExists(entry);
        Files.writeString(entry, body, StandardCharsets.UTF_8);
        Fs.makeExecutable(entry);
    }

    /**
     * Whether {@code entry} is one of these.
     *
     * <p>Asked before anything overwrites an entry point and before anything
     * counts one as broken. A cold shim is a REGULAR, EXECUTABLE file that
     * resolves and runs, so every existing presence and resolution check
     * passes on it — which is the point, and also why a reader that needs to
     * know "is this the real tool" has to ask this rather than ask the
     * filesystem.
     */
    public static boolean isCold(Path entry) {
        if (entry == null || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) return false;
        // Bounded to the first few lines: the marker is line 2 by construction,
        // and bin/cli also holds copied BINARIES (TarBackend writes real files
        // there), which nothing should read whole to answer a yes/no question.
        try (var reader = Files.newBufferedReader(entry, StandardCharsets.UTF_8)) {
            for (int i = 0; i < 4; i++) {
                String line = reader.readLine();
                if (line == null) return false;
                if (line.startsWith(MARKER)) return true;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return false;
    }
}
