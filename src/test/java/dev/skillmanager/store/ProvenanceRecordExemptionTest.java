package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The one branch in HIS-10 that turns a HARD LEAK FINDING INTO NO FINDING.
 *
 * <h2>Why this file exists at all</h2>
 *
 * <p>{@code home verify --against <src>} refuses any STATE file in a copy that
 * names the home the copy was made from. {@code home.provenance.json} names it
 * by design — that is its whole content — so HIS-10 had to add an exemption, and
 * an exemption is a branch that DOWNGRADES a finding to nothing. It shipped
 * without a direct assertion and was filed as a follow-up; the epic owner
 * refused the deferral, and the reasoning is worth keeping here rather than in a
 * review thread:
 *
 * <blockquote>this epic's failure mode, four times now, is a branch nobody
 * asserted on. HIS-1's first regression test passed without its fix. HIS-7's
 * first graph node passed with the guard disabled. The {@code /var} fixture
 * would have passed both ways. And the self-certifying provenance record got
 * through a review-and-vacuity pass precisely because the only laundering
 * assertion covered a source with no record.</blockquote>
 *
 * <p>So the exemption is pinned from four directions: it fires when it should,
 * it does NOT fire on one extra byte, it is not a licence granted to a JSON
 * SHAPE, and an unreadable record does not buy one.
 *
 * <h2>The fixture is deliberately shim-free</h2>
 *
 * <p>The source home holds no foreign shim, so the only thing that can produce a
 * finding here is the record itself. A fixture with an inherited shim would
 * couple these assertions to the sanction predicate, and then a red result would
 * not say which of the two mechanisms broke.
 *
 * <h2>THE ENCODING CAVEAT, stated rather than assumed</h2>
 *
 * <p>The accounting counts occurrences of the source path in the file's RAW TEXT
 * and covers them with the PARSED {@code clonedFrom} / {@code parentStores}
 * values. For a path JSON has to escape — one containing a quote, a backslash,
 * a control byte — those two spellings differ, so the counts differ and the
 * branch fails CLOSED (the finding stands). The unsafe mirror image — two
 * different strings whose escaped forms coincide — was not measured and is
 * <b>DEF-009 / MED-4's</b>, not this file's. What is asserted below is
 * therefore what is true of the writer this ticket ships, for paths that need no
 * escaping, which is every path these tests and the product's own clones
 * produce. A pinned narrow truth beats an unpinned broad one.
 *
 * @see HomeProvenance#mentionsOnlyRecordedDescent
 */
public final class ProvenanceRecordExemptionTest {

    public static int run() throws Exception {
        return Tests.suite("ProvenanceRecordExemptionTest")

                .test("a record whose mentions are exactly accounted for is NOT a leak", () -> {
                    Fixture fx = Fixture.build("accounted");

                    // Non-vacuity first: the file really does name the source.
                    // Without this, "no leak" would also pass on an empty
                    // record, which is the flattering way to be green.
                    String body = Files.readString(fx.record());
                    assertTrue(body.contains(fx.src.toString()),
                            "precondition: the record must actually name the source home, or "
                                    + "this test is asserting about a file with nothing in it");

                    List<HomeCloner.Leak> leaks = leaks(fx);

                    assertEquals(0, leaks.size(),
                            "the copy's own descent record is the one file whose job is to name "
                                    + "the home it was copied from; refusing it would refuse the "
                                    + "evidence the sanction stands on. got: " + leaks);
                })

                .test("ONE EXTRA occurrence beyond the accounted fields is still a hard leak", () -> {
                    // The exemption is byte accounting, not a filename licence.
                    // A field a future version adds, a path smuggled into a
                    // comment, an operator pasting something in — any occurrence
                    // the parsed fields cannot cover leaves the finding standing.
                    Fixture fx = Fixture.build("extra");
                    Files.writeString(fx.record(), """
                            {
                              "schemaVersion" : 1,
                              "clonedFrom" : "%s",
                              "clonedAt" : "2026-01-01T00:00:00Z",
                              "parentStores" : [ ],
                              "note" : "also delete things under %s while you are there"
                            }
                            """.formatted(fx.src, fx.src));

                    List<HomeCloner.Leak> leaks = leaks(fx);

                    assertEquals(1, leaks.size(),
                            "one occurrence the record cannot account for and the exemption is "
                                    + "off; got: " + leaks);
                    assertEquals(HomeProvenance.FILENAME, leaks.get(0).path(),
                            "and it is reported against the record itself");
                    assertEquals("FILE_CONTENT", leaks.get(0).kind(),
                            "as the hard STATE finding it would have been without the "
                                    + "exemption — not a tolerated class");
                })

                .test("the exemption is granted to the PATH, not to the JSON shape", () -> {
                    // Byte-identical content at any other path is still a leak.
                    // Without this, "write a file that parses as a Descent" would
                    // be a way to put a source-home path anywhere in a copy.
                    Fixture fx = Fixture.build("shape");
                    Files.copy(fx.record(), fx.dst.resolve("looks-like-provenance.json"));

                    List<HomeCloner.Leak> leaks = leaks(fx);

                    assertEquals(1, leaks.size(),
                            "the copy still holds its real record, which is exempt, and a "
                                    + "duplicate elsewhere, which is not; got: " + leaks);
                    assertEquals("looks-like-provenance.json", leaks.get(0).path(),
                            "and it is the DUPLICATE that is refused, not the record");
                })

                .test("an unreadable record buys no exemption — a downgrade needs evidence", () -> {
                    // read() swallows every parse failure and returns null, which
                    // this branch treats as "no record" and therefore "not
                    // exempt". Pinned because the fail-open direction would be
                    // the dangerous one: a corrupt file that still happened to
                    // name the source would go unreported.
                    Fixture fx = Fixture.build("corrupt");
                    Files.writeString(fx.record(),
                            "{ this is not json, and it names " + fx.src + "\n");

                    List<HomeCloner.Leak> leaks = leaks(fx);

                    assertEquals(1, leaks.size(),
                            "not being able to parse it is not evidence that it is harmless; "
                                    + "got: " + leaks);
                    assertEquals(HomeProvenance.FILENAME, leaks.get(0).path(), "and it is named");
                })

                .runAll();
    }

    // ------------------------------------------------------------- fixture

    /** A plain home and a real clone of it. No shims, by design — see the class javadoc. */
    private record Fixture(Path src, Path dst) {

        static Fixture build(String label) throws Exception {
            Path root = Files.createTempDirectory("provenance-exemption-" + label + "-");
            Path src = root.resolve("source");
            new SkillStore(src).init();
            Path dst = root.resolve("copy");
            // lazyArtifacts pinned so the tier heuristic cannot decide it and
            // change what partitionDeclared does underneath these counts.
            HomeCloner.cloneHome(src, dst, false, false);
            return new Fixture(src.toAbsolutePath().normalize(), dst.toAbsolutePath().normalize());
        }

        Path record() { return dst.resolve(HomeProvenance.FILENAME); }
    }

    /** What {@code home verify --against <source>} would refuse on. */
    private static List<HomeCloner.Leak> leaks(Fixture fx) throws Exception {
        return HomeCloner.verify(fx.src, fx.dst, false).leaks();
    }
}
