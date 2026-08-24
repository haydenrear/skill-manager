///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.ContextItem;
import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * THE MEMBERSHIP LAW — the second post-condition, beside {@code HomeFixpointLaw}.
 *
 * <p>For every Skill Manager home this graph produced or touched:
 *
 * <pre>
 *   its UNIT MEMBERSHIP is what the graph intended.
 *   No home gains a unit nobody installed into it;
 *   none loses one nobody removed.
 * </pre>
 *
 * <h2>Why a SECOND law, when the first one is the best instrument here</h2>
 *
 * <p>{@code HomeFixpointLaw} asks <em>"does every home this graph produced
 * still verify?"</em> and is wired into 24 of 30 graphs. <b>It would not have
 * caught DEF-047</b>, and the reason generalises: a re-realized home
 * <b>verifies fine</b>. It is internally consistent. It is merely wrong about
 * what it holds.
 *
 * <pre>
 *   worktree home BEFORE: deploy-helm, spec-double-compiler, test-graph, tracing-observability
 *   worktree home AFTER:  skill-manager,  spec-double-compiler, test-graph, tracing-observability
 * </pre>
 *
 * <p>{@code deploy-helm} gone, {@code skill-manager} and {@code skt} installed,
 * by a {@code project resolve} that resolved its target from the working
 * directory. Three instruments missed it — the ticket's own probes, its
 * adversarial reviewer, and the epic agent's containment check — because all
 * three asked <em>"did it write where it should not?"</em>. The close-out gate,
 * run later for another reason, asked <em>"does this home hold something the
 * tier above does not?"</em>, and that is the only question that sees a unit
 * <b>quietly going away</b>.
 *
 * <h2>What "intended" is, concretely: the records ARE the intent</h2>
 *
 * <p>A post-condition runs once, at the end, so it cannot hold a before-image
 * of a home it never saw at the start. It does not need one. A home carries its
 * own standing record of who put what in it, and <b>that record is what
 * "nobody installed it" means</b>. So the law reads the same membership through
 * three independent readers and requires one answer:
 *
 * <ol>
 *   <li><b>DISK</b> — {@code home describe --json}'s unit snapshot, which is
 *       production walking {@code skills/}, {@code plugins/}, {@code docs/} and
 *       {@code harnesses/}. What the home actually holds.</li>
 *   <li><b>RECORDS</b> — {@code installed/&lt;name&gt;.json} and
 *       {@code installed/&lt;name&gt;.projections.json}. What somebody installed.</li>
 *   <li><b>LOCK</b> — the {@code [[units]]} entries in {@code units.lock.toml}.
 *       What the home's manifest says it resolved to.</li>
 * </ol>
 *
 * <p>A name in DISK but not in RECORDS is <b>a unit nobody installed</b>. A
 * name in RECORDS but not on DISK is <b>a unit nobody removed</b> — and that is
 * literally how DEF-047 was found: {@code deploy-helm} vanished from
 * {@code skills/} and <em>its {@code .projections.json} record survived,
 * orphaned</em>. The law fires on exactly the residue the defect left.
 *
 * <h2>Homes are discovered STRUCTURALLY, and finding none is a FAILURE</h2>
 *
 * <p>Both choices are {@code HomeFixpointLaw}'s and both are load-bearing, so
 * they are copied deliberately rather than reinvented:
 *
 * <ul>
 *   <li>Every upstream context value that is an existing directory — plus its
 *       {@code .skill-manager} child — is offered to {@code home describe}, and
 *       <b>production decides what a home is</b>: exit {@code 2} is
 *       {@code NotAHomeException}, "not a home, skip". No second spelling of
 *       {@code looksLikeStoreRoot} lives here. A key-based lookup would be a
 *       list that goes stale silently; graphs publish home paths under a dozen
 *       different keys.</li>
 *   <li>Only homes under the JVM temp root are touched. The onboarding graph's
 *       scan once surfaced one of the OPERATOR'S REAL HOMES.
 *
 *       <p><b>This javadoc previously claimed {@code home describe} was
 *       classified {@code READ}. It was classified {@code WRITE}</b>, and the
 *       consequence was not cosmetic — review of #241, H1. {@code WRITES_HOME}
 *       let {@code SkillManagerCli.tryReconcile} run ahead of
 *       {@code DescribeCmd.call()}, reaching {@code ReconcileUseCase} →
 *       {@code OnboardUnit} → {@code writeSource}. Measured:
 *
 *       <pre>
 *         BEFORE installed/: []
 *         $ skill-manager home describe --home $H --json
 *         AFTER  installed/: [intruder.json]
 *       </pre>
 *
 *       <p>So the law's ONLY reader was <b>manufacturing the very records it
 *       was about to audit</b>, microseconds before the comparison. The
 *       {@code GAINED} direction — "a unit nobody installed" — was therefore
 *       STRUCTURALLY UNREACHABLE against a real home and fired only in the
 *       synthetic self-test below. And the law was a mutator across 24 graphs.
 *
 *       <p>{@code CommandHomeAccess} now classifies {@code home describe}
 *       READ unless the invocation matched {@code --init}. Re-measured on the
 *       same fixture: {@code installed/} stays {@code []}, the descriptor still
 *       reports the unit, and the law reports
 *       {@code GAINED [intruder] — present in the home, and no installed/
 *       record names them}. The skipped homes are counted and NAMED rather
 *       than silently dropped.</li>
 *   <li><b>A run that finds zero homes FAILS.</b> An instrument reporting
 *       success because it could not look is the failure mode this epic keeps
 *       paying for, and it is the one this law exists to close.</li>
 * </ul>
 *
 * <h2>The self-test is the reason to believe a green run</h2>
 *
 * <p>Three readers that all return the empty set agree perfectly. That is
 * mechanism C in this epic's vacuity ledger — a detector that never reached the
 * code reads exactly like a passing check — so the law plants two synthetic
 * homes in a temp directory and runs the SAME comparison over them: one that
 * has gained a unit with no record, one that has lost a unit whose record
 * survives. Both must be flagged, and a third, consistent home must not. A
 * blind detector and an over-eager one both fail here, every run, before the
 * real homes are looked at.
 *
 * <h2>What this law deliberately does NOT decide</h2>
 *
 * <p><b>It is a within-home consistency law, not a temporal one, and that has a
 * measured limit.</b> On run {@code 20260822-223059} the {@code home-integrity}
 * graph's own control deliberately reproduced the escape — a {@code project
 * resolve} reached a checkout from an unconfined working directory and swapped
 * that checkout's child home from one unit to another — and this law reported
 * the resulting home as CLEAN. It was: the resolve rewrote the records to match
 * what it had done, so disk and records agreed about a membership nobody asked
 * for. What the law catches is the RESIDUE form, which is the form DEF-047
 * actually took and the form no other instrument sees. The before/after form
 * needs a before-image, and the only place a before-image exists is around a
 * single operation — which is what
 * {@code home-integrity/ProjectVerbStaysInItsHome} does, with a control.
 * Recorded as a deferral; a post-condition that runs once cannot hold a
 * snapshot of a home it never saw at the start.
 *
 * <p>{@code home.runtime.json} carries a PERSISTED unit snapshot — the closest
 * thing a home has to a "first sight" image of itself. Its drift from the live
 * disk view is measured and logged as {@code descriptorDrift}, and it is
 * <b>not</b> a failure: nothing yet establishes how often a legitimate flow
 * leaves that descriptor stale, and turning an unmeasured signal into a
 * post-condition on 24 graphs would buy noise rather than coverage. Recorded as
 * a deferral rather than left as an unstated omission.
 *
 * @see HomeFixpointLaw
 */
