package dev.skillmanager.lifecycle;

import dev.skillmanager._lib.test.Tests;

import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * OUN-13 / DEF-OUN-017: two tables describe the same fact and must not
 * disagree.
 *
 * <p>{@link UnitSupersession#TABLE} says a unit has MOVED INTO a carrier and
 * retires the standalone. {@link BundledSkills} says a unit is installed on
 * every fresh onboard. A unit in both is installed in order to be retired on
 * the way through — which is exactly where {@code skill-manager} sat between
 * OUN-5 and OUN-13: the migration dropped it, the onboard put it back, and a
 * root sync visibly re-cloned the coordinate every time.
 *
 * <p>This is one assertion rather than a story because the failure mode is
 * silent: nothing crashes, a fresh home just does redundant work forever.
 */
public final class BundledAndSupersededDisagreeTest {

    public static int run() {
        Tests.Suite suite = Tests.suite("BundledAndSupersededDisagreeTest");

        suite.test("no unit is both bundled and superseded", () -> {
            for (UnitSupersession.Retirement r : UnitSupersession.TABLE) {
                if (r.kind() != UnitSupersession.Kind.MOVED_INTO_CARRIER) continue;
                assertTrue(!BundledSkills.isBundled(r.unit()),
                        "`" + r.unit() + "` is retired into `" + r.carrier() + "` by "
                                + "UnitSupersession.TABLE and still bundled by "
                                + "BundledSkills — a fresh onboard would install it "
                                + "only for the install path to retire it again. "
                                + "Remove it from BundledSkills.GITHUB_COORDS.");
            }
        });

        suite.test("CONTROL: the carrier itself is still bundled", () -> {
            assertTrue(BundledSkills.isBundled("skt"),
                    "skt carries the moved units, so it must arrive on a fresh onboard — "
                            + "without this the assertion above passes vacuously by "
                            + "bundling nothing at all");
        });

        return suite.runAll();
    }
}
