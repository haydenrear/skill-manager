//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared helpers for the {@code project-child-home} graph.
 *
 * <p>The graph drives the real {@code skill-manager} CLI against throwaway
 * projects and asserts the child-home materialization contract from
 * {@code specs/desired_program_model/External.tla}:
 *
 * <ul>
 *   <li>{@code ChildHomeWritesNeverReachTheParentStore} — independence.</li>
 *   <li>{@code AgentEditedChildUnitsAreNeverDestroyed} — no silent destruction.</li>
 *   <li>{@code EveryPassReportsExactlyTheHeldBackUnits} — reporting.</li>
 *   <li>{@code UnmodifiedChildUnitsConvergeOnTheirSource} — convergence.</li>
 *   <li>{@code ResolveLeavesOnlyClaimedOrHeldBackUnits} — prune scope.</li>
 *   <li>{@code InFlightMaterializationLeavesTheChildUnitIntact} — atomicity.</li>
 * </ul>
 *
 * <p><b>Everything here reads the filesystem with {@code NOFOLLOW_LINKS}.</b>
 * That is the whole point of this graph: a check that follows symlinks cannot
 * distinguish "the child home holds an independent copy" from "the child home
 * holds a symlink at the parent store", which is exactly the distinction the
 * copy-based materializer introduced.
 */
final class ChildHomeSupport {

    /** Units the fixture project depends on. */
    static final String UNIT_A = "chm-unit-a";
    static final String UNIT_B = "chm-unit-b";
    static final String UNIT_C = "chm-unit-c";
    static final String UNIT_D = "chm-unit-d";
    /** Installed in the parent store but never a project dependency; only ever
     *  reached through a symlink inside {@link #UNIT_B} / {@link #UNIT_D}. */
    static final String LINKED_SOURCE = "chm-linked-source";

    static final String PROJECT_NAME = "chm-child-home-project";

    private ChildHomeSupport() {}

    // ------------------------------------------------------------- process

    static Path repoRoot() {
        return Path.of(System.getProperty("user.dir")).resolve("..").normalize().toAbsolutePath();
    }

    static Path skillManager() {
        return repoRoot().resolve("skill-manager");
    }

    /**
     * Runs the real CLI against {@code home} with output captured to a node log.
     *
     * <p>The environment is {@link SmEnv}'s and not this file's. It used to be
     * this file's, and it was the incomplete one of the four copies: no
     * {@code CLAUDE_CONFIG_DIR}, and each agent variable set only
     * {@code ifPresent} — so a graph that reached here without
     * {@code env.prepared} silently spawned an unsandboxed child, which is
     * exactly issue #18's state.
     */
    static ProcessRecord sm(NodeContext ctx, String label, String home, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = skillManager().toString();
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(command);
        SmEnv.apply(ctx, pb, home);
        return Procs.run(ctx, label, pb);
    }

