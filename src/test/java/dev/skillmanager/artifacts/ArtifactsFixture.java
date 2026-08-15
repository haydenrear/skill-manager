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
        Path provisioned = home.resolve("cache/uv-tools/alpha/bin");
        Files.createDirectories(provisioned);
        Files.writeString(provisioned.resolve("alpha-script"), "#!/bin/sh\necho ok\n");
        provisioned.resolve("alpha-script").toFile().setExecutable(true);
        Files.writeString(bin.resolve("alpha-script"),
                "#!/bin/sh\nexec \"" + provisioned.resolve("alpha-script") + "\" \"$@\"\n");
        bin.resolve("alpha-script").toFile().setExecutable(true);
        // And the symlink shape beside it, dangling from the start.
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
        new HomeDigest(HomeDigest.SCHEMA_VERSION, "2026-01-01T00:00:00Z", "root-digest",
                List.of(new HomeDigest.UnitDigest("alpha", "SKILL", "digest-alpha",
                        Map.of("SKILL.md", "hash-1")))).write(store);

        return store;
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
