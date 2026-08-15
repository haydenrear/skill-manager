package dev.skillmanager.commands;

import dev.skillmanager.artifacts.Artifact;
import dev.skillmanager.artifacts.ArtifactIndex;
import dev.skillmanager.artifacts.ArtifactKind;
import dev.skillmanager.artifacts.ArtifactLedger;
import dev.skillmanager.artifacts.ArtifactReport;
import dev.skillmanager.store.NotAHomeException;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager artifacts} — name what this home derived.
 *
 * <p>A home derives nine classes of thing and, before this command, could not
 * answer "what did you build, and from what?" in one place. {@code list} and
 * {@code show} are the read surface; {@code record} is the only writer, and it
 * writes {@code artifacts.lock.toml} and nothing else.
 *
 * <p><b>This command changes no provisioning behaviour.</b> It does not
 * install, rebuild, prune or repair. A home that predates it lists correctly
 * with no migration, because {@link dev.skillmanager.artifacts.ArtifactBackfill}
 * reads the records that were already there.
 */
@Command(name = "artifacts",
        description = "Name and inspect the artifacts this home derived.",
        subcommands = {
                ArtifactsCommand.ListArtifacts.class,
                ArtifactsCommand.ShowArtifact.class,
                ArtifactsCommand.RecordLedger.class
        })
