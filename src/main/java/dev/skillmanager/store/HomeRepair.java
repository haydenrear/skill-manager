package dev.skillmanager.store;

import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.artifacts.ArtifactLedger;
import dev.skillmanager.launch.LauncherShims;
import dev.skillmanager.launch.RunningCli;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Detection and repair for a home that has ALREADY been damaged.</b>
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>HIS-13 / issue #159. Every other guard in this area <em>prevents</em>:
 * {@code WriteConfinement} stops the next out-of-home write, {@link
 * HomeProvenance} stops the next clone pruning its inherited toolchain,
 * {@code Confinement} stops a {@code project} verb escaping into another
 * checkout. None of them help a home that already took the damage — and this
 * machine has had several. The operator's root home was mis-anchored twice
 * during one epic, and <b>both times it was found by a person noticing an odd
 * path in a file, not by any command.</b> {@code HomeCommand} shipped
 * {@code clone}, {@code verify}, {@code describe}, {@code policy},
 * {@code shims}, {@code drift}, {@code sync}, {@code close-out} and
 * {@code refresh-plugins}, and nothing that repaired.
 *
 * <h2>Five conditions, and why {@code home verify} sees none of them</h2>
 *
 * <p>The ticket named three. Measuring them produced five: DEF-012's shape is
 * NOT the "another home's absolute path" it is tabulated as (see
 * {@link Kind#DANGLING_CLI_PIN}), and HIS-9's disclosed limitation — a
 * {@code bin/cli} that is itself a link out of the home — had to be recognised
 * here whether or not it was reported, because {@code Files.isDirectory}
 * follows it and an unguarded walk would describe another home's files as this
 * one's damage.
 *
 * <table>
 *   <caption>damage vs. the instrument</caption>
 *   <tr><th>damage</th><th>{@code home verify}</th><th>why</th></tr>
 *   <tr><td>an agent skill link repointed into another home's store</td>
 *       <td>MISSES</td>
 *       <td>{@code verifyRoots} walks the STORE. The agent directories are the
 *           home's OTHER axis and sit beside it, so nothing walked them at
 *           all — and a link that resolves was never asked WHICH store it
 *           resolves into.</td></tr>
 *   <tr><td>a shim rewritten with another home's absolute paths</td>
 *       <td>MISSES</td>
 *       <td>it resolves fine; it is simply wrong. {@code verify} reports
 *           references that do NOT resolve
 *           ({@link HomeCloner.Verification#unresolved()}) and references back
 *           to a {@code --against} source. A live path into a third home that
 *           exists is neither. DEF-012's shape.</td></tr>
 *   <tr><td>an entry point pruned that the descent record says was inherited</td>
 *       <td>MISSES</td>
 *       <td>nothing compared the live tree to {@code home.provenance.json}.</td></tr>
 * </table>
 *
 * <p>{@code home verify} does find a link that does not <em>resolve</em>. That
 * is a fourth shape, not one of these, and it is the one that was never the
 * expensive one: a dangling link fails at exec time and says so. The three above all
 * present as a working home.
 *
 * <h2>DETECTION NEVER REPAIRS — and that is a load-bearing property</h2>
 *
 * <p>DEF-067: {@code HomeFixpointLaw} parses the remedy out of a refusal and
 * <em>runs</em> it, so it can silently repair the condition it was checking and
 * report PASS. An observer that repairs is no longer an observer. This class
 * ships the repairer, so the split is structural rather than a convention:
 * {@link #detect} opens no file for writing, and {@link #repair} is the only
 * entry point that mutates. {@code home repair} without {@code --fix} calls
 * only the former, and a damaged home stays damaged and stays red across any
 * number of detection runs.
 *
 * <h2>ONE READER, not a fourth one</h2>
 *
 * <p>GOAL-one-home-one-answer. "Which home is this path in, and may this home
 * own it" is asked of {@link HomeCloner#unsanctionedForeignHome} — the same
 * predicate {@code home verify} refuses on and {@code InstallerRegistry}
 * exempts on — in every one of the five checks below. The <em>surface</em> is
 * new (the agent axis, and the text of a regular-file shim); the
 * <em>verdict</em> is production's existing one. Nothing here re-derives "is
 * that another home", and nothing here decides a sanction: {@link
 * HomeProvenance#sanctions} does, live, and a store it will not re-derive
 * sanctions nothing and is repaired from nothing.
 *
 * <h2>What a repair may touch</h2>
 *
 * <p>Every write goes through a {@link WriteConfinement} scope naming BOTH
 * AXES of the home being repaired — its store, and the three agent directories
 * structurally derived from it. Not {@link WriteConfinement#forHome}, whose
 * single root is the store: an agent-link repair legitimately writes into
 * {@code <homeRoot>/.claude/skills}, which is outside the store, and widening
 * the scope to {@code <homeRoot>} instead would permit the whole of {@code $HOME}
 * for a root-tier home — the exact blast radius this ticket exists because of.
 * The roots are the home's own two axes and nothing else.
 *
 * <p>The agent directories are derived from the STORE PATH
 * ({@link AgentHomes#homeRootFor} then {@link AgentHomes#agentDirsUnder}), never
 * from the ambient environment. That is HIS-14's defect stated as a rule: a
 * command told "home X" that reads {@code CLAUDE_CONFIG_DIR} for the agent half
 * repairs one home's store and rewrites another home's links, which is how 24
 * and then 38 of the operator's global skill links came to be repointed. There
 * is a test that plants a decoy in the environment and asserts it is untouched.
 */
public final class HomeRepair {

    private HomeRepair() {}

    /** What this repair is called in a confinement refusal. */
    private static final String WHAT = "home repair";

    /** The agent subdirectories a home projects units into. */
    private static final List<String> PROJECTED_DIRS = List.of("skills", "plugins");

    /**
     * The shim directories, home-relative, in the spelling findings report.
     *
     * <p>{@link HomeCloner#SHIM_DIR_NAMES}, not a second copy. Review of PR
     * #256, MAJOR-1: this class and {@code HomeCloner} each declared the list,
     * in different spellings, and the divergence reached the verdict.
     */
    private static final List<String> SHIM_DIRS = HomeCloner.SHIM_DIR_NAMES;

    /** Bigger than this and it is not a shim script; do not read it. */
    private static final long TEXT_LIMIT = 1024L * 1024L;

    /** What kind of damage a finding is. */
    public enum Kind {
        /**
         * An agent skill/plugin projection that resolves into another home's
         * store. Issue #159's original measurement: 24 links under the
         * operator's {@code ~/.claude/skills} repointed into a worktree's home,
         * then a further 38 into a sibling worktree.
         */
        MISANCHORED_AGENT_LINK,

        /**
         * A shim that is a regular file whose TEXT names a live path inside
         * another home. It runs, so nothing that checks for exec-ability or
         * resolution sees anything, and it runs the wrong home's program.
         *
         * <p>Also carries the {@code bin/cli} DIRECTORY case — HIS-9's
         * disclosed limitation — as ONE finding about the directory rather than
         * one per entry seen through the link. Reported and never repaired; the
         * only mechanical repair discards every entry point the home can reach,
         * which is the destructive recovery this class serves against.
         */
        FOREIGN_PATH_IN_SHIM,

        /**
         * An entry point this home DECLARES, does not hold, and whose still
         * re-derivable parent store does hold — the residue of a prune that
         * deleted an inherited artifact.
         */
        PRUNED_INHERITED_ENTRY,

        /**
         * The home's own front door names a build that is gone.
         *
         * <h3>Why this is a fourth kind and not a stretched third</h3>
         *
         * <p>The ticket tabulates three shapes and names DEF-012 as an instance
         * of {@link #FOREIGN_PATH_IN_SHIM}. <b>Measured, it is not.</b> What
         * DEF-012 actually recorded, on this machine, the day after a
         * {@code brew upgrade skill-manager 0.23.0 -> 0.24.0}:
         *
         * <pre>
         *   ~/.skill-manager/bin/cli/skill-manager line 117:
         *     cli="${SKILL_MANAGER_CLI:-/opt/homebrew/Cellar/skill-manager/0.23.0/…}"
         *   ls /opt/homebrew/Cellar/skill-manager/  ->  0.24.0     (0.23.0 is GONE)
         *   home verify --home ~/.skill-manager     ->  exit 0, "every reference … resolves"
         * </pre>
         *
         * <p>That path is not in another home; it is in no home, and it does
         * not exist. {@code home verify} is blind to it because
         * {@code underProvisionableRoot} only considers references falling
         * UNDER the home root, which is defensible for leak detection and
         * indefensible for "is this home healthy". DEF-012's own disposition
         * assigns the detection half here and says what it needs:
         * <i>"HIS-13's detection must cover references that LEAVE the home, not
         * only those inside it."</i>
         *
         * <p>Reported as its own kind rather than folded in, because a reader
         * meeting one line has to be able to tell "this home runs another
         * home's program" from "this home runs nothing at all", and because
         * the repairs are different commands.
         *
         * <p>Scoped to the ONE line {@link dev.skillmanager.launch.LauncherShims#pinnedCliIn}
         * parses, not to any absolute path leaving the home. A general "names
         * something outside this home that is not there" rule would fire on
         * every comment, every commented-out path and every optional tool a
         * wrapper mentions — and a rule that fires on those is a rule somebody
         * switches off. Production already has a reader for this exact line
         * ({@code danglingPinIn}, HIS-12), so this consults it rather than
         * growing a second one.
         */
        DANGLING_CLI_PIN
    }

    /**
     * One damaged thing, named with the repair for it.
     *
     * @param subject   home-relative path of the damaged entry, in the
     *                  spelling a reader can pass back to a command
     * @param detail    what is wrong, naming the OTHER home where there is one
     * @param remedy    what would fix this one finding
     * @param repairable whether {@link #repair} can carry that remedy out. A
     *                  finding this class cannot fix is still reported — the
     *                  alternative is a detector whose coverage is defined by
     *                  its repairer, which is how a check comes to be narrower
     *                  than the condition it decides (DEF-046)
     * @param target    the path a repair would point {@code subject} at, or
     *                  null when there is none
     */
    public record Finding(Kind kind, String subject, String detail, String remedy,
                          boolean repairable, Path target) {

        @Override
        public String toString() {
            return kind + " " + subject + " — " + detail;
        }
    }

    /**
     * What one detection run saw.
     *
     * @param examined how many entries were looked at. Reported because a
     *                 clean verdict over ZERO subjects is not a clean home, it
     *                 is a check that ran out of scope — mechanism B in this
     *                 epic's vacuity ledger, and the reason
     *                 {@code HomeIntegrity.Report} carries the same field
     */
    public record Report(Path home, int examined, List<Finding> findings) {

        public Report {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }

        /** True when nothing in this home is damaged in any of the three ways. */
        public boolean clean() { return findings.isEmpty(); }

        /** The findings {@link #repair} can carry out. */
        public List<Finding> repairable() {
            return findings.stream().filter(Finding::repairable).toList();
        }

        /** The findings a person still has to decide about. */
        public List<Finding> unrepairable() {
            return findings.stream().filter(f -> !f.repairable()).toList();
        }
    }

    /**
     * What one repair run did, with detection's answer from BEFORE and AFTER.
     *
     * <p>Both, and separately, because "repair works" is exactly the claim
     * {@code before.clean() == false && after.clean() == true} makes and
     * nothing weaker does. A repairer that reports its own success is the
     * remedy-that-does-not-work class (#142) with a nicer message.
     */
    public record Outcome(Report before, List<Finding> repaired, List<String> failed,
                          Report after) {

        public Outcome {
            repaired = repaired == null ? List.of() : List.copyOf(repaired);
            failed = failed == null ? List.of() : List.copyOf(failed);
        }

        /** True when this run changed nothing — the second run of an idempotent repair. */
        public boolean noop() { return repaired.isEmpty() && failed.isEmpty(); }
    }

    // ---------------------------------------------------------- detection

    /**
     * Name everything damaged in the home whose STORE is {@code store}.
     *
     * <p><b>Opens nothing for writing.</b> See the class javadoc: this is the
     * observer, and DEF-067 is what happens when an observer is allowed to
     * repair.
     */
    public static Report detect(Path store) {
        return detect(store, RunningCli.locateOrNull());
    }

    /**
     * As {@link #detect(Path)}, with the build a broken CLI pin would be
     * re-pinned at supplied.
     *
     * <p>Exists for the reason {@link dev.skillmanager.launch.LauncherShims#write(SkillStore, Path)}
     * does: the DETECTION of a dead pin and the RESOLUTION of the running build
     * are separate questions, and an in-process test cannot answer the second —
     * {@code RunningCli.locateOrNull()} returns null under a test runner, which
     * would silently downgrade every {@link Kind#DANGLING_CLI_PIN} finding to
     * unrepairable and make the idempotence assertion pass over a repair that
     * never ran. That is mechanism C: the probe green because the code was
     * never reached.
     *
     * @param cliPin the live build, or null when it cannot be located — in
     *               which case the finding is still REPORTED and carries the
     *               {@code home shims} spelling as its remedy
     */
    public static Report detect(Path store, Path cliPin) {
        Path root = store.toAbsolutePath().normalize();
        List<Finding> findings = new ArrayList<>();
        int examined = 0;
        examined += misanchoredAgentLinks(root, findings);
        examined += foreignPathsInShims(root, findings);
        examined += prunedInheritedEntries(root, findings);
        examined += danglingCliPin(root, cliPin, findings);
        return new Report(root, examined, findings);
    }

    /**
     * The home's own agent directories, structurally derived from its store.
     *
     * <p>Never {@link AgentHomes#agentHomeRoot()}, which consults the
     * environment. A repair that asks the environment where the agent half of
     * "this home" lives is the two-axis defect (#145 / DEF-029 / HIS-14) with
     * write access.
     */
    public static List<Path> agentDirsOf(Path store) {
        return AgentHomes.agentDirsUnder(AgentHomes.homeRootFor(store.toAbsolutePath().normalize()));
    }

    /**
     * Shape 1 — a projection that resolves into another home's store.
     *
     * <p>The link RESOLVES, which is why {@code home verify}'s dangling-link
     * check never had anything to say about it, and why the operator's global
     * links looked perfectly healthy for the whole time they were pointing at a
     * worktree.
     */
    private static int misanchoredAgentLinks(Path store, List<Finding> findings) {
        Path homeRoot = AgentHomes.homeRootFor(store);
        int examined = 0;
        for (Path agentDir : agentDirsOf(store)) {
            for (String kind : PROJECTED_DIRS) {
                Path dir = agentDir.resolve(kind);
                if (!Files.isDirectory(dir)) continue;
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                    for (Path entry : entries) {
                        if (!Files.isSymbolicLink(entry)) continue;
                        examined++;
                        String rel = relative(homeRoot, entry);
                        Path foreign = HomeCloner.unsanctionedForeignHome(rel, entry, store);
                        if (foreign == null) continue;
                        String name = entry.getFileName().toString();
                        Path mine = store.resolve(kind).resolve(name);
                        boolean held = Files.exists(mine, LinkOption.NOFOLLOW_LINKS);
                        findings.add(new Finding(Kind.MISANCHORED_AGENT_LINK, rel,
                                "resolves into the store at " + foreign
                                        + (held ? "" : ", and this home does not hold "
                                                + kind + "/" + name),
                                held ? "re-point it at " + mine
                                        : "this home does not hold " + kind + "/" + name
                                                + " — install it here, or remove the link",
                                held, held ? mine : null));
                    }
                } catch (IOException cannotList) {
                    Log.detail("home repair: could not list %s (%s)", dir, cannotList.getMessage());
                }
            }
        }
        return examined;
    }

    /**
     * Shape 2 — a regular-file shim whose text names a live path in another home.
     *
     * <h3>Why the text and not the link</h3>
     *
     * <p>A home holds two shim shapes and they fail differently. A SYMLINK into
     * another home is what {@code home verify} already refuses. A generated
     * WRAPPER is a regular file that execs an absolute path, and every check
     * built on {@code Files.isExecutable} or on link resolution passes over it
     * — measured on one clone, {@code skill-dev} (a symlink) self-healed and
     * {@code computeq} (a wrapper) was skipped forever, same home, same sync.
     *
     * <h3>The extraction is new; the VERDICT is not</h3>
     *
     * <p>Candidate absolute paths are pulled out of the text, and then each one
     * is handed to {@link HomeCloner#unsanctionedForeignHome} with the shim's
     * own home-relative location — so a wrapper that execs its <em>sanctioned</em>
     * parent-store entry is accepted for exactly the reason the symlink form of
     * the same thing is, and by the same code. A candidate that does not exist
     * is not this check's business: that is a broken reference and
     * {@code home verify} already reports it.
     */
    private static int foreignPathsInShims(Path store, List<Finding> findings) {
        int examined = 0;
        // HIS-21, revised at review of PR #256 (MAJOR-1). The ENUMERATION now
        // lives in HomeCloner.scanShimDirs beside the extraction rule and the
        // verdict, because listing with a directory stream here and with
        // walkFileTree there made the two readers disagree about a home whose
        // bin/cli is a symlink -- DEF-104's shape one link down. What is left
        // in this method is what is genuinely this command's own: the remedy
        // text, and what it says about a shim directory it will not descend.
        for (HomeCloner.ShimDirScan scan : HomeCloner.scanShimDirs(store)) {
            String dir = scan.dir();
            examined += scan.examined();
            Path outward = scan.outwardLink();
            // THE SHIM DIRECTORY ITSELF IS A LINK OUT OF THE HOME.
            //
            // HIS-9's disclosed limitation, and it is this ticket's declared
            // measurement target: "a home whose bin/cli is a symlink at another
            // home's can no longer be synced at all until a person repairs it by
            // hand." Nothing NAMED that condition. `home verify` reports the
            // FOREIGN_HOME leak on the entries it finds through the link, which
            // reads as several broken shims rather than as one broken
            // directory, and `sync` refuses with a confinement message about a
            // path rather than about the home.
            //
            // Checked FIRST, and the walk stops here, for a reason beyond
            // reporting: `Files.isDirectory` follows the link, so descending
            // would list the OTHER home's entries, report them under this
            // home's `bin/cli/<name>` spelling, and offer to rewrite them. The
            // write gate would refuse (`checkWrite` resolves the leaf), so the
            // damage would be noise rather than bytes — but a detector that
            // reads another home's files to describe this one is the
            // container-versus-entry distinction WriteConfinement records as
            // "getting it wrong is silent", and it is silent here too.
            //
            // NOT REPAIRABLE, and that is a decision rather than a gap. The
            // only mechanical repair is to delete the link and put an empty
            // directory in its place, which discards every shim this home can
            // currently reach — destructive recovery, which is the goal this
            // ticket serves under. So it is REPORTED, with the two exits a
            // person can take, and HIS-9's sentence stays true with the word
            // "invisible" removed from it.
            if (outward != null) {
                findings.add(new Finding(Kind.FOREIGN_PATH_IN_SHIM, dir,
                        "is not a directory in this home at all — it is a link at "
                                + outward + ", so every shim this home appears to hold "
                                + "belongs to that one, and a sync here would list and "
                                + "delete through it",
                        "no command repairs this: replacing the link discards every entry "
                                + "point the home can currently reach. Move the entries you "
                                + "want into a real " + dir + " directory, or re-clone the "
                                + "home from its source",
                        false, null));
                continue;
            }
            for (HomeCloner.ForeignShimPath found : scan.foreign()) {
                Path candidate = found.candidate();
                Path foreign = found.foreign();
                Path mine = mappedIntoThisHome(foreign, candidate, store);
                boolean fixable = mine != null;
                findings.add(new Finding(Kind.FOREIGN_PATH_IN_SHIM, found.rel(),
                        "runs " + candidate + ", which is inside the home at " + foreign,
                        fixable ? "rewrite that path to " + mine
                                : "no path under this home stands where " + candidate
                                        + " does — rebuild the entry point with "
                                        + "`skill-manager build`, or re-install its unit",
                        fixable, mine));
            }
        }
        return examined;
    }

    /**
     * The path in THIS home standing where {@code candidate} stands in
     * {@code foreign}, or null when there is none.
     *
     * <p>Null is not a failure to try harder — it is the honest answer, and the
     * finding says so rather than inventing a rewrite. A shim naming
     * {@code <other>/venvs/x/bin/y} where this home has no {@code venvs/x}
     * cannot be fixed by editing text; it needs the artifact built. Guessing
     * would produce a shim that resolves nowhere, which is strictly worse than
     * one that resolves into the wrong home: the first fails at exec time with
     * no explanation, and #142's lesson is that a remedy that does not work is
     * worse than none.
     */
    private static Path mappedIntoThisHome(Path foreign, Path candidate, Path store) {
        Path rel;
        try {
            // REALIZED on both sides. `foreignHomeReachedBy` resolves before it
            // answers, so `foreign` comes back as /private/var/... on macOS
            // while the token in the shim's text is spelled /var/..., and
            // relativizing the two spellings produces a ../../.. walk that
            // reads as "not under it". Measured: every finding on a temp-path
            // fixture came back unrepairable, which is the substring-compatible
            // spelling trap (ledger row 3) one directory separator over.
            rel = Fs.realOrNormalized(foreign)
                    .relativize(Fs.realOrNormalized(candidate));
        } catch (IllegalArgumentException notUnderIt) {
            return null;
        }
        if (rel.toString().isEmpty() || rel.startsWith("..")) return null;
        Path mine = store.resolve(rel);
        return Files.exists(mine, LinkOption.NOFOLLOW_LINKS) ? mine : null;
    }

    /**
     * Shape 3 — an entry point this home declares, does not hold, and whose
     * recorded parent store still does.
     *
     * <h3>The two records, and why it takes both</h3>
     *
     * <p>{@link ArtifactLedger} says what this home DECLARES; the outputs it
     * records are home-relative, so {@code bin/cli/tlc2} means this home is
     * meant to have that entry point. {@link HomeProvenance} says which stores
     * this home INHERITED from — and {@code parentStores} exists for exactly
     * this, its javadoc having reserved it for "reporting and repair (HIS-13)"
     * since HIS-10 demoted it from deciding anything.
     *
     * <p><b>THE PARAGRAPH THAT USED TO BE HERE WAS FALSE, and the review of
     * PR #244 measured it false.</b> It said: <i>"'This home declares an entry
     * it does not have' is a normal state … 'the parent has a tool this home
     * does not' is normal too … the conjunction is not normal: it is a shim
     * that was here, was inherited, and is gone."</i> <b>A lazy home satisfies
     * the conjunction from birth.</b> Every clone this program makes is in
     * exactly that state for every artifact it deferred, and the first thing
     * this check did on a stock clone was call it damaged.
     *
     * <p>So there is a THIRD condition, and it is the one that decides:
     * {@link HomePolicy#lazyArtifacts} must be FALSE. In a home that deferred
     * nothing, a declared entry point that is absent is abnormal. In a home
     * that deferred, it is the whole point. That is
     * {@link HomeCloner#partitionDeclared}'s rule read in the accusing
     * direction, and it is production's existing answer rather than a fourth
     * one — which is what {@code GOAL-one-home-one-answer} asks of this class
     * and what the first version of this method did not do.
     *
     * <h3>Fail closed on a store that will not re-derive</h3>
     *
     * <p>The snapshot is a CLAIM. {@link HomeProvenance#sanctions} is asked
     * about each recorded store, live, and one that no longer re-derives is
     * skipped entirely — no finding, and therefore no repair that would link
     * this home at a store nothing says it may share. That is the #228 review's
     * finding applied to the repair side: a record must not be able to grant
     * what it names.
     */
    private static int prunedInheritedEntries(Path store, List<Finding> findings) {
        // THE POLICY GATE, AND IT IS THE HALF THIS SHIPPED WITHOUT.
        //
        // Review of PR #244, blocker 1, measured on stock `home clone` output:
        // a freshly cloned, untouched, healthy home was reported DAMAGED.
        //
        //   home clone            exit 0  "no path in it reaches another home"
        //   home verify --home C  exit 0
        //   home repair --home C  exit 1  PRUNED_INHERITED_ENTRY bin/cli/tofu
        //
        // The javadoc below used to argue that "declared AND missing AND the
        // parent has it" was not a normal state. IT IS: a LAZY HOME satisfies
        // that conjunction FROM BIRTH. `bin/cli/tofu` was never in that clone
        // and was never pruned; the clone deferred it on purpose, and the
        // clone's own policy file says so in prose.
        //
        // Worse, the verdict depended on the OPERATOR'S MACHINE. Eight
        // artifacts were declared-only in that clone and exactly one fired,
        // because the root store happened to hold that one binary. On a
        // machine where helm/docker/k3d had been built, the same untouched
        // clone reports five.
        //
        // So this asks the question `HomeCloner.partitionDeclared` already
        // asks, in its words: "BOTH conditions, and neither alone... without
        // the policy test it would excuse them in the operator root, where
        // nothing deferred anything". Read the other way round, which is this
        // method's direction: without the policy test it ACCUSES them in every
        // home that deferred something, which is every home but the root.
        //
        // WHAT THIS COSTS, stated rather than hidden: on a lazy home this
        // check now says nothing, and a genuinely pruned inherited shim there
        // -- HIS-9's measured incident -- is INDISTINGUISHABLE from a deferred
        // one with the records that exist. Neither the ledger, the lock file
        // nor the descent record remembers that a path was once present.
        // Filed as DEF-073. A check that cannot tell the two apart must not
        // guess, and guessing in the direction of "damage" is the spurious
        // hold-back this ticket's own clause 3 forbids.
        try {
            if (HomePolicy.lazyArtifacts(new SkillStore(store))) return 0;
        } catch (IOException unreadablePolicy) {
            // Symmetrical with partitionDeclared's "an unreadable policy is not
            // a licence to excuse anything", inverted for a method that
            // accuses: an unreadable policy is not a licence to ACCUSE
            // anything. Fail towards silence -- this is the arm that produces
            // a repair which writes links into another store.
            Log.detail("home repair: could not read %s's policy (%s); not reporting "
                    + "declared-and-absent entry points", store, unreadablePolicy.getMessage());
            return 0;
        }
        List<Path> parents = new ArrayList<>();
        for (Path recorded : HomeProvenance.recordedParentStores(store)) {
            // THE SAME DISJUNCTION `sanctionedParentShim` DECIDES ON, and not
            // half of it. `isChildOf` is the one-level relation — the parent's
            // own registry claims this home — and `sanctions` re-derives a
            // longer chain for a copy of a child. Asking only the second was
            // measured reporting nothing for a home that IS a direct child:
            // `sanctions` walks `clonedFrom` and then asks whether that hop is
            // a child of the store, so for a first-generation child it asks
            // "is the parent a child of itself" and correctly says no.
            if (dev.skillmanager.bindings.ChildHomeLink.isChildOf(store, recorded)
                    || HomeProvenance.sanctions(store, recorded)) {
                parents.add(recorded);
            }
        }
        if (parents.isEmpty()) return 0;

        List<String> declared;
        try {
            declared = new ArrayList<>();
            for (ArtifactLedger.Row row : ArtifactLedger.load(new SkillStore(store)).rows()) {
                for (String output : row.outputs()) {
                    String normalized = output.replace('\\', '/');
                    for (String dir : SHIM_DIRS) {
                        if (normalized.startsWith(dir + "/") && !declared.contains(normalized)) {
                            declared.add(normalized);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException noLedger) {
            // No ledger is a legitimate state, not a licence to guess: with
            // nothing declaring what this home is meant to hold, "gone" and
            // "never here" are indistinguishable and this check says nothing.
            Log.detail("home repair: no artifact ledger in %s (%s)", store, noLedger.getMessage());
            return 0;
        }

        int examined = 0;
        for (String rel : declared) {
            examined++;
            if (Files.exists(store.resolve(rel), LinkOption.NOFOLLOW_LINKS)) continue;
            for (Path parent : parents) {
                Path theirs = parent.resolve(rel);
                if (!Files.exists(theirs, LinkOption.NOFOLLOW_LINKS)) continue;
                findings.add(new Finding(Kind.PRUNED_INHERITED_ENTRY, rel,
                        "is declared here, is gone, and the parent store " + parent
                                + " this home records still holds it",
                        "re-link it at " + theirs, true, theirs));
                break;
            }
        }
        return examined;
    }

    /**
     * Shape 4 — DEF-012. The home's pinned CLI names a build that is gone.
     *
     * <p>One subject, always examined, so the count says the question was
     * asked. {@link dev.skillmanager.launch.LauncherShims#danglingPinIn} is
     * production's reader for the line and it refuses to guess: a shim with no
     * readable literal pin returns empty, because "cannot tell" must never be
     * reported as "broken".
     *
     * <h2>HIS-19: the remedy names what the repair will actually write</h2>
     *
     * <p>{@code apply} carries this finding out by calling
     * {@link dev.skillmanager.launch.LauncherShims#write}, which since DEF-027
     * pins the most durable spelling of the build rather than the located one.
     * So the {@code target} and the printed remedy are put through the same
     * {@link dev.skillmanager.launch.DurableCliPin} the writer uses. Not a
     * second mechanism — the identical pure function, called twice — because a
     * remedy that names a path the repair does not write is #142's class
     * (a remedy that reads as authoritative and is not what happens), and this
     * epic's signature defect is two readers of one rule.
     *
     * <p>This is also the whole of HIS-19's migration story: existing homes
     * carrying a versioned pin are repaired by the command that already
     * repairs them, and come out durable. No second migration path.
     */
    private static int danglingCliPin(Path store, Path live, List<Finding> findings) {
        Path entrypoint;
        try {
            entrypoint = LauncherShims.cliEntrypoint(new SkillStore(store));
        } catch (RuntimeException notAStore) {
            return 0;
        }
        if (!Files.isRegularFile(entrypoint)) return 0;
        Path gone = LauncherShims.danglingPinIn(entrypoint).orElse(null);
        if (gone == null) return 1;
        Path durable = live == null ? null : dev.skillmanager.launch.DurableCliPin.forPin(live);
        String rel = relative(store, entrypoint);
        findings.add(new Finding(Kind.DANGLING_CLI_PIN, rel,
                "pins the build at " + gone + ", which is not there — this home's front "
                        + "door cannot open, and `home verify` calls it clean",
                durable == null
                        ? "re-pin it: skill-manager home shims --home " + store
                        : "re-pin it at " + durable,
                durable != null, durable));
        return 1;
    }

    // ------------------------------------------------------------- repair

    /**
     * Carry out every repairable finding, then RE-DETECT and report both.
     *
     * <p>The second detection is not decoration. It is the difference between
     * this command and the class of remedy #142 was filed about: a repairer
     * that reports its own success has asserted nothing, and the only statement
     * worth making is that the check which was red is now green. The caller
     * gets both reports and can compare them.
     *
     * <p>Idempotent by construction rather than by a flag: every action below
     * is "make this entry equal to that path", so running it against an
     * already-repaired home finds nothing to do and {@link Outcome#noop()} is
     * true. There is a test that asserts the second run changes no bytes.
     */
    public static Outcome repair(Path store) throws IOException {
        return repair(store, RunningCli.locateOrNull());
    }

    /** As {@link #repair(Path)}, with the build to re-pin at supplied. */
    public static Outcome repair(Path store, Path cliPin) throws IOException {
        Path root = store.toAbsolutePath().normalize();
        // A frozen home is one whose contents are EVIDENCE. `home shims`
        // refuses one for that reason and so does this: the whole point of the
        // freeze is that nothing rewrites what is being looked at, and a repair
        // is the most enthusiastic rewriter in the program. Detection still
        // runs on a frozen home — it opens nothing for writing.
        HomePolicy.requireLive(new SkillStore(root), "home repair --fix");
        Report before = detect(root, cliPin);
        List<Finding> repaired = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        // ONE REPAIR, THEN RE-DETECT. Not a pass over a list taken once.
        //
        // Review of PR #244, blocker 2, second half. Two findings named two
        // paths in ONE file; repairing the first rewrote the second's subject,
        // and the loop then carried on and reported on a finding whose subject
        // no longer existed. A snapshot list is a claim about a home that is
        // being changed underneath it — the same "read once, act later" shape
        // this epic keeps finding, inside the repairer.
        //
        // So the list is re-derived after every action, and a finding is only
        // ever applied if THIS pass still reports it. A repair that consumes
        // another finding's subject now simply makes that finding disappear
        // (or change), which the next detection reports honestly instead of
        // the loop acting on stale prose.
        //
        // Bounded, because the loop's termination depends on detection
        // shrinking and detection is not this method's to trust: an oscillating
        // pair of findings would otherwise spin forever holding a write scope.
        // The cap is generous and its exhaustion is REPORTED, never silent.
        Set<String> attempted = new LinkedHashSet<>();
        int cap = Math.max(8, before.findings().size() * 4);
        WriteConfinement.Scope previous = WriteConfinement.declare(ownedAxesOf(root));
        Report current = before;
        try {
            for (int pass = 0; pass < cap; pass++) {
                Finding next = null;
                for (Finding candidate : current.repairable()) {
                    if (attempted.add(key(candidate))) { next = candidate; break; }
                }
                if (next == null) break;
                try {
                    apply(root, next);
                    repaired.add(next);
                } catch (IOException | RuntimeException refused) {
                    failed.add(next.subject() + ": " + refused.getMessage());
                }
                current = detect(root, cliPin);
                if (pass == cap - 1 && !current.repairable().isEmpty()) {
                    failed.add("gave up after " + cap + " passes with "
                            + current.repairable().size() + " repairable finding(s) left — "
                            + "detection is not converging; re-run and report this");
                }
            }
        } finally {
            WriteConfinement.restore(previous);
        }
        return new Outcome(before, repaired, failed, current);
    }

    /**
     * The confinement one repair of {@code store} runs under: this home's
     * store, and this home's three agent directories. Nothing else.
     *
     * <p>Constructed rather than {@link WriteConfinement#forHome}, and the
     * class javadoc argues why. Briefly: {@code forHome}'s single root is the
     * store, and an agent-link repair writes outside it by design; widening to
     * the enclosing home root would hand a root-tier repair the whole of
     * {@code $HOME}. This is not an exemption — the two axes ARE the home
     * (HIS-14) — so it is not the "and also under X" parameter
     * {@code forHome}'s javadoc refuses.
     */
    static WriteConfinement.Scope ownedAxesOf(Path store) {
        List<Path> roots = new ArrayList<>();
        roots.add(Fs.realOrNormalized(store));
        for (Path agentDir : agentDirsOf(store)) roots.add(Fs.realOrNormalized(agentDir));
        return new WriteConfinement.Scope(store, List.copyOf(roots), WHAT);
    }

    /** A finding's identity for the attempted-set: kind, subject and the path it names. */
    private static String key(Finding finding) {
        return finding.kind() + "|" + finding.subject() + "|" + finding.detail();
    }

    private static void apply(Path store, Finding finding) throws IOException {
        switch (finding.kind()) {
            case MISANCHORED_AGENT_LINK -> relink(
                    AgentHomes.homeRootFor(store).resolve(finding.subject()), finding.target());
            case PRUNED_INHERITED_ENTRY -> relink(
                    store.resolve(finding.subject()), finding.target());
            case FOREIGN_PATH_IN_SHIM -> rewrite(
                    store.resolve(finding.subject()), store, finding);
            // The same call `home shims` makes, on the same home, with the pin
            // detection already resolved. Rewriting the line by hand here would
            // be a second writer for a generated file that has one.
            case DANGLING_CLI_PIN -> {
                WriteConfinement.checkWrite(store.resolve(finding.subject()), WHAT);
                LauncherShims.write(new SkillStore(store), finding.target());
            }
        }
    }

    /**
     * Make {@code link} a symlink at {@code target}, replacing whatever is
     * there.
     *
     * <p>Both the removal and the creation are gated. The removal is gated with
     * the DELETE rule — parent resolved, name re-appended — because the entry
     * being removed is a link that points OUT of the home on purpose, and
     * following its last component would refuse the very repair this is.
     */
    private static void relink(Path link, Path target) throws IOException {
        if (target == null) throw new IOException("nothing to point it at");
        WriteConfinement.requireInside(link, homeOf(link), "home repair (replace)");
        WriteConfinement.checkWrite(link.getParent(), WHAT);
        Files.createDirectories(link.getParent());
        if (Files.exists(link, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(link)) {
            Files.delete(link);
        }
        Files.createSymbolicLink(link, target);
    }

    /**
     * Replace every occurrence of the foreign path in a shim's text with this
     * home's equivalent.
     *
     * <p>Textual and exact — the whole absolute path, not a prefix — so a
     * rewrite cannot turn one wrong path into a different wrong path. The
     * executable bit is carried over: a shim that is repaired into
     * non-executability is a shim that stops working, which is the repair being
     * worse than the damage.
     */
    private static void rewrite(Path file, Path store, Finding finding) throws IOException {
        WriteConfinement.checkWrite(file, WHAT);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        // The path is carried on the finding, not re-parsed out of its prose.
        Path candidate = foreignPathOf(finding);
        if (candidate == null) {
            throw new IOException("finding names no path to rewrite: " + finding.detail());
        }
        String replaced = replaceWholePath(text, candidate.toString(),
                finding.target().toString());
        if (replaced.equals(text)) throw new IOException("the path is no longer in " + file);

        // THE POSTCONDITION, checked before the bytes are kept.
        //
        // Review of PR #244, blocker 2. `String.replace` on a path string IS a
        // prefix replace, so rewriting `<F>/skills` also rewrote
        // `<F>/skills/foo/run` -- a path this same run had already REPORTED as
        // unrepairable, on a line no finding named. Measured: a wrapper that
        // ran (exit 0) became one that resolves nowhere (exit 126), and
        // detection afterwards said the home was clean.
        //
        // `replaceWholePath` closes that specific hole. This closes the CLASS.
        // Whatever the replacement rule turns out to get wrong, a rewrite may
        // not leave this file naming a path under THIS home that is not there:
        // #142's remedy-that-does-not-work, produced by the remedy. Compared
        // as sets rather than counts, so a rewrite cannot swap one broken path
        // for another and pass.
        //
        // It is checked HERE and not by `home verify`, which cannot see it:
        // `missingReferencesIn` only considers paths under a PROVISIONABLE
        // root ({@code cache,venvs,tools,npm,pm}), and the measured casualty
        // was under {@code skills/}. I am the writer, so I verify what I wrote.
        Set<String> brokenBefore = missingPathsUnderHome(text, store);
        Set<String> brokenAfter = missingPathsUnderHome(replaced, store);
        brokenAfter.removeAll(brokenBefore);
        if (!brokenAfter.isEmpty()) {
            throw new IOException("refusing the rewrite: it would leave " + file
                    + " naming " + brokenAfter.size() + " path(s) under this home that do not "
                    + "exist (" + brokenAfter.iterator().next() + ") — a shim that runs and "
                    + "points at the wrong home is repairable; one that resolves nowhere is not");
        }
        boolean executable = Files.isExecutable(file);
        Files.writeString(file, replaced, StandardCharsets.UTF_8);
        if (executable) file.toFile().setExecutable(true);
    }

    /**
     * Characters that continue a path, for deciding where one ends.
     *
     * <p>The whole of blocker 2 lives in this predicate. {@code /a/b} occurs in
     * {@code /a/bc} and in {@code /a/b/c} as a SUBSTRING and in neither as a
     * PATH, and {@link String#replace} cannot tell the difference.
     */
    private static boolean pathChar(char c) {
        return Character.isLetterOrDigit(c) || c == '/' || c == '.' || c == '_' || c == '-';
    }

    /**
     * {@code text} with every whole-path occurrence of {@code from} replaced by
     * {@code to}, and substring occurrences left alone.
     *
     * <p>An occurrence counts when neither side continues a path: not preceded
     * by a path character (so {@code /x/a/b} does not match {@code /a/b}) and
     * not followed by one (so neither {@code /a/bc} nor {@code /a/b/c} does).
     */
    static String replaceWholePath(String text, String from, String to) {
        if (from == null || from.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        while (true) {
            int hit = text.indexOf(from, at);
            if (hit < 0) break;
            int end = hit + from.length();
            boolean whole = (hit == 0 || !pathChar(text.charAt(hit - 1)))
                    && (end == text.length() || !pathChar(text.charAt(end)));
            out.append(text, at, hit).append(whole ? to : from);
            at = end;
        }
        return out.append(text, at, text.length()).toString();
    }

    /**
     * Every path-shaped token in {@code text} that is under {@code store} and
     * is not on disk.
     *
     * <p>Scanned from the candidate TEXT rather than from the file, because the
     * point is to judge bytes that have not been written yet.
     */
    private static Set<String> missingPathsUnderHome(String text, Path store) {
        HomePaths paths = HomePaths.of(store);
        Set<String> missing = new LinkedHashSet<>();
        for (String token : pathTokensIn(text)) {
            Path candidate;
            try {
                candidate = Path.of(token);
            } catch (RuntimeException notAPath) {
                continue;
            }
            if (!paths.isInsideHome(candidate)) continue;
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) missing.add(token);
        }
        return missing;
    }

    /**
     * The foreign path a {@link Kind#FOREIGN_PATH_IN_SHIM} finding is about.
     *
     * <p>Recovered from the detail line's fixed prefix rather than re-scanning
     * the file, so detection and repair cannot end up disagreeing about which
     * occurrence is meant.
     */
    private static Path foreignPathOf(Finding finding) {
        String detail = finding.detail();
        String prefix = "runs ";
        int at = detail.indexOf(prefix);
        int end = detail.indexOf(", which is inside the home at ");
        if (at != 0 || end < prefix.length()) return null;
        try {
            return Path.of(detail.substring(prefix.length(), end));
        } catch (RuntimeException notAPath) {
            return null;
        }
    }

    /**
     * The store or agent directory {@code path} sits in, for the delete gate.
     *
     * <p>Compared in the DELETE spelling — parent resolved, name re-appended —
     * because the declared roots are realized and the entry being repaired is
     * addressed as spelled. Comparing the two directly was measured refusing
     * every agent-link repair on a macOS temp path: {@code /var/…/root/.claude}
     * does not start with {@code /private/var/…/root/.claude}, so the owner
     * lookup fell through to the STORE and the store does not contain an agent
     * directory. The refusal was correct about its own question and the
     * question was wrong.
     */
    private static Path homeOf(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        Path name = abs.getFileName();
        Path resolved = parent == null || name == null
                ? Fs.realOrNormalized(abs)
                : Fs.realOrNormalized(parent).resolve(name);
        WriteConfinement.Scope scope = WriteConfinement.declared();
        if (scope.unconfined()) return null;
        for (Path root : scope.roots()) {
            if (resolved.startsWith(root)) return root;
        }
        return scope.home();
    }

    // ---------------------------------------------------------- plumbing

    private static String relative(Path base, Path path) {
        Path abs = path.toAbsolutePath().normalize();
        try {
            return base.relativize(abs).toString().replace('\\', '/');
        } catch (IllegalArgumentException notUnderIt) {
            return abs.toString();
        }
    }


    /**
     * Absolute path-shaped tokens in a text file.
     *
     * <p>Deliberately a scanner and not a home reader. It answers "what strings
     * in here could be a path", and every one of them is then handed to
     * production's own {@link HomeCloner#unsanctionedForeignHome} to answer the
     * question that matters. The stop set is
     * {@code HomeCloner.scanFor}'s, for the same reason: a shim quotes its
     * paths, and a scanner that swallows the closing quote reports a path that
     * does not exist and therefore reports nothing at all.
     */
    static List<String> absolutePathTokens(Path file) {
        String text;
        try {
            if (Files.size(file) > TEXT_LIMIT) return List.of();
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException notText) {
            return List.of();
        }
        return pathTokensIn(text);
    }

    /**
     * {@link #absolutePathTokens} over a string.
     *
     * <p>Split out so the detector (which reads a file) and the rewrite's
     * postcondition (which judges bytes not yet written) cannot disagree about
     * what a path token is. Two scanners would be this epic's own defect
     * inside the guard against it.
     */
    /**
     * WHAT THIS SCANNER CANNOT SEE. Review of PR #256, minor 5.
     *
     * <p>Written down because both readers now share it, so a blind spot here
     * is a blind spot in {@code home verify} AND {@code home repair} at once —
     * consistent, which is the goal, and consistently wrong, which is worth a
     * sentence. Three, all reproduced:
     *
     * <ul>
     *   <li><b>Interpolation.</b> {@code exec "$SM_HOME/bin/x"} carries no
     *       literal path, so nothing is extracted. Deliberate: a scanner that
     *       guessed at variable values would report paths the shim does not
     *       run.</li>
     *   <li><b>Relative escapes.</b> {@code exec ../../../B/bin/x} is not
     *       absolute and this scans from {@code '/'}. The SYMLINK arm of
     *       {@code home verify} resolves relative targets and catches the link
     *       form; the wrapper form is not covered.</li>
     *   <li><b>Spaces.</b> The token stops at whitespace, so
     *       {@code /Some Home/.skill-manager/x} truncates to {@code /Some}.
     *       Usually that names nothing and the finding is simply missed —
     *       but where the truncation happens to EXIST and to sit inside another
     *       home, the finding names a path the shim does not run. That is the
     *       one case here that reports something false rather than nothing,
     *       and it is why this list is in the code rather than in a pull
     *       request.</li>
     * </ul>
     *
     * <p>Not widened here. The stop set is {@code HomeCloner.scanFor}'s
     * because a shim QUOTES its paths and a scanner that swallows the closing
     * quote reports a path that does not exist — the reason this method's own
     * comment gives — and every widening trades a missed finding for a false
     * one. Recorded so the next reader knows the boundary was chosen.
     */
    static List<String> pathTokensIn(String text) {
        Set<String> found = new LinkedHashSet<>();
        for (int at = text.indexOf('/'); at >= 0; at = text.indexOf('/', at + 1)) {
            if (at > 0 && "\"' \n\r\t:;,()=$".indexOf(text.charAt(at - 1)) < 0) continue;
            int end = at;
            while (end < text.length() && "\"'\n\r\t :;,)".indexOf(text.charAt(end)) < 0) end++;
            String candidate = text.substring(at, end);
            while (candidate.endsWith(".") || candidate.endsWith("/")) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            // Two separators minimum: `/` and `/usr` are not interesting, and
            // a token holding a shell expansion is not a literal path.
            if (candidate.indexOf('/', 1) < 0) continue;
            if (candidate.indexOf('$') >= 0 || candidate.indexOf('*') >= 0) continue;
            found.add(candidate);
            at = end - 1;
        }
        return List.copyOf(found);
    }
}
