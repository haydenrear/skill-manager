package dev.skillmanager.validation;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.store.SkillStore;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

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
 *   - unit: skill-manager
 *     path: scripts/env.sh
 *     reason: Defines CLI binary conventions.
 * ---
 * </pre>
 *
 * <p>The source markdown can live under any unit kind. The target can be
 * any installed unit kind: skill, plugin, doc-repo, or harness. Existing
 * frontmatter may still use the historical {@code skill} key; new
 * frontmatter should prefer {@code unit}.
 */
public final class MarkdownImportValidator {

    public static final String FRONTMATTER_KEY = "skill-imports";

    /**
     * Exit code for "the run printed skill-import violations".
     *
     * <h2>Why a violation needs an exit code at all</h2>
     *
     * <p>{@code install} used to print the violation block and exit 0. The
     * block lands after the success banner and after {@code ACTION_REQUIRED},
     * at the bottom of a long install, so an agent that reads the last lines
     * or checks {@code $?} concludes success — and a printed violation that
     * does not reach an exit code is not a check, it is a comment. Measured:
     * installing a unit with two bad imports (one naming a missing unit, one
     * naming a missing path in a present unit) exited 0 with both printed.
     *
     * <p>Distinct from the generic 1 so a caller can tell "your markdown names
     * something that is not there" from "the install failed". The unit is
     * still committed when this fires — the bytes are on disk and the
     * references in them are wrong — which is why it is reported after the
     * commit-failure code (4) rather than instead of it.
     *
     * <p>7, 8, 9 and 10 are taken (auth / unredirected launch; registry
     * unavailable / drift; frozen home / gateway attached / clone auth; git
     * fetch).
     */
    public static final int EXIT_CODE = 11;

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
            Optional<UnitRoot> root = installedRoot(store, name);
            if (root.isEmpty()) {
                roots.add(new UnitRoot(name, null, null));
                continue;
            }
            roots.add(root.get());
        }
        return validate(store, roots);
    }

    public static List<Violation> validateSource(SkillStore store, AgentUnit unit)
            throws IOException {
        return validate(store, List.of(new UnitRoot(unit.name(), unit.kind(), unit.sourcePath())));
    }

    /**
     * Directory names never walked when validating a skill project's own
     * markdown.
     *
     * <p>Everything hidden is skipped by {@link #isProjectOwnMarkdown} — that
     * covers {@code .git} and, more importantly, {@code .skill-manager},
     * {@code .claude}, {@code .codex} and {@code .gemini}, whose
     * {@code skills/} directories are symlinks into the store. Walking those
     * would re-validate every INSTALLED unit's imports under the project's
     * name and report them as the project's problem.
     *
     * <p>{@code libs/} is a project's development checkouts of OTHER
     * repositories, materialized by {@code project resolve --resolve-libs}.
     * Their markdown is not this project's to answer for.
     */
    private static final java.util.Set<String> NOT_THE_PROJECTS_OWN_MARKDOWN =
            java.util.Set.of("node_modules", "libs", "target", "build", "venv");

    /**
     * Validate the markdown a <em>skill project checkout</em> owns —
     * {@code CLAUDE.md}, {@code AGENTS.md}, {@code docs/**}{@code .md},
     * anything else the repository actually wrote.
     *
     * <h2>Why this exists</h2>
     *
     * <p>{@code ValidateMarkdownImports} was emitted only by
     * {@code InstallUseCase}, {@code SyncUseCase}, {@code OnboardCommand} and
     * {@code PublishCommand}, and {@link #validateInstalled} walks INSTALLED
     * UNIT ROOTS. A skill project checkout is not a unit root, so a project
     * whose own {@code CLAUDE.md} imported a skill was checked by nothing:
     * measured, with that import broken to name both a missing unit and a
     * missing path, {@code project resolve} exited 0 and a grep for "import"
     * or "violation" over the whole log returned nothing. The frontmatter was
     * inert — neither validated nor materialized — and the only signal an
     * operator got was the file not doing anything.
     *
     * <p>The import semantics are identical to a unit's: the same
     * {@code unit} / {@code path} / {@code reason} entries, resolved against
     * the same installed units. Only the ROOT being walked differs, which is
     * why this delegates to {@link #validateFile} rather than restating it.
     *
     * @param projectName the name violations are attributed to — the project,
     *                    not a unit, so a reader can tell "my checkout names
     *                    something that is not installed" from "an installed
     *                    unit does"
     */
    public static List<Violation> validateProject(SkillStore store, String projectName,
                                                  Path projectRoot) throws IOException {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return List.of();
        Path root = projectRoot.toAbsolutePath().normalize();
        List<Violation> violations = new ArrayList<>();
        UnitRoot as = new UnitRoot(projectName, null, root);
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files
                    .filter(Files::isRegularFile)
                    .filter(MarkdownImportValidator::isMarkdown)
                    .filter(f -> isProjectOwnMarkdown(root, f))::iterator) {
                violations.addAll(validateFile(store, as, file));
            }
        }
        return violations;
    }

    /**
     * True when {@code file} is markdown the project checkout itself authored.
     *
     * <p>{@link Files#walk} does not follow symbolic links, so the agent
     * directories' {@code skills/<unit>} links are never descended into — but
     * this is asserted by NAME as well, because the property under test is
     * "the project's own files" and leaning on a walk option for it is the
     * kind of implicit guarantee that stops holding the day someone
     * materializes a projection as a copy rather than a link.
     */
    private static boolean isProjectOwnMarkdown(Path root, Path file) {
        Path rel = root.relativize(file);
        for (int i = 0; i < rel.getNameCount() - 1; i++) {
            String segment = rel.getName(i).toString();
            if (segment.startsWith(".")) return false;
            if (NOT_THE_PROJECTS_OWN_MARKDOWN.contains(segment)) return false;
        }
        return true;
    }

    public static List<Violation> validate(SkillStore store, List<UnitRoot> roots)
            throws IOException {
        return validate(store, roots, Map.of());
    }

    /**
     * Validate {@code roots} against the installed store PLUS a candidate
     * closure of units that are staged but not yet installed.
     *
     * <h2>Why targets can be uninstalled</h2>
     *
     * <p>{@code project resolve} into a clean home must validate the whole
     * declared closure BEFORE committing any of it (issue #168): for units A
     * and B with reciprocal {@code skill-imports}, validating A after
     * installing only A reports B as missing whichever is installed first, and
     * every clean resolve advances one unit per invocation. The candidate map
     * lets an import target resolve to a staged source root — the same
     * {@link UnitRoot} shape {@link #validateSource} already uses for the
     * SOURCE side — so the closure is judged as the set it will be once
     * published, not as the incomplete store mid-publish.
     *
     * <p>Candidates win over the installed store deliberately: a staged unit
     * that replaces an installed copy should be validated against the bytes
     * about to land, not the ones about to be overwritten.
     *
     * @param candidates staged-unit roots keyed by unit name; consulted before
     *                   the installed store when resolving an import target
     */
    public static List<Violation> validate(SkillStore store, List<UnitRoot> roots,
                                           Map<String, UnitRoot> candidates)
            throws IOException {
        if (roots == null || roots.isEmpty()) return List.of();
        Map<String, UnitRoot> candidateRoots = candidates == null ? Map.of() : candidates;
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
                    violations.addAll(validateFile(store, candidateRoots, root, file));
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
        return validateFile(store, Map.of(), root, file);
    }

    private static List<Violation> validateFile(SkillStore store, Map<String, UnitRoot> candidates,
                                                UnitRoot root, Path file) {
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
        } catch (YAMLException ex) {
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
                        FRONTMATTER_KEY + "[" + i + "] must be a mapping with unit/path/reason"));
                continue;
            }
            validateImport(store, candidates, root, file, i, copyMap(map), violations);
        }
        return violations;
    }

    private static void validateImport(SkillStore store, Map<String, UnitRoot> candidates,
                                       UnitRoot root, Path file, int index,
                                       Map<String, Object> entry, List<Violation> violations) {
        String prefix = FRONTMATTER_KEY + "[" + index + "]";
        String unit = firstNonBlank(asString(entry.get("unit")), asString(entry.get("skill")));
        String path = asString(entry.get("path"));
        String reason = asString(entry.get("reason"));

        if (unit == null || unit.isBlank()) {
            violations.add(violation(root, file, prefix
                    + " is missing required `unit`; add an installed unit name"));
        }
        if (path == null || path.isBlank()) {
            violations.add(violation(root, file, prefix
                    + " is missing required `path`; add a file path inside the target unit"));
        }
        if (reason == null || reason.isBlank()) {
            violations.add(violation(root, file, prefix
                    + " is missing required `reason`; explain why the import exists"));
        }
        if (unit == null || unit.isBlank() || path == null || path.isBlank()) return;

        UnitRoot candidate = candidates.get(unit);
        Optional<UnitRoot> targetRoot = candidate != null && candidate.root() != null
                ? Optional.of(candidate)
                : installedRoot(store, unit);
        if (targetRoot.isEmpty()) {
            violations.add(violation(root, file, prefix + " references missing unit `" + unit
                    + "`; install it or fix the `unit` value"));
            return;
        }
        Path targetUnit = targetRoot.get().root().toAbsolutePath().normalize();
        Path rel;
        try {
            rel = Path.of(path);
        } catch (RuntimeException ex) {
            violations.add(violation(root, file, prefix + " has invalid `path` `" + path + "`"));
            return;
        }
        if (rel.isAbsolute()) {
            violations.add(violation(root, file, prefix
                    + " path must be relative to unit `" + unit + "`"));
            return;
        }
        Path target = targetUnit.resolve(rel).normalize();
        if (!target.startsWith(targetUnit)) {
            violations.add(violation(root, file, prefix
                    + " path escapes unit `" + unit + "`; keep it inside the unit directory"));
            return;
        }
        if (!Files.isRegularFile(target)) {
            violations.add(violation(root, file, prefix + " references missing path `" + path
                    + "` in unit `" + unit + "`; add the file or fix the path"));
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

    private static Optional<UnitRoot> installedRoot(SkillStore store, String name) {
        if (store.containsPlugin(name)) {
            return Optional.of(new UnitRoot(
                    name, UnitKind.PLUGIN, store.pluginsDir().resolve(name).toAbsolutePath()));
        }
        if (store.containsHarness(name)) {
            return Optional.of(new UnitRoot(
                    name, UnitKind.HARNESS, store.harnessesDir().resolve(name).toAbsolutePath()));
        }
        if (store.containsDocRepo(name)) {
            return Optional.of(new UnitRoot(
                    name, UnitKind.DOC, store.docsDir().resolve(name).toAbsolutePath()));
        }
        if (store.contains(name)) {
            return Optional.of(new UnitRoot(
                    name, UnitKind.SKILL, store.skillDir(name).toAbsolutePath()));
        }
        return Optional.empty();
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

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
