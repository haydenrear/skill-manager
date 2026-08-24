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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared machinery for the {@code home-sync} graph: the three-tier return path
 * ({@code home sync}, {@code home close-out}, {@code unit publish}) driven
 * against real homes on disk through the real CLI.
 *
 * <h2>Why the oracles live here and not inside each node</h2>
 *
 * <p>Every claim this graph makes reduces to one of four questions, and each of
 * them is answered by a named function below rather than by an inline
 * comparison in whichever node happens to ask it:
 *
 * <ul>
 *   <li>{@link #difference} — <em>did these bytes move?</em> It is the whole
 *       basis for "held back with its bytes intact", "a conflict wrote
 *       nothing", and "the dry run wrote nothing", because those three are the
 *       same measurement pointed at different trees.</li>
 *   <li>{@link #wroteNothingBut} — the same question over a whole home, with an
 *       explicit allow-list. A dry run does put one thing on disk (the home
 *       lock), and naming it is honest where tolerating "small" differences
 *       silently is how a dry-run-writes regression survives.</li>
 *   <li>{@link #stagingLeftovers} — <em>did a pass leave half of itself
 *       behind?</em> A displaced or staged tree under {@code .materialization/tmp}
 *       is the visible residue of an interrupted swap.</li>
 *   <li>{@link #gitState} — <em>is the git working tree exactly as it was?</em>
 *       Porcelain status, HEAD, and the index, read from git rather than
 *       inferred from an exit code.</li>
 * </ul>
 *
 * <p>Centralizing them is what makes {@code home.sync.sensitive} meaningful:
 * that node plants one defect per class and asserts <em>these same
 * functions</em> report it. An oracle re-implemented per node could be killed
 * in one node and blind in the next, and the mutation run would not be able to
 * tell.
 *
 * <h2>Every subprocess gets the sandboxed agent homes</h2>
 *
 * <p>See {@link #sm}. Identical discipline to {@code HomeCloneSupport}: the JVM
 * on macOS derives {@code user.home} from the OS and ignores {@code $HOME}, so
 * {@code JAVA_TOOL_OPTIONS=-Duser.home=...} is set alongside the four agent-home
 * variables. Without them {@code install} projects units into the operator's
 * real {@code ~/.claude}, which is the standing read-only constraint on this
 * epic.
 */
final class HomeSyncSupport {

    private HomeSyncSupport() {}

    // ------------------------------------------------------------- process

    static Path repoRoot() {
        return Path.of(System.getProperty("user.dir")).resolve("..").normalize().toAbsolutePath();
    }

    static Path skillManager() {
        return repoRoot().resolve("skill-manager");
    }

    /**
     * Run the real CLI against {@code home}, output captured to a node log.
     *
     * <p>The env is {@link SmEnv}'s, plus a redirected {@code $HOME}: this graph
     * asserts that nothing anywhere in a home names another home, and an
     * unpredicted {@code user.home} read would break that claim rather than
     * merely slow it down.
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
     * A setup command refused, so the fixture never reached the state the
     * assertions below it were written against.
     *
     * <p>Thrown rather than returned. {@code Node.run} turns anything thrown
     * out of a node body into an ERROR envelope, which is the point: a setup
     * step is not a claim, it is a precondition, and the only two honest
     * outcomes are "the state was reached" and "the node did not run".
     */
    static final class SetupRefused extends RuntimeException {

        SetupRefused(String label, int exitCode, List<String> command, String output) {
            super("setup step '" + label + "' exited " + exitCode + " — the fixture was NOT put"
                    + " into the state the assertions below it measure, so every one of them"
                    + " would have been measuring something else.\n  command: "
                    + String.join(" ", command) + "\n  output: " + output.strip());
        }
    }

    /**
     * Run a CLI command that exists to put the fixture into a state, and fail
     * the node when it refuses.
     *
     * <h2>Why this is not just {@link #sm}</h2>
     *
     * <p>Issue #135. {@code HomeSyncPermutations} froze
     * {@code base.resolve("frozen-dest")} — a path that was never laid out as a
     * home — and discarded the {@link ProcessRecord}. When {@code home policy}
     * grew its {@code NotAHomeException} refusal the freeze started exiting 2
     * and doing nothing, the destination stayed <b>live</b>, and the three
     * commands the node asserts exit 9 exited 0 and populated it instead. The
     * node had been asserting against a destination that was never in the state
     * it believed for as long as the refusal had existed.
     *
     * <p>The one-line repair is {@code --init}. The repair that stops the class
     * is this: a setup step that does not reach its state must fail the node
     * loudly, at the step, rather than quietly change what is being tested and
     * leave a downstream assertion to report a symptom that names the wrong
     * thing. {@code frozenExits=0/0/0} reads as "the freeze contract broke";
     * the truth was "nothing was ever frozen".
     *
     * <p>It throws instead of returning a flag because a returned flag is
     * ignorable in exactly the way the original call was, and every one of
     * these sites is a statement whose value nobody wanted.
     */
    static ProcessRecord setup(NodeContext ctx, String label, String home, String... args) {
        ProcessRecord record = sm(ctx, label, home, args);
        if (record.exitCode() != 0) {
            throw new SetupRefused(label, record.exitCode(), record.command(), log(ctx, label));
        }
        return record;
    }

    static String log(NodeContext ctx, String label) {
        try {
            return Files.readString(Procs.logFile(ctx, label));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The policy {@code home policy --home <h>} reported, or {@code ""}.
     *
     * <p>The home's own answer to "what am I", read back rather than inferred
     * from the exit code of the command that set it. Issue #135 is the whole
     * argument for the distinction: exit 0 from a freeze that was never
     * attempted and exit 0 from a freeze that landed are the same number.
     */
    static String policyLine(String logText) {
        for (String line : logText.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("policy:")) return trimmed.substring("policy:".length()).trim();
        }
        return "";
    }

    /** Exit code and captured stdout of a plain subprocess (git queries). */
    record Capture(int exit, String out) {
        boolean ok() { return exit == 0; }

        String trimmed() { return out == null ? "" : out.trim(); }

        List<String> lines() {
            List<String> out = new ArrayList<>();
            for (String line : trimmed().split("\n")) {
                if (!line.isBlank()) out.add(line.strip());
            }
            return out;
        }
    }

    /**
     * Run {@code git} in {@code dir} and capture stdout.
     *
     * <p>Deliberately not routed through the SDK's process helper: these are
     * assertions <em>about</em> git state, run several per claim, and the
     * envelope wants the claim rather than sixty subprocess records. The
     * skill-manager invocations that actually change something all go through
     * {@link #sm} and are attached to the node.
     */
    static Capture git(Path dir, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out;
            try (InputStream in = proc.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return new Capture(proc.waitFor(), out);
        } catch (Exception e) {
            return new Capture(-1, String.valueOf(e.getMessage()));
        }
    }

    /**
     * Everything git knows about a working tree, as one comparable value.
     *
     * <p>HEAD, the current branch, the porcelain status, and the full index
     * ({@code git ls-files -s}, which carries mode + blob sha + stage for every
     * tracked path). A stray staged deletion, a reset index, and a moved HEAD
     * are all differences in this string; an exit code is none of them.
     */
    static String gitState(Path repo) {
        return String.join("\n",
                "head=" + git(repo, "rev-parse", "HEAD").trimmed(),
                "branch=" + git(repo, "rev-parse", "--abbrev-ref", "HEAD").trimmed(),
                "status=" + git(repo, "status", "--porcelain").trimmed(),
                "index=" + git(repo, "ls-files", "-s").trimmed());
    }

    // ---------------------------------------------------------------- json

    /** Last line of {@code logText} that begins a JSON object. */
    static String jsonLine(String logText) {
        String found = "";
        for (String line : logText.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("{")) found = trimmed;
        }
        return found;
    }

    /** The parsed {@code --json} payload of the run logged under {@code label}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> json(NodeContext ctx, String label) {
        Object parsed = MiniJson.parse(jsonLine(log(ctx, label)));
        return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
    }

    /** The reported outcome object for one unit label, or an empty map. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> unit(Map<String, Object> report, String unitLabel) {
        Object units = report.get("units");
        if (!(units instanceof List<?> list)) return Map.of();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map && unitLabel.equals(map.get("unit"))) {
                return (Map<String, Object>) map;
            }
        }
        return Map.of();
    }

    /** {@code unchanged / updated / held-back / merged / conflicted / new / removed-upstream}. */
    static String status(Map<String, Object> report, String unitLabel) {
        Object value = unit(report, unitLabel).get("status");
        return value == null ? "(absent)" : value.toString();
    }

    static List<String> strings(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object entry : list) out.add(String.valueOf(entry));
        return out;
    }

    /** Conflicting file paths reported for one unit. */
    static List<String> conflicts(Map<String, Object> report, String unitLabel) {
        return strings(unit(report, unitLabel), "conflicts");
    }

    /** The {@code blockers[]} entry for one unit in a close-out verdict. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> blocker(Map<String, Object> verdict, String unitLabel) {
        Object blockers = verdict.get("blockers");
        if (!(blockers instanceof List<?> list)) return Map.of();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map && unitLabel.equals(map.get("unit"))) {
                return (Map<String, Object>) map;
            }
        }
        return Map.of();
    }

    static int blockerCount(Map<String, Object> verdict) {
        Object blockers = verdict.get("blockers");
        return blockers instanceof List<?> list ? list.size() : -1;
    }

    static boolean flag(Map<String, Object> object, String key) {
        return Boolean.TRUE.equals(object.get(key));
    }

    // ------------------------------------------------------------- oracles

    /**
     * Every path whose bytes differ between two snapshots of a tree.
     *
     * <p>{@code + path} appeared, {@code - path} vanished, {@code ~ path}
     * changed. The whole graph's "nothing was lost" and "nothing was written"
     * claims are this function returning empty; the sensitive node's claim that
     * those are falsifiable is this function returning non-empty on a planted
     * defect.
     */
    static List<String> difference(Map<String, String> before, Map<String, String> after) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : before.entrySet()) {
            String now = after.get(entry.getKey());
            if (now == null) out.add("- " + entry.getKey());
            else if (!now.equals(entry.getValue())) out.add("~ " + entry.getKey());
        }
        for (String path : after.keySet()) {
            if (!before.containsKey(path)) out.add("+ " + path);
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * {@link #difference} over a whole home, minus paths the caller has named
     * as legitimately allowed to appear.
     *
     * <p>The allow-list exists for exactly one artefact: a dry run takes the
     * home lock, and taking it creates {@code .materialization/.home.lock}. It
     * is passed in by name rather than filtered by a prefix rule, so a dry run
     * that wrote a materialization record — the same directory, one path along
     * — is still a violation.
     */
    static List<String> wroteNothingBut(Map<String, String> before, Map<String, String> after,
                                        Set<String> allowed) {
        List<String> out = new ArrayList<>();
        for (String change : difference(before, after)) {
            String path = change.substring(2);
            if (change.startsWith("+ ") && allowed.contains(path)) continue;
            out.add(change);
        }
        return out;
    }

    /**
     * Trees left under the home's staging area — the residue of a swap that did
     * not finish, and the thing that would load as a second copy of a unit if it
     * were ever left somewhere the store scans.
     */
    static List<String> stagingLeftovers(Path home) {
        return names(home.resolve(".materialization").resolve("tmp"));
    }

    // ---------------------------------------------------------- filesystem

    /**
     * Per-entry SHA-256 over a tree exactly as it sits on disk: file bytes,
     * directory names, and symlink <em>targets</em> — never what a link points
     * at. Paths are relative to {@code root}, so a tree that was moved compares
     * equal to itself.
     */
    static LinkedHashMap<String, String> entryDigests(Path root) throws IOException {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        collect(root, root, out);
        return out;
    }

    private static void collect(Path base, Path current, Map<String, String> out) throws IOException {
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
        String rel = base.equals(current) ? "." : base.relativize(current).toString();
        if (Files.isSymbolicLink(current)) {
            out.put(rel, "L:" + sha(Files.readSymbolicLink(current).toString()
                    .getBytes(StandardCharsets.UTF_8)));
            return;
        }
        if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            out.put(rel, "D");
            for (Path child : listSorted(current)) collect(base, child, out);
            return;
        }
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(current)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
        out.put(rel, "F:" + hex(digest.digest()));
    }

    /** Whole-tree digest, for the cases that want one number rather than a map. */
    static String treeDigest(Path root) throws IOException {
        MessageDigest digest = sha256();
        for (Map.Entry<String, String> entry : entryDigests(root).entrySet()) {
            digest.update((entry.getKey() + "\0" + entry.getValue() + "\0")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String sha(byte[] bytes) {
        MessageDigest digest = sha256();
        digest.update(bytes);
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
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

    static String read(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            return "";
        }
    }

    static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    static void append(Path path, String line) throws IOException {
        write(path, read(path) + line);
    }

    static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort; a leftover temp tree is not a finding
                }
            });
        } catch (IOException ignored) {
            // ditto
        }
    }

    // ------------------------------------------------------------- fixture

    /** Directory holding a home's skill units. */
    static Path skills(Path home) { return home.resolve("skills"); }

    static Path unitDir(Path home, String name) { return skills(home).resolve(name); }

    /**
     * The layout that makes a directory a Skill Manager HOME rather than a
     * directory with skills in it.
     *
     * <p>{@code installed/} is not decoration here. {@code home sync --from}
     * and {@code home close-out} now refuse a path that is not a home
     * ({@code LaunchEnv.looksLikeStoreRoot}: a descriptor, or the
     * {@code installed/} + {@code skills/} pair), because a non-home used to
     * contribute zero units to the reconcile and zero units reads exactly like
     * "the two homes agree" — which is how `close-out --home <the worktree
     * directory>` cleared a teardown. A fixture that skipped it would have been
     * testing the refusal rather than the reconcile.
     */
    static void mkHome(Path home) throws IOException {
        Files.createDirectories(skills(home));
        Files.createDirectories(home.resolve("installed"));
    }

    /**
     * The marker {@code HomeMembershipLaw} reads to tell a hand-staged unit
     * from a unit that appeared in a home nobody named. Spelled here as a
     * literal rather than imported: these are separate jbang node sources with
     * no shared compilation unit, and the law's javadoc is the contract. The
     * two spellings are held together by the law's own self-test, which fails
     * loudly if the marker it writes is not the marker it reads.
     */
    static final String STAGED_MARKER = ".test-graph-staged";

    /**
     * A minimal installable skill unit, written straight into a home.
     *
     * <h2>Why it also writes {@link #STAGED_MARKER} — DEF-107</h2>
     *
     * <p>This method exists because the thing under test is what {@code home
     * sync} does about a unit the destination has not got, so the precondition
     * has to be a unit on disk that nothing installed. That is, byte for byte,
     * the residue {@code HomeMembershipLaw} calls "a unit nobody installed" —
     * so the law read ten nodes' deliberate preconditions as product
     * violations and reddened the CORE {@code home-sync} graph
     * ({@code GAINED [hs-delta, hs-epsilon]}, three homes, run
     * {@code 20260824-193907}).
     *
     * <p>The fix is for the fixture to SAY what it staged rather than for the
     * law to stop looking. The marker goes inside the unit directory, so it
     * travels with the unit: {@code hs-delta} is staged in the root home and
     * then propagated to the project and worktree homes by a real
     * {@code home sync}, and it must arrive marked in all three.
     *
     * <p>It marks only what THIS method writes. A unit the product installs, or
     * one a defect drops into a home, carries no marker and is still a
     * violation — the law's self-test plants exactly that case beside a marked
     * one and requires it to be flagged.
     */
    static void mkUnit(Path home, String name, String body) throws IOException {
        mkHome(home);
        Path dir = unitDir(home, name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: home-sync graph fixture
                ---
                %s
                """.formatted(name, body));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "home-sync graph fixture"
                """.formatted(name));
        Files.writeString(dir.resolve(STAGED_MARKER),
                "staged by HomeSyncSupport.mkUnit — see HomeMembershipLaw.STAGED_MARKER\n");
    }

    /** A skill unit as an installable source directory (not inside a home). */
    static void mkSource(Path parent, String name, String body) throws IOException {
        Path dir = parent.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: home-sync graph fixture
                ---
                %s
                """.formatted(name, body));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "home-sync graph fixture"
                """.formatted(name));
    }

    // ---------------------------------------------------------- mini json

    /**
     * Enough JSON to read the CLI's own {@code --json} payloads.
     *
     * <p>Hand-rolled because a graph node is a jbang script with the SDK on its
     * classpath and nothing else, and because the alternative — pulling values
     * out with string search — cannot tell {@code "conflicts":[]} on the unit
     * being asserted from the one three objects later. Every claim in this
     * graph about "what the report said" reads through here.
     */
    static final class MiniJson {

        private final String src;
        private int at;

        private MiniJson(String src) { this.src = src; }

        static Object parse(String text) {
            if (text == null || text.isBlank()) return null;
            try {
                MiniJson parser = new MiniJson(text);
                parser.ws();
                Object value = parser.value();
                return value;
            } catch (RuntimeException e) {
                return null;
            }
        }

        private void ws() {
            while (at < src.length() && Character.isWhitespace(src.charAt(at))) at++;
        }

        private Object value() {
            ws();
            char c = src.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Object literal(String text, Object value) {
            if (!src.startsWith(text, at)) throw new IllegalStateException("bad literal at " + at);
            at += text.length();
            return value;
        }

        private Object number() {
            int from = at;
            while (at < src.length() && "-+.eE0123456789".indexOf(src.charAt(at)) >= 0) at++;
            return Double.parseDouble(src.substring(from, at));
        }

        private String string() {
            if (src.charAt(at) != '"') throw new IllegalStateException("expected string at " + at);
            at++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = src.charAt(at++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char escaped = src.charAt(at++);
                switch (escaped) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(src.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> sb.append(escaped);
                }
            }
        }

        private Map<String, Object> object() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            at++;
            ws();
            if (src.charAt(at) == '}') { at++; return out; }
            while (true) {
                ws();
                String key = string();
                ws();
                if (src.charAt(at) != ':') throw new IllegalStateException("expected : at " + at);
                at++;
                out.put(key, value());
                ws();
                char c = src.charAt(at++);
                if (c == '}') return out;
                if (c != ',') throw new IllegalStateException("expected , or } at " + at);
            }
        }

        private List<Object> array() {
            List<Object> out = new ArrayList<>();
            at++;
            ws();
            if (src.charAt(at) == ']') { at++; return out; }
            while (true) {
                out.add(value());
                ws();
                char c = src.charAt(at++);
                if (c == ']') return out;
                if (c != ',') throw new IllegalStateException("expected , or ] at " + at);
            }
        }
    }
}
