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
 *       scan once surfaced one of the OPERATOR'S REAL HOMES. This law never
 *       changes a home's MEMBERSHIP — it runs {@code home describe}, which is
 *       classified {@code READ} and writes no descriptor without
 *       {@code --write} — but {@code READ} is not {@code NONE}
 *       ({@code store.init()} still ensures the layout), and "does not write"
 *       is one refactor away from not being true. The skipped ones are counted
 *       and NAMED rather than silently dropped.</li>
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
                    .assertion("at_least_one_home_was_actually_checked", lookedAtSomething)
                    .metric("homesChecked", checked.size())
                    .metric("unitsObserved", unitsObserved)
                    .metric("homesOutsideSandbox", outsideSandbox.size())
                    .metric("descriptorDrift", descriptorDrift)
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
     * One home's unit set as three readers see it.
     *
     * @param disk    what the home holds, per production's own walk
     * @param records what somebody installed, per {@code installed/}
     * @param lock    what the manifest resolved to, per {@code units.lock.toml}
     */
    record Membership(Set<String> disk, Set<String> records, Set<String> lock) {

        List<String> violations(Path home) {
            List<String> out = new ArrayList<>();
            Set<String> gained = minus(disk, records);
            Set<String> lost = minus(records, disk);
            if (!gained.isEmpty()) {
                out.add(home + " GAINED " + gained + " — present in the home, and no "
                        + "installed/ record names them. A unit nobody installed.");
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
                    + "\n    lock    " + lock;
        }
    }

    private static Membership membership(Path home, String describeJson) {
        return new Membership(
                unitNames(describeJson),
                installedRecords(home.resolve("installed")),
                lockUnits(read(home.resolve("units.lock.toml"))));
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

                List<String> goodV = at(good, Set.of("alpha")).violations(good);
                List<String> gainedV = at(gained, Set.of("alpha", "intruder")).violations(gained);
                List<String> lostV = at(lost, Set.of("alpha")).violations(lost);

                StringBuilder sb = new StringBuilder("SELF-TEST (runs before any real home):");
                sb.append("\n  consistent home -> ").append(goodV.size()).append(" violation(s)");
                sb.append("\n  gained a unit   -> ").append(gainedV.size()).append(" violation(s)");
                sb.append("\n  lost  a unit    -> ").append(lostV.size()).append(" violation(s)");

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
                lockUnits(read(home.resolve("units.lock.toml"))));
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
        for (String r : records) Files.writeString(installed.resolve(r + ".json"), "{}");
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
