package dev.skillmanager.resolve;

import dev.skillmanager.model.UnitKind;

import java.util.List;

/**
 * Why a coord couldn't be resolved into a {@link UnitDescriptor}. Sealed
 * sum so callers can dispatch exhaustively; the renderer (ticket 05+)
 * formats each variant for plan-print.
 */
public sealed interface ResolutionError
        permits ResolutionError.NotFound,
                ResolutionError.MultiKindCollision,
                ResolutionError.KindMismatch,
                ResolutionError.UnknownLayout,
                ResolutionError.FetchFailed {

    /** Human-readable message used by tests and plan-print. */
    String message();

    /**
     * No registered unit, no clonable repo, no readable local path.
     */
    record NotFound(String coord, String reason) implements ResolutionError {
        @Override public String message() {
            return "no unit found for coord '" + coord + "': " + reason;
        }
    }

    /**
     * The registry returned hits for both a skill and a plugin sharing
     * the bare name. Caller must disambiguate with {@code skill:} or
     * {@code plugin:}.
     */
    record MultiKindCollision(String coord, List<UnitKind> candidates) implements ResolutionError {
        public MultiKindCollision {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
        @Override public String message() {
            StringBuilder sb = new StringBuilder("ambiguous coord '").append(coord)
                    .append("': matches ");
            for (int i = 0; i < candidates.size(); i++) {
                if (i > 0) sb.append(" and ");
                sb.append(candidates.get(i).name().toLowerCase());
            }
            sb.append("; pin with skill: or plugin:");
            return sb.toString();
        }
    }

    /**
     * The coord pinned a kind (e.g. {@code skill:foo}) but the
     * resolved unit is a different kind.
     */
    record KindMismatch(String coord, UnitKind requested, UnitKind found) implements ResolutionError {
        @Override public String message() {
            return "coord '" + coord + "' requested " + requested.name().toLowerCase()
                    + " but resolved as " + found.name().toLowerCase();
        }
    }

    /**
     * A direct-git or local coord pointed at a directory that has
     * neither {@code .claude-plugin/plugin.json} nor {@code SKILL.md}
     * at the root.
     */
    record UnknownLayout(String coord, String path) implements ResolutionError {
        @Override public String message() {
            return "coord '" + coord + "' resolved to '" + path
                    + "' but the layout is neither a plugin nor a bare skill";
        }
    }

    /**
     * Git clone or local-path read failed. {@link #cause()} preserves
     * the underlying exception's message for diagnostics.
     */
    record FetchFailed(String coord, String cause) implements ResolutionError {
        @Override public String message() {
            return "fetch failed for coord '" + coord + "': " + cause;
        }
    }
}
