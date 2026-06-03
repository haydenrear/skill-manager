package dev.skillmanager.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parsed {@code skill-project.toml} / {@code skill-manager-project.toml}
 * manifest. This is project intent only: registration records these
 * declarations without resolving, installing, or materializing them.
 */
public record SkillProject(
        String name,
        String version,
        String description,
        Path projectRoot,
        Path manifestPath,
        List<ProjectUnitRef> skills,
        List<ProjectUnitRef> plugins,
        List<ProjectUnitRef> harnesses,
        List<ProjectUnitRef> docs,
        List<CliDependency> cliDependencies,
        List<McpDependency> mcpDependencies,
        List<ProjectEnv> envs,
        List<ProjectLib> libs,
        List<ProjectProfile> profiles,
        String activeProfile
) {
    public SkillProject {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("project name must not be blank");
        }
        projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        manifestPath = manifestPath == null ? null : manifestPath.toAbsolutePath().normalize();
        skills = skills == null ? List.of() : List.copyOf(skills);
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
        harnesses = harnesses == null ? List.of() : List.copyOf(harnesses);
        docs = docs == null ? List.of() : List.copyOf(docs);
        cliDependencies = cliDependencies == null ? List.of() : List.copyOf(cliDependencies);
        mcpDependencies = mcpDependencies == null ? List.of() : List.copyOf(mcpDependencies);
        envs = envs == null ? List.of() : List.copyOf(envs);
        libs = libs == null ? List.of() : List.copyOf(libs);
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        activeProfile = activeProfile == null || activeProfile.isBlank() ? null : activeProfile;
    }

    public SkillProject(
            String name,
            String version,
            String description,
            Path projectRoot,
            Path manifestPath,
            List<ProjectUnitRef> skills,
            List<ProjectUnitRef> plugins,
            List<ProjectUnitRef> harnesses,
            List<ProjectUnitRef> docs,
            List<CliDependency> cliDependencies,
            List<McpDependency> mcpDependencies,
            List<ProjectEnv> envs,
            List<ProjectLib> libs
    ) {
        this(name, version, description, projectRoot, manifestPath, skills, plugins, harnesses, docs,
                cliDependencies, mcpDependencies, envs, libs, List.of(), null);
    }

    public String registryName() {
        if (activeProfile == null) return name;
        return name + "--" + safeProfileSegment(activeProfile);
    }

    public String childHomeId() {
        if (activeProfile == null) return "project:" + name;
        return "project:" + name + ":profile:" + activeProfile;
    }

    public Optional<ProjectProfile> profile(String profileName) {
        if (profileName == null || profileName.isBlank()) return Optional.empty();
        return profiles.stream().filter(p -> p.name().equals(profileName)).findFirst();
    }

    public SkillProject withProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return new SkillProject(name, version, description, projectRoot, manifestPath,
                    skills, plugins, harnesses, docs, cliDependencies, mcpDependencies,
                    envs, libs, profiles, null);
        }
        ProjectProfile selected = profile(profileName).orElseThrow(() ->
                new IllegalArgumentException("project profile not declared: " + profileName));
        EffectiveProfile effective = effectiveProfile(selected, new LinkedHashSet<>());
        return new SkillProject(name, version, description, projectRoot, manifestPath,
                selectUnits(skills, effective.skills(), UnitKind.SKILL, effective.inheritsDefault()),
                selectUnits(plugins, effective.plugins(), UnitKind.PLUGIN, effective.inheritsDefault()),
                selectUnits(harnesses, effective.harnesses(), UnitKind.HARNESS, effective.inheritsDefault()),
                selectUnits(docs, effective.docs(), UnitKind.DOC, effective.inheritsDefault()),
                effective.inheritsDefault() ? cliDependencies : List.of(),
                effective.inheritsDefault() ? mcpDependencies : List.of(),
                selectEnvs(envs, effective.envs(), effective.inheritsDefault()),
                selectLibs(libs, effective.libs(), effective.inheritsDefault()),
                profiles,
                profileName);
    }

    private EffectiveProfile effectiveProfile(ProjectProfile profile, Set<String> seen) {
        if (!seen.add(profile.name())) {
            throw new IllegalArgumentException("project profile inheritance cycle at " + profile.name());
        }
        boolean inheritsDefault = false;
        List<String> skillNames = new ArrayList<>();
        List<String> pluginNames = new ArrayList<>();
        List<String> harnessNames = new ArrayList<>();
        List<String> docNames = new ArrayList<>();
        List<String> envNames = new ArrayList<>();
        List<String> libNames = new ArrayList<>();
        for (String parent : profile.extendsProfiles()) {
            if ("default".equals(parent)) {
                inheritsDefault = true;
                continue;
            }
            ProjectProfile parentProfile = profile(parent).orElseThrow(() ->
                    new IllegalArgumentException("project profile " + profile.name()
                            + " extends unknown profile " + parent));
            EffectiveProfile p = effectiveProfile(parentProfile, seen);
            inheritsDefault = inheritsDefault || p.inheritsDefault();
            skillNames.addAll(p.skills());
            pluginNames.addAll(p.plugins());
            harnessNames.addAll(p.harnesses());
            docNames.addAll(p.docs());
            envNames.addAll(p.envs());
            libNames.addAll(p.libs());
        }
        skillNames.addAll(profile.skills());
        pluginNames.addAll(profile.plugins());
        harnessNames.addAll(profile.harnesses());
        docNames.addAll(profile.docs());
        envNames.addAll(profile.envs());
        libNames.addAll(profile.libs());
        seen.remove(profile.name());
        return new EffectiveProfile(inheritsDefault,
                dedupe(skillNames), dedupe(pluginNames), dedupe(harnessNames),
                dedupe(docNames), dedupe(envNames), dedupe(libNames));
    }

    private static List<ProjectUnitRef> selectUnits(
            List<ProjectUnitRef> base,
            List<String> aliases,
            UnitKind kind,
            boolean includeDefault
    ) {
        if (aliases.isEmpty()) return includeDefault ? base : List.of();
        Map<String, ProjectUnitRef> byAlias = new LinkedHashMap<>();
        for (ProjectUnitRef ref : base) byAlias.put(ref.alias(), ref);
        List<ProjectUnitRef> out = includeDefault ? new ArrayList<>(base) : new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ProjectUnitRef ref : out) seen.add(ref.alias());
        for (String alias : aliases) {
            ProjectUnitRef ref = byAlias.get(alias);
            if (ref == null) {
                throw new IllegalArgumentException("project profile selects unknown "
                        + kind.name().toLowerCase() + " alias: " + alias);
            }
            if (seen.add(alias)) out.add(ref);
        }
        return out;
    }

    private static List<ProjectEnv> selectEnvs(List<ProjectEnv> base, List<String> names, boolean includeDefault) {
        if (names.isEmpty()) return includeDefault ? base : List.of();
        Map<String, ProjectEnv> byName = new LinkedHashMap<>();
        for (ProjectEnv env : base) byName.put(env.name(), env);
        List<ProjectEnv> out = includeDefault ? new ArrayList<>(base) : new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ProjectEnv env : out) seen.add(env.name());
        for (String name : names) {
            ProjectEnv env = byName.get(name);
            if (env == null) {
                throw new IllegalArgumentException("project profile selects unknown env: " + name);
            }
            if (seen.add(name)) out.add(env);
        }
        return out;
    }

    private static List<ProjectLib> selectLibs(List<ProjectLib> base, List<String> names, boolean includeDefault) {
        if (names.isEmpty()) return includeDefault ? base : List.of();
        Map<String, ProjectLib> byName = new LinkedHashMap<>();
        for (ProjectLib lib : base) byName.put(lib.name(), lib);
        List<ProjectLib> out = includeDefault ? new ArrayList<>(base) : new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ProjectLib lib : out) seen.add(lib.name());
        for (String name : names) {
            ProjectLib lib = byName.get(name);
            if (lib == null) {
                throw new IllegalArgumentException("project profile selects unknown lib: " + name);
            }
            if (seen.add(name)) out.add(lib);
        }
        return out;
    }

    private static List<String> dedupe(List<String> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    private static String safeProfileSegment(String profileName) {
        return profileName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public record ProjectUnitRef(
            String alias,
            UnitKind kind,
            UnitReference reference,
            String revision,
            boolean install
    ) {
        public ProjectUnitRef {
            if (alias == null || alias.isBlank()) {
                throw new IllegalArgumentException("project dependency alias must not be blank");
            }
            if (kind == null) throw new IllegalArgumentException("project dependency kind must not be null");
            if (reference == null) throw new IllegalArgumentException("project dependency reference must not be null");
            if (!reference.kindFilter().accepts(kind)) {
                throw new IllegalArgumentException("reference " + reference.coord().raw()
                        + " does not match " + kind.name().toLowerCase() + " dependency " + alias);
            }
        }

        public String source() {
            return reference.coord().raw();
        }
    }

    public record ProjectEnv(
            String name,
            String python,
            List<String> dependencies,
            List<String> skillPackages,
            List<String> tools
    ) {
        public ProjectEnv {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("project env name must not be blank");
            }
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            skillPackages = skillPackages == null ? List.of() : List.copyOf(skillPackages);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    public record ProjectLib(
            String name,
            String source,
            String ref,
            String sha
    ) {
        public ProjectLib {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("project lib name must not be blank");
            }
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("project lib source must not be blank");
            }
        }
    }

    public record ProjectProfile(
            String name,
            List<String> extendsProfiles,
            List<String> skills,
            List<String> plugins,
            List<String> harnesses,
            List<String> docs,
            List<String> envs,
            List<String> libs
    ) {
        public ProjectProfile {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("project profile name must not be blank");
            }
            extendsProfiles = extendsProfiles == null ? List.of() : List.copyOf(extendsProfiles);
            skills = skills == null ? List.of() : List.copyOf(skills);
            plugins = plugins == null ? List.of() : List.copyOf(plugins);
            harnesses = harnesses == null ? List.of() : List.copyOf(harnesses);
            docs = docs == null ? List.of() : List.copyOf(docs);
            envs = envs == null ? List.of() : List.copyOf(envs);
            libs = libs == null ? List.of() : List.copyOf(libs);
        }
    }

    private record EffectiveProfile(
            boolean inheritsDefault,
            List<String> skills,
            List<String> plugins,
            List<String> harnesses,
            List<String> docs,
            List<String> envs,
            List<String> libs
    ) {}
}
