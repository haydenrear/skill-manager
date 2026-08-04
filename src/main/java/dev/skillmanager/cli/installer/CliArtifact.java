package dev.skillmanager.cli.installer;

import dev.skillmanager.store.HomeCloner;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * The one answer to "is this CLI tool usable from THIS home".
 *
 * <h2>Three predicates, three proxies, one defect five times over</h2>
 *
 * <p>Three places asked this question and all three asked something cheaper
 * instead:
 *
 * <ul>
 *   <li>{@code SkillScriptBackend.declaredBinaryStillPresent} —
 *       {@code Files.isExecutable(bin/cli/<binary>)}</li>
 *   <li>{@code CliPresence.providedByThisHome} — the same</li>
 *   <li>{@code TarBackend} — {@code Files.exists(bin/cli/<name>)}</li>
 * </ul>
 *
 * <p>Each proxy fails on its own axis, and the axis that produced the newest
 * instance is the <b>SHAPE of the artifact</b>. Measured on one real clone,
 * plain {@code sync}, no {@code --force-scripts}:
 *
 * <pre>
 * skill-dev   SYMLINK -> ../../cache/uv-tools/skill-dev/bin/skill-dev
 *             clone skips cache/ -> dangling -> isExecutable FOLLOWS -> false
 *             -> RERAN, self-healed
 *
 * computeq    REGULAR bash wrapper: exec "&lt;home&gt;/cache/skill-script-deploy-helm-computeq/venv/bin/computeq"
 *             clone re-anchors the wrapper to the NEW home's path, which does not exist
 *             the wrapper itself is a fine executable -> isExecutable TRUE
 *             -> SKIPPED, never repaired
 * </pre>
 *
 * <p>One home, one backend, one sync: one dep heals and the other is
 * permanently broken, purely because one is a symlink and the other is a
 * wrapper. Seven of the ten shims in the operator's home are wrappers
 * ({@code computeq}, {@code helm-deploy}, {@code monitoring},
 * {@code tracing-observability-install}, …), so this is most of a fresh
 * worktree's toolchain, and it is why the standing advice has been
 * {@code sync --force-scripts}.
 *
 * <h2>Why the answer is deterministic resolution, and not execution</h2>
 *
 * <p>The obvious "does it work" test is to run the thing. Rejected:
 *
 * <ul>
 *   <li>These are third-party binaries. {@code sync} touches every declared
 *       dep on every pass, so "run them all" is an unbounded side effect on a
 *       command whose job is to be safe to re-run. {@code helm-deploy} and
 *       {@code monitoring} are deploy tools; {@code --version} is a convention,
 *       not a contract, and nothing stops one of them contacting a cluster.</li>
 *   <li>It is not even a better oracle. A wrapper that execs a missing
 *       interpreter fails at exec time whatever flag you pass it, so the cheap
 *       check below already catches it; and a tool that exits non-zero on
 *       {@code --version} for its own reasons would be reinstalled on every
 *       sync forever.</li>
 * </ul>
 *
 * <p>So there is <b>no execution here at all</b>, not even bounded and
 * last-resort. What replaces it is the observation that skill-manager is not
 * looking at an opaque artifact: it GENERATES these wrappers' targets, under
 * its own {@code cache/skill-script-&lt;unit&gt;-&lt;dep&gt;} and
 * {@code venvs/} conventions, and {@code home verify} already knows how to ask
 * whether a generated file names a path this home does not hold. That check is
 * {@link HomeCloner#missingReferencesIn}, and this class calls it rather than
 * growing a second one — because a GATE and a REPAIR that derive "broken"
 * independently will disagree, and the one that disagrees quietly is the
 * repair. That disagreement is the whole bug: verify refused on exactly these
 * wrappers while the backend that was supposed to fix them saw nothing wrong.
 *
 * <h2>What this predicate does NOT cover</h2>
 *
 * <p>Stated so the next reader does not assume more than it checks:
 *
 * <ul>
 *   <li><b>Runtime-computed targets.</b> {@code bin/cli/tlc2} resolves its jar
 *       as {@code "$(dirname $0)"/.spec-double-compiler/tla2tools.jar} — no
 *       absolute path in the file — so a missing jar is invisible here. That
 *       wrapper checks itself and says so at exec time; this one does not.</li>
 *   <li><b>Targets outside the re-provisionable roots.</b> The scan only
 *       counts references under {@code cache/ venvs/ tools/ npm/ pm/}, because
 *       those are what a re-provision rebuilds. A wrapper naming a missing
 *       {@code &lt;home&gt;/skills/…} path is not flagged — and should not be,
 *       since a clone carries {@code skills/} and a missing one is a different
 *       failure.</li>
 *   <li><b>Anything outside this home.</b> A wrapper execing
 *       {@code /opt/homebrew/bin/thing} that has been uninstalled is not
 *       detected. Out of scope by construction: this asks whether the HOME is
 *       whole, not whether the machine is.</li>
 *   <li><b>Binary artifacts.</b> {@code missingReferencesIn} skips anything
 *       that looks binary, so a compiled launcher with an embedded absolute
 *       path is not read.</li>
 *   <li><b>Whether the tool is the RIGHT VERSION.</b> Unchanged and still
 *       missing — see {@code CliPresence}'s header for the
 *       {@code CliVersionConflict} gap. "Usable" here means "will exec", not
 *       "matches the manifest's pin".</li>
 *   <li><b>Correct behaviour.</b> Obviously. Nothing is run.</li>
 * </ul>
 */
public final class CliArtifact {

    private CliArtifact() {}

    /**
     * Why an artifact is or is not usable. {@code reason} is null exactly when
     * {@link #usable()} — a caller reporting a skip has something to print, and
     * a caller reporting a reinstall has the cause.
     */
    public record Verdict(Path path, String reason) {

        public boolean usable() { return reason == null; }

        @Override
        public String toString() {
            return usable() ? path + " (usable)" : path + " — " + reason;
        }
    }

    /** The artifact this home would run for {@code name}, and whether it works. */
    public static Verdict inHome(SkillStore store, String name) {
        if (store == null) return new Verdict(null, "no home");
        if (name == null || name.isBlank()) return new Verdict(null, "no name declared");
        return inspect(store.cliBinDir().resolve(name), store.root());
    }

    /** {@link #inHome} reduced to a boolean, for callers with nothing to say. */
    public static boolean usableInHome(SkillStore store, String name) {
        return inHome(store, name).usable();
    }

    /**
     * Whether {@code artifact} will run, for a home rooted at {@code homeRoot}.
     *
     * <p>The order matters only for the QUALITY of the reason: a dangling
     * symlink is already non-executable, so the branch above it exists to say
     * which target is missing rather than "not executable", which would send
     * the reader to {@code chmod}.
     */
    public static Verdict inspect(Path artifact, Path homeRoot) {
        if (artifact == null) return new Verdict(null, "no path");
        if (!Files.exists(artifact, LinkOption.NOFOLLOW_LINKS)) {
            return new Verdict(artifact, "absent");
        }
        if (Files.isSymbolicLink(artifact) && !Files.exists(artifact)) {
            String target;
            try {
                target = Files.readSymbolicLink(artifact).toString();
            } catch (IOException unreadable) {
                target = "?";
            }
            // The shape a clone leaves for every uv/npm-installed tool: the
            // link is copied, the tree it points into is skipped.
            return new Verdict(artifact, "dangling symlink -> " + target);
        }
        if (Files.isDirectory(artifact)) {
            return new Verdict(artifact, "is a directory");
        }
        if (!Files.isExecutable(artifact)) {
            return new Verdict(artifact, "present but not executable");
        }
        if (homeRoot != null) {
            // The shape a clone leaves for every generated wrapper, and the
            // one every previous predicate called healthy. Same scanner
            // `home verify` refuses on.
            List<String> missing = HomeCloner.missingReferencesIn(artifact, homeRoot);
            if (!missing.isEmpty()) {
                return new Verdict(artifact,
                        "runs " + missing.get(0) + ", which this home does not hold"
                                + (missing.size() > 1 ? " (+" + (missing.size() - 1) + " more)" : ""));
            }
        }
        return new Verdict(artifact, null);
    }
}
