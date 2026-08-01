///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES TicketLifecycleSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * <b>Step 4 — close out, concurrently.</b> Both tickets run
 * {@code close-change.sh} at the same time, both are refused, and then both
 * remedies reconcile into the one project home at the same time.
 *
 * <h2>Assert on the lock, not on wall-clock</h2>
 *
 * <p>This is the assertion the whole node is built around, and the obvious way
 * to make it is wrong. A skill-manager process spends 2-3 seconds in
 * jbang/JVM startup <em>before</em> {@code HomeLock.acquire} is reached.
 * Measured on the development machine: the lock file at
 * {@code <home>/.materialization/.home.lock} reads FREE at t=1-2s, HELD at
 * t=3-7s, and FREE afterwards. So two runs whose locked sections are strictly
 * ordered still show overlapping <em>process</em> windows, and a wall-clock
 * oracle over them reports "OVERLAPPED" and means nothing by it. An earlier
 * hand-run of exactly that experiment did report OVERLAPPED, on a pair that
 * was in fact perfectly serialised.
 *
 * <p>So {@code lockprobe.py} reads the lock itself. {@code java.nio}'s
 * {@code FileLock} is a POSIX {@code fcntl} record lock, which is the same
 * primitive {@code F_GETLK} queries — and {@code F_GETLK} names the holding
 * <b>pid</b>, so the attribution of a critical section to a process is the
 * kernel's rather than the test's. A held/free timeline could not tell one long
 * critical section from two adjacent ones, and "two adjacent ones" is exactly
 * the observation this node needs to make.
 *
 * <h2>Contention is arranged, not hoped for</h2>
 *
 * <p>A green result from "we started two syncs and they did not corrupt
 * anything" may mean they never overlapped at all. So the probe takes the lock
 * FIRST and holds it: both syncs are started, both reach
 * {@code HomeLock.acquire} while a third process holds the lock, and both are
 * released at one instant. That also tests something worth testing on its own —
 * that skill-manager waits for a lock held by a process that is not a JVM.
 *
 * <h2>The non-vacuity companion</h2>
 *
 * <p>The verdict function is run three times in this node, over three pairs:
 *
 * <ul>
 *   <li>the two real {@code home sync} runs — must be SERIALISED;</li>
 *   <li>two processes that take the lock properly — must be SERIALISED (the
 *       oracle is not simply saying yes to skill-manager);</li>
 *   <li>two processes that write <em>without</em> taking the lock — must be
 *       reported NOT serialised. This is the assertion's proof that it can
 *       fail. Without it "serialised" is a word the oracle could be printing
 *       unconditionally.</li>
 * </ul>
 *
 * <p>And {@code lockprobe.py selftest} runs before any of it, because the
 * {@code struct flock} layout is platform-specific and a wrong one unpacks the
 * holder pid as 0 — which reads exactly like "free". Every assertion above
 * would then be measuring an empty timeline.
 */
public class TicketLifecycleConcurrentCloseOut {

    static final NodeSpec SPEC = NodeSpec.of("ticket.lifecycle.concurrent.close.out")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("ticket.lifecycle.agent.edits")
            .tags("ticket-lifecycle", "close-out", "home-lock", "concurrency")
            .timeout("1200s")
            .output("syncLogA", "string")
            .output("syncLogB", "string")
            .output("syncExitA", "string")
            .output("syncExitB", "string");

    /** {@code close-change.sh}'s exit code for "the gate stopped this". */
    private static final int REFUSED_BY_THE_GATE = 4;

    /** How long the probe holds the lock while the two syncs start up. */
    private static final int BARRIER_SECONDS = 6;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String checkoutRaw = ctx.get("ticket.lifecycle.fixture.built", "checkout").orElse(null);
            String projectRaw = ctx.get("ticket.lifecycle.fixture.built", "projectHome")
                    .orElse(null);
            String workspaceRaw = ctx.get("ticket.lifecycle.fixture.built", "workspace")
                    .orElse(null);
            String scriptsRaw = ctx.get("ticket.lifecycle.fixture.built", "scriptsDir")
                    .orElse(null);
            String ambient = ctx.get("ticket.lifecycle.fixture.built", "ambientHome").orElse(null);
            if (checkoutRaw == null || projectRaw == null || workspaceRaw == null
                    || scriptsRaw == null || ambient == null) {
                return NodeResult.fail("ticket.lifecycle.concurrent.close.out",
                        "missing upstream context");
            }
            Path checkout = Path.of(checkoutRaw);
            Path project = Path.of(projectRaw);
            Path workspace = Path.of(workspaceRaw);
            Path closeChange = Path.of(scriptsRaw).resolve("close-change.sh");
            Path scratch = workspace.resolve("concurrency");
            Files.createDirectories(scratch);

