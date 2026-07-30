package dev.skillmanager.cli;

import dev.skillmanager.store.HomeScaffold;
import picocli.CommandLine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The durable answer to "may this command create a home?", one row per
 * command path.
 *
 * <p>It is the data half of {@link HomeScaffold}: that class owns the
 * mechanism (a declared mode, honoured at the single
 * {@link dev.skillmanager.store.SkillStore#init()} choke point) and this one
 * owns the classification. Read {@code HomeScaffold}'s class comment first —
 * it carries the measured defect and why the fix is general rather than a
 * {@code --version} special case.
 *
 * <h2>The rule</h2>
 *
 * <p>A command is {@link HomeScaffold.Access#READ_ONLY} when running it
 * against a home that does not exist should produce <em>output and no
 * bytes</em>. It may read a home that is already there; it must not bring one
 * into being. Everything else — anything that installs, syncs, binds,
 * projects, writes credentials, starts a gateway, registers a project, or
 * provisions another home — is {@link HomeScaffold.Access#WRITES_HOME} and
 * keeps first-run creation exactly as it was.
 *
 * <p>Two things are read-only regardless of the row:
 * <ul>
 *   <li><b>Help and version</b>. {@code skill-manager install --help} is a
 *       request for text, not an install, so a usage- or version-help request
 *       at any level of the parse overrides the leaf's classification. This is
 *       the case a per-command table alone would get wrong, and it is exactly
 *       the reported defect.</li>
 *   <li><b>Group parents</b> ({@code home}, {@code project}, {@code gateway},
 *       …). Invoked without a subcommand they print their own usage and
 *       return, so the leaf path for {@code skill-manager project} is
 *       {@code project} and it prints text.</li>
 * </ul>
 *
 * <h2>Where the borderline rows landed, and why</h2>
 *
 * <ul>
 *   <li>{@code search} and {@code registry status} talk to the registry but
 *       write nothing locally — READ_ONLY. {@code login} bare is the browser
 *       OAuth flow rather than a usage print and caches a bearer token under
 *       the home, so the whole {@code login} family except {@code login show}
 *       writes.</li>
 *   <li>{@code lock status} reports drift; {@code lock} bare prints usage.
 *       Neither writes. {@code sync --lock} does, and that is {@code sync}.</li>
 *   <li>{@code policy show} / {@code policy path} report the effective policy,
 *       defaults included, without materializing {@code policy.toml};
 *       {@code policy init} exists precisely to write it.</li>
 *   <li>{@code pm list} / {@code pm which} report on installed package
 *       managers; {@code pm install} and {@code pm setup} fetch them into the
 *       home.</li>
 *   <li>The {@code home} family is classified WRITES_HOME <b>as a family</b>,
 *       including the informational {@code home describe} / {@code home
 *       verify} / {@code home drift}. That is a deliberate under-claim, not a
 *       judgement that they write: {@code home}'s whole subject matter is
 *       provisioning homes, several of its subcommands take a {@code --home}
 *       naming a home other than the ambient one, and this change does not
 *       touch {@code HomeCommand}. Narrowing those three rows is a separate,
 *       verifiable step — and because the default is permissive, leaving them
 *       here preserves today's behaviour exactly.</li>
 *   <li>{@code exec} and {@code env run} launch a child <em>through</em> a
 *       home and bootstrap one when it is missing — WRITES_HOME, plainly.</li>
 * </ul>
 *
 * <h2>Completeness</h2>
 *
 * <p>Every path in {@link CliMetadata#commandPaths()} appears below;
 * {@code LazyHomeScaffoldTest} fails when one does not, so a command cannot
 * be added to the tree without someone answering this question for it. An
 * unknown path still resolves to {@link HomeScaffold.Access#WRITES_HOME},
 * so the failure mode of forgetting is "kept the old eager behaviour", never
 * "a writer lost its directories".
 */
public final class CommandHomeAccess {

    private static final HomeScaffold.Access READ = HomeScaffold.Access.READ_ONLY;
    private static final HomeScaffold.Access WRITE = HomeScaffold.Access.WRITES_HOME;

    private static final Map<String, HomeScaffold.Access> BY_PATH = byPath();

    private CommandHomeAccess() {}

    private static Map<String, HomeScaffold.Access> byPath() {
        Map<String, HomeScaffold.Access> m = new LinkedHashMap<>();

        // ------------------------------------------------------------ read-only
        m.put("skill-manager", READ);       // bare invocation prints usage
        m.put("ads", READ);
        m.put("ads list", READ);
        m.put("bindings", READ);
        m.put("bindings list", READ);
        m.put("bindings show", READ);
        m.put("cli", READ);
        m.put("cli list", READ);
        m.put("cli show", READ);
        m.put("cli path", READ);
        m.put("deps", READ);
        m.put("env", READ);                 // parent; `env sync` / `env run` write
        m.put("gateway", READ);
        m.put("gateway status", READ);
        m.put("harness", READ);
        m.put("harness list", READ);
        m.put("harness show", READ);
        m.put("list", READ);
        m.put("lock", READ);
        m.put("lock status", READ);
        m.put("login show", READ);
        m.put("pm", READ);
        m.put("pm list", READ);
        m.put("pm which", READ);
        m.put("policy", READ);
        m.put("policy show", READ);
        m.put("policy path", READ);
        m.put("project", READ);
        m.put("project list", READ);
        m.put("project show", READ);
        m.put("project profiles", READ);
        m.put("project profiles list", READ);
        m.put("registry", READ);
        m.put("registry status", READ);
        m.put("search", READ);
        m.put("show", READ);
        m.put("unit", READ);                // parent; `unit publish` writes

        // ----------------------------------------------------------- writes home
        m.put("ads create", WRITE);
        m.put("ads delete", WRITE);
        m.put("bind", WRITE);
        m.put("create", WRITE);
        m.put("create-account", WRITE);
        m.put("env sync", WRITE);
        m.put("env run", WRITE);
        m.put("exec", WRITE);
        m.put("gateway up", WRITE);
        m.put("gateway down", WRITE);
        m.put("gateway set", WRITE);
        m.put("gateway attach", WRITE);
        m.put("gateway detach", WRITE);
        m.put("harness instantiate", WRITE);
        m.put("harness rm", WRITE);
        m.put("home", WRITE);
        m.put("home clone", WRITE);
        m.put("home verify", WRITE);
        m.put("home describe", WRITE);
        m.put("home policy", WRITE);
        m.put("home shims", WRITE);
        m.put("home drift", WRITE);
        m.put("home sync", WRITE);
        m.put("home close-out", WRITE);
        m.put("install", WRITE);
        // `login` bare is the browser OAuth flow, not a usage print: it
        // caches the bearer token under the home. Only `login show` reads.
        m.put("login", WRITE);
        m.put("login logout", WRITE);
        m.put("onboard", WRITE);
        m.put("pm install", WRITE);
        m.put("pm setup", WRITE);
        m.put("policy init", WRITE);
        m.put("project register", WRITE);
        m.put("project resolve", WRITE);
        m.put("project sync", WRITE);
        m.put("project remove", WRITE);
        m.put("publish", WRITE);
        m.put("rebind", WRITE);
        m.put("registry set", WRITE);
        m.put("remove", WRITE);
        m.put("reset-password", WRITE);
        m.put("sync", WRITE);
        m.put("unbind", WRITE);
        m.put("uninstall", WRITE);
        m.put("unit publish", WRITE);
        m.put("upgrade", WRITE);

        return Map.copyOf(m);
    }

    /** Every classified command path. */
    public static Set<String> classifiedPaths() { return BY_PATH.keySet(); }

    /** The paths that must leave a non-existent home non-existent. */
    public static Set<String> readOnlyPaths() {
        return BY_PATH.entrySet().stream()
                .filter(e -> e.getValue() == READ)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * The mode for a command path on its own, ignoring help requests.
     * Unknown paths are {@link HomeScaffold.Access#WRITES_HOME}.
     */
    public static HomeScaffold.Access of(String commandPath) {
        if (commandPath == null || commandPath.isBlank()) return WRITE;
        return BY_PATH.getOrDefault(commandPath.trim(), WRITE);
    }

    /**
     * The mode for a parsed invocation: {@link HomeScaffold.Access#READ_ONLY}
     * whenever help or version was requested at any level, otherwise the leaf
     * command path's row.
     */
    public static HomeScaffold.Access of(CommandLine.ParseResult parseResult) {
        if (parseResult == null) return READ;
        if (helpRequested(parseResult)) return READ;
        return of(CliAgentContext.commandPath(parseResult));
    }

    /** True when {@code --help} or {@code --version} was asked for at any level. */
    private static boolean helpRequested(CommandLine.ParseResult parseResult) {
        for (CommandLine.ParseResult pr = parseResult; pr != null; pr = pr.subcommand()) {
            if (pr.isUsageHelpRequested() || pr.isVersionHelpRequested()) return true;
            if (!pr.hasSubcommand()) return false;
        }
        return false;
    }
}