public final class HomeMembershipLaw {

    static final NodeSpec SPEC = NodeSpec.of("home.membership.law")
            .kind(NodeSpec.Kind.ASSERTION)
            .timeout("900s")
            .output("homesChecked", "string")
            .output("unitsObserved", "string");

    /** Exit code of {@code NotAHomeException} — "that path is not a home". */
    private static final int NOT_A_HOME = 2;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            // THREE separate claims, kept separate. Folding them into one
            // boolean is mechanism A from this epic's vacuity ledger seen from
            // the reader's side: the self-test failing, the run checking
            // nothing, and a home genuinely holding the wrong units would all
            // redden one name and the envelope would not say which.
            List<String> violations = new ArrayList<>();
            List<String> log = new ArrayList<>();

            // ---------------------------------------------------- self-test
            // Before a single real home is read. A green law is worth nothing
            // until the same comparison has been shown to go red.
            SelfTest self = selfTest();
            log.add(self.report());

            Path cli = SmEnv.cli();
            Set<Path> all = candidateHomes(ctx.context());
            Set<Path> candidates = new LinkedHashSet<>();
            List<String> outsideSandbox = new ArrayList<>();
            for (Path c : all) {
                if (insideSandbox(c)) candidates.add(c); else outsideSandbox.add(c.toString());
            }

