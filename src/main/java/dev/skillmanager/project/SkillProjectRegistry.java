package dev.skillmanager.project;

import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.HomePaths;
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
                        // project_root points at a checkout elsewhere on disk
                        // and stays absolute. manifest_path is whichever
                        // manifest the caller registered from — the project's
                        // own file, or (when re-registering from a snapshot)
                        // this registry's copy under projects/<name>/. Only
                        // the latter is a self-reference, and encode() leaves
                        // the former alone.
                        escape(project.projectRoot().toString()),
                        escape(HomePaths.of(store.root()).encode(project.manifestPath())),
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
        String manifest = HomePaths.of(store.root())
                .decodeToString(toml.getString("project.manifest_path"));
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

    /**
     * The manifest a registered project declares TODAY: the repository's own
     * file, never the copy this registry cached under {@code projects/<name>/}.
     *
     * <p>#394. A root-home sync reconciled a project's child home against
     * {@link #loadSnapshot}, a copy two months older than the repository's
     * {@code skill-project.toml}, concluded a plugin the repository declares
     * was "no longer a project dependency", and deleted it. The snapshot is a
     * record of what was registered; it cannot show it is current, so nothing
     * that DELETES may be judged against it.
     *
     * <p>Which file: the registration's {@code manifest_path} when it names a
     * file outside this registry's own directory (the project's manifest, or a
     * custom {@code --manifest}); otherwise — the incident's shape, where the
     * registration was re-written from the snapshot and so points at itself —
     * {@code <project_root>/<manifest_file>}.
     *
     * @return empty when the project is not registered
     * @throws ProjectManifestUnreadableException when the repository's manifest
     *         is missing or does not parse; the caller must leave the child
     *         home alone rather than fall back to the snapshot
     */
    public Optional<SkillProject> loadCurrent(String name) throws IOException {
        Optional<SkillProjectRegistration> registration = read(name);
        if (registration.isEmpty()) return Optional.empty();
        Path manifest = repositoryManifest(registration.get());
        if (!Files.isRegularFile(manifest)) {
            throw new ProjectManifestUnreadableException(name, manifest,
                    "the repository's manifest is not there");
        }
        try {
            return Optional.of(SkillProjectParser.loadManifest(manifest, registration.get().projectRoot()));
        } catch (IOException | RuntimeException parse) {
            throw new ProjectManifestUnreadableException(name, manifest,
                    "the repository's manifest does not parse: " + parse.getMessage());
        }
    }

    /** The repository's own manifest for {@code reg} — see {@link #loadCurrent}. */
    public static Path repositoryManifest(SkillProjectRegistration reg) {
        Path recorded = reg.manifestPath() == null ? null : reg.manifestPath().toAbsolutePath().normalize();
        Path cacheDir = reg.registrationDir().toAbsolutePath().normalize();
        if (recorded != null && !recorded.startsWith(cacheDir)) return recorded;
        return reg.projectRoot().resolve(reg.manifestFile()).toAbsolutePath().normalize();
    }

    public void delete(String name) throws IOException {
        requireSafeName(name);
        Path dir = store.projectsDir().resolve(name);
        if (Files.exists(dir)) Fs.deleteRecursive(dir);
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
