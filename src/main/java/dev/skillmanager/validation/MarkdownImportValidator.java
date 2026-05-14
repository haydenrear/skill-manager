package dev.skillmanager.validation;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.store.SkillStore;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Validates frontmatter-only markdown imports:
 *
 * <pre>
 * ---
 * skill-imports:
 *   - skill: skill-manager-skill
 *     path: scripts/env.sh
 *     reason: Defines CLI binary conventions.
 * ---
 * </pre>
 *
 * <p>The source markdown can live under any unit kind. The target is
 * intentionally skill-only: {@code skill} resolves under
 * {@code $SKILL_MANAGER_HOME/skills/<name>} and {@code path} must stay
 * inside that installed skill directory.
 */
public final class MarkdownImportValidator {

    public static final String FRONTMATTER_KEY = "skill-imports";

    private MarkdownImportValidator() {}

    public record UnitRoot(String unitName, UnitKind kind, Path root) {}

    public record Violation(String unitName, UnitKind kind, Path file, String message) {
        public String render() {
            String unit = kind == null
                    ? unitName
                    : unitName + " (" + kind.name().toLowerCase() + ")";
            return unit + ": " + file + ": " + message;
        }
    }

    public static List<Violation> validateInstalled(SkillStore store, List<String> unitNames)
            throws IOException {
        if (unitNames == null || unitNames.isEmpty()) return List.of();
        List<UnitRoot> roots = new ArrayList<>();
        for (String name : unitNames) {
            if (name == null || name.isBlank()) continue;
            Optional<AgentUnit> loaded = store.loadUnit(name);
            if (loaded.isEmpty()) {
                roots.add(new UnitRoot(name, null, null));
                continue;
            }
            AgentUnit unit = loaded.get();
            roots.add(new UnitRoot(unit.name(), unit.kind(), unit.sourcePath()));
        }
        return validate(store, roots);
    }

    public static List<Violation> validateSource(SkillStore store, AgentUnit unit)
            throws IOException {
        return validate(store, List.of(new UnitRoot(unit.name(), unit.kind(), unit.sourcePath())));
    }

    public static List<Violation> validate(SkillStore store, List<UnitRoot> roots)
            throws IOException {
        if (roots == null || roots.isEmpty()) return List.of();
        List<Violation> violations = new ArrayList<>();
        for (UnitRoot root : roots) {
            if (root.root() == null || !Files.isDirectory(root.root())) {
                violations.add(new Violation(root.unitName(), root.kind(), Path.of("."),
                        "unit root is not installed; install the unit before validating imports"));
                continue;
            }
            try (Stream<Path> files = Files.walk(root.root())) {
                for (Path file : (Iterable<Path>) files
                        .filter(Files::isRegularFile)
                        .filter(MarkdownImportValidator::isMarkdown)::iterator) {
                    violations.addAll(validateFile(store, root, file));
                }
            }
        }
        return violations;
    }

    public static String format(List<Violation> violations) {
        StringBuilder sb = new StringBuilder("markdown skill-import validation failed");
        for (Violation v : violations) {
            sb.append('\n').append("  - ").append(v.render());
        }
        return sb.toString();
    }

    private static List<Violation> validateFile(SkillStore store, UnitRoot root, Path file) {
        List<Violation> violations = new ArrayList<>();
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException io) {
            violations.add(violation(root, file, "could not read markdown: " + io.getMessage()));
            return violations;
        }
        Optional<Map<String, Object>> frontmatter;
        try {
            frontmatter = frontmatter(content);
        } catch (RuntimeException ex) {
            violations.add(violation(root, file, "invalid YAML frontmatter: " + ex.getMessage()));
            return violations;
        }
        if (frontmatter.isEmpty()) return violations;
        Object raw = frontmatter.get().get(FRONTMATTER_KEY);
        if (raw == null) return violations;
        if (!(raw instanceof List<?> imports)) {
            violations.add(violation(root, file,
                    FRONTMATTER_KEY + " must be a list of import entries"));
            return violations;
        }
        for (int i = 0; i < imports.size(); i++) {
            Object item = imports.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                violations.add(violation(root, file,
                        FRONTMATTER_KEY + "[" + i + "] must be a mapping with skill/path/reason"));
                continue;
            }
            validateImport(store, root, file, i, copyMap(map), violations);
        }
        return violations;
    }

    private static void validateImport(SkillStore store, UnitRoot root, Path file, int index,
                                       Map<String, Object> entry, List<Violation> violations) {
        String prefix = FRONTMATTER_KEY + "[" + index + "]";
        String skill = asString(entry.get("skill"));
        String path = asString(entry.get("path"));
        String reason = asString(entry.get("reason"));

        if (skill == null || skill.isBlank()) {
            violations.add(violation(root, file, prefix
                    + " is missing required `skill`; add an installed skill name"));
        }
        if (path == null || path.isBlank()) {
            violations.add(violation(root, file, prefix
                    + " is missing required `path`; add a file path inside the target skill"));
        }
        if (reason == null || reason.isBlank()) {
            violations.add(violation(root, file, prefix
                    + " is missing required `reason`; explain why the import exists"));
        }
        if (skill == null || skill.isBlank() || path == null || path.isBlank()) return;

        Path targetSkill = store.skillDir(skill).toAbsolutePath().normalize();
        if (!store.contains(skill)) {
            violations.add(violation(root, file, prefix + " references missing skill `" + skill
                    + "`; install it or fix the `skill` value"));
            return;
        }
        Path rel;
        try {
            rel = Path.of(path);
        } catch (RuntimeException ex) {
            violations.add(violation(root, file, prefix + " has invalid `path` `" + path + "`"));
            return;
        }
        if (rel.isAbsolute()) {
            violations.add(violation(root, file, prefix
                    + " path must be relative to skill `" + skill + "`"));
            return;
        }
        Path target = targetSkill.resolve(rel).normalize();
        if (!target.startsWith(targetSkill)) {
            violations.add(violation(root, file, prefix
                    + " path escapes skill `" + skill + "`; keep it inside the skill directory"));
            return;
        }
        if (!Files.isRegularFile(target)) {
            violations.add(violation(root, file, prefix + " references missing path `" + path
                    + "` in skill `" + skill + "`; add the file or fix the path"));
        }
    }

    private static Violation violation(UnitRoot root, Path file, String message) {
        Path rendered = file;
        if (root.root() != null) {
            try {
                rendered = root.root().toAbsolutePath().normalize()
                        .relativize(file.toAbsolutePath().normalize());
            } catch (IllegalArgumentException ignored) {
                rendered = file;
            }
        }
        return new Violation(root.unitName(), root.kind(), rendered, message);
    }

    private static boolean isMarkdown(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> frontmatter(String content) {
        if (!content.startsWith("---")) return Optional.empty();
        int firstNl = content.indexOf('\n');
        if (firstNl < 0) return Optional.empty();
        int end = content.indexOf("\n---", firstNl);
        if (end < 0) return Optional.empty();
        String yaml = content.substring(firstNl + 1, end);
        Object loaded = new Yaml().load(yaml);
        if (!(loaded instanceof Map<?, ?> map)) return Optional.of(Map.of());
        return Optional.of(copyMap(map));
    }

    private static Map<String, Object> copyMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
