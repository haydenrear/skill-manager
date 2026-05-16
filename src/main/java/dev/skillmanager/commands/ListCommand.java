package dev.skillmanager.commands;

import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.effects.UnitReadProblemReporter;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager list} — render every installed unit with
 * kind-aware columns.
 *
 * <p>Layout:
 * <pre>
 * NAME                  KIND     VERSION    BINDINGS  SHA       SOURCE
 * repo-intelligence     plugin   0.4.2      2         abc123    registry
 * hello-skill           skill    1.0.0      1         def456    registry
 * </pre>
 *
 * <p>Column shape is stable — scripts that grep / awk the output by
 * column position should treat this as the current contract. Empty
 * fields render as {@code -} so the alignment stays consistent
 * regardless of which fields the installed-record happens to carry.
 */
@Command(name = "list", aliases = "ls", description = "List installed units.")
public final class ListCommand implements Callable<Integer> {

    private final SkillStore store;

    @Option(names = "--json", description = "Emit machine-readable JSON.")
    boolean json;

    public ListCommand() {
        this(SkillStore.defaultStore());
    }

    public ListCommand(SkillStore store) {
        this.store = store;
    }

    @Override
    public Integer call() throws Exception {
        store.init();
        var listed = store.listInstalledUnits();
        UnitReadProblemReporter.render(store, listed.problems(), false);
        List<AgentUnit> units = listed.units();
        if (units.isEmpty()) {
            if (json) {
                return JsonOutput.print(new Result(List.of())) ? 0 : 2;
            }
            System.out.println("(no units installed — use `skill-manager install <source>`)");
            return 0;
        }

        UnitStore sources = new UnitStore(store);
        BindingStore bindings = new BindingStore(store);
        List<Row> rows = rows(units, sources, bindings);
        if (json) {
            return JsonOutput.print(new Result(rows)) ? 0 : 2;
        }

        System.out.printf("%-28s %-8s %-10s %-9s %-9s %s%n",
                "NAME", "KIND", "VERSION", "BINDINGS", "SHA", "SOURCE");
        for (Row row : rows) {
            System.out.printf("%-28s %-8s %-10s %-9d %-9s %s%n",
                    row.name(),
                    row.kind(),
                    row.version() == null ? "-" : row.version(),
                    row.bindings(),
                    row.shortSha() == null ? "-" : row.shortSha(),
                    row.source() == null ? "-" : row.source());
        }
        return 0;
    }

    private static List<Row> rows(List<AgentUnit> units, UnitStore sources, BindingStore bindings) {
        List<Row> rows = new ArrayList<>();
        for (AgentUnit u : units) {
            InstalledUnit rec = sources.read(u.name()).orElse(null);
            String version = u.version();
            int bindingCount = bindings.read(u.name()).bindings().size();
            String shortSha = rec != null && rec.gitHash() != null && !rec.gitHash().isBlank()
                    ? truncate(rec.gitHash(), 7)
                    : null;
            String source = rec != null && rec.installSource() != null
                    ? rec.installSource().name().toLowerCase()
                    : null;
            rows.add(new Row(
                    u.name(),
                    u.kind().name().toLowerCase(),
                    version,
                    bindingCount,
                    rec == null ? null : rec.gitHash(),
                    shortSha,
                    source));
        }
        return rows;
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    public record Result(List<Row> units) {}

    public record Row(
            String name,
            String kind,
            String version,
            int bindings,
            String sha,
            String shortSha,
            String source) {}
}
