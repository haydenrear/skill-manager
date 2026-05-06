package dev.skillmanager.commands;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager list} — render every installed unit (skills +
 * plugins, post-ticket-14) with kind-aware columns.
 *
 * <p>Layout:
 * <pre>
 * NAME                  KIND    VERSION    SHA       SOURCE
 * repo-intelligence     plugin  0.4.2      abc123    registry
 * hello-skill           skill   1.0.0      def456    registry
 * </pre>
 *
 * <p>Column shape is stable — scripts that grep / awk the output by
 * column position keep working. Empty fields render as {@code -} so
 * the alignment stays consistent regardless of which fields the
 * installed-record happens to carry.
 */
@Command(name = "list", aliases = "ls", description = "List installed units (skills and plugins).")
public final class ListCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        List<AgentUnit> units = store.listInstalledUnits();
        if (units.isEmpty()) {
            System.out.println("(no units installed — use `skill-manager install <source>`)");
            return 0;
        }

        UnitStore sources = new UnitStore(store);
        System.out.printf("%-28s %-7s %-10s %-9s %s%n",
                "NAME", "KIND", "VERSION", "SHA", "SOURCE");
        for (AgentUnit u : units) {
            InstalledUnit rec = sources.read(u.name()).orElse(null);
            String version = u.version() != null ? u.version() : "-";
            String sha = rec != null && rec.gitHash() != null && !rec.gitHash().isBlank()
                    ? truncate(rec.gitHash(), 7)
                    : "-";
            String source = rec != null && rec.installSource() != null
                    ? rec.installSource().name().toLowerCase()
                    : "-";
            System.out.printf("%-28s %-7s %-10s %-9s %s%n",
                    u.name(),
                    u.kind().name().toLowerCase(),
                    version,
                    sha,
                    source);
        }
        return 0;
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }
}
