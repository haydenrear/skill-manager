///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Public-CLI A/B regression for a project-pinned git dependency.
 *
 * <p>The project selects immutable revision A while its source repository's
 * trunk later advances to B. A named {@code sync --from} publishes A again,
 * then automatically refreshes every project that claims the unit. That
 * automatic refresh is reconciliation work: it must materialize the selected
 * A bytes without independently fetching or merging trunk B.
 *
 * <p>The fixture is deliberately local and real. It creates two git commits,
 * a detached clean source checkout that has never seen B, a real project
 * registration, a real child home, and an unrelated installed skill with a
 * real skill-script CLI. The only behavior-changing action under test is the
 * public {@code skill-manager sync <unit> --from <detached-A> --yes} command;
 * every verdict after it comes from durable files or git provenance.
 */
public final class ProjectPinnedSyncRevisionCoherent {

    private static final String NODE_ID = "project.pinned.sync.revision.coherent";
    private static final String PROJECT = "tg-pinned-sync-project";
    private static final String TARGET = "tg-pinned-sync-skill";
    private static final String UNRELATED = "tg-pinned-unrelated-skill";
    private static final String UNRELATED_CLI = "tg-pinned-unrelated-cli";
    private static final String MARKER_A = "ISSUE-166-REVISION-A";
    private static final String MARKER_B = "ISSUE-166-REVISION-B";

    static final NodeSpec SPEC = NodeSpec.of(NODE_ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("project", "sync", "git", "revision", "spec-conformance")
            .timeout("300s")
            .output("home", "string")
            .output("projectRoot", "string")
            .output("selectedRevision", "string")
            .output("trunkRevision", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> run(ctx));
    }

