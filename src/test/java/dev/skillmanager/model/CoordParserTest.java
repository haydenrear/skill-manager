package dev.skillmanager.model;

import dev.skillmanager._lib.test.Tests;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Sweeps every grammar form documented in the spec and pins the
 * resulting {@link Coord} variant + projected fields. The parser is
 * permissive: malformed-looking strings fall through to {@link Coord.Bare}.
 * Strict validation is the resolver's job (ticket 04), not the
 * parser's.
 */
public final class CoordParserTest {

    public static int run() {
        return Tests.suite("CoordParserTest")
                .test("bare name", () -> {
                    Coord c = Coord.parse("hello-skill");
                    assertTrue(c instanceof Coord.Bare, "Bare variant");
                    Coord.Bare b = (Coord.Bare) c;
                    assertEquals("hello-skill", b.name(), "name");
                    assertEquals(null, b.version(), "no version");
                    assertEquals("hello-skill", b.raw(), "raw preserved");
                })
                .test("bare name@version", () -> {
                    Coord c = Coord.parse("hello-skill@1.2.3");
                    Coord.Bare b = (Coord.Bare) c;
                    assertEquals("hello-skill", b.name(), "name");
                    assertEquals("1.2.3", b.version(), "version");
                })
                .test("skill: prefix → Kinded(SKILL)", () -> {
                    Coord c = Coord.parse("skill:hello");
                    Coord.Kinded k = (Coord.Kinded) c;
                    assertEquals(UnitKind.SKILL, k.kind(), "kind");
                    assertEquals("hello", k.name(), "name");
                    assertEquals(null, k.version(), "no version");
                })
                .test("skill: prefix with version", () -> {
                    Coord c = Coord.parse("skill:hello@1.0.0");
                    Coord.Kinded k = (Coord.Kinded) c;
                    assertEquals(UnitKind.SKILL, k.kind(), "kind");
                    assertEquals("hello", k.name(), "name");
                    assertEquals("1.0.0", k.version(), "version");
                })
                .test("plugin: prefix → Kinded(PLUGIN)", () -> {
                    Coord c = Coord.parse("plugin:repo-intelligence");
                    Coord.Kinded k = (Coord.Kinded) c;
                    assertEquals(UnitKind.PLUGIN, k.kind(), "kind");
                    assertEquals("repo-intelligence", k.name(), "name");
                })
                .test("plugin: prefix with version", () -> {
                    Coord c = Coord.parse("plugin:foo@2.0.1");
                    Coord.Kinded k = (Coord.Kinded) c;
                    assertEquals(UnitKind.PLUGIN, k.kind(), "kind");
                    assertEquals("foo", k.name(), "name");
                    assertEquals("2.0.1", k.version(), "version");
                })
                .test("github: → DirectGit", () -> {
                    Coord c = Coord.parse("github:user/repo");
                    Coord.DirectGit g = (Coord.DirectGit) c;
                    assertEquals("https://github.com/user/repo", g.url(), "url expanded");
                    assertEquals(null, g.ref(), "no ref");
                })
                .test("github: with #ref", () -> {
                    Coord c = Coord.parse("github:user/repo#main");
                    Coord.DirectGit g = (Coord.DirectGit) c;
                    assertEquals("https://github.com/user/repo", g.url(), "url stripped of ref");
                    assertEquals("main", g.ref(), "ref pinned");
                })
                .test("git+https URL", () -> {
                    Coord c = Coord.parse("git+https://gitlab.example.com/x/y.git");
                    Coord.DirectGit g = (Coord.DirectGit) c;
                    assertEquals("https://gitlab.example.com/x/y.git", g.url(), "url");
                    assertEquals(null, g.ref(), "no ref");
                })
                .test("git+ with #ref", () -> {
                    Coord c = Coord.parse("git+https://gitlab.example.com/x/y.git#v1.2.3");
                    Coord.DirectGit g = (Coord.DirectGit) c;
                    assertEquals("https://gitlab.example.com/x/y.git", g.url(), "url");
                    assertEquals("v1.2.3", g.ref(), "ref");
                })
                .test("file:// absolute path → Local", () -> {
                    Coord c = Coord.parse("file:///abs/path/to/skill");
                    Coord.Local l = (Coord.Local) c;
                    assertEquals("/abs/path/to/skill", l.path(), "absolute path stripped of scheme");
                })
                .test("file: (single-slash, legacy) → Local", () -> {
                    // Backward compat: existing skills published with this form
                    // continue to parse as local refs.
                    Coord c = Coord.parse("file:./relative/path");
                    Coord.Local l = (Coord.Local) c;
                    assertEquals("./relative/path", l.path(), "single-slash file: prefix accepted");
                })
                .test("./ relative path → Local", () -> {
                    Coord c = Coord.parse("./local-skill");
                    Coord.Local l = (Coord.Local) c;
                    assertEquals("./local-skill", l.path(), "kept verbatim");
                })
                .test("../ relative path → Local", () -> {
                    Coord c = Coord.parse("../sibling");
                    Coord.Local l = (Coord.Local) c;
                    assertEquals("../sibling", l.path(), "kept verbatim");
                })
                .test("/ absolute path → Local", () -> {
                    Coord c = Coord.parse("/usr/local/skill");
                    Coord.Local l = (Coord.Local) c;
                    assertEquals("/usr/local/skill", l.path(), "kept verbatim");
                })
                .test("blank input rejected", () -> {
                    try {
                        Coord.parse("");
                        throw new AssertionError("expected IllegalArgumentException");
                    } catch (IllegalArgumentException expected) {}
                    try {
                        Coord.parse("   ");
                        throw new AssertionError("expected IllegalArgumentException");
                    } catch (IllegalArgumentException expected) {}
                })
                .test("null input rejected", () -> {
                    try {
                        Coord.parse(null);
                        throw new AssertionError("expected IllegalArgumentException");
                    } catch (IllegalArgumentException expected) {}
                })
                .test("whitespace trimmed", () -> {
                    Coord c = Coord.parse("  hello  ");
                    Coord.Bare b = (Coord.Bare) c;
                    assertEquals("hello", b.name(), "trimmed");
                    assertEquals("hello", b.raw(), "raw is trimmed too");
                })
                .test("skill: with sub-element → SubElement wrapping Kinded", () -> {
                    Coord c = Coord.parse("skill:repo-intel/quick-mode");
                    assertTrue(c instanceof Coord.SubElement, "SubElement variant");
                    Coord.SubElement s = (Coord.SubElement) c;
                    assertEquals("quick-mode", s.elementName(), "element name");
                    Coord.Kinded inner = (Coord.Kinded) s.unitCoord();
                    assertEquals(UnitKind.SKILL, inner.kind(), "inner kind");
                    assertEquals("repo-intel", inner.name(), "inner name");
                    assertEquals(null, inner.version(), "no inner version");
                    assertEquals("skill:repo-intel", inner.raw(), "synthesized inner raw");
                    assertEquals("skill:repo-intel/quick-mode", s.raw(), "outer raw preserved");
                })
                .test("plugin: with sub-element and version", () -> {
                    Coord c = Coord.parse("plugin:repo-intel/quick-mode@1.2.3");
                    Coord.SubElement s = (Coord.SubElement) c;
                    assertEquals("quick-mode", s.elementName(), "element name");
                    Coord.Kinded inner = (Coord.Kinded) s.unitCoord();
                    assertEquals("repo-intel", inner.name(), "inner name");
                    assertEquals("1.2.3", inner.version(), "version attaches to unit, not element");
                    assertEquals("plugin:repo-intel@1.2.3", inner.raw(), "synthesized inner raw includes version");
                })
                .test("bare with sub-element", () -> {
                    Coord c = Coord.parse("my-prompts/review-stance");
                    Coord.SubElement s = (Coord.SubElement) c;
                    assertEquals("review-stance", s.elementName(), "element name");
                    Coord.Bare inner = (Coord.Bare) s.unitCoord();
                    assertEquals("my-prompts", inner.name(), "inner name");
                })
                .test("unrecognized prefix falls through to Bare", () -> {
                    // The parser is permissive — only known prefixes route to
                    // their variants; anything else is a registry name and the
                    // resolver decides what to do with it.
                    Coord c = Coord.parse("weird:thing");
                    assertTrue(c instanceof Coord.Bare, "Bare fallback");
                    assertEquals("weird:thing", ((Coord.Bare) c).name(), "name preserved");
                })
                .runAll();
    }
}
