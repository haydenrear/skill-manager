package dev.skillmanager.project;

import dev.skillmanager.bindings.BindingSource;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class SkillProjectLockStore {

    private final SkillStore store;

    public SkillProjectLockStore(SkillStore store) {
        this.store = store;
    }

    public Path path(String projectName) {
        return store.projectsDir().resolve(projectName).resolve(SkillProjectLock.FILENAME);
    }

    public void write(SkillProjectLock lock) throws IOException {
        Fs.ensureDir(store.projectsDir().resolve(lock.projectName()));
        Files.writeString(path(lock.projectName()), render(lock));
    }

    public Optional<SkillProjectLock> read(String projectName) throws IOException {
        Path lockPath = path(projectName);
        if (!Files.isRegularFile(lockPath)) return Optional.empty();
        TomlParseResult toml = Toml.parse(lockPath);
        if (toml.hasErrors()) {
            StringBuilder sb = new StringBuilder("Failed to parse ").append(lockPath).append(":\n");
            toml.errors().forEach(err -> sb.append("  ").append(err).append('\n'));
            throw new IOException(sb.toString());
        }
        String name = toml.getString("project.name");
        String manifestFile = toml.getString("project.manifest_file");
        String resolvedAt = toml.getString("project.resolved_at");
        if (name == null || name.isBlank()) {
            throw new IOException("Malformed project lock in " + lockPath + ": missing project.name");
        }
        List<SkillProjectLock.ResolvedUnit> units = new ArrayList<>();
        TomlArray resolved = toml.getArray("resolved_units");
        if (resolved != null) {
            for (int i = 0; i < resolved.size(); i++) {
                TomlTable row = resolved.getTable(i);
                if (row == null) continue;
                String unitName = row.getString("name");
                String kind = row.getString("kind");
                if (unitName == null || kind == null) continue;
                units.add(new SkillProjectLock.ResolvedUnit(
                        unitName,
                        UnitKind.valueOf(kind),
                        row.getString("version"),
                        row.getString("source"),
                        Boolean.TRUE.equals(row.getBoolean("direct"))));
            }
        }
        List<SkillProjectLock.ProjectBinding> bindings = new ArrayList<>();
        TomlArray bindingRows = toml.getArray("bindings");
        if (bindingRows != null) {
            for (int i = 0; i < bindingRows.size(); i++) {
                TomlTable row = bindingRows.getTable(i);
                if (row == null) continue;
                String bindingId = row.getString("id");
                String unitName = row.getString("unit");
                String kind = row.getString("kind");
                String source = row.getString("source");
                if (bindingId == null || unitName == null || kind == null || source == null) continue;
                bindings.add(new SkillProjectLock.ProjectBinding(
                        bindingId,
                        unitName,
                        UnitKind.valueOf(kind),
                        BindingSource.valueOf(source),
                        row.getString("target_root")));
            }
        }
        return Optional.of(new SkillProjectLock(name, manifestFile, resolvedAt, units, bindings));
    }

    public List<SkillProjectLock> list() throws IOException {
        if (!Files.isDirectory(store.projectsDir())) return List.of();
        List<SkillProjectLock> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(store.projectsDir())) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isDirectory(p)) continue;
                Optional<SkillProjectLock> lock = read(p.getFileName().toString());
                lock.ifPresent(out::add);
            }
        }
        out.sort(Comparator.comparing(SkillProjectLock::projectName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public List<String> projectsClaiming(String unitName) throws IOException {
        List<String> claimers = new ArrayList<>();
        for (SkillProjectLock lock : list()) {
            boolean claimed = lock.resolvedUnits().stream().anyMatch(u -> u.name().equals(unitName));
            if (claimed) claimers.add(lock.projectName());
        }
        return claimers;
    }

    private static String render(SkillProjectLock lock) {
        StringBuilder sb = new StringBuilder();
        sb.append("version = 1\n\n");
        sb.append("[project]\n");
        sb.append("name = \"").append(esc(lock.projectName())).append("\"\n");
        sb.append("manifest_file = \"").append(esc(lock.manifestFile())).append("\"\n");
        sb.append("resolved_at = \"").append(esc(lock.resolvedAt())).append("\"\n\n");
        for (SkillProjectLock.ResolvedUnit unit : lock.resolvedUnits()) {
            sb.append("[[resolved_units]]\n");
            sb.append("name = \"").append(esc(unit.name())).append("\"\n");
            sb.append("kind = \"").append(unit.kind().name()).append("\"\n");
            if (unit.version() != null) {
                sb.append("version = \"").append(esc(unit.version())).append("\"\n");
            }
            if (unit.source() != null) {
                sb.append("source = \"").append(esc(unit.source())).append("\"\n");
            }
            sb.append("direct = ").append(unit.direct()).append("\n\n");
        }
        for (SkillProjectLock.ProjectBinding binding : lock.bindings()) {
            sb.append("[[bindings]]\n");
            sb.append("id = \"").append(esc(binding.bindingId())).append("\"\n");
            sb.append("unit = \"").append(esc(binding.unitName())).append("\"\n");
            sb.append("kind = \"").append(binding.unitKind().name()).append("\"\n");
            sb.append("source = \"").append(binding.source().name()).append("\"\n");
            if (binding.targetRoot() != null) {
                sb.append("target_root = \"").append(esc(binding.targetRoot())).append("\"\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
