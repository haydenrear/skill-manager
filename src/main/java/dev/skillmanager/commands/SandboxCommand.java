package dev.skillmanager.commands;

import dev.skillmanager.sandbox.Confinement;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code skill-manager sandbox status} — <b>the one call that answers "is this
 * process confined?"</b> for a caller that is not a JVM.
 *
 * <p>{@link Confinement#current()} is that call in-process. This is the same
 * question from a shell, a graph node or a hook, so a driver in any language
 * has one thing to run instead of five variables to check by hand and a
 * working directory nobody remembers to look at.
 *
 * <p>The exit code is the answer, so {@code if skill-manager sandbox status;
 * then …} works without parsing: {@code 0} confined, {@code 14} declared but
 * escaping (the same code the refusal uses — one condition, one number), and
 * {@code 1} for "no confinement declared", which is a different thing from a
 * failed confinement and must not read as one.
 *
 * @see Confinement
 */
@Command(name = "sandbox",
        description = "Inspect this process's sandbox.",
        subcommands = SandboxCommand.StatusCmd.class)
public final class SandboxCommand {

    /** Declared, but at least one axis is outside the root. */
    public static final int ESCAPING_EXIT_CODE =
            dev.skillmanager.sandbox.ConfinementEscapeException.EXIT_CODE;

    /** No confinement was declared at all. */
    public static final int UNDECLARED_EXIT_CODE = 1;

    @Command(name = "status",
            description = "Report every axis that decides where this process writes — the "
                    + "store, the three agent roots, and the working directory — against the "
                    + "confinement root $" + Confinement.ROOT_ENV + " declares. "
                    + "Exit 0 confined, 1 no confinement declared, "
                    + ESCAPING_EXIT_CODE + " declared but escaping.")
    public static final class StatusCmd implements Callable<Integer> {

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Override
        public Integer call() {
            Confinement confinement = Confinement.current();
            // A document on EVERY exit path, including the non-zero ones: this
            // command's whole purpose is to be read by a driver, and #235's
            // lesson was a --json failure path that printed nothing at all.
            if (json) {
                return JsonOutput.print(confinement.toMap()) ? exitFor(confinement) : 2;
            }
            if (!confinement.declared()) {
                Log.warn("not confined — no $%s declared", Confinement.ROOT_ENV);
            } else if (confinement.confined()) {
                Log.ok("confined to %s", confinement.root());
            } else {
                Log.error("declared confinement to %s, but %d axis/axes escape it",
                        confinement.root(), confinement.escapes().size());
            }
            System.out.println(confinement.describe());
            return exitFor(confinement);
        }

        private static int exitFor(Confinement confinement) {
            if (!confinement.declared()) return UNDECLARED_EXIT_CODE;
            return confinement.confined() ? 0 : ESCAPING_EXIT_CODE;
        }
    }

    private SandboxCommand() {}
}
