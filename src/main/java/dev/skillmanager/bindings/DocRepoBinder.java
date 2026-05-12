package dev.skillmanager.bindings;

import dev.skillmanager.model.DocSource;
import dev.skillmanager.model.DocUnit;
import dev.skillmanager.model.UnitKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Plans doc-repo (#48) bindings from a {@link DocUnit} and a target
 * root: one {@link Binding} per selected source, with each binding
 * carrying a {@link ProjectionKind#MANAGED_COPY} for the tracked
 * file copy plus one {@link ProjectionKind#IMPORT_DIRECTIVE} per
 * agent the source projects into.
 *
 * <p>The plan output is what {@code BindCommand} converts into a
 * sequence of {@link dev.skillmanager.effects.SkillEffect.MaterializeProjection}
 * + {@link dev.skillmanager.effects.SkillEffect.CreateBinding}
 * effects.
 *
 * <p>Source layout assumed in the store:
 * <pre>
 *   &lt;store&gt;/docs/&lt;unitName&gt;/                 ← DocUnit#sourcePath
 *     claude-md/&lt;file&gt;                    ← per DocSource#file
 *     skill-manager.toml
 * </pre>
 *
 * <p>Target layout produced under {@code targetRoot}:
 * <pre>
 *   docs/agents/&lt;basename&gt;                 ← MANAGED_COPY destPath
 *   CLAUDE.md                              ← IMPORT_DIRECTIVE destPath (claude)
 *   AGENTS.md                              ← IMPORT_DIRECTIVE destPath (codex)
 * </pre>
 */
public final class DocRepoBinder {

    private DocRepoBinder() {}

    /** Where the tracked copies land under the target root. */
    public static final String DOCS_SUBDIR = "docs/agents";

    /**
     * Plan one binding per source. When {@code selectedSourceId} is
     * non-null, only that source is included; when null, every source
     * declared in the doc-repo is bound.
     *
     * @param docUnit    the resolved DocUnit (sourcePath = store dir)
     * @param targetRoot the destination root (the project / sandbox)
     * @param selectedSourceId optional sub-element id; null = all
     * @param policy     conflict policy for materialization
     * @param source     binding source — {@link BindingSource#EXPLICIT}
     *                   for {@code bind} CLI; {@link BindingSource#HARNESS}
     *                   when invoked from a harness template instantiator
     */
    public static Plan plan(DocUnit docUnit, Path targetRoot,
                            String selectedSourceId, ConflictPolicy policy,
                            BindingSource source) throws IOException {
        return plan(docUnit, targetRoot, selectedSourceId, policy, source,
                s -> BindingStore.newBindingId());
    }

    /**
     * Same as {@link #plan(DocUnit, Path, String, ConflictPolicy, BindingSource)}
     * but with a custom binding id supplier. Used by harness
     * instantiation (#47) to stamp stable, instance-scoped ids of
     * the form {@code harness:<instanceId>:<repoName>:<sourceId>} so
     * re-instantiating the same template overwrites the existing
     * bindings rather than accumulating duplicates.
     */
    public static Plan plan(DocUnit docUnit, Path targetRoot,
                            String selectedSourceId, ConflictPolicy policy,
                            BindingSource source,
                            Function<DocSource, String> bindingIdFn) throws IOException {
        Path repoDir = docUnit.sourcePath();
        List<DocSource> selected = new ArrayList<>();
        if (selectedSourceId == null) {
            selected.addAll(docUnit.sources());
        } else {
            DocSource s = docUnit.findSource(selectedSourceId).orElse(null);
            if (s == null) {
                throw new IOException("doc-repo " + docUnit.name()
                        + " has no source '" + selectedSourceId + "'");
            }
            selected.add(s);
        }
        if (selected.isEmpty()) {
            throw new IOException("doc-repo " + docUnit.name() + " has no [[sources]] to bind");
        }
        List<Binding> bindings = new ArrayList<>(selected.size());
        for (DocSource src : selected) {
            bindings.add(planOne(docUnit, src, repoDir, targetRoot, policy, source,
                    bindingIdFn.apply(src)));
        }
        return new Plan(bindings);
    }

    private static Binding planOne(DocUnit docUnit, DocSource src, Path repoDir,
                                   Path targetRoot, ConflictPolicy policy,
                                   BindingSource source, String bindingId) throws IOException {
        String basename = basename(src.file());
        Path storeSource = repoDir.resolve(src.file());
        Path copyDest = targetRoot.resolve(DOCS_SUBDIR).resolve(basename);
        String boundHash = Sha256.hashFile(storeSource);

        List<Projection> rows = new ArrayList<>();

        // RENAME_EXISTING for the tracked copy if dest occupied. Skip
        // for symlinks-only kinds — irrelevant for managed copies of a
        // bytes-only file, but the binder mirrors the SkillUnit flow
        // for consistency.
        if (policy == ConflictPolicy.RENAME_EXISTING
                && Files.exists(copyDest, LinkOption.NOFOLLOW_LINKS)) {
            Path bak = copyDest.resolveSibling(basename + ".skill-manager-backup-" + ts());
            rows.add(new Projection(bindingId, null, bak,
                    ProjectionKind.RENAMED_ORIGINAL_BACKUP, copyDest.toString(), null));
        }
        rows.add(new Projection(bindingId, storeSource, copyDest,
                ProjectionKind.MANAGED_COPY, null, boundHash));

        // Per-agent IMPORT_DIRECTIVE rows. Same agent listed twice in
        // the manifest dedupes here — agents=["claude","claude"] is a
        // manifest bug we mask with a LinkedHashSet rather than fail on.
        Set<String> agents = new LinkedHashSet<>(src.agents());
        Path relImport = Path.of(DOCS_SUBDIR).resolve(basename);
        for (String agent : agents) {
            Path mdFile = targetRoot.resolve(agentMarkdownFile(agent));
            rows.add(new Projection(bindingId, relImport, mdFile,
                    ProjectionKind.IMPORT_DIRECTIVE, null, null));
        }

        return new Binding(
                bindingId,
                docUnit.name(),
                UnitKind.DOC,
                src.id(),               // sub-element = the source id
                targetRoot,
                policy,
                BindingStore.nowIso(),
                source,
                rows);
    }

    /** Result of {@link #plan}: zero or more bindings to materialize. */
    public record Plan(List<Binding> bindings) {
        public Plan {
            bindings = List.copyOf(bindings);
        }
    }

    /** Map an agent id to the markdown filename the import line is written into. */
    public static String agentMarkdownFile(String agentId) {
        return switch (agentId) {
            case "claude" -> "CLAUDE.md";
            case "codex" -> "AGENTS.md";
            // Future agents land here as new switch arms. Unknown ids
            // get a {@code AGENTS-<id>.md} fallback so we don't silently
            // collide with a known file.
            default -> "AGENTS-" + agentId + ".md";
        };
    }

    private static String basename(String filePath) {
        int slash = filePath.lastIndexOf('/');
        return slash < 0 ? filePath : filePath.substring(slash + 1);
    }

    private static String ts() {
        return OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
    }
}