            List<String> checked = new ArrayList<>();
            int unitsObserved = 0;
            int descriptorDrift = 0;
            // How many checked homes had ANYTHING for the three readers to
            // disagree about. Review of #241, M5: `homesChecked=1,
            // unitsObserved=0` satisfies the zero-homes guard and shipped
            // green on `project-manifest`, whose home is genuinely empty. That
            // is a WEAK run, not a wrong one -- an empty home that is still
            // empty is a real post-condition, and the self-test is what carries
            // the readers' sensitivity either way. It is reported as a number
            // rather than turned into a failure, because reddening a graph for
            // installing nothing would be inventing a defect. HIS-6 gets the
            // number for the sweep.
            int homesWithMembership = 0;
            // DEF-107. How many units this run DECLINED to judge because a
            // fixture marked them staged. Published rather than left implicit:
            // an exclusion nobody counts is an exclusion that grows.
            int stagedExcused = 0;

            for (Path home : candidates) {
                Run described = describe(cli, home);
                if (described.exit == NOT_A_HOME) continue;   // production says: not a home
                if (described.exit != 0) {
                    violations.add(home + ": home describe exited " + described.exit
                            + " — membership is undecidable here\n" + described.tail());
                    continue;
                }
                checked.add(home.toString());

                Membership m = membership(home, described.out);
                unitsObserved += m.disk().size();
                stagedExcused += minus(m.staged(), m.records()).size();
                if (!m.disk().isEmpty() || !m.records().isEmpty() || !m.lock().isEmpty()) {
                    homesWithMembership++;
                }
                log.add(m.report(home));
                violations.addAll(m.violations(home));

                Set<String> persisted = unitNames(read(home.resolve("home.runtime.json")));
                if (!persisted.isEmpty() && !persisted.equals(m.disk())) {
                    descriptorDrift++;
                    log.add("  note  " + home + " — home.runtime.json's persisted snapshot "
                            + persisted + " differs from the live disk view " + m.disk()
                            + " (measured, not asserted; see the class comment)");
                }
            }

            // A law that checked nothing is not a law. HomeFixpointLaw's reason,
            // and this law's reason for existing at all.
            boolean lookedAtSomething = !checked.isEmpty();
            boolean membershipHeld = violations.isEmpty();

            List<String> failures = new ArrayList<>(violations);
            if (!self.ok()) {
                failures.add(0, "the membership detector failed its own self-test: " + self.why());
            }
            if (!lookedAtSomething) {
                failures.add("no Skill Manager home was found in this graph's context — "
                        + "either this node is wired into a graph that produces none, or it "
                        + "runs before the home exists. Both make the law vacuous.");
            }

