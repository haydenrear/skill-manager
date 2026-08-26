//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared fixture and readers for the {@code artifact-dag} graph, which drives
 * the real {@code skill-manager} CLI over a home it built itself and asserts
 * the derived-artifact DAG the epic in #100 is about: ARTI-03 (identity),
 * ARTI-04 (every backend fingerprints its inputs), ARTI-05 (edges), ARTI-06
 * (per-artifact repair), ARTI-07 (a lazy clone) and ARTI-08 (teardown).
 *
 * <h2>Why the fixture is a skill-script that writes a cache tree</h2>
 *
 * <p>The DAG's interesting shape is a shim and the tree it runs out of: the
 * SAME install produces both, {@code cli-lock.toml} records ONE fingerprint
 * for the pair, and {@code ArtifactBackfill.provisionedTrees} credits the tree
 * to the shim by reading the shim's body rather than by parsing a naming
 * convention. Nothing else in a home carries that two-output edge, and
 * {@code artifacts stale} naming both from one moved input is exactly what
 * ARTI-05 shipped.
 *
 * <p>So every fixture unit here declares one {@code skill-script:} CLI
 * dependency whose installer writes
 * {@code cache/skill-script-<unit>-<tool>/bin/<tool>} and then a
 * {@code bin/cli/<tool>} wrapper that {@code exec}s that absolute path — the
 * shape a real home already holds seven times over
 * ({@code bin/cli/computeq} → {@code cache/skill-script-deploy-helm-computeq}).
 *
 * <p><b>And it is the only backend that can be exercised without a network or
 * a docker daemon.</b> {@code brew}, {@code npm}, {@code pip} and {@code uv}
 * resolve a package from the internet or find a tool on the host's PATH;
 * {@code tar} fetches a URL. A skill-script installs INTO the home
 * unconditionally, from bytes the graph wrote. That is a real limit on what
 * {@code every.lock.row.fingerprinted} can claim, and it is stated in that
 * node's javadoc rather than papered over here.
 *
 * <h2>Every subprocess gets the sandboxed agent homes</h2>
 *
 * <p>See {@link #sm}: the environment comes from {@code SmEnv} and nothing
 * else. {@code sources/sandbox/SandboxEnvContract.java} is the oracle that
 * keeps it that way — it fails the build when any file but
 * {@code sources/lib/SmEnv.java} writes {@code SKILL_MANAGER_HOME} into a
 * child, and when a file that names this repo's CLI does not reach the helper
 * through its {@code //SOURCES} closure.
 *
 * <h2>Each node builds its own home</h2>
 *
 * <p>Under {@code env.prepared}'s per-run temp root, so
 * {@code sources/common/HomeFixpointLaw.java} can find them ({@code insideSandbox}
 * only considers homes under {@code java.io.tmpdir}) and so no node reads a
 * lock state another node wrote. The homes are laid out as
 * {@code <workspace>/.skill-manager} with {@code .claude}/{@code .codex}/
 * {@code .gemini} BESIDE the store, which is the layout a real per-checkout
 * home has and the one that keeps a home's own bindings inside itself.
 */
final class ArtifactDagSupport {

    /** The unit whose artifacts each node edits, rebuilds or clones. */
    static final String UNIT_A = "ad-alpha-unit";
    static final String TOOL_A = "ad-alpha-tool";
    /** The SIBLING: present in every home, never the target of any repair. */
    static final String UNIT_B = "ad-beta-unit";
    static final String TOOL_B = "ad-beta-tool";
    /** Installed and then uninstalled, to observe the teardown edges. */
    static final String UNIT_T = "ad-transient-unit";
    static final String TOOL_T = "ad-transient-tool";

    /** What the installer makes the tool print before an edit, and after. */
    static final String MARKER_BEFORE = "v1";
    static final String MARKER_AFTER = "v2";

    private ArtifactDagSupport() {}

    // ------------------------------------------------------------------ ids

    /** {@code cli-shim:skill-script/<tool>} — {@code ArtifactIds.cliShim}. */
    static String shimId(String tool) {
        return "cli-shim:skill-script/" + tool;
    }

    /** {@code provisioned-tree:cache/<dir>} — {@code ArtifactIds.provisionedTree}. */
    static String treeId(String unit, String tool) {
        return "provisioned-tree:cache/" + treeDirName(unit, tool);
    }

    /** {@code unit-store:<unit>}. */
    static String unitStoreId(String unit) {
        return "unit-store:" + unit;
    }

    /** The cache directory name the fixture's installer chooses for itself. */
    static String treeDirName(String unit, String tool) {
        return "skill-script-" + unit + "-" + tool;
    }

    // ------------------------------------------------------------- layout

    /**
     * A private workspace for one node, under {@code env.prepared}'s temp root.
     *
     * <p>Returns the WORKSPACE; the store is {@link #storeOf}. Both the agent
     * roots and the store live under it, so the home's projections are its own.
     */
    static Path workspace(NodeContext ctx, String label) throws IOException {
        String envHome = ctx.get("env.prepared", "home").orElse(null);
        if (envHome == null || envHome.isBlank()) {
            throw new IOException("env.prepared published no home");
        }
        Path ws = Path.of(envHome).resolve("artifact-dag").resolve(label);
        deleteTree(ws);
        Files.createDirectories(ws.resolve(".claude"));
        Files.createDirectories(ws.resolve(".codex"));
        Files.createDirectories(ws.resolve(".gemini"));
        Path store = storeOf(ws);
        Files.createDirectories(store);
        // The permissive test policy, written the way EnvPrepared writes it:
        // production gates hooks and executable commands behind a prompt, and a
        // prompt in a non-interactive node hangs until the node's timeout. The
        // CLI's writeDefaultIfMissing skips when the file is already there.
        Files.writeString(store.resolve("policy.toml"), """
                # artifact-dag graph policy: unattended installs, like EnvPrepared's.
                require_confirmation = false
                [install]
                require_confirmation_for_hooks = false
                require_confirmation_for_mcp = false
                require_confirmation_for_cli_deps = false
                require_confirmation_for_executable_commands = false
                """);
        return ws;
    }

    /** The store root of a workspace: {@code <workspace>/.skill-manager}. */
    static Path storeOf(Path workspace) {
        return workspace.resolve(".skill-manager");
    }

    /** The workspace a store root belongs to — its parent, by construction. */
    static Path workspaceOf(Path store) {
        Path parent = store.toAbsolutePath().normalize().getParent();
        return parent == null ? store : parent;
    }

    // ------------------------------------------------------------- fixture

    /**
     * The installer every fixture unit ships. It writes its own cache tree and
     * then a wrapper that execs into it by ABSOLUTE path, which is what makes
     * the tree a {@code provisioned-tree} artifact credited to this shim.
     */
    private static final String INSTALLER = """
            #!/usr/bin/env bash
            set -euo pipefail
            tree="$SKILL_MANAGER_HOME/cache/skill-script-@UNIT@-@TOOL@"
            mkdir -p "$tree/bin" "$SKILL_MANAGER_HOME/bin/cli"
            {
              echo '#!/usr/bin/env bash'
              echo 'echo "@TOOL@ @MARKER@"'
            } > "$tree/bin/@TOOL@"
            chmod +x "$tree/bin/@TOOL@"
            {
              echo '#!/usr/bin/env bash'
              echo "exec \\"$tree/bin/@TOOL@\\" \\"\\$@\\""
            } > "$SKILL_MANAGER_HOME/bin/cli/@TOOL@"
            chmod +x "$SKILL_MANAGER_HOME/bin/cli/@TOOL@"
            """;

    private static final String SKILL_MD = """
            ---
            name: @UNIT@
            description: artifact-dag graph fixture declaring the skill-script CLI dep @TOOL@.
            ---

            # @UNIT@

            A fixture. Its one CLI dependency writes a cache tree and a wrapper
            into it, so the home holds the shim/tree pair the DAG is about.
            """;

    private static final String MANIFEST = """
            [skill]
            name = "@UNIT@"
            version = "0.1.0"
            description = "artifact-dag graph fixture declaring the skill-script CLI dep @TOOL@."

            [[cli_dependencies]]
            spec = "skill-script:@TOOL@"
            on_path = "@TOOL@"

            [cli_dependencies.install.any]
            script = "install-@TOOL@.sh"
            binary = "@TOOL@"
            """;

    /**
     * Scaffold one installable unit under {@code parent} and return its dir.
     *
     * <p>The installer goes under {@code skill-scripts/} — the ONLY directory
     * the resolver looks in, and the one whose bytes the fingerprint is taken
     * over ("skill-scripts/ tree bytes + script path + declared args").
     */
    static Path scaffoldUnit(Path parent, String unit, String tool) throws IOException {
        Path dir = parent.resolve(unit);
        Files.createDirectories(dir.resolve("skill-scripts"));
        Files.writeString(dir.resolve("SKILL.md"), fill(SKILL_MD, unit, tool));
        Files.writeString(dir.resolve("skill-manager.toml"), fill(MANIFEST, unit, tool));
        Path script = dir.resolve("skill-scripts").resolve("install-" + tool + ".sh");
        writeExecutable(script, fill(INSTALLER, unit, tool));
        return dir;
    }

    private static String fill(String template, String unit, String tool) {
        return template.replace("@UNIT@", unit)
                .replace("@TOOL@", tool)
                .replace("@MARKER@", MARKER_BEFORE);
    }

    /** The installed unit's own copy of its installer, inside the home. */
    static Path installedScript(Path store, String unit, String tool) {
        return store.resolve("skills").resolve(unit)
                .resolve("skill-scripts").resolve("install-" + tool + ".sh");
    }

    /**
     * Edit a unit's {@code skill-scripts/} tree IN THE HOME, so its declared
     * inputs move without anything being installed. Returns false when the
     * marker was not found, which the caller reports rather than swallows.
     */
    static boolean editInstalledScript(Path store, String unit, String tool) throws IOException {
        Path script = installedScript(store, unit, tool);
        if (!Files.isRegularFile(script)) return false;
        String body = Files.readString(script);
        String edited = body.replace("\"" + tool + " " + MARKER_BEFORE + "\"",
                "\"" + tool + " " + MARKER_AFTER + "\"");
        if (edited.equals(body)) return false;
        Files.writeString(script, edited);
        return true;
    }

    // ------------------------------------------------------------- process

    /** Run the real CLI against the home at {@code store}. */
    static ProcessRecord sm(NodeContext ctx, String label, Path store, String... args) {
        List<String> command = new ArrayList<>();
        command.add(SmEnv.cli().toString());
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        SmEnv.apply(pb, store.toString(), SmEnv.repoRoot().toString(),
                SmEnv.sandboxUnder(workspaceOf(store)));
        return Procs.run(ctx, label, pb);
    }

    /** Run an arbitrary executable — a generated shim — with the same sandbox. */
    static ProcessRecord exec(NodeContext ctx, String label, Path store, List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        SmEnv.apply(pb, store.toString(), SmEnv.repoRoot().toString(),
                SmEnv.sandboxUnder(workspaceOf(store)));
        return Procs.run(ctx, label, pb);
    }

    /**
     * The captured output of one subprocess.
     *
     * <p>{@code ProcessRecord.logPath()} is RELATIVE to the run's report dir —
     * {@code Procs} does that on purpose so the envelope JSON stays portable —
     * so it is resolved against {@link NodeContext#reportDir()} here rather
     * than read as an absolute path that does not exist.
     */
    static String log(NodeContext ctx, ProcessRecord rec) {
        try {
            String rel = rec == null ? null : rec.logPath();
            if (rel == null || rel.isBlank()) return "";
            Path p = Path.of(rel);
            if (!p.isAbsolute() && ctx.reportDir() != null) p = ctx.reportDir().resolve(p);
            return Files.isRegularFile(p) ? Files.readString(p) : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- json

    /**
     * Every {@code "id"} the CLI's JSON named, in order.
     *
     * <p>Both spellings are accepted because the CLI pretty-prints
     * ({@code "id" : "x"}) while a future compact writer would not.
     */
    static List<String> ids(String json) {
        List<String> out = new ArrayList<>();
        for (String needle : new String[] {"\"id\" : \"", "\"id\":\""}) {
            int from = 0;
            while (true) {
                int at = json.indexOf(needle, from);
                if (at < 0) break;
                int start = at + needle.length();
                int end = json.indexOf('"', start);
                if (end < 0) break;
                out.add(json.substring(start, end));
                from = end;
            }
        }
        return out;
    }

    /**
     * The slice of {@code json} that belongs to one artifact: from its
     * {@code "id"} to the next one, or to the end.
     *
     * <p>A per-artifact field has to be read inside its own object, not by a
     * global search — {@code install_fingerprint} appears once per shim and a
     * whole-document lookup would report the first one for all of them.
     */
    static String sectionFor(String json, String id) {
        int at = json.indexOf("\"" + id + "\"");
        if (at < 0) return "";
        int next = json.length();
        for (String needle : new String[] {"\"id\" : \"", "\"id\":\""}) {
            int candidate = json.indexOf(needle, at + id.length());
            if (candidate >= 0 && candidate < next) next = candidate;
        }
        return json.substring(at, next);
    }

    /** String value of {@code "key":"..."} or {@code "key" : "..."}, else "". */
    static String jsonString(String json, String key) {
        for (String needle : new String[] {"\"" + key + "\" : \"", "\"" + key + "\":\""}) {
            int at = json.indexOf(needle);
            if (at < 0) continue;
            int from = at + needle.length();
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) { sb.append(json.charAt(++i)); continue; }
                if (c == '"') break;
                sb.append(c);
            }
            return sb.toString();
        }
        return "";
    }

    /** Integer value of {@code "key" : N}, or -1. */
    static int jsonInt(String json, String key) {
        for (String needle : new String[] {"\"" + key + "\" : ", "\"" + key + "\":"}) {
            int at = json.indexOf(needle);
            if (at < 0) continue;
            int from = at + needle.length();
            int to = from;
            while (to < json.length()
                    && (Character.isDigit(json.charAt(to)) || json.charAt(to) == '-')) to++;
            if (to == from) continue;
            return Integer.parseInt(json.substring(from, to));
        }
        return -1;
    }

    // ------------------------------------------------------------ lock file

    /** Every {@code ["backend"."tool"]} row of {@code cli-lock.toml}, as text. */
    static List<String> cliLockRows(Path store) {
        List<String> rows = new ArrayList<>();
        Path lock = store.resolve("cli-lock.toml");
        if (!Files.isRegularFile(lock)) return rows;
        String body;
        try {
            body = Files.readString(lock);
        } catch (IOException e) {
            return rows;
        }
        StringBuilder cur = null;
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("[")) {
                if (cur != null) rows.add(cur.toString());
                cur = new StringBuilder(line).append('\n');
                continue;
            }
            if (cur != null) cur.append(line).append('\n');
        }
        if (cur != null) rows.add(cur.toString());
        return rows;
    }

    /** The value of {@code key = "..."} inside one lock row, else "". */
    static String lockValue(String row, String key) {
        for (String line : row.split("\n", -1)) {
            String s = line.strip();
            if (!s.startsWith(key)) continue;
            int eq = s.indexOf('=');
            if (eq < 0) continue;
            String v = s.substring(eq + 1).strip();
            if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                return v.substring(1, v.length() - 1);
            }
            return v;
        }
        return "";
    }

    // ---------------------------------------------------------- filesystem

    /** Whether {@code cache/} holds any provisioned tree at all. */
    static List<String> cacheTrees(Path store) {
        return names(store.resolve("cache"));
    }

    /** Immediate child names of {@code dir}, sorted; empty when absent. */
    static List<String> names(Path dir) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return out;
        try (var entries = Files.list(dir)) {
            entries.map(p -> p.getFileName().toString()).sorted().forEach(out::add);
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    /** How many skill-script installs this home has logged. */
    static int skillScriptRuns(Path store) {
        return names(store.resolve("logs").resolve("skill-scripts")).size();
    }

    static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    /**
     * A digest over everything an install DERIVES: the shims, the trees, the
     * unit stores and the CLI lock. Deliberately narrower than the whole home,
     * so "reading the census rebuilt nothing" is not falsified by a log line.
     */
    static String derivedDigest(Path store) throws IOException {
        return digestOf(store, List.of("bin", "cache", "skills", "cli-lock.toml"));
    }

    /**
     * A digest over the whole home EXCEPT its journals.
     *
     * <p>{@code logs/}, {@code audit.log} and {@code tmp/} are append-only
     * records of what happened; an install/uninstall pair is not expected to
     * leave them byte-identical and asserting that it does would be asserting
     * that nothing was recorded. Everything else — {@code skills/},
     * {@code bin/}, {@code cache/}, {@code installed/}, both lock files, the
     * generated marketplace — is state the pair is expected to restore.
     */
    static String homeDigest(Path store) throws IOException {
        List<String> tops = names(store);
        List<String> included = new ArrayList<>();
        for (String top : tops) {
            if (JOURNALS.contains(top)) continue;
            included.add(top);
        }
        return digestOf(store, included);
    }

    /** The top-level entries a home rewrites as a matter of record-keeping. */
    static final Set<String> JOURNALS = Set.of("logs", "audit.log", "tmp");

    /** The same inventory, readable, for naming what moved in a failure. */
    static List<String> homeInventory(Path store) throws IOException {
        List<String> out = new ArrayList<>();
        for (String top : names(store)) {
            if (JOURNALS.contains(top)) continue;
            inventoryInto(store, store.resolve(top), out);
        }
        out.sort(String::compareTo);
        return out;
    }

    private static String digestOf(Path root, List<String> tops) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        for (String top : tops) {
            digestInto(root, root.resolve(top), digest);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16))
                    .append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static void digestInto(Path base, Path current, MessageDigest digest)
            throws IOException {
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
        String rel = base.equals(current) ? "" : base.relativize(current).toString();
        if (Files.isSymbolicLink(current)) {
            frame(digest, "L", rel);
            digest.update(Files.readSymbolicLink(current).toString()
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            frame(digest, "D", rel);
            for (Path child : listSorted(current)) digestInto(base, child, digest);
            return;
        }
        frame(digest, "F", rel);
        try (InputStream in = Files.newInputStream(current)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
    }

    private static void frame(MessageDigest digest, String kind, String rel) {
        byte[] path = rel.getBytes(StandardCharsets.UTF_8);
        digest.update((kind + "\0" + path.length + "\0").getBytes(StandardCharsets.UTF_8));
        digest.update(path);
    }

    private static void inventoryInto(Path base, Path current, List<String> out)
            throws IOException {
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
        String rel = base.equals(current) ? "." : base.relativize(current).toString();
        if (Files.isSymbolicLink(current)) {
            out.add("L " + rel + " -> " + Files.readSymbolicLink(current));
            return;
        }
        if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            out.add("D " + rel);
            for (Path child : listSorted(current)) inventoryInto(base, child, out);
            return;
        }
        out.add("F " + rel + " " + Files.size(current));
    }

    private static List<Path> listSorted(Path dir) throws IOException {
        List<Path> children = new ArrayList<>();
        try (var entries = Files.list(dir)) {
            entries.forEach(children::add);
        }
        children.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return children;
    }

    /** Entries in {@code a} that are not in {@code b}, capped for readability. */
    static List<String> only(List<String> a, List<String> b, int cap) {
        List<String> out = new ArrayList<>();
        for (String s : a) {
            if (b.contains(s)) continue;
            out.add(s);
            if (out.size() >= cap) break;
        }
        return out;
    }

    static void writeExecutable(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        try {
            Set<PosixFilePermission> perms =
                    new LinkedHashSet<>(Files.getPosixFilePermissions(path));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem; the install will report it
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a re-run inside the same env; the caller will see the state
                }
            });
        }
    }
}
