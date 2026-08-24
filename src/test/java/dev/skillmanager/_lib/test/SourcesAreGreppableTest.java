package dev.skillmanager._lib.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * DEF-109 — <b>no tracked source file may contain a raw NUL byte, because a
 * source that contains one is invisible to search.</b>
 *
 * <h2>What this is defending, and why a comment could not</h2>
 *
 * <p>{@code grep -I} — the default for the wrappers agents in this repository
 * use — classifies any file containing a NUL as binary and <b>skips it
 * silently</b>: no match, no warning, exit 1. Two tracked Java sources had one,
 * written as a raw byte inside a char/string literal where the escape
 * {@code '\0'} would have compiled identically:
 *
 * <ul>
 *   <li>{@code ProjectVendoredResolver.key()} — <b>the class at the centre of
 *       DEF-103</b>, the defect HIS-22 exists to fix. Three conclusions in that
 *       ticket were false because a search of it returned nothing.</li>
 *   <li>{@code LazyArtifactHomeTest} — a fixture writing a fake native
 *       artifact.</li>
 * </ul>
 *
 * <p>It was caught in both cases only because the answer was <em>implausible</em>:
 * {@code grep -c "class" <file>} printed nothing at all, where 0 was the worst
 * honest answer. The reviewer of PR #255 then hit it a third time — its own
 * NUL-hunting command was itself a wrapper {@code grep}, and reported "no
 * NUL-bearing files".
 *
 * <h2>Why a test and not just the two fixes</h2>
 *
 * <p>Because the two fixes hold only until somebody types a third one, and
 * nothing about doing so looks wrong. This is the countermeasure the vacuity
 * ledger keeps asking for and rarely gets: not a note telling a human to be
 * careful, but a check that <b>fails loudly at the moment the condition
 * returns</b>. It reads the tree with {@code Files.readAllBytes}, which has no
 * opinion about binaries, so the instrument cannot suffer the defect it is
 * looking for.
 *
 * <p>Scoped to source and configuration extensions. Jars, PNGs and other real
 * binaries legitimately contain NULs and are none of this test's business —
 * there were nine of those in the tree when this was written, and they are
 * expected to stay.
 */
public final class SourcesAreGreppableTest {

    /** Extensions a reader expects to be able to grep. */
    private static final List<String> TEXT_EXTENSIONS = List.of(
            ".java", ".py", ".kt", ".kts", ".sh", ".md", ".toml", ".yaml", ".yml",
            ".json", ".tla", ".cfg", ".properties", ".txt");

    public static int run() throws Exception {
        return Tests.suite("SourcesAreGreppableTest")

                .test("no tracked source file contains a raw NUL byte", () -> {
                    Path repo = repoRoot();
                    List<Path> scanned = new ArrayList<>();
                    List<String> offenders = new ArrayList<>();
                    walk(repo, repo, scanned, offenders);

                    // THE INSTRUMENT'S OWN POSITIVE CONTROL. An empty offender
                    // list means nothing until something proves the walk reached
                    // files and the detector can fire. This epic has logged nine
                    // outputs that carried no way to detect their own
                    // invalidity, and DEF-109 is one of them; a check written to
                    // close it must not be a tenth.
                    assertTrue(scanned.size() > 500,
                            "the walk must actually reach the tree; scanned only " + scanned.size());
                    Path canary = Files.createTempFile("nul-canary-", ".java");
                    try {
                        Files.write(canary, "class A { char c = '\0'; }".getBytes());
                        assertTrue(containsNul(canary),
                                "the detector must fire on a file that really has one");
                        Files.write(canary, "class A { char c = '\\0'; }".getBytes());
                        assertTrue(!containsNul(canary),
                                "and must NOT fire on the escaped form, which is the fix");
                    } finally {
                        Files.deleteIfExists(canary);
                    }

                    assertEquals(0, offenders.size(),
                            "a source with a raw NUL is silently skipped by every `grep -I`, "
                                    + "so it cannot be searched and its absence from results reads "
                                    + "as absence from the tree. Write the escape `\\0` instead — "
                                    + "it compiles to the same byte. Offenders:\n  "
                                    + String.join("\n  ", offenders));
                })

                .runAll();
    }

    private static void walk(Path repo, Path dir, List<Path> scanned, List<String> offenders)
            throws IOException {
        try (var entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    if (name.equals(".git") || name.equals("build") || name.equals(".gradle")
                            || name.equals("node_modules") || name.equals(".skill-manager")
                            || name.equals(".venv") || name.equals("venvs")
                            || name.equals("__pycache__") || name.equals(".history")) {
                        continue;
                    }
                    walk(repo, entry, scanned, offenders);
                    continue;
                }
                if (TEXT_EXTENSIONS.stream().noneMatch(name::endsWith)) continue;
                scanned.add(entry);
                if (containsNul(entry)) offenders.add(repo.relativize(entry).toString());
            }
        }
    }

    private static boolean containsNul(Path file) throws IOException {
        for (byte b : Files.readAllBytes(file)) {
            if (b == 0) return true;
        }
        return false;
    }

    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve(".git")) || Files.isRegularFile(p.resolve(".git"))) {
                return p;
            }
        }
        return here;
    }
}
