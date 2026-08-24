package dev.skillmanager.project;

import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * <b>Does this home hold what its own manifest declares?</b> — DEF-096.
 *
 * <h2>The measurement</h2>
 *
 * <p>This repository's {@code skill-project.toml} declares {@code [plugins.skt]}
 * and {@code [skills.skill-manager]}. Read on 2026-08-24, the project home held
 * <b>neither</b>, and {@code projects/} was <b>empty</b> — the manifest had
 * never been realized. Every ticket worktree in the epic is a clone of that
 * home, so twenty-two ticket agents worked without the two units that document
 * the thing they were working on, and nothing anywhere said so. HIS-20 measured
 * the cost: <b>9 of 11</b> improved progressive-disclosure cells reach PASS from
 * {@code project resolve} alone, with zero documentation change.
 *
 * <h2>What this is, and what it deliberately is not</h2>
 *
 * <p>It is a <em>reader</em>. It installs nothing, refuses nothing, and returns
 * no exit code of its own. The acceptance item is "either holds them or
 * <b>says plainly</b> that it does not", and the second clause is the one that
 * was missing — a home that is short two units is not a fault, it is a fact,
 * and the fault was that the fact was unobservable.
 *
 * <p>It is deliberately <em>one-directional</em>: declared-but-absent only.
 * The same two homes hold {@code deploy-helm}, {@code test-graph} and
 * {@code tracing-observability}, none of which the manifest declares, and one
 * of which the manifest explicitly explains it will never declare. Those are
 * transitives and operator installs; reporting them would bury the two rows
 * that matter under noise that is correct by design.
 *
 * <h2>Where the manifest is looked for, and why that is enough</h2>
 *
 * <p>Beside the home: {@code <home>/../skill-project.toml}. That is the
 * convention every project home in this system is built on —
 * {@code <repoRoot>/.skill-manager} beside {@code <repoRoot>/skill-project.toml}
 * — and it is the only relation available, because a home does not record its
 * own project root ({@code home.provenance.json} records the clone chain, and
 * {@code projects/<name>/registration.toml} is written only by
 * {@code project register}, which on the home that motivated this had never
 * run). A root home has no manifest beside it and gets an empty answer, which
 * is the correct one.
 */
public final class ProjectManifestRealization {

    /** One unit a manifest declares, as the manifest spells it. */
    public record Declared(String alias, UnitKind kind, String source, String resolvedName) {
        /** The name to look for in a home: the resolved one when known, else the alias. */
        public String lookupName() {
            return resolvedName == null || resolvedName.isBlank() ? alias : resolvedName;
        }

        public String label() {
            return kind.name().toLowerCase() + ":" + lookupName();
        }
    }

    /**
     * What a home's manifest declares that the home does not hold.
     *
     * @param manifest the manifest read, or null when there is none beside the home
     * @param declared every installable ref the manifest names
     * @param missing  the subset the home does not hold
     */
    public record Shortfall(Path manifest, List<Declared> declared, List<Declared> missing) {
        public Shortfall {
            declared = declared == null ? List.of() : List.copyOf(declared);
            missing = missing == null ? List.of() : List.copyOf(missing);
        }

        public static Shortfall none() { return new Shortfall(null, List.of(), List.of()); }

        /** True when there is nothing to say — including when there is no manifest. */
        public boolean clean() { return missing.isEmpty(); }

        /** True when a manifest was actually found and read. */
        public boolean hasManifest() { return manifest != null; }

        /**
         * The sentence a command prints. States the denominator as well as the
         * numerator: "2 of 4 declared units are not installed here" is
         * actionable where "2 units missing" is a number without a scale.
         */
        public String summary() {
            return missing.size() + " of " + declared.size()
                    + " unit(s) declared by " + manifest + " are not installed in this home";
        }

        /** One line per missing unit, naming what would install it. */
        public List<String> render() {
            List<String> out = new ArrayList<>();
            if (clean()) return out;
            out.add(summary());
            for (Declared d : missing) {
                out.add("  declared but absent: " + d.label() + "  (source: " + d.source() + ")");
            }
            out.add("  remedy: skill-manager project resolve --project-dir "
                    + (manifest == null ? "<project>" : manifest.getParent()));
            return out;
        }
    }

    private ProjectManifestRealization() {}

    /** The project manifest sitting beside this home, if there is one. */
    public static Optional<Path> manifestBeside(Path homeRoot) {
        if (homeRoot == null) return Optional.empty();
        Path parent = homeRoot.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent)) return Optional.empty();
        return Optional.ofNullable(SkillProjectParser.findManifest(parent));
    }

    /**
     * Compare the manifest beside {@code home} against what {@code home} holds.
     *
     * <p>Never throws for the ordinary shapes: no manifest, or an unreadable /
     * malformed one, both answer {@link Shortfall#none()}. This is a diagnostic
     * bolted onto commands whose job is something else, and a diagnostic that
     * can fail its host is worse than no diagnostic. A malformed manifest is
     * already reported, loudly and with position information, by
     * {@code project register}.
     */
    public static Shortfall inspect(SkillStore home) {
        if (home == null) return Shortfall.none();
        Optional<Path> manifest = manifestBeside(home.root());
        if (manifest.isEmpty()) return Shortfall.none();
        SkillProject project;
        try {
            project = SkillProjectParser.loadManifest(manifest.get());
        } catch (Exception unreadable) {
            return Shortfall.none();
        }
        List<Declared> declared = new ArrayList<>();
        List<Declared> missing = new ArrayList<>();
        for (SkillProject.ProjectUnitRef ref : installableRefs(project)) {
            String resolved = null;
            try {
                resolved = ProjectDependencyResolver
                        .installedUnitName(ref.reference(), project.projectRoot(), home)
                        .orElse(null);
            } catch (IOException ignored) {
                // A store we cannot read cannot be reported on. Fall through to
                // the alias, which is what the manifest itself promises the
                // installed name will be.
            }
            Declared d = new Declared(ref.alias(), ref.kind(), ref.source(), resolved);
            declared.add(d);
            if (!holds(home, d)) missing.add(d);
        }
        return new Shortfall(manifest.get(), declared, missing);
    }

    /**
     * Every unit name the home's own manifest declares, resolved as far as it
     * can be offline. Used by {@code home close-out} to tell "the destination
     * lacks this and cannot get it" from "the destination lacks this and its
     * own manifest declares it" (DEF-101).
     */
    public static Set<String> declaredNames(SkillStore home) {
        Set<String> names = new LinkedHashSet<>();
        Shortfall shortfall = inspect(home);
        for (Declared d : shortfall.declared()) {
            names.add(d.alias());
            if (d.resolvedName() != null && !d.resolvedName().isBlank()) names.add(d.resolvedName());
        }
        return names;
    }

    private static boolean holds(SkillStore home, Declared d) {
        try {
            if (home.containsUnit(d.lookupName())) return true;
            return !d.lookupName().equals(d.alias()) && home.containsUnit(d.alias());
        } catch (Exception unreadable) {
            return true;   // cannot prove absence; never invent a finding
        }
    }

    private static List<SkillProject.ProjectUnitRef> installableRefs(SkillProject project) {
        List<SkillProject.ProjectUnitRef> refs = new ArrayList<>();
        for (var r : project.skills()) if (r.install()) refs.add(r);
        for (var r : project.plugins()) if (r.install()) refs.add(r);
        for (var r : project.docs()) if (r.install()) refs.add(r);
        for (var r : project.harnesses()) if (r.install()) refs.add(r);
        return refs;
    }
}
