package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.cli.installer.CliShimPruner;
import dev.skillmanager.cli.installer.InstallerRegistry;
import dev.skillmanager.model.CliDependency;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * DEF-007: a delete that follows a link out of the home, on all three tiers.
 *
 * <h2>The measurement this reproduces</h2>
 *
 * <p>2026-08-21, on the HIS-10 branch, two hand-built homes and no clone
 * involved. {@code homeB/bin/cli} was a symlink at {@code homeA/bin/cli}, and
 * homeA held two executables:
 *
 * <pre>
 * home verify homeB      -> FOREIGN_HOME bin/cli   (the GATE sees it)
 * CliShimPruner.prune(homeB)
 *                        -> "pruned bin/cli/alpha … not this home's parent store"
 *                           "pruned bin/cli/beta  … not this home's parent store"
 * homeA/bin/cli          -> 2 entries BEFORE, 0 entries AFTER
 * </pre>
 *
 * <p>{@code Files.isDirectory} follows a link and so does {@code Files.list}, so
 * every entry the prune saw was in the other home, every one was judged foreign
 * <em>correctly</em>, and every one was deleted <em>there</em>. Two readers of
 * one rule, disagreeing in the one direction that destroys bytes.
 *
 * <h2>Why three tiers and not one</h2>
 *
 * <p>The epic's damage has been to the ROOT home, which is the tier with no
 * tests. A one-tier fixture would also miss the question this ticket most has
 * to get right: <b>being a genuine child does not license deleting through a
 * directory link into the parent.</b> A sanctioned parent-store shim is an
 * ENTRY that lives in the child and points out; a {@code bin/cli} that IS a link
 * is a different shape, nothing sanctions it, and the last case here pins the
 * distinction so the fix cannot be "refuse anything pointing at a parent",
 * which would tear down the sharing every child home exists for.
 *
 * <p>Tier 1 is <b>root-shaped</b>: a store that is nobody's child, with no claim
 * anywhere and no descent record. It is synthetic — nothing here goes anywhere
 * near the operator's real root home.
 */
public final class PruneStaysInsideItsHomeTest {

