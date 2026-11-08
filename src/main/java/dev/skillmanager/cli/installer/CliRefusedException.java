package dev.skillmanager.cli.installer;

import java.io.IOException;

/**
 * #428 R3/R5: an install or rebuild that {@link InstallerRegistry#installOne}
 * REFUSED, as opposed to one that failed.
 *
 * <p>Both refusals #404 added are this type, so every caller can tell them
 * apart from a download that broke and report them the same way:
 *
 * <ul>
 *   <li>{@link Kind#SHARED_PIN_CONFLICT}: a unit moved its pin on a CLI row
 *       that another live unit still claims at the old pin. Nothing is
 *       installed and nothing is recorded.</li>
 *   <li>{@link Kind#PIN_MOVED_NOT_REINSTALLED}: the pin moved, and the backend
 *       did not reinstall, so {@code bin/cli} still holds the old install.</li>
 * </ul>
 *
 * <p>A refusal is a state the home is in until a person acts, so it is
 * reported as such: callers turn it into a
 * {@link dev.skillmanager.effects.ContextFact.CliRefused} fact, which makes the
 * command exit non-zero and appears in its JSON. It is not a warning that
 * scrolls past an exit 0.
 */
public final class CliRefusedException extends IOException {

    public enum Kind {
        SHARED_PIN_CONFLICT("shared-pin-conflict"),
        PIN_MOVED_NOT_REINSTALLED("pin-moved-not-reinstalled");

        private final String token;

        Kind(String token) { this.token = token; }

        public String token() { return token; }
    }

    private final Kind kind;
    private final String unitName;
    private final String depName;

    public CliRefusedException(Kind kind, String unitName, String depName, String message) {
        super(message);
        this.kind = kind;
        this.unitName = unitName;
        this.depName = depName;
    }

    public Kind kind() { return kind; }

    public String unitName() { return unitName; }

    public String depName() { return depName; }
}
