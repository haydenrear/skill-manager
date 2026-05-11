package dev.skillmanager._lib.harness;

import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.effects.EffectContext;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Test harness for leaf-effect handler tests (tickets 06–10). Wires the real
 * {@link LiveInterpreter} against a temp-directory store so handlers run for
 * real; state transitions (source-record errors, store contents) are
 * observable after each {@link #run} call via {@link #sourceOf} and
 * {@link #context}.
 *
 * <p>Create one harness per test case — every {@link #create()} call gets a
 * fresh temp directory so mutations don't bleed across cases. A true
 * {@code InMemoryFs} fake will replace the real temp directories once that
 * fake gains substance (ticket 06 lands the substrate; later tickets flesh
 * it out).
 *
 * <h2>Sandboxing the harness CLIs</h2>
 *
 * Effects that drive {@code claude}/{@code codex} subprocesses (any path
 * through {@link dev.skillmanager.effects.SkillEffect.SyncAgents} or
 * plugin install) need their {@code CLAUDE_HOME} / {@code CLAUDE_CONFIG_DIR}
 * / {@code CODEX_HOME} to point somewhere safe — otherwise the real
 * developer config files get mutated. Java can't rewrite
 * {@code System.getenv()}, so {@link AgentHomes} maintains a thread-local
 * override map; this harness installs entries at {@link #create()} pointing
 * at {@code <tmp>/claude-home} and {@code <tmp>/codex-home}, and
 * {@link #close()} clears them.
 *
 * <p>Always close the harness — either with try-with-resources or an
 * explicit {@code @AfterEach} call. JUnit reuses worker threads, so a
 * leaked override from one test would otherwise redirect the next test's
 * lookups at a deleted temp dir (fails loudly with ENOENT — still
 * better than the pre-fix "silently pollute ~/.claude" behavior).
 */
public final class TestHarness implements AutoCloseable {

    private final SkillStore store;
    private final EffectContext ctx;
    private final LiveInterpreter interpreter;
    private final Path tmpRoot;

    private TestHarness(SkillStore store, Path tmpRoot) {
        this.store = store;
        this.ctx = new EffectContext(store, null);
        this.interpreter = new LiveInterpreter(store);
        this.tmpRoot = tmpRoot;
    }

    public static TestHarness create() throws IOException {
        java.nio.file.Path tmp = Files.createTempDirectory("skill-handler-test-");
        // Sandboxed agent homes — the harness CLI drivers in
        // HarnessPluginCli read via AgentHomes, which checks these
        // thread-local overrides BEFORE falling back to the real
        // CLAUDE_HOME / CODEX_HOME env vars. Without this any test
        // that drives plugin install would mutate the developer's real
        // ~/.claude/plugins/known_marketplaces.json (issue we
        // discovered when a stale "skill-manager" marketplace entry
        // pointing at a deleted temp dir kept failing
        // `claude plugin marketplace update`).
        Path claudeHome = tmp.resolve("claude-home");
        Path claudeConfigDir = claudeHome.resolve(".claude");
        Path codexHome = tmp.resolve("codex-home");
        Files.createDirectories(claudeConfigDir);
        Files.createDirectories(codexHome);
        AgentHomes.setOverride(AgentHomes.CLAUDE_HOME, claudeHome);
        AgentHomes.setOverride(AgentHomes.CLAUDE_CONFIG_DIR, claudeConfigDir);
        AgentHomes.setOverride(AgentHomes.CODEX_HOME, codexHome);

        SkillStore store = new SkillStore(tmp);
        store.init();
        return new TestHarness(store, tmp);
    }

    /**
     * Sandboxed Claude home (parent of {@code .claude/}). Tests that
     * want to assert "the harness CLI wrote here" point at this path.
     */
    public Path claudeHome() { return tmpRoot.resolve("claude-home"); }

    /**
     * Sandboxed Codex home (contains {@code config.toml} and the plugin
     * marketplace registrations).
     */
    public Path codexHome() { return tmpRoot.resolve("codex-home"); }

    @Override
    public void close() {
        // Drop the AgentHomes thread-local overrides so the next test
        // sharing this JUnit worker thread starts clean. Temp-dir
        // cleanup is left to JUnit's @TempDir / the OS — harness
        // doesn't try to rm -rf, because some tests inspect tmpRoot
        // post-close for assertion details.
        AgentHomes.clearOverrides();
    }

    /**
     * Write a minimal {@link InstalledUnit} record for {@code name} with the
     * given {@link UnitKind}. Effects that read-then-write source records
     * (error add / clear / validate) require this to exist first.
     */
    public void seedUnit(String name, UnitKind kind) throws IOException {
        InstalledUnit u = new InstalledUnit(
                name, "0.1.0", InstalledUnit.Kind.UNKNOWN,
                InstalledUnit.InstallSource.UNKNOWN,
                null, null, null, UnitStore.nowIso(), null, kind);
        new UnitStore(store).write(u);
        ctx.invalidate();
    }

    /**
     * Materialize the on-disk presence checked by {@link SkillStore#contains}
     * / {@link SkillStore#containsPlugin}: a SKILL kind drops a {@code SKILL.md}
     * under {@code skills/<name>}; a PLUGIN kind drops {@code .claude-plugin/plugin.json}
     * under {@code plugins/<name>}. The minimum surface effects like
     * {@link SkillEffect.RejectIfAlreadyInstalled} need to detect "installed".
     */
    public void scaffoldUnitDir(String name, UnitKind kind) throws IOException {
        switch (kind) {
            case SKILL -> {
                Path dir = store.skillDir(name);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("SKILL.md"),
                        "---\nname: " + name + "\ndescription: harness\n---\nbody\n");
            }
            case PLUGIN -> {
                Path dir = store.pluginsDir().resolve(name);
                Files.createDirectories(dir.resolve(".claude-plugin"));
                Files.writeString(dir.resolve(".claude-plugin/plugin.json"),
                        "{ \"name\": \"" + name + "\" }\n");
            }
        }
    }

    /** Reload the source record for {@code name} from disk. */
    public Optional<InstalledUnit> sourceOf(String name) {
        return new UnitStore(store).read(name);
    }

    /** Run one effect and return its receipt. */
    public EffectReceipt run(SkillEffect effect) {
        Program<EffectReceipt> prog = new Program<>(
                "harness-" + UUID.randomUUID(),
                List.of(effect),
                receipts -> receipts.isEmpty() ? null : receipts.get(0));
        return interpreter.runWithContext(prog, ctx);
    }

    /** Run a sequence of effects and return all receipts. */
    public List<EffectReceipt> runAll(List<SkillEffect> effects) {
        Program<List<EffectReceipt>> prog = new Program<>(
                "harness-" + UUID.randomUUID(),
                effects,
                receipts -> receipts);
        return interpreter.runWithContext(prog, ctx);
    }

    public EffectContext context() { return ctx; }
    public SkillStore store() { return store; }
}