    public static int run() throws Exception {
        return Tests.suite("PruneStaysInsideItsHomeTest")

                .test("ROOT tier: a prune whose bin/cli is a link at another home is refused", () -> {
                    Tiers t = Tiers.build("root");
                    Path victim = t.other;
                    linkBinCliAt(t.root, victim);

                    String refusal = refusalFrom(() -> CliShimPruner.prune(new SkillStore(t.root)));

                    assertEquals(2, entryCount(victim.resolve("bin/cli")),
                            "not one byte left the other home; before the guard this was 0");
                    assertTrue(refusal.contains(victim.resolve("bin/cli").toString())
                                    || refusal.contains(realOf(victim).resolve("bin/cli").toString()),
                            "the refusal names the offending path; got:\n" + refusal);
                    assertTrue(refusal.contains(t.root.toString())
                                    || refusal.contains(realOf(t.root).toString()),
                            "and the home it escaped; got:\n" + refusal);
                })

                .test("PROJECT tier: a REGISTERED child may not prune through a link at its parent",
                        () -> {
                    Tiers t = Tiers.build("project");
                    linkBinCliAt(t.project, t.root);

                    String refusal = refusalFrom(() -> CliShimPruner.prune(new SkillStore(t.project)));

                    assertEquals(2, entryCount(t.root.resolve("bin/cli")),
                            "the parent store keeps every entry — a real claim is not a "
                                    + "licence to empty the home that made it");
                    assertTrue(refusal.contains("outside the home"),
                            "and it is a refusal, not a warning; got:\n" + refusal);
                })

                .test("WORKTREE tier: a clone with a descent record may not either", () -> {
                    Tiers t = Tiers.build("worktree");
                    Path clone = t.cloneOfProject();
                    // The clone carries a HomeProvenance record, so it is a
                    // grandchild whose inherited shims ARE sanctioned. That is
                    // the strongest form of "allowed to reach into that home",
                    // and it still does not extend to deleting through it.
                    assertTrue(HomeProvenance.read(clone) != null,
                            "precondition: the clone recorded its descent");
                    linkBinCliAt(clone, t.root);

                    String refusal = refusalFrom(() -> CliShimPruner.prune(new SkillStore(clone)));

                    assertEquals(2, entryCount(t.root.resolve("bin/cli")),
                            "the root store keeps every entry");
                    assertTrue(refusal.contains("outside the home"),
                            "and it is a refusal; got:\n" + refusal);
                })

                .test("the SANCTIONED mirror is untouched by the guard — sharing still works", () -> {
                    // The shape the guard must NOT catch, on the tier where it
                    // matters: a real bin/cli directory in the child holding a
                    // LINK at the parent's entry. Deleting that link removes the
                    // link, which lives here; it is the delete rule, and it is
                    // why checkDelete resolves the parent rather than the leaf.
                    Tiers t = Tiers.build("sanctioned");
                    Path clone = t.cloneOfProject();
                    Path mirror = clone.resolve("bin/cli/alpha");
                    assertTrue(Files.isSymbolicLink(mirror),
                            "precondition: the clone inherited its parent's shim as a link");

                    List<CliShimPruner.Pruned> pruned = CliShimPruner.prune(new SkillStore(clone));

                    assertTrue(Files.exists(mirror, LinkOption.NOFOLLOW_LINKS),
                            "sharing the toolchain a parent provisioned is what a child home "
                                    + "is FOR; a guard that broke it would be a worse defect "
                                    + "than the one it closes");
                    assertEquals(0, pruned.size(), "and nothing was pruned: " + pruned);
                    assertEquals(2, entryCount(t.root.resolve("bin/cli")),
                            "and the parent still holds both entries");
                })

                .test("takeOwnershipOfShim — DEF-007's SECOND call site — is refused too", () -> {
                    // HIS-10 made this reachable. Before it, a clone's inherited
                    // link was pruned on the first sync and never survived to a
                    // rebuild; it now survives every sync, and the rebuild path
                    // deletes the entry at cliBinDir().resolve(spelling) — which
                    // is the other home's entry when bin/cli is a link.
                    Tiers t = Tiers.build("ownership");
                    Path victim = t.other;
                    linkBinCliAt(t.root, victim);
                    SkillStore store = new SkillStore(t.root);

                    String refusal = refusalFrom(
                            () -> InstallerRegistry.takeOwnershipOfShim(dep("alpha"), store));

                    assertEquals(2, entryCount(victim.resolve("bin/cli")),
                            "the other home keeps both entries");
                    assertTrue(refusal.contains("outside the home"),
                            "and the second call site refuses with the same message shape; got:\n"
                                    + refusal);
                })

                .test("takeOwnershipOfShim still takes ownership when the home is intact", () -> {
                    // The control. A guard that refused everything would pass
                    // every case above, and this epic has shipped exactly that
                    // defect before (a shim that ALWAYS refused satisfied "it
                    // refuses rather than succeeding silently" perfectly).
                    Tiers t = Tiers.build("ownership-control");
                    Path binCli = Files.createDirectories(t.root.resolve("bin/cli"));
                    Path inherited = binCli.resolve("gamma");
                    Files.createSymbolicLink(inherited, t.other.resolve("bin/cli/alpha"));

                    Path removed = InstallerRegistry.takeOwnershipOfShim(dep("gamma"),
                            new SkillStore(t.root));

                    assertTrue(removed != null, "it reported what it removed");
                    assertTrue(!Files.exists(inherited, LinkOption.NOFOLLOW_LINKS),
                            "the slot is this home's to write now");
                    assertEquals(2, entryCount(t.other.resolve("bin/cli")),
                            "and the other home is untouched — the LINK went, not its target");
                })

                .runAll();
    }

    // ------------------------------------------------------------- fixture

    /** The name both fixture homes carry an executable under. */
    private static CliDependency dep(String tool) {
        return new CliDependency(tool, "skill-script:" + tool, null, null, tool, false,
                Map.of("any", new CliDependency.InstallTarget(
                        null, null, tool, List.of(), null, "install.sh", List.of())));
    }

