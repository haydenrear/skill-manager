package dev.skillmanager.project;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.bindings.MaterializationMode;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.policy.FrozenHomeException;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Real {@code project sync}: pull each unit's trunk, and push a home's edits
 * back as a branch and a pull request.
 *
 * <h2>Everything here runs against local bare repositories</h2>
 *
 * <p>A {@code git init --bare} directory is a fully functional remote: it can be
 * cloned, fetched from, and pushed to. So the whole pull/commit/branch/push
 * sequence is exercised for real, offline, with no possibility of touching
 * anyone's remote. The single step a fixture cannot provide — opening a pull
 * request — is behind {@link PullRequestOpener}, and a recording fake asserts on
 * the request that would have been made.
 *
 * <h2>What the assertions are about</h2>
 *
 * <p>Not "did it report the right thing". Every destructive case asserts
 * <b>bytes</b>: the agent's exact edit is read back out of the file afterwards,
 * and the trunk's hash in the bare repository is compared before and after. A
 * merge that quietly resolved in upstream's favour, or a push that fast-forwarded
 * the trunk, both report success — and both fail these tests.
 */
public final class ProjectTrunkSyncTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ProjectTrunkSyncTest");

        if (!GitOps.isAvailable()) {
            System.out.println("== ProjectTrunkSyncTest — SKIPPED, git is not on PATH");
            return 0;
        }

        // -------------------------------------------------------------- pull

        suite.test("pull merges the unit's trunk into the home", () -> {
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("trunk-skill");
                installFromUpstream(h.store(), upstream);
                upstream.commitOnTrunk("references/new.md", "upstream addition\n");

                UnitTrunkPull.Report report = pull(h.store(), "trunk-skill",
                        UnitTrunkPull.Options.defaults());

                UnitTrunkPull.UnitPull unit = only(report);
                assertEquals(UnitTrunkPull.Status.MERGED, unit.status(), "the trunk was merged");
                assertEquals("upstream addition\n",
                        Files.readString(h.store().skillDir("trunk-skill").resolve("references/new.md")),
                        "and the upstream content is in the home");
            }
        });

        suite.test("a unit already at its trunk reports up to date, not merged", () -> {
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("current-skill");
                installFromUpstream(h.store(), upstream);

                UnitTrunkPull.Report report = pull(h.store(), "current-skill",
                        UnitTrunkPull.Options.defaults());

                assertEquals(UnitTrunkPull.Status.UP_TO_DATE, only(report).status(), "no drift");
                assertTrue(report.changed().isEmpty(), "and nothing counted as changed");
            }
        });

        suite.test("a unit installed from a local path is reported, not failed", () -> {
            try (TestHarness h = TestHarness.create()) {
                Path unit = h.store().skillDir("local-skill");
                Fs.ensureDir(unit);
                Files.writeString(unit.resolve("SKILL.md"),
                        "---\nname: local-skill\ndescription: local\n---\nbody\n");
                new UnitStore(h.store()).write(new InstalledUnit(
                        "local-skill", "0.1.0", InstalledUnit.Kind.LOCAL_DIR,
                        InstalledUnit.InstallSource.LOCAL_FILE, null, null, null,
                        UnitStore.nowIso(), List.of(), UnitKind.SKILL));

                UnitTrunkPull.Report report = pull(h.store(), "local-skill",
                        UnitTrunkPull.Options.defaults());

                assertEquals(UnitTrunkPull.Status.NOT_GIT_TRACKED, only(report).status(),
                        "there is no trunk to pull, and that is information rather than an error");
                assertTrue(report.problems().isEmpty(), "so it does not fail the sync");
            }
        });

        // ------------------------------------------- never destroy, on pull

        suite.test("a unit with local edits is held back and its bytes are untouched", () -> {
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("edited-skill");
                installFromUpstream(h.store(), upstream);
                Path body = h.store().skillDir("edited-skill").resolve("SKILL.md");
                String agentEdit = "---\nname: edited-skill\ndescription: local\n---\n"
                        + "AGENT WROTE THIS AND IT MUST SURVIVE\n";
                Files.writeString(body, agentEdit);
                upstream.commitOnTrunk("references/new.md", "upstream addition\n");

                UnitTrunkPull.Report report = pull(h.store(), "edited-skill",
                        UnitTrunkPull.Options.defaults());

                // The bytes, first and foremost.
                assertEquals(agentEdit, Files.readString(body),
                        "the agent's edit is byte-for-byte what it was");
                assertFalse(Files.exists(h.store().skillDir("edited-skill").resolve("references/new.md")),
                        "and no merge happened at all — the upstream file is absent");
                assertEquals(UnitTrunkPull.Status.HELD_BACK, only(report).status(), "reported held back");
                assertEquals(1, report.heldBack().size(), "and counted");
            }
        });

        suite.test("--merge keeps both sides: the agent's edit and the trunk's", () -> {
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("merge-skill");
                installFromUpstream(h.store(), upstream);
                Path mine = h.store().skillDir("merge-skill").resolve("references/mine.md");
                Fs.ensureDir(mine.getParent());
                Files.writeString(mine, "AGENT WROTE THIS\n");
                upstream.commitOnTrunk("references/theirs.md", "upstream addition\n");

                UnitTrunkPull.Report report = pull(h.store(), "merge-skill",
                        new UnitTrunkPull.Options(true, null, false, null));

                assertEquals("AGENT WROTE THIS\n", Files.readString(mine),
                        "the agent's file survived the merge, byte-for-byte");
                assertEquals("upstream addition\n",
                        Files.readString(h.store().skillDir("merge-skill").resolve("references/theirs.md")),
                        "and upstream's arrived");
                assertEquals(UnitTrunkPull.Status.MERGED, only(report).status(), "reported merged");
            }
        });

        suite.test("a conflicting merge stops and the agent's text is still on disk", () -> {
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("conflict-skill");
                installFromUpstream(h.store(), upstream);
                Path body = h.store().skillDir("conflict-skill").resolve("SKILL.md");
                Files.writeString(body, "---\nname: conflict-skill\ndescription: d\n---\n"
                        + "AGENT SIDE OF THE CONFLICT\n");
                upstream.replaceOnTrunk("SKILL.md", "---\nname: conflict-skill\ndescription: d\n---\n"
                        + "UPSTREAM SIDE OF THE CONFLICT\n");

                UnitTrunkPull.Report report = pull(h.store(), "conflict-skill",
                        new UnitTrunkPull.Options(true, null, false, null));

                assertContains(Files.readString(body), "AGENT SIDE OF THE CONFLICT",
                        "the agent's text is still in the worktree — not resolved away");
                assertEquals(UnitTrunkPull.Status.CONFLICTED, only(report).status(),
                        "and the conflict is reported rather than resolved for you");
                assertEquals(1, report.problems().size(), "which makes the sync report a problem");
            }
        });

        suite.test("project sync does not destroy an edit in the child home", () -> {
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("child-edited");
                installFromUpstream(h.store(), upstream);
                Path repoRoot = Files.createTempDirectory("project-sync-childedit-");
                SkillProject project = projectFor(repoRoot, "child-edit-project", "child-edited");
                new ProjectDependencyResolver(h.store(), null)
                        .resolve(project, new ProjectDependencyResolver.Options(true, false));

                Path childBody = repoRoot.resolve(".skill-manager/skills/child-edited/SKILL.md");
                String agentEdit = "---\nname: child-edited\ndescription: d\n---\n"
                        + "AGENT IMPROVED THIS SKILL IN THE CHILD HOME\n";
                Files.writeString(childBody, agentEdit);
                upstream.commitOnTrunk("references/new.md", "upstream addition\n");

                ProjectSyncUseCase.Result result = new ProjectSyncUseCase(h.store(), null)
                        .sync(project, new ProjectDependencyResolver.Options(true, false));

                assertEquals(agentEdit, Files.readString(childBody),
                        "the child-home edit is byte-for-byte what the agent left");
                assertEquals(1, result.resolved().childHome().heldBack().size(),
                        "and the sync says it held it back");
                assertEquals("upstream addition\n",
                        Files.readString(h.store().skillDir("child-edited").resolve("references/new.md")),
                        "while the parent store did take the trunk's new content");
            }
        });

        suite.test("project sync reports the drift it caused and keeps the agent's bytes", () -> {
            // The two halves of #9 in one place, because they are the pair that
            // must hold together: the pull is announced (so the agent cannot go on
            // acting on a skill that moved) AND the agent's own edit survives it.
            // Either one alone has a passing-but-useless form — a report about a
            // unit that was overwritten is self-consistent, and an intact edit says
            // nothing about the unit next to it.
            try (TestHarness h = TestHarness.create()) {
                Upstream moving = Upstream.create("drift-moving");
                Upstream edited = Upstream.create("drift-edited");
                installFromUpstream(h.store(), moving);
                installFromUpstream(h.store(), edited);
                Path repoRoot = Files.createTempDirectory("project-sync-drift-");
                Files.writeString(repoRoot.resolve("skill-project.toml"), """
                        [project]
                        name = "drift-project"

                        [skills.moving]
                        source = "skill:drift-moving"

                        [skills.edited]
                        source = "skill:drift-edited"
                        """);
                SkillProject project = SkillProjectParser.load(repoRoot);
                new ProjectDependencyResolver(h.store(), null)
                        .resolve(project, new ProjectDependencyResolver.Options(true, false));
                // Baseline the home so the sync below has something to diff from.
                dev.skillmanager.store.HomeDigest.compute(h.store()).write(h.store());

                // One unit moves upstream. The other ALSO moves upstream, on the
                // very file the agent edited — so a pull that did not hold back
                // would rewrite the agent's bytes (cleanly or with conflict
                // markers, both of which change the file).
                moving.commitOnTrunk("references/upstream.md", "the trunk changed this\n");
                edited.replaceOnTrunk("SKILL.md", "---\nname: drift-edited\n"
                        + "description: d\n---\nUPSTREAM REWROTE THIS LINE\n");
                Path agentFile = h.store().skillDir("drift-edited").resolve("SKILL.md");
                String agentEdit = "---\nname: drift-edited\ndescription: d\n---\n"
                        + "AGENT WROTE THIS AND IT MUST SURVIVE\n";
                Files.writeString(agentFile, agentEdit);

                ProjectSyncUseCase.Result result = new ProjectSyncUseCase(h.store(), null)
                        .sync(project, new ProjectDependencyResolver.Options(true, false));

                // Bytes first.
                assertEquals(agentEdit, Files.readString(agentFile),
                        "the agent's edit is byte-for-byte what it was");
                // Then the announcement.
                var drift = result.driftReport();
                assertTrue(drift.unitNames().contains("drift-moving"),
                        "the unit the trunk moved is named in the drift report");
                assertTrue(drift.units().stream()
                                .filter(u -> u.name().equals("drift-moving"))
                                .anyMatch(u -> u.allFiles().contains("references/upstream.md")),
                        "down to the file that changed");
                assertTrue(dev.skillmanager.store.DriftGate.pending(h.store()).isPresent(),
                        "and the next launch is gated until somebody reads it");
            }
        });

        // ---------------------------------------------------------- CHECKOUT

        suite.test("a checkout unit is materialized as its own git clone", () -> {
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("checkout-skill");
                installFromUpstream(h.store(), upstream);
                Path repoRoot = Files.createTempDirectory("project-checkout-");
                SkillProject project = projectFor(repoRoot, "checkout-project", "checkout-skill");

                new ProjectDependencyResolver(h.store(), null).resolve(project,
                        new ProjectDependencyResolver.Options(true, false, Set.of("checkout-skill")));

                Path childUnit = repoRoot.resolve(".skill-manager/skills/checkout-skill");
                assertTrue(GitOps.isGitRepo(childUnit), "the child unit is a git checkout");
                assertEquals(upstream.bare().toString(), GitOps.originUrl(childUnit),
                        "with origin pointed at the unit's own remote, not at the parent store");
                var record = materializer(h.store(), repoRoot)
                        .readRecord("checkout-skill", UnitKind.SKILL).orElseThrow();
                assertEquals("CHECKOUT", record.mode(), "the mode is persisted per unit");
                assertNotNull(record.sourceRevision(), "with the revision it was checked out at");
            }
        });

        suite.test("the next resolve does not clobber a checkout, commits and all", () -> {
            // The failure an integration review predicted: a third enum constant
            // that is not per-unit and not persisted gets deleted by the next
            // ordinary resolve, taking unpushed commits with it.
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("sticky-skill");
                installFromUpstream(h.store(), upstream);
                Path repoRoot = Files.createTempDirectory("project-checkout-sticky-");
                SkillProject project = projectFor(repoRoot, "sticky-project", "sticky-skill");
                new ProjectDependencyResolver(h.store(), null).resolve(project,
                        new ProjectDependencyResolver.Options(true, false, Set.of("sticky-skill")));

                Path childUnit = repoRoot.resolve(".skill-manager/skills/sticky-skill");
                Path agentFile = childUnit.resolve("references/agent.md");
                Fs.ensureDir(agentFile.getParent());
                Files.writeString(agentFile, "COMMITTED IN THE CHECKOUT, PUSHED NOWHERE\n");
                String commit = GitOps.commitAll(childUnit, "agent work");
                assertNotNull(commit, "the agent committed inside the checkout");

                // A perfectly ordinary resolve, with the project-wide default.
                ProjectDependencyResolver.Result second =
                        new ProjectDependencyResolver(h.store(), null).resolve(project,
                                new ProjectDependencyResolver.Options(true, false));

                assertTrue(GitOps.isGitRepo(childUnit), "still a checkout");
                assertEquals("COMMITTED IN THE CHECKOUT, PUSHED NOWHERE\n",
                        Files.readString(agentFile),
                        "and the committed file is byte-for-byte intact");
                assertEquals(commit, GitOps.headHash(childUnit),
                        "HEAD is still the agent's commit — nothing was re-cloned over it");
                assertEquals("CHECKOUT", materializer(h.store(), repoRoot)
                                .readRecord("sticky-skill", UnitKind.SKILL).orElseThrow().mode(),
                        "and it is still recorded as a checkout, so the mode survives too");
                // Not held back: recognizing the checkout is what keeps this out
                // of the hold-back report. Without the recorded mode the copy path
                // sees a tree it cannot account for (a .git the parent lacks) and
                // reports every resolve as "local changes" forever — which is not
                // destructive, but it does turn the one report that must mean
                // something into noise.
                assertTrue(second.childHome().heldBack().isEmpty(),
                        "a recognized checkout is not reported as an unaccounted-for tree");
            }
        });

        suite.test("a checkout with commits of its own counts as locally modified", () -> {
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("modified-checkout");
                installFromUpstream(h.store(), upstream);
                Path repoRoot = Files.createTempDirectory("project-checkout-modified-");
                SkillProject project = projectFor(repoRoot, "modified-project", "modified-checkout");
                new ProjectDependencyResolver(h.store(), null).resolve(project,
                        new ProjectDependencyResolver.Options(true, false,
                                Set.of("modified-checkout")));
                ChildHomeMaterializer materializer = materializer(h.store(), repoRoot);
                assertFalse(materializer.isLocallyModified("modified-checkout", UnitKind.SKILL),
                        "a fresh checkout at the recorded revision is not modified");

                Path childUnit = repoRoot.resolve(".skill-manager/skills/modified-checkout");
                Files.writeString(childUnit.resolve("SKILL.md"),
                        "---\nname: modified-checkout\ndescription: d\n---\nagent edit\n");

                assertTrue(materializer.isLocallyModified("modified-checkout", UnitKind.SKILL),
                        "an uncommitted change makes it modified");
                GitOps.commitAll(childUnit, "agent work");
                assertTrue(materializer.isLocallyModified("modified-checkout", UnitKind.SKILL),
                        "and so does a commit the recorded revision does not know about");
            }
        });

        // ------------------------------------------------------ push-back

        suite.test("publish pushes a branch and leaves the trunk exactly where it was", () -> {
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("publish-skill");
                installFromUpstream(h.store(), upstream);
                String trunkBefore = upstream.trunkHash();
                Path body = h.store().skillDir("publish-skill").resolve("SKILL.md");
                Files.writeString(body, "---\nname: publish-skill\ndescription: d\n---\nimproved\n");
                RecordingOpener opener = new RecordingOpener("https://example.invalid/pr/1");

                UnitPublisher.Result result = new UnitPublisher(h.store(), opener)
                        .publish("publish-skill", UnitPublisher.Options.forTicket("#8"));

                assertEquals(UnitPublisher.Status.PUBLISHED, result.status(), "published");
                assertEquals("skill/8-publish-skill", result.branch(), "on a ticket-named branch");
                assertEquals(trunkBefore, upstream.trunkHash(),
                        "the trunk in the remote is byte-identical — a PR proposes, it does not land");
                assertEquals(result.commit(), upstream.branchHash("skill/8-publish-skill"),
                        "and the branch in the remote holds the new commit");
                assertEquals("https://example.invalid/pr/1", result.pullRequestUrl(), "PR url reported");
                PullRequestOpener.Request request = opener.only();
                assertEquals("skill/8-publish-skill", request.headBranch(), "PR head");
                assertEquals("main", request.baseBranch(), "PR base is the trunk");
            }
        });

        suite.test("publish --direct is the only way the trunk moves", () -> {
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("direct-skill");
                installFromUpstream(h.store(), upstream);
                String trunkBefore = upstream.trunkHash();
                Files.writeString(h.store().skillDir("direct-skill").resolve("SKILL.md"),
                        "---\nname: direct-skill\ndescription: d\n---\nimproved\n");
                RecordingOpener opener = new RecordingOpener(null);

                UnitPublisher.Result result = new UnitPublisher(h.store(), opener).publish(
                        "direct-skill",
                        new UnitPublisher.Options("#8", null, null, null, true, false, null));

                assertEquals(UnitPublisher.Status.PUSHED_DIRECT, result.status(), "pushed directly");
                assertFalse(trunkBefore.equals(upstream.trunkHash()), "and the trunk moved");
                assertEquals(result.commit(), upstream.trunkHash(), "to the published commit");
                assertTrue(opener.requests().isEmpty(), "no pull request was opened");
            }
        });

        suite.test("re-publishing the same ticket adds a commit, it does not reset the branch", () -> {
            // `git checkout -B` would silently discard the first commit here.
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("twice-skill");
                installFromUpstream(h.store(), upstream);
                Path unit = h.store().skillDir("twice-skill");
                RecordingOpener opener = new RecordingOpener("https://example.invalid/pr/2");
                UnitPublisher publisher = new UnitPublisher(h.store(), opener);

                Fs.ensureDir(unit.resolve("references"));
                Files.writeString(unit.resolve("references/first.md"), "first edit\n");
                UnitPublisher.Result first = publisher.publish("twice-skill",
                        UnitPublisher.Options.forTicket("#8"));
                assertEquals(UnitPublisher.Status.PUBLISHED, first.status(), "first publish");

                // Back on the trunk in between, which is where a home lands after
                // a reinstall or a trunk pull. This is what makes the difference
                // between `git switch` and `git checkout -B` observable: -B would
                // reset skill/8-twice-skill to main and drop the first commit.
                git(unit, "switch", "--quiet", "main");
                // switching back to the trunk removed references/ with the first
                // commit's file, so the directory has to be recreated.
                Fs.ensureDir(unit.resolve("references"));
                Files.writeString(unit.resolve("references/second.md"), "second edit\n");
                UnitPublisher.Result second = publisher.publish("twice-skill",
                        UnitPublisher.Options.forTicket("#8"));

                assertEquals(UnitPublisher.Status.PUBLISHED, second.status(), "second publish");
                List<String> files = upstream.filesOnBranch("skill/8-twice-skill");
                assertTrue(files.contains("references/first.md"),
                        "the first commit's file is still on the branch in the remote");
                assertTrue(files.contains("references/second.md"),
                        "alongside the second's");
            }
        });

        suite.test("publish with nothing to publish says so and pushes nothing", () -> {
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("noop-skill");
                installFromUpstream(h.store(), upstream);
                RecordingOpener opener = new RecordingOpener("https://example.invalid/pr/3");

                UnitPublisher.Result result = new UnitPublisher(h.store(), opener)
                        .publish("noop-skill", UnitPublisher.Options.forTicket("#8"));

                assertEquals(UnitPublisher.Status.NOTHING_TO_PUBLISH, result.status(), "no-op");
                assertEquals(null, upstream.branchHash("skill/8-noop-skill"),
                        "no branch was created in the remote");
                assertTrue(opener.requests().isEmpty(), "and no pull request was opened");
            }
        });

        suite.test("publish without a ticket is refused before anything is committed", () -> {
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("noticket-skill");
                installFromUpstream(h.store(), upstream);
                Path unit = h.store().skillDir("noticket-skill");
                Files.writeString(unit.resolve("SKILL.md"),
                        "---\nname: noticket-skill\ndescription: d\n---\nimproved\n");

                try {
                    new UnitPublisher(h.store(), new RecordingOpener(null))
                            .publish("noticket-skill",
                                    new UnitPublisher.Options(null, null, null, null, false, false, null));
                    throw new AssertionError("expected a missing ticket to be refused");
                } catch (IOException expected) {
                    assertContains(expected.getMessage(), "a ticket is required",
                            "the refusal says what is missing");
                }
                assertTrue(GitOps.hasWorktreeChanges(unit),
                        "the edit is still uncommitted — nothing was done on the way to refusing");
                assertFalse(GitOps.branchExists(unit, "skill/unknown-noticket-skill"),
                        "and no branch was created");
            }
        });

        suite.test("publish on a frozen home refuses and commits nothing", () -> {
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("frozen-skill");
                installFromUpstream(h.store(), upstream);
                Path unit = h.store().skillDir("frozen-skill");
                Files.writeString(unit.resolve("SKILL.md"),
                        "---\nname: frozen-skill\ndescription: d\n---\nimproved\n");
                String headBefore = GitOps.headHash(unit);
                HomePolicy.write(h.store(), HomePolicy.FROZEN);

                try {
                    new UnitPublisher(h.store(), new RecordingOpener(null))
                            .publish("frozen-skill", UnitPublisher.Options.forTicket("#8"));
                    throw new AssertionError("expected a frozen home to refuse publishing");
                } catch (FrozenHomeException expected) {
                    assertEquals("unit publish", expected.operation(), "operation named");
                }
                assertEquals(headBefore, GitOps.headHash(unit), "HEAD did not move");
                assertTrue(GitOps.hasWorktreeChanges(unit), "and the edit was not committed");
                assertEquals(null, upstream.branchHash("skill/8-frozen-skill"),
                        "nothing reached the remote");
            }
        });

        suite.test("publish from a child-home checkout publishes the child's commits", () -> {
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("checkout-publish");
                installFromUpstream(h.store(), upstream);
                Path repoRoot = Files.createTempDirectory("publish-checkout-");
                SkillProject project = projectFor(repoRoot, "publish-checkout-project",
                        "checkout-publish");
                new ProjectDependencyResolver(h.store(), null).resolve(project,
                        new ProjectDependencyResolver.Options(true, false,
                                Set.of("checkout-publish")));
                Path childUnit = repoRoot.resolve(".skill-manager/skills/checkout-publish");
                Fs.ensureDir(childUnit.resolve("references"));
                Files.writeString(childUnit.resolve("references/from-child.md"), "child work\n");
                String trunkBefore = upstream.trunkHash();
                RecordingOpener opener = new RecordingOpener("https://example.invalid/pr/4");

                UnitPublisher.Result result = new UnitPublisher(h.store(), opener).publish(
                        "checkout-publish",
                        new UnitPublisher.Options("#8", null, null, null, false, false,
                                repoRoot.resolve(".skill-manager")));

                assertEquals(UnitPublisher.Status.PUBLISHED, result.status(), "published");
                assertEquals(childUnit, result.repo(), "from the child home's checkout");
                assertTrue(upstream.filesOnBranch("skill/8-checkout-publish")
                                .contains("references/from-child.md"),
                        "the child home's file reached the remote branch");
                assertEquals(trunkBefore, upstream.trunkHash(), "the trunk did not move");
            }
        });

        suite.test("a dry run reports the plan and touches nothing", () -> {
            try (TestHarness h = TestHarness.create()) {
                Upstream upstream = Upstream.create("dryrun-skill");
                installFromUpstream(h.store(), upstream);
                Path unit = h.store().skillDir("dryrun-skill");
                Files.writeString(unit.resolve("SKILL.md"),
                        "---\nname: dryrun-skill\ndescription: d\n---\nimproved\n");
                String headBefore = GitOps.headHash(unit);

                UnitPublisher.Result result = new UnitPublisher(h.store(), new RecordingOpener(null))
                        .publish("dryrun-skill",
                                new UnitPublisher.Options("#8", null, null, null, false, true, null));

                assertEquals(UnitPublisher.Status.PLANNED, result.status(), "planned only");
                assertEquals(headBefore, GitOps.headHash(unit), "HEAD did not move");
                assertTrue(GitOps.hasWorktreeChanges(unit), "the edit is still uncommitted");
                assertEquals(null, upstream.branchHash("skill/8-dryrun-skill"), "nothing pushed");
            }
        });

        suite.test("branch names are slugged so a ticket like `#8` still produces a legal ref", () -> {
            assertEquals("skill/8-my-skill", UnitPublisher.branchFor("#8", "my-skill"), "hash ticket");
            assertEquals("skill/t7-push-back-my-skill",
                    UnitPublisher.branchFor("T7 push back", "my-skill"), "spaces and case");
            assertEquals("skill/unknown-my-skill", UnitPublisher.branchFor(null, "my-skill"),
                    "a missing ticket still yields a ref rather than a git error");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- fixtures

    /**
     * A bare repository standing in for a unit's real remote, plus a scratch
     * clone used to put commits on its trunk.
     */
    private record Upstream(Path bare, Path work, String name) {

        /** {@code name} is the unit name: the fixture's SKILL.md declares it. */
        static Upstream create(String name) throws Exception {
            Path root = Files.createTempDirectory("upstream-" + name + "-");
            Path bare = root.resolve(name + ".git");
            Files.createDirectories(bare);
            git(bare, "init", "--bare", "-b", "main", "--quiet");

            Path work = root.resolve("work");
            Files.createDirectories(work);
            git(work, "init", "-b", "main", "--quiet");
            Files.writeString(work.resolve("SKILL.md"),
                    "---\nname: " + name + "\ndescription: upstream fixture\n---\nbody\n");
            git(work, "add", "-A");
            commit(work, "initial");
            git(work, "remote", "add", "origin", bare.toString());
            git(work, "push", "--quiet", "origin", "main");
            return new Upstream(bare, work, name);
        }

        /** Add a file on the trunk and push it, so a pull has something to find. */
        void commitOnTrunk(String relPath, String content) throws Exception {
            Path file = work.resolve(relPath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            git(work, "add", "-A");
            commit(work, "upstream: " + relPath);
            git(work, "push", "--quiet", "origin", "main");
        }

        /** Replace a file on the trunk, for arranging a conflict. */
        void replaceOnTrunk(String relPath, String content) throws Exception {
            commitOnTrunk(relPath, content);
        }

        String trunkHash() { return branchHash("main"); }

        String branchHash(String branch) {
            Result r = run(bare, List.of("git", "rev-parse", "--verify", "--quiet",
                    "refs/heads/" + branch));
            return r.exit == 0 && !r.out.isBlank() ? r.out.trim() : null;
        }

        List<String> filesOnBranch(String branch) {
            Result r = run(bare, List.of("git", "ls-tree", "-r", "--name-only", branch));
            if (r.exit != 0 || r.out.isBlank()) return List.of();
            return List.of(r.out.trim().split("\\r?\\n"));
        }
    }

    /**
     * Installs a unit into {@code store} as a clone of {@code upstream} — the
     * shape a {@code github:} / {@code git+} install leaves behind.
     *
     * <p>Deliberately does not push: the store's checkout must start exactly at
     * the trunk so that the upstream's next commit is genuinely ahead of it, which
     * is the situation every pull test needs.
     */
    private static void installFromUpstream(SkillStore store, Upstream upstream) throws Exception {
        String unitName = upstream.name();
        Path dest = store.skillDir(unitName);
        Fs.ensureDir(dest.getParent());
        git(dest.getParent(), "clone", "--quiet", upstream.bare().toString(), dest.toString());
        new UnitStore(store).write(new InstalledUnit(
                unitName, "0.1.0", InstalledUnit.Kind.GIT,
                InstalledUnit.InstallSource.GIT, upstream.bare().toString(),
                GitOps.headHash(dest), "main", UnitStore.nowIso(), List.of(), UnitKind.SKILL));
    }

    private static SkillProject projectFor(Path repoRoot, String projectName, String unitName)
            throws Exception {
        Files.createDirectories(repoRoot);
        Files.writeString(repoRoot.resolve("skill-project.toml"), """
                [project]
                name = "%s"

                [skills.demo]
                source = "skill:%s"
                """.formatted(projectName, unitName));
        return SkillProjectParser.load(repoRoot);
    }

    private static UnitTrunkPull.Report pull(SkillStore store, String unitName,
                                             UnitTrunkPull.Options options) throws Exception {
        return new UnitTrunkPull(store, null).pull(
                List.of(new SkillProjectLock.ResolvedUnit(unitName, UnitKind.SKILL, "0.1.0",
                        null, true)),
                null, options);
    }

    private static UnitTrunkPull.UnitPull only(UnitTrunkPull.Report report) {
        assertEquals(1, report.pulls().size(), "one unit pulled");
        return report.pulls().get(0);
    }

    private static ChildHomeMaterializer materializer(SkillStore parent, Path repoRoot) {
        return new ChildHomeMaterializer(parent,
                new SkillStore(repoRoot.resolve(".skill-manager")));
    }

    /** Captures the pull requests that would have been opened. */
    private static final class RecordingOpener implements PullRequestOpener {
        private final String url;
        private final List<Request> requests = new ArrayList<>();

        RecordingOpener(String url) { this.url = url; }

        @Override
        public Optional<String> open(Request request) {
            requests.add(request);
            return Optional.ofNullable(url);
        }

        List<Request> requests() { return requests; }

        Request only() {
            assertEquals(1, requests.size(), "one pull request opened");
            return requests.get(0);
        }
    }

    // -------------------------------------------------------------- git glue

    private static void git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        Result r = run(dir, argv);
        if (r.exit != 0) {
            throw new IOException("fixture git " + String.join(" ", args) + " failed in " + dir
                    + " (rc=" + r.exit + "): " + r.out);
        }
    }

    /** Commit with an explicit identity: a CI runner has no global git user. */
    private static void commit(Path dir, String message) throws Exception {
        git(dir, "-c", "user.email=fixture@localhost", "-c", "user.name=fixture",
                "commit", "--quiet", "-m", message);
    }

    private record Result(int exit, String out) {}

    private static Result run(Path workdir, List<String> argv) {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        if (workdir != null) pb.directory(workdir.toFile());
        try {
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            return new Result(p.waitFor(), out.toString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Result(-1, e.getMessage() == null ? "" : e.getMessage());
        }
    }
}
