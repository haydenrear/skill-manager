package dev.skillmanager.project;

import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Project registry for {@code skill-manager project register}. The registry
 * snapshots manifest intent under {@code $SKILL_MANAGER_HOME/projects/<name>/}
 * and stores registration metadata, but deliberately does not resolve or
 * install any declared dependency.
 */
public final class SkillProjectRegistry {

    public static final String REGISTRATION_FILENAME = "registration.toml";

    private final SkillStore store;

    public SkillProjectRegistry(SkillStore store) {
        this.store = store;
    }

    public SkillProjectRegistration register(SkillProject project) throws IOException {
        String name = project.registryName();
        requireSafeName(name);
        Fs.ensureDir(store.projectsDir());
        Path dir = store.projectsDir().resolve(name);
        String manifestFile = project.manifestPath().getFileName().toString();
        requireSafeManifestFile(manifestFile, dir.resolve(REGISTRATION_FILENAME));
        Fs.ensureDir(dir);
        Files.copy(project.manifestPath(), dir.resolve(manifestFile),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        String registeredAt = Instant.now().toString();
        Path registration = dir.resolve(REGISTRATION_FILENAME);
        Files.writeString(registration, """
                [project]
                name = "%s"
                project_root = "%s"
                manifest_path = "%s"
                manifest_file = "%s"
                profile = "%s"
                registered_at = "%s"
                """.formatted(
                        escape(name),
                        escape(project.projectRoot().toString()),
                        escape(project.manifestPath().toString()),
                        escape(manifestFile),
                        escape(project.activeProfile() == null ? "" : project.activeProfile()),
                        escape(registeredAt)));
        return new SkillProjectRegistration(
                name,
                project.projectRoot(),
                project.manifestPath(),
                manifestFile,
                dir,
                registeredAt);
    }

    public Optional<SkillProjectRegistration> read(String name) throws IOException {
        requireSafeName(name);
        Path dir = store.projectsDir().resolve(name);
        Path registration = dir.resolve(REGISTRATION_FILENAME);
        if (!Files.isRegularFile(registration)) return Optional.empty();
        TomlParseResult toml = Toml.parse(registration);
        if (toml.hasErrors()) {
            StringBuilder sb = new StringBuilder("Failed to parse ").append(registration).append(":\n");
            toml.errors().forEach(err -> sb.append("  ").append(err).append('\n'));
            throw new IOException(sb.toString());
        }
        String projectName = toml.getString("project.name");
        String root = toml.getString("project.project_root");
        String manifest = toml.getString("project.manifest_path");
        String manifestFile = toml.getString("project.manifest_file");
        String registeredAt = toml.getString("project.registered_at");
        if (projectName == null || root == null || manifest == null) {
            throw new IOException("Malformed project registration in " + registration);
        }
        if (manifestFile == null || manifestFile.isBlank()) {
            manifestFile = Path.of(manifest).getFileName().toString();
        }
        requireSafeManifestFile(manifestFile, registration);
        return Optional.of(new SkillProjectRegistration(
                projectName,
                Path.of(root),
                Path.of(manifest),
                manifestFile,
                dir,
                registeredAt));
    }

    public List<SkillProjectRegistration> list() throws IOException {
        if (!Files.isDirectory(store.projectsDir())) return List.of();
        List<SkillProjectRegistration> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(store.projectsDir())) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isDirectory(p)) continue;
                Optional<SkillProjectRegistration> reg = read(p.getFileName().toString());
                reg.ifPresent(out::add);
            }
        }
        out.sort(Comparator.comparing(SkillProjectRegistration::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public Optional<SkillProject> loadSnapshot(String name) throws IOException {
        Optional<SkillProjectRegistration> registration = read(name);
        if (registration.isEmpty()) return Optional.empty();
        SkillProjectRegistration reg = registration.get();
        Path manifest = reg.registrationDir().resolve(reg.manifestFile());
        if (!Files.isRegularFile(manifest)) return Optional.empty();
        return Optional.of(SkillProjectParser.loadManifest(manifest, reg.projectRoot()));
    }

    private static void requireSafeName(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IOException("Invalid project name for registry path: " + name);
        }
    }

    private static void requireSafeManifestFile(String manifestFile, Path registration) throws IOException {
        Path path = Path.of(manifestFile);
        if (path.isAbsolute()
                || path.getNameCount() != 1
                || !path.getFileName().toString().equals(manifestFile)
                || REGISTRATION_FILENAME.equals(manifestFile)) {
            throw new IOException("Malformed project registration manifest_file in " + registration);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
