package dev.skillmanager.effects;

import dev.skillmanager.model.Skill;
import dev.skillmanager.model.UnitReference;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.resolve.Resolver;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handlers for the {@code BuildResolveGraphFrom*} effects. Each
 * scenario-specific effect does its own IO-bearing discovery
 * (parsing CLI args, walking the install root, walking live
 * skill_references) to build a coord list, then funnels through
 * {@link #runResolver} for the actual resolver call + fact emission +
 * ctx-slot write.
 *
 * <p>Per-scenario fact stream:
 * <ul>
 *   <li><b>install</b> — no discovery facts; the one coord is implied
 *       by the source string. Resolver runs, emits one
 *       {@link ContextFact.GraphResolved} + one
 *       {@link ContextFact.TransitiveFailed} per failure.</li>
 *   <li><b>onboard</b> — one {@link ContextFact.BundledSkillFound},
 *       {@link ContextFact.BundledSkillFromGithub},
 *       {@link ContextFact.BundledSkillAlreadyInstalled}, or
 *       {@link ContextFact.BundledSkillMissing} per bundled spec,
 *       then resolver facts.</li>
 *   <li><b>sync</b> — no per-ref discovery facts (the ref set is
 *       already implied by the live unit list); resolver facts + a
 *       {@link EffectContext#addError} on each parent that declared
 *       a failing coord (and {@link EffectContext#clearError} on
 *       parents whose refs all resolved this pass).</li>
 * </ul>
 */
final class ResolveGraphHandlers {

    private ResolveGraphHandlers() {}

    // ----------------------------------------------------------------
    // install: source/version → one Coord → resolve

    static EffectReceipt buildFromSource(
            SkillEffect.BuildResolveGraphFromSource e, EffectContext ctx) throws IOException {
        // Install path's fail-fast semantics: ANY resolve failure
        // (top-level OR transitive) halts the program. The pre-Program
        // InstallCommand code did the same — bail with a typed exit
        // code derived from the FIRST failure's cause.
        Resolver.Coord coord = new Resolver.Coord(e.source(), e.version());
        return runResolverHaltOnFailure(e, ctx, List.of(coord), List.of());
    }

    // ----------------------------------------------------------------
    // onboard: walk bundled-skill specs against installRoot (or fall back
    // to each spec's github coord), emit a per-spec decision fact, skip
    // already-installed, resolve the rest.

    static EffectReceipt buildFromBundledSkills(
            SkillEffect.BuildResolveGraphFromBundledSkills e, EffectContext ctx) throws IOException {
        SkillStore store = ctx.store();
        List<ContextFact> discoveryFacts = new ArrayList<>();
        List<Resolver.Coord> toResolve = new ArrayList<>();
        // Accumulate per-spec failures (e.g. local install-root mode but
        // the on-disk dir is missing) instead of bailing on the first
        // one — same shape as the resolver itself, so the user sees
        // EVERY discovery problem in one run rather than fixing them
        // sequentially.
        int missingCount = 0;
        for (var spec : e.bundledSkills()) {
            String publishedName;
            String coord;
            if (e.installRoot() != null) {
                Path skillDir = e.installRoot().resolve(spec.dirName());
                if (!Files.isDirectory(skillDir)
                        || !Files.isRegularFile(skillDir.resolve("SKILL.md"))) {
                    discoveryFacts.add(new ContextFact.BundledSkillMissing(
                            spec.publishedName(), skillDir.toString()));
                    missingCount++;
                    continue;
                }
                publishedName = readSkillName(skillDir, spec.publishedName());
                coord = skillDir.toString();
                discoveryFacts.add(new ContextFact.BundledSkillFound(publishedName, coord));
            } else {
                publishedName = spec.publishedName();
                coord = spec.githubCoord();
                discoveryFacts.add(new ContextFact.BundledSkillFromGithub(publishedName, coord));
            }
            if (store.contains(publishedName)) {
                discoveryFacts.add(new ContextFact.BundledSkillAlreadyInstalled(
                        publishedName, store.skillDir(publishedName).toString()));
                continue;
            }
            toResolve.add(new Resolver.Coord(coord, null));
        }
        // Onboard halts on any resolve failure — pre-Program OnboardCommand
        // bailed with a typed exit code from TransitiveFailures.exitCodeFor
        // on any failure. Use the halt-on-failure variant to preserve that.
        EffectReceipt resolverReceipt = runResolverHaltOnFailure(e, ctx, toResolve, discoveryFacts);
        // Fold missing-spec count into the receipt status. If any
        // bundled spec was missing, downgrade ok→partial; if EVERY
        // spec was missing (no coords got as far as the resolver),
        // mark failed. The discovery facts are already in the receipt
        // (we passed them in as precedingFacts).
        if (missingCount == 0) return resolverReceipt;
        String missingMsg = missingCount + " bundled spec(s) missing on disk";
        if (toResolve.isEmpty() && resolverReceipt.status() == EffectStatus.OK) {
            return EffectReceipt.failed(e, resolverReceipt.facts(), missingMsg);
        }
        if (resolverReceipt.status() == EffectStatus.FAILED) {
            return EffectReceipt.failed(e, resolverReceipt.facts(),
                    resolverReceipt.errorMessage() + "; " + missingMsg);
        }
        return EffectReceipt.partial(e, resolverReceipt.facts(),
                (resolverReceipt.errorMessage() == null || resolverReceipt.errorMessage().isBlank()
                        ? missingMsg
                        : resolverReceipt.errorMessage() + "; " + missingMsg));
    }

    // ----------------------------------------------------------------
    // sync: walk live skills' references, build the unmet-coord set,
    // resolve, attribute failures back to parents in the store.

    static EffectReceipt buildFromUnmetReferences(
            SkillEffect.BuildResolveGraphFromUnmetReferences e, EffectContext ctx) throws IOException {
        SkillStore store = ctx.store();
        // Track which parent unit declared each unmet coord so we can
        // attribute resolver failures back to them (and clear stale
        // TRANSITIVE_RESOLVE_FAILED errors on parents whose refs now
        // all resolve).
        Map<String, Set<String>> coordToParents = new LinkedHashMap<>();
        Set<String> seenCoords = new LinkedHashSet<>();
        Set<String> parentsWithRefs = new LinkedHashSet<>();
        List<Resolver.Coord> unmet = new ArrayList<>();
        for (Skill s : e.liveSkills()) {
            for (UnitReference ref : s.skillReferences()) {
                String coord = referenceToCoord(ref, store, s.name());
                String refName = ref.name() != null ? ref.name() : guessName(coord);
                if (refName == null || refName.isBlank()) continue;
                parentsWithRefs.add(s.name());
                if (store.contains(refName)) continue;
                coordToParents
                        .computeIfAbsent(coord, k -> new LinkedHashSet<>())
                        .add(s.name());
                if (!seenCoords.add(refName)) continue;
                unmet.add(new Resolver.Coord(coord, ref.version()));
            }
        }
        if (unmet.isEmpty()) {
            // Everything in scope is already in the store — clear any
            // lingering TRANSITIVE_RESOLVE_FAILED on the parents.
            // Self-clearing matches REGISTRY_UNAVAILABLE etc.
            for (String parent : parentsWithRefs) {
                try { ctx.clearError(parent, InstalledUnit.ErrorKind.TRANSITIVE_RESOLVE_FAILED); }
                catch (IOException ignored) {}
            }
            ctx.setResolvedGraph(new ResolvedGraph());
            return EffectReceipt.ok(e, new ContextFact.GraphResolved(0, 0));
        }

        Resolver.ResolveOutcome outcome = new Resolver(store).resolveAll(unmet);
        ctx.setResolvedGraph(outcome.graph());

        List<ContextFact> facts = new ArrayList<>();
        facts.add(new ContextFact.GraphResolved(
                outcome.graph().resolved().size(), outcome.failures().size()));
        Set<String> parentsWithFailures = new LinkedHashSet<>();
        for (Resolver.ResolveFailure f : outcome.failures()) {
            facts.add(new ContextFact.TransitiveFailed(
                    f.source(), f.requestedBy(), f.reason()));
            for (String parent : coordToParents.getOrDefault(f.source(), Set.of())) {
                if (!store.contains(parent)) continue;
                try {
                    ctx.addError(parent,
                            InstalledUnit.ErrorKind.TRANSITIVE_RESOLVE_FAILED,
                            "could not resolve " + f.source() + ": " + f.reason());
                    parentsWithFailures.add(parent);
                } catch (IOException ignored) {
                    // addError persistence failure doesn't crash the
                    // sync — the TransitiveFailed fact above is still
                    // the user-visible signal.
                    // Log.error("Found an IO error");
                }
            }
        }
        // Parents whose refs ALL succeeded this pass: clear any
        // lingering TRANSITIVE_RESOLVE_FAILED error.
        for (String parent : parentsWithRefs) {
            if (parentsWithFailures.contains(parent)) continue;
            try { ctx.clearError(parent, InstalledUnit.ErrorKind.TRANSITIVE_RESOLVE_FAILED); }
            catch (IOException ignored) {}
        }
        if (outcome.failures().isEmpty()) return EffectReceipt.ok(e, facts);
        if (outcome.graph().resolved().isEmpty()) {
            return EffectReceipt.failed(e, facts, outcome.failures().size() + " unmet ref(s) failed to resolve");
        }
        return EffectReceipt.partial(e, facts,
                outcome.failures().size() + " of " + unmet.size() + " unmet ref(s) failed to resolve");
    }

    // ----------------------------------------------------------------
    // Shared resolver runner — used by install + onboard. Sync needs
    // parent attribution so it builds its own list of facts inline.

    /**
     * Halt-on-any-failure variant: install + onboard's pre-Program
     * shape bailed with a typed exit code from the FIRST failure's
     * cause whenever the resolver returned a non-empty failures list
     * (regardless of how much of the graph still resolved). This
     * preserves that — a transitive failure under install means the
     * top-level can't proceed cleanly, so we halt and surface the
     * typed exit code via {@link ContextFact.HaltWithExitCode} for
     * the decoder to pick up.
     */
    private static EffectReceipt runResolverHaltOnFailure(
            SkillEffect effect, EffectContext ctx,
            List<Resolver.Coord> coords, List<ContextFact> precedingFacts) throws IOException {
        if (coords.isEmpty()) {
            ctx.setResolvedGraph(new ResolvedGraph());
            List<ContextFact> facts = new ArrayList<>(precedingFacts);
            facts.add(new ContextFact.GraphResolved(0, 0));
            return EffectReceipt.ok(effect, facts);
        }
        Resolver.ResolveOutcome outcome = new Resolver(ctx.store()).resolveAll(coords);
        ctx.setResolvedGraph(outcome.graph());
        List<ContextFact> facts = new ArrayList<>(precedingFacts);
        facts.add(new ContextFact.GraphResolved(
                outcome.graph().resolved().size(), outcome.failures().size()));
        for (Resolver.ResolveFailure f : outcome.failures()) {
            facts.add(new ContextFact.TransitiveFailed(
                    f.source(), f.requestedBy(), f.reason()));
        }
        if (outcome.failures().isEmpty()) return EffectReceipt.ok(effect, facts);
        // The renderer already prints a one-line warn per failure from
        // the TransitiveFailed facts above; the typed exit code via
        // {@code HaltWithExitCode} surfaces the failure kind for shell
        // wrappers and the report decoder. No inline banner here — the
        // CLI top-level handler keeps the verbose actionable banner for
        // exceptions that escape uncaught.
        int code = dev.skillmanager.resolve.TransitiveFailures.exitCodeFor(outcome.failures());
        String msg = outcome.failures().size() + " coord(s) failed to resolve";
        facts.add(new ContextFact.HaltWithExitCode(code, msg));
        // FAILED status + HALT continuation. The status drives the
        // decoder's errorCount + commitFailed checks; the continuation
        // tells the interpreter to skip the rest of the program. The
        // effect's continuationOnFail() / continuationOnPartial() also
        // declare HALT statically, so this is consistent with the
        // declared default.
        return EffectReceipt.failedAndHalt(effect, facts, msg);
    }

    // ----------------------------------------------------------------
    // helpers (private; ported from prior pre-Program implementations)

    private static String readSkillName(Path skillDir, String fallback) {
        Path toml = skillDir.resolve("skill-manager.toml");
        if (!Files.isRegularFile(toml)) return fallback;
        try {
            boolean inSkillTable = false;
            for (String raw : Files.readAllLines(toml)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    inSkillTable = line.equals("[skill]");
                    continue;
                }
                if (!inSkillTable) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                if (!"name".equals(key)) continue;
                String value = line.substring(eq + 1).trim();
                if (value.length() >= 2
                        && (value.startsWith("\"") && value.endsWith("\"")
                            || value.startsWith("'") && value.endsWith("'"))) {
                    return value.substring(1, value.length() - 1);
                }
                return value;
            }
        } catch (IOException ignored) {}
        return fallback;
    }

    private static String referenceToCoord(UnitReference ref, SkillStore store, String parentSkillName) {
        if (ref.isLocal()) {
            Path rel = Path.of(ref.path());
            if (rel.isAbsolute()) return rel.toString();
            return store.skillDir(parentSkillName).resolve(rel).normalize().toString();
        }
        return ref.version() != null && !ref.version().isBlank()
                ? ref.name() + "@" + ref.version()
                : ref.name();
    }

    private static String guessName(String coord) {
        if (coord == null) return null;
        String normalized = coord;
        int at = normalized.indexOf('@');
        if (at >= 0) normalized = normalized.substring(0, at);
        if (normalized.startsWith("file:")) normalized = normalized.substring(5);
        if (normalized.startsWith("github:")) {
            int slash = normalized.lastIndexOf('/');
            return slash >= 0 ? normalized.substring(slash + 1) : null;
        }
        if (normalized.endsWith(".git")) normalized = normalized.substring(0, normalized.length() - 4);
        int slash = normalized.lastIndexOf('/');
        String tail = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return tail.isBlank() ? null : tail;
    }
}
