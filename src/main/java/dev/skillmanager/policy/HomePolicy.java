package dev.skillmanager.policy;

import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Whether a home may be mutated in place.
 *
 * <p>Two homes can both be correct and want opposite things. A dev
 * harness must be current, so it pulls and pushes constantly. An
 * experiment's agent home must be reproducible, so touching it after the
 * run invalidates the result. meta-orchestrator encodes the second as
 * prose asking humans to remember ("After the run finishes, freeze the
 * run directory: do not reuse it, do not sync or upgrade agent homes in
 * place"). Prose is not a guarantee; a run that gets synced looks
 * identical afterwards and its numbers are quietly wrong.
 *
 * <p>So the choice becomes per-home and declared, in
 * {@code home.policy.toml} at the home root:
 *
 * <pre>
 * policy = "frozen"
 * </pre>
 *
 * <p>{@link #FROZEN} refuses {@code sync}, {@code upgrade}, {@code project
 * sync}, and any other in-place mutation routed through
 * {@link #requireLive(SkillStore, String)}. {@link #LIVE} is the default
 * and the behaviour every home had before this existed, so adding the file
 * is opt-in and its absence is never ambiguous.
 *
 * <h2>Why an unreadable or unrecognized value fails loudly</h2>
 *
 * <p>The dangerous direction is asymmetric. Reading {@code frozen} as
 * {@code live} silently destroys the guarantee the file exists to provide;
 * reading {@code live} as {@code frozen} merely blocks a command with a
 * message naming the file. So a value this version does not recognize —
 * a typo, a future policy name — is an error, not a fallback to
 * {@link #LIVE}.
 *
 * <h2>The second key: {@link #LAZY_ARTIFACTS_KEY}</h2>
 *
 * <p>{@code lazy_artifacts} lives in the same file rather than in a new one,
 * and the reason is the same one {@link dev.skillmanager.artifacts.ArtifactLedger}
 * gives for staying out of {@code cli-lock.toml}, read the other way round:
 * this file is already the home's own declaration about how it may be treated,
 * it is already root-level {@code Surface.STATE} to a clone, and it already has
 * exactly one writer. A second policy file would be a second thing a clone has
 * to carry and a second place an operator has to look.
 *
 * <p>The cost of sharing it is that {@link #write} must not drop the other
 * key — {@code bootstrap-home.sh} calls {@code home policy live} on every
 * bootstrap, after the clone has decided the laziness — so both writers go
 * through {@link #writeFile} and each preserves what it did not come to
 * change. That is not a nicety: a rewrite that dropped {@code lazy_artifacts}
 * would silently turn a lazy home eager on its next {@code home policy} call,
 * and the symptom would be a bootstrap that got slower for no visible reason.
 */
public enum HomePolicy {

    /** Mutable in place: sync, upgrade, and push-back are allowed. */
    LIVE,

    /** Immutable in place: every in-place mutation is refused. */
    FROZEN;

    public static final String FILENAME = "home.policy.toml";

    private static final String KEY = "policy";

    /** The wire spelling: lowercase, as written in the TOML. */
    public String wire() { return name().toLowerCase(Locale.ROOT); }

    public boolean frozen() { return this == FROZEN; }

    /** The policy file for {@code store}, whether or not it exists. */
    public static Path file(SkillStore store) {
        return store.root().resolve(FILENAME);
    }

    /**
     * The declared policy for {@code store}, or {@link #LIVE} when no
     * {@code home.policy.toml} is present.
     *
     * @throws IOException when the file exists but cannot be understood
     */
    public static HomePolicy load(SkillStore store) throws IOException {
        Path f = file(store);
        if (!Files.isRegularFile(f)) return LIVE;
        TomlParseResult toml = Toml.parse(f);
        if (toml.hasErrors()) {
            throw new IOException(FILENAME + " has errors: " + toml.errors());
        }
        String raw = toml.getString(KEY);
        if (raw == null || raw.isBlank()) {
            throw new IOException(f + " has no `" + KEY + "` value; expected \"live\" or \"frozen\"");
        }
        return parse(raw, f);
    }

    /**
     * Parse a declared policy name. Rejects anything unrecognized rather
     * than defaulting — see the class javadoc for why silence here is the
     * expensive direction.
     */
    public static HomePolicy parse(String raw, Object origin) throws IOException {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (HomePolicy p : values()) {
            if (p.wire().equals(value)) return p;
        }
        throw new IOException((origin == null ? "" : origin + ": ")
                + "unknown home policy \"" + raw + "\"; expected \"live\" or \"frozen\"");
    }

    /**
     * Declare {@code policy} for {@code store}, preserving any declared
     * {@link #LAZY_ARTIFACTS_KEY}. See the class javadoc for why this rewrite
     * has to read before it writes.
     */
    public static void write(SkillStore store, HomePolicy policy) throws IOException {
        writeFile(store, policy, declaredLazyArtifacts(store));
    }

    // ------------------------------------------------------- lazy artifacts

    /** The key naming whether this home declares its artifacts before building them. */
    public static final String LAZY_ARTIFACTS_KEY = "lazy_artifacts";

    /**
     * What this home DECLARES about lazy artifacts, or null when it says
     * nothing.
     *
     * <p>Separate from {@link #lazyArtifacts} because the two answer different
     * questions and a caller that cannot tell them apart writes the default
     * back into the file as though it had been chosen. {@code home policy}
     * prints the declared value and the effective one, in those words.
     */
    public static Boolean declaredLazyArtifacts(SkillStore store) throws IOException {
        Path f = file(store);
        if (!Files.isRegularFile(f)) return null;
        TomlParseResult toml = Toml.parse(f);
        if (toml.hasErrors()) {
            throw new IOException(FILENAME + " has errors: " + toml.errors());
        }
        if (!toml.contains(LAZY_ARTIFACTS_KEY)) return null;
        Object raw = toml.get(LAZY_ARTIFACTS_KEY);
        if (raw instanceof Boolean b) return b;
        throw new IOException(f + ": `" + LAZY_ARTIFACTS_KEY + "` must be true or false, not "
                + (raw == null ? "null" : "\"" + raw + "\""));
    }

    /**
     * Whether this home declares its artifacts and builds them on demand.
     *
     * <p>The declared value when there is one, else {@link #lazyArtifactsDefault}.
     */
    public static boolean lazyArtifacts(SkillStore store) throws IOException {
        Boolean declared = declaredLazyArtifacts(store);
        return declared != null ? declared : lazyArtifactsDefault(store);
    }

    /**
     * <b>On for every home except the operator root.</b>
     *
     * <p>The owner's decision, recorded on ARTI-07: a project or worktree home
     * is created constantly, is thrown away just as often, and pays the whole
     * provisioning cost before an agent types anything. The operator root is
     * created once and is the machine's actual toolchain, so a cold artifact
     * there is a surprise with no worktree to blame it on.
     *
     * <p><b>The tier test is the one that already exists.</b>
     * {@code skt}'s {@code classify_tier} decides {@code root} by
     * {@code home.resolve() == root_home().resolve()} and everything else by
     * where the checkout is; the split this policy needs is exactly that first
     * comparison, so it is that comparison and not a second notion of tier.
     * {@code project} and {@code worktree} both default on, so the half of
     * {@code classify_tier} this does not reproduce is the half that would not
     * change the answer.
     *
     * <p>Deliberately NOT {@link SkillStore#defaultStore()}: that honours
     * {@code $SKILL_MANAGER_HOME}, so under a bootstrap — which exports it at
     * the home being created — every home would classify as the root and
     * nothing would ever be lazy.
     */
    public static boolean lazyArtifactsDefault(SkillStore store) {
        return !isRootHome(store);
    }

    /** Whether {@code store} IS the operator root home, {@code ~/.skill-manager}. */
    public static boolean isRootHome(SkillStore store) {
        if (store == null) return false;
        Path root = dev.skillmanager.agent.AgentHomes.userHome().resolve(".skill-manager");
        return normalize(store.root()).equals(normalize(root));
    }

    private static Path normalize(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        try {
            return abs.toRealPath();
        } catch (IOException e) {
            // A home that does not exist yet still has a name, and the name is
            // what this comparison is about.
            return abs;
        }
    }

    /** Declare {@code lazy} for {@code store}, preserving the live/frozen policy. */
    public static void writeLazyArtifacts(SkillStore store, boolean lazy) throws IOException {
        HomePolicy current;
        try {
            current = load(store);
        } catch (IOException e) {
            // A file this version cannot parse is not a reason to lose the
            // laziness decision; the load path still fails loudly for readers.
            current = LIVE;
        }
        writeFile(store, current, lazy);
    }

    private static void writeFile(SkillStore store, HomePolicy policy, Boolean lazy)
            throws IOException {
        Fs.ensureDir(store.root());
        StringBuilder sb = new StringBuilder("""
                # How this Skill Manager home may be treated.
                #
                #   live   — the default. `sync`, `upgrade`, and push-back may
                #            mutate this home in place.
                #   frozen — refuse every in-place mutation. Use for homes whose
                #            contents are evidence: an experiment run, a bisect
                #            checkpoint, anything whose numbers stop meaning
                #            something the moment the units under it move.
                #
                # Clone a frozen home (`skill-manager home clone --to <dir>`) to get
                # a live copy; the original stays as it was.
                policy = "%s"
                """.formatted(policy.wire()));
        if (lazy != null) {
            sb.append("""

                    # lazy_artifacts — whether this home DECLARES its derived
                    # artifacts and builds each on demand, instead of
                    # materializing all of them up front. Default: on for a
                    # project or worktree home, off for the operator root.
                    #
                    # A declared-and-not-built artifact is a normal state here,
                    # not a fault: `skill-manager home verify` reports it as
                    # `declared`, `skill-manager artifacts list` shows it as
                    # `declared-only`, and the entry point that needs it refuses
                    # by naming `skill-manager build <id>`.
                    %s = %s
                    """.formatted(LAZY_ARTIFACTS_KEY, lazy));
        }
        Files.writeString(file(store), sb.toString());
    }

    /**
     * Gate for an operation that mutates a home in place. No-op on a
     * {@link #LIVE} home.
     *
     * @param operation what is being refused, for the message
     * @throws FrozenHomeException when the home is {@link #FROZEN}
     */
    public static void requireLive(SkillStore store, String operation) throws IOException {
        if (store == null) return;
        if (load(store).frozen()) {
            throw new FrozenHomeException(operation, store.root());
        }
    }
}
