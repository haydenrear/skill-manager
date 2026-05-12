package dev.skillmanager.model;

/**
 * Parsed coordinate form. Sealed sum over the four shapes the
 * resolver eventually dispatches on:
 *
 * <ul>
 *   <li>{@link Bare} — {@code name} or {@code name@version}; kind is
 *       inferred at resolve time.</li>
 *   <li>{@link Kinded} — {@code skill:name} / {@code plugin:name},
 *       optionally {@code @version}; pins the resolver to one kind.</li>
 *   <li>{@link DirectGit} — {@code github:user/repo[#ref]} or
 *       {@code git+url[#ref]}; the resolver clones and inspects the
 *       on-disk shape to determine kind.</li>
 *   <li>{@link Local} — {@code file:///abs}, {@code ./rel},
 *       {@code ../rel}, or {@code /abs}; same on-disk-shape detection.</li>
 * </ul>
 *
 * <p>{@link #raw()} preserves the user's original input verbatim — the
 * canonical {@link #render()} form is parseable but may differ in
 * spacing or prefix choice (e.g. {@code github:foo/bar} round-trips
 * as itself, not as {@code git+https://github.com/foo/bar}).
 *
 * <p>This type is the input the resolver (ticket 04) dispatches on.
 * Ticket 02 only delivers parsing — no resolution, no fetching. The
 * exhaustive {@code switch} pattern in handlers downstream is what
 * makes adding new coord shapes a compiler-checked refactor.
 */
public sealed interface Coord permits Coord.Bare, Coord.Kinded, Coord.DirectGit, Coord.Local, Coord.SubElement {

    /** The raw input string, trimmed. Round-trip target for tests. */
    String raw();

    /** Re-render to a canonical, parseable form. Defaults to {@link #raw()}. */
    default String render() { return raw(); }

    // ----------------------------------------------------------------- shapes

    record Bare(String raw, String name, String version) implements Coord {}

    record Kinded(String raw, UnitKind kind, String name, String version) implements Coord {}

    record DirectGit(String raw, String url, String ref) implements Coord {}

    record Local(String raw, String path) implements Coord {}

    /**
     * Addresses one named entry inside a {@link AgentUnit}'s manifest —
     * e.g. one {@code [[sources]]} row of a doc-repo. The resolver still
     * resolves to the unit; {@link #elementName} is a binding-time
     * selector that {@code skill-manager bind} consumes.
     *
     * <p>Wraps an inner {@link Coord} that itself names the unit
     * ({@link Bare} or {@link Kinded} in practice — sub-element form on
     * a direct-git or local-path coord isn't accepted by the parser).
     * Versions and kind selectors live on the inner coord; the
     * sub-element segment is always the trailing {@code /<name>}.
     */
    record SubElement(String raw, Coord unitCoord, String elementName) implements Coord {}

    // ----------------------------------------------------------------- parser

    /**
     * Parse a coordinate string. Throws {@link IllegalArgumentException}
     * for blank / null input; otherwise always produces a {@link Coord}
     * — even malformed-looking strings fall through to {@link Bare}, on
     * the principle that the parser is permissive and the resolver is
     * strict (ambiguity becomes a {@code ResolutionError} in ticket 04,
     * not a parse error here).
     */
    static Coord parse(String input) {
        if (input == null) throw new IllegalArgumentException("coord must not be null");
        String c = input.trim();
        if (c.isEmpty()) throw new IllegalArgumentException("coord must not be blank");

        if (c.startsWith("skill:")) return parseKinded(c, UnitKind.SKILL, "skill:");
        if (c.startsWith("plugin:")) return parseKinded(c, UnitKind.PLUGIN, "plugin:");
        if (c.startsWith("github:")) return parseGithub(c);
        if (c.startsWith("git+")) return parseGitPlus(c);
        // Both `file://abs/path` and `file:./rel` (legacy single-slash) are
        // accepted; the path projection drops the prefix verbatim.
        if (c.startsWith("file://")) return new Local(c, c.substring("file://".length()));
        if (c.startsWith("file:")) return new Local(c, c.substring("file:".length()));
        if (c.startsWith("./") || c.startsWith("../") || c.startsWith("/")) return new Local(c, c);

        return parseBare(c);
    }

    private static Coord parseBare(String raw) {
        // body[@version] — the body may itself contain a sub-element selector.
        int at = raw.indexOf('@');
        String body = at < 0 ? raw : raw.substring(0, at);
        String version = at < 0 ? null : raw.substring(at + 1).trim();

        int slash = body.indexOf('/');
        if (slash < 0) {
            return new Bare(raw, body.trim(), version);
        }
        String unitName = body.substring(0, slash).trim();
        String element = body.substring(slash + 1).trim();
        // Synthesize the inner unit coord's raw form so render() round-trips.
        String innerRaw = at < 0 ? unitName : unitName + "@" + version;
        Bare inner = new Bare(innerRaw, unitName, version);
        return new SubElement(raw, inner, element);
    }

    private static Coord parseKinded(String raw, UnitKind kind, String prefix) {
        String body = raw.substring(prefix.length());
        int at = body.indexOf('@');
        String namePart = at < 0 ? body : body.substring(0, at);
        String version = at < 0 ? null : body.substring(at + 1).trim();

        int slash = namePart.indexOf('/');
        if (slash < 0) {
            return new Kinded(raw, kind, namePart.trim(), version);
        }
        String unitName = namePart.substring(0, slash).trim();
        String element = namePart.substring(slash + 1).trim();
        String innerRaw = prefix + unitName + (version == null ? "" : "@" + version);
        Kinded inner = new Kinded(innerRaw, kind, unitName, version);
        return new SubElement(raw, inner, element);
    }

    private static DirectGit parseGithub(String raw) {
        String rest = raw.substring("github:".length());
        int hash = rest.indexOf('#');
        String repo = hash < 0 ? rest : rest.substring(0, hash);
        String ref = hash < 0 ? null : rest.substring(hash + 1);
        return new DirectGit(raw, "https://github.com/" + repo, ref);
    }

    private static DirectGit parseGitPlus(String raw) {
        String rest = raw.substring("git+".length());
        int hash = rest.indexOf('#');
        String url = hash < 0 ? rest : rest.substring(0, hash);
        String ref = hash < 0 ? null : rest.substring(hash + 1);
        return new DirectGit(raw, url, ref);
    }
}