    private static NodeResult run(NodeContext ctx) {
        Env env = Env.from(ctx);
        if (env == null) {
            return NodeResult.fail(NODE_ID, "missing env.prepared context");
        }
        try {
            env = env.isolated("project-pinned-sync-home");
        } catch (Exception e) {
            return NodeResult.fail(NODE_ID, "could not create isolated home: " + e.getMessage());
        }

        Path repoRoot = SmEnv.repoRoot();
        Path cli = SmEnv.cli();
        Path fixtureRoot;
        Path upstream;
        Path selectedA;
        Path projectRoot;
        Path unrelatedSource;
        String sourceCoord;
        String revisionA;

        try {
            fixtureRoot = Files.createTempDirectory("sm-project-pinned-sync-");
            upstream = fixtureRoot.resolve("upstream");
            selectedA = fixtureRoot.resolve("selected-a");
            projectRoot = fixtureRoot.resolve("project");
            unrelatedSource = fixtureRoot.resolve("unrelated-source");
            Files.createDirectories(projectRoot);

            scaffoldSkill(upstream, TARGET, MARKER_A);
            git(upstream, "init", "-b", "main", "--quiet");
            commitAll(upstream, "revision A");
            revisionA = gitOutput(upstream, "rev-parse", "HEAD");

            // Clone while A is the only object, then detach. When B appears
            // later, the source handed to `sync --from` cannot smuggle B into
            // the destination through its own object database.
            command(List.of("git", "clone", "--quiet", upstream.toUri().toString(),
                    selectedA.toString())).requireSuccess("clone selected A");
            git(selectedA, "checkout", "--quiet", "--detach", revisionA);

            scaffoldSkillWithCli(unrelatedSource, UNRELATED, UNRELATED_CLI);
            sourceCoord = "git+" + upstream.toUri() + "#main";
            Files.writeString(projectRoot.resolve("skill-project.toml"), """
                    [project]
                    name = "%s"

                    [skills.target]
                    source = "%s"
                    revision = "%s"

                    [skills.unrelated]
                    source = "%s"
                    """.formatted(PROJECT, sourceCoord, revisionA, unrelatedSource));
        } catch (Exception e) {
            return NodeResult.fail(NODE_ID, "fixture setup failed: " + e.getMessage())
                    .publish("home", env.home().toString());
        }

        ProcessRecord installUnrelated = runCli(ctx, "install-unrelated", env, repoRoot, cli,
                "install", unrelatedSource.toString(), "--yes", "--no-bind-default", "--json");
        ProcessRecord resolve = runCli(ctx, "resolve-pinned-a", env, repoRoot, cli,
                "project", "resolve", "--skip-gateway", "--json",
                "--project-dir", projectRoot.toString());

        Path installedTarget = env.home().resolve("skills").resolve(TARGET);
        Path childTarget = projectRoot.resolve(".skill-manager/skills").resolve(TARGET);
        Path targetRecord = env.home().resolve("installed").resolve(TARGET + ".json");
        Path unitsLock = env.home().resolve("units.lock.toml");
        Path cliLock = env.home().resolve("cli-lock.toml");
        Path projectRegistry = env.home().resolve("projects").resolve(PROJECT);
        Path registeredManifest = projectRegistry.resolve("skill-project.toml");
        Path registration = projectRegistry.resolve("registration.toml");
        Path projectLock = projectRegistry.resolve("project-lock.toml");
        Path unrelatedRecord = env.home().resolve("installed").resolve(UNRELATED + ".json");
        Path unrelatedParentTree = env.home().resolve("skills").resolve(UNRELATED);
        Path unrelatedChildTree = projectRoot.resolve(".skill-manager/skills").resolve(UNRELATED);
        Path unrelatedParentShim = env.home().resolve("bin/cli").resolve(UNRELATED_CLI);
        Path unrelatedChildShim = projectRoot.resolve(".skill-manager/bin/cli").resolve(UNRELATED_CLI);

        // Install/resolve deliberately writes the lock before source
        // provenance for newly-resolved units is visible to that same staged
        // program. `sync --refresh` is the public stabilization boundary: it
        // writes the complete live records once, giving the A/B action an
        // honest byte-for-byte lock baseline rather than asserting against a
        // known provisional lock.
        ProcessRecord stabilizeLock = runCli(ctx, "stabilize-units-lock", env, repoRoot, cli,
                "sync", "--refresh");
        String unrelatedUnitsLockBlock = arrayTableFor(
                readText(unitsLock), "[[units]]", UNRELATED);
        String unrelatedCliLockBlock = tableFor(
                readText(cliLock), "[\"skill-script\".\"" + UNRELATED_CLI + "\"]");
        boolean unrelatedInstalledRecordPresent = Files.isRegularFile(unrelatedRecord);
        boolean unrelatedParentTreeNonempty = treeHasMaterial(unrelatedParentTree);
        boolean unrelatedChildTreeNonempty = treeHasMaterial(unrelatedChildTree);
        boolean unrelatedUnitsLockRowPresent = unrelatedUnitsLockBlock != null;
        boolean unrelatedCliLockRowPresent = unrelatedCliLockBlock != null
                && tomlStringArrayContains(unrelatedCliLockBlock, "requested_by", UNRELATED);
        boolean unrelatedParentCliShimExecutable = Files.isExecutable(unrelatedParentShim);
        boolean unrelatedChildCliShimExecutable = Files.isExecutable(unrelatedChildShim);
        ProjectUnitSemantics projectUnitBefore = ProjectUnitSemantics.from(
                arrayTableFor(readText(projectLock), "[[resolved_units]]", TARGET));
        UnrelatedSnapshot unrelatedBefore = UnrelatedSnapshot.capture(
                env.home(), projectRoot, UNRELATED, UNRELATED_CLI, unitsLock, cliLock);

        String revisionB;
        boolean selectedWasCleanDetachedA;
        boolean selectedLackedBBefore;
        try {
            scaffoldSkill(upstream, TARGET, MARKER_B);
            commitAll(upstream, "revision B");
            revisionB = gitOutput(upstream, "rev-parse", "HEAD");
            selectedWasCleanDetachedA = Objects.equals(revisionA, gitOutput(selectedA, "rev-parse", "HEAD"))
                    && gitSymbolicRef(selectedA) == null
                    && gitClean(selectedA);
            selectedLackedBBefore = !gitObjectExists(selectedA, revisionB);
        } catch (Exception e) {
            return NodeResult.fail(NODE_ID, "could not advance the local trunk to B: " + e.getMessage())
                    .process(resolve)
                    .process(installUnrelated)
                    .process(stabilizeLock)
                    .publish("home", env.home().toString())
                    .publish("projectRoot", projectRoot.toString())
                    .publish("selectedRevision", revisionA);
        }

        ProcessRecord sync = runCli(ctx, "named-sync-from-detached-a", env, repoRoot, cli,
                "sync", TARGET,
                "--from", selectedA.toString(),
                "--yes",
                "--skip-mcp",
                "--skip-agents");

        String installedHead = gitOutputOrNull(installedTarget, "rev-parse", "HEAD");
        String installedRecord = readText(targetRecord);
        String globalLockText = readText(unitsLock);
        String registeredManifestText = readText(registeredManifest);
        String registrationText = readText(registration);
        String projectLockText = readText(projectLock);
        String childText = readText(childTarget.resolve("SKILL.md"));
        String syncLog = processLog(ctx, sync);

        String targetLockBlock = arrayTableFor(globalLockText, "[[units]]", TARGET);
        String resolvedProjectBlock = arrayTableFor(projectLockText, "[[resolved_units]]", TARGET);
        ProjectUnitSemantics projectUnitAfter = ProjectUnitSemantics.from(resolvedProjectBlock);
        UnrelatedSnapshot unrelatedAfter = UnrelatedSnapshot.capture(
                env.home(), projectRoot, UNRELATED, UNRELATED_CLI, unitsLock, cliLock);

        boolean fixtureReady = resolve.exitCode() == 0
                && installUnrelated.exitCode() == 0
                && stabilizeLock.exitCode() == 0
                && !revisionA.equals(revisionB)
                && selectedWasCleanDetachedA
                && selectedLackedBBefore
                && unrelatedInstalledRecordPresent
                && unrelatedParentTreeNonempty
                && unrelatedChildTreeNonempty
                && unrelatedUnitsLockRowPresent
                && unrelatedCliLockRowPresent
                && unrelatedParentCliShimExecutable
                && unrelatedChildCliShimExecutable;
        boolean namedSyncExitZero = sync.exitCode() == 0;
        boolean checkoutHeadA = revisionA.equals(installedHead);
        boolean installedRecordA = jsonStringFieldEquals(installedRecord, "gitHash", revisionA);
        boolean unitsLockA = targetLockBlock != null
                && tomlStringFieldEquals(targetLockBlock, "resolved_sha", revisionA);
        boolean registrationA = Files.isRegularFile(registration)
                && registrationText.contains("name = \"" + PROJECT + "\"")
                && tomlStringFieldEquals(registeredManifestText, "revision", revisionA)
                && tomlStringFieldEquals(registeredManifestText, "source", sourceCoord);
        boolean childA = childText.contains(MARKER_A) && !childText.contains(MARKER_B);
        boolean projectLockSourceAndDirect = projectUnitBefore != null
                && projectUnitBefore.direct()
                && sameOrigin(upstream.toUri().toString(), projectUnitBefore.source())
                && projectUnitBefore.equals(projectUnitAfter);
        boolean claimantDidNotFetchOrMergeB = checkoutHeadA
                && selectedLackedBBefore
                && !gitObjectExists(installedTarget, revisionB)
                && !childText.contains(MARKER_B);
        boolean noPartialOrErrorReceipt = namedSyncExitZero
                && Pattern.compile("\\\"errors\\\"\\s*:\\s*\\[\\s*]", Pattern.DOTALL)
                        .matcher(installedRecord).find()
                && !installedRecord.contains("PROJECT_SYNC_FAILED")
                && !syncLog.contains("PROJECT_SYNC_FAILED")
                && !syncLog.contains("× SyncClaimingProjects")
                && !syncLog.contains("✗ SyncClaimingProjects");
        UnrelatedComparison unrelatedComparison = unrelatedBefore.compare(unrelatedAfter);
        boolean unrelatedByteIdentical = unrelatedComparison.identical();

        List<Path> gitTrees = new ArrayList<>(List.of(upstream, selectedA, installedTarget));
        if (isGitTree(childTarget)) gitTrees.add(childTarget);
        boolean everyGitTreeClean = gitTrees.size() >= 3 && gitTrees.stream().allMatch(
                ProjectPinnedSyncRevisionCoherent::gitClean);

        boolean pass = fixtureReady
                && namedSyncExitZero
                && checkoutHeadA
                && installedRecordA
                && unitsLockA
                && registrationA
                && childA
                && projectLockSourceAndDirect
                && claimantDidNotFetchOrMergeB
                && noPartialOrErrorReceipt
                && unrelatedByteIdentical
                && everyGitTreeClean;

        String detail = "A=" + revisionA
                + " B=" + revisionB
                + " installedHead=" + installedHead
                + " fixtureReady=" + fixtureReady
                + " syncExit=" + sync.exitCode()
                + " installedRecordA=" + installedRecordA
                + " unitsLockA=" + unitsLockA
                + " registrationA=" + registrationA
                + " childA=" + childA
                + " projectLockSourceAndDirect=" + projectLockSourceAndDirect
                + " bAbsentFromInstalled=" + !gitObjectExists(installedTarget, revisionB)
                + " noPartialOrErrorReceipt=" + noPartialOrErrorReceipt
                + " unrelatedByteIdentical=" + unrelatedByteIdentical
                + " unrelatedFixtureReady={record=" + unrelatedInstalledRecordPresent
                + ",parentTree=" + unrelatedParentTreeNonempty
                + ",childTree=" + unrelatedChildTreeNonempty
                + ",unitsLockRow=" + unrelatedUnitsLockRowPresent
                + ",cliLockRow=" + unrelatedCliLockRowPresent
                + ",parentShim=" + unrelatedParentCliShimExecutable
                + ",childShim=" + unrelatedChildCliShimExecutable + "}"
                + " unrelatedSurfaces={" + unrelatedComparison.detail() + "}"
                + " cleanGitTrees=" + everyGitTreeClean;

        return (pass ? NodeResult.pass(NODE_ID) : NodeResult.fail(NODE_ID, detail))
                .process(resolve)
                .process(installUnrelated)
                .process(stabilizeLock)
                .process(sync)
                .assertion("fixture_project_resolve_exit_zero", resolve.exitCode() == 0)
                .assertion("fixture_unrelated_install_exit_zero", installUnrelated.exitCode() == 0)
                .assertion("fixture_units_lock_stabilized", stabilizeLock.exitCode() == 0)
                .assertion("fixture_source_is_clean_detached_a_without_b", selectedWasCleanDetachedA
                        && selectedLackedBBefore)
                .assertion("fixture_unrelated_installed_record_present", unrelatedInstalledRecordPresent)
                .assertion("fixture_unrelated_parent_tree_nonempty", unrelatedParentTreeNonempty)
                .assertion("fixture_unrelated_child_tree_nonempty", unrelatedChildTreeNonempty)
                .assertion("fixture_unrelated_units_lock_row_present", unrelatedUnitsLockRowPresent)
                .assertion("fixture_unrelated_cli_lock_row_present", unrelatedCliLockRowPresent)
                .assertion("fixture_unrelated_parent_cli_shim_executable",
                        unrelatedParentCliShimExecutable)
                .assertion("fixture_unrelated_child_cli_shim_executable",
                        unrelatedChildCliShimExecutable)
                .assertion("named_sync_exit_zero", namedSyncExitZero)
                .assertion("checkout_head_remains_selected_a", checkoutHeadA)
                .assertion("installed_git_hash_is_selected_a", installedRecordA)
                .assertion("units_lock_resolved_sha_is_selected_a", unitsLockA)
                .assertion("registered_project_revision_is_selected_a", registrationA)
                .assertion("child_realization_is_selected_a", childA)
                .assertion("project_lock_source_and_directness_preserved", projectLockSourceAndDirect)
                .assertion("claimant_refresh_did_not_fetch_or_merge_b", claimantDidNotFetchOrMergeB)
                .assertion("no_partial_or_error_receipt", noPartialOrErrorReceipt)
                .assertion("unrelated_records_trees_locks_and_shims_byte_identical",
                        unrelatedByteIdentical)
                .assertion("every_git_tree_is_clean", everyGitTreeClean)
                .metric("gitTreesChecked", gitTrees.size())
                .publish("home", env.home().toString())
                .publish("projectRoot", projectRoot.toString())
                .publish("selectedRevision", revisionA)
                .publish("trunkRevision", revisionB)
                .log(detail);
    }

