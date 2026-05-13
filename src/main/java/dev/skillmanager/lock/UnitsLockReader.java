package dev.skillmanager.lock;

import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.store.SkillStore;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code units.lock.toml} into a {@link UnitsLock}. Unknown
 * {@code schema_version} fails loudly — the lock format is the
 * source-of-truth for vendored installs and silently ignoring future
 * versions would let a newer-built lock land in an older skill-manager
 * and produce inconsistent installs.
 */
public final class UnitsLockReader {

    public static final String FILENAME = "units.lock.toml";

    private UnitsLockReader() {}

    public static Path defaultPath(SkillStore store) {
        return store.root().resolve(FILENAME);
    }

    /**
     * Read the lock at {@code path}. Returns {@link UnitsLock#empty} if
     * the file is missing — the lock is born on first install. Throws
     * {@link IOException} on parse errors or unsupported schema_version.
     */
    public static UnitsLock read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return UnitsLock.empty();
        TomlParseResult toml = Toml.parse(path);
        if (toml.hasErrors()) {
            throw new IOException("invalid TOML in " + path + ": " + toml.errors());
        }
        Long sv = toml.getLong("schema_version");
        int schemaVersion = sv == null ? UnitsLock.CURRENT_SCHEMA : sv.intValue();
        if (schemaVersion != UnitsLock.CURRENT_SCHEMA) {
            throw new IOException("unsupported units.lock.toml schema_version=" + schemaVersion
                    + " (this skill-manager understands version " + UnitsLock.CURRENT_SCHEMA + ")");
        }

        List<LockedUnit> rows = new ArrayList<>();
        TomlArray arr = toml.getArray("units");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                TomlTable t = arr.getTable(i);
                rows.add(parseRow(t, i, path));
            }
        }
        return new UnitsLock(schemaVersion, rows);
    }

    private static LockedUnit parseRow(TomlTable t, int i, Path path) throws IOException {
        String name = t.getString("name");
        if (name == null || name.isBlank()) {
            throw new IOException("units.lock.toml row " + i + " has no name (" + path + ")");
        }
        String kindStr = t.getString("kind");
        UnitKind kind = parseKind(kindStr, name, path);
        String version = nullableString(t, "version");
        String installSourceStr = t.getString("install_source");
        InstalledUnit.InstallSource installSource = parseInstallSource(installSourceStr);
        String origin = nullableString(t, "origin");
        String ref = nullableString(t, "ref");
        String resolvedSha = nullableString(t, "resolved_sha");
        return new LockedUnit(name, kind, version, installSource, origin, ref, resolvedSha);
    }

    private static UnitKind parseKind(String kindStr, String name, Path path) throws IOException {
        if (kindStr == null || kindStr.isBlank()) return UnitKind.SKILL; // legacy default
        return switch (kindStr.toLowerCase()) {
            case "skill" -> UnitKind.SKILL;
            case "plugin" -> UnitKind.PLUGIN;
            case "doc" -> UnitKind.DOC;
            case "harness" -> UnitKind.HARNESS;
            default -> throw new IOException(
                    "units.lock.toml unit '" + name + "' has unknown kind=" + kindStr + " (" + path + ")");
        };
    }

    private static InstalledUnit.InstallSource parseInstallSource(String s) {
        if (s == null || s.isBlank()) return InstalledUnit.InstallSource.UNKNOWN;
        try {
            return InstalledUnit.InstallSource.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return InstalledUnit.InstallSource.UNKNOWN;
        }
    }

    private static String nullableString(TomlTable t, String key) {
        String s = t.getString(key);
        return (s == null || s.isBlank()) ? null : s;
    }
}
