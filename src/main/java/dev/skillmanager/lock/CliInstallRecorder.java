package dev.skillmanager.lock;

import dev.skillmanager.cli.installer.CliArtifact;
import dev.skillmanager.cli.installer.CliPresence;
import dev.skillmanager.cli.installer.CliRefusedException;
import dev.skillmanager.cli.installer.InstallerRegistry;
import dev.skillmanager.cli.installer.ProvisionTally;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.plan.PlanAction;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;

/**
 * Runs every {@link PlanAction.RunCliInstall} in a plan, records each success
 * to the {@link CliLock}, and saves the lock once at the end.
 *
 * <p>Returns a {@link ProvisionTally} rather than printing a summary itself:
 * the handler turns it into a {@link
 * dev.skillmanager.effects.ContextFact.CliInstalledFor} fact and
 * {@code ConsoleProgramRenderer} is still the only place that prints.
 *
 * <h2>This class does not know any backend's name</h2>
 *
 * <p>It used to. The line was
 * {@code "skill-script".equals(rc.dep().backend()) ? SkillScriptBackend.fingerprintFor(…) : null}
 * — the recorder reaching past the {@link InstallerRegistry} it already holds
 * to a static on one concrete adapter, so that one backend of five recorded
 * what its artifact was derived FROM while the other four recorded nothing and
 * fell back to {@code CliPresence.alreadyProvided}, which answers whether a
 * file exists and runs. {@code LiveInterpreter.runCliInstall} carried a second
 * copy of the same branch, maintained separately and identical by hand.
 *
 * <p>{@link #record} is now the one place a lock row is written from a
 * dependency, and both callers use it.
 */
public final class CliInstallRecorder {

    private CliInstallRecorder() {}

    /** What a bulk pass did, and what it refused (#428 R5). */
    public record Result(ProvisionTally tally, java.util.List<CliRefusedException> refusals) {
        public Result {
            refusals = refusals == null ? java.util.List.of() : java.util.List.copyOf(refusals);
        }
    }

    /** {@link #runWithRefusals}'s tally alone, for callers that only count. */
    public static ProvisionTally run(InstallPlan plan, SkillStore store) throws IOException {
        return runWithRefusals(plan, store).tally();
    }

    public static Result runWithRefusals(InstallPlan plan, SkillStore store) throws IOException {
        InstallerRegistry registry = new InstallerRegistry();
        CliLock lock = CliLock.load(store);
        java.util.Map<String, java.util.Map<String, String>> pinsInThisPass = pinsByUnit(plan);
        // #428 R4: a requester that is neither in this pass nor an installed
        // unit still declaring the row is stale. Drop it before anything
        // compares against the row, and persist that with the pass's save.
        CliClaimants.prune(lock, store, claimsByUnit(plan));
        ProvisionTally tally = ProvisionTally.EMPTY;
        java.util.List<CliRefusedException> refusals = new java.util.ArrayList<>();
        for (PlanAction a : plan.actions()) {
            if (!(a instanceof PlanAction.RunCliInstall rc)) continue;
            try {
                // #404 / #405 review, #428 R3: the shared-pin guard now runs
                // inside installOne, so this path and every other one share it.
                tally = tally.plus(registry.installOne(
                        rc.dep(), store, rc.unitName(), rc.forceScripts(), lock,
                        pinsInThisPass.getOrDefault(key(rc.dep()), java.util.Map.of())));
                record(lock, registry, rc.dep(), store, rc.unitName());
            } catch (CliRefusedException refused) {
                // Still printed, and now also returned so the receipt can make
                // the command exit non-zero (#428 R5).
                Log.warn("%s", refused.getMessage());
                tally = tally.withFailure();
                refusals.add(refused);
            } catch (dev.skillmanager.store.WriteOutsideHomeException confined) {
                // NOT ONE DEP'S FAILURE, so not this loop's to absorb.
                //
                // The catch below is deliberate resilience: one dep that cannot
                // be fetched must not stop the other twenty, so it warns and
                // tallies. A write-confinement refusal is a different kind of
                // event -- this install was pointed at a home it was not given,
                // and the same bin/cli is about to be handed to every remaining
                // dep in the plan. Absorbing it would carry on aiming at that
                // home, nineteen more times, and `installCli` would still hand
                // back EffectReceipt.ok so `sync` exited 0.
                //
                // Measured before this arm existed: through CliInstallRecorder
                // .run the refusal returned normally; through installOne
                // directly it threw. Same defect, two verdicts, and the bulk
                // path is the one every `sync` takes.
                //
                // Rethrown rather than tallied so it reaches LiveInterpreter's
                // handler and becomes a FAILED receipt. Being unchecked is NOT
                // what does this -- see WriteOutsideHomeException's javadoc,
                // which used to claim it was.
                throw confined;
            } catch (Exception e) {
                Log.warn("cli: %s failed: %s", rc.dep().name(), e.getMessage());
                tally = tally.withFailure();
            }
        }
        lock.save(store);
        return new Result(tally, refusals);
    }

