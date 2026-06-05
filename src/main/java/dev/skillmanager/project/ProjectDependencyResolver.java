package dev.skillmanager.project;

import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.BindingSource;
import dev.skillmanager.bindings.ConflictPolicy;
import dev.skillmanager.bindings.DocRepoBinder;
import dev.skillmanager.bindings.HarnessInstantiator;
import dev.skillmanager.bindings.ProjectionKind;
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
import dev.skillmanager.resolve.Resolver;
import dev.skillmanager.resolve.TransitiveFailures;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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
            List<String> bindingIds,
            ProjectChildHomeScaffolder.Result childHome
    ) {}

    public Result resolve(SkillProject project, Options options) throws IOException {
        Options opts = options == null ? Options.defaults() : options;
        SkillProjectRegistration registration = new SkillProjectRegistry(store).register(project);
        SkillProjectLockStore lockStore = new SkillProjectLockStore(store);
        SkillProjectLock previousLock = lockStore.read(project.registryName()).orElse(null);
        InstalledProjectUnits installed = installMissing(project, opts);
        List<SkillProjectLock.ResolvedUnit> resolvedUnits =
                collectResolvedUnits(project, installed.directNames());
        ProjectChildHomeScaffolder.Result childHome =
                new ProjectChildHomeScaffolder(store).scaffold(project, resolvedUnits);
        List<SkillProjectLock.ProjectBinding> projectBindings =
                materializeProjectBindings(project, childHome.layout(), childHome.childStore(),
                        resolvedUnits, previousLock);
        SkillProjectLock lock = new SkillProjectLock(
                project.registryName(),
                project.activeProfile(),
                registration.manifestFile(),
                Instant.now().toString(),
                resolvedUnits,
                projectBindings,
                previousLock == null ? List.of() : previousLock.envs(),
                previousLock == null ? List.of() : previousLock.libs());
        lockStore.write(lock);
        return new Result(
                registration,
                lock,
                installed.installed(),
                projectBindings.stream().map(SkillProjectLock.ProjectBinding::bindingId).toList(),
                childHome);
    }

    private record InstalledProjectUnits(List<String> installed, List<String> directNames) {}

    private InstalledProjectUnits installMissing(SkillProject project, Options options) throws IOException {
        List<String> installed = new ArrayList<>();
        List<String> directNames = new ArrayList<>();
        for (SkillProject.ProjectUnitRef ref : installableRefs(project)) {
            String expectedName = unitName(ref.reference(), project.projectRoot()).orElse(null);
            if (expectedName == null) {
                expectedName = installedNameFromOrigin(ref).orElse(null);
            }
            if (expectedName == null) {
                expectedName = discoverTopLevelName(ref, project.projectRoot());
            }
            if (expectedName != null) {
                directNames.add(expectedName);
            }
            if (expectedName != null && store.containsUnit(expectedName)) {
                validateExpectedKind(ref, expectedName);
                continue;
            }

            InstallSource source = installSource(ref, project.projectRoot());
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
            if (expectedName != null && !store.containsUnit(expectedName)) {
                throw new IOException("project dependency " + ref.alias()
                        + " did not install expected unit " + expectedName);
            }
            validateExpectedKind(ref, expectedName);
        }
        return new InstalledProjectUnits(List.copyOf(installed), List.copyOf(directNames));
    }

    private List<SkillProjectLock.ProjectBinding> materializeProjectBindings(
            SkillProject project,
            dev.skillmanager.bindings.ChildHomeHarnessInstaller.Layout layout,
            SkillStore bindingSourceStore,
            List<SkillProjectLock.ResolvedUnit> resolvedUnits,
            SkillProjectLock previousLock
    ) throws IOException {
        List<Binding> bindings = new ArrayList<>();
        Path targetRoot = project.activeProfile() == null ? project.projectRoot() : layout.targetDir();
        bindings.addAll(planProjectAgentBindings(project, layout, bindingSourceStore, targetRoot, resolvedUnits));
        for (SkillProject.ProjectUnitRef ref : project.docs()) {
            String docName = resolvedUnitName(project, ref, resolvedUnits);
            DocUnit doc = DocRepoParser.load(bindingSourceStore.unitDir(docName, UnitKind.DOC));
            String selectedSourceId = selectedSubElement(ref.reference());
            DocRepoBinder.Plan plan = DocRepoBinder.plan(
                    doc,
                    targetRoot,
                    selectedSourceId,
                    ConflictPolicy.OVERWRITE,
                    BindingSource.PROFILE,
                    src -> project.childHomeId() + ":doc:" + doc.name() + ":" + src.id());
            bindings.addAll(plan.bindings());
        }
        for (SkillProject.ProjectUnitRef ref : project.harnesses()) {
            String harnessName = resolvedUnitName(project, ref, resolvedUnits);
            HarnessUnit harness = HarnessParser.load(bindingSourceStore.unitDir(harnessName, UnitKind.HARNESS));
            HarnessInstantiator.Plan plan = HarnessInstantiator.plan(
                    harness,
                    project.childHomeId() + ":" + harness.name(),
                    layout.claudeHome(),
                    layout.codexHome(),
                    layout.geminiHome(),
                    targetRoot,
                    bindingSourceStore);
            bindings.addAll(plan.bindings());
        }
        reconcileMaterializedBindings(bindings, previousLock, layout, bindingSourceStore);
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

    private List<Binding> planProjectAgentBindings(
            SkillProject project,
            dev.skillmanager.bindings.ChildHomeHarnessInstaller.Layout layout,
            SkillStore bindingSourceStore,
            Path targetRoot,
            List<SkillProjectLock.ResolvedUnit> resolvedUnits
    ) throws IOException {
        List<Binding> bindings = new ArrayList<>();
        for (SkillProjectLock.ResolvedUnit unit : projectAgentResolvedUnits(
                bindingSourceStore, resolvedUnits)) {
            String bindingId = project.childHomeId() + ":unit:" + unit.name();
            Path source = bindingSourceStore.unitDir(unit.name(), unit.kind()).toAbsolutePath().normalize();
            List<dev.skillmanager.bindings.Projection> projections =
                    projectAgentProjections(bindingId, source, layout, unit);
            if (projections.isEmpty()) continue;
            bindings.add(new Binding(
                    bindingId,
                    unit.name(),
                    unit.kind(),
                    null,
                    targetRoot,
                    ConflictPolicy.OVERWRITE,
                    BindingStore.nowIso(),
                    BindingSource.PROFILE,
                    projections));
        }
        return bindings;
    }

    private List<SkillProjectLock.ResolvedUnit> projectAgentResolvedUnits(
            SkillStore bindingSourceStore,
            List<SkillProjectLock.ResolvedUnit> resolvedUnits
    ) throws IOException {
        Map<String, SkillProjectLock.ResolvedUnit> byName = new LinkedHashMap<>();
        for (SkillProjectLock.ResolvedUnit unit : resolvedUnits == null
                ? List.<SkillProjectLock.ResolvedUnit>of()
                : resolvedUnits) {
            byName.put(unit.name(), unit);
        }

        ArrayDeque<String> queue = new ArrayDeque<>();
        for (SkillProjectLock.ResolvedUnit unit : byName.values()) {
            if (unit.direct() && (unit.kind() == UnitKind.SKILL || unit.kind() == UnitKind.PLUGIN)) {
                queue.add(unit.name());
            }
        }

        Map<String, SkillProjectLock.ResolvedUnit> selected = new LinkedHashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String name = queue.removeFirst();
            if (!visited.add(name)) continue;
            SkillProjectLock.ResolvedUnit row = byName.get(name);
            if (row == null) continue;
            if (row.kind() == UnitKind.SKILL || row.kind() == UnitKind.PLUGIN) {
                selected.put(row.name(), row);
            }
            AgentUnit unit = bindingSourceStore.loadUnit(row.name()).orElseThrow(() ->
                    new IOException("project resolved unit is not installed in child store: " + row.name()));
            for (UnitReference child : unit.references()) {
                unitName(child, unit.sourcePath()).ifPresent(childName -> {
                    if (byName.containsKey(childName) && !visited.contains(childName)) {
                        queue.add(childName);
                    }
                });
            }
        }
        return new ArrayList<>(selected.values());
    }

    private static List<dev.skillmanager.bindings.Projection> projectAgentProjections(
            String bindingId,
            Path source,
            dev.skillmanager.bindings.ChildHomeHarnessInstaller.Layout layout,
            SkillProjectLock.ResolvedUnit unit
    ) {
        return switch (unit.kind()) {
            case SKILL -> List.of(
                    new dev.skillmanager.bindings.Projection(
                            bindingId, source,
                            layout.claudeHome().resolve("skills").resolve(unit.name()),
                            ProjectionKind.SYMLINK, null),
                    new dev.skillmanager.bindings.Projection(
                            bindingId, source,
                            layout.codexHome().resolve("skills").resolve(unit.name()),
                            ProjectionKind.SYMLINK, null),
                    new dev.skillmanager.bindings.Projection(
                            bindingId, source,
                            layout.geminiHome().resolve("skills").resolve(unit.name()),
                            ProjectionKind.SYMLINK, null));
            case PLUGIN -> List.of(
                    new dev.skillmanager.bindings.Projection(
                            bindingId, source,
                            layout.claudeHome().resolve("plugins").resolve(unit.name()),
                            ProjectionKind.SYMLINK, null));
            case DOC, HARNESS -> List.of();
        };
    }

    private String resolvedUnitName(
            SkillProject project,
            SkillProject.ProjectUnitRef ref,
            List<SkillProjectLock.ResolvedUnit> resolvedUnits
    ) throws IOException {
        Optional<String> fromCoord = unitName(ref.reference(), project.projectRoot());
        if (fromCoord.isPresent()) return fromCoord.get();

        String expectedOrigin = expectedOrigin(ref);
        List<SkillProjectLock.ResolvedUnit> matches = new ArrayList<>();
        for (SkillProjectLock.ResolvedUnit unit : resolvedUnits == null
                ? List.<SkillProjectLock.ResolvedUnit>of()
                : resolvedUnits) {
            if (unit.kind() != ref.kind()) continue;
            if (sameOrigin(expectedOrigin, unit.source())) matches.add(unit);
        }
        if (matches.size() == 1) return matches.get(0).name();
        throw new IOException("could not determine " + ref.kind().name().toLowerCase()
                + " name for " + ref.source());
    }

    private void reconcileMaterializedBindings(
            List<Binding> desiredBindings,
            SkillProjectLock previousLock,
            dev.skillmanager.bindings.ChildHomeHarnessInstaller.Layout layout,
            SkillStore bindingSourceStore
    ) throws IOException {
        List<SkillEffect> effects = new ArrayList<>();
        Set<String> desiredIds = new LinkedHashSet<>();
        for (Binding b : desiredBindings) desiredIds.add(b.bindingId());

        if (previousLock != null && !previousLock.bindings().isEmpty()) {
            BindingStore bindingStore = new BindingStore(store);
            for (SkillProjectLock.ProjectBinding row : previousLock.bindings()) {
                if (desiredIds.contains(row.bindingId())) continue;
                BindingStore.LocatedBinding located =
                        bindingStore.findById(row.bindingId()).orElse(null);
                if (located != null) {
                    List<dev.skillmanager.bindings.Projection> projections =
                            new ArrayList<>(located.binding().projections());
                    Collections.reverse(projections);
                    for (var p : projections) {
                        effects.add(new SkillEffect.UnmaterializeProjection(p));
                    }
                    effects.add(new SkillEffect.RemoveBinding(located.unitName(), located.binding().bindingId()));
                    continue;
                }
                addMissingLedgerProjectAgentRemoval(effects, row, layout, bindingSourceStore);
            }
        }

        for (Binding b : desiredBindings) {
            for (var p : b.projections()) {
                effects.add(new SkillEffect.MaterializeProjection(p, b.conflictPolicy()));
            }
            effects.add(new SkillEffect.CreateBinding(b));
        }
        if (effects.isEmpty()) return;
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

    private void addMissingLedgerProjectAgentRemoval(
            List<SkillEffect> effects,
            SkillProjectLock.ProjectBinding row,
            dev.skillmanager.bindings.ChildHomeHarnessInstaller.Layout layout,
            SkillStore bindingSourceStore
    ) throws IOException {
        if (!row.bindingId().contains(":unit:")) return;
        if (row.unitKind() != UnitKind.SKILL && row.unitKind() != UnitKind.PLUGIN) return;

        Path source = bindingSourceStore.unitDir(row.unitName(), row.unitKind()).toAbsolutePath().normalize();
        List<dev.skillmanager.bindings.Projection> ownedProjections = new ArrayList<>();
        for (dev.skillmanager.bindings.Projection projection : ProjectRemoveUseCase.projectAgentProjections(
                row.bindingId(), source, layout, row.unitName(), row.unitKind())) {
            if (ProjectRemoveUseCase.isProjectOwnedSymlink(projection.destPath(), source)) {
                ownedProjections.add(projection);
            }
        }
        if (ownedProjections.isEmpty()) return;

        Collections.reverse(ownedProjections);
        for (var projection : ownedProjections) {
            effects.add(new SkillEffect.UnmaterializeProjection(projection));
        }
        effects.add(new SkillEffect.RemoveBinding(row.unitName(), row.bindingId()));
    }

    private List<SkillProjectLock.ResolvedUnit> collectResolvedUnits(
            SkillProject project,
            List<String> directResolvedNames
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
        for (String name : directResolvedNames == null ? List.<String>of() : directResolvedNames) {
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

    private Optional<String> installedNameFromOrigin(SkillProject.ProjectUnitRef ref) throws IOException {
        Coord c = ref.reference().coord();
        if (c instanceof Coord.SubElement s) c = s.unitCoord();
        if (!(c instanceof Coord.DirectGit)) return Optional.empty();

        String expected = expectedOrigin(ref);
        if (expected == null || expected.isBlank()) return Optional.empty();
        UnitStore unitStore = new UnitStore(store);
        for (AgentUnit unit : store.listInstalledUnits().units()) {
            Optional<dev.skillmanager.source.InstalledUnit> record = unitStore.read(unit.name());
            if (record.isEmpty()) continue;
            if (sameOrigin(expected, record.get().origin())) return Optional.of(unit.name());
        }
        return Optional.empty();
    }

    private String discoverTopLevelName(SkillProject.ProjectUnitRef ref, Path baseRoot) throws IOException {
        InstallSource source = installSource(ref, baseRoot);
        var outcome = new Resolver(store).resolveAll(
                List.of(new Resolver.Coord(source.source(), source.version())));
        try {
            if (!outcome.failures().isEmpty()) {
                int code = TransitiveFailures.exitCodeFor(outcome.failures());
                String reason = outcome.failures().get(0).reason();
                throw new IOException("project dependency resolve failed for "
                        + ref.alias() + " (" + ref.source() + "), exit=" + code
                        + ", errors=" + outcome.failures().size()
                        + ": " + reason);
            }
            if (outcome.graph().resolved().isEmpty()) return null;
            var topLevel = outcome.graph().resolved().get(0);
            if (topLevel.unit().kind() != ref.kind()) {
                throw new IOException("project dependency " + ref.alias()
                        + " expected " + ref.kind() + " but resolved " + topLevel.unit().kind());
            }
            return topLevel.name();
        } finally {
            outcome.graph().cleanup();
        }
    }

    private record InstallSource(String source, String version) {}

    private static InstallSource installSource(SkillProject.ProjectUnitRef ref, Path baseRoot) {
        Coord c = ref.reference().coord();
        if (c instanceof Coord.SubElement s) c = s.unitCoord();
        String revision = blankToNull(ref.revision());
        return switch (c) {
            case Coord.Local l -> {
                Path p = Path.of(l.path());
                Path resolved = p.isAbsolute() ? p : baseRoot.resolve(p).normalize();
                yield new InstallSource(resolved.toString(), null);
            }
            case Coord.Kinded k -> new InstallSource(k.name(), firstNonBlank(revision, k.version()));
            case Coord.Bare b -> new InstallSource(b.name(), firstNonBlank(revision, b.version()));
            case Coord.DirectGit g -> new InstallSource("git+" + g.url(), firstNonBlank(revision, g.ref()));
            case Coord.SubElement ignored -> throw new IllegalStateException("handled above");
        };
    }

    private static String expectedOrigin(SkillProject.ProjectUnitRef ref) {
        Coord c = ref.reference().coord();
        if (c instanceof Coord.SubElement s) c = s.unitCoord();
        return switch (c) {
            case Coord.DirectGit g -> g.url();
            case Coord.Local l -> {
                Path p = Path.of(l.path());
                yield p.toAbsolutePath().normalize().toString();
            }
            case Coord.Bare b -> b.name();
            case Coord.Kinded k -> k.name();
            case Coord.SubElement ignored -> null;
        };
    }

    private static boolean sameOrigin(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) return false;
        return normalizeOrigin(expected).equals(normalizeOrigin(actual));
    }

    private static String normalizeOrigin(String origin) {
        String out = origin.trim();
        if (out.startsWith("git+")) out = out.substring("git+".length());
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        if (out.endsWith(".git")) out = out.substring(0, out.length() - ".git".length());
        return out;
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

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
