package dev.skillmanager.artifacts;

import java.nio.file.Path;

/**
 * The artifact id grammar, and the input-reference grammar beside it.
 *
 * <h2>{@code <kind>:<key>} — and why it has to be home-stable</h2>
 *
 * <p>An id is {@link ArtifactKind#id()}, a colon, and a key built only from
 * facts that survive being copied to another root. It is a readable string
 * rather than a digest because two different consumers have to hold it:
 * {@code artifacts show <id>} in somebody's terminal, and ARTI-09's Python
 * kernel over the JSON. Neither is served by a hash.
 *
 * <p><b>The same artifact must have the same id in the root home and in a
 * worktree clone</b>, or the DAG cannot cross a tier and ARTI-07 has nothing to
 * reason with: "this ticket home is missing {@code provisioned-tree:cache/…}"
 * is only a sentence if the tree has the same name in the home that has it. So
 * every key below is a function of {@code (kind, unit name | backend+package |
 * store-root+directory name | bindingId + projection kind + the destination's
 * offset from its binding's target root)} — and of nothing else. In particular
 * a key never contains:
 *
 * <ul>
 *   <li>an absolute path — {@code /Users/a/.skill-manager/skills/x} and
 *       {@code /tmp/wt/.skill-manager/skills/x} are the same artifact;</li>
 *   <li>a timestamp — {@code installed_at} differs per home by construction;</li>
 *   <li>a hash of the content — the whole point is that the content may be
 *       stale, and an id that changed when the bytes changed would make
 *       "this artifact is stale" unsayable.</li>
 * </ul>
 *
 * <p>Binding ids are safe to key on and are the least obvious case, so it is
 * worth saying why: a binding id is authored as {@code default:claude:discovery}
 * or {@code <owner>:<repo>:<sub-element>:bind}, is persisted in
 * {@code installed/<u>.projections.json}, and {@code HomeCloner} copies that
 * ledger and re-anchors the PATHS inside it without touching the id. The
 * absolute destination of a projection does change between homes; its offset
 * from the binding's own target root does not, which is why the key uses the
 * offset ({@link #destKey}) and the listing resolves the full path live.
 *
 * <h2>Input references</h2>
 *
 * <p>An input is a typed string, not a path, so that ARTI-05 can turn a list of
 * them into real edges without re-parsing filesystem layout:
 *
 * <pre>
 *   unit:&lt;name&gt;                    the unit's store bytes
 *   store:&lt;home-relative path&gt;      bytes inside this home
 *   spec:&lt;declared spec&gt;           a declared CLI package spec, verbatim
 *   git:&lt;url&gt;[@&lt;ref&gt;]              an upstream git source
 *   binding:&lt;bindingId&gt;             a persisted binding
 *   record:&lt;home-relative path&gt;     another state record
 * </pre>
 *
 * <p>Every scheme is home-independent for the same reason the ids are.
 */
public final class ArtifactIds {

    private ArtifactIds() {}

    public static String of(ArtifactKind kind, String key) {
        return kind.id() + ":" + key;
    }

    public static String unitStore(String unitName) {
        return of(ArtifactKind.UNIT_STORE, unitName);
    }

    public static String cliShim(String backend, String tool) {
        return of(ArtifactKind.CLI_SHIM, backend + "/" + tool);
    }

    public static String provisionedTree(String storeRoot, String directoryName) {
        return of(ArtifactKind.PROVISIONED_TREE, storeRoot + "/" + directoryName);
    }

    /**
     * {@code projection:<bindingId>#<kind>/<dest relative to the binding's target root>}.
     *
     * <p>The binding id scopes the row to one unit and one target root; the
     * projection kind and the destination separate the rows within it. Both
     * halves of that are needed and neither is obvious:
     *
     * <ul>
     *   <li>a doc binding emits one {@code MANAGED_COPY} and three
     *       {@code IMPORT_DIRECTIVE} rows, so the kind alone does not separate
     *       them and the destination does;</li>
     *   <li>a project binding emits three {@code SYMLINK} rows into
     *       {@code .claude/skills/<u>}, {@code .codex/skills/<u>} and
     *       {@code .gemini/skills/<u>} — the same leaf name three times, so the
     *       LEAF alone does not separate them and the path relative to the
     *       target root does.</li>
     * </ul>
     *
     * <p>Relative to the target root rather than absolute, for the same reason
     * everything else here is: the absolute destination differs between a home
     * and its clone, and the offset from the binding's own root does not.
     */
    public static String projection(String bindingId, String projectionKind, String destKey) {
        return of(ArtifactKind.PROJECTION, bindingId + "#" + projectionKind + "/" + destKey);
    }

    /**
     * The home-stable key for a projection's destination: its offset from the
     * binding's target root, falling back to the leaf name when the row does
     * not sit under it.
     */
    public static String destKey(Path targetRoot, Path dest) {
        if (dest == null) return "?";
        if (targetRoot != null) {
            Path root = targetRoot.toAbsolutePath().normalize();
            Path target = dest.toAbsolutePath().normalize();
            if (target.startsWith(root) && !target.equals(root)) {
                return root.relativize(target).toString().replace('\\', '/');
            }
        }
        return dest.getFileName().toString();
    }

    public static String marketplaceEntry(String pluginName) {
        return of(ArtifactKind.MARKETPLACE_ENTRY, pluginName);
    }

    public static String harnessInstance(String instanceId) {
        return of(ArtifactKind.HARNESS_INSTANCE, instanceId);
    }

    public static String mcpRegistration(String serverId) {
        return of(ArtifactKind.MCP_REGISTRATION, serverId);
    }

    public static String docImport(String docUnitName) {
        return of(ArtifactKind.DOC_IMPORT, docUnitName);
    }

    public static String unitDigest(String unitName) {
        return of(ArtifactKind.UNIT_DIGEST, unitName);
    }

    // ------------------------------------------------------------ input refs

    public static String unitInput(String unitName) { return "unit:" + unitName; }

    public static String storeInput(String homeRelative) { return "store:" + homeRelative; }

    /**
     * {@code spec:<the declared spec verbatim>}. The spec already carries its
     * own backend prefix ({@code pip:ruff==0.6}), so prefixing the backend a
     * second time would produce {@code spec:pip:pip:ruff==0.6} — a reference no
     * consumer could match back against {@code cli-lock.toml}.
     */
    public static String specInput(String spec) {
        return "spec:" + spec;
    }

    public static String gitInput(String url, String ref) {
        return "git:" + url + (ref == null || ref.isBlank() ? "" : "@" + ref);
    }

    public static String bindingInput(String bindingId) { return "binding:" + bindingId; }

    public static String recordInput(String homeRelative) { return "record:" + homeRelative; }

    // ------------------------------------------------------------- utilities

    /**
     * {@code path} expressed relative to {@code homeRoot} with {@code /}
     * separators, or null when it is not inside the home. Null is the signal
     * that a path must NOT be written to the ledger.
     */
    public static String homeRelative(Path homeRoot, Path path) {
        if (homeRoot == null || path == null) return null;
        Path root = homeRoot.toAbsolutePath().normalize();
        Path target = path.toAbsolutePath().normalize();
        if (!target.startsWith(root)) return null;
        String relative = root.relativize(target).toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative;
    }
}