            // ---------------------------------------------------------------
            // 1. both close-change.sh runs, at the same time, both refused
            // ---------------------------------------------------------------
            LinkedHashMap<String, String> projectBeforeGate =
                    TicketLifecycleSupport.digests(project);

            Path gateLogA = Procs.logFile(ctx, "close-change-a");
            Path gateLogB = Procs.logFile(ctx, "close-change-b");
            Process gateA = TicketLifecycleSupport.spawnScript(ctx, gateLogA, checkout, closeChange,
                    ambient, TicketLifecycleSupport.TICKET_A);
            Process gateB = TicketLifecycleSupport.spawnScript(ctx, gateLogB, checkout, closeChange,
                    ambient, TicketLifecycleSupport.TICKET_B);
            int gateExitA = gateA.waitFor();
            int gateExitB = gateB.waitFor();
            String gateTextA = HomeSyncSupport.read(gateLogA);
            String gateTextB = HomeSyncSupport.read(gateLogB);

            boolean bothWereRefused =
                    gateExitA == REFUSED_BY_THE_GATE && gateExitB == REFUSED_BY_THE_GATE;
            // Each names ITS OWN units: the shared one plus the one only it
            // touched, and not the other ticket's.
            boolean eachRefusalNamesItsOwnUnits =
                    gateTextA.contains("BLOCKED  skill:" + TicketLifecycleSupport.SHARED)
                    && gateTextA.contains("BLOCKED  skill:" + TicketLifecycleSupport.UNIT_A)
                    && !gateTextA.contains("BLOCKED  skill:" + TicketLifecycleSupport.UNIT_B)
                    && gateTextB.contains("BLOCKED  skill:" + TicketLifecycleSupport.SHARED)
                    && gateTextB.contains("BLOCKED  skill:" + TicketLifecycleSupport.UNIT_B)
                    && !gateTextB.contains("BLOCKED  skill:" + TicketLifecycleSupport.UNIT_A);
            // Nothing was removed. A gate that refuses and removes anyway is
            // the failure the script exists to prevent, and the directory being
            // there is the only proof of it that a status line cannot fake.
            boolean neitherWorktreeWasRemoved =
                    Files.isDirectory(TicketLifecycleSupport.worktreeFor(checkout,
                            TicketLifecycleSupport.TICKET_A))
                    && Files.isDirectory(TicketLifecycleSupport.worktreeFor(checkout,
                            TicketLifecycleSupport.TICKET_B));
            // And the gate is a question, not an action.
            boolean theGateWroteNothing = HomeSyncSupport.difference(projectBeforeGate,
                    TicketLifecycleSupport.digests(project)).isEmpty();

            List<String> remediesA = remediesIn(gateTextA);
            List<String> remediesB = remediesIn(gateTextB);
            List<String> unrunnable = new ArrayList<>();
            for (String remedy : concat(remediesA, remediesB)) {
                String head = remedy.strip().split("\\s+")[0];
                if (!head.startsWith("/") || !Files.isExecutable(Path.of(head))) {
                    unrunnable.add(remedy);
                }
            }
            boolean everyRemedyIsRunnable = !remediesA.isEmpty() && !remediesB.isEmpty()
                    && unrunnable.isEmpty();
            if (remediesA.isEmpty() || remediesB.isEmpty()) {
                // Stop here rather than throw three lines down. The whole
                // concurrency experiment is "run what the gate told each ticket
                // to run", and there is nothing honest to run in its place.
                return NodeResult.fail("ticket.lifecycle.concurrent.close.out",
                        "the gate printed no remedy for one of the tickets, so there is nothing to "
                                + "run concurrently; exits=" + gateExitA + "/" + gateExitB
                                + " remediesA=" + remediesA + " remediesB=" + remediesB)
                        .assertion("both_close_change_runs_were_refused_by_the_gate",
                                bothWereRefused)
                        .assertion("every_remedy_the_gate_printed_is_an_absolute_runnable_command",
                                false);
            }

