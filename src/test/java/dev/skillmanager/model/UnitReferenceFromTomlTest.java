package dev.skillmanager.model;

import dev.skillmanager._lib.test.Tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Skill manifests can now mix {@code skill:} and {@code plugin:} coords
 * in {@code skill_references}. The parser yields {@link UnitReference}s
 * with the {@link UnitKindFilter} derived from each coord's prefix.
 * Bare coords become {@link UnitKindFilter#ANY}; kinded coords narrow.
 * Inline-table entries (with explicit {@code name} / {@code version} /
 * {@code path}) round through {@link UnitReference#legacy} and land
 * with {@link UnitKindFilter#ANY}.
 *
 * <p>This is the manifest-layer guarantee that lets the planner
 * (ticket 05) walk one DAG over plugin + skill nodes — every reference
 * carries enough info to constrain the resolver.
 */
public final class UnitReferenceFromTomlTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("unit-reference-from-toml-test-");

        return Tests.suite("UnitReferenceFromTomlTest")
                .test("skill_references with mixed kinds → mixed kindFilters", () -> {
                    Skill skill = scaffold(tmp, "mixed-refs",
                            "skill:other-skill",
                            "plugin:repo-intelligence",
                            "bare-name@1.0.0",
                            "github:user/repo#main",
                            "./local-thing");
                    List<UnitReference> refs = skill.skillReferences();
                    assertSize(5, refs, "five references parsed");

                    Map<String, UnitReference> byCoord = byRaw(refs);

                    UnitReference skillRef = byCoord.get("skill:other-skill");
                    assertTrue(skillRef.coord() instanceof Coord.Kinded, "skill: → Kinded");
                    assertEquals(UnitKindFilter.SKILL_ONLY, skillRef.kindFilter(), "skill kind filter");
                    assertTrue(skillRef.isKindPinned(), "kind pinned");

                    UnitReference pluginRef = byCoord.get("plugin:repo-intelligence");
                    assertTrue(pluginRef.coord() instanceof Coord.Kinded, "plugin: → Kinded");
                    assertEquals(UnitKindFilter.PLUGIN_ONLY, pluginRef.kindFilter(), "plugin kind filter");

                    UnitReference bareRef = byCoord.get("bare-name@1.0.0");
                    assertTrue(bareRef.coord() instanceof Coord.Bare, "bare → Bare");
                    assertEquals(UnitKindFilter.ANY, bareRef.kindFilter(), "bare → ANY");
                    assertEquals("bare-name", bareRef.name(), "bare name");
                    assertEquals("1.0.0", bareRef.version(), "bare version");

                    UnitReference gitRef = byCoord.get("github:user/repo#main");
                    assertTrue(gitRef.coord() instanceof Coord.DirectGit, "github: → DirectGit");
                    assertEquals("https://github.com/user/repo", gitRef.gitUrl(), "url");
                    assertEquals("main", gitRef.gitRef(), "ref");

                    UnitReference localRef = byCoord.get("./local-thing");
                    assertTrue(localRef.coord() instanceof Coord.Local, "./ → Local");
                    assertTrue(localRef.isLocal(), "isLocal predicate");
                    assertEquals("./local-thing", localRef.path(), "path");
                })
                .test("inline-table reference (name + path) → legacy projection", () -> {
                    Path dir = tmp.resolve("inline-table");
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve(SkillParser.SKILL_FILENAME),
                            "---\nname: x\ndescription: y\n---\nbody");
                    Files.writeString(dir.resolve(SkillParser.TOML_FILENAME),
                            """
                            [skill]
                            name = "x"
                            version = "0.1.0"

                            [[skill_references]]
                            name = "explicit-name"
                            version = "1.0.0"
                            """);
                    Skill skill = SkillParser.load(dir);
                    assertSize(1, skill.skillReferences(), "one inline-table reference");
                    UnitReference ref = skill.skillReferences().get(0);
                    assertEquals("explicit-name", ref.name(), "name from inline table");
                    assertEquals("1.0.0", ref.version(), "version from inline table");
                    assertEquals(UnitKindFilter.ANY, ref.kindFilter(), "inline-table → ANY");
                })
                .test("inline-table reference (path only) → Local", () -> {
                    Path dir = tmp.resolve("inline-path");
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve(SkillParser.SKILL_FILENAME),
                            "---\nname: x\ndescription: y\n---\nbody");
                    Files.writeString(dir.resolve(SkillParser.TOML_FILENAME),
                            """
                            [skill]
                            name = "x"
                            version = "0.1.0"

                            [[skill_references]]
                            path = "./relative-target"
                            """);
                    Skill skill = SkillParser.load(dir);
                    assertSize(1, skill.skillReferences(), "one inline-table path reference");
                    UnitReference ref = skill.skillReferences().get(0);
                    assertTrue(ref.isLocal(), "isLocal");
                    assertEquals("./relative-target", ref.path(), "path");
                })
                .test("empty references → empty list", () -> {
                    Path dir = tmp.resolve("no-refs");
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve(SkillParser.SKILL_FILENAME),
                            "---\nname: x\ndescription: y\n---\nbody");
                    Files.writeString(dir.resolve(SkillParser.TOML_FILENAME),
                            """
                            [skill]
                            name = "x"
                            version = "0.1.0"
                            """);
                    Skill skill = SkillParser.load(dir);
                    assertSize(0, skill.skillReferences(), "no references");
                })
                .runAll();
    }

    private static Skill scaffold(Path tmp, String name, String... coords) throws IOException {
        Path dir = tmp.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SkillParser.SKILL_FILENAME),
                "---\nname: " + name + "\ndescription: fixture\n---\nbody");
        StringBuilder toml = new StringBuilder();
        toml.append("[skill]\n");
        toml.append("name = \"").append(name).append("\"\n");
        toml.append("version = \"0.1.0\"\n\n");
        toml.append("skill_references = [\n");
        for (String coord : coords) toml.append("  \"").append(coord).append("\",\n");
        toml.append("]\n");
        Files.writeString(dir.resolve(SkillParser.TOML_FILENAME), toml.toString());
        return SkillParser.load(dir);
    }

    private static Map<String, UnitReference> byRaw(List<UnitReference> refs) {
        Map<String, UnitReference> out = new HashMap<>();
        for (UnitReference r : refs) out.put(r.coord().raw(), r);
        return out;
    }
}
