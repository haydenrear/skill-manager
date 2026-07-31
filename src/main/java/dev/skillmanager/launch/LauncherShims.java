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

    public record Result(Path dir, List<Path> written) {}

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
        Path pin = pinnedCli.toAbsolutePath().normalize();
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

        return new Result(dir, List.copyOf(written));
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
     */
    public static String script(String agent) {
        return """
                #!/usr/bin/env bash
                # Generated by `skill-manager home shims` — do not edit.
                #
                # Launches `%1$s` bound to the Skill Manager home this shim lives in.
                # The home is derived from the shim's own location, so a cloned home
                # needs no rewrite; every rule about the launch environment (PATH
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

                cli="${SKILL_MANAGER_CLI:-$home/bin/cli/skill-manager}"
                if [ ! -x "$cli" ]; then
                  echo "%1$s: the home at $home has no CLI entrypoint." >&2
                  echo "  expected: $cli" >&2
                  echo "  Re-provision it with \\`skill-manager home shims --home $home\\`," >&2
                  echo "  or set SKILL_MANAGER_CLI to the build this home should run." >&2
                  exit 127
                fi

                exec "$cli" exec --home "$home" -- %1$s "$@"
                """.formatted(agent);
    }

    /** Replaced with the absolute path of the pinned CLI. Not a format specifier. */
    static final String PIN_PLACEHOLDER = "@SKILL_MANAGER_CLI_PIN@";

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
        return CLI_TEMPLATE.replace(PIN_PLACEHOLDER, cli.toAbsolutePath().normalize().toString());
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
            export SKILL_MANAGER_HOME="$home"

            cli="${SKILL_MANAGER_CLI:-@SKILL_MANAGER_CLI_PIN@}"
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
            """;

}