public final class ArtifactsCommand implements Callable<Integer> {

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }

    // ------------------------------------------------------------------ list

    /**
     * {@code skill-manager artifacts list} — every artifact, one line each.
     *
     * <p>Read-only in the strict sense the {@link dev.skillmanager.cli.CommandHomeAccess}
     * table means: run against a path that is not a home it produces a refusal
     * and no bytes.
     */
    @Command(name = "list",
            description = "List every derived artifact this home holds.")
    public static final class ListArtifacts implements Callable<Integer> {

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Option(names = "--kind", paramLabel = "<kind>",
                description = "Only this kind, e.g. cli-shim, unit-store, projection.")
        String kind;

        @Option(names = "--owner", paramLabel = "<unit>",
                description = "Only artifacts owned by this unit.")
        String owner;

        @Override
        public Integer call() throws IOException {
            SkillStore store = requireHome();
            if (store == null) return NotAHomeException.EXIT_CODE;

            ArtifactKind wanted = null;
            if (kind != null && !kind.isBlank()) {
                wanted = ArtifactKind.fromId(kind);
                if (wanted == null) {
                    Log.error("unknown artifact kind: %s (known: %s)", kind, knownKinds());
                    return 2;
                }
            }

            ArtifactIndex index = ArtifactIndex.of(store);
            List<Artifact> selected = new ArrayList<>();
            for (Artifact artifact : index.artifacts()) {
                if (wanted != null && artifact.kind() != wanted) continue;
                if (owner != null && !owner.isBlank() && !owner.equals(artifact.owner())) continue;
                selected.add(artifact);
            }

            if (json) {
                return JsonOutput.print(ArtifactReport.of(index, selected)) ? 0 : 2;
            }
            render(index, selected);
            return 0;
        }

        private static void render(ArtifactIndex index, List<Artifact> selected) {
            System.out.println("artifacts — " + index.home());
            System.out.println(index.ledgerPresent()
                    ? "ledger: " + ArtifactLedger.FILENAME + " ("
                            + index.ledger().rows().size() + " row(s), recorded "
                            + index.ledger().recordedAt() + ")"
                    : "ledger: none — backfilled from this home's existing records");
            System.out.println();
            int width = 0;
            for (Artifact artifact : selected) width = Math.max(width, artifact.id().length());
            for (Artifact artifact : selected) {
                System.out.printf("%-" + Math.max(width, 8) + "s  %-14s %-9s %s%n",
                        artifact.id(),
                        lower(artifact.materialization().name()),
                        lower(artifact.agreement().name()),
                        artifact.owner() == null ? "" : artifact.owner());
            }
            System.out.println();
            System.out.printf("%d artifact(s) across %d kind(s)%n",
                    selected.size(),
                    (int) selected.stream().map(Artifact::kind).distinct().count());
        }
    }

    // ------------------------------------------------------------------ show

    /** {@code skill-manager artifacts show <id>} — one artifact, in full. */
    @Command(name = "show",
            description = "Show one artifact: its inputs, its outputs, and what is recorded about it.")
    public static final class ShowArtifact implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<id>",
                description = "Artifact id, e.g. cli-shim:brew/kubectl.")
        String id;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Override
        public Integer call() throws IOException {
            SkillStore store = requireHome();
            if (store == null) return NotAHomeException.EXIT_CODE;

            ArtifactIndex index = ArtifactIndex.of(store);
            var found = index.byId(id);
            if (found.isEmpty()) {
                Log.error("no artifact with id %s in %s", id, index.home());
                List<String> near = index.idsMatching(id);
                if (!near.isEmpty()) {
                    System.err.println("  did you mean:");
                    Log.errorList("    ", near);
                }
                return 1;
            }
            Artifact artifact = found.get();
            if (json) {
                return JsonOutput.print(ArtifactReport.of(index, List.of(artifact))) ? 0 : 2;
            }
            System.out.println("id:              " + artifact.id());
            System.out.println("kind:            " + artifact.kind().id());
            System.out.println("owner:           "
                    + (artifact.owner() == null ? "(none — nothing in this home owns it)"
                                                : artifact.owner()));
            System.out.println("materialization: " + lower(artifact.materialization().name()));
            System.out.println("agreement:       " + lower(artifact.agreement().name()));
            System.out.println("origin:          " + lower(artifact.origin().name()));
            System.out.println("source:          "
                    + (artifact.source() == null ? "(none — no record declares it)"
                                                 : artifact.source()));
            System.out.println("inputs:");
            if (artifact.inputs().isEmpty()) System.out.println("  (none declared)");
            for (String input : artifact.inputs()) System.out.println("  " + input);
            System.out.println("outputs:");
            if (artifact.outputs().isEmpty()) System.out.println("  (none — the source record is the artifact)");
            for (Artifact.Output output : artifact.outputs()) {
                System.out.printf("  %-10s %-8s %s%n",
                        lower(output.presence().name()), lower(output.scope().name()), output.path());
            }
            if (!artifact.recorded().isEmpty()) {
                System.out.println("recorded:");
                artifact.recorded().forEach((k, v) -> System.out.println("  " + k + " = " + v));
            }
            if (!artifact.actual().isEmpty()) {
                System.out.println("actual:");
                artifact.actual().forEach((k, v) -> System.out.println("  " + k + " = " + v));
            }
            return 0;
        }
    }

    // ---------------------------------------------------------------- record

    /**
     * {@code skill-manager artifacts record} — persist the ledger.
     *
     * <p>Its own verb rather than a flag on {@code list}, so that {@code list}
     * can stay in the READ_ONLY column of
     * {@link dev.skillmanager.cli.CommandHomeAccess} without an exception. The
     * shape is {@code home drift --record}'s: a read that reports, and a
     * separate gesture that writes a baseline.
     *
     * <p>It records the <b>merged</b> set, not the backfilled one. Recording in
     * a home that skipped {@code cache/} must not erase the declaration that
     * those trees exist — that would delete the only evidence a clone has of
     * what it is missing, which is the knowledge the whole ledger is for.
     */
    @Command(name = "record",
            description = "Write " + ArtifactLedger.FILENAME + " from what this home holds.")
    public static final class RecordLedger implements Callable<Integer> {

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Override
        public Integer call() throws IOException {
            SkillStore store = requireHome();
            if (store == null) return NotAHomeException.EXIT_CODE;
            ArtifactIndex index = ArtifactIndex.of(store);
            ArtifactLedger ledger = ArtifactLedger.of(index.artifacts());
            ledger.save(store);
            if (json) {
                return JsonOutput.print(ArtifactReport.of(index, index.artifacts())) ? 0 : 2;
            }
            Log.ok("recorded %d artifact(s) to %s",
                    index.artifacts().size(), ArtifactLedger.file(store));
            return 0;
        }
    }

    // ----------------------------------------------------------------- utils

    /**
     * The ambient home, or null after printing the refusal.
     *
     * <p>A refusal rather than an empty listing, for the reason
     * {@link NotAHomeException} records: a report that says "0 artifacts" about
     * a path that is not a home reads exactly like a report about an empty one,
     * and the wrong path is usually the one already in the operator's hand.
     */
    static SkillStore requireHome() {
        SkillStore store = SkillStore.defaultStore();
        if (store.isHome()) return store;
        Log.error("%s is not a Skill Manager home", store.root());
        return null;
    }

    private static String knownKinds() {
        StringBuilder sb = new StringBuilder();
        for (ArtifactKind kind : ArtifactKind.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(kind.id());
        }
        return sb.toString();
    }

    private static String lower(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