    static String log(NodeContext ctx, String label) {
        try {
            return Files.readString(Procs.logFile(ctx, label));
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- json

    /**
     * The CLI's {@code --json} summary is the last JSON object line of the run;
     * everything before it is human-readable progress output on the same stream.
     */
    static String jsonSummary(String logText) {
        String found = "";
        for (String line : logText.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("{\"name\":")) found = trimmed;
        }
        return found;
    }

    /**
     * The set of unit labels ({@code skill:chm-unit-a}) the pass reported as
     * held back. Deliberately parsed as a SET so a node can assert exact
     * equality: {@code EveryPassReportsExactlyTheHeldBackUnits} forbids both
     * under-reporting and naming a unit that was not held back.
     */
    static Set<String> heldBackUnits(String jsonSummary) {
        Set<String> out = new LinkedHashSet<>();
        int start = jsonSummary.indexOf("\"heldBack\":[");
        if (start < 0) return out;
        int open = jsonSummary.indexOf('[', start);
        int depth = 0;
        int end = -1;
        for (int i = open; i < jsonSummary.length(); i++) {
            char c = jsonSummary.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) { end = i; break; }
            }
        }
        if (end < 0) return out;
        String body = jsonSummary.substring(open, end + 1);
        String key = "\"unit\":\"";
        int at = body.indexOf(key);
        while (at >= 0) {
            int from = at + key.length();
            int to = body.indexOf('"', from);
            if (to < 0) break;
            out.add(body.substring(from, to));
            at = body.indexOf(key, to);
        }
        return out;
    }

    // ---------------------------------------------------------- filesystem

    static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return "";
        }
    }

    static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    static Path childHome(Path projectDir) {
        return projectDir.resolve(".skill-manager");
    }

    static Path childUnit(Path projectDir, String name) {
        return childHome(projectDir).resolve("skills").resolve(name);
    }

    static Path parentUnit(Path home, String name) {
        return home.resolve("skills").resolve(name);
    }

    static Path materializationRecord(Path projectDir, String name) {
        return childHome(projectDir).resolve(".materialization/skill").resolve(name + ".json");
    }

    /** Child unit names currently present, by directory listing (NOFOLLOW). */
    static List<String> childUnitNames(Path projectDir) {
        Path skills = childHome(projectDir).resolve("skills");
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(skills, LinkOption.NOFOLLOW_LINKS)) return out;
        try (var entries = Files.list(skills)) {
            entries.map(p -> p.getFileName().toString()).sorted().forEach(out::add);
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    /**
     * True when {@code path} is a real directory and not a symlink. This is the
     * check {@code Files.isDirectory(path)} cannot make: that call follows
     * links, so it is equally true of a symlink at the parent store.
     */
    static boolean isRealDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path);
    }

    /** True when {@code path} is a real file and not a symlink. */
    static boolean isRealFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path);
    }

    /**
     * True when {@code path}'s real location lies inside {@code root} — used to
     * prove a child unit is NOT the parent store's unit under another name.
     */
    static boolean realPathInside(Path path, Path root) {
        try {
            return path.toRealPath().startsWith(root.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Every symlink at or below {@code root} whose target resolves inside
     * {@code parentStore}. A copy-materialized unit must have none: each such
     * link is a live write-through channel into the parent store.
     */
    static List<String> storeLinksBelow(Path root, Path parentStore) throws IOException {
        List<String> out = new ArrayList<>();
        Path storeReal;
        try {
            storeReal = parentStore.toRealPath();
        } catch (IOException e) {
            return out;
        }
        collectStoreLinks(root, root, storeReal, out);
        return out;
    }

    private static void collectStoreLinks(Path base, Path current, Path storeReal, List<String> out)
            throws IOException {
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(current)) {
            Path raw = Files.readSymbolicLink(current);
            Path resolved = raw.isAbsolute()
                    ? raw.normalize()
                    : current.getParent().resolve(raw).normalize();
            Path real;
            try {
                real = resolved.toRealPath();
            } catch (IOException broken) {
                real = resolved;
            }
            if (real.startsWith(storeReal)) out.add(base.relativize(current) + " -> " + raw);
            return;
        }
        if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            for (Path child : listSorted(current)) collectStoreLinks(base, child, storeReal, out);
        }
    }

    /**
     * Stable SHA-256 over a tree exactly as it sits on disk: directories,
     * file bytes, and symlink TARGETS (never their contents).
     *
     * <p>Used to assert the parent store is byte-for-byte unchanged across a
     * child-home edit. A follow-links digest would be useless here — under the
     * old symlink child home the two trees were the same bytes.
     */
    static String treeDigest(Path root) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        digestInto(root, root, digest);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static void digestInto(Path base, Path current, MessageDigest digest) throws IOException {
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
        String rel = base.equals(current) ? "" : base.relativize(current).toString();
        if (Files.isSymbolicLink(current)) {
            frame(digest, "L", rel);
            digest.update(Files.readSymbolicLink(current).toString().getBytes(StandardCharsets.UTF_8));
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

    private static List<Path> listSorted(Path dir) throws IOException {
        List<Path> children = new ArrayList<>();
        try (var entries = Files.list(dir)) {
            entries.forEach(children::add);
        }
        children.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return children;
    }

    // ------------------------------------------------------------- fixture

    static String skillBody(String name, String rev) {
        return """
                ---
                name: %s
                description: child-home graph fixture
                ---
                body %s
                """.formatted(name, rev);
    }

    static String skillManifest(String name) {
        return """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "child-home graph fixture"
                """.formatted(name);
    }

    static void scaffoldSkill(Path unitsDir, String name, String rev) throws IOException {
        Path dir = unitsDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), skillBody(name, rev));
        Files.writeString(dir.resolve("skill-manager.toml"), skillManifest(name));
    }

    /** Project manifest claiming exactly {@code units}. */
    static String projectManifest(Path unitsDir, String... units) {
        StringBuilder sb = new StringBuilder("""
                [project]
                name = "%s"
                """.formatted(PROJECT_NAME));
        for (String unit : units) {
            sb.append("\n[skills.").append(unit.replace("chm-unit-", "u")).append("]\n");
            sb.append("source = \"").append(unitsDir.resolve(unit)).append("\"\n");
        }
        return sb.toString();
    }
}
