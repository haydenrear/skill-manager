package dev.skillmanager.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.skillmanager.agent.AgentHomes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code home.runtime.json} — the one artifact a consumer reads to launch
 * an agent against a Skill Manager home.
 *
 * <p>It replaces the bespoke manifests consumers were each inventing
 * (meta-orchestrator's {@code agents/manifest.json} being the one this was
 * promoted from): where the home is, whether it may be mutated, the exact
 * environment to export, which {@code skill-manager} binary goes with it,
 * which gateway to talk to, and what was installed at the moment of
 * writing.
 *
 * <h2>The file lives inside the store, the paths do not name it</h2>
 *
 * <p>The descriptor is written at {@code $SKILL_MANAGER_HOME/home.runtime.json}
 * — inside the store, not beside it — for one reason: that is the surface
 * {@link HomeCloner} carries, so a descriptor written once keeps describing
 * the home after a copy moves it. On disk, paths that travel with the home
 * are stored as {@code $SKILL_MANAGER_HOME/...} (and, for the agent homes
 * that sit beside the store, {@code $SKILL_MANAGER_HOME/../...}); they
 * resolve against whatever root the file is read from, so the same bytes
 * describe the copy and a clone needs no descriptor rewrite at all. See
 * {@code storageMapper} for the exact bound on that encoding and
 * {@link #toJson(Path)} for why the printed form is different.
 *
 * <h2>{@code CLAUDE_HOME} and {@code CLAUDE_CONFIG_DIR} carry one value</h2>
 *
 * <p>Both are emitted, and both are {@code <homeRoot>/.claude}. Consumers
 * read whichever they know about — {@code CLAUDE_CONFIG_DIR} is the
 * operative one for the Claude CLI — and they cannot disagree because
 * there is only one value to disagree about. That is safe for
 * skill-manager's own reader precisely because
 * {@link AgentHomes#claude()} consults {@code CLAUDE_CONFIG_DIR} first:
 * a value that is already the {@code .claude} directory is used verbatim
 * rather than having a second {@code .claude} appended.
 *
 * <p>Publishing them off {@code ~/.claude} is what makes home isolation
 * real. {@code SKILL_MANAGER_HOME} alone is not enough — skills are
 * loaded from the Claude config dir, so a project-local home whose
 * consumer still points Claude at {@code ~/.claude} is not isolated at
 * all.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"homeRoot", "policy", "env", "cli", "gateway", "units", "envContributions"})
