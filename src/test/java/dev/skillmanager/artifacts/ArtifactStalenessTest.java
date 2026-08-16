package dev.skillmanager.artifacts;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.cli.installer.InstallerRegistry;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * ARTI-05: "unit X moved — what is now stale?", and the rule that a probe which
 * did not finish is not a clean verdict.
 *
 * <p>The headline case is the ticket's declared expected effect, run as a test
 * rather than only as a demonstration: <b>editing a unit's
 * {@code skill-scripts/} tree makes {@code artifacts stale} name that unit's
 * shim AND the {@code cache/skill-script-*} tree it writes, with no install in
 * between.</b> Both halves matter — the shim is decided by its own recorded
 * fingerprint moving, the tree by being the same install's other output — and a
 * version of this that named only the shim would be the graph missing exactly
 * the class the ticket exists to bring into it.
 */
public final class ArtifactStalenessTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ArtifactStalenessTest");

        suite.test("an untouched home reports its fingerprinted shim as current", () -> {
            SkillStore store = fingerprinted();
            ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store), store);

            var shim = freshness.of(ArtifactIds.cliShim("skill-script", "alpha-script"));
            assertEquals(ArtifactFreshness.Freshness.CURRENT, shim.freshness(),
                    "nothing moved: " + shim.reason());
            var tree = freshness.of(ArtifactIds.provisionedTree("cache", "skill-script-alpha-alpha-script"));
            assertEquals(ArtifactFreshness.Freshness.CURRENT, tree.freshness(),
                    "and neither did the tree that install wrote: " + tree.reason());
        });

        suite.test("editing skill-scripts/ makes the shim AND its tree stale, with no install",
                () -> {
                    SkillStore store = fingerprinted();
                    // One byte, in the tree the fingerprint is over. No install,
                    // no sync, no touching of bin/cli or cache/.
                    Path script = store.root().resolve("skills/alpha/skill-scripts/install.sh");
                    Files.writeString(script, Files.readString(script) + "\n# edited\n");

                    ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store),
                            store);

                    var shim = freshness.of(ArtifactIds.cliShim("skill-script", "alpha-script"));
                    assertEquals(ArtifactFreshness.Freshness.STALE, shim.freshness(),
                            "the shim's declared inputs moved: " + shim.reason());
                    assertContains(shim.reason(), "skill-scripts/",
                            "and the reason names what moved");

                    var tree = freshness.of(ArtifactIds.provisionedTree("cache", "skill-script-alpha-alpha-script"));
                    assertEquals(ArtifactFreshness.Freshness.STALE, tree.freshness(),
                            "so did the tree that same install wrote: " + tree.reason());

                    // And every output is still exactly where it was — which is
                    // the point: no presence check in the system moved.
                    assertTrue(Files.exists(store.root().resolve("bin/cli/alpha-script")),
                            "the shim file is untouched");
                    assertTrue(Files.isDirectory(store.root().resolve("cache/skill-script-alpha-alpha-script")),
                            "and so is the tree");
                });

        suite.test("a shim whose declaring unit is gone is unverifiable, never current", () -> {
            SkillStore store = fingerprinted();
            // The unit's manifest is what declares the inputs. Without it there
            // is nothing to re-read, and "I did not look" is the only true
            // answer — the rule `skt check` learned the hard way.
            Files.delete(store.root().resolve("skills/alpha/skill-manager.toml"));

            ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store), store);
            var shim = freshness.of(ArtifactIds.cliShim("skill-script", "alpha-script"));
            assertEquals(ArtifactFreshness.Freshness.UNVERIFIABLE, shim.freshness(),
                    "an input that cannot be read is not a passing one: " + shim.reason());
            assertContains(shim.reason(), "no longer see",
                    "and it says which side of the comparison is missing");
        });

        suite.test("a row with no recorded fingerprint is unverifiable, not current", () -> {
            SkillStore store = ArtifactsFixture.seed();
            // The fixture's pip shim IS the dangler — a link into a tree this
            // home does not hold — and since ARTI-06 that alone decides it
            // (stale). Materialise its target first, so this case measures the
            // axis it is named for: the row carries no install_fingerprint,
            // which is the 0-of-16 state the epic found on the real home.
            Path target = store.root().resolve("cache/uv-tools/alpha/bin/dangler");
            Files.createDirectories(target.getParent());
            Files.writeString(target, "#!/bin/sh\necho ok\n");
            target.toFile().setExecutable(true);

            ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store), store);
            var shim = freshness.of(ArtifactIds.cliShim("pip", "alpha-pkg"));
            assertEquals(ArtifactFreshness.Freshness.UNVERIFIABLE, shim.freshness(),
                    "nothing to compare against: " + shim.reason());
        });

        suite.test("ARTI-06: a shim on disk whose output is hidden stops being current", () -> {
            // THE case the epic exists for, and the one `stale` reported as
            // CURRENT through ARTI-05. Measured then, on a real home:
            //   $ mv bin/cli/skt bin/cli/.hidden
            //   freshness = CURRENT "its inputs still hash to the fingerprint
            //                        recorded at install"
            SkillStore store = fingerprinted();
            var before = ArtifactFreshness.of(ArtifactIndex.of(store), store)
                    .of(ArtifactIds.cliShim("skill-script", "alpha-script"));
            assertEquals(ArtifactFreshness.Freshness.CURRENT, before.freshness(),
                    "precondition: it is current while its output is there");

            Files.move(store.root().resolve("bin/cli/alpha-script"),
                    store.root().resolve("bin/cli/.hidden"));

            var after = ArtifactFreshness.of(ArtifactIndex.of(store), store)
                    .of(ArtifactIds.cliShim("skill-script", "alpha-script"));
            assertEquals(ArtifactFreshness.Freshness.STALE, after.freshness(),
                    "a declared artifact with nothing at its path is not current: "
                            + after.reason());
            assertContains(after.reason(), "bin/cli/alpha-script",
                    "and the reason names the output that is not there");
            assertEquals(Artifact.Materialization.DECLARED_ONLY, after.materialization(),
                    "the verdict carries the axis that decided it");
        });

        suite.test("ARTI-06: presence may DEMOTE a verdict and may never promote one", () -> {
            // The companion that keeps the rule above from BEING the presence
            // proxy this epic removes. A present output earns nothing: an
            // artifact whose inputs moved is still stale with its file in place.
            SkillStore store = fingerprinted();
            Files.writeString(store.root().resolve("skills/alpha/skill-scripts/install.sh"),
                    "# replaced\n");

            var moved = ArtifactFreshness.of(ArtifactIndex.of(store), store)
                    .of(ArtifactIds.cliShim("skill-script", "alpha-script"));
            assertEquals(ArtifactFreshness.Freshness.STALE, moved.freshness(),
                    "a present file does not rescue moved inputs: " + moved.reason());
            assertEquals(Artifact.Materialization.MATERIALIZED, moved.materialization(),
                    "and the artifact IS on disk, so presence contributed nothing here");
            assertContains(moved.reason(), "skill-scripts/",
                    "the reason is still the input comparison, not the file");
        });

        suite.test("ARTI-06: stale --json carries the axis that decided each row", () -> {
            SkillStore store = fingerprinted();
            Files.move(store.root().resolve("bin/cli/alpha-script"),
                    store.root().resolve("bin/cli/.hidden"));

            StaleReport report = StaleReport.of(store.root().toString(),
                    ArtifactFreshness.of(ArtifactIndex.of(store), store));

            StaleReport.VerdictView row = report.stale().stream()
                    .filter(v -> v.id().equals(ArtifactIds.cliShim("skill-script", "alpha-script")))
                    .findFirst().orElseThrow();
            assertEquals("declared-only", row.materialization(),
                    "a consumer of stale --json alone can now tell the two apart");
            assertTrue(StaleReport.SCHEMA >= 2, "and the schema says the field is there");
        });

        suite.test("staleness propagates downstream and names what caused it", () -> {
            SkillStore store = fingerprinted();
            Path script = store.root().resolve("skills/alpha/skill-scripts/install.sh");
            Files.writeString(script, "# replaced\n");

            ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store), store);
            var tree = freshness.of(ArtifactIds.provisionedTree("cache", "skill-script-alpha-alpha-script"));
            assertEquals(ArtifactFreshness.Freshness.STALE, tree.freshness(), "stale");

            StaleReport report = StaleReport.of(store.root().toString(), freshness);
            assertTrue(report.summary().stale() >= 2,
                    "the shim and the tree are both named: " + report.summary().stale());
            assertTrue(report.stale().stream()
                            .anyMatch(v -> v.kind().equals(ArtifactKind.PROVISIONED_TREE.id())),
                    "a provisioned tree is in the stale list, which is new in ARTI-05");
        });

        suite.test("a tree named only through a BROKEN link is credited to nobody", () -> {
            SkillStore store = fingerprinted();
            ArtifactIndex index = ArtifactIndex.of(store);

            // bin/cli/dangler -> cache/uv-tools/alpha/bin/dangler, which is not
            // there. It is the ONLY shim naming cache/uv-tools, so a rule that
            // asked no more than "exactly one claimant" would hand a shared uv
            // root the pip row's fingerprint — and a `stale` verdict would then
            // name a unit that did not write the tree.
            Artifact shared = index.byId(ArtifactIds.provisionedTree("cache", "uv-tools"))
                    .orElseThrow();
            assertTrue(shared.owner() == null,
                    "a broken link is no evidence about who wrote a directory, but it named "
                            + shared.owner());
            assertEquals(List.of(), shared.inputs(), "so the tree declares nothing");
            assertEquals(ArtifactFreshness.Freshness.UNVERIFIABLE,
                    ArtifactFreshness.of(index, store)
                            .of(ArtifactIds.provisionedTree("cache", "uv-tools")).freshness(),
                    "and is reported undecided rather than guessed at");
        });

        suite.test("--kind filters the counts too, so a report cannot contradict itself", () -> {
            SkillStore store = fingerprinted();
            Files.writeString(store.root().resolve("skills/alpha/skill-scripts/install.sh"),
                    "# replaced\n");
            ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store), store);

            StaleReport all = StaleReport.of(store.root().toString(), freshness, null);
            StaleReport trees = StaleReport.of(store.root().toString(), freshness,
                    ArtifactKind.PROVISIONED_TREE);

            assertTrue(all.summary().stale() > trees.summary().stale(),
                    "the filter actually narrows the set");
            assertEquals(trees.stale().size(), trees.summary().stale(),
                    "and the count describes the rows beside it, not the whole home");
            assertTrue(trees.summary().artifacts() < all.summary().artifacts(),
                    "denominator included");
            for (StaleReport.VerdictView view : trees.stale()) {
                assertEquals(ArtifactKind.PROVISIONED_TREE.id(), view.kind(),
                        "every row is of the requested kind");
            }
        });

        suite.test("a missing input makes a verdict unverifiable rather than current", () -> {
            SkillStore store = fingerprinted();
            // The tree the shim execs into is gone; the shim itself is
            // untouched and its fingerprint still matches. "Its inputs agree"
            // is true and "it is current" is not.
            deleteRecursive(store.root().resolve("cache/skill-script-alpha-alpha-script"));

            ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store), store);
            var shim = freshness.of(ArtifactIds.cliShim("skill-script", "alpha-script"));
            assertFalse(shim.freshness() == ArtifactFreshness.Freshness.CURRENT,
                    "a shim that cannot reach its tree is not current: " + shim.reason());
        });

        suite.test("an installed plugin the marketplace does not list is stale", () -> {
            SkillStore store = ArtifactsFixture.seed();
            // The fixture's manifest lists `beta`. Rewrite it to list nothing:
            // the generated set has fallen behind the set it is generated from,
            // which is the question `RefreshHarnessPlugins` never asks.
            Files.writeString(
                    store.root().resolve("plugin-marketplace/.claude-plugin/marketplace.json"),
                    "{\"name\": \"fixture\", \"plugins\": []}\n");

            ArtifactIndex index = ArtifactIndex.of(store);
            var entry = index.byId(ArtifactIds.marketplaceEntry("beta"));
            assertTrue(entry.isPresent(),
                    "the artifact that should exist and does not is still listed");

            ArtifactFreshness freshness = ArtifactFreshness.of(index, store);
            var verdict = freshness.of(ArtifactIds.marketplaceEntry("beta"));
            assertEquals(ArtifactFreshness.Freshness.STALE, verdict.freshness(),
                    "the manifest is behind: " + verdict.reason());
        });

        suite.test("a re-pointed marketplace link DISAGREES — the identity half is compared", () -> {
            // The stored link target is an input to the marketplace digest, so
            // re-pointing it changes the artifact. Recomputing `actual` from the
            // RECORDED target made that self-confirming: the comparison re-hashed
            // its own answer and could never disagree. Both halves come off the
            // disk now, and this is what pins it.
            SkillStore store = ArtifactsFixture.seed();
            var mp = new dev.skillmanager.project.PluginMarketplace(store);
            mp.regenerate();
            assertEquals(Artifact.Agreement.AGREES,
                    agreementOf(store, ArtifactIds.marketplaceEntry("beta")),
                    "precondition: a freshly generated entry agrees with its record");

            Path link = mp.pluginsLinkDir().resolve("beta");
            Files.deleteIfExists(link);
            Files.createSymbolicLink(link, Path.of("../../plugins/somewhere-else"));

            assertEquals(Artifact.Agreement.DISAGREES,
                    agreementOf(store, ArtifactIds.marketplaceEntry("beta")),
                    "a link re-pointed at another unit is a changed artifact and must be able "
                            + "to say so");
        });

        suite.test("a declared MCP server nothing registered is a node, and it is stale", () -> {
            SkillStore store = ArtifactsFixture.withMcpUnit(ArtifactsFixture.seed());
            ArtifactIndex index = ArtifactIndex.of(store);

            var declared = index.byId(ArtifactIds.mcpRegistration("demo-unregistered"));
            assertTrue(declared.isPresent(),
                    "a dependency nothing registered is an artifact, not an absence");
            assertEquals("mcp-alpha", declared.orElseThrow().owner(),
                    "and it has the unit that declares it as its owner");
            assertContains(declared.orElseThrow().inputs().toString(), "unit:mcp-alpha",
                    "which is also its input, so a unit moving reaches it");

            ArtifactFreshness freshness = ArtifactFreshness.of(index, store);
            assertEquals(ArtifactFreshness.Freshness.STALE,
                    freshness.of(ArtifactIds.mcpRegistration("demo-unregistered")).freshness(),
                    "declared and never registered");
            assertEquals(ArtifactFreshness.Freshness.UNVERIFIABLE,
                    freshness.of(ArtifactIds.mcpRegistration("demo-mcp")).freshness(),
                    "and a registration this home recorded no digest for is not claimed current");
        });

        suite.test("a recorded MCP digest is DECIDABLE against the live declaration", () -> {
            // The reason this class does not have to be hardcoded unverifiable:
            // specDigest covers {load_spec, init_schema} only, both pure
            // functions of the installed McpDependency. Nothing about the
            // installing PROCESS is in it, so this pass recomputes it exactly.
            SkillStore store = ArtifactsFixture.withMcpUnit(ArtifactsFixture.seed());
            recordMcpDigest(store, "demo-mcp");

            // Decidability is an AGREEMENT, which is what ArtifactBackfill
            // computes and what this ticket added. Asserted on that axis rather
            // than on the folded verdict: the fold also carries this artifact's
            // UPSTREAM, and in this fixture `unit-store:mcp-alpha` is
            // undecidable (no git checkout behind it), which correctly holds the
            // final verdict at UNVERIFIABLE for a `declared` digest. Asserting
            // CURRENT here would have been asserting that the upstream rule does
            // not apply — see the next case.
            assertEquals(Artifact.Agreement.AGREES,
                    agreementOf(store, ArtifactIds.mcpRegistration("demo-mcp")),
                    "an untouched declaration matches its recorded digest — a real comparison, "
                            + "not an UNRECORDED shrug");

            // Bump the image the dep declares — a real manifest edit.
            Path toml = store.root().resolve("skills/mcp-alpha/skill-manager.toml");
            Files.writeString(toml, Files.readString(toml)
                    .replace("image = \"example/demo:1\"", "image = \"example/demo:2\""));

            assertEquals(Artifact.Agreement.DISAGREES,
                    agreementOf(store, ArtifactIds.mcpRegistration("demo-mcp")),
                    "and the comparison notices");
            var after = ArtifactFreshness.of(ArtifactIndex.of(store), store)
                    .of(ArtifactIds.mcpRegistration("demo-mcp"));
            assertEquals(ArtifactFreshness.Freshness.STALE, after.freshness(),
                    "editing the declared mcp spec makes the registration stale, with no "
                            + "gateway anywhere: " + after.reason());
        });

        suite.test("a `declared` digest does not outrank an undecided record about it", () -> {
            // ARTI-05 granted "direct" evidence — a verdict that survives an
            // UNVERIFIABLE upstream — only to a hash of bytes re-read off this
            // home's disk, and byInstallFingerprint gates it on
            // `now.isResolved()`. The MCP digest is graded `declared`, so it
            // must not get that privilege; the CLI rows would not. One bar, not
            // two, and this method set the flag unconditionally at first.
            SkillStore store = ArtifactsFixture.withMcpUnit(ArtifactsFixture.seed());
            recordMcpDigest(store, "demo-mcp");
            var verdict = ArtifactFreshness.of(ArtifactIndex.of(store), store)
                    .of(ArtifactIds.mcpRegistration("demo-mcp"));
            assertEquals(ArtifactFreshness.Freshness.UNVERIFIABLE, verdict.freshness(),
                    "a declared digest that agrees does not clear an undecided input: "
                            + verdict.reason());
        });

        suite.test("the MCP digest does not cover init VALUES, which keeps a secret out", () -> {
            // The persisted basis says so; this is the measurement behind it,
            // and the property is why a digest recorded by an install that read
            // a secret is reproducible by a pass that cannot see one.
            SkillStore store = ArtifactsFixture.withMcpUnit(ArtifactsFixture.seed());
            var dep = liveDep(store, "demo-mcp");
            var client = new dev.skillmanager.mcp.GatewayClient(
                    dev.skillmanager.mcp.GatewayConfig.of(
                            java.net.URI.create("http://127.0.0.1:1")));
            assertEquals(
                    dev.skillmanager.mcp.GatewayClient.specDigest(
                            client.registerPayload(dep, false, java.util.Map.of())),
                    dev.skillmanager.mcp.GatewayClient.specDigest(
                            client.registerPayload(dep, true,
                                    java.util.Map.of("API_KEY", "rpa_PLAINTEXT_SECRET"))),
                    "neither the deploy decision nor an env-resolved init value enters the "
                            + "digest — so nothing derived from a plaintext secret is written "
                            + "into mcp-lock.json, and a later pass reproduces the digest "
                            + "without ever seeing one");
        });

        // ------------------------------------------------------------ ARTI-18
        // The largest class in a real home — 106 of 190 artifacts, five times
        // the next — of which 105 could not reach a verdict because the field
        // that decided the kind (`boundHash`) describes a DIFFERENT kind. A
        // symlink copies no bytes, so it never has one; reading where it points
        // is the question it can actually answer.

        suite.test("ARTI-18: a link that resolves to its declared source is current", () -> {
            SkillStore store = ArtifactsFixture.seed();
            if (ArtifactsFixture.withProjectedGitUnit(store, "delta", null) == null) {
                skipped("git is unavailable, so there is no current unit to project");
                return;
            }
            ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store), store);
            assertEquals(ArtifactFreshness.Freshness.CURRENT,
                    freshness.of(ArtifactIds.unitStore("delta")).freshness(),
                    "precondition: the source unit is current");

            var projection = onlyProjectionOf(freshness, "delta");
            assertEquals(ArtifactFreshness.Freshness.CURRENT, projection.freshness(),
                    "a correct link over a current unit: " + projection.reason());
            assertContains(projection.reason(), "skills/delta",
                    "and the reason names the source it resolves to");
        });

        suite.test("ARTI-18: a repointed link is a definite negative, not unverifiable", () -> {
            SkillStore store = ArtifactsFixture.seed();
            Path dest = ArtifactsFixture.withProjectedGitUnit(store, "delta", null);
            if (dest == null) {
                skipped("git is unavailable");
                return;
            }
            // No install, no sync — one link, pointed somewhere else.
            Files.delete(dest);
            Files.createSymbolicLink(dest, store.root().resolve("skills/alpha"));

            var projection = onlyProjectionOf(
                    ArtifactFreshness.of(ArtifactIndex.of(store), store), "delta");
            assertEquals(ArtifactFreshness.Freshness.STALE, projection.freshness(),
                    "reporting `I cannot tell` about a link this pass just read is the "
                            + "over-generous oracle the epic removes: " + projection.reason());
            assertContains(projection.reason(), "skills/delta",
                    "the reason names what was declared");
            assertContains(projection.reason(), "skills/alpha",
                    "and what it found instead");
            // THE ARTI-06 invariant, from the other side: the link is on disk
            // and it resolves, so every presence check in the system passes.
            assertEquals(Artifact.Materialization.MATERIALIZED, projection.materialization(),
                    "presence contributed nothing — the verdict is the link comparison");
        });

        suite.test("ARTI-18: a dangling link says which source this home does not hold", () -> {
            SkillStore store = ArtifactsFixture.seed();
            Path dest = ArtifactsFixture.withProjectedGitUnit(store, "delta", null);
            if (dest == null) {
                skipped("git is unavailable");
                return;
            }
            // The link still names the declared source; the source is gone.
            deleteRecursive(store.root().resolve("skills/delta"));

            var projection = onlyProjectionOf(
                    ArtifactFreshness.of(ArtifactIndex.of(store), store), "delta");
            assertEquals(ArtifactFreshness.Freshness.STALE, projection.freshness(),
                    "not `unverifiable`: " + projection.reason());
            assertContains(projection.reason(), "does not hold it",
                    "and it says which of the two negatives this is");
        });

        suite.test("ARTI-18: the copy fallback is undecided rather than wrong", () -> {
            SkillStore store = ArtifactsFixture.seed();
            Path dest = ArtifactsFixture.withProjectedGitUnit(store, "delta", null);
            if (dest == null) {
                skipped("git is unavailable");
                return;
            }
            // MaterializeProjection falls back to Fs.copyRecursive where a
            // filesystem refuses symlinks, so a row recorded SYMLINK is
            // legitimately a real tree. Calling that `repointed` would report a
            // correct projection broken; calling it current would credit a copy
            // nothing compared.
            Files.delete(dest);
            Files.createDirectories(dest);
            Files.writeString(dest.resolve("SKILL.md"), "---\nname: delta\n---\n");

            var projection = onlyProjectionOf(
                    ArtifactFreshness.of(ArtifactIndex.of(store), store), "delta");
            assertEquals(ArtifactFreshness.Freshness.UNVERIFIABLE, projection.freshness(),
                    "there is no pointer to compare: " + projection.reason());
            assertContains(projection.reason(), "copy fallback",
                    "and the reason says why, rather than crashing or guessing");
        });

        suite.test("ARTI-18: presence may DEMOTE a projection and may never promote one", () -> {
            // The companion to the ARTI-06 case above, in the class that newly
            // reaches CURRENT. The link is perfect and its file is on disk; the
            // unit it projects records a commit its own checkout is not on. A
            // projection of a stale unit is stale, and nothing about the link
            // being there rescues it.
            SkillStore store = ArtifactsFixture.seed();
            Path dest = ArtifactsFixture.withProjectedGitUnit(store, "delta",
                    "419d73886012b3472759763d928590417824ca1b");
            if (dest == null) {
                skipped("git is unavailable");
                return;
            }
            ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store), store);
            assertEquals(ArtifactFreshness.Freshness.STALE,
                    freshness.of(ArtifactIds.unitStore("delta")).freshness(),
                    "precondition: the source unit is stale");

            var projection = onlyProjectionOf(freshness, "delta");
            assertEquals(ArtifactFreshness.Freshness.STALE, projection.freshness(),
                    "freshness composes: " + projection.reason());
            assertEquals(Artifact.Materialization.MATERIALIZED, projection.materialization(),
                    "with the link present and resolving the whole time");
            assertTrue(projection.because().contains(ArtifactIds.unitStore("delta")),
                    "and it names the unit that decided it: " + projection.because());
        });

        suite.test("ARTI-18: boundHash keeps deciding the kind that carries one", () -> {
            // Rule 3 of the slice, as a test rather than an assertion. The
            // fixture's doc-repo MANAGED_COPY has a real boundHash; nothing
            // about the symlink rule may touch how it is decided.
            SkillStore store = ArtifactsFixture.seed();
            var managed = ArtifactIndex.of(store).artifacts().stream()
                    .filter(a -> a.kind() == ArtifactKind.PROJECTION)
                    .filter(a -> "MANAGED_COPY".equals(a.recorded().get("projection_kind")))
                    .findFirst().orElseThrow();
            assertEquals(Artifact.Agreement.AGREES, managed.agreement(),
                    "its recorded hash still describes the bytes beside it");
            assertTrue(managed.actual().get("link_state") == null,
                    "and no link was probed for it, so one field cannot mean two things");
            // Its verdict is still whatever the pre-ARTI-18 rules made it —
            // here `unverifiable`, inherited from an undecided `unit-store:
            // handbook`, exactly as before. What must not appear is a link.
            assertFalse(ArtifactFreshness.of(ArtifactIndex.of(store), store)
                            .of(managed.id()).reason().contains("link"),
                    "the symlink rule does not reach the kind that carries a hash");
        });

        return suite.runAll();
    }

    /** The recorded-versus-disk agreement this home derives for one artifact. */
    private static Artifact.Agreement agreementOf(SkillStore store, String id) throws Exception {
        return ArtifactIndex.of(store).byId(id)
                .orElseThrow(() -> new IllegalStateException("no artifact " + id))
                .agreement();
    }

    /** The installed dependency named {@code server}, as the home parses it. */
    private static dev.skillmanager.model.McpDependency liveDep(SkillStore store, String server)
            throws Exception {
        for (var unit : store.listInstalledUnits().units()) {
            for (var dep : unit.mcpDependencies()) {
                if (server.equals(dep.name())) return dep;
            }
        }
        throw new IllegalStateException("the fixture declares " + server);
    }

    /**
     * Write the {@code mcp-lock.json} row a successful registration leaves —
     * digest computed from the LIVE dep, from the same object and the same call
     * {@code McpWriter} uses.
     */
    private static void recordMcpDigest(SkillStore store, String server) throws Exception {
        var dep = liveDep(store, server);
        var client = new dev.skillmanager.mcp.GatewayClient(
                dev.skillmanager.mcp.GatewayConfig.of(java.net.URI.create("http://127.0.0.1:1")));
        String digest = dev.skillmanager.mcp.GatewayClient.specDigest(
                client.registerPayload(dep, true, java.util.Map.of()));
        dev.skillmanager.mcp.McpRegistrationLock.read(store.root())
                .with(dev.skillmanager.mcp.McpRegistrationLock.Entry.of(
                        server, "mcp-alpha", dep.defaultScope(),
                        dev.skillmanager.lock.Fingerprint.declared(digest,
                                dev.skillmanager.mcp.McpRegistrationLock.BASIS)))
                .write(store.root());
    }

    /**
     * The one projection artifact owned by {@code unit}. Looked up by owner
     * rather than by rebuilding its id, because the id grammar is
     * {@link ArtifactIds}'s private matter and a test that re-spells it is a
     * second spelling that can disagree.
     */
    private static ArtifactFreshness.Verdict onlyProjectionOf(ArtifactFreshness freshness,
                                                              String unit) {
        List<ArtifactFreshness.Verdict> found = freshness.all().stream()
                .filter(v -> v.kind() == ArtifactKind.PROJECTION && unit.equals(v.owner()))
                .toList();
        assertEquals(1, found.size(), "exactly one projection of " + unit + ": " + found);
        return found.get(0);
    }

    private static void skipped(String why) {
        System.out.println("  [SKIP] ARTI-18: " + why);
    }

    /**
     * {@link ArtifactsFixture#seed} plus a real {@code skill-scripts/} tree and
     * the fingerprint its backend computes over it — the shape a home has after
     * one install, and the only shape in which "did my inputs move" is a
     * question with an answer.
     */
    private static SkillStore fingerprinted() throws Exception {
        SkillStore store = ArtifactsFixture.seed();
        Path scripts = store.root().resolve("skills/alpha/skill-scripts");
        Files.createDirectories(scripts);
        Files.writeString(scripts.resolve("install.sh"), "#!/bin/sh\necho installing\n");

        CliDependency dep = declaredScriptDep(store);
        var fingerprint = new InstallerRegistry().fingerprintFor(dep, store, "alpha");
        assertTrue(fingerprint.present(),
                "the fixture must produce a real digest, not a gap: " + fingerprint.gap());

        CliLock lock = CliLock.load(store);
        lock.recordInstall("skill-script", "alpha-script", "1.0.0", "skill-script:alpha-script",
                null, "alpha", fingerprint, "alpha-script");
        lock.save(store);
        return store;
    }

    private static CliDependency declaredScriptDep(SkillStore store) throws Exception {
        for (var unit : store.listInstalledUnits().units()) {
            for (CliDependency dep : unit.cliDependencies()) {
                if ("skill-script".equals(dep.backend())) return dep;
            }
        }
        throw new IllegalStateException("the fixture declares a skill-script dep");
    }

    private static void deleteRecursive(Path path) throws Exception {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

}
