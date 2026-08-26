package dev.skillmanager.launch;

import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates the {@code claude} / {@code codex} / {@code gemini} launchers that
 * bind a harness process to the Skill Manager home they live in.
 *
 * <h2>Nobody should have to export anything</h2>
 *
 * <p>Before this existed, {@code AgentHomes} only <em>read</em> the four
 * config-root variables and the project documentation told a human to export
 * them by hand. That is a step a person forgets and an agent cannot be asked to
 * know about, and forgetting it does not fail — it silently runs against the
 * global home. A shim on {@code PATH} makes the correct environment the
 * <em>default</em> for anything launched from a worktree.
 *
 * <h2>The HOME is relocatable; the CLI is pinned. That is a change, and here
 * is why it was made</h2>
 *
 * <p>This class used to promise that a shim carried <em>no</em> absolute path
 * at all: it derived its home from its own location and found its CLI by
 * searching {@code PATH}, so a home copied to a new root kept working with no
 * rewrite. Half of that is still true and is still the property the whole
 * per-project-home design rests on — <b>the home is still derived from the
 * shim's own location</b> ({@code <home>/bin/launch/<x>} → {@code <home>},
 * {@code <home>/bin/cli/skill-manager} → {@code <home>}), and a copied home
 * still binds itself rather than the home it was copied from.
 *
 * <p>The other half was not relocatability, it was a silent downgrade.
 * "Whatever {@code skill-manager} is on {@code PATH}" is not a stable
 * reference to the build this home was provisioned with; it is a reference to
 * whatever the operator happens to have installed. Measured (issue #61): on a
 * machine whose {@code PATH} {@code skill-manager} is the released 0.19.2 —
 * a build with no {@code exec} subcommand — every launcher this class writes
 * ends in {@code exec "$cli" exec --home ...}, so a home provisioned by
 * {@code home shims} alone died with {@code Unmatched arguments: 'exec'}. Both
 * builds answer {@code --version} with the same string, so nothing about the
 * failure said which CLI had answered.
 *
 * <p>So {@code bin/cli/skill-manager} now pins, absolutely, the launcher that
 * {@link RunningCli} says is running when {@code home shims} executes. The
 * tradeoff is stated rather than assumed:
 *
 * <ul>
 *   <li>A <b>stale pin fails loudly</b> — exit 127, the missing path named, and
 *       the one command that repairs it ({@code home shims}) printed. A
 *       {@code PATH} fallback fails <b>quietly</b>, by running an older CLI
 *       that prints usage and may exit 0. Between the two, loud wins; the
 *       whole surface exists because a silent no-launch reads as success.</li>
 *   <li>Copying a home to another root still works. Copying a home to another
 *       <em>machine</em> now needs one command to re-pin, which it needed
 *       anyway: a home whose CLI came from {@code PATH} was never carrying the
 *       build it was provisioned with, only the illusion of one.</li>
 * </ul>
 *
 * <p>{@code git-integration-repo}'s {@code bootstrap-home.sh} reached the same
 * conclusion first and wrote the pin from bash, which is why homes bootstrapped
 * by that script worked and homes provisioned by {@code home shims} alone did
 * not. Two writers of one file is what let the difference hide; this class is
 * now the writer, and {@code ensure_cli_pin} has nothing left to repair.
 *
 * <h2>HIS-19: the pin is the most DURABLE spelling of that build, not the most
 * physical one</h2>
 *
 * <p>Everything above stands and the tradeoff it states was still real, but one
 * of its clauses was measured false. "A stale pin fails loudly" is what the
 * bullet promised; DEF-012 measured a stale pin failing <em>silently</em>, in
 * the one place it matters — {@code brew upgrade} deleted the keg the operator's
 * root home pinned, the entrypoint file remained present and executable, and
 * {@code home verify} returned <b>exit 0</b>. Every reader that tests {@code -x}
 * on that file, including {@code HomeDescriptor.locateCli}, called the home
 * healthy.
 *
 * <p>HIS-12 stopped remedies naming a dead pin and HIS-13 made {@code home
 * repair} report and re-pin one. Neither stopped THIS class writing the same
 * pin on the next run, so the repair ran on a treadmill. {@link DurableCliPin}
 * is the cause: given the located build it looks for another spelling of THE
 * SAME FILE that carries no version, and pins that instead.
 *
 * <p><b>It is not the {@code PATH} fallback this section argues against, and
 * the difference is enforced rather than asserted.</b> The build is already
 * chosen — a candidate is refused unless {@link java.nio.file.Path#toRealPath}
 * makes it the identical file — so what a home runs today is byte-for-byte
 * unchanged, and there is still exactly one absolute path in the generated file
 * and no search at launch time. What changes is the day after an upgrade: the
 * home runs the build that replaced the one it was provisioned with, rather
 * than nothing at all. The property this section defends narrows from "the
 * build it was provisioned with" to "that build, or its successor under the
 * same installation", and it narrows only where the alternative is a path that
 * names a deleted file. A build whose path carries no version — a source
 * checkout, a CI artifact, a hand-built binary — is pinned exactly as before.
 *
 * <h2>One implementation of the launch rules</h2>
 *
 * <p>{@code PATH} precedence, foreign-home pruning, the
 * {@code CLAUDE_CONFIG_DIR} refusal and the home policy all live in
 * {@link LaunchEnv} and are exercised by tests. A shim that assembled the
 * environment in bash would be a second, untested copy of security-relevant
 * logic — and it is the copy that actually runs. The shims therefore stay
 * thin: locate the home, hand off to {@code skill-manager exec}.
 */
public final class LauncherShims {

    /** The harnesses a home publishes a launcher for. */
    public static final List<String> AGENTS = List.of("claude", "codex", "gemini");

    private LauncherShims() {}

    /**
     * What one {@code home shims} run wrote.
     *
     * @param pin the path actually recorded in {@code bin/cli/skill-manager},
     *            which since HIS-19 need not be the path the caller passed in:
     *            {@link DurableCliPin} may substitute a versionless spelling of
     *            the same file. Carried here so the COMMAND reports what was
     *            written rather than what was located — those were two different
     *            paths for one run, which is exactly the reader-disagreement
     *            this epic exists to remove, freshly created by its fix.
     */
    public record Result(Path dir, List<Path> written, Path pin) {}

    /** {@code <store>/bin/launch}. */
    public static Path dir(SkillStore store) {
        return LaunchEnv.launcherDir(store);
    }

    /** {@code <store>/bin/cli/skill-manager} — the home's own CLI entrypoint. */
    public static Path cliEntrypoint(SkillStore store) {
        return store.cliBinDir().resolve(CLI).toAbsolutePath().normalize();
    }

    /** The basename both {@link #script} and {@code HomeDescriptor.resolveCli} read. */
    private static final String CLI = "skill-manager";

    /**
     * Write (or rewrite) every launcher into {@code <store>/bin/launch}, pinning
     * the CLI that is running right now.
     *
     * <p>Resolution happens <em>before</em> anything is written, so a home that
     * cannot be pinned is left exactly as it was rather than half-provisioned
     * with launchers that have nothing to delegate to.
     *
     * @throws RunningCli.UnknownLocationException when the running CLI's own
     *         location cannot be established. Deliberately fatal: writing a
     *         {@code PATH}-resolving shim instead is the defect (see the class
     *         javadoc), and a home with no launch surface at all is a state an
     *         operator can see.
     */
    public static Result write(SkillStore store) throws IOException {
        return write(store, RunningCli.locate());
    }

    /**
     * As {@link #write(SkillStore)}, with the CLI to pin supplied.
     *
     * <p>Exists so the resolution and the generation can be tested apart: the
     * tests that care what the shims <em>do</em> pass a fixture CLI, and the
     * tests that care how the running CLI is <em>found</em> drive
     * {@link RunningCli} directly. It is also the seam a caller that already
     * knows which build it wants a home bound to would use.
     *
     * <p>Refused on a frozen home: the launchers are content, and a frozen home
     * is one whose contents are evidence.
     */
    public static Result write(SkillStore store, Path pinnedCli) throws IOException {
        HomePolicy.requireLive(store, "home shims");
        // THE PIN IS THE MOST DURABLE SPELLING OF THIS BUILD, NOT THE MOST
        // PHYSICAL ONE. DEF-027, the cause under DEF-012.
        //
        // `toAbsolutePath().normalize()` was the whole of this line, and it is
        // faithful to a fault: on a Homebrew install the launcher resolves its
        // own symlink before exporting SKILL_MANAGER_INSTALL_DIR, so RunningCli
        // hands this method a path INSIDE the keg
        // (/opt/homebrew/Cellar/skill-manager/0.24.0/...). That pin is correct
        // exactly until the next `brew upgrade` deletes the directory it names,
        // and wrong forever after — measured live on the operator's machine
        // during this epic's own 0.24.0 release, with `home verify` reporting
        // exit 0 on the broken home.
        //
        // DurableCliPin never chooses a BUILD; it is handed one and looks only
        // for another spelling of THE SAME FILE, checked by real-path equality.
        // So this changes nothing about which binary a home runs today, and it
        // is not the PATH search RunningCli's javadoc argues against: the
        // resolution happens once, here, and the generated entrypoint still
        // carries one absolute path and no PATH branch.
        DurableCliPin.Choice choice = DurableCliPin.choose(pinnedCli);
        Path pin = choice.pin();
        if (choice.substituted()) {
            // Said out loud, because it changes which file this home's front
            // door names. A substitution nobody can audit is the shape
            // `ensure_cli_pin` had when it silently overwrote 17 correct pins.
            dev.skillmanager.util.Log.detail(
                    "home shims: pinning %s instead of %s — the same build reached by a "
                            + "spelling that survives an upgrade (%s)",
                    pin, choice.located(), choice.source());
        }
        // And the whole audit, not only the outcome. Bounded to at most three
        // lines because every candidate is derived from the located path.
        // Choice.considered's javadoc promised this and nothing printed it
        // until the review of #250 grepped for the reader (m5).
        for (String line : choice.auditLines()) {
            dev.skillmanager.util.Log.detail("  pin candidate: %s", line);
        }
        Path dir = dir(store);
        Fs.ensureDir(dir);
        List<Path> written = new java.util.ArrayList<>();
        for (String agent : AGENTS) {
            Path file = dir.resolve(agent);
            Files.writeString(file, script(agent));
            Fs.makeExecutable(file);
            written.add(file);
        }

        // The home's own CLI entrypoint, written here because it is the same
        // category of artifact as the launchers above — a generated shim the
        // home owns — and this is the command that owns them.
        //
        // Before this existed nothing wrote it, while BOTH the launcher script
        // and HomeDescriptor.resolveCli read it: a contract with two readers and
        // no writer. The observed symptom was the worst kind for a launcher, a
        // silent no-launch, so cliScript() is written to be incapable of
        // exiting 0 without having exec'd something.
        Path cli = cliEntrypoint(store);
        Fs.ensureDir(cli.getParent());
        Files.writeString(cli, cliScript(pin));
        Fs.makeExecutable(cli);
        written.add(cli);

        return new Result(dir, List.copyOf(written), pin);
    }

    /**
     * The launcher body for {@code agent}.
     *
     * <p>{@code SKILL_MANAGER_CLI} is honoured first so a harness that built its
     * own CLI can pin it (and so a test can substitute a stub), then the home's
     * own {@code bin/cli/skill-manager} — which {@link #write(SkillStore, Path)}
     * writes in the same call, so it is present whenever this file is.
     *
     * <p>There is no {@code PATH} branch any more, and its removal is the point
     * of issue #61 rather than tidying. This shim's last line is
     * {@code exec "$cli" exec --home ...}; the released CLI on {@code PATH} has
     * no {@code exec} subcommand, so the branch that reached it could only ever
     * produce {@code Unmatched arguments: 'exec'} — after having looked, to
     * every caller, like the shim had found a CLI. Refusing with the home named
     * is the same failure made visible.
     *
     * <p><b>There is no {@code .formatted()} here any more either.</b> It used
     * to end in {@code .formatted(agent)}, which meant every literal percent in
     * the body had to be doubled — the trap documented on
     * {@link #cliScript(Path)}, which once made a shim refuse unconditionally on
     * every home. The self-reference guard below is written in bash parameter
     * expansion ({@code ${p%/*}}) and {@code printf '%s'}, so the body now
     * carries percents that mean something, and the safe way to interpolate a
     * body carrying percents is not to run it through a formatter at all.
     */
    public static String script(String agent) {
        return AGENT_TEMPLATE
                .replace(SELF_GUARD_PLACEHOLDER, SELF_GUARD)
                .replace(AGENT_PLACEHOLDER, agent);
    }

    /** Replaced with the harness name. Not a format specifier — see {@link #script}. */
    static final String AGENT_PLACEHOLDER = "@SKILL_MANAGER_AGENT@";

    private static final String AGENT_TEMPLATE = """
            #!/usr/bin/env bash
            # Generated by `skill-manager home shims` — do not edit.
            #
            # Launches `@SKILL_MANAGER_AGENT@` bound to the Skill Manager home this shim
            # lives in. The home is derived from the shim's own location, so a cloned
            # home needs no rewrite; every rule about the launch environment (PATH
            # precedence, refusing an unredirected CLAUDE_CONFIG_DIR, the home
            # policy) is applied by `skill-manager exec`, not duplicated here.
            #
            # The CLI comes from this home's own bin/cli/skill-manager, which is
            # written by the same `home shims` run and pins the build that wrote
            # it. There is no PATH fallback: this script ends in `exec ... exec`,
            # and an older skill-manager on PATH has no `exec` subcommand.
            set -euo pipefail

            shim_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
            home="$(cd -- "$shim_dir/../.." && pwd -P)"

            @SKILL_MANAGER_SELF_GUARD@

            fallback="$home/bin/cli/skill-manager"
            cli="${SKILL_MANAGER_CLI:-$fallback}"
            sm_refuse_self_exec "$cli" "$fallback" "@SKILL_MANAGER_AGENT@" \\
              "skill-manager home shims --home $home"
            cli="$sm_cli"

            if [ ! -x "$cli" ]; then
              echo "@SKILL_MANAGER_AGENT@: the home at $home has no CLI entrypoint." >&2
              echo "  expected: $cli" >&2
              echo "  Re-provision it with \\`skill-manager home shims --home $home\\`," >&2
              echo "  or set SKILL_MANAGER_CLI to the build this home should run." >&2
              exit 127
            fi

            exec "$cli" exec --home "$home" -- @SKILL_MANAGER_AGENT@ "$@"
            """;

    /** Replaced with the absolute path of the pinned CLI. Not a format specifier. */
    static final String PIN_PLACEHOLDER = "@SKILL_MANAGER_CLI_PIN@";

    /** Replaced with {@link #SELF_GUARD}. */
    static final String SELF_GUARD_PLACEHOLDER = "@SKILL_MANAGER_SELF_GUARD@";

    /**
     * Exit status when a shim is asked to {@code exec} itself and has nothing
     * else to fall back to.
     *
     * <p>78 is {@code EX_CONFIG} from {@code sysexits(3)} — "something was found
     * in an unconfigured or misconfigured state" — which is exactly the case: the
     * shim works, the request does not. It is deliberately not 127 ("the CLI is
     * missing", which would send an operator looking for an absent file that is
     * in fact present) and not 1.
     */
    public static final int SELF_EXEC_EXIT_CODE = 78;

    /**
     * Exit status when {@code SKILL_MANAGER_HOME} names a home other than the
     * one this shim lives in, and no {@code --home} settled it.
     *
     * <h2>The defect, which is this epic's own class on the launch surface</h2>
     *
     * <p>{@code bin/cli/skill-manager} exports its own home, deliberately —
     * that override exists because running a project home's shim with a decoy
     * home inherited created ten directories in the decoy. What it did not do
     * is <em>say</em> so. {@code SKILL_MANAGER_HOME=<x> <y>/bin/cli/skill-manager}
     * edits <b>y</b>, having been told <b>x</b>, silently. That is how a command
     * aimed at a worktree home lands in the root home, and it is why every
     * scratch home in this epic had to be driven with the raw build instead of
     * a home's own pin.
     *
     * <p>Both directions of the bug are now closed: the shim still never runs
     * against the inherited home (the incident above), and it no longer runs
     * against its own without saying so. Neither home is silently edited.
     *
     * <p>79, not 78: this is a different misconfiguration from the self-exec
     * refusal and a caller must be able to tell them apart. It sits next to it
     * because both are {@code sysexits}' {@code EX_CONFIG} family — the shim
     * works, the request does not.
     */
    public static final int HOME_MISMATCH_EXIT_CODE = 79;

    /**
     * The marker a test — or an operator reading a stuck fan-out — can grep the
     * generated shims for to tell "this shim can defend itself" from "this shim
     * is the version that hangs".
     */
    public static final String SELF_GUARD_MARKER = "skill-manager:self-exec-guard";

    /**
     * The bash both generated shims carry: refuse to {@code exec} <em>this very
     * file</em>.
     *
     * <h2>The failure it removes is a hang, which is worse than an error</h2>
     *
     * <p>Both shims end in {@code exec "$cli" …} where {@code $cli} comes from
     * {@code SKILL_MANAGER_CLI} first. Nothing stopped that variable from naming
     * the shim doing the exec'ing, and when it did, the shim re-entered itself
     * with the same environment and did it again. Reproduced directly:
     *
     * <pre>
     * control:  &lt;shim&gt; --version                          → returns immediately
     * repro:    SKILL_MANAGER_CLI=&lt;shim&gt;  &lt;shim&gt; --version  → never returns (exit 124 under timeout)
     * </pre>
     *
     * <p>It is not hypothetical and it is not a typo an operator makes.
     * {@code git-integration-repo}'s {@code close-change.sh} reaches it by
     * construction: its {@code pick_cli} selects the home's own
     * {@code bin/cli/skill-manager} and then invokes it as
     * {@code SKILL_MANAGER_CLI="$CLI" "$CLI" …}. One orphaned process from that
     * path burned 7:03 of CPU over 13:06 elapsed, printed nothing, and survived
     * its parent being killed. On a 24-repo fan-out that reads as slow work,
     * which is why the defence belongs <em>in the shim</em> — the layer that is
     * present on every home, whoever calls it and however old their script is.
     *
     * <h2>Physical paths, not strings</h2>
     *
     * <p>The comparison has to survive every other spelling of one file:
     * {@code ./skill-manager}, a symlink planted on PATH, and — on macOS, where
     * a temp dir is reachable as both — {@code /var/…} against
     * {@code /private/var/…}. {@code sm_physical} therefore follows symlinks on
     * the final component itself and puts the containing directory through
     * {@code cd -P}, which resolves every other component. A string compare
     * would pass all three of those straight through.
     *
     * <h2>Falling back beats refusing, and the asymmetry is the reason</h2>
     *
     * <p>When the self-reference comes from {@code SKILL_MANAGER_CLI} the shim
     * uses its fallback (the pin, or the home's own entrypoint) and says so on
     * stderr. The two sides are not equivalent: the pin is a <em>recorded fact</em>
     * about the build this home was provisioned with, written by
     * {@code home shims}; {@code SKILL_MANAGER_CLI} is a request from a caller,
     * and a self-referential request carries no information at all — it says
     * "run me", which is what we already are. Ignoring an empty request loses
     * nothing, while refusing would take down callers like {@code close-change.sh}
     * that are otherwise asking for something perfectly reasonable (a CLI bound
     * to this home) and are already getting it, because the shim exports
     * {@code SKILL_MANAGER_HOME} before it delegates.
     *
     * <p>The rejected value is also {@code unset} before the {@code exec}, so it
     * cannot be inherited and acted on further down. That matters for one
     * command in particular: {@code RunningCli.locate} reads
     * {@code SKILL_MANAGER_CLI} to decide what {@code home shims} should pin, so
     * passing a self-reference through would let the next {@code home shims}
     * write a pin to the shim — turning a recoverable situation into the one
     * below.
     *
     * <p>When the <em>fallback</em> is the self-reference there is nothing left
     * to delegate to, and the shim exits {@link #SELF_EXEC_EXIT_CODE} with both
     * paths named. Loud and non-zero, because the alternative is the hang.
     */
    static final String SELF_GUARD = """
            # skill-manager:self-exec-guard — see LauncherShims.SELF_GUARD.
            #
            # `exec "$cli"` when $cli IS this file re-enters this file forever. That
            # is a HANG: no output, no timeout, and it outlives the parent that
            # started it. Measured on one such process: 7:03 of CPU over 13:06
            # elapsed, silent throughout.
            #
            # Physical paths, not strings: `./skill-manager`, a symlink on PATH and
            # /var vs /private/var on macOS are all the same file under different
            # spellings, and a string compare lets every one of them through.
            sm_dirname() {
              local d
              case "$1" in
                */*) d="${1%/*}"; printf '%s' "${d:-/}" ;;
                *)   printf '%s' "." ;;
              esac
            }

            # The physical path of $1: the final component followed if it is itself a
            # symlink, then its directory through `cd -P`, which resolves the rest.
            # Prints nothing when the path cannot be resolved, and a comparison
            # against nothing is skipped rather than guessed.
            sm_physical() {
              local p="$1" d b hops=0
              while [ -L "$p" ] && [ "$hops" -lt 40 ]; do
                if ! b="$(readlink -- "$p" 2>/dev/null)"; then break; fi
                d="$(sm_dirname "$p")"
                case "$b" in
                  /*) p="$b" ;;
                  *)  p="$d/$b" ;;
                esac
                hops=$((hops + 1))
              done
              if ! d="$(cd -- "$(sm_dirname "$p")" 2>/dev/null && pwd -P)"; then return 0; fi
              printf '%s/%s\\n' "$d" "${p##*/}"
            }

            sm_self="$(sm_physical "${BASH_SOURCE[0]}")"

            # Sets $sm_cli to something that is not this file, or exits non-zero.
            # $1 the requested CLI, $2 the fallback, $3 this shim's label,
            # $4 the command that re-provisions the home.
            sm_refuse_self_exec() {
              sm_cli="$1"
              [ -n "$sm_self" ] || return 0
              [ "$(sm_physical "$1")" = "$sm_self" ] || return 0
              if [ -z "$2" ] || [ "$(sm_physical "$2")" = "$sm_self" ]; then
                echo "$3: the CLI this shim would run is this shim itself:" >&2
                echo "  $sm_self" >&2
                echo "  Nothing is left to delegate to, and exec'ing it would re-enter this" >&2
                echo "  script forever rather than fail — so this is a refusal, not a launch." >&2
                echo "  Re-provision the home with \\`$4\\`, run from the build it should use." >&2
                exit @SKILL_MANAGER_SELF_EXEC_EXIT@
              fi
              echo "$3: ignoring SKILL_MANAGER_CLI=$1 — it resolves to this shim ($sm_self)," >&2
              echo "  and exec'ing it would re-enter this script forever. Using $2 instead." >&2
              # Rejected here means rejected downstream too: `home shims` reads
              # SKILL_MANAGER_CLI to choose what to pin, so a self-reference passed
              # on could be written into the pin and make the branch above reachable.
              unset SKILL_MANAGER_CLI
              sm_cli="$2"
            }
            """.replace("@SKILL_MANAGER_SELF_EXEC_EXIT@", String.valueOf(SELF_EXEC_EXIT_CODE));

    /**
     * A machine-readable marker in the generated entrypoint, so another tool can
     * tell "this slot already holds a correct absolute pin" from "this slot
     * holds something I should not touch".
     *
     * <p>It exists because of a specific, measured accident.
     * {@code git-integration-repo}'s {@code ensure_cli_pin} decided whether to
     * overwrite this file by grepping it for the words {@code home shims} —
     * which every version of it contains, including the fixed one — and so
     * overwrote a correct pin on 17 of 25 homes with a pin to a CLI IT had
     * chosen, while failing to recognise its own earlier output on an 18th. A
     * predicate that keys on prose cannot distinguish versions of the prose.
     * This is the stable token to key on instead.
     */
    public static final String PIN_MARKER = "skill-manager:cli-pin";

    /**
     * The body of {@code <home>/bin/cli/skill-manager}, pinned to {@code cli}.
     *
     * <h2>It cannot find the wrong CLI, because it does not look</h2>
     *
     * <p>The previous body searched {@code PATH} with its own directory removed
     * — {@link LaunchEnv} puts {@code <home>/bin/cli} at the FRONT of the launch
     * PATH, so an unfiltered {@code command -v skill-manager} resolved to itself
     * and spun. That filter was careful, correct, and answering the wrong
     * question. What {@code PATH} knows is which build the operator installed
     * globally; what this file has to record is which build this home was
     * provisioned with. On the machine where issue #61 was measured those were
     * the released 0.19.2 and a working build respectively, and the released one
     * has no {@code exec} subcommand — so every launcher this class writes died
     * at its last line. The recursion guard is gone with the search that needed
     * it.
     *
     * <h2>It cannot succeed without launching</h2>
     *
     * <p>Every path out of this script either {@code exec}s the pinned CLI or
     * exits 127 with a diagnostic naming the path and the command that repairs
     * it. That is the whole point of the ticket that added it: the defect being
     * fixed was a shim that printed a usage error and exited 0, which reads as
     * success to every caller and to every graph node that checks an exit code.
     *
     * <h2>{@code SKILL_MANAGER_HOME} is exported, and that is load-bearing</h2>
     *
     * <p>A command reached through this file is by definition a command about
     * THIS home. Without the export it runs against whatever home the caller
     * happens to carry, and an unset {@code SKILL_MANAGER_HOME} means
     * {@code SkillStore.defaultStore()} — the operator's global home, which is
     * the single failure per-checkout homes exist to prevent.
     *
     * <h2>There is still no {@code .formatted()} here, on purpose</h2>
     *
     * <p>{@link #script(String)} ends in {@code .formatted(agent)}, so every
     * literal percent in it must be doubled. This method interpolates with
     * {@link String#replace(CharSequence, CharSequence)} instead, so percents
     * below stay literal and the historical trap cannot come back: a
     * {@code printf '%%s'} copied across from the method above once made the
     * filtered PATH the single entry {@code %s}, so the shim took its refusal
     * branch UNCONDITIONALLY — exit 127 on every home, including ones with a
     * working CLI on PATH. It survived the graph because the only assertion
     * covering it was one-sided: a shim that ALWAYS refuses satisfies "it
     * refuses rather than succeeding silently" perfectly. Both the unit test and
     * the {@code checkout-home} node now assert on generated bytes and on the
     * exec'd CLI's output, never on an exit code alone.
     */
    public static String cliScript(Path cli) {
        return CLI_TEMPLATE
                .replace(SELF_GUARD_PLACEHOLDER, SELF_GUARD)
                .replace(PIN_PLACEHOLDER, cli.toAbsolutePath().normalize().toString());
    }

    private static final String CLI_TEMPLATE = """
            #!/usr/bin/env bash
            # skill-manager:cli-pin — generated by `skill-manager home shims`, do not edit.
            #
            # The token above is the stable predicate for "this slot holds a
            # correct absolute CLI pin". Key on it rather than on any of the prose
            # below, which changes.
            #
            # The CLI entrypoint for the Skill Manager home this shim lives in
            # (<home>/bin/cli/skill-manager).
            #
            # The HOME is derived from this shim's own location, so a home copied to
            # a new root still binds itself. The CLI is PINNED to the build that ran
            # `home shims`. There is no PATH fallback on purpose: falling through to
            # an older CLI is the failure this file exists to remove, and that CLI
            # answers unknown subcommands with top-level usage — a downgrade that
            # looks like success.
            set -euo pipefail

            self_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
            home="$(cd -- "$self_dir/../.." && pwd -P)"

            # BIND THE HOME. Without this the shim exec'd a CLI carrying
            # whatever SKILL_MANAGER_HOME the caller happened to have — which,
            # unset, is the operator's global home. Measured: running
            # <project>/.skill-manager/bin/cli/skill-manager --version against
            # an empty decoy home created TEN directories in the decoy and
            # printed `reconcile: backfilled N default-agent binding(s)`. So
            # the one command whose entire purpose is to be "the CLI for THIS
            # home" was the command most likely to mutate a different one —
            # and it is the command an onboarding checklist tells an agent to
            # run to prove the home works.
            #
            # The shim's own location wins over an inherited value on purpose:
            # its identity is "the CLI for the home I live in", and a shim
            # that deferred to the environment would be indistinguishable from
            # the bare CLI. Name a different home with --home, or call the CLI
            # directly.
            #
            # HIS-9: WINNING SILENTLY IS THE OTHER HALF OF THE SAME DEFECT.
            # Overriding fixed the decoy incident above and created its mirror:
            # `SKILL_MANAGER_HOME=<x> <y>/bin/cli/skill-manager` edits y, having
            # been told x, and says nothing. That is how a command aimed at a
            # worktree home lands in the root home instead — the class this
            # ticket exists for, on the launch surface rather than in the
            # filesystem. So the shim still never runs against the inherited
            # home, and it no longer runs against its own without saying so: a
            # DIFFERENT home named in the environment is a REFUSAL naming both.
            # Two spellings of one directory are not different (`cd -P` on each
            # side); an unset or empty value is not a request and still binds
            # this home, which is the case the block above is about.
            @SKILL_MANAGER_SELF_GUARD@

            # `--home` on the command line settles the question and is never
            # refused: it is the escape this file's own comment above tells the
            # operator to use, it is what the refusal below recommends, and it
            # is what bootstrap-home.sh should be passing. A refusal whose
            # printed remedy the refusal itself would reject is not a remedy.
            sm_names_a_home=0
            for sm_arg in "$@"; do
              if [ "$sm_arg" = "--home" ] || [ "${sm_arg#--home=}" != "$sm_arg" ]; then
                sm_names_a_home=1
                break
              fi
            done

            sm_inherited_home="${SKILL_MANAGER_HOME:-}"
            if [ -n "$sm_inherited_home" ] && [ "$sm_names_a_home" -eq 0 ]; then
              sm_named="$(cd -- "$sm_inherited_home" 2>/dev/null && pwd -P || printf '%s' "$sm_inherited_home")"
              if [ "$sm_named" != "$home" ]; then
                echo "skill-manager: refusing to run against a home you did not name." >&2
                echo "  you named:  $sm_inherited_home" >&2
                echo "  this shim would have edited: $home" >&2
                echo "  This entrypoint binds the home it lives in, so it cannot honour" >&2
                echo "  SKILL_MANAGER_HOME. Refusing rather than silently editing the" >&2
                echo "  other one." >&2
                # NAME BOTH HOMES AND LET THE OPERATOR CHOOSE. The shim must
                # not guess, and two earlier versions of this text did.
                #
                # The first printed `--home $sm_inherited_home` only, which
                # recommends the home named in the ENVIRONMENT -- not the one
                # this shim serves -- so following it verbatim operated on a
                # third thing.
                #
                # The second tried to infer intent from the invocation and could
                # not: a #! script never sees the word that was typed (the shell
                # PATH-resolves it and execve's the absolute path, and the
                # kernel discards argv[0]), so bare-name and absolute
                # invocations give an IDENTICAL $0. Worse, the damage case this
                # guard exists for -- `SKILL_MANAGER_HOME=<x> <y>/bin/cli/
                # skill-manager sync foo` -- is itself absolute, so no
                # shim-side observation separates it from a pasted remedy.
                #
                # Intent is therefore STATED by the caller, on the command line,
                # where --home is already this guard's exemption. Both spellings
                # below are exact and both are runnable.
                echo "  Say which one you mean:" >&2
                echo "    --home $home   (this shim's home)" >&2
                echo "    --home $sm_inherited_home   (the home your environment names)" >&2
                exit @SKILL_MANAGER_HOME_MISMATCH_EXIT@
              fi
            fi

            export SKILL_MANAGER_HOME="$home"

            # The pin is written out TWICE, and the duplication is load-bearing.
            # The assignment below is not only bash. bootstrap-home.sh's
            # ensure_cli_pin recognises this file by the marker at the top, then
            # reads the pinned path back out of the FIRST line whose prefix is
            # that assignment up to the default-value colon-dash, strips the
            # closing brace and quote, and asserts the result is executable.
            # Hoisting the path into a variable and defaulting to it here leaves
            # the shim working perfectly while handing that reader four
            # characters that are not a file, which refuses the home. Keep the
            # assignment's shape, and give the guard its own copy of the
            # literal. Nothing above may spell that prefix out either: the
            # extractor takes the first match in the whole file, comments
            # included, so a comment quoting it wins over the real line.
            cli="${SKILL_MANAGER_CLI:-@SKILL_MANAGER_CLI_PIN@}"
            sm_refuse_self_exec "$cli" "@SKILL_MANAGER_CLI_PIN@" "skill-manager" \\
              "skill-manager home shims --home $home"
            cli="$sm_cli"

            if [ ! -x "$cli" ]; then
              echo "skill-manager: the CLI pinned for the home at $home is missing:" >&2
              echo "  $cli" >&2
              echo "  Re-pin it with \\`skill-manager home shims --home $home\\`, run from the" >&2
              echo "  build this home should use, or set SKILL_MANAGER_CLI." >&2
              echo "  There is deliberately no PATH fallback: an older skill-manager answers" >&2
              echo "  unknown subcommands with top-level usage, which looks like success." >&2
              exit 127
            fi

            exec "$cli" "$@"
            """.replace("@SKILL_MANAGER_HOME_MISMATCH_EXIT@",
                    String.valueOf(HOME_MISMATCH_EXIT_CODE));

    /**
     * The prefix of the one line in a generated entrypoint that carries the
     * pin, up to the default-value {@code :-}.
     *
     * <p>This shape is already a contract with a second reader:
     * {@code git-integration-repo}'s {@code ensure_cli_pin} finds the first
     * line with this prefix, strips the closing brace and quote, and asserts
     * the result is executable. See the comment inside {@link #CLI_TEMPLATE}
     * for why nothing above that line may spell the prefix out. Named here so
     * a third reader does not invent a fourth spelling of it.
     */
    public static final String PIN_PREFIX = "cli=\"${SKILL_MANAGER_CLI:-";

    /**
     * The absolute build a generated entrypoint pins, or empty when
     * {@code entrypoint} is not one of ours or carries no readable pin.
     *
     * <h2>Why anything reads this back</h2>
     *
     * <p>DEF-012, measured 2026-08-21: the pin is an absolute VERSIONED path
     * into the Homebrew Cellar
     * ({@code /opt/homebrew/Cellar/skill-manager/0.23.0/libexec/bin/skill-manager}),
     * and {@code brew upgrade} to 0.24.0 deleted that directory. The shim
     * itself still exists and is still executable, so every caller that tests
     * {@code -x <home>/bin/cli/skill-manager} — including
     * {@link dev.skillmanager.store.HomeDescriptor#locateCli} — sees a healthy
     * home entrypoint, while running it can only produce exit 127. A REMEDY
     * naming that file reads as authoritative and cannot run, which is the
     * defect of issue #161 in its purest form.
     *
     * <p>Empty rather than throwing for a file that is not ours, or is ours
     * and unreadable: "cannot tell" must not be reported as "broken". Only a
     * pin that is present AND names something absent is a finding.
     */
    public static java.util.Optional<Path> pinnedCliIn(Path entrypoint) {
        if (entrypoint == null || !Files.isRegularFile(entrypoint)) {
            return java.util.Optional.empty();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(entrypoint);
        } catch (IOException unreadable) {
            return java.util.Optional.empty();
        }
        if (lines.stream().noneMatch(l -> l.contains(PIN_MARKER))) {
            return java.util.Optional.empty();
        }
        // EVERY matching line, not the first. `ensure_cli_pin` takes the first
        // and the template's own comment warns that nothing above it may spell
        // the prefix out — a warning is not an invariant, and a file with two
        // assignment lines that disagree is a file this cannot read. Two
        // agreeing lines are fine; two disagreeing ones are "cannot tell".
        java.util.Set<String> found = new java.util.LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (!trimmed.startsWith(PIN_PREFIX)) continue;
            String rest = trimmed.substring(PIN_PREFIX.length());
            // The CLOSING `}"` must be on this line. It is what makes a line
            // continuation unreadable rather than truncated: a wrapped pin
            // leaves the brace on the next line, so the search fails and this
            // reports "cannot tell". Falling back to end-of-line here would
            // return a truncated path and call a healthy home dangling.
            //
            // A separate `endsWith("\\")` guard stood here and was REMOVED: the
            // epic's vacuity rule could not make it fail, because every
            // continuation it was meant to catch is already caught by this
            // search. A guard that cannot fail is not a guard.
            int end = rest.indexOf("}\"");
            if (end < 0) return java.util.Optional.empty();
            String pin = rest.substring(0, end);
            if (pin.isBlank()) return java.util.Optional.empty();
            // NOT A LITERAL PATH. A pin carrying `$`, a backtick or `$(` is
            // computed by the shell at run time, and the text is not what will
            // be exec'd. Reading it literally makes a HEALTHY home look
            // dangling — and the caller's response to "dangling" is to push
            // that home off its own working front door, so a false positive
            // here is worse than no check at all.
            if (pin.indexOf('$') >= 0 || pin.indexOf('`') >= 0) {
                return java.util.Optional.empty();
            }
            found.add(pin);
        }
        return found.size() == 1
                ? java.util.Optional.of(Path.of(found.iterator().next()))
                : java.util.Optional.empty();
    }

    /**
     * Whether {@code entrypoint}'s pin names something that is gone.
     *
     * <p>False when there is no readable literal pin — see
     * {@link #pinnedCliIn}, and note that "cannot tell" must never be reported
     * as "broken" here.
     *
     * <p>{@code isRegularFile} as well as {@code isExecutable}, because a
     * DIRECTORY is executable on POSIX (that is what the execute bit means for
     * one): a pin naming a directory would otherwise pass as a live build. The
     * link is followed, so a pin through a symlink to a real build is live and
     * a pin through a dangling symlink is not.
     */
    public static java.util.Optional<Path> danglingPinIn(Path entrypoint) {
        Path pin = pinnedCliIn(entrypoint).orElse(null);
        if (pin == null) return java.util.Optional.empty();
        return Files.isRegularFile(pin) && Files.isExecutable(pin)
                ? java.util.Optional.empty()
                : java.util.Optional.of(pin);
    }
}