    private static void scaffoldSkill(Path dir, String name, String marker) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: pinned revision test-graph fixture
                ---
                %s
                """.formatted(name, marker));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "pinned revision test-graph fixture"
                """.formatted(name));
    }

    private static void scaffoldSkillWithCli(Path dir, String name, String cliName) throws IOException {
        Files.createDirectories(dir.resolve("skill-scripts"));
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: unrelated byte-identity fixture
                ---
                These bytes must not change during the named sync.
                """.formatted(name));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%1$s"
                version = "0.1.0"
                description = "unrelated byte-identity fixture"

                [[cli_dependencies]]
                name = "%2$s"
                spec = "skill-script:%2$s"

                [cli_dependencies.install.any]
                script = "install-%2$s.sh"
                binary = "%2$s"
                """.formatted(name, cliName));
        Files.writeString(dir.resolve("skill-scripts/install-" + cliName + ".sh"), """
                #!/usr/bin/env sh
                set -eu
                mkdir -p "$SKILL_MANAGER_BIN_DIR"
                cat > "$SKILL_MANAGER_BIN_DIR/%1$s" <<'EOF'
                #!/usr/bin/env sh
                echo %1$s
                EOF
                chmod +x "$SKILL_MANAGER_BIN_DIR/%1$s"
                """.formatted(cliName));
    }

    private static void commitAll(Path repo, String message) throws Exception {
        git(repo, "add", "-A");
        git(repo, "-c", "user.email=test@example.com", "-c", "user.name=Test Graph",
                "commit", "--quiet", "-m", message);
    }

    private static ProcessRecord runCli(NodeContext ctx, String label, Env env,
                                        Path repoRoot, Path cli, String... args) {
        List<String> command = new ArrayList<>();
        command.add(cli.toString());
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        env.apply(pb, repoRoot);
        return Procs.run(ctx, label, pb);
    }

    private static String processLog(NodeContext ctx, ProcessRecord process) {
        if (process.logPath() == null || process.logPath().isBlank()) return "";
        try {
            return Files.readString(ctx.reportDir().resolve(process.logPath()));
        } catch (IOException e) {
            return "";
        }
    }

    private static String readText(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean jsonStringFieldEquals(String json, String field, String value) {
        return Pattern.compile("\\\"" + Pattern.quote(field)
                        + "\\\"\\s*:\\s*\\\"" + Pattern.quote(value) + "\\\"")
                .matcher(json).find();
    }

    private static boolean tomlStringFieldEquals(String toml, String field, String value) {
        return Pattern.compile("(?m)^" + Pattern.quote(field)
                        + "\\s*=\\s*\\\"" + Pattern.quote(value) + "\\\"\\s*$")
                .matcher(toml).find();
    }

    private static String tomlStringField(String toml, String field) {
        if (toml == null) return null;
        var matcher = Pattern.compile("(?m)^" + Pattern.quote(field)
                        + "\\s*=\\s*\\\"([^\\\"]*)\\\"\\s*$")
                .matcher(toml);
        return matcher.find() ? matcher.group(1) : null;
    }

    private record ProjectUnitSemantics(String source, boolean direct) {
        static ProjectUnitSemantics from(String block) {
            if (block == null) return null;
            String source = tomlStringField(block, "source");
            boolean direct = Pattern.compile("(?m)^direct\\s*=\\s*true\\s*$")
                    .matcher(block).find();
            return source == null ? null : new ProjectUnitSemantics(source, direct);
        }
    }

    /** Return the named TOML array-table block without parsing private code. */
    private static String arrayTableFor(String toml, String header, String name) {
        int from = 0;
        while (true) {
            int start = toml.indexOf(header, from);
            if (start < 0) return null;
            int next = toml.indexOf("\n[[", start + header.length());
            String block = next < 0 ? toml.substring(start) : toml.substring(start, next);
            if (tomlStringFieldEquals(block, "name", name)) return block;
            from = start + header.length();
        }
    }

    private static String tableFor(String toml, String header) {
        int start = toml.indexOf(header);
        if (start < 0) return null;
        int next = toml.indexOf("\n[", start + header.length());
        return next < 0 ? toml.substring(start) : toml.substring(start, next);
    }

    private static boolean tomlStringArrayContains(String block, String field, String value) {
        if (block == null) return false;
        return Pattern.compile("(?m)^" + Pattern.quote(field)
                        + "\\s*=\\s*\\[[^]]*\\\"" + Pattern.quote(value) + "\\\"")
                .matcher(block).find();
    }

    /** Match the production UnitStore origin identity without importing private graph internals. */
    private static boolean sameOrigin(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) {
            return false;
        }
        return normalizeOrigin(expected).equals(normalizeOrigin(actual));
    }

    private static String normalizeOrigin(String origin) {
        String normalized = origin.trim();
        if (normalized.startsWith("git+")) {
            normalized = normalized.substring("git+".length());
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - ".git".length());
        }
        return normalized;
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        command(command).requireSuccess("git " + String.join(" ", args));
    }

    private static String gitOutput(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Cmd result = command(command);
        result.requireSuccess("git " + String.join(" ", args));
        return result.output().strip();
    }

    private static String gitOutputOrNull(Path repo, String... args) {
        try {
            return gitOutput(repo, args);
        } catch (Exception e) {
            return null;
        }
    }

    private static String gitSymbolicRef(Path repo) {
        return gitOutputOrNull(repo, "symbolic-ref", "-q", "HEAD");
    }

    private static boolean gitObjectExists(Path repo, String sha) {
        if (sha == null || sha.isBlank()) return false;
        try {
            List<String> command = List.of("git", "-C", repo.toString(),
                    "cat-file", "-e", sha + "^{commit}");
            return command(command).exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isGitTree(Path repo) {
        try {
            return command(List.of("git", "-C", repo.toString(),
                    "rev-parse", "--is-inside-work-tree")).exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean gitClean(Path repo) {
        try {
            Cmd result = command(List.of("git", "-C", repo.toString(),
                    "status", "--porcelain", "--untracked-files=all"));
            return result.exitCode() == 0 && result.output().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private static Cmd command(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        return new Cmd(exit, output);
    }

    private record Cmd(int exitCode, String output) {
        void requireSuccess(String operation) throws IOException {
            if (exitCode != 0) {
                throw new IOException(operation + " failed with exit " + exitCode + ": " + output.strip());
            }
        }
    }

    private record UnrelatedSnapshot(
            String installedRecord,
            Map<String, String> installedTree,
            Map<String, String> childTree,
            String unitsLock,
            String cliLock,
            String parentCliShim,
            String childCliShim
    ) {
        static UnrelatedSnapshot capture(Path home, Path projectRoot,
                                         String unitName, String cliName,
                                         Path unitsLock, Path cliLock) {
            return new UnrelatedSnapshot(
                    snapshot(home.resolve("installed").resolve(unitName + ".json")),
                    snapshotTree(home.resolve("skills").resolve(unitName)),
                    snapshotTree(projectRoot.resolve(".skill-manager/skills").resolve(unitName)),
                    snapshot(unitsLock),
                    snapshot(cliLock),
                    snapshot(home.resolve("bin/cli").resolve(cliName)),
                    snapshot(projectRoot.resolve(".skill-manager/bin/cli").resolve(cliName)));
        }

        UnrelatedComparison compare(UnrelatedSnapshot after) {
            boolean recordSame = Objects.equals(installedRecord, after.installedRecord);
            boolean treeSame = Objects.equals(installedTree, after.installedTree);
            boolean childTreeSame = Objects.equals(childTree, after.childTree);
            boolean unitsLockSame = Objects.equals(unitsLock, after.unitsLock);
            boolean cliLockSame = Objects.equals(cliLock, after.cliLock);
            boolean parentShimSame = Objects.equals(parentCliShim, after.parentCliShim);
            boolean childShimSame = Objects.equals(childCliShim, after.childCliShim);
            String detail = "record=" + surfaceDiff(installedRecord, after.installedRecord)
                    + ",tree=" + treeDiff(installedTree, after.installedTree)
                    + ",childTree=" + treeDiff(childTree, after.childTree)
                    + ",unitsLock=" + surfaceDiff(unitsLock, after.unitsLock)
                    + ",cliLock=" + surfaceDiff(cliLock, after.cliLock)
                    + ",parentShim=" + surfaceDiff(parentCliShim, after.parentCliShim)
                    + ",childShim=" + surfaceDiff(childCliShim, after.childCliShim);
            return new UnrelatedComparison(
                    recordSame && treeSame && childTreeSame && unitsLockSame && cliLockSame
                            && parentShimSame && childShimSame,
                    detail);
        }
    }

    private record UnrelatedComparison(boolean identical, String detail) {}

    /** Bounded diagnostics retained in the node's evidence envelope. */
    private static String surfaceDiff(String before, String after) {
        if (Objects.equals(before, after)) return "same:" + digest(before);
        return "changed:" + digest(before) + "->" + digest(after)
                + "[before=" + boundedReadable(before)
                + ";after=" + boundedReadable(after) + "]";
    }

    private static String treeDiff(Map<String, String> before, Map<String, String> after) {
        if (Objects.equals(before, after)) return "same:" + digest(before.toString());
        Set<String> paths = new LinkedHashSet<>();
        paths.addAll(before.keySet());
        paths.addAll(after.keySet());
        List<String> changed = new ArrayList<>();
        for (String path : paths) {
            String left = before.get(path);
            String right = after.get(path);
            if (!Objects.equals(left, right)) {
                changed.add(path + "=" + surfaceDiff(left, right));
                if (changed.size() == 8) break;
            }
        }
        return "changed:" + String.join("|", changed);
    }

    private static String boundedReadable(String snapshot) {
        if (snapshot == null) return "<null>";
        String readable = snapshot;
        int marker = snapshot.indexOf(":", "FILE:executable=".length());
        if (snapshot.startsWith("FILE:executable=") && marker >= 0) {
            try {
                byte[] bytes = Base64.getDecoder().decode(snapshot.substring(marker + 1));
                readable = new String(bytes, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // The encoded form is still bounded below and remains useful.
            }
        }
        readable = readable.replace("\n", "\\n").replace("\r", "\\r");
        int limit = 1400;
        return readable.length() <= limit ? readable : readable.substring(0, limit) + "...";
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return "digest-error";
        }
    }

    private static Map<String, String> snapshotTree(Path root) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            out.put("<root>", "MISSING");
            return out;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted().forEach(path -> {
                String relative = root.equals(path) ? "." : root.relativize(path).toString();
                out.put(relative, snapshot(path));
            });
        } catch (IOException e) {
            out.put("<error>", e.getClass().getSimpleName() + ":" + e.getMessage());
        }
        return out;
    }

    private static boolean treeHasMaterial(Path root) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var paths = Files.walk(root)) {
            return paths.anyMatch(path -> !root.equals(path));
        } catch (IOException e) {
            return false;
        }
    }

    private static String snapshot(Path path) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "MISSING";
            if (Files.isSymbolicLink(path)) return "LINK:" + Files.readSymbolicLink(path);
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return "DIR";
            byte[] bytes = Files.readAllBytes(path);
            return "FILE:executable=" + Files.isExecutable(path) + ":"
                    + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            return "ERROR:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

    private record Env(Path home, Path claudeHome, Path codexHome, Path geminiHome) {
        static Env from(NodeContext ctx) {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claude = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codex = ctx.get("env.prepared", "codexHome").orElse(null);
            String gemini = ctx.get("env.prepared", "geminiHome").orElse(null);
            if (home == null || claude == null || codex == null || gemini == null) return null;
            return new Env(Path.of(home), Path.of(claude), Path.of(codex), Path.of(gemini));
        }

        Env isolated(String name) throws IOException {
            // A rerun from saved Test Graph context must not inherit the first
            // attempt's installed state. Every invocation gets a fresh sibling
            // below the env.prepared root while remaining owned by that run.
            Path privateHome = Files.createTempDirectory(home, name + "-");
            Path agentHome = privateHome.resolve("agent-home");
            Path privateCodex = agentHome.resolve(".codex");
            Path privateGemini = agentHome.resolve(".gemini");
            Files.createDirectories(agentHome.resolve(".claude"));
            Files.createDirectories(privateCodex);
            Files.createDirectories(privateGemini);
            Files.writeString(privateHome.resolve("policy.toml"), """
                    require_confirmation = false
                    [install]
                    require_confirmation_for_hooks = false
                    require_confirmation_for_mcp = false
                    require_confirmation_for_cli_deps = false
                    require_confirmation_for_executable_commands = false
                    """);
            return new Env(privateHome, agentHome, privateCodex, privateGemini);
        }

        void apply(ProcessBuilder pb, Path repoRoot) {
            SmEnv.apply(pb, home.toString(), repoRoot.toString(),
                    SmEnv.sandbox(claudeHome, codexHome, geminiHome));
        }
    }

    private ProjectPinnedSyncRevisionCoherent() {}
}
