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
import dev.skillmanager.launch.RunningCli;
import dev.skillmanager.shared.util.Fs;

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
     *
     * <p>The rule itself lives in {@link AgentHomes#homeRootFor}, because
     * skill-manager's own projector now derives its agent directories from
     * exactly this question and the two answers have to be one answer. They
     * were not: the descriptor published {@code <root>/.claude} while
     * projection wrote into {@code ~/.claude}, so a per-project home
     * advertised an agent directory nothing ever filled and filled one it had
     * no business touching. Issue #145.
     */
    public static Path homeRootFor(Path storeRoot) {
        return AgentHomes.homeRootFor(storeRoot);
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
     * Which step of {@link #locateCli} answered, and therefore how much the
     * answer is worth.
     *
     * <p>The distinction is the whole of issue #161. Three of these steps
     * identify a build somebody deliberately associated with this home or with
     * this process; the fourth is a guess that happens to be spelled
     * absolutely, which reads as authoritative and is not.
     */
    public enum CliSource {
        /** {@code SKILL_MANAGER_CLI} — an explicit pin from the caller. */
        PINNED_ENV(true),
        /**
         * {@code <storeRoot>/bin/cli/skill-manager} — the home's own
         * entrypoint, which {@code home shims} wrote.
         *
         * <p>It used to be modelled here as "the source that binds the home by
         * itself", and remedies built on it carried no binding. That is
         * <b>wrong twice</b>. HIS-9 makes the entrypoint REFUSE a mismatch with
         * exit 79 rather than silently rebinding, so "it binds its own home" is
         * no longer the whole story; and binding is not a property of which
         * build was found at all — see {@link #homeArg}.
         */
        HOME_ENTRYPOINT(true),
        /**
         * The build that is running right now, located through
         * {@link dev.skillmanager.launch.RunningCli} — which reads
         * {@code SKILL_MANAGER_INSTALL_DIR} rather than the process basename,
         * so it works in a shipped launcher.
         */
        RUNNING_BUILD(true),
        /**
         * A raw {@code PATH} walk. Nothing here says which build was found;
         * on a developer machine it is routinely an older release that answers
         * an unknown subcommand with top-level usage and exit 0 (#61).
         */
        PATH_FALLBACK(false),
        /**
         * Nothing resolved, or the only candidate left was this home's own
         * entrypoint with a DANGLING pin.
         */
        UNRESOLVED(false);

        private final boolean verified;

        CliSource(boolean verified) {
            this.verified = verified;
        }

        /**
         * Whether anything actually associated this executable with this home
         * or this process, as opposed to it merely being first on {@code PATH}.
         */
        public boolean verified() { return verified; }
    }

    /**
     * A located CLI and the step that located it.
     *
     * @param path null exactly when {@code source} is {@link CliSource#UNRESOLVED}
     */
    public record ResolvedCli(Path path, CliSource source, Path danglingHomePin) {
        public ResolvedCli(Path path, CliSource source) { this(path, source, null); }
    }

    /**
     * Locate the {@code skill-manager} executable that belongs with this home.
     * The precedence below is not decoration: it is parsed out of THIS javadoc
     * and checked against the walk the code performs, by
     * {@code HomeDescriptorCliRemedyTest} — "the javadoc's stated precedence is
     * the precedence the walk observed".
     *
     * <p>It said {@code HomeDescriptorTest} until #229's review, which is a
     * class that does not check this. A pointer naming the wrong checker is
     * DEF-021's pattern landing in the very javadoc whose accuracy is this
     * ticket's subject.
     *
     * <p>Note what it is NOT written as. A {@code {@link}} here would be
     * checkable by {@code javac -Xdoclint:reference} — and would also be a
     * main-source reference to a test class, which does not resolve on a
     * main-only compile. So this stays {@code @code}, which puts it in exactly
     * the family that flag cannot see. That is the boundary DEF-021 records,
     * standing in its own example.
     *
     * <ol>
     *   <li>{@code SKILL_MANAGER_CLI}, when it names an executable — the
     *       explicit pin, used by a harness that built its own.</li>
     *   <li>{@code <storeRoot>/bin/cli/skill-manager}, the home's own
     *       entrypoint, before anything global — a home that ships a CLI means
     *       to use it, and that file is the only candidate that binds this home
     *       rather than inheriting whatever home the caller carries. Skipped
     *       when the build it pins is GONE (DEF-012), because a remedy must not
     *       name a front door that cannot open.</li>
     *   <li>The build that is running right now, via
     *       {@link dev.skillmanager.launch.RunningCli#locate}.</li>
     *   <li>{@code PATH} — a guess, reported as {@link CliSource#PATH_FALLBACK}
     *       so a caller can decline to present it as authoritative.</li>
     * </ol>
     *
     * <h2>The step that used to be second was dead, and is gone</h2>
     *
     * <p>It read "the running process's own command, when it really is a
     * {@code skill-manager} launcher", implemented as
     * {@code ProcessHandle.current().info().command()} filtered on the basename
     * {@code skill-manager}. Every shipped distribution of this CLI is a shell
     * launcher that {@code exec}s a JVM, so that command is {@code java} or
     * {@code jbang} and the filter never matched — in ANY shipped
     * configuration. The javadoc claimed a precedence the code did not have,
     * and the step it claimed was the one that would have made the
     * {@code PATH} fallback rare.
     *
     * <p>{@link dev.skillmanager.launch.RunningCli} already answers that exact
     * question correctly, by reading the {@code SKILL_MANAGER_INSTALL_DIR}
     * back-reference every launcher exports, so the dead step is replaced by a
     * live one rather than merely deleted. It is placed AFTER the home's own
     * entrypoint, which is where the old ordering effectively put it.
     *
     * <p><b>What that ordering does and does not preserve.</b> For a home that
     * HAS a live entrypoint, the answer is unchanged: the entrypoint won
     * before and wins now. For every other home the answer CAN change, and the
     * change is the point — a live step 3 now answers where a raw {@code PATH}
     * walk used to. Four shapes move: a home with no {@code bin/cli} at all; a
     * home whose entrypoint's pin an upgrade deleted; a machine whose
     * {@code PATH} {@code skill-manager} is a foreign home's entrypoint (which
     * is now skipped outright); and a machine with no {@code skill-manager} on
     * {@code PATH}, which used to resolve nothing. In each, the previous answer
     * was a guess or a wrong home, which is what #161 is about. The claim worth
     * making is the narrow one, and it is what {@code HomeDescriptorTest}
     * pins: <em>a home that ships a working CLI still names that CLI.</em>
     *
     * <h2>Another home's entrypoint is never the answer</h2>
     *
     * <p>{@code <otherHome>/bin/cli/skill-manager} exports
     * {@code SKILL_MANAGER_HOME=<otherHome>} from its own location and
     * deliberately lets that win over the environment — so it cannot be
     * pointed at this home by any prefix a remedy could carry. Reached through
     * {@code PATH} (an operator with a home's {@code bin/cli} on their profile
     * PATH — the measured shape of DEF-002) it turns "re-run this against the
     * home you were in" into "silently edit a different home". Every step
     * therefore skips such a candidate rather than returning it.
     *
     * <p>Returns {@link CliSource#UNRESOLVED} with a null path when none of
     * those find one; the descriptor field is then omitted rather than filled
     * with a plausible guess, because a consumer that execs a wrong path fails
     * confusingly while one that sees a missing field can say so.
     */
    public static ResolvedCli locateCli(Path storeRoot) {
        return locateCli(storeRoot, System::getenv, RunningCli::locateOrNull);
    }

    /**
     * The testable form. {@code System.getenv} cannot be set from inside a JVM,
     * so a resolution rule driven only by the ambient environment is a rule
     * nobody can test — which is exactly how the dead second step survived
     * three releases and a javadoc that described it.
     *
     * @param env          environment lookup ({@code SKILL_MANAGER_CLI}, {@code PATH})
     * @param runningBuild the running launcher, or null when it cannot be established
     */
    static ResolvedCli locateCli(Path storeRoot,
                                 java.util.function.Function<String, String> env,
                                 java.util.function.Supplier<Path> runningBuild) {
        Path root = storeRoot == null ? null : storeRoot.toAbsolutePath().normalize();

        String pinned = env.apply("SKILL_MANAGER_CLI");
        if (pinned != null && !pinned.isBlank()) {
            Path p = normalizedExecutable(Path.of(pinned.trim()));
            if (p != null && !isForeignHomeEntrypoint(root, p)) {
                return new ResolvedCli(p, CliSource.PINNED_ENV);
            }
        }
        Path danglingPin = null;
        if (root != null) {
            Path own = normalizedExecutable(homeEntrypoint(root));
            if (own != null) {
                danglingPin = danglingPinIn(own);
                if (danglingPin == null) return new ResolvedCli(own, CliSource.HOME_ENTRYPOINT);
            }
        }
        Path running = runningBuild.get();
        if (running != null) {
            Path p = normalizedExecutable(running);
            if (p != null && !isForeignHomeEntrypoint(root, p)) {
                return new ResolvedCli(p, CliSource.RUNNING_BUILD, danglingPin);
            }
        }
        Path found = onPath(root, env.apply("PATH"));
        return found == null
                ? new ResolvedCli(null, CliSource.UNRESOLVED, danglingPin)
                : new ResolvedCli(found, CliSource.PATH_FALLBACK, danglingPin);
    }

    /**
     * The build {@code entrypoint} pins, when that build is GONE — otherwise
     * null.
     *
     * <h2>DEF-012: a pin that does not survive an upgrade of the thing it pins</h2>
     *
     * <p>Measured 2026-08-21, immediately after {@code brew upgrade
     * skill-manager} 0.23.0 → 0.24.0: the root home's entrypoint pinned
     * {@code /opt/homebrew/Cellar/skill-manager/0.23.0/libexec/bin/skill-manager},
     * a directory Homebrew had deleted. The shim file itself is still there
     * and still executable, so the {@code isExecutable} test every reader
     * (including this one) applies to it passed — while running it could only
     * ever produce exit 127.
     *
     * <p>This is the RESOLUTION half of DEF-012 and it is deliberately narrow:
     * a remedy must not name a front door that cannot open. It does not change
     * how a pin is written or what any command does; the pin's dangling is
     * carried into {@link CliSpelling#caveat()} and the next candidate answers
     * instead. DETECTING a damaged home — {@code home verify} returning exit 0
     * on a home whose own CLI is gone — is HIS-13's, and stays there.
     *
     * <p>Null for a file that is not one of ours, or whose pin is unreadable:
     * "cannot tell" is not "broken", and treating it as broken would push
     * every hand-written entrypoint off its own home.
     */
    private static Path danglingPinIn(Path entrypoint) {
        // One definition of "this pin is gone", in the class that writes the
        // file. A second spelling here is how two readers of one artifact come
        // to disagree, which is the whole subject of this epic.
        return dev.skillmanager.launch.LauncherShims.danglingPinIn(entrypoint).orElse(null);
    }

    /**
     * As {@link #locateCli}, keeping the pre-#161 shape for callers that only
     * want the path — the {@code home.runtime.json} {@code cli} field, which
     * has no room to carry a caveat.
     */
    public static Path resolveCli(Path storeRoot) {
        return locateCli(storeRoot).path();
    }

    /** {@code <storeRoot>/bin/cli/skill-manager}. */
    private static Path homeEntrypoint(Path storeRoot) {
        return storeRoot.resolve("bin").resolve("cli").resolve("skill-manager");
    }

    /**
     * Whether {@code candidate} is the pinned entrypoint of a Skill Manager
     * home that is NOT {@code storeRoot}.
     *
     * <p>Structural, and every part of the shape is required: the basename,
     * the {@code bin/cli} parents, and the enclosing directory actually being
     * a home ({@link dev.skillmanager.launch.LaunchEnv#looksLikeStoreRoot} —
     * the one predicate this repository asks that question with).
     *
     * <h2>The CANDIDATE is resolved first, and that was the hole</h2>
     *
     * <p>The first version of this resolved {@code owner} and {@code storeRoot}
     * but never the candidate — so it saw only the shape it was handed. The
     * most ordinary way to put a CLI on {@code PATH} is a symlink:
     *
     * <pre>
     * /usr/local/bin/skill-manager -> &lt;foreignHome&gt;/bin/cli/skill-manager
     * </pre>
     *
     * <p>whose parents are {@code /usr/local/bin} and {@code /usr/local}, not
     * {@code bin/cli} under a home — so the predicate returned false and
     * DEF-002 survived intact for that spelling, inside the fix for DEF-002.
     * The link is followed first, then the shape is read off the real file.
     * That is the spelling-invariance clause GOAL-one-home-one-answer states,
     * applied to the input as well as to the comparison.
     */
    static boolean isForeignHomeEntrypoint(Path storeRoot, Path candidate) {
        if (candidate == null) return false;
        candidate = Fs.realOrNormalized(candidate);
        if (!looksLikeSkillManagerLauncher(candidate)) return false;
        Path cliDir = candidate.getParent();
        if (cliDir == null || !"cli".equals(String.valueOf(cliDir.getFileName()))) return false;
        Path binDir = cliDir.getParent();
        if (binDir == null || !"bin".equals(String.valueOf(binDir.getFileName()))) return false;
        Path owner = binDir.getParent();
        if (owner == null || !dev.skillmanager.launch.LaunchEnv.looksLikeStoreRoot(owner)) {
            return false;
        }
        if (storeRoot == null) return true;
        return !Fs.realOrNormalized(owner).equals(Fs.realOrNormalized(storeRoot));
    }

    /**
     * How a remedy spells the {@code skill-manager} to run, and what it must
     * admit about it.
     *
     * @param storeRoot the home the remedy is about
     * @param cli       the located executable, or null
     * @param source    which step of {@link #locateCli} located it
     */
    public record CliSpelling(Path storeRoot, Path cli, CliSource source, Path danglingHomePin) {

        public CliSpelling(Path storeRoot, Path cli, CliSource source) {
            this(storeRoot, cli, source, null);
        }

        /** Just the executable, quoted so it survives being pasted into a shell. */
        public String binary() {
            return cli == null ? "skill-manager" : shellQuote(cli.toString());
        }

        /**
         * {@code --home <storeRoot>}, or the empty string when there is no home
         * to name. See {@link HomeDescriptor#homeArg} for why the binding lives
         * in an argument rather than in {@link #binary()}.
         */
        public String homeArg() {
            return HomeDescriptor.homeArg(storeRoot);
        }

        /**
         * The one line a caller must print under a remedy built from
         * {@link #binary()}, or null when there is nothing to admit.
         *
         * <p>Issue #161: the fix that made remedies absolute
         * ({@code /opt/homebrew/bin/skill-manager …}) made the {@code PATH}
         * case READ more authoritative while leaving it exactly as wrong — that
         * absolute path is the same binary the bare token would have resolved
         * to. Nothing distinguished the two for the reader. This is what
         * distinguishes them.
         */
        public String caveat() {
            // EVERY instruction here is itself a remedy, and the review of
            // #229 caught this one reintroducing DEF-002 inside the fix for
            // DEF-002: it was built from `binary()` alone, so a pasted
            // `<build> home shims` with no SKILL_MANAGER_HOME set re-pins the
            // operator's ROOT home. The dangling-pin branch had the identical
            // bug, and it fires exactly when the reader's front door is
            // already broken. `home shims` takes --home; it is used.
            String rePin = (binary() + " home shims " + homeArg()).strip();
            if (danglingHomePin != null) {
                // DEF-012's resolution half. Stated FIRST because it is the
                // more actionable fact: the reader's home has a broken front
                // door, and whatever this remedy fell through to is a
                // consequence of that rather than the thing to fix.
                return "this home's own CLI entrypoint pins a build that is gone ("
                        + danglingHomePin + ") — an upgrade deleted it, and the shim cannot "
                        + "open. This remedy fell through to " + (cli == null ? "nothing" : cli)
                        + ". Re-pin the home with `" + rePin + "`.";
            }
            return switch (source) {
                case PATH_FALLBACK -> "this skill-manager came from PATH (" + cli + "); nothing "
                        + "records it as the build that printed this message, and an older "
                        + "release answers an unknown subcommand with usage and exit 0. Give "
                        + "this home its own CLI with `" + rePin + "` to remove the guess.";
                case UNRESOLVED -> "no skill-manager could be located for " + storeRoot
                        + ", so this remedy names the bare command and your PATH decides which "
                        + "build runs it.";
                default -> null;
            };
        }

        /** Whether the located build is one somebody associated with this home or process. */
        public boolean verified() { return source.verified(); }
    }

    /**
     * How to spell {@code skill-manager} inside a remedy this build prints, so
     * that pasting the remedy runs a build that understands this home — and
     * runs it AGAINST this home.
     *
     * <p>Lives here rather than at each refusal because a guard at N call sites
     * is N chances to miss one. {@code home close-out} learned that the
     * expensive way: the fix went into {@code close-change.sh}, which corrected
     * the {@code --json} consumer and left the human path printing the
     * un-runnable spelling. The answer belongs where the string is built, and
     * every refusal that prints a remedy should reach for this.
     *
     * @param storeRoot the home the remedy is about
     */
    public static CliSpelling cliSpelling(Path storeRoot) {
        return cliSpelling(storeRoot, System::getenv, RunningCli::locateOrNull);
    }

    /** The testable form — see {@link #locateCli(Path, java.util.function.Function,
     * java.util.function.Supplier)} for why one exists. */
    static CliSpelling cliSpelling(Path storeRoot,
                                   java.util.function.Function<String, String> env,
                                   java.util.function.Supplier<Path> runningBuild) {
        Path root = storeRoot == null ? null : storeRoot.toAbsolutePath().normalize();
        ResolvedCli resolved = locateCli(root, env, runningBuild);
        return new CliSpelling(root, resolved.path(), resolved.source(),
                resolved.danglingHomePin());
    }

    /**
     * The head token of a remedy about {@code storeRoot}: which build to run.
     * Nothing else — see {@link #homeArg} for which home to run it against.
     */
    public static String cliInvocation(Path storeRoot) {
        return cliSpelling(storeRoot).binary();
    }

    /**
     * {@code --home <storeRoot>} — how a remedy names the home it is about.
     * Empty when {@code storeRoot} is null.
     *
     * <h2>The binding is a property of the VERB, not of the CLI token</h2>
     *
     * <p>#229's first attempt made it a property of {@link CliSource}: a remedy
     * whose build was not the home's own entrypoint got an
     * {@code env SKILL_MANAGER_HOME=<X>} prefix in front of the head token.
     * That was wrong three ways, and each was measured.
     *
     * <ol>
     *   <li><b>The head token is the one token every consumer replaces.</b>
     *       {@code home-sync}'s {@code remedyArgs} documents itself as dropping
     *       token 0 and re-running the remedy through its own CLI, and
     *       {@code close-change.sh}'s {@code run_cli} does the same. A binding
     *       carried there is silently deleted by all of them — and because that
     *       strip is guarded by {@code endsWith("skill-manager")}, an
     *       {@code env} prefix was not deleted but passed through as ARGUMENTS:
     *       {@code Unmatched arguments from index 0: '/usr/bin/env', …}. That
     *       took the {@code home-sync} graph red, 12 of 18 nodes skipped.
     *       {@code --home} lives in a token nobody substitutes.</li>
     *   <li><b>Some verbs already name their target.</b>
     *       {@code home sync --from <a> --to <b>} says which home it writes in
     *       its own arguments; a binding in front of it is redundant at best
     *       and contradictory at worst.</li>
     *   <li><b>An env prefix in the head token neutralizes the checks that
     *       guard remedies.</b> Three graph assertions require the head to be
     *       an absolute, executable path. {@code /usr/bin/env} satisfies both
     *       forever, whatever the resolution behind it does, so those readers
     *       go permanently green. With the head token a real per-home
     *       executable again, they assert something.</li>
     * </ol>
     *
     * <p>So a remedy binds by class of VERB. A verb that names its own target
     * binds nothing; every other verb takes {@code --home}, and the two that
     * did not ({@code sync}, {@code project sync}) were given it — additive,
     * and it changes nothing about what those operations do.
     *
     * <p>It is also the spelling that binds for ALL FOUR {@link CliSource}s,
     * including a home with no entrypoint of its own. The rejected alternative
     * — printing {@code <X>/bin/cli/skill-manager} as the head — assumed every
     * home has that file. It does not: only {@code home shims} writes it, and
     * {@code home clone} copies only what the source had. {@code home-sync}'s
     * own project and worktree fixtures have an empty {@code bin/cli}.
     */
    public static String homeArg(Path storeRoot) {
        return storeRoot == null ? "" : "--home " + shellQuote(storeRoot.toString());
    }

    /**
     * A token that survives being pasted into a shell, whatever is in it.
     *
     * <h2>Quoting on a space alone was a hole, and it was DEF-002's hole</h2>
     *
     * <p>The predecessor quoted only when it saw {@code ' '}. A home path
     * containing {@code $} was then emitted raw and the reader's shell expanded
     * it — so the remedy named, and bound, <em>a different home</em>: exactly
     * the failure this ticket exists to remove, produced by its own fix. A
     * {@code '} gave {@code unexpected EOF}; {@code ;}, {@code &}, a newline
     * and a tab are an injection surface whose input is a filesystem path,
     * which an operator, a fixture, or an attacker chooses.
     *
     * <p>The rule is inverted accordingly: quote everything unless every
     * character is in a small allowlist that is safe unquoted in every POSIX
     * shell. Single-quoting is total — no escape is interpreted inside it — and
     * an embedded {@code '} is closed, escaped and reopened.
     */
    public static String shellQuote(String raw) {
        if (raw == null || raw.isEmpty()) return "''";
        for (int i = 0; i < raw.length(); i++) {
            if (SAFE_UNQUOTED.indexOf(raw.charAt(i)) < 0) {
                return "'" + raw.replace("'", "'\\''") + "'";
            }
        }
        return raw;
    }

    /**
     * Characters that need no quoting in any POSIX shell. Deliberately small:
     * a character omitted here costs a pair of quotes, a character wrongly
     * included costs a remedy that does something else.
     */
    private static final String SAFE_UNQUOTED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-+=:,/@%^";

    private static Path normalizedExecutable(Path candidate) {
        if (candidate == null) return null;
        Path abs = candidate.toAbsolutePath().normalize();
        return Files.isExecutable(abs) ? abs : null;
    }

    private static boolean looksLikeSkillManagerLauncher(Path command) {
        Path name = command.getFileName();
        if (name == null) return false;
        String base = name.toString().toLowerCase(Locale.ROOT);
        if (base.endsWith(".exe")) base = base.substring(0, base.length() - 4);
        return base.equals("skill-manager");
    }

    private static Path onPath(Path storeRoot, String path) {
        if (path == null || path.isBlank()) return null;
        for (String part : path.split(java.io.File.pathSeparator)) {
            if (part.isBlank()) continue;
            Path candidate = normalizedExecutable(Path.of(part, "skill-manager"));
            if (candidate == null) continue;
            // A foreign home's entrypoint on PATH is the measured shape of
            // DEF-002. Skipped, not returned: it would rebind the remedy to a
            // home the operator was not in, and no prefix can stop it.
            if (isForeignHomeEntrypoint(storeRoot, candidate)) continue;
            return candidate;
        }
        return null;
    }
}
