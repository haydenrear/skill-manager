package dev.skillmanager.commands;

import dev.skillmanager.store.HomeCloner;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Operations on a Skill Manager home as a whole, as distinct from the
 * units inside it.
 */
@Command(name = "home",
        description = "Inspect and copy Skill Manager homes.",
        subcommands = {
                HomeCommand.CloneCmd.class,
                HomeCommand.VerifyCmd.class
        })
public final class HomeCommand {

    /**
     * Produce a per-project home. The point of the copy is that
     * {@code SKILL_MANAGER_HOME} can be pointed at it and nothing reaches
     * back into the original — so the copy is not trusted, it is verified.
     */
    @Command(name = "clone",
            description = "Copy this home to a new root (skipping cache/) and verify "
                    + "that nothing in the copy still points at the original.")
    public static final class CloneCmd implements Callable<Integer> {

        @Option(names = "--to", required = true,
                description = "Destination home directory. Must not exist, or be empty.")
        Path to;

        @Option(names = "--from",
                description = "Source home. Defaults to $SKILL_MANAGER_HOME.")
        Path from;

        @Option(names = "--strict",
                description = "Also fail when authored unit content mentions the source "
                        + "home. Such references are historical records (spec .history, "
                        + "effect evidence) and rewriting them corrupts the unit, so they "
                        + "are reported but tolerated by default.")
        boolean strict;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Override
        public Integer call() throws Exception {
            Path source = from != null ? from : SkillStore.defaultStore().root();
            HomeCloner.Report report = HomeCloner.cloneHome(source, to, strict);
            print(report, json);
            return report.clean() ? 0 : 1;
        }
    }

    /**
     * Re-run only the check. Useful against a home cloned by other means
     * (an {@code rsync}, a container image build) before trusting it.
     */
    @Command(name = "verify",
            description = "Check that a home holds no absolute reference back to another home.")
    public static final class VerifyCmd implements Callable<Integer> {

        @Option(names = "--home", required = true, description = "Home to check.")
        Path home;

        @Option(names = "--against", required = true,
                description = "The home it must not reference — normally the one it was copied from.")
        Path against;

        @Option(names = "--strict", description = "Also fail on authored unit content references.")
        boolean strict;

        @Override
        public Integer call() throws Exception {
            HomeCloner.Verification result = HomeCloner.verify(against, home, strict);
            // Tolerated references are still references. Reporting only the
            // leak list would let "0 leaks" read as "nothing survives" while
            // authored content still names the other home.
            if (!result.contentReferences().isEmpty()) {
                Log.info("%d unit-content file(s) mention %s — historical records, tolerated%s",
                        result.contentReferences().size(), against,
                        strict ? " (counted as failures under --strict)" : "; re-run with --strict to fail on them");
                for (String ref : result.contentReferences()) Log.info("    %s", ref);
            }
            if (!result.danglingLinks().isEmpty()) {
                Log.warn("%d symlink(s) do not resolve in %s", result.danglingLinks().size(), home);
                for (String dangling : result.danglingLinks()) Log.warn("    %s", dangling);
            }
            if (result.clean()) {
                Log.ok("no %sreference to %s survives in %s",
                        result.contentReferences().isEmpty() ? "" : "repairable ", against, home);
                return 0;
            }
            Log.error("%d reference(s) to %s survive in %s",
                    result.leaks().size(), against, home);
            for (HomeCloner.Leak leak : result.leaks()) Log.error("  %s", leak);
            return 1;
        }
    }

    private static void print(HomeCloner.Report report, boolean json) {
        if (json) {
            System.out.println("""
                    {"source":"%s","dest":"%s","directories":%d,"files":%d,"symlinks":%d,\
                    "bytes":%d,"linksRelativized":%d,"stateReanchored":%d,\
                    "provisionedRewritten":%d,"leaks":%d,"contentReferences":%d,\
                    "danglingLinks":%d,"danglingReferences":%d,"clean":%b}"""
                    .formatted(esc(report.source().toString()), esc(report.dest().toString()),
                            report.directories(), report.files(), report.symlinks(),
                            report.bytes(), report.linksRelativized(), report.stateReanchored(),
                            report.provisionedRewritten(), report.leaks().size(),
                            report.contentReferences().size(), report.danglingLinks().size(),
                            report.danglingReferences().size(), report.clean()));
            return;
        }
        Log.info("  source:      %s", report.source());
        Log.info("  destination: %s", report.dest());
        Log.info("  copied:      %d dirs, %d files, %d links (%d bytes; %s skipped)",
                report.directories(), report.files(), report.symlinks(), report.bytes(),
                String.join(", ", HomeCloner.SKIPPED_DIRS.stream().sorted().toList()));
        Log.info("  re-anchored: %d links relativized, %d records, %d provisioned files",
                report.linksRelativized(), report.stateReanchored(), report.provisionedRewritten());
        if (!report.contentReferences().isEmpty()) {
            Log.info("  %d unit-content file(s) mention the source home — historical records, "
                    + "left as written", report.contentReferences().size());
        }
        int unresolved = report.danglingLinks().size() + report.danglingReferences().size();
        if (unresolved > 0) {
            Log.warn("  %d reference(s) do not resolve in the copy (targets under a skipped "
                    + "directory); re-provision with `skill-manager cli`", unresolved);
            for (String dangling : report.danglingLinks()) Log.warn("    link   %s", dangling);
            for (String dangling : report.danglingReferences()) Log.warn("    script %s", dangling);
        }
        if (report.clean()) {
            Log.ok("cloned home to %s — no path in it resolves back to %s",
                    report.dest(), report.source());
        } else {
            Log.error("clone verification FAILED — %d path(s) still point at %s",
                    report.leaks().size(), report.source());
            for (HomeCloner.Leak leak : report.leaks()) Log.error("    %s", leak);
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
