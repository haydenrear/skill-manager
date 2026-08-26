package dev.skillmanager.artifacts;

import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingSource;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ConflictPolicy;
import dev.skillmanager.bindings.HarnessInstanceLock;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionKind;
import dev.skillmanager.bindings.Sha256;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.HomeDigest;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One seeded home holding at least one instance of every
 * {@link ArtifactKind}, plus the two shapes the epic's measured evidence
 * says a model has to be able to express:
 *
 * <ul>
 *   <li>a shim that exists and cannot run — {@code bin/cli/dangler} pointing
 *       into a {@code cache/} tree the home does not hold, which is exactly the
 *       {@code bin/cli/jinja2} and {@code bin/cli/skill-dev} pair every cloned
 *       ticket home ships;</li>
 *   <li>a unit whose recorded {@code gitHash} does NOT describe its own store —
 *       {@code hyper-experiments-finance} at {@code 419d7388} against a checkout
 *       at {@code 5b3c8d2b}. That needs a real repository to be a real test, so
 *       it lives in {@link #withGitUnit} and is added only where it is asserted
 *       on; the clone fixture stays git-free so a clone's own verification is
 *       measuring this ledger and not a {@code .git} directory.</li>
 * </ul>
 *
 * <p>Deliberately built through the production writers ({@link UnitStore},
 * {@link BindingStore}, {@link CliLock}, {@link HomeDigest}) rather than by
 * hand-writing JSON, so a fixture cannot drift into describing a home shape
 * that no writer produces.
 */
final class ArtifactsFixture {

    private ArtifactsFixture() {}

    static Path newDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }

    /** A home with one artifact of every kind. */
    static SkillStore seed() throws Exception {
        Path home = newDir("artifacts-home-");
        SkillStore store = new SkillStore(home);
        store.init();

        // --- unit store + the unit that declares the CLI deps -------------
        Path alpha = home.resolve("skills/alpha");
        Files.createDirectories(alpha);
        Files.writeString(alpha.resolve("SKILL.md"),
                "---\nname: alpha\ndescription: fixture\n---\nbody\n");
        Files.writeString(alpha.resolve("skill-manager.toml"), """
                [skill]
                name = "alpha"
                version = "0.1.0"
                description = "alpha fixture"

                [[cli_dependencies]]
                name = "alpha-pkg"
                spec = "pip:alpha-pkg"
                on_path = "dangler"

                [[cli_dependencies]]
                name = "alpha-script"
                spec = "skill-script:alpha-script"
                on_path = "alpha-script"

                # ARTI-05 needs the dep to be FINGERPRINTABLE, which means it
                # has to name the script whose tree the digest is over. Without
                # an install target `SkillScriptBackend.fingerprint` returns a
                # gap, and a gap is the one thing this fixture must not silently
                # be — a staleness test that passes because nothing could be
                # computed proves nothing about staleness.
                [cli_dependencies.install.any]
                script = "install.sh"
                binary = "alpha-script"
                """);
        new UnitStore(store).write(new InstalledUnit("alpha", "0.1.0",
                InstalledUnit.Kind.LOCAL_DIR, InstalledUnit.InstallSource.LOCAL_FILE,
                null, null, null, "2026-01-01T00:00:00Z", List.of(), UnitKind.SKILL));

        // --- cli shims: one that works, one that exists and cannot run ----
        CliLock lock = CliLock.load(store);
        lock.put(new CliLock.Entry("skill-script", "alpha-script", "1.0.0",
                "skill-script:alpha-script", null, List.of("alpha"),
                "2026-01-01T00:00:00Z", "fingerprint-abc"));
        lock.put(new CliLock.Entry("pip", "alpha-pkg", "2.0.0", "pip:alpha-pkg",
                null, List.of("alpha"), "2026-01-01T00:00:00Z", null));
        lock.save(store);
        Path bin = home.resolve("bin/cli");
        Files.createDirectories(bin);
        // A generated WRAPPER, not a symlink: seven of the ten shims in the
        // operator's home are this shape, and it is the one every presence
        // check called healthy — the wrapper is a fine executable whatever
        // happened to the tree it execs into.
        //
        // The wrapper's target is under its OWN tree and RESOLVES; the dangling
        // symlink beside it points into a DIFFERENT tree and does not. That is
        // the real home's shape (`cache/skill-script-deploy-helm-computeq` per
        // skill-script dep, `cache/uv-tools` shared by the uv-installed tools)
        // and both halves are load-bearing for ARTI-05:
        //
        //   - `cache/skill-script-alpha-alpha-script` is credited to the
        //     skill-script row, so a skill-scripts/ edit reaches it;
        //   - `cache/uv-tools` is credited to NOBODY, because the only shim
        //     naming it does so through a broken link, and a broken link is no
        //     evidence about which install wrote a directory. If it were
        //     credited, a shared uv root would inherit the pip row's
        //     fingerprint and a `stale` verdict would name the wrong unit.
        Path provisioned = home.resolve("cache/skill-script-alpha-alpha-script/venv/bin");
        Files.createDirectories(provisioned);
        Files.writeString(provisioned.resolve("alpha-script"), "#!/bin/sh\necho ok\n");
        provisioned.resolve("alpha-script").toFile().setExecutable(true);
        Files.writeString(bin.resolve("alpha-script"),
                "#!/bin/sh\nexec \"" + provisioned.resolve("alpha-script") + "\" \"$@\"\n");
        bin.resolve("alpha-script").toFile().setExecutable(true);
        // And the symlink shape beside it, dangling from the start.
        Files.createDirectories(home.resolve("cache/uv-tools"));
        Files.createSymbolicLink(bin.resolve("dangler"),
                Path.of("../../cache/uv-tools/alpha/bin/dangler"));

        // --- provisioned trees (what a clone skips) -----------------------
        Files.createDirectories(home.resolve("venvs/alpha-venv"));

        // --- projections ---------------------------------------------------
        Path agentSkills = home.resolve(".claude/skills");
        Files.createDirectories(agentSkills);
        Files.createSymbolicLink(agentSkills.resolve("alpha"), alpha);
        new BindingStore(store).write(new dev.skillmanager.bindings.ProjectionLedger("alpha",
                List.of(new Binding("default:claude:alpha", "alpha", UnitKind.SKILL, null,
                        agentSkills, ConflictPolicy.ERROR, "2026-01-01T00:00:00Z",
                        BindingSource.DEFAULT_AGENT,
                        List.of(new Projection("default:claude:alpha", alpha,
                                agentSkills.resolve("alpha"), ProjectionKind.SYMLINK, null))))));

        // --- marketplace ---------------------------------------------------
        Path plugins = home.resolve("plugins/beta");
        Files.createDirectories(plugins);
        // ARTI-05 compares the generated manifest against the INSTALLED plugin
        // set, so the plugin has to be installed rather than merely present as
        // a directory — otherwise the fixture models a home in which every
        // marketplace row is orphaned.
        Files.createDirectories(plugins.resolve(".claude-plugin"));
        Files.writeString(plugins.resolve(".claude-plugin/plugin.json"), """
                {"name": "beta", "version": "0.1.0", "description": "beta fixture"}
                """);
        new UnitStore(store).write(new InstalledUnit("beta", "0.1.0",
                InstalledUnit.Kind.LOCAL_DIR, InstalledUnit.InstallSource.LOCAL_FILE,
                null, null, null, "2026-01-01T00:00:00Z", List.of(), UnitKind.PLUGIN));
        Files.createDirectories(home.resolve("plugin-marketplace/.claude-plugin"));
        Files.createDirectories(home.resolve("plugin-marketplace/plugins"));
        Files.writeString(home.resolve("plugin-marketplace/.claude-plugin/marketplace.json"), """
                {"name": "fixture", "plugins": [{"name": "beta", "source": "./plugins/beta"}]}
                """);
        Files.createSymbolicLink(home.resolve("plugin-marketplace/plugins/beta"), plugins);

        // --- harness instance ----------------------------------------------
        Path sandbox = home.resolve("harnesses/instances");
        new HarnessInstanceLock("coordinator", "inst-1", null, null, null, null,
                "2026-01-01T00:00:00Z").write(sandbox, home);

        // --- MCP registration ------------------------------------------------
        Files.writeString(home.resolve("gateway-config.json"), """
                {"protocol_version": "2025-11-05", "mcp_servers": [{"server_id": "demo-mcp"}]}
                """);

        // --- doc import -------------------------------------------------------
        Path docs = home.resolve("docs/handbook");
        Files.createDirectories(docs);
        Path docSource = docs.resolve("page.md");
        Files.writeString(docSource, "# page\n");
        Path importedInto = newDir("doc-consumer-").resolve("docs/agents");
        Files.createDirectories(importedInto);
        Path copy = importedInto.resolve("page.md");
        Files.writeString(copy, "# page\n");
        new UnitStore(store).write(new InstalledUnit("handbook", "0.1.0",
                InstalledUnit.Kind.LOCAL_DIR, InstalledUnit.InstallSource.LOCAL_FILE,
                null, null, null, "2026-01-01T00:00:00Z", List.of(), UnitKind.DOC));
        new BindingStore(store).write(new dev.skillmanager.bindings.ProjectionLedger("handbook",
                List.of(new Binding("owner:consumer:page:bind", "handbook", UnitKind.DOC, "page",
                        importedInto.getParent().getParent(), ConflictPolicy.RENAME_EXISTING,
                        "2026-01-01T00:00:00Z", BindingSource.EXPLICIT,
                        List.of(new Projection("owner:consumer:page:bind", docSource, copy,
                                ProjectionKind.MANAGED_COPY, null, Sha256.hashFile(copy)))))));

        // --- digest ------------------------------------------------------------
        // Both installed units that a clone's own `home drift --record` would
        // digest. `beta` is here because it is an installed PLUGIN, and a
        // fixture whose digest names fewer units than the home holds makes the
        // clone-stability comparison fail on the fixture's omission rather than
        // on anything about ids.
        new HomeDigest(HomeDigest.SCHEMA_VERSION, "2026-01-01T00:00:00Z", "root-digest",
                List.of(new HomeDigest.UnitDigest("alpha", "SKILL", "digest-alpha",
                                Map.of("SKILL.md", "hash-1")),
                        new HomeDigest.UnitDigest("beta", "PLUGIN", "digest-beta",
                                Map.of(".claude-plugin/plugin.json", "hash-2")))).write(store);

        return store;
    }

    /**
     * The {@code pip}/{@code brew}/{@code npm} shim shape: {@code bin/cli/<tool>}
     * is ITSELF a symlink into a {@code venvs/} tree.
     *
     * <h2>Why this is a separate shape and not a spelling of the wrapper</h2>
     *
     * <p>{@link #seed} models the skill-script shape, where the home's
     * {@code bin/cli} entry is a generated wrapper — a REGULAR FILE that names
     * its tree in its body. A child home mirrors either shape the same way, by
     * symlinking at the parent's entry, and the two then resolve differently:
     * a chain through a wrapper stops AT the parent's {@code bin/cli/<tool>},
     * and a chain through a symlink shim runs one hop further, past it, into
     * {@code venvs/<x>/bin/<tool>}.
     *
     * <p>ARTI-08's first implementation asked {@code toRealPath()} for the
     * endpoint and compared it by exact string. That is right for the wrapper
     * and wrong for this, and the prune suite's child-home case used the
     * wrapper — so the guard passed while both this shim and the tree feeding
     * it were being deleted out from under a registered child home. The
     * fixture choice was the reason the gap was invisible, which is why this
     * shape now exists here rather than in one test's body.
     *
     * <p>{@code cli-shim:pip/shared-venv} → {@code bin/cli/shared-tool} →
     * {@code provisioned-tree:venvs/shared-venv}.
     */
    static void withSharedVenvShim(SkillStore store) throws Exception {
        Path venvBin = store.root().resolve("venvs/shared-venv/bin");
        Files.createDirectories(venvBin);
        Path tool = venvBin.resolve("shared-tool");
        Files.writeString(tool, "#!/bin/sh\necho shared\n");
        tool.toFile().setExecutable(true);

        Path shim = store.cliBinDir().resolve("shared-tool");
        Files.createDirectories(shim.getParent());
        Files.deleteIfExists(shim);
        // RELATIVE, which is what every installer writes and what makes the
        // link survive a home clone.
        Files.createSymbolicLink(shim, Path.of("../../venvs/shared-venv/bin/shared-tool"));

        CliLock lock = CliLock.load(store);
        lock.put(new CliLock.Entry("pip", "shared-venv", "1.0.0", "pip:shared-venv", null,
                List.of("alpha"), "2026-01-01T00:00:00Z", "shared-tool", null));
        lock.save(store);

        Path manifest = store.root().resolve("skills/alpha/skill-manager.toml");
        Files.writeString(manifest, Files.readString(manifest) + """

                [[cli_dependencies]]
                name = "shared-venv"
                spec = "pip:shared-venv"
                on_path = "shared-tool"
                """);
    }

    /**
     * Add a unit declaring two MCP servers — one the fixture's
     * {@code gateway-config.json} already registers and one nothing registered.
     *
     * <p>The second is the shape a home synced without {@code --include-mcp} is
     * in, and before ARTI-05 nothing in the home said so: an MCP registration
     * was a name in a JSON file with no owner and no input, so a declaration
     * with no registration was not merely undecided — it was invisible.
     */
    static SkillStore withMcpUnit(SkillStore store) throws Exception {
        Path unit = store.root().resolve("skills/mcp-alpha");
        Files.createDirectories(unit);
        Files.writeString(unit.resolve("SKILL.md"),
                "---\nname: mcp-alpha\ndescription: fixture\n---\nbody\n");
        Files.writeString(unit.resolve("skill-manager.toml"), """
                [skill]
                name = "mcp-alpha"
                version = "0.1.0"
                description = "mcp fixture"

                [[mcp_dependencies]]
                name = "demo-mcp"
                display_name = "Demo"
                description = "registered in gateway-config.json"
                [mcp_dependencies.load]
                type = "docker"
                image = "example/demo:1"

                [[mcp_dependencies]]
                name = "demo-unregistered"
                display_name = "Declared only"
                description = "declared by this unit and registered nowhere"
                [mcp_dependencies.load]
                type = "docker"
                image = "example/ghost:1"
                """);
        new UnitStore(store).write(new InstalledUnit("mcp-alpha", "0.1.0",
                InstalledUnit.Kind.LOCAL_DIR, InstalledUnit.InstallSource.LOCAL_FILE,
                null, null, null, "2026-01-01T00:00:00Z", List.of(), UnitKind.SKILL));
        return store;
    }

    /**
     * Replace {@code alpha}'s projection ledger with ONE {@code SYMLINK} row of
     * the caller's choosing, leaving what is on disk to the caller.
     *
     * <p>The point is that most of ARTI-18's link states need no git: only
     * "a correct link over a CURRENT unit" does, because only a git checkout
     * can make a unit-store artifact agree. Every other state
     * ({@code repointed}, {@code dangling}, {@code copied}, {@code absent},
     * {@code undeclared}, {@code resolves-outside}, {@code foreign-home}) is
     * decided by the link comparison or by materialization, both of which
     * dominate whatever the unit-store's own verdict is — so they are asserted
     * here on the git-free seeded home and cannot become a case that "passes"
     * on a machine with no git while asserting nothing.
     *
     * @param source what the ledger DECLARES; null models a row with no source
     */
    static void reprojectAlpha(SkillStore store, Path source, Path dest) throws Exception {
        String bindingId = "default:claude:alpha";
        new BindingStore(store).write(new dev.skillmanager.bindings.ProjectionLedger("alpha",
                List.of(new Binding(bindingId, "alpha", UnitKind.SKILL, null, dest.getParent(),
                        ConflictPolicy.ERROR, "2026-01-01T00:00:00Z", BindingSource.DEFAULT_AGENT,
                        List.of(new Projection(bindingId, source, dest,
                                ProjectionKind.SYMLINK, null))))));
    }

    /** As {@link #reprojectAlpha}, with a {@code boundHash} recorded on the row. */
    static void reprojectAlphaWithHash(SkillStore store, Path source, Path dest, String hash)
            throws Exception {
        String bindingId = "default:claude:alpha";
        new BindingStore(store).write(new dev.skillmanager.bindings.ProjectionLedger("alpha",
                List.of(new Binding(bindingId, "alpha", UnitKind.SKILL, null, dest.getParent(),
                        ConflictPolicy.ERROR, "2026-01-01T00:00:00Z", BindingSource.DEFAULT_AGENT,
                        List.of(new Projection(bindingId, source, dest,
                                ProjectionKind.SYMLINK, null, hash))))));
    }

    /**
     * A directory that IS a Skill Manager home as far as the copied-home
     * detector is concerned — it holds {@code installed/} — carrying its own
     * copy of {@code skills/<unit>} at the same relative place.
     *
     * <p>What an un-re-anchored copy of a home points back at.
     */
    static Path otherHomeHolding(String unit) throws IOException {
        Path other = newDir("artifacts-other-home-");
        Files.createDirectories(other.resolve("installed"));
        Path skill = other.resolve("skills").resolve(unit);
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"),
                "---\nname: " + unit + "\ndescription: the OTHER home's copy\n---\nbody\n");
        return other;
    }

    /**
     * A git-backed unit plus one {@link ProjectionKind#SYMLINK} projection of
     * its store directory — the shape ARTI-18 decides, and the one the seeded
     * home cannot express on its own.
     *
     * <p>The recorded commit is the checkout's OWN head by default, so the
     * unit-store artifact is {@code CURRENT} and the projection's verdict is
     * decided by its link rather than inherited from an undecided input. That
     * matters: {@code alpha} is a {@code LOCAL_DIR} unit with no
     * {@code gitHash}, so its store is legitimately {@code unverifiable} and
     * every projection of it is too, whatever its link says. A test for "a
     * correct link over a current unit is current" needs a current unit to
     * exist.
     *
     * @param recordedHash the commit to record, or null for the checkout's own
     *        head. Passing a hash the checkout is not on is how a caller gets a
     *        STALE unit under a perfectly correct link.
     * @return the destination link, or null when git is unavailable and the
     *         caller should skip the assertion rather than pretend it ran
     */
    static Path withProjectedGitUnit(SkillStore store, String name, String recordedHash)
            throws Exception {
        if (!dev.skillmanager.source.GitOps.isAvailable()) return null;
        Path unit = store.root().resolve("skills/" + name);
        Files.createDirectories(unit);
        Files.writeString(unit.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: fixture\n---\nbody\n");
        String origin = "https://example.invalid/" + name + ".git";
        if (!dev.skillmanager.source.GitOps.initLocalSnapshot(unit, origin)) return null;
        String head = recordedHash != null ? recordedHash
                : dev.skillmanager.source.GitOps.headHash(unit);
        if (head == null) return null;
        new UnitStore(store).write(new InstalledUnit(name, "0.1.0",
                InstalledUnit.Kind.GIT, InstalledUnit.InstallSource.GIT, origin, head, "main",
                "2026-01-01T00:00:00Z", List.of(), UnitKind.SKILL));

        Path agentSkills = store.root().resolve(".claude/skills");
        Files.createDirectories(agentSkills);
        Path dest = agentSkills.resolve(name);
        Files.createSymbolicLink(dest, unit);
        String bindingId = "default:claude:" + name;
        new BindingStore(store).write(new dev.skillmanager.bindings.ProjectionLedger(name,
                List.of(new Binding(bindingId, name, UnitKind.SKILL, null, agentSkills,
                        ConflictPolicy.ERROR, "2026-01-01T00:00:00Z", BindingSource.DEFAULT_AGENT,
                        List.of(new Projection(bindingId, unit, dest,
                                ProjectionKind.SYMLINK, null))))));
        return dest;
    }

    /**
     * Add a real git-backed unit whose {@code installed/} record claims a
     * commit its own checkout is not on.
     *
     * @return the name of the unit, or null when git is unavailable and the
     *         caller should skip the assertion rather than pretend it ran
     */
    static String withGitUnit(SkillStore store) throws Exception {
        if (!dev.skillmanager.source.GitOps.isAvailable()) return null;
        Path gamma = store.root().resolve("skills/gamma");
        Files.createDirectories(gamma);
        Files.writeString(gamma.resolve("SKILL.md"),
                "---\nname: gamma\ndescription: fixture\n---\nbody\n");
        if (!dev.skillmanager.source.GitOps.initLocalSnapshot(
                gamma, "https://example.invalid/gamma.git")) {
            return null;
        }
        new UnitStore(store).write(new InstalledUnit("gamma", "0.1.0",
                InstalledUnit.Kind.GIT, InstalledUnit.InstallSource.GIT,
                "https://example.invalid/gamma.git",
                // A hash the store demonstrably is not on. The real home's
                // version of this is 419d7388 against a checkout at 5b3c8d2b.
                "419d73886012b3472759763d928590417824ca1b",
                "main", "2026-01-01T00:00:00Z", List.of(), UnitKind.SKILL));
        return "gamma";
    }
}
