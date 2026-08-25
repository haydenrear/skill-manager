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
 *       touch {@code HomeCommand}. Narrowing those rows is a separate,
 *       verifiable step. What WRITES_HOME preserves is the <em>permission</em>
 *       to scaffold, not the <em>ordering</em> — see below, because the
 *       difference is a behaviour change and it is the interesting one.</li>
 *   <li>{@code exec} and {@code env run} launch a child <em>through</em> a
 *       home and bootstrap one when it is missing — WRITES_HOME, plainly.</li>
 * </ul>
 *
 * <h2>WRITES_HOME kept the permission, not the ordering</h2>
 *
 * <p>An earlier version of this comment claimed that leaving the {@code home}
 * family here "preserves today's behaviour exactly". That was false, and the
 * reasoning behind it is worth keeping as a warning: the permissive default
 * governs whether {@link dev.skillmanager.store.SkillStore#init()} is allowed
 * to create directories, and says nothing about <em>when</em> it runs. Before
 * this change the CLI's execution strategy called
 * {@code SkillStore.defaultStore().init()} from {@code tryReconcile} on every
 * invocation, <em>before</em> the parsed command executed, so by the time
 * {@code HomeCommand.requireHome} or {@code ExecCommand} asked "is the ambient
 * home a home?", the answer was yes — because starting the process had just
 * made it one. Removing the eager {@code init()} does not remove a permission;
 * it lets a refusal that was already in the code finally fire.
 *
 * <p>Measured against a fresh, empty decoy resolved through {@code pwd -P},
 * {@code 5f8d61e} (before) versus {@code 7d87a06} (after), with
 * {@code SKILL_MANAGER_HOME=$decoy} and no {@code --home}:
 *
 * <pre>{@code
 * command             before            after
 * home describe       exit 0, 12 new    exit 2, 0 new
 * home drift          exit 0, 12 new    exit 2, 0 new
 * home policy         exit 0, 12 new    exit 2, 0 new
 * home shims          exit 0, 17 new    exit 2, 0 new
 * exec --print-env    exit 0, 13 new    exit 2, 0 new
 * home                exit 2, 12 new    exit 2,  0 new
 * home verify         exit 2,  0 new    exit 2,  0 new
 * }</pre>
 *
 * <p>Exit 2 is {@link dev.skillmanager.store.NotAHomeException#EXIT_CODE}, and
 * the message is "… is not a Skill Manager home". That is <b>correct, not a
 * regression</b>: it is issue #33 ("{@code home describe}/{@code drift}/
 * {@code policy} lay out a full home at any path they are given") taking
 * effect. The refusal was written for exactly this, together with the
 * {@code --init} opt-in that covers the one legitimate gesture it removed —
 * declaring a policy or a descriptor on a home as it is being created. For a
 * path named by {@code --home} the refusal always worked, because the eager
 * scaffold materialized the <em>ambient</em> store and never the one
 * {@code --home} pointed at. For the ambient home it had been unreachable
 * since the day it was written ({@code ff6c92e} for {@code HomeCommand},
 * {@code a1946e6} for {@code exec}, both ancestors of {@code 5f8d61e}) — dead
 * code that read as a working guard. {@code home verify} is the control: it
 * refuses on a missing {@code --home} argument rather than on the ambient
 * home, so it exited 2 on both commits.
 *
 * <p>Callers were swept. Every in-repo caller of these commands names its home
 * explicitly and is therefore unaffected: the generated launcher shims run
 * {@code exec --home "$home"} ({@code LauncherShims.script}), and
 * {@code git-integration-repo/scripts/bootstrap-home.sh} runs {@code home
 * clone} first and then passes {@code --home "$STORE"} to every later command
 * including its {@code exec --print-env} verification. The one caller that
 * reads a home's descriptor without {@code --home} would be a person at a
 * prompt, and for them the refusal is the message they need.
 *
 * <h2>What did not change</h2>
 *
 * <p>{@code 7d87a06}'s commit body says "All 43 read-only invocations swept
 * clean; every exit code is unchanged." That is exact for the READ_ONLY set
 * and for nothing else. Every one of the 37 READ_ONLY paths below (43
 * invocations counting {@code --help} / {@code --version} / {@code install
 * --help} aliases) keeps its exit code and now writes nothing; the WRITES_HOME
 * paths in the table above changed exit code, as designed. Read the sentence
 * as scoped to its own list.
 *
 * <h2>A preview must not be the write (DEF-122)</h2>
 *
 * <p>Every row above answers "may this command create a home?". That question
 * turned out to have a second reader: {@code SkillManagerCli.tryReconcile}
 * gates itself on the declared access too, and reconciliation is not
 * {@code init()} — it ONBOARDS, writing an {@code installed/<unit>.json}
 * record for every unit present in the home without one. So a WRITES_HOME row
 * grants two permissions, and a {@code --dry-run} invocation was exercising
 * the second one before its own preview printed.
 *
 * <p>Measured on {@code ee1cbf8}, against a home holding one unit WITH an
 * {@code installed/} record and one WITHOUT — and with every home variable
 * UNSET, so the store resolved through {@code $HOME/.skill-manager}, the
 * operator's ROOT home:
 *
 * <pre>{@code
 * $ skill-manager remove probe-unit --dry-run
 * reconcile: onboarded=2 cleared=0
 *
 * DRY RUN — no changes will be made          <-- printed AFTER the writes
 * ...
 * 9 paths added: installed/probe-unit.json, bin/, bin/cli/, bin/mcp/,
 *                cache/, npm/, projects/, venvs/
 * and installed/control-unit.json REWRITTEN in place.
 * }</pre>
 *
 * <p>The stray record is the small half. The large half is that {@code
 * --dry-run} is what a careful operator reaches for to CHECK an instruction
 * before running it, so the mutation happened at exactly the moment someone
 * was being careful — and it converts the damage shape being inspected (a unit
 * with no {@code installed/} record) into a different one, so the home read
 * after the preview is not the home that was asked about.
 *
 * <p>Hence {@link #PREVIEW_OPTION}: {@code --dry-run} matched at the leaf makes
 * the invocation READ_ONLY, whatever the row says. It is {@link #INIT_GATED}'s
 * mirror — that map is "READ unless the invocation asked to create", this is
 * "WRITE unless the invocation promised to create nothing" — and it is general
 * rather than a {@code remove} special case because all twenty commands
 * declaring the flag describe it as writing nothing ("change nothing",
 * "without writing anything", "without touching the filesystem or ledger").
 * Two rows above previously carried a comment saying {@code --dry-run}
 * deliberately did NOT move them; those comments were answering "what
 * permission does this command need", and this table is asked "are bytes
 * written". Both now move.
 *
 * <p>What this costs, stated rather than discovered later: a preview no longer
 * runs reconciliation, so it previews the home AS IT IS rather than as it
 * would be after the real command's own reconcile pass. For {@code remove}
 * that is the correct answer and the whole point. For a preview whose plan
 * depends on records reconcile would have manufactured, the preview can be
 * narrower than the run — which is the safe direction, and the alternative is
 * a preview that mutates.
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
        m.put("artifacts", READ);        // parent; `artifacts record` writes
        m.put("artifacts list", READ);
        m.put("artifacts show", READ);
        m.put("artifacts stale", READ);
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
        // `sandbox status` reads the ENVIRONMENT, never the store: it answers
        // "is this process confined?" from the five home variables and the
        // working directory. Classified READ rather than given a fourth
        // level, because READ here means "does not scaffold", which is the
        // property this table decides.
        m.put("sandbox", READ);
        m.put("sandbox status", READ);
        m.put("search", READ);
        m.put("show", READ);
        m.put("unit", READ);                // parent; `unit publish` writes

        // ----------------------------------------------------------- writes home
        m.put("ads create", WRITE);
        m.put("ads delete", WRITE);
        m.put("artifacts record", WRITE);
        // `artifacts prune` DELETES from the home, so it is the strongest
        // WRITE in this table. --dry-run now moves it, along with every other
        // row that declares the flag -- see PREVIEW_OPTION and the DEF-122
        // section of this class comment for why the earlier note here ("access
        // is the permission the command needs, not the bytes one invocation
        // happened to write") was answering a different question than the one
        // this table is asked.
        m.put("artifacts prune", WRITE);
        m.put("bind", WRITE);
        // `build` re-derives artifacts into the home. Its --dry-run row is the
        // same one PREVIEW_OPTION now decides for every previewing command:
        // "Print what would be built and change nothing" is a promise about
        // bytes, and this table is what decides whether bytes are written.
        m.put("build", WRITE);
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
        // READ, not WRITE, and narrowed deliberately -- see the `home` family
        // note above, which called that narrowing "a separate, verifiable
        // step". This is that step, for the two rows whose own contracts say
        // they write nothing: `home verify` inspects a home and `home
        // close-out` documents itself as "Writes nothing; safe to run
        // repeatedly". Both were mutating the home named by --home, because
        // SkillManagerCli.tryReconcile ran ahead of them and was gated on
        // neither this classification nor the home policy. Review of #234,
        // HIGH-2. Neither declares --init, so nothing legitimate loses the
        // permission to scaffold.
        m.put("home verify", READ);
        // READ unless --init, and the narrowing is #234's HIGH-2 re-entering
        // through the one `home` subcommand nobody narrowed. Measured in review
        // of #241, against a home holding one unit and an EMPTY installed/:
        //
        //   BEFORE installed/: []
        //   $ skill-manager home describe --home $H --json
        //   AFTER  installed/: [intruder.json]
        //
        // WRITES_HOME let tryReconcile run ahead of DescribeCmd.call(), and it
        // reaches ReconcileUseCase -> OnboardUnit -> writeSource. So the
        // command documented as printing an interop descriptor was MANUFACTURING
        // the installed-unit records it then reported -- which also made
        // `home.membership.law`'s "a unit nobody installed" direction
        // structurally unreachable, because the only reader it had created the
        // missing record microseconds before comparing against it.
        //
        // Unlike `home verify` and `home close-out`, this row cannot simply be
        // READ: `home describe --init` exists precisely to lay a home out
        // first. Hence INIT_GATED -- read by default, write when the invocation
        // asks to create. Nothing legitimate loses the permission to scaffold.
        m.put("home describe", READ);
        m.put("home policy", WRITE);
        m.put("home shims", WRITE);
        m.put("home drift", WRITE);
        m.put("home sync", WRITE);
        m.put("home refresh-plugins", WRITE);
        m.put("home close-out", READ);
        // HIS-13. READ by default and WRITE only with --fix -- the same
        // invocation-dependent row `home describe` needed, and for a sharper
        // reason: the bare command is the OBSERVER (DEF-067), so classifying it
        // WRITE would have every detection run scaffold the home it was pointed
        // at, which is a mutation performed by the thing whose whole contract
        // is that it performs none.
        m.put("home repair", READ);
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
     * Command paths whose row is READ <em>unless</em> the invocation matched
     * the option that asks to create a home.
     *
     * <p>A third state was needed because the two existing ones cannot express
     * {@code home describe}: classified WRITE it mutates every home it is
     * pointed at (review of #241, H1), and classified READ outright it would
     * break {@code --init}, which exists to lay a home out. The row is a
     * property of the INVOCATION, not of the command.
     *
     * <p>{@code home policy} and {@code home shims} declare {@code --init} too
     * and are still flat WRITE. Narrowing them is the same one-line change and
     * is deliberately NOT made here: neither is on any path this ticket reads,
     * and each needs its own measurement of what it writes without the flag.
     */
    /**
     * The option that turns any row into {@link HomeScaffold.Access#READ_ONLY}.
     *
     * <p>{@link #INIT_GATED} handles the per-command case "READ unless the
     * invocation asked to create"; this is the whole-table mirror, "WRITE
     * unless the invocation promised to create nothing". Read the DEF-122
     * section of this class comment for the measurement — a {@code --dry-run}
     * that wrote nine paths into the operator's root home before printing
     * "no changes will be made".
     *
     * <p>Matched at the LEAF only, by the same {@link #matchedAtLeaf} the
     * init gate uses: {@code skill-manager --dry-run install} is not a thing
     * picocli accepts, and a parent that happened to declare the name should
     * not silently disarm a writing leaf.
     */
    static final String PREVIEW_OPTION = "--dry-run";

    private static final Map<String, String> INIT_GATED =
            Map.of("home describe", "--init",
                   // Not an --init at all, which is why the NAME of this map is
                   // now narrower than what it holds. Kept rather than renamed:
                   // the mechanism is exactly right -- "READ unless the
                   // invocation matched the option that makes it write" -- and
                   // a rename would touch every reader of a constant this
                   // ticket has no other business in. `home repair` writes only
                   // under --fix; see HomeRepair's class javadoc for why that
                   // separation is load-bearing rather than cosmetic.
                   "home repair", "--fix");

    /**
     * The mode for a parsed invocation: {@link HomeScaffold.Access#READ_ONLY}
     * whenever help or version was requested at any level, otherwise the leaf
     * command path's row — narrowed by {@link #INIT_GATED} where the row
     * depends on an option.
     *
     * <p>A null parse result is {@link HomeScaffold.Access#WRITES_HOME}, the
     * same answer {@link #of(String)} gives an unknown path. It shipped as
     * READ_ONLY, which was a fail-<em>closed</em> hole in a fail-open design
     * and contradicted both this class's "an unknown path still resolves to
     * WRITES_HOME" and {@code HomeScaffold}'s "the default is permissive, on
     * purpose". Picocli never hands the execution strategy a null today, so
     * the fix is unobservable — which is the reason to make it now rather than
     * to leave one branch disagreeing with the two paragraphs that describe
     * it. The direction matters if it ever does become reachable: guessing
     * WRITES_HOME leaves a reader creating directories it did not need, and
     * guessing READ_ONLY leaves a writer silently creating nothing.
     */
    public static HomeScaffold.Access of(CommandLine.ParseResult parseResult) {
        if (parseResult == null) return WRITE;
        if (helpRequested(parseResult)) return READ;
        String path = CliAgentContext.commandPath(parseResult);
        // Ahead of INIT_GATED deliberately, though nothing exercises the
        // overlap today: no command in the tree declares both an --init-style
        // option and --dry-run, and if one ever does, the flag that promises
        // to write nothing is the one that should win.
        if (matchedAtLeaf(parseResult, PREVIEW_OPTION)) return READ;
        String creates = path == null ? null : INIT_GATED.get(path.trim());
        if (creates != null && matchedAtLeaf(parseResult, creates)) return WRITE;
        return of(path);
    }

    /**
     * Whether the LEAF parse matched {@code option}.
     *
     * <p>{@code hasMatchedOption} throws for a name the leaf command does not
     * declare, which was harmless while the only callers were INIT_GATED rows
     * asking about their own command's flag. {@link #PREVIEW_OPTION} asks
     * every leaf, and most of them have no {@code --dry-run}, so the absent
     * case is now the common one and answering "no" is the whole job.
     */
    private static boolean matchedAtLeaf(CommandLine.ParseResult parseResult, String option) {
        CommandLine.ParseResult leaf = parseResult;
        while (leaf.hasSubcommand()) leaf = leaf.subcommand();
        if (leaf.commandSpec().findOption(option) == null) return false;
        return leaf.hasMatchedOption(option);
    }

    /**
     * {@link #helpRequested} for callers outside this class — the confinement
     * gate in {@code SkillManagerCli} exempts the same invocations this class
     * does, and it must ask the same question rather than spell a second one.
     */
    public static boolean helpOrVersionRequested(CommandLine.ParseResult parseResult) {
        return parseResult != null && helpRequested(parseResult);
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