    /**
     * Write {@code dep}'s row: its identity, the {@link Fingerprint} its own
     * backend computed over its declared inputs, and the binary the declaring
     * unit says this row produces.
     *
     * <p>The caller saves the lock — {@code run} saves once at the end of a bulk
     * pass and the single-dep effect path saves immediately — so this does not.
     *
     * @return the row as written
     */
    public static CliLock.Entry record(CliLock lock, InstallerRegistry registry,
                                       CliDependency dep, SkillStore store, String unitName) {
        RequestedVersion.Requested req = RequestedVersion.of(dep);
        CliLock.Entry row = lock.recordInstall(dep.backend(), req.tool(), req.version(), dep.spec(),
                declaredHash(dep), unitName,
                registry.fingerprintFor(dep, store, unitName),
                producedBinary(dep));
        // #404: the declaration this install was made FROM, so the next pass can
        // tell a bumped pin from an unchanged one (CliPin.moved). Written after
        // recordInstall because that method keeps an existing row whole when the
        // version is unchanged -- including its old spec -- and a tar url bump at
        // the same min_version is exactly that case.
        CliLock.Entry declared = row.withDeclaration(dep.spec(), CliPin.of(dep))
                .withProvided(provenance(dep, store));
        lock.put(declared);
        return declared;
    }

    /**
     * #409: how {@code dep} is satisfied right now — {@link CliLock#PROVIDED_HOME}
     * when this home holds a usable {@code bin/cli} artifact for it,
     * {@link CliLock#PROVIDED_SYSTEM} when it does not and
     * {@link CliPresence#providedOutsideEveryHome} finds its {@code on_path} on
     * PATH outside every Skill Manager home, else null.
     *
     * <p>Read off the disk after the backend ran rather than from the backend's
     * {@code InstallOutcome}, because {@code ALREADY_PRESENT} conflates the two
     * answers {@link CliPresence#alreadyProvided} keeps apart — "this home
     * already provisioned it" and "the system provides it" — and only the
     * second means {@code bin/cli} is not expected to hold anything. Home first:
     * a shim this home DOES hold is this home's artifact whatever else is on
     * PATH, and must stay reported if it is later deleted.
     *
     * <p>Null when neither holds (a skipped backend, a failed probe) — the row
     * then claims nothing about provenance, which {@code ArtifactBackfill}
     * treats as a pre-#409 row rather than as either answer.
     */
    public static String provenance(CliDependency dep, SkillStore store) {
        String binary = producedBinary(dep);
        if (binary != null && CliArtifact.inHome(store, binary).usable()) return CliLock.PROVIDED_HOME;
        if (CliPresence.providedByThisHome(dep, store) != null) return CliLock.PROVIDED_HOME;
        if (CliPresence.providedOutsideEveryHome(dep.onPath()) != null) return CliLock.PROVIDED_SYSTEM;
        return null;
    }

