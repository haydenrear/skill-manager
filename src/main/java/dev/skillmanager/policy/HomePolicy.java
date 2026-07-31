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

    /** Declare {@code policy} for {@code store}, replacing any prior file. */
    public static void write(SkillStore store, HomePolicy policy) throws IOException {
        Fs.ensureDir(store.root());
        Files.writeString(file(store), """
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
