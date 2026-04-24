package dev.skillmanager.commands;

import dev.skillmanager.lock.CliLock;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(
        name = "cli",
        description = "Inspect the CLI lock (which tools are installed, which skills requested them).",
        subcommands = {CliCommand.List.class, CliCommand.Show.class, CliCommand.Path.class})
public final class CliCommand implements Runnable {
    @Override public void run() { new picocli.CommandLine(this).usage(System.out); }

    @Command(name = "list", description = "Show every installed CLI tool and its locked version.")
    public static final class List implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            CliLock lock = CliLock.load(store);
            var all = lock.all();
            if (all.isEmpty()) {
                System.out.println("(no CLI tools installed — install a skill that declares them)");
                return 0;
            }
            System.out.printf("%-8s %-28s %-14s %s%n", "BACKEND", "TOOL", "VERSION", "REQUESTED BY");
            for (CliLock.Entry e : all) {
                System.out.printf("%-8s %-28s %-14s %s%n",
                        e.backend(),
                        e.tool(),
                        e.version() == null ? "-" : e.version(),
                        String.join(", ", e.requestedBy()));
            }
            return 0;
        }
    }

    @Command(name = "show", description = "Dump a single lock entry as TOML.")
    public static final class Show implements Callable<Integer> {
        @Parameters(index = "0", description = "Format: <backend>:<tool> (e.g. pip:ruff)") String ref;

        @Override
        public Integer call() throws Exception {
            int colon = ref.indexOf(':');
            if (colon < 0) { Log.error("use <backend>:<tool>, got: %s", ref); return 1; }
            SkillStore store = SkillStore.defaultStore();
            store.init();
            CliLock.Entry e = CliLock.load(store).get(ref.substring(0, colon), ref.substring(colon + 1));
            if (e == null) { Log.warn("not found: %s", ref); return 1; }
            System.out.println("backend:      " + e.backend());
            System.out.println("tool:         " + e.tool());
            System.out.println("version:      " + (e.version() == null ? "-" : e.version()));
            System.out.println("spec:         " + (e.spec() == null ? "-" : e.spec()));
            if (e.sha256() != null) System.out.println("sha256:       " + e.sha256());
            System.out.println("requested_by: " + String.join(", ", e.requestedBy()));
            if (e.installedAt() != null) System.out.println("installed_at: " + e.installedAt());
            return 0;
        }
    }

    @Command(name = "path", description = "Print the path to the CLI lock file.")
    public static final class Path implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            System.out.println(store.root().resolve(CliLock.FILENAME).toString());
            return 0;
        }
    }
}