    /**
     * The binary name this dep's artifact lands under in {@code bin/cli/}.
     *
     * <p>A target's declared {@code binary} first, then {@code on_path}, then
     * nothing rather than a guess. brew and npm link every executable in a
     * package prefix, so a row's tool name is frequently not a binary at all,
     * and recording it as one would put a fabricated path into the record that
     * {@code ArtifactBackfill} probes.
     *
     * <p><b>ARTI-08 reversed the first two.</b> They used to read
     * "{@code on_path} first, because it is what the declaring unit asserts it
     * needs on PATH" — which is true and is an answer to a different question.
     * {@code on_path} is the name this dep PROBES for; {@code install.<os>.binary}
     * is the name the install PRODUCES. They are usually equal and they are
     * allowed to differ, and where they differ the old order recorded a
     * {@code binary} the install never wrote: the artifact's output was a path
     * that does not exist, so the tree the install actually created was
     * credited to nobody and survived every teardown. Measured on
     * {@code test_graph/fixtures/skill-script-skill}, whose {@code on_path} is
     * deliberately a name nothing provides so that the install always runs.
     */
    /** backend:tool -> (unit -> declared pin), for every governed CLI dep this pass installs. */
    static java.util.Map<String, java.util.Map<String, String>> pinsByUnit(InstallPlan plan) {
        java.util.Map<String, java.util.Map<String, String>> out = new java.util.HashMap<>();
        for (PlanAction a : plan.actions()) {
            if (!(a instanceof PlanAction.RunCliInstall rc) || !CliPin.governs(rc.dep())) continue;
            out.computeIfAbsent(key(rc.dep()), k -> new java.util.HashMap<>())
                    .put(rc.unitName(), CliPin.of(rc.dep()));
        }
        return out;
    }

    /**
     * #428 R4 on the single-dependency paths (the install effect and
     * {@code build}): drop stale requesters from {@code cli-lock.toml}, with
     * {@code unitName}'s declaration of {@code dep} as the only pass. Saves only
     * when something was pruned. Best effort: a lock that cannot be read or
     * written is left alone, and the guard still ignores stale requesters.
     */
    public static void pruneStaleRequesters(SkillStore store, String unitName, CliDependency dep) {
        try {
            CliLock lock = CliLock.load(store);
            java.util.Map<String, java.util.Map<String, String>> pass = new java.util.HashMap<>();
            if (unitName != null && dep != null) {
                pass.put(key(dep), java.util.Map.of(unitName, CliPin.of(dep)));
            }
            if (!CliClaimants.prune(lock, store, pass).isEmpty()) lock.save(store);
        } catch (IOException | RuntimeException e) {
            Log.warn("cli: could not prune stale requesters from %s: %s",
                    CliLock.FILENAME, e.getMessage());
        }
    }

    /** Like {@link #pinsByUnit}, for every CLI dep in the pass, governed or not. */
    static java.util.Map<String, java.util.Map<String, String>> claimsByUnit(InstallPlan plan) {
        java.util.Map<String, java.util.Map<String, String>> out = new java.util.HashMap<>();
        for (PlanAction a : plan.actions()) {
            if (!(a instanceof PlanAction.RunCliInstall rc)) continue;
            out.computeIfAbsent(key(rc.dep()), k -> new java.util.HashMap<>())
                    .put(rc.unitName(), CliPin.of(rc.dep()));
        }
        return out;
    }

    private static String key(CliDependency dep) {
        return dep.backend() + ":" + RequestedVersion.of(dep).tool();
    }

    private static String producedBinary(CliDependency dep) {
        for (var target : dep.install().values()) {
            if (target.binary() != null && !target.binary().isBlank()) return target.binary();
        }
        if (dep.onPath() != null && !dep.onPath().isBlank()) return dep.onPath();
        return null;
    }

    private static String declaredHash(CliDependency dep) {
        for (var t : dep.install().values()) if (t.sha256() != null) return t.sha256();
        return null;
    }
}
