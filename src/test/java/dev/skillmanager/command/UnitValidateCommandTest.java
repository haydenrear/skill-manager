package dev.skillmanager.command;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.commands.UnitCommand;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.validation.MarkdownImportValidator;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;

/**
 * #427: {@code skill-manager publish --dry-run} was the documented
 * markdown-import validator, and {@code publish} went with the registry.
 * {@code unit validate <dir>} is the local replacement: it must FAIL on a
 * broken import (publish's dry-run only printed it) and pass on a clean unit.
 */
public final class UnitValidateCommandTest {

    public static int run() throws Exception {
        return Tests.suite("UnitValidateCommandTest")
                .test("a broken import exits non-zero and names it", () -> {
                    Path root = Files.createTempDirectory("unit-validate-");
                    Path unit = skill(root, "broken-skill", """
                            skill-imports:
                              - unit: no-such-unit
                                path: reference.md
                                reason: Needed by tests.
                            """);
                    Run r = validate(store(root), unit.toString());
                    assertEquals(MarkdownImportValidator.EXIT_CODE, r.rc, "violation exit code: " + r.out);
                    assertContains(r.out, "no-such-unit", "the broken import is named");
                })
                .test("a clean unit exits zero", () -> {
                    Path root = Files.createTempDirectory("unit-validate-");
                    Path unit = skill(root, "clean-skill", "skill-imports: []\n");
                    Run r = validate(store(root), unit.toString());
                    assertEquals(0, r.rc, "clean exit code: " + r.out);
                })
                .test("--json reports the violation and still exits non-zero", () -> {
                    Path root = Files.createTempDirectory("unit-validate-");
                    Path unit = skill(root, "broken-json", """
                            skill-imports:
                              - unit: no-such-unit
                                path: reference.md
                                reason: Needed by tests.
                            """);
                    Run r = validate(store(root), unit.toString(), "--json");
                    assertEquals(MarkdownImportValidator.EXIT_CODE, r.rc, "violation exit code: " + r.out);
                    assertContains(r.out, "\"ok\":false", "json says not ok");
                    assertContains(r.out, "no-such-unit", "json names the import");
                })
                .test("a directory with no manifest exits 1", () -> {
                    Path root = Files.createTempDirectory("unit-validate-");
                    Path empty = Files.createDirectories(root.resolve("empty"));
                    Run r = validate(store(root), empty.toString());
                    assertEquals(1, r.rc, "unparseable exit code: " + r.out);
                })
                .runAll();
    }

    private record Run(int rc, String out) {}

    private static SkillStore store(Path root) throws Exception {
        return new SkillStore(Files.createDirectories(root.resolve("home")));
    }

    private static Run validate(SkillStore store, String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = System.out, err = System.err;
        PrintStream cap = new PrintStream(buf, true, StandardCharsets.UTF_8);
        int rc;
        try {
            System.setOut(cap);
            System.setErr(cap);
            rc = new CommandLine(new UnitCommand.ValidateCmd(store)).execute(args);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return new Run(rc, buf.toString(StandardCharsets.UTF_8));
    }

    private static Path skill(Path root, String name, String imports) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\n"
                + "description: " + name + " fixture\n" + imports + "---\nbody\n");
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "%s fixture"
                """.formatted(name, name));
        return dir;
    }
}
