package dev.skillmanager.policy;

import java.io.IOException;

/**
 * Thrown when an operation that would mutate a home in place is attempted
 * against a home declared {@link HomePolicy#FROZEN}.
 *
 * <p>An {@link IOException} on purpose: every guarded entry point already
 * declares {@code throws IOException}, so adding the gate could not be
 * skipped by a caller that forgot to handle a new checked type. The
 * commands catch it specifically to print the remediation and exit
 * {@link #EXIT_CODE} instead of surfacing a stack trace.
 */
public final class FrozenHomeException extends IOException {

    /**
     * Exit code for "refused because the home is frozen". Distinct from
     * the sync-outcome codes (7 refused / 8 conflicted) because nothing
     * was attempted at all, and from 2 (usage) because the command line
     * was valid.
     */
    public static final int EXIT_CODE = 9;

    private final String operation;
    private final java.nio.file.Path homeRoot;

    FrozenHomeException(String operation, java.nio.file.Path homeRoot) {
        super(operation + " refused: home is frozen (" + homeRoot.resolve(HomePolicy.FILENAME)
                + " declares policy = \"frozen\"). A frozen home is a reproducibility "
                + "guarantee, not a suggestion — clone it (`skill-manager home clone`) to "
                + "get a live copy, or run `skill-manager home policy live` to thaw it "
                + "deliberately.");
        this.operation = operation;
        this.homeRoot = homeRoot;
    }

    /** The refused operation, e.g. {@code "sync"}. */
    public String operation() { return operation; }

    /** The frozen home. */
    public java.nio.file.Path homeRoot() { return homeRoot; }
}
