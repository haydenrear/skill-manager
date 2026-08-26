package dev.skillmanager.artifacts;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.pm.PackageManager;
import dev.skillmanager.store.SkillStore;

import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * ARTI-03: a home that predates the ledger lists correctly, with no rebuild
 * and no migration, and the listing keeps the two distinctions the epic's
 * measured evidence turns on.
 */
public final class ArtifactBackfillTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ArtifactBackfillTest");

        suite.test("a home with no ledger still enumerates every kind", () -> {
            SkillStore store = ArtifactsFixture.seed();
            ArtifactIndex index = ArtifactIndex.of(store);

            assertFalse(index.ledgerPresent(), "no ledger file was written");
            for (ArtifactKind kind : ArtifactKind.values()) {
                assertTrue(index.artifacts().stream().anyMatch(a -> a.kind() == kind),
                        "backfilled at least one " + kind.id());
            }
            assertEquals(Artifact.Origin.HOME,
                    index.byId(ArtifactIds.unitStore("alpha")).orElseThrow().origin(),
                    "everything came from the home, nothing from a ledger");
        });

        suite.test("a shim that exists and cannot run is DECLARED, not materialized", () -> {
            SkillStore store = ArtifactsFixture.seed();
            ArtifactIndex index = ArtifactIndex.of(store);

            Artifact working = index.byId(ArtifactIds.cliShim("skill-script", "alpha-script"))
                    .orElseThrow();
            assertEquals(Artifact.Materialization.MATERIALIZED, working.materialization(),
                    "the shim that runs is materialized");

            // bin/cli/dangler exists. Files.exists says yes if you do not
            // follow the link and isExecutable says no if you do; only the
            // DANGLING state says "the shim is there and it will not run",
            // which is what a cloned ticket home ships for jinja2/skill-dev.
            Artifact dangling = index.byId(ArtifactIds.cliShim("pip", "alpha-pkg")).orElseThrow();
            assertEquals("bin/cli/dangler", dangling.outputs().get(0).path(),
                    "the shim is named for the BINARY, which cli-lock.toml does not record");
            assertEquals(Artifact.Presence.DANGLING, dangling.outputs().get(0).presence(),
                    "a link into a tree the home does not hold");
            assertEquals(Artifact.Materialization.DECLARED_ONLY, dangling.materialization(),
                    "declared, never materialized");
            assertContains(dangling.actual().get("unusable_because"), "dangling symlink",
                    "and the listing says why");
        });

        suite.test("a lock row nothing declares reports UNKNOWN, not missing", () -> {
            SkillStore store = ArtifactsFixture.seed();
            dev.skillmanager.lock.CliLock lock = dev.skillmanager.lock.CliLock.load(store);
            // The uninstall-leaves-artifacts-behind shape (skill-manager#104):
            // the lock row outlives the unit that named the binary.
            lock.put(new dev.skillmanager.lock.CliLock.Entry("npm", "orphan-pkg", "1.0.0",
                    "npm:orphan-pkg", null, List.of("gone"), "2026-01-01T00:00:00Z", null));
            lock.save(store);

            Artifact orphan = ArtifactIndex.of(store)
                    .byId(ArtifactIds.cliShim("npm", "orphan-pkg")).orElseThrow();
            assertEquals(Artifact.Presence.UNKNOWN, orphan.outputs().get(0).presence(),
                    "nothing on disk names the binary, so nothing is claimed about it");
            assertEquals(Artifact.Materialization.UNKNOWN, orphan.materialization(),
                    "'I could not look' is not 'it is missing'");
        });

        suite.test("recorded and actual are separate, and can disagree", () -> {
            SkillStore store = ArtifactsFixture.seed();
            String unit = ArtifactsFixture.withGitUnit(store);
            if (unit == null) {
                System.out.println("      (skipped: git unavailable — no repository to disagree with)");
                return;
            }
            Artifact artifact = ArtifactIndex.of(store)
                    .byId(ArtifactIds.unitStore(unit)).orElseThrow();

            assertEquals("419d73886012b3472759763d928590417824ca1b",
                    artifact.recorded().get("git_hash"), "the record's claim is kept verbatim");
            assertNotNull(artifact.actual().get("git_hash"), "and the store's own HEAD beside it");
            assertEquals(Artifact.Agreement.DISAGREES, artifact.agreement(),
                    "a recorded hash is not automatically true");

            // The control: a unit with no recorded hash is UNRECORDED, never
            // AGREES. Crediting an empty field is the over-generous oracle that
            // put this epic's baseline at 2/9 instead of 1/9.
            Artifact alpha = ArtifactIndex.of(store)
                    .byId(ArtifactIds.unitStore("alpha")).orElseThrow();
            assertEquals(Artifact.Agreement.UNRECORDED, alpha.agreement(),
                    "nothing recorded is not agreement");
        });

        suite.test("the ledger is an index of identity and copies no state", () -> {
            SkillStore store = ArtifactsFixture.seed();
            ArtifactIndex index = ArtifactIndex.of(store);
            ArtifactLedger.of(index.artifacts()).save(store);

            String toml = java.nio.file.Files.readString(ArtifactLedger.file(store));
            // The fingerprint, the version and the bound hash all exist in this
            // home and none of them belong here: a second copy is a second
            // thing that can disagree with the disk.
            assertTrue(!toml.contains("fingerprint-abc"),
                    "cli-lock.toml's install_fingerprint is not duplicated");
            assertTrue(!toml.contains("digest-alpha"),
                    "home.digest.json's per-unit digest is referenced, not copied");
            assertContains(toml, "source = \"home.digest.json\"",
                    "the digest artifact names the record that owns the fact instead");
        });

        suite.test("the JSON report is a stable contract with a computed summary", () -> {
            SkillStore store = ArtifactsFixture.seed();
            ArtifactIndex index = ArtifactIndex.of(store);
            ArtifactReport report = ArtifactReport.of(index, index.artifacts());

            assertEquals(ArtifactReport.SCHEMA, report.schema(), "schema is declared");
            assertEquals(index.artifacts().size(), report.summary().artifacts(), "summary counts");
            Map<String, Integer> byKind = report.summary().byKind();
            for (ArtifactKind kind : ArtifactKind.values()) {
                assertTrue(byKind.containsKey(kind.id()),
                        "every kind has a row even at zero: " + kind.id());
            }
            ArtifactReport.ArtifactView view = report.artifacts().get(0);
            assertEquals(view.kind(), view.id().substring(0, view.id().indexOf(':')),
                    "the id's scheme is the kind token, so a consumer needs no mapping table");
        });

        // -------------------------------------------- ARTI-20 (#122), item 2
        //
        // The measurement: `4 unclaimed` of 10 provisioned trees in the project
        // home, two of them pm/uv and pm/node. No cli-lock row claims them and
        // none ever will, so the class could not reach "every instance decided"
        // whatever else landed.

        suite.test("a bundled package manager declares the version this codebase pins", () -> {
            SkillStore store = ArtifactsFixture.seed();
            plantBundledPm(store, PackageManager.UV, PackageManager.UV.defaultVersion, true);

            Artifact uv = ArtifactIndex.of(store)
                    .byId(ArtifactIds.provisionedTree("pm", "uv")).orElseThrow();

            assertEquals(List.of("spec:pm:uv@" + PackageManager.UV.defaultVersion), uv.inputs(),
                    "the pinned version IS the declaration, and it is now recorded as one");
            assertEquals("pm/uv/current", uv.source(),
                    "and the record it was read from is named");
            assertEquals(Artifact.Agreement.AGREES, uv.agreement(),
                    "this home has the pinned version");
            assertEquals("resolved", uv.recorded().get("install_fingerprint_kind"),
                    "the digest covers a version read off THIS home's disk");
            assertEquals(PackageManager.UV.defaultVersion, uv.actual().get("installed_version"),
                    "and the observation is recorded beside the declaration, not folded into it");
            assertNotNull(uv.recorded().get("url"), "the pinned download is part of the record");
        });

        suite.test("a bundled package manager left behind by a version bump DISAGREES", () -> {
            SkillStore store = ArtifactsFixture.seed();
            // The state every existing home is in the moment PackageManager's
            // defaultVersion moves: ensureBundled returns early on ANY bundled
            // copy, so the bump never reaches a home that already has one and
            // nothing reported the gap. This is the whole argument for
            // recording these trees rather than excluding them.
            plantBundledPm(store, PackageManager.UV, "0.0.1-ancient", true);

            Artifact uv = ArtifactIndex.of(store)
                    .byId(ArtifactIds.provisionedTree("pm", "uv")).orElseThrow();
            assertEquals(Artifact.Agreement.DISAGREES, uv.agreement(),
                    "the pin and the disk are compared, and they do not match");
            assertEquals("0.0.1-ancient", uv.actual().get("installed_version"),
                    "the record says which uv this home is actually running");
            assertEquals(PackageManager.UV.defaultVersion, uv.recorded().get("pinned_version"),
                    "beside the one it should be running");
        });

        suite.test("the current pointer is read in both spellings setCurrent can write", () -> {
            SkillStore store = ArtifactsFixture.seed();
            // A filesystem without symlinks gets a pointer FILE. A reader that
            // knows only the symlink reports "no current version" on exactly
            // the homes the fallback exists for.
            plantBundledPm(store, PackageManager.NODE, PackageManager.NODE.defaultVersion, false);

            Artifact node = ArtifactIndex.of(store)
                    .byId(ArtifactIds.provisionedTree("pm", "node")).orElseThrow();
            assertEquals(Artifact.Agreement.AGREES, node.agreement(),
                    "the pointer file was read, not skipped");
            assertEquals(PackageManager.NODE.defaultVersion,
                    node.actual().get("installed_version"), "and it named the version");
        });

        suite.test("a bundled root with no current pointer says so instead of guessing", () -> {
            SkillStore store = ArtifactsFixture.seed();
            java.nio.file.Files.createDirectories(store.root().resolve("pm/uv/0.4.18/bin"));

            Artifact uv = ArtifactIndex.of(store)
                    .byId(ArtifactIds.provisionedTree("pm", "uv")).orElseThrow();
            assertEquals(Artifact.Agreement.UNVERIFIABLE, uv.agreement(),
                    "a version directory is not a claim about which one is current");
            assertNotNull(uv.recorded().get("install_fingerprint_gap"),
                    "and the reason is recorded rather than left as a bare absence");
        });

        suite.test("an external package manager is not described as something we derived", () -> {
            SkillStore store = ArtifactsFixture.seed();
            // docker and brew are system-managed: PackageManager.bundleable()
            // is false and downloadUrl throws. A pm/docker directory is not an
            // artifact this codebase produced and must not claim to be.
            java.nio.file.Files.createDirectories(store.root().resolve("pm/docker"));

            Artifact docker = ArtifactIndex.of(store)
                    .byId(ArtifactIds.provisionedTree("pm", "docker")).orElseThrow();
            assertEquals(List.of(), docker.inputs(), "nothing here declares a docker install");
            assertEquals(Artifact.Agreement.UNRECORDED, docker.agreement(),
                    "and the honest verdict is that nothing is recorded about it");
        });

        return suite.runAll();
    }

    /**
     * {@code pm/<id>/<version>/bin/<binary>} plus the {@code current} pointer,
     * as {@code PackageManagerRuntime.install} and {@code setCurrent} leave it.
     *
     * @param symlink true for the symlink form, false for the pointer-file
     *        fallback taken on filesystems without symlinks
     */
    private static void plantBundledPm(SkillStore store, PackageManager pm, String version,
                                       boolean symlink) throws Exception {
        java.nio.file.Path tool = store.root().resolve("pm").resolve(pm.id);
        java.nio.file.Path bin = tool.resolve(version).resolve("bin");
        java.nio.file.Files.createDirectories(bin);
        java.nio.file.Files.writeString(bin.resolve(pm.binaryName()), "#!/bin/sh\n");
        java.nio.file.Path current = tool.resolve("current");
        if (symlink) {
            java.nio.file.Files.createSymbolicLink(current, java.nio.file.Path.of(version));
        } else {
            java.nio.file.Files.writeString(current, version);
        }
    }
}
