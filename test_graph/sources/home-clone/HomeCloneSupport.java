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
 * Shared helpers for the {@code home-clone} graph, which drives the real
 * {@code skill-manager} CLI over a SYNTHETIC fixture home and asserts the
 * home-level isolation contract from {@code specs/desired_program_model}
 * ({@code External.tla} {@code HomeSpec}):
 *
 * <ul>
 *   <li>{@code SourceHomeIsByteIdenticalToItsCloneTimeSelf} — the home the copy
 *       came from is read once and never written.</li>
 *   <li>{@code AHomeIsAPureFunctionOfItsRoot} — the clone works with the source
 *       renamed away.</li>
 *   <li>{@code NoOwnedSurfaceNamesAnotherHome} — nothing skill-manager wrote in
 *       the copy names the original.</li>
 *   <li>{@code ToolchainRootsAreNeverShared} /
 *       {@code AHomeMissingItsToolchainsStillHasItsPackageManagers} /
 *       {@code EveryHomeMissingItsToolchainsSaysSo} — venvs/tools/npm absent and
 *       reported, pm/ carried.</li>
 *   <li>{@code AuthoredContentIsNeverRewritten} — a spec-history file naming the
 *       source home survives verbatim.</li>
 * </ul>
 *
 * <h2>The fixture home is built, never cloned from the developer's</h2>
 *
 * The real {@code ~/.skill-manager} is 5.4 GB and this machine has less free
 * space than that in {@code /private/tmp}. It is also the home the standing
 * constraint on issue #1 forbids writing to. So the fixture is scaffolded from
 * nothing, with one artifact per problem class issue #20 measured: an absolute
 * in-unit symlink into the store, an absolute in-home symlink under
 * {@code bin/cli}, a generated shim with a hardcoded home path in its BODY, a
 * second shim whose target is under a skipped toolchain root, a {@code pm/}
 * entry that must survive, a {@code venvs/} entry that must not, and an authored
 * file under {@code skills/} that records an absolute home path and must be left
 * exactly as written.
 *
 * <h2>Every subprocess gets the sandboxed agent homes</h2>
 *
 * See {@link #sm}, which delegates the env to {@code SmEnv} rather than spelling
 * it out. It used to spell it out, and that made it one of four copies of the
 * same recipe that had drifted apart — issue #30, which measured 50 of the 105
 * env sites in this tree passing neither an agent-home variable nor {@code HOME},
 * so {@code install} projected units into the developer's real {@code ~/.claude}
 * and {@code ~/.codex} by way of the {@code user.home} fallback in
 * {@code AgentHomes}/{@code CodexAgent}/{@code GeminiAgent}.
 *
 * <p>The graph still carries a node that PROVES it did not happen, rather than
 * relying on any helper being called correctly, and
 * {@code sources/sandbox/SandboxEnvContract.java} now fails on a node that
 * spells the env itself or spawns the CLI without the helper at all.
 */
final class HomeCloneSupport {

    /** Units installed into the fixture home. */
    static final String UNIT_A = "hc-unit-a";
    static final String UNIT_B = "hc-unit-b";
    /** Reached only through an in-unit symlink, never a direct dependency. */
    static final String LINKED = "hc-linked";

    /** Shim whose exec target is carried by the clone: must keep working. */
    static final String GOOD_SHIM = "hc-tool";
    /** Shim whose exec target is under a SKIPPED toolchain root: must be reported. */
    static final String DANGLING_SHIM = "hc-venv-tool";
    /** Absolute in-home symlink under bin/cli: must come out relative. */
    static final String LINK_SHIM = "hc-link";

    /** In-unit symlink into the store, written ABSOLUTE in the fixture. */
    static final String IN_UNIT_LINK = "vendor/linked";

    /** Authored file that legitimately records an absolute home path. */
    static final String AUTHORED_HISTORY = "history/run-0001.md";

    private HomeCloneSupport() {}

    // ------------------------------------------------------------- process

    static Path repoRoot() {
        return Path.of(System.getProperty("user.dir")).resolve("..").normalize().toAbsolutePath();
    }

    static Path skillManager() {
        return repoRoot().resolve("skill-manager");
    }

    /**
     * Run the real CLI against {@code home}, with output captured to a node log.
     *
     * <p>The five-variable sandbox comes from {@link SmEnv} — this method used to
     * spell it out and was one of the four copies that disagreed (issue #30).
     * On top of it this graph redirects {@code HOME} and {@code user.home},
     * because its claim is about the whole home: {@code NoOwnedSurfaceNamesAnotherHome}
     * is falsified by an unpredicted {@code user.home} read, not merely slowed by
     * one.
     */
    static ProcessRecord sm(NodeContext ctx, String label, String home, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = skillManager().toString();
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(command);
        SmEnv.apply(ctx, pb, home);
        ctx.get("env.prepared", "home")
                .ifPresent(sandbox -> SmEnv.alsoRedirectPosixHome(pb, sandbox));
        return Procs.run(ctx, label, pb);
    }

    /**
     * {@link #sm}, but with the agent homes BESIDE the home being written
     * rather than at the run's shared sandbox root.
     *
     * <h2>Why the fixture needs its own agent homes</h2>
     *
     * <p>{@link #sm} points every install at {@code env.prepared}'s shared
     * {@code <sandbox>/agent-home/…}, which is outside the fixture home's own
     * root. That produced a source home whose {@code DEFAULT_AGENT} bindings
     * all named a directory belonging to no home in particular — and a clone of
     * it whose ledger named the SAME directory as the source's. Two homes, one
     * set of links: an {@code uninstall} in the copy deletes the original's
     * projections. That is the #145 shape, in a layout
     * {@code LaunchEnv.requireClaudeRedirected} refuses to launch anyway.
     *
     * <p>Since {@code home clone} stopped inheriting claims over paths outside
     * the new home, those bindings are dropped by the copy — correctly — and
     * the ledger assertions downstream had nothing left to read. The repair is
     * the fixture, not the assertion: give the fixture home the layout every
     * real home has, {@code <root>/{.claude,.codex,.gemini}} beside the store,
     * so its bindings are its own and the clone can re-anchor them.
     *
     * <p>Deliberately NOT repaired by weakening "outside the new home's root"
     * to "inside some other home's span": that would keep the graph green while
     * letting a binding into a plain, non-home checkout survive a clone, which
     * is the half of the defect with the projection targets in it.
     */
    static ProcessRecord smIntoOwnAgentHomes(NodeContext ctx, String label, String home,
                                             String... args) {
        String[] command = new String[args.length + 1];
        command[0] = skillManager().toString();
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(command);
        SmEnv.apply(pb, home, SmEnv.repoRoot().toString(),
                SmEnv.sandboxUnder(agentRootOf(home)));
        ctx.get("env.prepared", "home")
                .ifPresent(sandbox -> SmEnv.alsoRedirectPosixHome(pb, sandbox));
        return Procs.run(ctx, label, pb);
    }

    /**
     * The root whose {@code .claude}/{@code .codex}/{@code .gemini} belong to
     * the home at {@code storeRoot} — {@code AgentHomes.homeRootFor}'s rule:
     * the parent when the store is named {@code .skill-manager}, the store
     * itself otherwise.
     */
    static Path agentRootOf(String storeRoot) {
        Path abs = Path.of(storeRoot).toAbsolutePath().normalize();
        Path name = abs.getFileName();
        if (name != null && ".skill-manager".equals(name.toString()) && abs.getParent() != null) {
            return abs.getParent();
        }
        return abs;
    }

    /** Run an arbitrary executable (a generated shim) with the same sandbox. */
    static ProcessRecord exec(NodeContext ctx, String label, String home, List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        SmEnv.apply(ctx, pb, home);
        ctx.get("env.prepared", "home")
                .ifPresent(sandbox -> SmEnv.alsoRedirectPosixHome(pb, sandbox));
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

    /** Last line of {@code logText} that begins a JSON object. */
    static String jsonLine(String logText, String startsWith) {
        String found = "";
        for (String line : logText.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith(startsWith)) found = trimmed;
        }
        return found;
    }

    /** Integer value of {@code "key":N} in a flat JSON object, or -1. */
    static int jsonInt(String json, String key) {
        String needle = "\"" + key + "\":";
        int at = json.indexOf(needle);
        if (at < 0) return -1;
        int from = at + needle.length();
        int to = from;
        while (to < json.length() && (Character.isDigit(json.charAt(to)) || json.charAt(to) == '-')) to++;
        if (to == from) return -1;
        return Integer.parseInt(json.substring(from, to));
    }

    /** String value of {@code "key":"..."} or {@code "key" : "..."}. */
    static String jsonString(String json, String key) {
        for (String needle : new String[] {"\"" + key + "\":\"", "\"" + key + "\" : \""}) {
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

    static void writeExecutable(Path path, String content) throws IOException {
        write(path, content);
        try {
            Set<PosixFilePermission> perms = new LinkedHashSet<>(Files.getPosixFilePermissions(path));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem; the shim assertions will report it
        }
    }

    static Path storeOf(Path projectDir) {
        return projectDir.resolve(".skill-manager");
    }

    static Path unitDir(Path store, String name) {
        return store.resolve("skills").resolve(name);
    }

    static Path shim(Path store, String name) {
        return store.resolve("bin").resolve("cli").resolve(name);
    }

    /** True when {@code path} exists as a symlink whose stored target is relative. */
    static boolean isRelativeSymlink(Path path) {
        try {
            return Files.isSymbolicLink(path) && !Files.readSymbolicLink(path).isAbsolute();
        } catch (IOException e) {
            return false;
        }
    }

    static String linkTarget(Path path) {
        try {
            return Files.isSymbolicLink(path) ? Files.readSymbolicLink(path).toString() : "";
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Every path at or below {@code root} that still mentions {@code needle} in
     * its bytes or in a symlink target, with the surface class it falls under.
     *
     * <p>This is an INDEPENDENT scan, not a reading of the clone's own report.
     * The distinction is the whole point: `home clone` exiting 0 is a claim, and
     * the acceptance criterion for the clone is the filesystem, not the claim.
     */
    static List<String> referencesTo(Path root, String needle) throws IOException {
        List<String> hits = new ArrayList<>();
        collectReferences(root, root, needle, hits);
        hits.sort(String::compareTo);
        return hits;
    }

    private static void collectReferences(Path base, Path current, String needle, List<String> hits)
            throws IOException {
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
        String rel = base.equals(current) ? "" : base.relativize(current).toString();
        if (Files.isSymbolicLink(current)) {
            String target = Files.readSymbolicLink(current).toString();
            if (target.contains(needle)) hits.add("SYMLINK " + rel + " -> " + target);
            return;
        }
        if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            for (Path child : listSorted(current)) collectReferences(base, child, needle, hits);
            return;
        }
        if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            if (Files.size(current) > 4L * 1024 * 1024) return;
            String text = new String(Files.readAllBytes(current), StandardCharsets.UTF_8);
            if (text.contains(needle)) hits.add(surfaceOf(rel) + " " + rel);
        } catch (IOException ignored) {
            // unreadable file; nothing to assert about its bytes
        }
    }

    /**
     * The surface class of a home-relative path, mirroring
     * {@code HomeCloner.classify}. Reimplemented here rather than imported so a
     * mutation to production {@code classify} cannot make the graph agree with
     * itself — the graph has to be an independent opinion about which
     * references are tolerated.
     */
    static String surfaceOf(String rel) {
        String n = rel.replace('\\', '/');
        String top = n.contains("/") ? n.substring(0, n.indexOf('/')) : n;
        if (Set.of("skills", "plugins", "docs", "harnesses").contains(top)
                && !n.startsWith("harnesses/instances/")) {
            return "CONTENT";
        }
        if (Set.of("venvs", "tools", "npm", "pm", "bin", "gateway-data").contains(top)) {
            return "PROVISIONED";
        }
        return "STATE";
    }

    /**
     * Stable SHA-256 over a tree exactly as it sits on disk: directory names,
     * file bytes, and symlink TARGETS — never what a link points at.
     *
     * <p>Paths are framed relative to {@code root}, so renaming the root does
     * not change the digest. That is deliberate: the graph renames the source
     * home away and back, and a digest that moved would make the byte-identity
     * assertion unfalsifiable in one direction and vacuous in the other.
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

    /** Sorted relative inventory of a tree — the human-readable diff companion. */
    static List<String> inventory(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        inventoryInto(root, root, out);
        out.sort(String::compareTo);
        return out;
    }

    private static void inventoryInto(Path base, Path current, List<String> out) throws IOException {
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

    // ------------------------------------------------------------- fixture

    static String skillBody(String name, String rev) {
        return """
                ---
                name: %s
                description: home-clone graph fixture
                ---
                body %s
                """.formatted(name, rev);
    }

    static String skillManifest(String name) {
        return """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "home-clone graph fixture"
                """.formatted(name);
    }

    static void scaffoldSkill(Path unitsDir, String name, String rev) throws IOException {
        Path dir = unitsDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), skillBody(name, rev));
        Files.writeString(dir.resolve("skill-manager.toml"), skillManifest(name));
    }
}
