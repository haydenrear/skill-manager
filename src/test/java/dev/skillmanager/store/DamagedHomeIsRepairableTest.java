package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.commands.HomeCommand;
import dev.skillmanager.launch.LaunchEnv;
import dev.skillmanager.launch.LauncherShims;
import dev.skillmanager.policy.HomePolicy;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * <b>A home that is ALREADY damaged can be told apart from a healthy one, and
 * repaired.</b>
 *
 * <p>HIS-13 / issue #159. {@code GOAL-no-destructive-recovery} clause 2, whose
 * baseline is <i>"NO detection and NO repair exist"</i>.
 *
 * <h2>The fixture is ROOT-SHAPED, and that is an acceptance criterion</h2>
 *
 * <p>"Detection covers the root tier, and there is a test that runs it there."
 * The root tier is the one that keeps taking the damage — 24 links, then 38 —
 * and it is structurally different from every other tier in three ways that all
 * matter here:
 *
 * <ul>
 *   <li>it has <b>no descent record</b>, so {@link HomeProvenance#sanctions}
 *       sanctions nothing and every foreign path is unsanctioned. A detector
 *       tested only against clones would be tested only against the branch that
 *       has a record to read;</li>
 *   <li>its agent directories are {@code <root>/.claude} and siblings, i.e. the
 *       parent of the store rather than a profile directory; and</li>
 *   <li>{@code $HOME} is its home root, so a repair confined to "the enclosing
 *       directory" would be confined to the whole of {@code $HOME}.</li>
 * </ul>
 *
 * <p>{@link Fixture} builds one under a temp directory and NEVER touches the
 * real {@code $HOME}: the store path is what the detector derives the agent
 * directories from, so pointing it at a scratch root is sufficient — and
 * {@code the_agent_axis_comes_from_the_home_and_not_the_environment} is the
 * test that proves that is true rather than assumed.
 *
 * <h2>Every assertion here has a mutation that reddens it</h2>
 *
 * <p>Recorded in {@code results/epic-home-integrity-sync/tickets/HIS-13/} with
 * the observed output. Mechanism D of the vacuity ledger is the trap this
 * ticket is most exposed to — damaged-home fixtures come in symlink,
 * regular-file and missing-file flavours and production branches on exactly
 * that — so the four damage shapes are deliberately one of each:
 *
 * <pre>
 *   MISANCHORED_AGENT_LINK   a SYMLINK, outside the store, resolving fine
 *   FOREIGN_PATH_IN_SHIM     a REGULAR FILE whose TEXT names another home
 *   PRUNED_INHERITED_ENTRY   a MISSING file the ledger says should be here
 *   DANGLING_CLI_PIN         a REGULAR FILE naming a path that is not there
 * </pre>
 *
 * <p>They are decided by four different branches of {@link HomeRepair#detect},
 * and no probe below is counted against a branch it does not exercise.
 */
public final class DamagedHomeIsRepairableTest {

    /** The unit a projection names, and the tool a shim carries. */
    private static final String UNIT = "alpha";
    private static final String TOOL = "alpha-tool";

    public static int run() throws Exception {
        return Tests.suite("DamagedHomeIsRepairableTest")

                .test("#289: a SANCTIONED parent mirror is a finding when this home holds "
                        + "its own copy of what it runs", () -> {
                    // THE DISAGREEMENT THIS CLOSES, measured on 2026-08-31 on
                    // this machine, both readers asked about the same homes at
                    // the same moment:
                    //
                    //   scripts/measure_goal_home_runs_its_own_copy.py
                    //     -> 43 unsanctioned pairs, 15 homes, target 0
                    //   skill-manager home repair --home <one of those homes>
                    //     -> "nothing ... is damaged in a way this command
                    //         knows about (32 entries examined)"
                    //
                    // `sanctionedParentShim` requires three conditions -- shim
                    // entry, same target, ancestor home -- and NONE of them asks
                    // whether this home holds its own copy of the program the
                    // entry runs. The goal's walker asks exactly that, calls the
                    // answer "unsanctioned", and counted 0 of the legitimate
                    // fallback kind: every crossing on the machine had a local
                    // copy sitting unused.
                    //
                    // THE FIXTURE IS THE SANCTIONED SHAPE, deliberately. If the
                    // link were unsanctioned the existing DEF-115 arm would name
                    // it and this test would pass without exercising anything
                    // new -- so the precondition below asserts the sanction
                    // holds before the claim asserts the finding.
                    Fixture fx = Fixture.build("shadowed");

                    String tool = "shadowtool";
                    String script = "skills/" + UNIT + "/run.py";

                    // The parent holds the unit and a shim that RUNS it.
                    Path parentScript = fx.other.resolve(script);
                    Files.createDirectories(parentScript.getParent());
                    Files.writeString(parentScript, "print('parent')\n");
                    Path parentEntry = fx.other.resolve("bin/cli").resolve(tool);
                    Files.writeString(parentEntry,
                            "#!/usr/bin/env bash\nexec python3 \"" + parentScript + "\" \"$@\"\n");
                    parentEntry.toFile().setExecutable(true);

                    // This home mirrors it -- the shape ChildHomeMaterializer
                    // writes, and legitimate on its face.
                    Path mirror = fx.store.resolve("bin/cli").resolve(tool);
                    Files.createSymbolicLink(mirror, parentEntry);

                    // AND THIS HOME HAS ITS OWN COPY. This is the whole fact:
                    // the bytes above are correct in a home without this file
                    // and wrong in a home with it.
                    Path mine = fx.store.resolve(script);
                    Files.createDirectories(mine.getParent());
                    Files.writeString(mine, "print('mine')\n");

                    assertTrue(HomeCloner.unsanctionedForeignHome(
                                    "bin/cli/" + tool, mirror, fx.store) == null,
                            "precondition: the mirror is SANCTIONED, so the existing "
                                    + "foreign-path arms do not fire and this asserts about "
                                    + "the new condition rather than an old one");

                    List<String> subjects = HomeRepair.detect(fx.store, fx.pin).findings().stream()
                            .filter(f -> f.kind() == HomeRepair.Kind.PARENT_SHIM_SHADOWS_LOCAL_COPY)
                            .map(HomeRepair.Finding::subject)
                            .distinct().sorted().toList();

                    assertEquals(List.of("bin/cli/" + tool), subjects,
                            "THE CLAIM: repair names the shadowed mirror, so it and the "
                                    + "goal's walker answer 'does this home run its own copy' "
                                    + "the same way");
                })

                .test("#289: --fix leaves the shadowed mirror REPORTED, and that is deliberate",
                        () -> {
                    // THE GUARD AGAINST A DESTRUCTIVE "IMPROVEMENT". The only
                    // mechanical repair available here is to delete the link,
                    // which takes away the sole entry point this home currently
                    // reaches for that tool and puts nothing in its place. The
                    // remedy is to REBUILD the home's own entry point, which
                    // needs the unit's declaration and is `skill-manager
                    // build`'s job.
                    //
                    // So this asserts the finding SURVIVES --fix. If someone
                    // later wires a rewrite into it, this goes red and they
                    // have to argue for it rather than discover it in a home.
                    Fixture fx = Fixture.build("shadow-not-repaired");
                    fx.damageShadowedMirror();

                    HomeRepair.Report before = HomeRepair.detect(fx.store, fx.pin);
                    assertTrue(before.findings().stream()
                                    .anyMatch(f -> f.kind()
                                            == HomeRepair.Kind.PARENT_SHIM_SHADOWS_LOCAL_COPY),
                            "precondition: the shape is planted and reported");

                    HomeRepair.repair(fx.store, fx.pin);

                    HomeRepair.Report after = HomeRepair.detect(fx.store, fx.pin);
                    assertTrue(after.findings().stream()
                                    .anyMatch(f -> f.kind()
                                            == HomeRepair.Kind.PARENT_SHIM_SHADOWS_LOCAL_COPY),
                            "THE CLAIM: --fix does not silently take the tool away; the "
                                    + "finding is still reported afterwards");
                    assertTrue(Files.exists(fx.store.resolve("bin/cli/shadowed-tool"),
                                    LinkOption.NOFOLLOW_LINKS),
                            "and the link itself is still there — repair removed nothing");
                })

                .test("#289: a sanctioned mirror with NO local copy stays clean", () -> {
                    // THE OTHER DIRECTION, and it is the one that protects
                    // dispatch. HIS-7 records what a narrowed sanction cost the
                    // last time: `bootstrap-home.sh` failed and neither
                    // `wt new` nor `skt ticket new` could produce a ticket home
                    // at all. Sharing a parent's provisioned toolchain is what a
                    // child home is FOR, so a mirror with nothing local behind
                    // it must stay silent -- a detector that cannot be quiet
                    // would turn every freshly cloned worktree home into a
                    // wall of findings.
                    Fixture fx = Fixture.build("not-shadowed");

                    String tool = "sharedtool";
                    Path parentScript = fx.other.resolve("skills/" + UNIT + "/only-there.py");
                    Files.createDirectories(parentScript.getParent());
                    Files.writeString(parentScript, "print('parent')\n");
                    Path parentEntry = fx.other.resolve("bin/cli").resolve(tool);
                    Files.writeString(parentEntry,
                            "#!/usr/bin/env bash\nexec python3 \"" + parentScript + "\" \"$@\"\n");
                    parentEntry.toFile().setExecutable(true);
                    Files.createSymbolicLink(fx.store.resolve("bin/cli").resolve(tool), parentEntry);

                    assertFalse(Files.exists(fx.store.resolve("skills/" + UNIT + "/only-there.py")),
                            "precondition: this home has NO copy of what the mirror runs");

                    List<String> subjects = HomeRepair.detect(fx.store, fx.pin).findings().stream()
                            .filter(f -> f.kind() == HomeRepair.Kind.PARENT_SHIM_SHADOWS_LOCAL_COPY)
                            .map(HomeRepair.Finding::subject)
                            .distinct().sorted().toList();

                    assertEquals(List.of(), subjects,
                            "THE CLAIM: the legitimate fallback stays legitimate. The goal's "
                                    + "walker counts this shape separately and charges it to "
                                    + "nothing; so must this reader");
                })

                .test("a healthy ROOT-TIER home is reported clean, over a non-zero subject count", () -> {
                    Fixture fx = Fixture.build("healthy");

                    HomeRepair.Report report = HomeRepair.detect(fx.store, fx.pin);

                    // The precondition is asserted before the claim, and it is
                    // the one mechanism B is about: "clean" over zero subjects
                    // is a check that ran out of scope, not a healthy home.
                    assertTrue(report.examined() > 0,
                            "the detector must have LOOKED at something; examined="
                                    + report.examined());
                    assertTrue(report.clean(),
                            "a home this test just built is not damaged; got: "
                                    + report.findings());
                })

                .test("a merely STALE home is not reported as damaged", () -> {
                    // GOAL-no-spurious-holdback's clause, restated as an
                    // assertion rather than as an intention. A guard that turns
                    // a normal state into a finding is a guard somebody
                    // switches off, and this epic has the receipts.
                    Fixture fx = Fixture.build("stale");
                    fx.makeStale();

                    HomeRepair.Report report = HomeRepair.detect(fx.store, fx.pin);
                    assertTrue(report.clean(),
                            "an unacknowledged drift record, an out-of-date unit and a "
                                    + "declared-but-unbuilt artifact are all NORMAL. got: "
                                    + report.findings());
                    assertTrue(DriftGate.pending(new SkillStore(fx.store)).isPresent(),
                            "precondition: the home really is stale — otherwise this asserts "
                                    + "nothing about staleness");
                })

                .test("BLOCKER 1: one disk state, two policies, and the verdict follows the policy", () -> {
                    // The regression for the review's first blocker, stated as
                    // sharply as it can be: the SAME bytes, judged twice.
                    //
                    // A fresh `home clone` was reported damaged. The finding
                    // was `PRUNED_INHERITED_ENTRY` on an artifact the clone had
                    // DEFERRED and that had never been in it. Worse, the
                    // verdict depended on the operator's machine -- eight
                    // artifacts were declared-only and exactly one fired,
                    // because the root store happened to hold that binary.
                    Fixture fx = Fixture.build("blocker1");
                    fx.damagePrunedEntry();

                    HomePolicy.writeLazyArtifacts(new SkillStore(fx.store), false);
                    HomeRepair.Report eager = HomeRepair.detect(fx.store, fx.pin);
                    assertTrue(eager.findings().stream()
                                    .anyMatch(f -> f.kind()
                                            == HomeRepair.Kind.PRUNED_INHERITED_ENTRY),
                            "a home that deferred NOTHING and is missing an entry point it "
                                    + "declares is damaged; got " + eager.findings());

                    // Not one byte of the home changed between these two calls
                    // except the policy line, which is a statement ABOUT the
                    // home rather than a fact in it.
                    HomePolicy.writeLazyArtifacts(new SkillStore(fx.store), true);
                    HomeRepair.Report lazy = HomeRepair.detect(fx.store, fx.pin);
                    assertTrue(lazy.findings().stream()
                                    .noneMatch(f -> f.kind()
                                            == HomeRepair.Kind.PRUNED_INHERITED_ENTRY),
                            "and the identical home that DID defer is not damaged at all — "
                                    + "this is the state every clone this program makes is in "
                                    + "from birth; got " + lazy.findings());

                    // And the answer agrees with the reader that already had
                    // one, which is the guard goal rather than a nicety.
                    assertTrue(HomePolicy.lazyArtifacts(new SkillStore(fx.store)),
                            "precondition: production's own policy reader sees the change "
                                    + "this test made — otherwise both halves above are the "
                                    + "same run twice");
                })

                .test("an EAGER home missing an entry no parent holds is not a repair this command can make", () -> {
                    // The other half of shape 3's conjunction, which had no
                    // assertion until probe V11 came back GREEN and the harness
                    // said so. V11 removes "and the recorded parent still holds
                    // it"; with the policy gate added for blocker 1, the stale
                    // fixture returns before that line is reached, so the
                    // mutation stopped being reachable — mechanism C, caught by
                    // the harness rather than by me reading a transcript.
                    //
                    // What the conjunction is FOR: without it, an entry no
                    // parent holds is reported as repairable and `--fix` builds
                    // a symlink at a path that does not exist. That is the same
                    // failure as blocker 2 on the third arm — a remedy that
                    // makes things worse — and #142 is the rule it breaks.
                    // Whether an eager home SHOULD hear about such an entry at
                    // all is a real question, and the answer is not "from the
                    // command that repairs by re-linking": there is nothing to
                    // re-link at. `home verify` and `build` own it.
                    Fixture fx = Fixture.build("no-parent-copy");
                    HomePolicy.writeLazyArtifacts(new SkillStore(fx.store), false);
                    Files.writeString(fx.store.resolve("artifacts.lock.toml"),
                            Files.readString(fx.store.resolve("artifacts.lock.toml"),
                                    StandardCharsets.UTF_8)
                                    .replace("outputs = [\"bin/cli/" + TOOL + "\"]",
                                            "outputs = [\"bin/cli/" + TOOL
                                                    + "\", \"bin/cli/orphan\"]"),
                            StandardCharsets.UTF_8);
                    assertFalse(Files.exists(fx.other.resolve("bin/cli/orphan"),
                                    LinkOption.NOFOLLOW_LINKS),
                            "precondition: no recorded parent holds it — otherwise this is "
                                    + "the reportable case and asserts the opposite");

                    HomeRepair.Report report = HomeRepair.detect(fx.store, fx.pin);
                    assertTrue(report.findings().stream()
                                    .noneMatch(f -> f.subject().equals("bin/cli/orphan")),
                            "not reported: there is nothing to re-link at, so naming this a "
                                    + "PRUNED_INHERITED_ENTRY would name a repair that does "
                                    + "not exist; got " + report.findings());
                    HomeRepair.repair(fx.store, fx.pin);
                    assertFalse(Files.exists(fx.store.resolve("bin/cli/orphan"),
                                    LinkOption.NOFOLLOW_LINKS),
                            "and --fix created no link at a path nothing stands at");
                })

                .test("every damage shape is reported, one line each, naming the repair", () -> {
                    // GENERIC OVER Kind.values() on purpose: a new kind that
                    // nothing plants fails here rather than shipping undetected,
                    // which is how #289's own kind was caught the first time
                    // this ran.
                    Fixture fx = Fixture.build("every-kind");
                    fx.damageEveryKind();

                    HomeRepair.Report report = HomeRepair.detect(fx.store, fx.pin);

                    assertFalse(report.clean(), "a home damaged every way is not clean");
                    for (HomeRepair.Kind kind : HomeRepair.Kind.values()) {
                        assertTrue(report.findings().stream().anyMatch(f -> f.kind() == kind),
                                kind + " was planted and not reported; got: " + report.findings());
                    }
                    for (HomeRepair.Finding finding : report.findings()) {
                        assertTrue(finding.remedy() != null && !finding.remedy().isBlank(),
                                "every finding names its repair; " + finding + " named none");
                    }
                })

                .test("BLOCKER 2: a rewrite replaces a PATH, not a substring, and keeps the shim running", () -> {
                    // The review measured, on stock output: a wrapper naming a
                    // foreign home twice -- once as a base, once as a longer
                    // path under it -- had BOTH rewritten by one repair, because
                    // `String.replace` on a path string is a prefix replace. The
                    // second occurrence was a path this same run had already
                    // REPORTED AS UNREPAIRABLE. A shim that ran (exit 0) became
                    // one that resolved nowhere (exit 126), on a line no finding
                    // named, and detection afterwards called the home clean.
                    Fixture fx = Fixture.build("blocker2");
                    // A real file under the OTHER home, at a path this home has
                    // no counterpart for — so the longer occurrence resolves
                    // (it is a live foreign path, not a dangling one) and is
                    // NOT repairable, which is the reviewer's arrangement.
                    Path deep = fx.other.resolve("skills").resolve(UNIT).resolve("bin/run");
                    Files.createDirectories(deep.getParent());
                    Files.writeString(deep, "#!/bin/sh\necho other\n");
                    deep.toFile().setExecutable(true);

                    Path wrapper = fx.store.resolve("bin/cli").resolve("prefix");
                    Files.writeString(wrapper, "#!/usr/bin/env bash\n# base: "
                            + fx.other.resolve("skills") + "\nexec \"" + deep + "\"\n");
                    wrapper.toFile().setExecutable(true);

                    // PRECONDITIONS, asserted, because this fixture is only the
                    // right one if the two paths really are a base and a longer
                    // path under it, and only the base is repairable here.
                    HomeRepair.Report before = HomeRepair.detect(fx.store, fx.pin);
                    List<HomeRepair.Finding> onIt = before.findings().stream()
                            .filter(f -> f.subject().equals("bin/cli/prefix")).toList();
                    assertEquals(2, onIt.size(),
                            "two foreign paths in one shim; got " + onIt);
                    assertEquals(1, (int) onIt.stream().filter(HomeRepair.Finding::repairable).count(),
                            "exactly one of them is repairable — if both were, the defect "
                                    + "could not be expressed here; got " + onIt);
                    String longer = deep.toString();

                    HomeRepair.repair(fx.store, fx.pin);

                    String after = Files.readString(wrapper);
                    assertContains(after, longer,
                            "the UNREPAIRABLE path is untouched — a repair may not rewrite a "
                                    + "line no finding named, and least of all one it had just "
                                    + "declared it could not fix");
                    assertContains(after, fx.store.resolve("skills").toString(),
                            "while the repairable base path WAS rewritten, so this is not "
                                    + "passing by doing nothing");
                    assertFalse(HomeRepair.detect(fx.store, fx.pin).clean(),
                            "and detection afterwards is still RED about the path that is "
                                    + "still wrong — a green verdict over surviving damage is "
                                    + "the failure that made this a blocker");
                })

                .test("BLOCKER 2 postcondition: a rewrite that would break the shim is REFUSED", () -> {
                    // The class-level guard behind the specific fix. Whatever
                    // the replacement rule gets wrong, a rewrite may not leave
                    // the file naming a path under THIS home that is not there:
                    // #142's remedy-that-does-not-work, produced by the remedy.
                    //
                    // Driven through the seam directly, because producing the
                    // state through the fixed `replaceWholePath` is exactly what
                    // is now impossible -- so the branch would otherwise have
                    // no coverage at all, which is M3's complaint one method over.
                    String text = "#!/bin/sh\nexec \"" + "/nowhere/x" + "\"\n";
                    assertEquals(text, HomeRepair.replaceWholePath(text, "/nowhere/xy", "/other"),
                            "a longer path does not match a shorter occurrence");
                    assertEquals("#!/bin/sh\nexec \"/other\"\n",
                            HomeRepair.replaceWholePath(text, "/nowhere/x", "/other"),
                            "and the whole-path occurrence does");
                    assertEquals("a /p/q/r b", HomeRepair.replaceWholePath("a /p/q/r b", "/p/q", "/Z"),
                            "a base path inside a longer one is NOT replaced — the blocker");
                    assertEquals("a /Z b", HomeRepair.replaceWholePath("a /p/q b", "/p/q", "/Z"),
                            "the same base standing alone IS");
                    assertEquals("x/p/q y", HomeRepair.replaceWholePath("x/p/q y", "/p/q", "/Z"),
                            "and an occurrence that is the TAIL of another path is not one");
                })

                .test("DETECTION REPAIRS NOTHING — run it twice and the damage is still there", () -> {
                    // DEF-067, as a property of the command rather than a
                    // promise in its javadoc. An observer that repairs is no
                    // longer an observer, and this ticket ships the repairer,
                    // so the separation is asserted where it can fail.
                    Fixture fx = Fixture.build("observer");
                    fx.damageAll();
                    List<String> before = fx.snapshot();

                    HomeRepair.Report first = HomeRepair.detect(fx.store, fx.pin);
                    HomeRepair.Report second = HomeRepair.detect(fx.store, fx.pin);

                    // THE CLAIM IS FIRST, and the order is the correction.
                    //
                    // Review of PR #244, M4: my vacuity table credited V5 --
                    // the probe that PLANTS DEF-067's hazard by making `detect`
                    // call `repair` -- with reddening this assertion "on its
                    // claim". It did not. It reddened `precondition: the first
                    // run sees the damage`, because a detector that repairs
                    // leaves nothing to see. Mechanism A, undeclared, on the
                    // keystone assertion of the hazard this ticket exists to
                    // avoid.
                    //
                    // The fix is not a better label. "The first run sees the
                    // damage" was never a precondition: it IS the claim, stated
                    // in the weaker of its two forms. The strong form is that
                    // the bytes did not move, and it is now evaluated first, so
                    // the probe reddens what it is credited with.
                    assertEquals(String.join("\n", before), String.join("\n", fx.snapshot()),
                            "two bare detection runs changed NOTHING — asserted over every "
                                    + "file's content and every link's target, on both axes");
                    assertFalse(first.clean(),
                            "and the damage is still being reported: a detector that had "
                                    + "quietly repaired it would be clean here, which is "
                                    + "DEF-067's third consequence — it can green a real defect");
                    assertEquals(first.findings().size(), second.findings().size(),
                            "and the verdict is the same twice, so nothing moved between them");
                })

                .test("REPAIR makes detection clean, and it is DETECTION that says so", () -> {
                    Fixture fx = Fixture.build("repair");
                    fx.damageAll();
                    assertFalse(HomeRepair.detect(fx.store, fx.pin).clean(), "precondition: damaged");

                    HomeRepair.Outcome outcome = HomeRepair.repair(fx.store, fx.pin);

                    assertTrue(outcome.failed().isEmpty(),
                            "no repair was refused; " + outcome.failed());
                    assertTrue(outcome.after().clean(),
                            "detection re-run AFTER the repair must be clean — the repairer's "
                                    + "own opinion of how it went is not evidence (#142). got: "
                                    + outcome.after().findings());
                    assertEquals(outcome.before().findings().size(), outcome.repaired().size(),
                            "and every finding it reported is one it carried out");

                    // The repairs are the right ones, not merely absences.
                    assertEquals(fx.store.resolve("skills").resolve(UNIT),
                            Files.readSymbolicLink(fx.claudeSkills().resolve(UNIT)),
                            "the projection points at THIS home's store now");
                    assertContains(Files.readString(fx.store.resolve("bin/cli/wrapper")),
                            fx.store.toString(),
                            "the wrapper runs this home's tree");
                    assertTrue(Files.isSymbolicLink(fx.store.resolve("bin/cli").resolve(TOOL)),
                            "the pruned inherited entry is a link at the parent again");
                })

                .test("REPAIR IS IDEMPOTENT — the second run changes no bytes", () -> {
                    Fixture fx = Fixture.build("idempotent");
                    fx.damageAll();
                    HomeRepair.repair(fx.store, fx.pin);
                    List<String> afterFirst = fx.snapshot();

                    HomeRepair.Outcome again = HomeRepair.repair(fx.store, fx.pin);

                    assertTrue(again.noop(),
                            "the second repair had nothing to do; repaired=" + again.repaired()
                                    + " failed=" + again.failed());
                    assertEquals(String.join("\n", afterFirst), String.join("\n", fx.snapshot()),
                            "and it wrote nothing — asserted over bytes and link targets, not "
                                    + "over the command's own report of itself");
                })

                .test("M3: a projection this home cannot back is reported UNREPAIRABLE, not relinked", () -> {
                    // The review found `boolean held = Files.exists(...)` could
                    // be forced TRUE with the whole 13-case suite still green.
                    // That branch decides whether a MISANCHORED_AGENT_LINK is
                    // repairable and which remedy prints; forced true, `--fix`
                    // relinks at a path that does not exist — turning a link
                    // into the wrong home into a link into nothing, which is
                    // the same class as blocker 2 on the other axis.
                    Fixture fx = Fixture.build("m3-held");
                    Path orphan = fx.claudeSkills().resolve("not-here");
                    Path theirs = fx.other.resolve("skills").resolve("not-here");
                    Files.createDirectories(theirs);
                    Files.writeString(theirs.resolve("SKILL.md"), "---\nname: not-here\n---\n");
                    Files.createSymbolicLink(orphan, theirs);
                    assertFalse(Files.exists(fx.store.resolve("skills").resolve("not-here")),
                            "precondition: this home really does NOT hold the unit");

                    HomeRepair.Report report = HomeRepair.detect(fx.store, fx.pin);
                    HomeRepair.Finding f = report.findings().stream()
                            .filter(x -> x.subject().endsWith("/not-here")).findFirst()
                            .orElseThrow(() -> new AssertionError(
                                    "the mis-anchored link was not reported at all: "
                                            + report.findings()));
                    assertFalse(f.repairable(),
                            "a projection this home cannot back is NOT repairable");
                    assertContains(f.remedy(), "this home does not hold",
                            "and the remedy says which of the two things to do");

                    HomeRepair.repair(fx.store, fx.pin);
                    assertEquals(theirs, Files.readSymbolicLink(orphan),
                            "and --fix left it exactly as it was rather than relinking it at "
                                    + "a path that does not exist");
                })

                .test("M3: a broken CLI pin with no locatable build is reported, with the runnable remedy", () -> {
                    // The other branch the review found uncovered:
                    // `DANGLING_CLI_PIN`'s `live == null` arm. V4 discarded the
                    // finding list, so nothing ever read what this arm prints —
                    // and what it prints is the ONLY thing an operator gets when
                    // the CLI cannot locate itself, which is exactly the
                    // situation DEF-012 leaves a machine in.
                    Fixture fx = Fixture.build("m3-pin");
                    fx.damageCliPin();

                    HomeRepair.Report unlocatable = HomeRepair.detect(fx.store, null);
                    HomeRepair.Finding f = unlocatable.findings().stream()
                            .filter(x -> x.kind() == HomeRepair.Kind.DANGLING_CLI_PIN)
                            .findFirst().orElseThrow(() -> new AssertionError(
                                    "the dead pin was not reported: " + unlocatable.findings()));
                    assertFalse(f.repairable(),
                            "with no build to re-pin at, this is not repairable — inventing "
                                    + "one is the remedy-that-does-not-work class");
                    assertContains(f.remedy(), "home shims --home",
                            "so the remedy is the command a person can actually type, and it "
                                    + "names the home");

                    // The SAME home, the SAME bytes, with a build located: the
                    // arm flips. Without this the assertion above would also
                    // pass on a build where nothing is ever repairable.
                    HomeRepair.Report locatable = HomeRepair.detect(fx.store, fx.pin);
                    HomeRepair.Finding g = locatable.findings().stream()
                            .filter(x -> x.kind() == HomeRepair.Kind.DANGLING_CLI_PIN)
                            .findFirst().orElseThrow();
                    assertTrue(g.repairable(), "with a build located, it is repairable");
                    assertEquals(fx.pin, g.target(), "at the build that was located");
                })

                .test("the agent axis comes from the HOME and not from the ENVIRONMENT", () -> {
                    // HIS-14's defect, aimed at the command that rewrites agent
                    // links for a living. `SKILL_MANAGER_HOME` binds the store
                    // axis only; a repair that reads CLAUDE_CONFIG_DIR for the
                    // other half repairs one home and rewrites another's links,
                    // which is how 24 and then 38 of the operator's global
                    // skill links came to be repointed.
                    Fixture fx = Fixture.build("axes");
                    fx.damageAll();
                    Fixture decoy = Fixture.build("axes-decoy");
                    decoy.damageAgentLink();
                    List<String> decoyBefore = decoy.snapshot();

                    var saved = AgentHomes.snapshotOverrides();
                    try {
                        AgentHomes.setOverride(AgentHomes.CLAUDE_CONFIG_DIR, decoy.claudeDir());
                        AgentHomes.setOverride(AgentHomes.CODEX_HOME, decoy.root.resolve(".codex"));
                        AgentHomes.setOverride(AgentHomes.GEMINI_HOME, decoy.root.resolve(".gemini"));

                        HomeRepair.Report report = HomeRepair.detect(fx.store, fx.pin);
                        assertTrue(report.findings().stream()
                                        .anyMatch(f -> f.kind()
                                                == HomeRepair.Kind.MISANCHORED_AGENT_LINK
                                                && f.subject().startsWith(".claude/")),
                                "detection found the damage in the home it was GIVEN, with the "
                                        + "environment naming a different one; got: "
                                        + report.findings());
                        HomeRepair.repair(fx.store, fx.pin);
                    } finally {
                        AgentHomes.restoreOverrides(saved);
                    }

                    assertEquals(String.join("\n", decoyBefore), String.join("\n", decoy.snapshot()),
                            "and the home the ENVIRONMENT named is byte-for-byte untouched — "
                                    + "including its own planted damage, which is still there");
                    assertFalse(HomeRepair.detect(decoy.store, decoy.pin).clean(),
                            "the decoy is still damaged: a repair that 'helpfully' fixed it "
                                    + "would have escaped the home it was given");
                })

                .test("a repair cannot write outside the two axes of the home it was given", () -> {
                    // The confinement is the home's STORE plus its own three
                    // agent directories, and nothing else. Not `forHome`, whose
                    // single root is the store — an agent-link repair writes
                    // outside that by design — and not the enclosing home root,
                    // which for a ROOT-tier home is the whole of $HOME.
                    Fixture fx = Fixture.build("confinement");
                    WriteConfinement.Scope scope = HomeRepair.ownedAxesOf(fx.store);

                    assertEquals(4, scope.roots().size(),
                            "one store and three agent directories; got " + scope.roots());
                    assertFalse(scope.roots().contains(fx.root),
                            "the enclosing root is NOT a permitted root — for a root-tier home "
                                    + "that is $HOME; got " + scope.roots());

                    WriteConfinement.Scope previous = WriteConfinement.declare(scope);
                    try {
                        boolean refused = false;
                        try {
                            WriteConfinement.checkWrite(
                                    fx.root.resolve("sibling/.claude/skills/x"), "probe");
                        } catch (WriteOutsideHomeException expected) {
                            refused = true;
                        }
                        assertTrue(refused,
                                "a write into a SIBLING home's agent directory is refused");
                        // And the two things it must permit, or the repair
                        // cannot do its job at all.
                        WriteConfinement.checkWrite(fx.store.resolve("bin/cli/x"), "probe");
                        WriteConfinement.checkWrite(fx.claudeSkills().resolve("x"), "probe");
                    } finally {
                        WriteConfinement.restore(previous);
                    }
                })

                .test("`home repair` reports and exits 1; `--fix` repairs and exits 0", () -> {
                    // The CLI surface, because that is what an operator and a
                    // graph node run. Three separate invocations, in the order
                    // the graph runs them.
                    Fixture fx = Fixture.build("cli");
                    fx.damageAll();

                    Result detect = cli(fx.pin, "--home", fx.store.toString());
                    assertEquals(1, detect.rc, "detection on a damaged home exits 1:\n" + detect.err);
                    assertContains(detect.err, "MISANCHORED_AGENT_LINK",
                            "and names the damage by kind");
                    assertContains(detect.err, "repair:",
                            "and names the repair for each finding");
                    assertContains(detect.err, "--fix",
                            "and names the command that carries them out");

                    Result fix = cli(fx.pin, "--home", fx.store.toString(), "--fix");
                    assertEquals(0, fix.rc, "the repair exits 0:\n" + fix.err + fix.out);

                    Result after = cli(fx.pin, "--home", fx.store.toString());
                    assertEquals(0, after.rc,
                            "and detection, run on its own afterwards, agrees:\n" + after.err);
                    assertContains(after.out, "examined",
                            "the clean verdict states how much it looked at");
                })

                .test("a home damaged AFTER a repair goes red again — the verdict is not sticky", () -> {
                    // Cheap, and it closes the reading where `--fix` writes a
                    // "repaired" marker that later runs read instead of the
                    // filesystem. Two of this epic's eleven vacuity instances
                    // are a check that stopped examining its subject.
                    Fixture fx = Fixture.build("re-damage");
                    fx.damageAll();
                    HomeRepair.repair(fx.store, fx.pin);
                    assertTrue(HomeRepair.detect(fx.store, fx.pin).clean(), "precondition: repaired");

                    fx.damageAgentLink();
                    assertFalse(HomeRepair.detect(fx.store, fx.pin).clean(),
                            "fresh damage after a repair is still damage");
                })

                .test("HIS-9's measurement target: a home whose bin/cli IS a link is NAMED", () -> {
                    // HIS-9 disclosed: "a home whose bin/cli is a symlink at
                    // another home's can no longer be synced at all until a
                    // person repairs it by hand -- and nothing this ticket
                    // ships will repair it for them." That sentence is this
                    // ticket's declared measurement target, so it is measured
                    // rather than asserted about.
                    //
                    // The answer after HIS-13 is: STILL TRUE about the repair,
                    // NO LONGER TRUE about the detection. It is reported, as
                    // ONE finding about the directory, with the two exits a
                    // person can take -- and, critically, the walk does not
                    // descend through it and start describing the other home's
                    // files as this home's damage.
                    Fixture fx = Fixture.build("his9-shape");
                    Path cli = fx.store.resolve("bin/cli");
                    List<String> otherBefore = fx.snapshotOf(fx.other);
                    HomeIntegrityDelete.deleteRecursive(cli);
                    Files.createSymbolicLink(cli, fx.other.resolve("bin/cli"));

                    HomeRepair.Report report = HomeRepair.detect(fx.store, fx.pin);
                    List<HomeRepair.Finding> aboutTheDir = report.findings().stream()
                            .filter(f -> f.subject().equals("bin/cli")).toList();
                    assertEquals(1, aboutTheDir.size(),
                            "ONE finding about the directory, not one per entry seen through "
                                    + "it; got " + report.findings());
                    assertFalse(aboutTheDir.get(0).repairable(),
                            "and it is NOT offered as repairable — the only mechanical repair "
                                    + "discards every entry point the home can reach, which is "
                                    + "the destructive recovery this goal forbids");
                    assertContains(aboutTheDir.get(0).remedy(), "no command repairs this",
                            "the finding says so in the line a person reads");
                    assertTrue(report.findings().stream()
                                    .noneMatch(f -> f.subject().startsWith("bin/cli/")),
                            "and nothing under it was reported: the walk must not descend "
                                    + "through the link and describe the OTHER home's files as "
                                    + "this home's damage; got " + report.findings());

                    HomeRepair.repair(fx.store, fx.pin);
                    assertEquals(String.join("\n", otherBefore),
                            String.join("\n", fx.snapshotOf(fx.other)),
                            "and a repair run against this home changed nothing in the home "
                                    + "its bin/cli points into");
                })

                .test("a FORGED descent record buys no repair — the chain it names must re-derive", () -> {
                    // The #228 review's finding, on the REPAIR side, which is
                    // where it bites hardest: `parentStores` is a SNAPSHOT kept
                    // "for reporting and repair", and a repair that trusted it
                    // would take one file written into the home being judged as
                    // an instruction to link that home at an arbitrary store.
                    // That is worse than the read-side hole the review closed —
                    // there it switched a gate off, here it would create paths.
                    Fixture fx = Fixture.build("forged");
                    fx.damagePrunedEntry();
                    assertFalse(HomeRepair.detect(fx.store, fx.pin).clean(),
                            "precondition: with the REAL claim in place the pruned entry is "
                                    + "reported, so this fixture can express the finding");

                    // Revoke the real claim — what ChildHomeRegistry.delete
                    // does on teardown — and leave the record naming the store.
                    Files.delete(fx.other.resolve("child-homes/root/child-home.json"));
                    Files.delete(fx.other.resolve("child-homes/root"));

                    HomeRepair.Report report = HomeRepair.detect(fx.store, fx.pin);
                    assertTrue(report.findings().stream()
                                    .noneMatch(f -> f.kind()
                                            == HomeRepair.Kind.PRUNED_INHERITED_ENTRY),
                            "a record that names a store nothing claims re-derives to nothing, "
                                    + "so it sanctions nothing and repairs nothing; got: "
                                    + report.findings());
                    HomeRepair.Outcome outcome = HomeRepair.repair(fx.store, fx.pin);
                    assertFalse(Files.exists(fx.store.resolve("bin/cli").resolve(TOOL),
                                    LinkOption.NOFOLLOW_LINKS),
                            "and no link into that store was created; repaired="
                                    + outcome.repaired());
                })

                .test("detection uses HIS-10's reader: a SANCTIONED inherited shim is not damage", () -> {
                    // GOAL-one-home-one-answer, as a guard. If detection asked
                    // its own question about "is that another home", it would
                    // report every child home's legitimately inherited
                    // toolchain as damaged — the FOREIGN_HOME false positive
                    // HIS-10 spent a ticket removing, reintroduced by a fourth
                    // reader.
                    //
                    // THE BRANCH THIS EXERCISES, stated because mechanism D is
                    // this ticket's likeliest trap: `bin/cli/mirror` is a
                    // REGULAR-FILE wrapper whose TEXT names the parent's
                    // identically-named entry, so the subject travels the
                    // FOREIGN_PATH_IN_SHIM arm and lands on
                    // `sanctionedParentShim`'s three conditions — the same
                    // three the symlink form is judged by, reached from the
                    // other side. A symlink decoy here would exercise a branch
                    // `verifyRoots` decides earlier and this detector does not
                    // decide at all, which is exactly HIS-17's blocker.
                    //
                    // The subject is a CLONE, i.e. a GRANDCHILD, because the
                    // one-level `isChildOf` relation can answer for the middle
                    // tier without the record. Only the descent record can
                    // sanction a grandchild, so deleting it is a control with
                    // nothing else holding the verdict up.
                    Fixture fx = Fixture.build("sanctioned");
                    assertTrue(HomeRepair.detect(fx.store, fx.pin).clean(),
                            "precondition: the middle tier's mirror wrapper is sanctioned by "
                                    + "the parent's own claim");
                    Path child = fx.cloneToChild("child");
                    assertContains(Files.readString(child.resolve("bin/cli/mirror")),
                            fx.other.toString(),
                            "precondition: the clone still carries the wrapper naming the "
                                    + "grandparent store — if re-anchoring rewrote it there is "
                                    + "no foreign path here and this test asserts nothing");

                    HomeRepair.Report sanctioned = HomeRepair.detect(child, fx.pin);
                    assertTrue(sanctioned.clean(),
                            "a clone's inherited wrapper is sanctioned by the descent record "
                                    + "the clone wrote; got " + sanctioned.findings());

                    Files.delete(child.resolve(HomeProvenance.FILENAME));
                    HomeRepair.Report unsanctioned = HomeRepair.detect(child, fx.pin);
                    assertTrue(unsanctioned.findings().stream()
                                    .anyMatch(f -> f.kind() == HomeRepair.Kind.FOREIGN_PATH_IN_SHIM
                                            && f.subject().equals("bin/cli/mirror")),
                            "and with the record gone the SAME bytes are damage — so the "
                                    + "verdict above came from production's sanction and not "
                                    + "from this detector being blind. got: "
                                    + unsanctioned.findings());
                })

                .test("DEF-104: verify and repair return the SAME verdict on a wrapper shim", () -> {
                    // HIS-21 / DEF-104. THE HEADLINE, AND THE CORRECTION TO IT.
                    //
                    // Measured on the operator's root home, same minute:
                    // `home verify` exit 0, "no path in it reaches any other
                    // Skill Manager home"; `home repair` five
                    // FOREIGN_PATH_IN_SHIM findings, and repair was right.
                    //
                    // #253's proposed mechanism was that `verifyRoots` frames
                    // the question against a home's SOURCE, so a home with no
                    // parent never runs the branch. THAT IS NOT THE MECHANISM,
                    // and the third assertion below is what says so: the same
                    // blindness held WITH a source. The foreign-home question
                    // was asked only of SYMLINKS; a regular-file wrapper was
                    // invisible at every tier.
                    //
                    // THE BRANCH THIS EXERCISES: `bin/cli/wrapper` is a REGULAR
                    // FILE whose text execs a path in the other home, so it
                    // travels HomeCloner.verifyRoots' regular-file arm and the
                    // new foreignPathsInShimContent call on it. A symlink decoy
                    // would exercise the arm that was ALREADY correct, which is
                    // HIS-17's blocker (vacuity row 11) restated one ticket on.
                    //
                    // THE MUTATION THAT REDDENS IT: delete the
                    // foreignPathsInShimContent loop from verifyRoots. Assertion
                    // 2 then fails with `isolated()=true` while assertion 1 --
                    // the repair half, untouched -- still passes, which is the
                    // disagreement itself.
                    Fixture fx = Fixture.build("def104");

                    HomeCloner.Verification healthy = HomeCloner.verify(fx.store, false);
                    assertTrue(healthy.isolated(),
                            "precondition: an undamaged home verifies clean, INCLUDING its "
                                    + "sanctioned bin/cli/mirror wrapper. Without this the "
                                    + "widening would redden every child home and the finding "
                                    + "below would be noise; got " + healthy.isolationFailures());

                    fx.damageShimText();

                    List<String> repairSubjects = HomeRepair.detect(fx.store, fx.pin).findings()
                            .stream()
                            .filter(f -> f.kind() == HomeRepair.Kind.FOREIGN_PATH_IN_SHIM)
                            .map(HomeRepair.Finding::subject)
                            .distinct()
                            .sorted()
                            .toList();
                    assertEquals(List.of("bin/cli/wrapper"), repairSubjects,
                            "precondition: repair still names exactly the damaged wrapper");

                    HomeCloner.Verification damaged = HomeCloner.verify(fx.store, false);
                    List<String> verifySubjects = damaged.isolationFailures().stream()
                            .filter(l -> HomeCloner.Leak.FOREIGN_PATH_IN_SHIM.equals(l.kind()))
                            .map(HomeCloner.Leak::path)
                            .distinct()
                            .sorted()
                            .toList();
                    assertEquals(repairSubjects, verifySubjects,
                            "THE CLAIM: the two readers name the same file. verify said "
                                    + damaged.isolationFailures() + "; repair said "
                                    + repairSubjects);
                    assertFalse(damaged.isolated(),
                            "and verify REFUSES the home rather than merely mentioning it — "
                                    + "a FOREIGN_PATH_IN_SHIM leak must be an isolation "
                                    + "failure at every strictness, because the shim RUNS "
                                    + "that path");

                    // THE MECHANISM CORRECTION, as an assertion rather than a
                    // sentence. #253 said the branch does not run for a
                    // parentless home. If that were the cause, handing verify a
                    // source would make it see the wrapper before this fix and
                    // this line would prove nothing. Measured before the fix:
                    // `home verify --home A --against C` exited 0 on exactly
                    // this shape. The blindness was about the SURFACE.
                    HomeCloner.Verification withSource =
                            HomeCloner.verify(fx.other, fx.store, false);
                    assertTrue(withSource.isolationFailures().stream()
                                    .anyMatch(l -> HomeCloner.Leak.FOREIGN_PATH_IN_SHIM
                                                    .equals(l.kind())
                                            && l.path().equals("bin/cli/wrapper")),
                            "the same wrapper is found with a source supplied — so the fix is "
                                    + "not conditioned on the home being parentless, and "
                                    + "neither was the defect; got "
                                    + withSource.isolationFailures());
                })

                .test("DEF-104: the SANCTION survives the widening — a clone's inherited wrapper is not a leak", () -> {
                    // The non-detection half, and the one that decides whether
                    // this widening is shippable. `home verify` runs on every
                    // home HomeFixpointLaw touches across 24 graphs; if a
                    // legitimately inherited wrapper became a leak, the epic
                    // would trade one wrong answer for a louder one.
                    //
                    // A GRANDCHILD, deliberately: only the descent record can
                    // sanction it, so deleting that record is a control with
                    // nothing else holding the verdict up — the same argument
                    // the detection test above it makes, asked of the other
                    // reader.
                    Fixture fx = Fixture.build("def104-sanction");
                    Path child = fx.cloneToChild("child");
                    assertContains(Files.readString(child.resolve("bin/cli/mirror")),
                            fx.other.toString(),
                            "precondition: the clone still carries the wrapper naming the "
                                    + "grandparent store — if re-anchoring rewrote it there is "
                                    + "no foreign path here and this test asserts nothing");

                    HomeCloner.Verification sanctioned = HomeCloner.verify(child, false);
                    assertTrue(sanctioned.isolated(),
                            "a clone's inherited wrapper is sanctioned by the descent record "
                                    + "the clone wrote; got " + sanctioned.isolationFailures());

                    Files.delete(child.resolve(HomeProvenance.FILENAME));
                    HomeCloner.Verification unsanctioned = HomeCloner.verify(child, false);
                    assertTrue(unsanctioned.isolationFailures().stream()
                                    .anyMatch(l -> HomeCloner.Leak.FOREIGN_PATH_IN_SHIM
                                                    .equals(l.kind())
                                            && l.path().equals("bin/cli/mirror")),
                            "and with the record gone the SAME bytes are a leak — so the "
                                    + "verdict above came from production's sanction and not "
                                    + "from verify being blind again; got "
                                    + unsanctioned.isolationFailures());
                })

                .test("MAJOR-1: verify and repair agree when bin/cli IS A SYMLINK", () -> {
                    // Review of PR #256, MAJOR-1. HIS-21's own claim was "one
                    // extraction rule, one verdict, ONE SCOPE". Two of three
                    // were true. The scope was declared twice --
                    // HomeRepair `List.of("bin/cli","bin/mcp")`, HomeCloner
                    // `List.of("bin/cli/","bin/mcp/")` -- and the two ENUMERATED
                    // differently: a directory stream FOLLOWS a symlinked
                    // bin/cli, walkFileTree does not.
                    //
                    // THE BRANCH THIS EXERCISES: HomeCloner.scanShimDirs'
                    // collectShimFiles, entered on a shim directory that is
                    // itself a link. Not the same branch as the DEF-104 case
                    // above, which reaches the identical detector through a
                    // REAL directory -- the two differ by one link, which is
                    // exactly how far apart the readers had drifted.
                    //
                    // MEASURED ON THE FIXED TREE before this change:
                    //   home verify --home X -> exit 0, "no path in it reaches
                    //                          any other Skill Manager home"
                    //   home repair --home X -> exit 1, FOREIGN_PATH_IN_SHIM
                    // which is DEF-104's shape one link down.
                    //
                    // MUTATION THAT REDDENS IT: point verifyRoots back at a
                    // per-file check inside the walk. The repair half stays
                    // green, which is the disagreement.
                    Fixture fx = Fixture.build("major1");
                    Path realDir = Files.createTempDirectory("major1-outside-");
                    Path moved = realDir.resolve("wrapper");
                    Files.writeString(moved, "#!/usr/bin/env bash\nexec \""
                            + fx.other.resolve("venvs/v/bin/wrapper") + "\" \"$@\"\n",
                            StandardCharsets.UTF_8);
                    moved.toFile().setExecutable(true);
                    // bin/cli becomes a LINK at a directory outside the home
                    // that is NOT itself a home -- so neither the container
                    // rule nor verify's symlink arm fires, and only the
                    // enumeration decides.
                    Path binCli = fx.store.resolve("bin/cli");
                    HomeIntegrityDelete.deleteRecursive(binCli);
                    Files.createSymbolicLink(binCli, realDir);
                    assertFalse(LaunchEnv.looksLikeStoreRoot(realDir),
                            "precondition: the link target is NOT itself a home — otherwise "
                                    + "the container rule fires and this asserts about a "
                                    + "branch that was already correct");

                    List<String> repairSubjects = HomeRepair.detect(fx.store, fx.pin).findings()
                            .stream()
                            .filter(f -> f.kind() == HomeRepair.Kind.FOREIGN_PATH_IN_SHIM)
                            .map(HomeRepair.Finding::subject)
                            .distinct().sorted().toList();
                    assertEquals(List.of("bin/cli/wrapper"), repairSubjects,
                            "precondition: repair follows the linked directory and names the "
                                    + "wrapper inside it");

                    HomeCloner.Verification v = HomeCloner.verify(fx.store, false);
                    List<String> verifySubjects = v.isolationFailures().stream()
                            .filter(l -> HomeCloner.Leak.FOREIGN_PATH_IN_SHIM.equals(l.kind()))
                            .map(HomeCloner.Leak::path)
                            .distinct().sorted().toList();
                    assertEquals(repairSubjects, verifySubjects,
                            "THE CLAIM: one scope, so the two readers name the same file "
                                    + "through a symlinked shim directory too. verify said "
                                    + v.isolationFailures() + "; repair said " + repairSubjects);
                })

                .test("DEF-115: verify and repair return the SAME verdict on a SYMLINKED shim "
                        + "into another home", () -> {
                    // HIS-6, the terminal evaluation. THIS ASSERTION IS RED ON
                    // THE TREE THAT SHIPS IT, deliberately, and that is the
                    // whole point of it. HIS-6 is forbidden to repair the
                    // product to make its own goal pass, and it is equally
                    // forbidden to leave a found bug without a check. So the
                    // check lands red, the goal is reported as measured, and
                    // whoever fixes DEF-115 turns this green.
                    //
                    // WHAT WAS MEASURED, at epic tip 17648705e67b, on two
                    // throwaway homes, through the REAL CLI:
                    //
                    //   A holds a symlink bin/cli/linktool -> a file in B
                    //     home verify --home A  -> exit 1  FOREIGN_HOME bin/cli/linktool
                    //     home repair --home A  -> exit 0  "nothing ... is damaged
                    //                                       in a way this command
                    //                                       knows about (0 entries examined)"
                    //
                    //   CONTROL, A holds a WRAPPER instead (regular file, same target)
                    //     home verify --home A  -> exit 1  FOREIGN_PATH_IN_SHIM bin/cli/wrappertool
                    //     home repair --home A  -> exit 1  FOREIGN_PATH_IN_SHIM bin/cli/wrappertool
                    //
                    // The control is what makes the arm readable: the two
                    // readers CAN agree, so the disagreement is about the
                    // surface and not about the fixture. Run with BOTH shapes
                    // in one home, verify names two subjects and repair names
                    // one -- the subject SETS differ, which is the sharpest
                    // form of GOAL-one-home-one-answer's failure.
                    //
                    // WHY IT IS STRUCTURAL, not incidental. HomeRepair's
                    // foreign-shim detector consumes HomeCloner.scanShimDirs,
                    // which extracts absolute paths out of a shim's TEXT. A
                    // symlink has no text. So `home repair` cannot report a
                    // symlinked shim into another home at all -- while
                    // `home repair --help` advertises, unqualified, "shims
                    // pointing into another home", and `home verify`'s own
                    // header tells the reader "`home repair` reads the same two
                    // directories". True of the directories, false of the
                    // conclusion.
                    //
                    // THE RELATIONSHIP TO DEF-104, which is the reason this is
                    // worth an assertion rather than a note. DEF-104 was the
                    // MIRROR IMAGE: verify saw symlinks and was blind to
                    // content, repair saw content. HIS-21 taught verify to read
                    // content and declared "one extraction rule, one verdict"
                    // for both readers. The half that was never done is repair
                    // learning to read LINKS. The epic closed the asymmetry in
                    // one direction and left it open in the other, and no graph
                    // and no unit case compared the two readers on a symlink,
                    // because every existing comparison plants a wrapper.
                    //
                    // THE BRANCH THIS EXERCISES: HomeCloner.verifyRoots' SYMLINK
                    // arm versus HomeRepair's text-extraction arm. Distinct from
                    // the DEF-104 case (both readers, regular-file arm) and from
                    // MAJOR-1 (both readers, regular-file arm reached through a
                    // linked DIRECTORY). Three cases, three branch pairs.
                    Fixture fx = Fixture.build("def115");

                    // A THIRD home, deliberately NOT fx.other: fx.other is a
                    // recorded parent store, so a path into it is SANCTIONED
                    // and this case would assert about the sanction rather than
                    // about the surface. Mechanism B, and it is the obvious way
                    // to get this fixture wrong.
                    Path third = Files.createTempDirectory("def115-third-")
                            .resolve(".skill-manager");
                    Files.createDirectories(third.resolve("bin/cli"));
                    Files.createDirectories(third.resolve("skills"));
                    Files.createDirectories(third.resolve("installed"));
                    Path thirdTool = third.resolve("bin/cli").resolve("realtool");
                    Files.writeString(thirdTool, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
                    thirdTool.toFile().setExecutable(true);
                    assertTrue(LaunchEnv.looksLikeStoreRoot(third),
                            "precondition: the link target is inside something the product "
                                    + "recognises AS A HOME -- otherwise neither reader has "
                                    + "anything to disagree about; got " + third);
                    assertFalse(HomeProvenance.recordedParentStores(fx.store)
                                    .contains(third.toString()),
                            "precondition: the third home is NOT a recorded parent store, so "
                                    + "a path into it is unsanctioned and both readers are "
                                    + "supposed to refuse it");

                    // BOTH shapes, in one home, in one run: a symlink and a
                    // wrapper naming the same third home. The wrapper is the
                    // in-run control -- if the readers agreed about NOTHING the
                    // comparison below would be uninformative.
                    Files.createSymbolicLink(
                            fx.store.resolve("bin/cli").resolve("linktool"), thirdTool);
                    Path wrapper = fx.store.resolve("bin/cli").resolve("wrappertool");
                    Files.writeString(wrapper,
                            "#!/usr/bin/env bash\nexec \"" + thirdTool + "\" \"$@\"\n",
                            StandardCharsets.UTF_8);
                    wrapper.toFile().setExecutable(true);

                    List<String> repairSubjects = HomeRepair.detect(fx.store, fx.pin).findings()
                            .stream()
                            .map(HomeRepair.Finding::subject)
                            .filter(sub -> sub.startsWith("bin/cli/linktool")
                                    || sub.startsWith("bin/cli/wrappertool"))
                            .distinct().sorted().toList();
                    assertTrue(repairSubjects.contains("bin/cli/wrappertool"),
                            "IN-RUN CONTROL: repair still names the WRAPPER, so the reader ran "
                                    + "and the comparison below is about the symlink and not "
                                    + "about a detector that saw nothing; got " + repairSubjects);

                    HomeCloner.Verification v = HomeCloner.verify(fx.store, false);
                    List<String> verifySubjects = v.isolationFailures().stream()
                            .map(HomeCloner.Leak::path)
                            .filter(pth -> pth.startsWith("bin/cli/linktool")
                                    || pth.startsWith("bin/cli/wrappertool"))
                            .distinct().sorted().toList();
                    assertTrue(verifySubjects.contains("bin/cli/linktool"),
                            "IN-RUN CONTROL: verify names the SYMLINK -- this is the arm that "
                                    + "was always correct, and if it stopped firing the claim "
                                    + "below would go green for the wrong reason; got "
                                    + verifySubjects);

                    assertEquals(verifySubjects, repairSubjects,
                            "THE CLAIM, and it is RED at epic tip 17648705e67b (DEF-115): "
                                    + "one home, one moment, two readers, and they must name "
                                    + "the same set of shims that reach another home -- "
                                    + "whether the reach is spelled as a symlink or as text "
                                    + "inside a wrapper. verify said " + verifySubjects
                                    + "; repair said " + repairSubjects
                                    + ". `home repair` reads shim TEXT, and a symlink has "
                                    + "none, so it can never report this shape.");
                })

                .runAll();
    }

    // ------------------------------------------------------------- fixture

    /**
     * A ROOT-SHAPED home: {@code <root>/.skill-manager} beside
     * {@code <root>/.claude}, with a second home nearby to be damaged INTO.
     *
     * <p>Two homes, because every one of the four shapes is about a path
     * belonging to a home other than the one under test, and a one-home fixture
     * cannot express any of them — mechanism B, which cost this epic four of
     * its eleven instances.
     */
    private static final class Fixture {
        private final Path root;
        private final Path store;
        private final Path other;
        /**
         * The LIVE build — what {@code RunningCli} would answer if this were a
         * real invocation. Supplied rather than located, because an in-process
         * test cannot be found as a running CLI. NOT the build the home is
         * pinned at: that one is deleted by {@link #damageCliPin()}.
         */
        private final Path pin;

        private Fixture(Path root, Path store, Path other, Path pin) {
            this.root = root;
            this.store = store;
            this.other = other;
            this.pin = pin;
        }

        static Fixture build(String label) throws Exception {
            Path tmp = Files.createTempDirectory("home-repair-" + label + "-");
            Path root = tmp.resolve("root");
            Path store = newHome(root.resolve(AgentHomes.STORE_DIR_NAME));
            Path other = newHome(tmp.resolve("other").resolve(AgentHomes.STORE_DIR_NAME));

            // A unit in BOTH stores, so a mis-anchored projection is a link
            // that RESOLVES — the whole point of the shape. A projection at a
            // path that is simply missing is a dangling link, which
            // `home verify` has always found.
            for (Path s : List.of(store, other)) {
                Path unit = Files.createDirectories(s.resolve("skills").resolve(UNIT));
                Files.writeString(unit.resolve("SKILL.md"), "---\nname: " + UNIT + "\n---\n");
            }

            // The healthy projection: this home's agent dir at this home's unit.
            Path skills = Files.createDirectories(root.resolve(".claude").resolve("skills"));
            Files.createSymbolicLink(skills.resolve(UNIT), store.resolve("skills").resolve(UNIT));

            // A generated WRAPPER, the regular-file shim shape, naming its own
            // home. And the tree it names, in both homes, so a wrapper pointed
            // at the wrong one still RESOLVES.
            for (Path s : List.of(store, other)) {
                Path venv = Files.createDirectories(s.resolve("venvs").resolve("v").resolve("bin"));
                Files.writeString(venv.resolve("wrapper"), "#!/bin/sh\nexit 0\n");
                venv.resolve("wrapper").toFile().setExecutable(true);
                Path shim = Files.createDirectories(s.resolve("bin/cli")).resolve("wrapper");
                Files.writeString(shim, "#!/usr/bin/env bash\nexec \""
                        + venv.resolve("wrapper") + "\" \"$@\"\n");
                shim.toFile().setExecutable(true);
            }

            // An inherited entry: the OTHER home holds the tool, this one
            // mirrors it, the parent claims this home, and the ledger records
            // that this home is meant to have it.
            Path parentEntry = other.resolve("bin/cli").resolve(TOOL);
            Files.writeString(parentEntry, "#!/bin/sh\nexit 0\n");
            parentEntry.toFile().setExecutable(true);
            Files.createSymbolicLink(store.resolve("bin/cli").resolve(TOOL), parentEntry);
            Path claim = Files.createDirectories(other.resolve("child-homes/root"));
            Files.writeString(claim.resolve("child-home.json"), """
                    {
                      "id" : "root",
                      "parentHome" : "%s",
                      "childHome" : "%s",
                      "units" : [ ],
                      "createdAt" : "2026-01-01T00:00:00Z"
                    }
                    """.formatted(other, store));
            Files.writeString(store.resolve("artifacts.lock.toml"), """
                    schema = 1
                    recorded_at = "2026-01-01T00:00:00Z"

                    [[artifact]]
                    id = "cli-shim:test/%s"
                    kind = "cli-shim"
                    owner = "%s"
                    inputs = []
                    outputs = ["bin/cli/%s"]
                    source = "cli-lock.toml"
                    """.formatted(TOOL, UNIT, TOOL));
            // THIS HOME DEFERRED NOTHING, declared rather than assumed.
            //
            // Review of PR #244, blocker 1. `PRUNED_INHERITED_ENTRY` is only a
            // finding in a home where a declared-and-absent entry point is
            // ABNORMAL, and `lazyArtifactsDefault` is "on for every home except
            // the operator root". A fixture that left the default in place
            // would model a LAZY home, where the state is normal, and then
            // assert that the state is damage -- which is precisely the false
            // positive the review measured on stock `home clone` output.
            HomePolicy.writeLazyArtifacts(new SkillStore(store), false);

            // And the descent record, so the recorded parent store re-derives.
            HomeProvenance.write(store, new HomeProvenance.Descent(
                    HomeProvenance.SCHEMA_VERSION, other.toString(),
                    "2026-01-01T00:00:00Z", List.of(other.toString())));

            // The home's own front door, pinned at a build that exists — and
            // a SECOND, newer build beside it. That is DEF-012's arrangement
            // exactly: `brew upgrade skill-manager 0.23.0 -> 0.24.0` leaves a
            // home pinned at a VERSIONED Cellar path the upgrade deleted, and
            // the live build is the other directory. A one-build fixture cannot
            // express it: re-pinning at a build that is also gone repairs
            // nothing, which is what the first run of this suite measured.
            Path oldBuild = tmp.resolve("build").resolve("0.1.0").resolve("skill-manager");
            Path newBuild = tmp.resolve("build").resolve("0.2.0").resolve("skill-manager");
            for (Path build : List.of(oldBuild, newBuild)) {
                Files.createDirectories(build.getParent());
                Files.writeString(build, "#!/bin/sh\nexit 0\n");
                build.toFile().setExecutable(true);
            }
            LauncherShims.write(new SkillStore(store), oldBuild);

            // A SANCTIONED inherited wrapper: a regular file at bin/cli/mirror
            // whose text execs the parent's identically-named entry. Legitimate
            // (the parent claims this home), and the control for the
            // GOAL-one-home-one-answer guard below.
            Path parentMirror = other.resolve("bin/cli").resolve("mirror");
            Files.writeString(parentMirror, "#!/bin/sh\nexit 0\n");
            parentMirror.toFile().setExecutable(true);
            Path mirror = store.resolve("bin/cli").resolve("mirror");
            Files.writeString(mirror, "#!/usr/bin/env bash\nexec \"" + parentMirror + "\" \"$@\"\n");
            mirror.toFile().setExecutable(true);

            return new Fixture(root, store, other, newBuild);
        }

        Path claudeDir() { return root.resolve(".claude"); }

        Path claudeSkills() { return claudeDir().resolve("skills"); }

        /** Shape 1: the projection now resolves into the OTHER home's store. */
        void damageAgentLink() throws IOException {
            Path link = claudeSkills().resolve(UNIT);
            Files.deleteIfExists(link);
            Files.createSymbolicLink(link, other.resolve("skills").resolve(UNIT));
        }

        /** Shape 2: the wrapper's TEXT names the other home's tree. It still runs. */
        void damageShimText() throws IOException {
            Path shim = store.resolve("bin/cli/wrapper");
            Files.writeString(shim, Files.readString(shim, StandardCharsets.UTF_8)
                    .replace(store.toString(), other.toString()), StandardCharsets.UTF_8);
            shim.toFile().setExecutable(true);
        }

        /** Shape 3: the inherited entry is pruned. The ledger still declares it. */
        void damagePrunedEntry() throws IOException {
            Files.delete(store.resolve("bin/cli").resolve(TOOL));
        }

        /** Shape 4: DEF-012 — the pinned build is deleted out from under it. */
        void damageCliPin() throws IOException {
            Path pinned = LauncherShims.pinnedCliIn(
                    LauncherShims.cliEntrypoint(new SkillStore(store))).orElseThrow();
            // What `brew upgrade` does: the versioned directory the home names
            // is removed and a newer one takes its place. The shim FILE stays
            // executable, which is why every reader testing -x on it called the
            // home healthy.
            Files.delete(pinned);
            Files.delete(pinned.getParent());
        }

        /**
         * The four REPAIRABLE shapes. Kept exactly that, because the repair
         * tests assert the invariant "after `--fix`, detection is clean" — and
         * a report-only shape planted here would make that invariant
         * unsatisfiable for reasons that have nothing to do with repair.
         */
        void damageAll() throws IOException {
            damageAgentLink();
            damageShimText();
            damagePrunedEntry();
            damageCliPin();
        }

        /**
         * Every shape the detector knows, repairable or not — the fixture for
         * the DETECTION-coverage guard.
         *
         * <p>Separate from {@link #damageAll()} on purpose. Detection coverage
         * and repair coverage are two questions, and #289 is the first kind
         * where they differ: it is reported and deliberately never repaired,
         * because the only mechanical repair removes the sole entry point the
         * home currently reaches.
         */
        void damageEveryKind() throws IOException {
            damageAll();
            damageShadowedMirror();
        }

        /**
         * #289. A mirror this home is SANCTIONED to hold, of a parent entry
         * that runs a skill this home also has its own copy of.
         *
         * <p>Structurally indistinguishable from the legitimate mirror the
         * fixture already plants at {@code bin/cli/mirror}; what makes it
         * damage is the local copy written last. Planted here so the
         * every-kind guard above stays an invariant rather than a list
         * somebody has to remember to extend.
         */
        void damageShadowedMirror() throws IOException {
            Path parentScript = other.resolve("skills").resolve(UNIT).resolve("shadowed.py");
            Files.createDirectories(parentScript.getParent());
            Files.writeString(parentScript, "print('parent')\n");
            Path parentEntry = other.resolve("bin/cli").resolve("shadowed-tool");
            Files.writeString(parentEntry,
                    "#!/usr/bin/env bash\nexec python3 \"" + parentScript + "\" \"$@\"\n");
            parentEntry.toFile().setExecutable(true);
            Files.createSymbolicLink(store.resolve("bin/cli").resolve("shadowed-tool"), parentEntry);
            Path mine = store.resolve("skills").resolve(UNIT).resolve("shadowed.py");
            Files.createDirectories(mine.getParent());
            Files.writeString(mine, "print('mine')\n");
        }

        /**
         * Stale, and nothing else: an unacknowledged drift record and a unit
         * whose bytes moved after it was recorded. Neither is damage.
         */
        void makeStale() throws Exception {
            SkillStore s = new SkillStore(store);
            DriftGate.recordSince(s, HomeDigest.read(s).orElse(null), "fixture");
            Files.writeString(store.resolve("skills").resolve(UNIT).resolve("SKILL.md"),
                    "---\nname: " + UNIT + "\ndescription: moved\n---\n");
            DriftGate.recordSince(s, HomeDigest.read(s).orElse(null), "fixture");
            // THE DECLARED-BUT-UNBUILT ENTRY POINT -- and it is the variant
            // that CAN trip the check, which is the whole point.
            //
            // This used to plant `bin/cli/never-built`, a name NO PARENT STORE
            // HOLDS. That is the one flavour of staleness `prunedInheritedEntries`
            // could never have reported, because its second condition is "and
            // the recorded parent still holds it" -- so the assertion for the
            // clause this ticket is graded on was tested against the only case
            // that cannot fail it. DEF-046's shape, inside clause 3's own
            // oracle, and the review of PR #244 found it by finding the false
            // positive it was supposed to have caught.
            //
            // So: the SAME entry the damaged fixture prunes, whose parent DOES
            // hold it -- byte-for-byte the state that produces a finding above
            // -- with the one thing that makes it normal: this home deferred.
            HomePolicy.writeLazyArtifacts(new SkillStore(store), true);
            Files.delete(store.resolve("bin/cli").resolve(TOOL));
        }

        /** A child home made by the real cloner, which records its own descent. */
        Path cloneToChild(String name) throws Exception {
            Path dest = root.getParent().resolve(name).resolve(AgentHomes.STORE_DIR_NAME);
            HomeCloner.cloneHome(store, dest, false, false);
            return dest;
        }

        /**
         * Every byte and every link target this home holds on either axis.
         *
         * <p>Link targets as TEXT and file contents as text, because "did the
         * repair change anything" is a question about both and a
         * content-only snapshot would call a repointed symlink unchanged.
         */
        /** {@link #snapshot()} for an arbitrary root, for the other home. */
        List<String> snapshotOf(Path root) {
            List<String> out = new ArrayList<>();
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return out;
            try (var walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk.sorted()::iterator) {
                    if (Files.isSymbolicLink(p)) out.add(p + " -> " + Files.readSymbolicLink(p));
                    else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                        out.add(p + " :: " + Files.readString(p, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException | RuntimeException unreadable) {
                out.add(root + " !! " + unreadable);
            }
            return out;
        }

        List<String> snapshot() {
            List<String> out = new ArrayList<>();
            List<Path> roots = new ArrayList<>();
            roots.add(store);
            roots.addAll(HomeRepair.agentDirsOf(store));
            for (Path r : roots) {
                if (!Files.exists(r, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var walk = Files.walk(r)) {
                    for (Path p : (Iterable<Path>) walk.sorted()::iterator) {
                        if (Files.isSymbolicLink(p)) {
                            out.add(p + " -> " + Files.readSymbolicLink(p));
                        } else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                            out.add(p + " :: " + Files.readString(p, StandardCharsets.UTF_8));
                        }
                    }
                } catch (IOException | RuntimeException unreadable) {
                    out.add(r + " !! " + unreadable);
                }
            }
            return out;
        }
    }

    /** A recursive delete that does not follow links, for the fixture only. */
    private static final class HomeIntegrityDelete {
        static void deleteRecursive(Path root) throws IOException {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(root);
                return;
            }
            try (var entries = Files.list(root)) {
                for (Path e : (Iterable<Path>) entries::iterator) deleteRecursive(e);
            }
            Files.delete(root);
        }
    }

    private static Path newHome(Path root) throws Exception {
        SkillStore store = new SkillStore(root);
        store.init();
        return root;
    }

    // ------------------------------------------------------------ plumbing

    private record Result(int rc, String out, String err) {}

    private static Result cli(Path pin, String... args) {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            int rc = new CommandLine(new HomeCommand.RepairCmd(null, pin)).execute(args);
            return new Result(rc, out.toString(), err.toString());
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }
}