    /**
     * Three real tiers plus one unrelated home.
     *
     * <ul>
     *   <li>{@code root} — a store that is nobody's child. The synthetic
     *       root-shaped fixture; the operator's real root home is never
     *       touched by anything in this file.</li>
     *   <li>{@code project} — a REGISTERED child: root holds
     *       {@code child-homes/proj/child-home.json}, which is the evidence
     *       {@code ChildHomeLink.isChildOf} reads.</li>
     *   <li>{@code other} — a home with no relationship to any of them, for the
     *       cases where the point is that the two homes are strangers.</li>
     * </ul>
     */
    private static final class Tiers {
        final Path base;
        final Path root;
        final Path project;
        final Path other;

        private Tiers(Path base, Path root, Path project, Path other) {
            this.base = base;
            this.root = root;
            this.project = project;
            this.other = other;
        }

        static Tiers build(String label) throws Exception {
            Path base = Files.createTempDirectory("prune-confinement-" + label + "-");
            Path root = home(base.resolve("root"));
            Path project = home(base.resolve("proj/.skill-manager"));
            Path other = home(base.resolve("other"));

            executable(root.resolve("bin/cli/alpha"));
            executable(root.resolve("bin/cli/beta"));
            executable(other.resolve("bin/cli/alpha"));
            executable(other.resolve("bin/cli/beta"));

            // The project home is a registered child of root, and mirrors one
            // of its shims the way ChildHomeMaterializer does.
            Files.createDirectories(project.resolve("bin/cli"));
            Files.createSymbolicLink(project.resolve("bin/cli/alpha"),
                    root.resolve("bin/cli/alpha"));
            Path claim = Files.createDirectories(root.resolve("child-homes/proj"));
            Files.writeString(claim.resolve("child-home.json"), """
                    {
                      "id" : "proj",
                      "parentHome" : "%s",
                      "childHome" : "%s",
                      "units" : [ ],
                      "createdAt" : "2026-01-01T00:00:00Z"
                    }
                    """.formatted(root, project));

            return new Tiers(base, root, project, other);
        }

        /** A real clone, so the worktree tier carries a real descent record. */
        Path cloneOfProject() throws Exception {
            Path dest = base.resolve("wt/.skill-manager");
            HomeCloner.cloneHome(project, dest, false, false);
            return dest;
        }
    }

    private static Path home(Path root) throws Exception {
        new SkillStore(root).init();
        return root;
    }

    private static Path executable(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/usr/bin/env sh\nexit 0\n");
        file.toFile().setExecutable(true, false);
        return file;
    }

    /** Replace {@code home}'s {@code bin/cli} with a link at {@code victim}'s. */
    private static void linkBinCliAt(Path home, Path victim) throws Exception {
        Path binCli = home.resolve("bin/cli");
        if (Files.exists(binCli, LinkOption.NOFOLLOW_LINKS)) {
            dev.skillmanager.shared.util.Fs.deleteRecursive(binCli);
        }
        Files.createDirectories(binCli.getParent());
        Files.createSymbolicLink(binCli, victim.resolve("bin/cli"));
    }

    private static int entryCount(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return (int) stream.count();
        }
    }

    private static Path realOf(Path p) {
        return dev.skillmanager.shared.util.Fs.realOrNormalized(p);
    }

    /**
     * Run {@code body} and return the confinement refusal it raised.
     *
     * <p>Fails the case when nothing was refused, and that is the shape that
     * makes these assertions non-vacuous: with the guard removed the body
     * returns normally and this throws "expected a refusal", rather than the
     * case quietly passing on an unrelated exception.
     */
    private static String refusalFrom(ThrowingRunnable body) throws Exception {
        try {
            body.run();
        } catch (WriteOutsideHomeException refused) {
            return refused.getMessage();
        }
        throw new AssertionError("expected a write-confinement refusal, and nothing was refused "
                + "— the prune ran to completion in the wrong home");
    }

    private interface ThrowingRunnable { void run() throws Exception; }
}
