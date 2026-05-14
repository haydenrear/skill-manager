package dev.skillmanager.commands;

import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code skill-manager bindings list|show} surfaces the projection
 * ledger. Listing flattens every per-unit ledger into one table;
 * {@code show} drills down to one binding's projections.
 */
@Command(name = "bindings",
        description = "Inspect the projection ledger (list | show <bindingId>).",
        subcommands = {
                BindingsCommand.ListCmd.class,
                BindingsCommand.ShowCmd.class
        })
public final class BindingsCommand {

    @Command(name = "list", description = "List bindings across every unit.")
    public static final class ListCmd implements Callable<Integer> {

        @Option(names = "--unit", description = "Filter to one unit name.")
        String unit;

        @Option(names = "--root", description = "Filter to bindings under this target root (substring match).")
        String root;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Override
        public Integer call() {
            SkillStore store = SkillStore.defaultStore();
            BindingStore bs = new BindingStore(store);
            List<Binding> bindings = bs.listAll().stream()
                    .filter(b -> unit == null || b.unitName().equals(unit))
                    .filter(b -> root == null || (b.targetRoot() != null
                            && b.targetRoot().toString().contains(root)))
                    .collect(Collectors.toList());
            if (json) {
                return JsonOutput.print(new ListResult(bindings.stream().map(ListCmd::row).toList())) ? 0 : 2;
            }
            if (bindings.isEmpty()) {
                System.out.println("(no bindings)");
                return 0;
            }
            System.out.printf("%-28s  %-22s  %-18s  %-44s  %-16s  %s%n",
                    "ID", "UNIT", "SUB-ELEMENT", "TARGET", "POLICY", "MANAGED-BY");
            for (Binding b : bindings) {
                System.out.printf("%-28s  %-22s  %-18s  %-44s  %-16s  %s%n",
                        b.bindingId(),
                        b.unitName(),
                        b.subElement() == null ? "" : b.subElement(),
                        b.targetRoot() == null ? "" : b.targetRoot().toString(),
                        b.conflictPolicy().name().toLowerCase().replace('_', '-'),
                        b.source().name().toLowerCase().replace('_', '-'));
            }
            return 0;
        }

        private static Row row(Binding b) {
            return new Row(
                    b.bindingId(),
                    b.unitName(),
                    b.unitKind().name().toLowerCase(),
                    b.subElement(),
                    b.targetRoot() == null ? null : b.targetRoot().toString(),
                    b.conflictPolicy().name().toLowerCase().replace('_', '-'),
                    b.source().name().toLowerCase().replace('_', '-'));
        }

        public record ListResult(List<Row> bindings) {}
        public record Row(
                String id,
                String unit,
                String kind,
                String subElement,
                String target,
                String policy,
                String managedBy) {}
    }

    @Command(name = "show", description = "Show one binding's projections in detail.")
    public static final class ShowCmd implements Callable<Integer> {

        @Parameters(index = "0", description = "Binding id")
        String bindingId;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Override
        public Integer call() {
            SkillStore store = SkillStore.defaultStore();
            BindingStore bs = new BindingStore(store);
            var located = bs.findById(bindingId);
            if (located.isEmpty()) {
                Log.error("no binding with id %s", bindingId);
                return 1;
            }
            Binding b = located.get().binding();
            if (json) {
                return JsonOutput.print(new ShowResult(
                        b.bindingId(),
                        b.unitName(),
                        b.unitKind().name().toLowerCase(),
                        b.subElement(),
                        b.targetRoot() == null ? null : b.targetRoot().toString(),
                        b.conflictPolicy().name(),
                        b.source().name(),
                        b.createdAt(),
                        b.projections().stream().map(ShowCmd::projectionRow).toList())) ? 0 : 2;
            }
            System.out.println("binding:   " + b.bindingId());
            System.out.println("unit:      " + b.unitName() + " (" + b.unitKind() + ")");
            if (b.subElement() != null) System.out.println("sub:       " + b.subElement());
            System.out.println("target:    " + b.targetRoot());
            System.out.println("policy:    " + b.conflictPolicy());
            System.out.println("source:    " + b.source());
            System.out.println("createdAt: " + b.createdAt());
            System.out.println("projections:");
            for (Projection p : b.projections()) {
                System.out.printf("  - %-26s %s%n",
                        p.kind() + (p.backupOf() != null ? " (backup of " + p.backupOf() + ")" : ""),
                        formatPath(p.destPath()));
                if (p.sourcePath() != null) {
                    System.out.println("      source: " + p.sourcePath());
                }
            }
            return 0;
        }

        private static String formatPath(Path p) {
            return p == null ? "(null)" : p.toString();
        }

        private static ProjectionRow projectionRow(Projection p) {
            return new ProjectionRow(
                    p.kind().name(),
                    p.sourcePath() == null ? null : p.sourcePath().toString(),
                    p.destPath() == null ? null : p.destPath().toString(),
                    p.backupOf(),
                    p.boundHash());
        }

        public record ShowResult(
                String id,
                String unit,
                String kind,
                String subElement,
                String target,
                String policy,
                String source,
                String createdAt,
                List<ProjectionRow> projections) {}

        public record ProjectionRow(
                String kind,
                String source,
                String destination,
                String backupOf,
                String boundHash) {}
    }
}