            // ---------------------------------------------------------------
            // 2. the probe proves itself before anything is asserted on it
            // ---------------------------------------------------------------
            Path lock = TicketLifecycleSupport.lockFile(project);
            ProcessRecord probeSelfTest = TicketLifecycleSupport.plain(ctx, "lockprobe-selftest",
                    null, ambient, List.of("python3", TicketLifecycleSupport.lockProbe().toString(),
                            "selftest", scratch.resolve("selftest.lock").toString()));
            boolean theLockProbeCanSeeAHolder = probeSelfTest.exitCode() == 0;

            // ---------------------------------------------------------------
            // 3. the two remedies, concurrently, under a held lock
            // ---------------------------------------------------------------
            Race real = race(ctx, ambient, scratch, "real", lock,
                    argv(remediesA.get(0)), argv(remediesB.get(0)));

            TicketLifecycleSupport.Verdict verdict =
                    TicketLifecycleSupport.verdict(real.samples(), Set.of(real.barrierPid()));
            boolean bothSyncsCompleted = real.exitA() >= 0 && real.exitB() >= 0;
            // The precondition that makes the verdict mean anything: the two
            // processes really were alive at the same time. Without it a
            // "serialised" verdict could just be two runs that never met.
            boolean theTwoRunsGenuinelyOverlapped = real.processWindowsOverlapped();
            boolean bothRunsTookTheHomeLock = verdict.distinctHolders() == 2;
            boolean theirLockedSectionsDidNotOverlap = verdict.disjoint();
            boolean oneRunWaitedForTheOther = verdict.serialised();
            // And the destination is coherent AS A HOME, not merely per unit:
            // every unit directory is present and readable, the installed
            // ledger still parses, and no staging tree was left behind.
            boolean theProjectHomeIsStillAHome = isAHome(project);
            boolean noStagingLeftovers = HomeSyncSupport.stagingLeftovers(project).isEmpty();

            // ---------------------------------------------------------------
            // 4. the same oracle, on a pair that is NOT serialised
            // ---------------------------------------------------------------
            Path decoyHome = scratch.resolve("decoy");
            Files.createDirectories(decoyHome.resolve(".materialization"));
            Path decoyLock = TicketLifecycleSupport.lockFile(decoyHome);
            Path decoyTarget = decoyHome.resolve("written.txt");
            String probe = TicketLifecycleSupport.lockProbe().toString();

            Race unserialised = race(ctx, ambient, scratch, "unserialised", decoyLock,
                    List.of("python3", probe, "write", decoyLock.toString(),
                            decoyTarget.toString(), "2"),
                    List.of("python3", probe, "write", decoyLock.toString(),
                            decoyTarget.toString(), "2"));
            TicketLifecycleSupport.Verdict unserialisedVerdict = TicketLifecycleSupport
                    .verdict(unserialised.samples(), Set.of(unserialised.barrierPid()));
            boolean anUnserialisedPairIsDetected = !unserialisedVerdict.serialised()
                    && unserialisedVerdict.distinctHolders() == 0;

            Race serialised = race(ctx, ambient, scratch, "serialised", decoyLock,
                    List.of("python3", probe, "lockedwrite", decoyLock.toString(),
                            decoyTarget.toString(), "1"),
                    List.of("python3", probe, "lockedwrite", decoyLock.toString(),
                            decoyTarget.toString(), "1"));
            TicketLifecycleSupport.Verdict serialisedVerdict = TicketLifecycleSupport
                    .verdict(serialised.samples(), Set.of(serialised.barrierPid()));
            boolean aProperlyLockedPairIsAccepted = serialisedVerdict.serialised();

            boolean pass = bothWereRefused && eachRefusalNamesItsOwnUnits
                    && neitherWorktreeWasRemoved && theGateWroteNothing && everyRemedyIsRunnable
                    && theLockProbeCanSeeAHolder && bothSyncsCompleted
                    && theTwoRunsGenuinelyOverlapped && bothRunsTookTheHomeLock
                    && theirLockedSectionsDidNotOverlap && oneRunWaitedForTheOther
                    && theProjectHomeIsStillAHome && noStagingLeftovers
                    && anUnserialisedPairIsDetected && aProperlyLockedPairIsAccepted;

