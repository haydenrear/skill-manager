package dev.skillmanager.project;

import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingSource;
import dev.skillmanager.bindings.ConflictPolicy;
import dev.skillmanager.bindings.DocRepoBinder;
import dev.skillmanager.bindings.HarnessInstantiator;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.Coord;
import dev.skillmanager.model.DocRepoParser;
import dev.skillmanager.model.DocUnit;
import dev.skillmanager.model.HarnessParser;
import dev.skillmanager.model.HarnessUnit;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.model.UnitReference;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Realizes one project manifest into store-installed units, project-scoped
 * doc/harness bindings, and a project lock.
 */
public final class ProjectDependencyResolver {

    private final SkillStore store;
    private final GatewayConfig gateway;

    public ProjectDependencyResolver(SkillStore store, GatewayConfig gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    public record Options(boolean yes, boolean withGateway) {
        public static Options defaults() { return new Options(true, true); }
    }

    public record Result(
            SkillProjectRegistration registration,
            SkillProjectLock lock,
            List<String> installed,
            List<String> bindingIds
    ) {}

    public Result resolve(SkillProject project, Options options) throws IOException {
        Options opts = options == null ? Options.defaults() : options;
        SkillProjectRegistration registration = new SkillProjectRegistry(store).register(project);
        List<String> installed = installMissing(project, opts);
        List<SkillProjectLock.ProjectBinding> projectBindings = materializeProjectBindings(project);
        List<SkillProjectLock.ResolvedUnit> resolvedUnits = collectResolvedUnits(project, installed);
        SkillProjectLock lock = new SkillProjectLock(
                project.name(),
                registration.manifestFile(),
                Instant.now().toString(),
                resolvedUnits,
                projectBindings);
        new SkillProjectLockStore(store).write(lock);
        return new Result(
                registration,
                lock,
                installed,
                projectBindings.stream().map(SkillProjectLock.ProjectBinding::bindingId).toList());
    }

    private List<String> installMissing(SkillProject project, Options options) throws IOException {
        List<String> installed = new ArrayList<>();
        for (SkillProject.ProjectUnitRef ref : installableRefs(project)) {
            String expectedName = unitName(ref.reference(), project.projectRoot()).orElse(null);
            if (expectedName != null && store.containsUnit(expectedName)) {
                validateExpectedKind(ref, expectedName);
                continue;
            }

            InstallSource source = installSource(ref.reference(), project.projectRoot());
            var program = InstallUseCase.buildProgram(
                    store,
                    gateway,
                    null,
                    source.source(),
                    source.version(),
                    options.yes(),
                    false,
                    options.withGateway(),
                    false);
            Executor.Outcome<InstallUseCase.Report> outcome = new Executor(store, gateway).runStaged(program);
            InstallUseCase.Report report = outcome.result();
            if (report.exitCode() != 0 || report.errorCount() != 0) {
                throw new IOException("project dependency resolve failed for "
                        + ref.alias() + " (" + ref.source() + "), exit=" + report.exitCode()
                        + ", errors=" + report.errorCount());
            }
            installed.addAll(report.committed());
            expectedName = expectedName == null ? unitName(ref.reference(), project.projectRoot()).orElse(null) : expectedName;
            if (expectedName != null && !store.containsUnit(expectedName)) {
                throw new IOException("project dependency " + ref.alias()
                        + " did not install expected unit " + expectedName);
            }
            validateExpectedKind(ref, expectedName);
        }
        return installed;
    }

    private List<SkillProjectLock.ProjectBinding> materializeProjectBindings(SkillProject project) throws IOException {
        List<Binding> bindings = new ArrayList<>();
        for (SkillProject.ProjectUnitRef ref : project.docs()) {
            String docName = unitName(ref.reference(), project.projectRoot()).orElseThrow(() ->
                    new IOException("could not determine doc-repo name for " + ref.source()));
            DocUnit doc = DocRepoParser.load(store.unitDir(docName, UnitKind.DOC));
            String selectedSourceId = selectedSubElement(ref.reference());
            DocRepoBinder.Plan plan = DocRepoBinder.plan(
                    doc,
                    project.projectRoot(),
                    selectedSourceId,
                    ConflictPolicy.OVERWRITE,
                    BindingSource.PROFILE,
                    src -> "project:" + project.name() + ":doc:" + doc.name() + ":" + src.id());
            bindings.addAll(plan.bindings());
        }
        for (SkillProject.ProjectUnitRef ref : project.harnesses()) {
            String harnessName = unitName(ref.reference(), project.projectRoot()).orElseThrow(() ->
                    new IOException("could not determine harness name for " + ref.source()));
            HarnessUnit harness = HarnessParser.load(store.unitDir(harnessName, UnitKind.HARNESS));
            HarnessInstantiator.Plan plan = HarnessInstantiator.plan(
                    harness,
                    "project:" + project.name() + ":" + harness.name(),
                    project.projectRoot().resolve(".claude"),
                    project.projectRoot().resolve(".codex"),
                    project.projectRoot().resolve(".gemini"),
                    project.projectRoot(),
                    store);
            bindings.addAll(plan.bindings());
        }
        if (!bindings.isEmpty()) materialize(bindings);
        List<SkillProjectLock.ProjectBinding> rows = new ArrayList<>();
        for (Binding b : bindings) {
            rows.add(new SkillProjectLock.ProjectBinding(
                    b.bindingId(),
                    b.unitName(),
                    b.unitKind(),
                    b.source(),
                    b.targetRoot().toString()));
        }
        return rows;
    }

    private void materialize(List<Binding> bindings) throws IOException {
        List<SkillEffect> effects = new ArrayList<>();
        for (Binding b : bindings) {
            for (var p : b.projections()) {
                effects.add(new SkillEffect.MaterializeProjection(p, b.conflictPolicy()));
            }
            effects.add(new SkillEffect.CreateBinding(b));
        }
        Program<Integer> program = new Program<>(
                "project-bind-" + UUID.randomUUID(),
                effects,
                receipts -> {
                    int failures = 0;
                    for (var r : receipts) {
                        if (r.status() == dev.skillmanager.effects.EffectStatus.FAILED
                                || r.status() == dev.skillmanager.effects.EffectStatus.PARTIAL) failures++;
                    }
                    return failures;
                });
        Executor.Outcome<Integer> outcome = new Executor(store, gateway).run(program);
        if (outcome.result() != 0) {
            throw new IOException("project binding materialization failed with "
                    + outcome.result() + " failed effect(s)");
        }
    }

    private List<SkillProjectLock.ResolvedUnit> collectResolvedUnits(
            SkillProject project,
            List<String> newlyInstalled
    ) throws IOException {
        Map<String, SkillProjectLock.ResolvedUnit> rows = new LinkedHashMap<>();
        Set<String> directNames = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (SkillProject.ProjectUnitRef ref : installableRefs(project)) {
            Optional<String> name = unitName(ref.reference(), project.projectRoot());
            if (name.isEmpty()) continue;
            directNames.add(name.get());
            queue.add(name.get());
        }
        for (String name : newlyInstalled == null ? List.<String>of() : newlyInstalled) {
            if (name == null || name.isBlank()) continue;
            directNames.add(name);
            queue.add(name);
        }
        UnitStore unitStore = new UnitStore(store);
        while (!queue.isEmpty()) {
            String name = queue.removeFirst();
            if (rows.containsKey(name)) continue;
            AgentUnit unit = store.loadUnit(name).orElseThrow(() ->
                    new IOException("project resolved unit is not installed: " + name));
            String source = unitStore.read(name)
                    .map(u -> u.origin() == null ? null : u.origin())
                    .orElse(null);
            rows.put(name, new SkillProjectLock.ResolvedUnit(
                    unit.name(),
                    unit.kind(),
                    unit.version(),
                    source,
                    directNames.contains(name)));
            for (UnitReference child : unit.references()) {
                unitName(child, unit.sourcePath()).ifPresent(childName -> {
                    if (!rows.containsKey(childName)) queue.add(childName);
                });
            }
        }
        return new ArrayList<>(rows.values());
    }

    private List<SkillProject.ProjectUnitRef> installableRefs(SkillProject project) {
        List<SkillProject.ProjectUnitRef> refs = new ArrayList<>();
        for (var r : project.skills()) if (r.install()) refs.add(r);
        for (var r : project.plugins()) if (r.install()) refs.add(r);
        for (var r : project.docs()) if (r.install()) refs.add(r);
        for (var r : project.harnesses()) if (r.install()) refs.add(r);
        return refs;
    }

    private void validateExpectedKind(SkillProject.ProjectUnitRef ref, String expectedName) throws IOException {
        if (expectedName == null) return;
        Optional<AgentUnit> installed = store.loadUnit(expectedName);
        if (installed.isEmpty()) return;
        if (installed.get().kind() != ref.kind()) {
            throw new IOException("project dependency " + ref.alias()
                    + " expected " + ref.kind() + " but installed " + installed.get().kind());
        }
    }

    private record InstallSource(String source, String version) {}

    private static InstallSource installSource(UnitReference ref, Path baseRoot) {
        Coord c = ref.coord();
        if (c instanceof Coord.SubElement s) c = s.unitCoord();
        return switch (c) {
            case Coord.Local l -> {
                Path p = Path.of(l.path());
                Path resolved = p.isAbsolute() ? p : baseRoot.resolve(p).normalize();
                yield new InstallSource(resolved.toString(), null);
            }
            case Coord.Kinded k -> new InstallSource(k.name(), k.version());
            case Coord.Bare b -> new InstallSource(b.name(), b.version());
            case Coord.DirectGit g -> new InstallSource(g.raw(), g.ref());
            case Coord.SubElement ignored -> throw new IllegalStateException("handled above");
        };
    }

    private static Optional<String> unitName(UnitReference ref, Path baseRoot) {
        Coord c = ref.coord();
        if (c instanceof Coord.SubElement s) c = s.unitCoord();
        return switch (c) {
            case Coord.Bare b -> Optional.ofNullable(blankToNull(b.name()));
            case Coord.Kinded k -> Optional.ofNullable(blankToNull(k.name()));
            case Coord.Local l -> unitNameFromLocal(l, baseRoot);
            case Coord.DirectGit ignored -> Optional.empty();
            case Coord.SubElement ignored -> Optional.empty();
        };
    }

    private static Optional<String> unitNameFromLocal(Coord.Local local, Path baseRoot) {
        Path p = Path.of(local.path());
        Path dir = p.isAbsolute() ? p : baseRoot.resolve(p).normalize();
        try {
            if (dev.skillmanager.model.PluginParser.looksLikePlugin(dir)) {
                return Optional.of(dev.skillmanager.model.PluginParser.load(dir).name());
            }
            if (dev.skillmanager.model.HarnessParser.looksLikeHarness(dir)) {
                return Optional.of(dev.skillmanager.model.HarnessParser.load(dir).name());
            }
            if (dev.skillmanager.model.DocRepoParser.looksLikeDocRepo(dir)) {
                return Optional.of(dev.skillmanager.model.DocRepoParser.load(dir).name());
            }
            Path skillMd = dir.resolve(dev.skillmanager.model.SkillParser.SKILL_FILENAME);
            if (java.nio.file.Files.isRegularFile(skillMd)) {
                return Optional.of(dev.skillmanager.model.SkillParser.load(dir).name());
            }
        } catch (IOException ignored) {}
        return Optional.empty();
    }

    private static String selectedSubElement(UnitReference ref) {
        return ref.coord() instanceof Coord.SubElement s ? s.elementName() : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
