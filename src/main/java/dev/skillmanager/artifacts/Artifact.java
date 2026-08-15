package dev.skillmanager.artifacts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One derived thing a home holds, named.
 *
 * <p>Skill Manager derives nine classes of thing and, before this record,
 * named none of them: every class had its own per-command side knowledge
 * ({@code cli-lock.toml}, {@code installed/<u>.projections.json}, a
 * regenerated marketplace tree, a gateway config, 5.3 MB of drift digest) and
 * there was no noun that meant "the thing that was derived". This is that
 * noun, and it carries exactly five facts nothing else records: a stable
 * {@link #id()}, a {@link #kind()}, an owning unit, its declared
 * {@link #inputs()}, and its {@link #outputs()}.
 *
 * <h2>Two distinctions this record exists to keep apart</h2>
 *
 * <p><b>Declared versus materialized.</b> {@link #materialization()} is derived
 * from probing {@link #outputs()} and is never asserted. A cloned ticket home
 * ships {@code bin/cli/jinja2 -> ../../venvs/jinja2-cli/bin/jinja2} and
 * {@code bin/cli/skill-dev -> ../../cache/uv-tools/…} while carrying neither
 * {@code venvs/} nor {@code cache/}: the shim exists, so every presence test
 * in the system passes, and it does not run. That is {@link Presence#DANGLING}
 * here, and it is the state ARTI-07 needs in order to decide what a clone must
 * build rather than rebuilding everything.
 *
 * <p><b>Recorded versus actual.</b> {@link #recorded()} is what some record in
 * the home CLAIMS; {@link #actual()} is what the disk says; {@link #agreement()}
 * is the comparison. They are separate fields because they demonstrably differ:
 * the project home records {@code hyper-experiments-finance} at {@code 419d7388}
 * while its own store checkout sits at {@code 5b3c8d2b} — which IS that unit's
 * {@code origin/main} — and {@code deploy-helm} disagrees the same way. Three of
 * twenty rows do not describe their own store, and {@code skt check} reads the
 * row rather than the bytes, so it reports a pull for a unit with nothing to
 * pull. A model that had one "hash" field could not express that, and a listing
 * built on it would repeat the claim instead of testing it.
 *
 * <h2>Where the facts live</h2>
 *
 * <p>Every fact in {@link #recorded()} already has an owner —
 * {@code installed/<u>.json}'s {@code gitHash}, {@code cli-lock.toml}'s
 * {@code install_fingerprint}, a projection's {@code boundHash},
 * {@code home.digest.json}'s per-unit digest. An {@code Artifact} REFERENCES
 * that owner through {@link #source()} and does not become a second copy of it.
 * A second copy is a second thing that can disagree with the disk, which is the
 * defect this epic exists to remove rather than to reproduce.
 */
public record Artifact(
        /** Stable across homes. See {@link ArtifactIds} for the grammar and why. */
        String id,
        ArtifactKind kind,
        /** The unit this artifact belongs to, or null when nothing owns it. */
        String owner,
        /** Declared input references. See {@link ArtifactIds} for the schemes. */
        List<String> inputs,
        List<Output> outputs,
        /** Home-relative path of the record that declares this artifact; may be null. */
        String source,
        /** What a record in the home claims about this artifact. */
        Map<String, String> recorded,
        /** What the disk says, where asking is cheap enough to ask on a read. */
        Map<String, String> actual,
        Agreement agreement,
        Origin origin
) {

    public Artifact {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        recorded = recorded == null ? Map.of() : Map.copyOf(recorded);
        actual = actual == null ? Map.of() : Map.copyOf(actual);
        if (agreement == null) agreement = Agreement.UNRECORDED;
        if (origin == null) origin = Origin.HOME;
    }

    /**
     * One place this artifact lands.
     *
     * <p>{@link #path()} is <b>home-relative</b> when {@link Scope#HOME} and
     * absolute when {@link Scope#EXTERNAL}. Only the first shape is ever
     * written to the ledger — see {@link ArtifactLedger} for why an absolute
     * path in a home's own state file is a claim over somebody else's
     * directory rather than a description of this home.
     */
    public record Output(String path, Scope scope, Presence presence) {
        public static Output inHome(String relative, Presence presence) {
            return new Output(relative, Scope.HOME, presence);
        }

        public static Output external(String absolute, Presence presence) {
            return new Output(absolute, Scope.EXTERNAL, presence);
        }
    }

    /** Whether an output lands inside this home or somewhere else on the machine. */
    public enum Scope { HOME, EXTERNAL }

    /** What probing an output path found. */
    public enum Presence {
        /** The path exists, and if it is a symlink its target exists too. */
        PRESENT,
        /** Nothing is at the path. */
        MISSING,
        /**
         * A symlink whose target does not exist. Its own state rather than
         * {@link #MISSING} because every presence check in the system passes on
         * it — that is precisely how a shim into a skipped {@code cache/} tree
         * reported healthy forever.
         */
        DANGLING,
        /** Not probed. */
        UNKNOWN
    }

    /** Whether the artifact's outputs are actually on disk. */
    public enum Materialization {
        /** Every output is {@link Presence#PRESENT}. */
        MATERIALIZED,
        /** Some outputs are present and some are not. */
        PARTIAL,
        /**
         * The artifact is declared and none of its outputs are usable. What a
         * cloned home's skipped trees look like before anything rebuilds them,
         * and what a dangling shim looks like once the model can tell the
         * difference.
         */
        DECLARED_ONLY,
        /**
         * At least one output could not be probed, so no verdict is available.
         *
         * <p>Reachable, and not a placeholder: {@code cli-lock.toml} keys a row
         * by the PACKAGE name ({@code brew:opentofu}) while the shim it produced
         * is named for the BINARY ({@code bin/cli/tofu}), and the lock does not
         * record the second. When the declaring unit is gone the mapping is gone
         * with it, and "I cannot tell you where this artifact landed" is the
         * only true answer. Reporting {@code declared-only} instead would be a
         * missing-file claim about a path that was never the artifact.
         */
        UNKNOWN
    }

    /** How a recorded claim compares with the disk. */
    public enum Agreement {
        /** Something is recorded and the disk agrees with it. */
        AGREES,
        /**
         * Something is recorded and the disk says otherwise. The state that
         * moved this epic's headline baseline from 2/9 to 1/9.
         */
        DISAGREES,
        /** Nothing about this artifact's inputs is recorded anywhere. */
        UNRECORDED,
        /**
         * Something is recorded, and this home cannot cheaply check it — a
         * store that is not a git checkout, a digest whose recomputation is a
         * full walk of the unit. Never folded into {@link #AGREES}: "I did not
         * look" and "I looked and it matched" are different claims.
         */
        UNVERIFIABLE
    }

    /** Where the listing learned about this artifact. */
    public enum Origin {
        /** Derived from the home's live records on this pass. */
        HOME,
        /**
         * In {@code artifacts.lock.toml} and NOT found in the home now. The
         * declared-but-absent case: a clone that skipped {@code cache/} still
         * knows the trees are supposed to exist, which is what makes a
         * demand-driven build possible at all.
         */
        LEDGER,
        /** Both — recorded previously and still derivable now. */
        LEDGER_AND_HOME
    }

    public Materialization materialization() {
        if (outputs.isEmpty() && origin == Origin.LEDGER) {
            // The home did not produce this artifact on this pass, and it has
            // no output path to probe. It is declared and nothing more — the
            // state a clone is in for everything under the trees it skipped.
            return Materialization.DECLARED_ONLY;
        }
        if (outputs.isEmpty()) {
            // A record-shaped artifact (a digest row, an MCP registration) has
            // no output of its own; its source record IS the output, and the
            // backfill only emits it when that record was read.
            return source == null ? Materialization.DECLARED_ONLY : Materialization.MATERIALIZED;
        }
        int present = 0;
        int unknown = 0;
        for (Output output : outputs) {
            if (output.presence() == Presence.PRESENT) present++;
            else if (output.presence() == Presence.UNKNOWN) unknown++;
        }
        if (present == outputs.size()) return Materialization.MATERIALIZED;
        // Ordered so that "I could not look" never masquerades as an answer,
        // and never overrides an output this pass positively found missing.
        if (unknown > 0) return Materialization.UNKNOWN;
        return present == 0 ? Materialization.DECLARED_ONLY : Materialization.PARTIAL;
    }

    /** This artifact with its outputs replaced (used by the ledger overlay). */
    public Artifact withOutputs(List<Output> next) {
        return new Artifact(id, kind, owner, inputs, next, source,
                recorded, actual, agreement, origin);
    }

    /** This artifact restamped with a different {@link Origin}. */
    public Artifact withOrigin(Origin next) {
        return new Artifact(id, kind, owner, inputs, outputs, source,
                recorded, actual, agreement, next);
    }

    /** Lowercase-hyphen enum rendering, shared by the JSON view and the ledger. */
    static String token(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Insertion-ordered map with null values dropped — a fact nobody has is absent. */
    public static Map<String, String> facts(String... keyThenValue) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyThenValue.length; i += 2) {
            if (keyThenValue[i] != null && keyThenValue[i + 1] != null) {
                out.put(keyThenValue[i], keyThenValue[i + 1]);
            }
        }
        return out;
    }
}
