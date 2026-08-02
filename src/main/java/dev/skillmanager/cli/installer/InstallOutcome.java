package dev.skillmanager.cli.installer;

/**
 * <b>What a CLI-dependency install actually did.</b>
 *
 * <p>The distinction this type exists to make is between an EVENT and a
 * STATE. A dep that was installed, or that failed, is something that happened
 * during this run and that a reader may have to act on. A dep that was already
 * on PATH is a property of the machine, unchanged by the run, and identical on
 * every subsequent run forever.
 *
 * <p>Measured on the operator's real 20-unit home: {@code sync} printed 26
 * {@code ✓ cli: …} lines of which <b>18 were "already on PATH"</b>. Those 18
 * lines scale with the number of DECLARED deps rather than with anything the
 * command did, and on a home with more units they are most of the output. They
 * are now {@link dev.skillmanager.util.Log#detail} — run log always, console
 * under {@code --verbose} — and the console gets the count.
 *
 * <p>Returned rather than accumulated in a global so the classification stays
 * with the backend that knows it, and so the two paths that install CLI deps
 * ({@code sync}'s bulk {@link dev.skillmanager.lock.CliInstallRecorder} and
 * {@code install}'s decomposed {@code RunCliInstall} effect) can report the
 * same thing without agreeing about anything else.
 */
public enum InstallOutcome {

    /** Nothing happened: the binary was already on PATH or already installed. */
    ALREADY_PRESENT,

    /** Something happened: an artifact was fetched, built, linked, or run. */
    INSTALLED,

    /**
     * The backend declined — unknown or unavailable backend, no install target
     * for this platform. A warning naming the reason is printed at the point of
     * refusal; this only classifies it for the rollup.
     */
    SKIPPED;

    /** Whether this outcome is an event the console should report per item. */
    public boolean isEvent() {
        return this == INSTALLED;
    }
}