            NodeResult result = failures.isEmpty()
                    ? NodeResult.pass("home.membership.law")
                    : NodeResult.fail("home.membership.law", String.join("; ", failures));
            return result
                    .assertion("every_home_holds_exactly_what_was_installed_into_it",
                            membershipHeld)
                    .assertion("the_detector_flags_a_planted_gain_and_a_planted_loss", self.ok())
                    // Named separately from the line above even though both are
                    // decided by `self.ok()`: this is the claim a reviewer of
                    // DEF-107 will attack -- "you excused the fixtures and the
                    // law stopped seeing anything" -- and it should be readable
                    // in the envelope without opening the log.
                    .assertion("an_unmarked_intruder_beside_a_marked_staged_unit_is_still_flagged",
                            self.ok())
                    .assertion("at_least_one_home_was_actually_checked", lookedAtSomething)
                    .metric("homesChecked", checked.size())
                    .metric("unitsObserved", unitsObserved)
                    .metric("homesOutsideSandbox", outsideSandbox.size())
                    .metric("descriptorDrift", descriptorDrift)
                    .metric("homesWithMembership", homesWithMembership)
                    .metric("stagedUnitsExcused", stagedExcused)
                    .publish("homesWithMembership", String.valueOf(homesWithMembership))
                    .publish("homesChecked", String.join(",", checked))
                    .publish("unitsObserved", String.valueOf(unitsObserved))
                    .log(String.join("\n", log)
                            + (outsideSandbox.isEmpty() ? ""
                                    : "\nSKIPPED (outside the sandbox, never touched): "
                                            + String.join(", ", outsideSandbox)));
        });
    }

    // ----------------------------------------------------------- membership

    /**
     * The file a FIXTURE writes inside a unit directory to say "the graph put
     * this here by hand, on purpose, as a precondition".
     *
     * <h2>DEF-107: the law was right about the state and wrong about the case</h2>
     *
     * <p>The {@code home-sync} graph stages sync preconditions by writing unit
     * trees straight into a home — {@code HomeSyncSupport.mkUnit}, ten nodes —
     * because the thing under test is what {@code home sync} does about a unit
     * the destination has not got. A hand-written unit has no
     * {@code installed/} record by construction, so this law read every one of
     * them as {@code GAINED [...] — a unit nobody installed} and reddened the
     * CORE graph. Measured, run {@code 20260824-193907}: three homes,
     * {@code hs-delta} and {@code hs-epsilon}, and the law's self-test passed
     * in both directions first. <b>The instrument worked; the rule did not fit
     * the case.</b>
     *
     * <h2>Why marking, and why the mark lives INSIDE the unit</h2>
     *
     * <p>Three alternatives were considered and rejected by measurement, not
     * by taste:
     *
     * <ul>
     *   <li><b>Drop the GAINED direction.</b> Refused outright by the ticket:
     *       GAINED is the direction that catches a unit appearing in a home
     *       nobody named, and DEF-047 is half GAINED.</li>
     *   <li><b>Order the law later in the graph.</b> #253 proposed this on the
     *       reasoning that {@link #SPEC} declares no {@code dependsOn} and so
     *       "runs mid-scenario and calls an intermediate state final". Measured
     *       in the same run: {@code home-sync} wires it
     *       {@code .dependsOn("home.sync.authored.agent.tree")} and the planner
     *       ran it {@code [19/19]}, dead last. Every one of the twenty-three
     *       wirings in {@code build.gradle.kts} declares a predecessor. Ordering
     *       was never the cause and could not have been the fix: the staged
     *       units are still on disk at the end of the graph, which is exactly
     *       when a post-condition looks.</li>
     *   <li><b>Have {@code mkUnit} write an {@code installed/} record too.</b>
     *       That would make the readers agree by rewriting the scenario:
     *       {@code HomeSyncPermutations} plants {@code GHOST} specifically as a
     *       unit with "no materialization record", and giving it one deletes
     *       the case.</li>
     * </ul>
     *
     * <p>So the fixture DECLARES what it staged, and the declaration travels
     * with the unit because it is a file inside the unit directory. That
     * matters: {@code hs-delta} was staged in the root home and then propagated
     * to the project and worktree homes by a real {@code home sync}, arriving
     * in both without a record. A per-home ledger would have covered one home
     * of three.
     *
     * <p><b>It cannot blind the direction it narrows.</b> Only a marked unit is
     * excused, production never writes this file, and the self-test now plants
     * a home holding a marked unit AND an unmarked intruder and requires the
     * intruder to still be flagged — so a mark that silenced everything fails
     * the law before a real home is read.
     */
    static final String STAGED_MARKER = ".test-graph-staged";

    /** The four directories a unit can live in, per {@code SkillStore}'s layout. */
    private static final List<String> UNIT_DIRS =
            List.of("skills", "plugins", "docs", "harnesses");

    /**
     * One home's unit set as three readers see it, plus what the graph
     * declared it staged.
     *
     * @param disk    what the home holds, per production's own walk
     * @param records what somebody installed, per {@code installed/}
     * @param lock    what the manifest resolved to, per {@code units.lock.toml}
     * @param staged  what a FIXTURE marked as hand-planted ({@link #STAGED_MARKER})
     */
    record Membership(Set<String> disk, Set<String> records, Set<String> lock,
                      Set<String> staged) {

        /** Three readers and no staging declaration — the shape every caller had. */
        Membership(Set<String> disk, Set<String> records, Set<String> lock) {
            this(disk, records, lock, Set.of());
        }

        List<String> violations(Path home) {
            List<String> out = new ArrayList<>();
            // DEF-107. A unit the graph MARKED as staged is not "a unit nobody
            // installed" -- it is a unit this graph installed by hand and said
            // so. Subtracted here and nowhere else: `lost` below is untouched,
            // because a record naming a unit the home does not hold is a
            // finding whatever anyone staged.
            Set<String> gained = minus(minus(disk, records), staged);
            Set<String> lost = minus(records, disk);
            if (!gained.isEmpty()) {
                out.add(home + " GAINED " + gained + " — present in the home, and no "
                        + "installed/ record names them. A unit nobody installed."
                        + (staged.isEmpty() ? ""
                                : " (" + staged.size() + " other unit(s) here carry "
                                        + STAGED_MARKER + " and were excused.)"));
            }
            if (!lost.isEmpty()) {
                out.add(home + " LOST " + lost + " — an installed/ record names them and "
                        + "the home does not hold them. A unit nobody removed.");
            }
            // The lock is the third reader and it is checked against the
            // records rather than against disk: disk-vs-records is already
            // stated above, and repeating it through a third path would double
            // one finding rather than add one.
            //
            // AN EMPTY LOCK ABSTAINS. Measured, run 20260822-223059: four
            // root-tier homes had all three readers in exact agreement, and the
            // one disagreement was a PROJECT CHILD home whose units.lock.toml
            // is empty BY DESIGN -- a child home's resolved closure is recorded
            // in the project registry, not in the child's own lock. A reader
            // with nothing to say is not a reader that disagrees, and a law
            // that fired on every child home in the repository would be turned
            // off within a week. The claim above -- disk versus records -- is
            // untouched by this and is what the law's sentence actually says.
            if (lock.isEmpty()) return out;
            Set<String> lockOnly = minus(lock, records);
            Set<String> recordsOnly = minus(records, lock);
            if (!lockOnly.isEmpty() || !recordsOnly.isEmpty()) {
                out.add(home + " LOCK DISAGREES — units.lock.toml names " + lockOnly
                        + " with no installed/ record, and installed/ names " + recordsOnly
                        + " with no lock entry.");
            }
            return out;
        }

        String report(Path home) {
            boolean ok = violations(home).isEmpty();
            return (ok ? "PASS  " : "FAIL  ") + home
                    + "\n    disk    " + disk
                    + "\n    records " + records
                    + "\n    lock    " + lock
                    // Printed always, empty set included. A silent exclusion is
                    // the thing this law exists to refuse, so the number of
                    // units it declined to judge is on every report whether or
                    // not it changed the verdict.
                    + "\n    staged  " + staged + "  (" + STAGED_MARKER + ")";
        }
    }

    private static Membership membership(Path home, String describeJson) {
        return new Membership(
                unitNames(describeJson),
                installedRecords(home.resolve("installed")),
                lockUnits(read(home.resolve("units.lock.toml"))),
                stagedUnits(home));
    }

    /**
     * Unit directories in {@code home} carrying {@link #STAGED_MARKER}.
     *
     * <p>Read off the home rather than handed in: the law discovers homes
     * structurally and cannot be given a list, and a unit that a real
     * {@code home sync} carried from one home to another must arrive marked or
     * the declaration would cover one home of three.
     */
    static Set<String> stagedUnits(Path home) {
        Set<String> out = new TreeSet<>();
        for (String dir : UNIT_DIRS) {
            Path unitRoot = home.resolve(dir);
            if (!Files.isDirectory(unitRoot)) continue;
            try (Stream<Path> s = Files.list(unitRoot)) {
                s.forEach(unit -> {
                    if (Files.isRegularFile(unit.resolve(STAGED_MARKER))) {
                        out.add(unit.getFileName().toString());
                    }
                });
            } catch (IOException unreadable) {
                // Unreadable reads as "nothing was staged", which makes every
                // hand-planted unit a GAINED violation. Loud, not silent, and
                // the safe direction for a narrowing.
            }
        }
        return out;
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }

    // -------------------------------------------------------------- readers

    /**
     * The {@code units} array of a {@code home describe --json} document.
     *
     * <p>Scoped to the array before names are taken: the descriptor also
     * carries a {@code cli} block and a {@code gateway} block, and an
     * unscoped {@code "name"} sweep would pick up whatever those gain next.
     */
    static Set<String> unitNames(String json) {
        Set<String> out = new TreeSet<>();
        if (json == null) return out;
        int at = json.indexOf("\"units\"");
        if (at < 0) return out;
        int open = json.indexOf('[', at);
        if (open < 0) return out;
        int depth = 0;
        int close = -1;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']' && --depth == 0) { close = i; break; }
        }
        if (close < 0) return out;
        Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json.substring(open, close));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /**
     * Every unit an {@code installed/} record names.
     *
     * <p>Both {@code <name>.json} and {@code <name>.projections.json} count.
     * The projections file alone is what survived DEF-047 — "its
     * {@code .projections.json} record survives, orphaned" — so a reader that
     * looked only at {@code <name>.json} would have missed the very instance
     * this law is built from.
     */
    static Set<String> installedRecords(Path installedDir) {
        Set<String> out = new TreeSet<>();
        if (!Files.isDirectory(installedDir)) return out;
        try (Stream<Path> s = Files.list(installedDir)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.endsWith(".projections.json")) {
                    out.add(n.substring(0, n.length() - ".projections.json".length()));
                } else if (n.endsWith(".json")) {
                    out.add(n.substring(0, n.length() - ".json".length()));
                }
            });
        } catch (IOException ignored) {
            // unreadable: reported as an empty record set, which shows up as
            // every disk unit having "gained" — loud, not silent.
        }
        return out;
    }

    /** The {@code name = "..."} of each {@code [[units]]} block. */
    static Set<String> lockUnits(String toml) {
        Set<String> out = new TreeSet<>();
        if (toml == null) return out;
        boolean inUnits = false;
        for (String raw : toml.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("[[")) {
                inUnits = line.equals("[[units]]");
                continue;
            }
            if (line.startsWith("[")) { inUnits = false; continue; }
            if (!inUnits) continue;
            Matcher m = Pattern.compile("^name\\s*=\\s*\"([^\"]+)\"").matcher(line);
            if (m.find()) out.add(m.group(1));
        }
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readString(p) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ self-test

    /** The outcome of running the detector over homes with known answers. */
    record SelfTest(boolean ok, String why, String report) {}

    /**
     * Plant three homes with known membership and run the SAME comparison.
     *
     * <p>Not a mock of the comparison: {@link #membership} is not used here
     * only because it shells {@code home describe}, so the DISK set is supplied
     * directly and the other two readers run against real files on disk. The
     * three violation rules under test are the production ones.
     */
    static SelfTest selfTest() {
        try {
            Path root = Files.createTempDirectory("membership-selftest-");
            try {
                // consistent: disk == records == lock
                Path good = plant(root.resolve("consistent"),
                        Set.of("alpha"), Set.of("alpha"), Set.of("alpha"));
                // gained: on disk, no record — "a unit nobody installed"
                Path gained = plant(root.resolve("gained"),
                        Set.of("alpha", "intruder"), Set.of("alpha"), Set.of("alpha"));
                // lost: the unit is gone from the home and ONLY its
                // .projections.json record survives. That is DEF-047 to the
                // letter — "deploy-helm is GONE from skills/ (its
                // .projections.json record survives, orphaned)" — and it is why
                // the record reader must count both spellings. Planted in the
                // exact shape so that narrowing the reader to <name>.json
                // fails here rather than in production a year from now.
                Path lost = plantOrphanedProjection(root.resolve("lost"),
                        Set.of("alpha"), "deploy-helm");

                // DEF-107's control, and the reason the staging exclusion is
                // not a hole. ONE home holds BOTH a marked staged unit and an
                // unmarked intruder. The mark must excuse exactly one of them:
                // if it excuses neither the exclusion does not work, and if it
                // excuses both the GAINED direction is dead and DEF-047 walks
                // back in. Neither can be true and this run be green.
                Path mixed = plant(root.resolve("staged-and-intruder"),
                        Set.of("alpha", "hand-planted", "intruder"), Set.of("alpha"),
                        Set.of("alpha"));
                Files.writeString(
                        mixed.resolve("skills").resolve("hand-planted").resolve(STAGED_MARKER),
                        "home-sync graph fixture\n");

                List<String> goodV = at(good, Set.of("alpha")).violations(good);
                List<String> gainedV = at(gained, Set.of("alpha", "intruder")).violations(gained);
                List<String> lostV = at(lost, Set.of("alpha")).violations(lost);
                Set<String> mixedStaged = stagedUnits(mixed);
                List<String> mixedV =
                        at(mixed, Set.of("alpha", "hand-planted", "intruder")).violations(mixed);

                StringBuilder sb = new StringBuilder("SELF-TEST (runs before any real home):");
                sb.append("\n  consistent home -> ").append(goodV.size()).append(" violation(s)");
                sb.append("\n  gained a unit   -> ").append(gainedV.size()).append(" violation(s)");
                sb.append("\n  lost  a unit    -> ").append(lostV.size()).append(" violation(s)");
                sb.append("\n  staged + intruder -> ").append(mixedV.size())
                        .append(" violation(s), staged=").append(mixedStaged)
                        .append(", violations=").append(mixedV);

                if (!goodV.isEmpty()) {
                    return new SelfTest(false,
                            "it flagged a home whose three readers agree: " + goodV, sb.toString());
                }
                if (gainedV.isEmpty()) {
                    return new SelfTest(false,
                            "it did NOT flag a home holding a unit no record names", sb.toString());
                }
                if (lostV.isEmpty()) {
                    return new SelfTest(false,
                            "it did NOT flag a record naming a unit the home does not hold",
                            sb.toString());
                }
                // DEF-107's two-sided control, stated as two separate failures
                // so the report says WHICH way it broke.
                if (!mixedStaged.equals(Set.of("hand-planted"))) {
                    return new SelfTest(false,
                            "the " + STAGED_MARKER + " reader did not read exactly the marked "
                                    + "unit: " + mixedStaged, sb.toString());
                }
                String mixedText = String.join(" ", mixedV);
                if (mixedText.contains("hand-planted")) {
                    return new SelfTest(false,
                            "it flagged a unit the graph MARKED as staged: " + mixedV,
                            sb.toString());
                }
                if (!mixedText.contains("intruder")) {
                    return new SelfTest(false,
                            "IT DID NOT FLAG AN UNMARKED INTRUDER standing beside a marked "
                                    + "staged unit — the staging exclusion has swallowed the "
                                    + "GAINED direction, which is the direction DEF-047 was "
                                    + "caught by: " + mixedV,
                            sb.toString());
                }
                return new SelfTest(true, "", sb.toString());
            } finally {
                deleteTree(root);
            }
        } catch (IOException e) {
            return new SelfTest(false, "the self-test could not run: " + e.getMessage(),
                    "SELF-TEST: errored");
        }
    }

    /** Read the two file-backed readers off a planted home. */
    private static Membership at(Path home, Set<String> disk) {
        return new Membership(new TreeSet<>(disk),
                installedRecords(home.resolve("installed")),
                lockUnits(read(home.resolve("units.lock.toml"))),
                // The SAME reader the real lane uses, off real files. Supplying
                // the staged set directly here would let the exclusion pass its
                // own self-test while the reader that finds the marker in a
                // real home was broken -- mechanism D, in the control.
                stagedUnits(home));
    }

    /**
     * A home holding {@code disk} whose only trace of {@code orphan} is an
     * orphaned {@code <orphan>.projections.json}. DEF-047's residue.
     */
    private static Path plantOrphanedProjection(Path home, Set<String> disk, String orphan)
            throws IOException {
        plant(home, disk, disk, disk);
        Files.writeString(home.resolve("installed").resolve(orphan + ".projections.json"), "{}");
        return home;
    }

    private static Path plant(Path home, Set<String> disk, Set<String> records, Set<String> lock)
            throws IOException {
        for (String d : disk) Files.createDirectories(home.resolve("skills").resolve(d));
        Path installed = Files.createDirectories(home.resolve("installed"));
        for (String r : records) {
            Files.writeString(installed.resolve(r + ".json"), "{}");
            // AND the projections record beside it, because a LIVE unit has
            // both. Review of #241, M3: without this the only
            // .projections.json in the whole lane was the orphan the "lost"
            // fixture plants -- the one file for which reading it under the
            // wrong name still lands in the right bucket -- so V4a measured a
            // fixture that could not express the defect it was probing. That
            // is mechanism B occurring inside the instrument built to count
            // mechanism B. A real home has one of these beside every live
            // unit, and under V4a's mutation each becomes a spurious LOST.
            Files.writeString(installed.resolve(r + ".projections.json"), "{}");
        }
        StringBuilder toml = new StringBuilder("schema_version = 1\n");
        for (String l : lock) toml.append("\n[[units]]\nname = \"").append(l).append("\"\n");
        Files.writeString(home.resolve("units.lock.toml"), toml.toString());
        return home;
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------ discovery

    /**
     * {@code HomeFixpointLaw.candidateHomes}, deliberately identical: every
     * distinct existing directory any upstream node published, plus each one's
     * {@code .skill-manager} child.
     */
    private static Set<Path> candidateHomes(List<ContextItem> context) {
        Set<Path> out = new LinkedHashSet<>();
        for (ContextItem item : context) {
            for (String value : item.data().values()) {
                if (value == null || value.isBlank()) continue;
                for (String part : value.split("[,\\n]")) {
                    String trimmed = part.trim();
                    if (trimmed.length() < 2 || !trimmed.startsWith("/")) continue;
                    add(out, Path.of(trimmed));
                    add(out, Path.of(trimmed).resolve(".skill-manager"));
                }
            }
        }
        return out;
    }

    /** {@code HomeFixpointLaw.insideSandbox}, and for its stated reason. */
    private static boolean insideSandbox(Path home) {
        try {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            return home.startsWith(tmp);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void add(Set<Path> out, Path p) {
        try {
            if (Files.isDirectory(p)) out.add(p.toRealPath());
        } catch (IOException | RuntimeException ignored) {
            // unreadable or malformed: not a home we can read
        }
    }

    // -------------------------------------------------------------- process

    private record Run(int exit, String out, String err) {
        String tail() {
            String all = (out + err).strip();
            int from = Math.max(0, all.length() - 1500);
            return all.substring(from);
        }
    }

    private static Run describe(Path cli, Path home) {
        return exec(List.of(cli.toString(), "home", "describe",
                "--home", home.toString(), "--json"), cli, home);
    }

    private static Run exec(List<String> command, Path cli, Path home) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            // Through SmEnv like every other node that spawns the CLI. A node
            // that shells skill-manager without the four agent roots pinned
            // resolves them against the operator's real ~/.claude, which is the
            // shape half this epic is about.
            SmEnv.apply(pb, home.toString(), SmEnv.sandboxUnder(home.resolve("agent-home")));
            // Pin WHICH BUILD the law is about; without it HomeDescriptor's CLI
            // resolution falls through to a PATH walk and finds whatever
            // released skill-manager the developer has.
            pb.environment().put("SKILL_MANAGER_CLI", cli.toString());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            String err = new String(p.getErrorStream().readAllBytes());
            if (!p.waitFor(600, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Run(-1, out, err + "\n[timed out]");
            }
            return new Run(p.exitValue(), out, err);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Run(-1, "", String.valueOf(e.getMessage()));
        }
    }

    private HomeMembershipLaw() {}
}
