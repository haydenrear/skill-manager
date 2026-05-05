package dev.skillmanager._lib.harness;

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
 */
public final class TestHarness {

    private final SkillStore store;
    private final EffectContext ctx;
    private final LiveInterpreter interpreter;

    private TestHarness(SkillStore store) {
        this.store = store;
        this.ctx = new EffectContext(store, null);
        this.interpreter = new LiveInterpreter(store);
    }

    public static TestHarness create() throws IOException {
        java.nio.file.Path tmp = Files.createTempDirectory("skill-handler-test-");
        SkillStore store = new SkillStore(tmp);
        store.init();
        return new TestHarness(store);
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