            return (pass
                    ? NodeResult.pass("ticket.lifecycle.concurrent.close.out")
                    : NodeResult.fail("ticket.lifecycle.concurrent.close.out",
                            "gateExits=" + gateExitA + "/" + gateExitB
                                    + " namesOwnUnits=" + eachRefusalNamesItsOwnUnits
                                    + " remediesA=" + remediesA + " remediesB=" + remediesB
                                    + " unrunnable=" + unrunnable
                                    + " probeSelfTest=" + probeSelfTest.exitCode()
                                    + " syncExits=" + real.exitA() + "/" + real.exitB()
                                    + " overlapped=" + theTwoRunsGenuinelyOverlapped
                                    + " realVerdict=" + verdict.detail()
                                    + " unserialisedVerdict=" + unserialisedVerdict.detail()
                                    + " serialisedVerdict=" + serialisedVerdict.detail()))
                    .process(probeSelfTest)
                    .assertion("both_close_change_runs_were_refused_by_the_gate", bothWereRefused)
                    .assertion("each_refusal_names_its_own_units_and_not_the_others",
                            eachRefusalNamesItsOwnUnits)
                    .assertion("a_refused_close_change_removes_no_worktree",
                            neitherWorktreeWasRemoved)
                    .assertion("the_close_out_gate_writes_nothing_at_all", theGateWroteNothing)
                    .assertion("every_remedy_the_gate_printed_is_an_absolute_runnable_command",
                            everyRemedyIsRunnable)
                    .assertion("the_lock_probe_can_observe_a_holder_before_it_is_believed",
                            theLockProbeCanSeeAHolder)
                    .assertion("both_concurrent_syncs_ran_to_completion", bothSyncsCompleted)
                    .assertion("the_two_syncs_were_genuinely_alive_at_the_same_time",
                            theTwoRunsGenuinelyOverlapped)
                    .assertion("both_syncs_took_the_project_homes_lock", bothRunsTookTheHomeLock)
                    .assertion("their_locked_sections_do_not_overlap",
                            theirLockedSectionsDidNotOverlap)
                    .assertion("one_sync_waited_for_the_other_rather_than_interleaving",
                            oneRunWaitedForTheOther)
                    .assertion("the_destination_is_still_coherent_as_a_home",
                            theProjectHomeIsStillAHome)
                    .assertion("the_concurrent_pass_left_no_staging_leftovers", noStagingLeftovers)
                    .assertion("the_lock_oracle_detects_a_genuinely_unserialised_pair",
                            anUnserialisedPairIsDetected)
                    .assertion("the_lock_oracle_accepts_a_properly_locked_pair",
                            aProperlyLockedPairIsAccepted)
                    .metric("lockSamples", real.samples().size())
                    .metric("heldSamples", verdict.heldSamples())
                    .metric("distinctHolders", verdict.distinctHolders())
                    .metric("processOverlapMillis", real.overlapMillis())
                    .log("real: " + verdict.detail())
                    .log("unserialised control: " + unserialisedVerdict.detail())
                    .log("serialised control: " + serialisedVerdict.detail())
                    .publish("syncLogA", real.logA().toString())
                    .publish("syncLogB", real.logB().toString())
                    .publish("syncExitA", Integer.toString(real.exitA()))
                    .publish("syncExitB", Integer.toString(real.exitB()));
        });
    }

    // ------------------------------------------------------------------ race

    /** One two-writer experiment: what was sampled, and when each side ran. */
    record Race(List<TicketLifecycleSupport.Sample> samples, long barrierPid,
                long startA, long endA, int exitA,
                long startB, long endB, int exitB,
                Path logA, Path logB) {

        boolean processWindowsOverlapped() {
            return Math.min(endA, endB) > Math.max(startA, startB);
        }

        long overlapMillis() {
            return Math.max(0, Math.min(endA, endB) - Math.max(startA, startB));
        }
    }

    /**
     * Run two commands against one lock file, with the lock held by the probe
     * until both are up, and an {@code F_GETLK} sampler watching throughout.
     *
     * <p>The barrier is what turns a race into an experiment. Started first and
     * released once, it guarantees both writers reach their acquisition while
     * the lock is unavailable — which is the condition under which "did they
     * serialise" is a question with content. Its own pid is returned so the
     * verdict can exclude it: the barrier is the instrument, not a subject.
     */
    private static Race race(com.hayden.testgraphsdk.sdk.NodeContext ctx, String ambient,
                             Path scratch, String tag, Path lock,
                             List<String> commandA, List<String> commandB)
            throws IOException, InterruptedException {
        Path ready = scratch.resolve(tag + ".ready");
        Path release = scratch.resolve(tag + ".release");
        Path stop = scratch.resolve(tag + ".stop");
        Path samples = scratch.resolve(tag + ".samples");
        for (Path stale : List.of(ready, release, stop, samples)) Files.deleteIfExists(stale);
        Files.createDirectories(lock.getParent());
        String probe = TicketLifecycleSupport.lockProbe().toString();

        Process barrier = TicketLifecycleSupport.spawn(ctx, scratch.resolve(tag + ".barrier.log"),
                ambient, List.of("python3", probe, "hold", lock.toString(),
                        ready.toString(), release.toString(), "180"));
        long deadline = System.currentTimeMillis() + 60_000;
        while (!Files.isRegularFile(ready) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        long barrierPid = 0;
        if (Files.isRegularFile(ready)) {
            barrierPid = Long.parseLong(HomeSyncSupport.read(ready).strip());
        }

        Process sampler = TicketLifecycleSupport.spawn(ctx, scratch.resolve(tag + ".sampler.log"),
                ambient, List.of("python3", probe, "sample", lock.toString(), "300",
                        stop.toString(), samples.toString()));

        Path logA = scratch.resolve(tag + ".a.log");
        Path logB = scratch.resolve(tag + ".b.log");
        long startA = System.currentTimeMillis();
        Process a = TicketLifecycleSupport.spawn(ctx, logA, ambient, commandA);
        long startB = System.currentTimeMillis();
        Process b = TicketLifecycleSupport.spawn(ctx, logB, ambient, commandB);

        // Long enough for a JVM to get past startup and be waiting ON the lock
        // rather than still booting — the whole reason the barrier exists.
        Thread.sleep(BARRIER_SECONDS * 1000L);
        Files.writeString(release, "go\n");

        int exitA = a.waitFor();
        long endA = System.currentTimeMillis();
        int exitB = b.waitFor();
        long endB = System.currentTimeMillis();

        Files.writeString(stop, "stop\n");
        sampler.waitFor();
        barrier.waitFor();

        return new Race(TicketLifecycleSupport.readSamples(samples), barrierPid,
                startA, endA, exitA, startB, endB, exitB, logA, logB);
    }

    // --------------------------------------------------------------- helpers

    /**
     * The remedies {@code close-change.sh} rendered, verbatim.
     *
     * <p>Taken from the script's own output rather than from a second
     * {@code home close-out --json} call, because the script's rendering is
     * part of what is under test: it once ran a regex substitution over the
     * CLI's sentence and rewrote the conflicted-file list — where
     * {@code skill-manager.toml} matched the token — into a path in a different
     * repository.
     */
    private static List<String> remediesIn(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (!line.startsWith("run: ")) continue;
            String remedy = line.substring("run: ".length()).strip();
            if (!out.contains(remedy)) out.add(remedy);
        }
        return out;
    }

    /**
     * A remedy as an argument vector.
     *
     * <p>Everything after a two-space run is prose the remedy adds for a human
     * ("then resolve: SKILL.md"); everything before it is the command. Nothing
     * is substituted — the point of executing the remedy is that it is the
     * remedy, not something like it.
     */
    private static List<String> argv(String remedy) {
        String command = remedy.split(" {2}")[0].trim();
        return new ArrayList<>(List.of(command.split("\\s+")));
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /**
     * Whether {@code home} is still a Skill Manager HOME rather than a
     * directory that happens to hold some units.
     *
     * <p>The per-unit swap is already atomic; the risk two interleaved syncs
     * create is a destination that is coherent per unit and incoherent as a
     * home. So this asks the home-level questions: the descriptor, the
     * installed ledger and every unit's manifest are all present and readable.
     */
    private static boolean isAHome(Path home) {
        if (!Files.isDirectory(home.resolve("installed"))
                || !Files.isDirectory(home.resolve("skills"))) return false;
        for (String unit : HomeSyncSupport.names(home.resolve("skills"))) {
            Path dir = home.resolve("skills").resolve(unit);
            if (!Files.isRegularFile(dir.resolve("SKILL.md"))
                    || !Files.isRegularFile(dir.resolve("skill-manager.toml"))) return false;
        }
        for (String entry : HomeSyncSupport.names(home.resolve("installed"))) {
            if (!entry.endsWith(".json")) continue;
            Object parsed = HomeSyncSupport.MiniJson.parse(
                    HomeSyncSupport.read(home.resolve("installed").resolve(entry)).strip());
            if (parsed == null) return false;
        }
        return true;
    }
}
