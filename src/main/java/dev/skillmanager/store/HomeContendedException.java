package dev.skillmanager.store;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Refused because another operation holds this home's lock and did not let go
 * within the wait.
 *
 * <h2>Why a type and not a sentence</h2>
 *
 * <p>{@link HomeLock#acquire} threw a plain {@link IOException} whose message
 * said "is locked by another …". A consumer that wanted to tell "the home is
 * busy, try again" apart from "the home is broken" had to match on that
 * sentence — and #235 is the ticket about consumers of this CLI getting text
 * where they asked for structure. A caller can now branch on the type, and
 * {@code --json} can name the reason as {@code home_locked} without a regex
 * over someone else's prose.
 *
 * <p>It stays an {@link IOException} on purpose: every existing catch site
 * keeps working, and this is a subclass rather than a new checked type in the
 * signatures of {@code HomeSync}, {@code HomeCloseOut} and {@code Executor}.
 */
public final class HomeContendedException extends IOException {

    /** The machine-readable reason a {@code --json} consumer branches on. */
    public static final String ERROR_CODE = "home_locked";

    private final transient Path home;
    private final String operation;

    HomeContendedException(String operation, Path home, String detail) {
        super(operation + ": " + home + " " + detail);
        this.home = home;
        this.operation = operation;
    }

    /** The contended home. */
    public Path home() { return home; }

    /** The operation that gave up waiting for it. */
    public String operation() { return operation; }
}
