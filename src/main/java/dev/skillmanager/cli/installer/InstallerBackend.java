package dev.skillmanager.cli.installer;

import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;

public interface InstallerBackend {

    String id();

    boolean available();

    /**
     * Install {@code dep} requested by {@code skillName}. Each backend lands its
     * artifact(s) in {@link SkillStore#cliBinDir()} (via direct copy or symlink)
     * so a user only has to add one directory to PATH.
     *
     * @return what actually happened, so a caller can tell a run that installed
     *         something from a run that found everything already present. A
     *         backend that cannot tell should return
     *         {@link InstallOutcome#INSTALLED}: over-reporting an event costs
     *         one console line, under-reporting one hides work that was done.
     */
    InstallOutcome install(CliDependency dep, SkillStore store, String skillName)
            throws IOException;

    /**
     * Variant for callers that need to force a backend-specific replay.
     * Most backends do not have a replay gate, so the default preserves the
     * existing install behavior.
     */
    default InstallOutcome install(CliDependency dep, SkillStore store, String skillName,
                                   boolean force) throws IOException {
        return install(dep, store, skillName);
    }

    /**
     * Whether {@code dep} is already satisfied for the home rooted at
     * {@code store}, so this backend has nothing to do.
     *
     * <p>Delegates to {@link CliPresence}; see that class for why "is it on
     * PATH" was the wrong question and what replaced it. There used to be an
     * {@code isOnPath(String)} default here and every backend opened with it —
     * which is how one shorthand became four copies of the same defect. It is
     * gone rather than sharpened: a method on the interface every backend
     * implements, named for the check they must not perform, is how this
     * recurs.
     */
    default boolean alreadyProvided(CliDependency dep, SkillStore store) {
        return CliPresence.alreadyProvided(dep, store);
    }

    /**
     * A digest over this backend's declared INPUTS to {@code dep}'s artifact,
     * so a later pass can decide staleness by comparing inputs rather than by
     * checking whether a file exists.
     *
     * <h2>Why this is on the interface and has no default</h2>
     *
     * <p>It used to live nowhere. {@code CliInstallRecorder} carried
     * {@code "skill-script".equals(dep.backend()) ? SkillScriptBackend.fingerprintFor(…) : null}
     * — the recorder reaching past the interface it holds to a static on one
     * concrete adapter — and {@code LiveInterpreter.runCliInstall} carried a
     * second, independently maintained copy of the same branch. Measured on the
     * project home before this change: 9 of 9 {@code skill-script} rows carried
     * {@code install_fingerprint} and <b>0 of 16</b> brew/npm/pip/tar rows did,
     * so four backends out of five answered "am I stale" with
     * {@link #alreadyProvided}, which answers a different question.
     *
     * <p>No default implementation, deliberately. A default returning
     * {@link Fingerprint#gap} would let a new backend inherit exactly the state
     * this method exists to remove, silently, at the moment it is added — and
     * "adding a backend means editing a hardcoded branch in a class that should
     * not know your name" is the finding this replaces. A new backend now
     * declares its scheme or declares its gap, in its own file, and the
     * registry no longer has a name-shaped hole for it.
     *
     * <p>This is NOT the check {@link #alreadyProvided} forbids, and the
     * distinction is the whole ticket: {@code alreadyProvided} asks the disk
     * about an OUTPUT, this asks the declaration about an INPUT. Nothing here
     * may probe {@code bin/cli} to decide the digest — reading what was
     * RESOLVED into a provisioned tree ({@code venvs/<tool>}'s dist-info, an
     * npm prefix's {@code package.json}, a brew cellar's version directory) is
     * reading the identity of the thing installed, which is an input to the
     * next comparison, not a presence proxy for this one.
     *
     * <h2>Contract</h2>
     *
     * <ul>
     *   <li>Build the digest with {@link dev.skillmanager.lock.Fingerprints},
     *       which forces a versioned domain-separation prefix. Every scheme is
     *       {@code <backend>-vN}; changing what a scheme covers means a new
     *       {@code N}, because an in-place edit invalidates every fingerprint
     *       already written to every home's {@code cli-lock.toml}.</li>
     *   <li>Be deterministic and home-independent. Two homes that installed the
     *       same dep from the same declaration must agree, or the digest cannot
     *       cross a tier and ARTI-07 has nothing to compare a clone against.
     *       No absolute paths, no timestamps.</li>
     *   <li>Never throw. An input this backend cannot read is a
     *       {@link Fingerprint#gap} with a sentence saying so.</li>
     * </ul>
     *
     * <p>Recording is universal from this ticket; GATING on the result is not.
     * {@code skill-script} compares the digest to decide whether to re-run its
     * script, because re-running one is expensive and arbitrary. The other four
     * only record, and their install decision is unchanged — turning a recorded
     * digest into a rebuild trigger is the {@code build} verb's job.
     */
    Fingerprint fingerprint(CliDependency dep, SkillStore store, String unitName);
}