public record HomeDescriptor(
        Path homeRoot,
        String policy,
        Env env,
        Cli cli,
        Gateway gateway,
        List<Unit> units,
        Map<String, String> envContributions
) {

    public static final String FILENAME = "home.runtime.json";

    public HomeDescriptor {
        units = units == null ? List.of() : List.copyOf(units);
        envContributions = envContributions == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(envContributions));
    }

    /**
     * The environment a consumer must export to bind a process to this
     * home. Every value is derived from the home's own layout, never read
     * back out of the ambient process environment — a descriptor that
     * echoed whatever was already set would describe the machine that
     * wrote it rather than the home it names.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"SKILL_MANAGER_HOME", "CLAUDE_CONFIG_DIR", "CLAUDE_HOME",
            "CODEX_HOME", "GEMINI_HOME"})
    public record Env(
            @JsonProperty("SKILL_MANAGER_HOME") Path skillManagerHome,
            @JsonProperty("CLAUDE_CONFIG_DIR") Path claudeConfigDir,
            @JsonProperty("CLAUDE_HOME") Path claudeHome,
            @JsonProperty("CODEX_HOME") Path codexHome,
            @JsonProperty("GEMINI_HOME") Path geminiHome
    ) {
        /** Ordered {@code NAME -> value}, ready to hand to a subprocess. */
        public Map<String, String> asMap() {
            Map<String, String> out = new LinkedHashMap<>();
            put(out, "SKILL_MANAGER_HOME", skillManagerHome);
            put(out, AgentHomes.CLAUDE_CONFIG_DIR, claudeConfigDir);
            put(out, AgentHomes.CLAUDE_HOME, claudeHome);
            put(out, AgentHomes.CODEX_HOME, codexHome);
            put(out, AgentHomes.GEMINI_HOME, geminiHome);
            return out;
        }

        private static void put(Map<String, String> out, String key, Path value) {
            if (value != null) out.put(key, value.toString());
        }
    }

    /**
     * Tooling that goes with this home. {@code skillManager} is resolved
     * at write time, never hardcoded — a per-project home is frequently
     * paired with a specific build of the CLI, and a baked-in
     * {@code /opt/homebrew/bin/skill-manager} would silently point a
     * project at the wrong one.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cli(Path skillManager) {}

    /**
     * Which gateway this home talks to, and whether it is allowed to
     * start or stop it. {@code owned == false} means "attached to a
     * gateway some other home runs" — the shared-gateway mode that keeps
     * N per-project homes from racing for one port.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Gateway(String url, boolean owned) {}

    /**
     * One row of the {@code skill-manager list --json} snapshot, promoted
     * to a standard descriptor field so every consumer gets chain of
     * custody without shelling out and parsing.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"name", "kind", "version", "source", "sha"})
    public record Unit(String name, String kind, String version, String source, String sha) {}

    // ------------------------------------------------------------- layout

    /**
     * The agent-home root enclosing {@code storeRoot}: the directory that
     * holds {@code .claude/}, {@code .codex/}, and {@code .gemini/}
     * alongside the store.
     *
     * <p>By convention the store is {@code <root>/.skill-manager}, so the
     * root is its parent — which is true for both {@code ~/.skill-manager}
     * (root {@code ~}) and a per-project {@code <repo>/.skill-manager}
     * (root {@code <repo>}). A store that is not named
     * {@code .skill-manager} is its own root, because there is nothing
     * else it could be; the profile layout
     * ({@code ProjectChildHomeScaffolder.layoutFor} with a named profile)
     * is that shape and puts its agent homes under {@code agents/}, so
     * such callers should pass the root explicitly rather than rely on
     * this derivation.
     */
    public static Path homeRootFor(Path storeRoot) {
        Path abs = storeRoot.toAbsolutePath().normalize();
        Path name = abs.getFileName();
        if (name != null && ".skill-manager".equals(name.toString())) {
            Path parent = abs.getParent();
            if (parent != null) return parent;
        }
        return abs;
    }

    /**
     * The env block for a home rooted at {@code homeRoot} whose store is
     * {@code storeRoot}. {@code CLAUDE_HOME} and {@code CLAUDE_CONFIG_DIR}
     * are the same path by construction — see the class javadoc.
     */
    public static Env envFor(Path homeRoot, Path storeRoot) {
        Path root = homeRoot.toAbsolutePath().normalize();
        Path claudeConfigDir = root.resolve(AgentHomes.CLAUDE_DIR_NAME);
        return new Env(
                storeRoot.toAbsolutePath().normalize(),
                claudeConfigDir,
                claudeConfigDir,
                root.resolve(".codex"),
                root.resolve(".gemini"));
    }

    // -------------------------------------------------------------- serde

    /** Where the descriptor for {@code storeRoot} lives. */
    public static Path file(Path storeRoot) {
        return storeRoot.resolve(FILENAME);
    }

    /**
     * Write the descriptor into {@code storeRoot} in storage form: every
     * path that travels with the home is tokenized, so the same bytes
     * describe the copy after a relocation.
     *
     * <p>Storage form is <em>not</em> what {@link #toJson(Path)} prints —
     * see {@link #storageMapper} for the distinction and why it exists.
     */
    public void write(Path storeRoot) throws IOException {
        Files.createDirectories(storeRoot);
        storageMapper(storeRoot).writerWithDefaultPrettyPrinter()
                .writeValue(file(storeRoot).toFile(), this);
    }

    /**
     * Read the descriptor from {@code storeRoot}, resolving every token
     * against it, so the returned record always holds absolute paths that
     * are true of the home it was read from. Empty when absent;
     * {@link IOException} when present and unparseable — a descriptor that
     * cannot be read is a launch that must not proceed on guesses.
     */
    public static Optional<HomeDescriptor> read(Path storeRoot) throws IOException {
        Path f = file(storeRoot);
        if (!Files.isRegularFile(f)) return Optional.empty();
        return Optional.of(storageMapper(storeRoot).readValue(f.toFile(), HomeDescriptor.class));
    }

    /**
     * Pretty JSON with every path resolved — the consumer view, as
     * {@code home describe --json} emits it. Needs no root, because a
     * descriptor in memory always holds absolute paths: it was either just
     * assembled from a home or read through {@link #read(Path)}, which
     * resolves.
     *
     * <p>This deliberately differs from what {@link #write(Path)} puts on
     * disk. The two readers want opposite things: the file has to survive
     * being copied to a new root, which means tokens; a consumer piping
     * this into {@code jq} wants paths it can use without implementing
     * token expansion. Making the durable record relocatable and the
     * printed view concrete gives each what it needs, and the printed view
     * is always generated from the home it was asked about, so it cannot go
     * stale.
     */
    public String toJson() throws IOException {
        return viewMapper().writerWithDefaultPrettyPrinter().writeValueAsString(this);
    }

    // ------------------------------------------------------ path encoding

    /**
     * Mapper for the on-disk form: paths are stored so that they follow the
     * home. Two cases are encoded, and only two:
     *
     * <ul>
     *   <li><b>Inside the store</b> — {@code $SKILL_MANAGER_HOME/<rel>},
     *       exactly {@link HomePaths}.</li>
     *   <li><b>Beside the store</b> — a path under the store's own parent,
     *       which is where the agent homes live
     *       ({@code <root>/.claude} beside {@code <root>/.skill-manager}).
     *       Stored as {@code $SKILL_MANAGER_HOME/../<rel>}. These are the
     *       reason a plain {@link HomePaths} encoding is not enough here:
     *       they are the whole point of the descriptor and none of them are
     *       inside the store.</li>
     * </ul>
     *
     * <p>Anything else stays absolute. That bound is the important part.
     * Walking further up ({@code ../../elsewhere}) would silently repoint an
     * unrelated path at whatever sits at that offset from the copy — the
     * same failure {@link HomePaths} refuses to risk in a projection ledger.
     * An agent home the operator placed outside the home's own directory is
     * genuinely external and is left naming what it names.
     *
     * <p>Widening the rule is safe on this file in a way it would not be on
     * a ledger: nothing deletes or overwrites based on a descriptor field.
     * It is an output.
     */
    private static ObjectMapper storageMapper(Path storeRoot) {
        Path root = storeRoot.toAbsolutePath().normalize();
        return mapper(new JsonSerializer<Path>() {
            @Override
            public void serialize(Path value, JsonGenerator gen, SerializerProvider provider)
                    throws IOException {
                gen.writeString(encodeTravelling(root, value));
            }
        }, new JsonDeserializer<Path>() {
            @Override
            public Path deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                return decodeTravelling(root, p.getValueAsString());
            }
        });
    }

    /** Mapper for the printed form: paths verbatim, both directions. */
    private static ObjectMapper viewMapper() {
        return mapper(new JsonSerializer<Path>() {
            @Override
            public void serialize(Path value, JsonGenerator gen, SerializerProvider provider)
                    throws IOException {
                gen.writeString(value.toString());
            }
        }, new JsonDeserializer<Path>() {
            @Override
            public Path deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                return Path.of(p.getValueAsString());
            }
        });
    }

    private static ObjectMapper mapper(JsonSerializer<Path> ser, JsonDeserializer<Path> de) {
        SimpleModule m = new SimpleModule("skill-manager-home-descriptor");
        m.addSerializer(Path.class, ser);
        m.addDeserializer(Path.class, de);
        return new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .registerModule(m);
    }

    /** Storage form for {@code path} relative to the home at {@code storeRoot}. */
    static String encodeTravelling(Path storeRoot, Path path) {
        if (path == null) return null;
        HomePaths paths = HomePaths.of(storeRoot);
        if (paths.isInsideHome(path)) return paths.encode(path);
        Path beside = storeRoot.getParent();
        if (beside != null) {
            Path abs = path.toAbsolutePath().normalize();
            if (abs.startsWith(beside)) {
                String rel = beside.relativize(abs).toString()
                        .replace(java.io.File.separatorChar, '/');
                return rel.isEmpty()
                        ? HomePaths.TOKEN + "/.."
                        : HomePaths.TOKEN + "/../" + rel;
            }
        }
        return path.toString();
    }

    /**
     * Runtime form of a stored path. Normalized, because the {@code ..}
     * segment above only means "the store's parent" once collapsed — an
     * un-normalized {@code <home>/.skill-manager/../.claude} compares
     * unequal to the {@code <home>/.claude} every caller works with.
     */
    static Path decodeTravelling(Path storeRoot, String stored) {
        Path decoded = HomePaths.of(storeRoot).decode(stored);
        return decoded == null ? null : decoded.normalize();
    }

    // ------------------------------------------------------- cli discovery

    /**
     * Locate the {@code skill-manager} executable that belongs with this
     * home, in the order a consumer would want it honoured:
     *
     * <ol>
     *   <li>{@code SKILL_MANAGER_CLI}, when it names an executable — the
     *       explicit pin, used by a harness that built its own.</li>
     *   <li>The running process's own command, when it really is a
     *       {@code skill-manager} launcher rather than a bare {@code java}
     *       or {@code jbang}. Self-reference is the most accurate answer
     *       available and needs no search.</li>
     *   <li>{@code <storeRoot>/bin/cli/skill-manager}, the home's own
     *       shim, before anything global — a per-project home that ships a
     *       CLI means to use it.</li>
     *   <li>{@code PATH}.</li>
     * </ol>
     *
     * <p>Returns null when none of those find one, and the field is then
     * omitted from the JSON rather than filled with a plausible guess: a
     * consumer that execs a wrong path fails confusingly, while one that
     * sees a missing field can say so.
     */
    public static Path resolveCli(Path storeRoot) {
        String pinned = System.getenv("SKILL_MANAGER_CLI");
        if (pinned != null && !pinned.isBlank()) {
            Path p = Path.of(pinned.trim());
            if (Files.isExecutable(p)) return p.toAbsolutePath().normalize();
        }
        Path own = ProcessHandle.current().info().command()
                .map(Path::of)
                .filter(HomeDescriptor::looksLikeSkillManagerLauncher)
                .orElse(null);
        if (own != null && Files.isExecutable(own)) return own.toAbsolutePath().normalize();
        if (storeRoot != null) {
            Path shim = storeRoot.resolve("bin").resolve("cli").resolve("skill-manager");
            if (Files.isExecutable(shim)) return shim.toAbsolutePath().normalize();
        }
        return onPath("skill-manager");
    }

    private static boolean looksLikeSkillManagerLauncher(Path command) {
        Path name = command.getFileName();
        if (name == null) return false;
        String base = name.toString().toLowerCase(Locale.ROOT);
        if (base.endsWith(".exe")) base = base.substring(0, base.length() - 4);
        return base.equals("skill-manager");
    }

    private static Path onPath(String binary) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;
        for (String part : path.split(java.io.File.pathSeparator)) {
            if (part.isBlank()) continue;
            Path candidate = Path.of(part, binary);
            if (Files.isExecutable(candidate)) return candidate.toAbsolutePath().normalize();
        }
        return null;
    }
}
