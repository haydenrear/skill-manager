package dev.skillmanager.cli.installer;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>What a provisioning pass did, in the shape the console reports it.</b>
 *
 * <p>One tally covers both surfaces that provision a home — package-manager
 * TOOLS ({@code uv}, {@code node}, {@code brew}) and unit CLI DEPS — because
 * they have the same three outcomes and the same problem: the "already there"
 * case scales with what is DECLARED, while the interesting cases scale with
 * what the run DID.
 *
 * <p>Measured on the operator's real 20-unit home, {@code sync} printed 4 tool
 * lines and 26 cli lines; 18 of the 26 said {@code already on PATH} and all 4
 * tool lines said {@code ready} or {@code on PATH}. Twenty-two lines out of
 * forty-five reported that nothing happened.
 *
 * <p>{@link #render(String)} is deliberately empty when there is nothing to
 * say, and never empty when there is: a run that provisioned nothing at all
 * prints no line, and a run that touched anything prints exactly one naming
 * every non-zero category. That is what lets a reader tell a no-op sync from
 * one that installed everything without reading either.
 */
public record ProvisionTally(int alreadyPresent, int installed, int missing, int failed) {

    public static final ProvisionTally EMPTY = new ProvisionTally(0, 0, 0, 0);

    public ProvisionTally plus(InstallOutcome outcome) {
        return switch (outcome) {
            case ALREADY_PRESENT -> new ProvisionTally(alreadyPresent + 1, installed, missing, failed);
            case INSTALLED -> new ProvisionTally(alreadyPresent, installed + 1, missing, failed);
            // A backend that declined is neither an event nor a steady state;
            // it is counted with the failures because the reason it declined
            // has already been printed as a warning and the reader needs the
            // count to match the warnings they can see.
            case SKIPPED -> new ProvisionTally(alreadyPresent, installed, missing, failed + 1);
        };
    }

    public ProvisionTally withMissing() {
        return new ProvisionTally(alreadyPresent, installed, missing + 1, failed);
    }

    public ProvisionTally withFailure() {
        return new ProvisionTally(alreadyPresent, installed, missing, failed + 1);
    }

    public int total() {
        return alreadyPresent + installed + missing + failed;
    }

    public boolean isEmpty() {
        return total() == 0;
    }

    /**
     * One line, or none.
     *
     * <p>Every non-zero category appears. {@code alreadyPresent} is included
     * even though nothing happened to it — dropping it is the failure mode this
     * whole change has to avoid, because "cli: 2 installed" and
     * "cli: 18 already present, 2 installed" answer different questions and
     * only the second one is checkable against the home.
     *
     * @param label the surface, e.g. {@code "cli"} or {@code "tools"}
     * @return the line, or {@code null} when there is nothing to report
     */
    public String render(String label) {
        if (isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        if (alreadyPresent > 0) parts.add(alreadyPresent + " already present");
        if (installed > 0) parts.add(installed + " installed");
        if (missing > 0) parts.add(missing + " missing");
        if (failed > 0) parts.add(failed + " failed");
        return label + ": " + String.join(", ", parts);
    }
}
