package dev.skillmanager.model;

import dev.skillmanager._lib.test.Tests;

import static dev.skillmanager._lib.test.Tests.assertEquals;

/**
 * Pins {@code Coord.parse(coord.render()) == coord} for every grammar
 * form. Round-tripping is the contract that lets a coord be serialized
 * (e.g. into a lockfile or a plan-print line), persisted, and parsed
 * back into the same record. Without it, downstream tooling that
 * stores coords as strings can't reliably reconstruct the original.
 */
public final class CoordRoundTripTest {

    public static int run() {
        String[] inputs = {
                // bare
                "hello-skill",
                "hello-skill@1.2.3",
                "x@0",
                // kinded
                "skill:hello",
                "skill:hello@1.0.0",
                "plugin:repo-intelligence",
                "plugin:foo@2.0.1",
                // direct git
                "github:user/repo",
                "github:user/repo#main",
                "github:owner/proj#v1.0.0-alpha",
                "git+https://gitlab.example.com/x/y.git",
                "git+https://gitlab.example.com/x/y.git#v1.2.3",
                "git+ssh://git@host/x.git#abc123",
                // local
                "file:///abs/path",
                "file:./legacy/relative",
                "./relative",
                "../sibling/x",
                "/abs/path",
        };

        Tests.Suite suite = Tests.suite("CoordRoundTripTest");
        for (String input : inputs) {
            suite.test("round-trip: " + input, () -> {
                Coord parsed = Coord.parse(input);
                String rendered = parsed.render();
                Coord reparsed = Coord.parse(rendered);
                assertEquals(parsed, reparsed,
                        "parse ∘ render is identity (input=" + input + ", rendered=" + rendered + ")");
            });
        }
        return suite.runAll();
    }
}
