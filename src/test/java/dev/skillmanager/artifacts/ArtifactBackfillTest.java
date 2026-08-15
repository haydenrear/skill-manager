package dev.skillmanager.artifacts;

import dev.skillmanager._lib.test.Tests;
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

        return suite.runAll();
    }
}
