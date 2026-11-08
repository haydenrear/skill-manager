package dev.skillmanager.commands;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.HomeSync;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * What {@code home sync --unit} SAYS and RETURNS, as opposed to what it does.
 *
 * <p>{@link dev.skillmanager.store.HomeSyncUnitFilterTest} covers the
 * reconcile. Nothing covered the surface, and for this flag the surface is
 * load-bearing twice over.
 *
 * <p><b>The exit code is an API.</b> {@code skt publish} feature-detects the
 * flag, so that an older pin degrades to a whole-home sync rather than
 * hard-failing — and to do that it must tell "this CLI has no {@code --unit}"
 * from "that unit does not exist". The first ships {@code EXIT_CODE} 12 for
 * the second precisely so the two are separable; a test that only asserted
 * "non-zero" would let the number drift back onto picocli's usage code and
 * turn a typo into a silent whole-home sync.
 *
 * <p><b>The report's own defence is a rendered string.</b> A targeted pass
 * prints seven zeroes out of eight counts, which reads exactly like a clean
 * home; the {@code unit:} line and the JSON {@code "unit"} field are the only
 * things separating "one unit reconciled" from "the whole home is fine". They
 * were argued for in the change that added them and then asserted nowhere.
 */
public final class HomeSyncUnitCliTest {

    public static int run() throws Exception {
        return Tests.suite("HomeSyncUnitCliTest")

                .test("an unknown --unit exits 12, NOT picocli's usage code", () -> {
                    Homes homes = homes("exit");
                    Result r = sync(homes, "--unit", "no-such-unit");

                    assertEquals(12, r.rc(), "the documented UnknownUnitException code");
                    assertEquals(HomeSync.UnknownUnitException.EXIT_CODE, r.rc(),
                            "and the constant callers compile against");
                    assertFalse(r.rc() == 2,
                            "2 is picocli's usage code — a caller feature-detecting the flag "
                                    + "would read that as 'this CLI has no --unit' and silently "
                                    + "fall back to the whole-home sync this flag exists to avoid");
                    assertContains(r.all(), "no unit named 'no-such-unit'", "and it says which");
                })

                .test("#434 F7: a record that cannot be restated exits non-zero and still visits the other units", () -> {
                    Homes homes = homes("record-ro");
                    SkillStore dest = new SkillStore(homes.dest());
                    assertEquals(0, sync(homes).rc(), "first pass copies both units");
                    UnitStore records = new UnitStore(dest);
                    records.write(staleRecord("alpha"));
                    records.write(staleRecord("beta"));
                    assertTrue(records.file("alpha").toFile().setWritable(false), "fixture: record read-only");
                    try {
                        Result r = sync(homes);
                        assertEquals(1, r.rc(), "a record the pass could not write fails the command: " + r.all());
                        assertContains(r.all(), "alpha", "the failure names the unit");
                        HomeSync.Report report = HomeSync.run(new SkillStore(homes.source()), dest,
                                new HomeSync.Options(false, false));
                        assertTrue(report.recordFailures().stream().anyMatch(f -> f.contains("alpha")),
                                "and the Report carries it: " + report.recordFailures());
                        assertFalse("0.0.1".equals(records.read("beta").orElseThrow().version()),
                                "the unit after the failure was still restated");
                    } finally {
                        records.file("alpha").toFile().setWritable(true);
                    }
                })

                .test("picocli still owns 2, so the two really are distinguishable", () -> {
                    Homes homes = homes("usage");
                    Result r = sync(homes, "--no-such-flag");

                    assertEquals(2, r.rc(), "an unknown OPTION is picocli's usage error");
                    assertTrue(r.rc() != HomeSync.UnknownUnitException.EXIT_CODE,
                            "which is the whole point: one code per meaning");
                })

                .test("a targeted run NAMES the unit, so its zeroes cannot read as a clean home", () -> {
                    Homes homes = homes("render");
                    Result r = sync(homes, "--unit", "alpha");

                    assertEquals(0, r.rc(), "a clean targeted sync succeeds");
                    assertContains(r.all(), "unit:", "the report names the narrowing");
                    assertContains(r.all(), "alpha", "with the unit");
                    assertContains(r.all(), "the rest of the home was not visited",
                            "and says plainly what it did NOT look at");
                    assertContains(r.all(), "(unit alpha only)",
                            "the success line is qualified too — 'reconciled <home>' alone "
                                    + "is a claim about the whole home");
                })

                .test("a whole-home run says none of that", () -> {
                    Homes homes = homes("whole");
                    Result r = sync(homes);

                    assertEquals(0, r.rc(), "still succeeds");
                    assertFalse(r.all().contains("unit:"), "no narrowing line");
                    assertFalse(r.all().contains("only)"), "and no qualifier on the success line");
                })

                .test("--json carries the unit: null whole-home, the name when targeted", () -> {
                    Homes homes = homes("json");

                    Result whole = sync(homes, "--json");
                    assertContains(whole.out(), "\"unit\":null",
                            "a whole-home pass reports no filter");

                    Result targeted = sync(homes, "--json", "--unit", "alpha");
                    assertContains(targeted.out(), "\"unit\":\"alpha\"",
                            "without this, clean:true on one unit is indistinguishable "
                                    + "from clean:true on the whole home");
                    assertContains(targeted.out(), "\"clean\":true", "and it IS clean");
                })

                .test("--json on the refusal is a payload, not an empty stdout", () -> {
                    Homes homes = homes("errjson");
                    Result r = sync(homes, "--json", "--unit", "no-such-unit");

                    assertEquals(12, r.rc(), "same code with --json");
                    assertContains(r.out(), "\"error\":\"unknown_unit\"", "typed");
                    assertContains(r.out(), "\"unit\":\"no-such-unit\"", "and names the unit");
                    assertContains(r.out(), "\"clean\":false",
                            "a script must not read a refusal as a reconcile");
                    assertContains(r.out(), "\"exitCode\":12", "the code is in the payload too");
                })

                .runAll();
    }

    // ------------------------------------------------------------- helpers

    private record Homes(Path source, Path dest) {}

    private record Result(int rc, String out, String err) {
        String all() { return out + err; }
    }

    private static Homes homes(String label) throws IOException {
        Path root = Files.createTempDirectory("home-sync-cli-" + label + "-");
        SkillStore source = new SkillStore(root.resolve("source"));
        source.init();
        SkillStore dest = new SkillStore(root.resolve("dest"));
        dest.init();
        UnitFixtures.scaffoldSkill(source.skillsDir(), "alpha", DepSpec.empty());
        UnitFixtures.scaffoldSkill(source.skillsDir(), "beta", DepSpec.empty());
        return new Homes(source.root(), dest.root());
    }

    /** An installed record naming a version the manifest does not, so a pass must restate it. */
    private static InstalledUnit staleRecord(String name) {
        return new InstalledUnit(name, "0.0.1", InstalledUnit.Kind.LOCAL_DIR,
                InstalledUnit.InstallSource.LOCAL_FILE, "/nowhere/" + name, null, null,
                "2026-01-01T00:00:00Z", java.util.List.of(), UnitKind.SKILL);
    }

    private static Result sync(Homes homes, String... extra) {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = new String[4 + extra.length];
        args[0] = "--from";
        args[1] = homes.source().toString();
        args[2] = "--to";
        args[3] = homes.dest().toString();
        System.arraycopy(extra, 0, args, 4, extra.length);
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            int rc = new CommandLine(new HomeCommand.SyncCmd()).execute(args);
            return new Result(rc, out.toString(), err.toString());
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }
}
