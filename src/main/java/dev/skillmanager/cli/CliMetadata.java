package dev.skillmanager.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Canonical command/workflow catalog for progressive CLI disclosure.
 *
 * <p>Picocli annotations remain the executable command tree. This catalog is
 * the stable metadata layer that links that tree to modeled workflows, bundled
 * skill documentation, examples, and future agent-facing context output.
 */
public final class CliMetadata {

    private CliMetadata() {}

    public record CommandMetadata(String path, List<String> aliases) {
        public CommandMetadata {
            aliases = List.copyOf(aliases);
        }
    }

    public record WorkflowMetadata(
            String id,
            String commandPath,
            List<String> examples,
            List<String> relatedSkillDocs,
            boolean agentContextAvailable) {
        public WorkflowMetadata {
            examples = List.copyOf(examples);
            relatedSkillDocs = List.copyOf(relatedSkillDocs);
        }
    }

    private static final List<CommandMetadata> COMMANDS = List.of(
            command("skill-manager"),
            command("ads"),
            command("ads list"),
            command("ads create"),
            command("ads delete"),
            command("artifacts"),
            command("artifacts list"),
            command("artifacts show"),
            command("artifacts stale"),
            command("artifacts record"),
            command("artifacts prune"),
            command("bind"),
            command("bindings"),
            command("build"),
            command("bindings list"),
            command("bindings show"),
            command("cli"),
            command("cli list"),
            command("cli show"),
            command("cli path"),
            command("create"),
            command("create-account"),
            command("deps"),
            command("env"),
            command("env sync"),
            command("env run"),
            command("exec"),
            command("gateway"),
            command("gateway up"),
            command("gateway down"),
            command("gateway status"),
            command("gateway set"),
            command("gateway attach"),
            command("gateway detach"),
            command("harness"),
            command("harness instantiate"),
            command("harness rm"),
            command("harness list"),
            command("harness show"),
            command("home"),
            command("home clone"),
            command("home verify"),
            command("home describe"),
            command("home policy"),
            command("home shims"),
            command("home drift"),
            command("home sync"),
            command("home refresh-plugins"),
            command("home close-out"),
            command("home repair"),
            command("install"),
            command("list", "ls"),
            command("lock"),
            command("lock status"),
            command("login"),
            command("login logout"),
            command("login show"),
            command("onboard"),
            command("pm"),
            command("pm install"),
            command("pm list"),
            command("pm which"),
            command("pm setup"),
            command("policy"),
            command("policy show"),
            command("policy init"),
            command("policy path"),
            command("project"),
            command("project register"),
            command("project resolve"),
            command("project sync"),
            command("project remove"),
            command("project show"),
            command("project list"),
            command("project profiles"),
            command("project profiles list"),
            command("publish"),
            command("registry"),
            command("registry set"),
            command("registry status"),
            command("rebind"),
            command("remove", "rm"),
            command("reset-password"),
            command("sandbox"),
            command("sandbox status"),
            command("search"),
            command("show"),
            command("sync"),
            command("unbind"),
            command("uninstall", "un"),
            command("unit"),
            command("unit publish"),
            command("upgrade")
    );

    /**
     * The authoring doc surface, and the reason it is a constant rather than a
     * literal.
     *
     * <h2>SI-18: it is not in this repository any more</h2>
     *
     * <p>These docs used to be read out of the in-tree {@code skill-publisher-skill/}
     * tree, and the name of that tree was what the metadata said. SI-18 deleted
     * the tree: it was a vendored snapshot of the skt plugin, and the authoring
     * docs now live in {@code unit-authoring}, a contained skill of the
     * {@code tla-spec-dev} plugin.
     *
     * <p>So the surface is named after the UNIT that holds it, not the
     * repository directory it used to be copied into — which is the spelling
     * that survives the unit moving between repositories again. Verified at
     * SI-18: all five workflows below, and their {@code --help} routes, are
     * documented in that skill upstream.
     *
     * <h2>Why the calls below spell the literal instead of using this</h2>
     *
     * <p>{@code specs/program_model/production_adapters.py} reads this file as
     * TEXT, and extracts each workflow's doc surfaces with
     * {@code re.findall(r'"([^"]+)"', …)} over the {@code docs(...)} argument.
     * A constant has no quotes, so {@code docs(UNIT_AUTHORING_DOCS)} yielded an
     * EMPTY surface set and {@code docs("skill-manager-skill", CONST)} silently
     * lost its second — which took
     * {@code test_cli_skill_docs_program_model.py} red. The constant stays for
     * the Java side to refer to; the call sites stay literal so the model can
     * still read them, and the docs test asserts every surface is either
     * in-tree or exactly this value, so the two cannot drift apart.
     */
    public static final String UNIT_AUTHORING_DOCS = "unit-authoring";

    private static final List<WorkflowMetadata> WORKFLOWS = List.of(
            workflow("account-auth", "login", docs("skill-manager-skill"),
                    "skill-manager login"),
            workflow("ads-manage", "ads", docs("skill-manager-skill"),
                    "skill-manager ads list"),
            workflow("author-dependencies", "create", docs("unit-authoring"),
                    "skill-manager create my-plugin --kind plugin"),
            workflow("author-unit", "create", docs("unit-authoring"),
                    "skill-manager create my-skill"),
            workflow("bind-projection", "bind", docs("skill-manager-skill"),
                    "skill-manager bind docs-team --to ./project"),
            workflow("cli-lock-inspect", "cli", docs("skill-manager-skill"),
                    "skill-manager cli list"),
            workflow("discover-installed-units", "list", docs("skill-manager-skill"),
                    "skill-manager list"),
            workflow("force-skill-scripts", "sync", docs("skill-manager-skill"),
                    "skill-manager sync acme-skill --force-scripts"),
            workflow("gateway-lifecycle", "gateway", docs("skill-manager-skill"),
                    "skill-manager gateway status"),
            // No `workflow(...)` rows for `home describe` / `home policy` /
            // `home shims` / `exec` / `unit publish` / `gateway attach` yet:
            // SkillManagerSkillDocsTest requires every
            // modeled workflow to be documented in the bundled skill docs,
            // and those docs live in the duplicated `skill-manager-skill/`
            // leaf, which must be edited in its own repository first (see
            // the integration repo's CLAUDE.md). The commands are catalogued
            // above; the workflow rows land with the leaf's doc change.
            workflow("harness-instantiate", "harness instantiate", docs("skill-manager-skill"),
                    "skill-manager harness instantiate app-harness --id dev"),
            workflow("harness-remove", "harness rm", docs("skill-manager-skill"),
                    "skill-manager harness rm dev"),
            workflow("inspect-unit", "show", docs("skill-manager-skill"),
                    "skill-manager show acme-skill"),
            workflow("install-git-unit", "install", docs("skill-manager-skill"),
                    "skill-manager install github:owner/repo"),
            workflow("install-local-unit", "install",
                    docs("skill-manager-skill", "unit-authoring"),
                    "skill-manager install file:./my-skill"),
            workflow("install-registry-unit", "install", docs("skill-manager-skill"),
                    "skill-manager install acme-skill"),
            workflow("onboard-default-skills", "onboard", docs("skill-manager-skill"),
                    "skill-manager onboard"),
            workflow("package-manager-bootstrap", "pm", docs("skill-manager-skill"),
                    "skill-manager pm setup"),
            workflow("policy-inspect", "policy", docs("skill-manager-skill"),
                    "skill-manager policy show"),
            workflow("project-env", "env sync", docs("skill-manager-skill"),
                    "skill-manager env sync --project-dir ."),
            workflow("project-profile-resolve", "project profiles", docs("skill-manager-skill"),
                    "skill-manager project profiles list"),
            workflow("project-register", "project register", docs("skill-manager-skill"),
                    "skill-manager project register"),
            workflow("project-resolve", "project resolve", docs("skill-manager-skill"),
                    "skill-manager project resolve"),
            workflow("publish-unit", "publish", docs("skill-manager-skill", "unit-authoring"),
                    "skill-manager publish ./my-skill"),
            workflow("rebind-projection", "rebind", docs("skill-manager-skill"),
                    "skill-manager rebind binding-id --to ./new-project"),
            workflow("refresh-lockfile", "sync", docs("skill-manager-skill"),
                    "skill-manager sync --refresh"),
            workflow("registry-lifecycle", "registry", docs("skill-manager-skill"),
                    "skill-manager registry status"),
            workflow("remove-installed-unit", "remove", docs("skill-manager-skill"),
                    "skill-manager remove acme-skill"),
            workflow("skill-scripts", "install", docs("unit-authoring"),
                    "skill-manager install file:./skill-with-scripts"),
            workflow("sync-all-units", "sync", docs("skill-manager-skill"),
                    "skill-manager sync"),
            workflow("sync-from-local-source", "sync", docs("skill-manager-skill"),
                    "skill-manager sync acme-skill --from ./source --yes"),
            workflow("sync-lockfile", "sync", docs("skill-manager-skill"),
                    "skill-manager sync --lock units.lock.toml"),
            workflow("sync-one-unit", "sync", docs("skill-manager-skill"),
                    "skill-manager sync acme-skill"),
            workflow("unbind-projection", "unbind", docs("skill-manager-skill"),
                    "skill-manager unbind binding-id"),
            workflow("upgrade-units", "upgrade", docs("skill-manager-skill"),
                    "skill-manager upgrade acme-skill")
    );


    /**
     * Every doc surface a workflow is allowed to name.
     *
     * <p>With {@link #inTreeDocSurfaces()} empty, "is this surface known?"
     * cannot be answered by "can we read it" any more — nothing is readable.
     * Without an explicit roster a misspelled surface would land in the
     * external bucket and look deliberate, which is the failure mode the
     * readable-surface check used to catch for free.
     *
     * <p>{@code skill-manager-skill} is still spelled as the repository
     * directory it was published from rather than as its unit name
     * ({@code skill-manager}), because that is what the program model's
     * {@code SkillDocSurfaces} says and the two are asserted equal. Renaming it
     * is a model change, not a string change.
     */
    public static Set<String> knownDocSurfaces() {
        return Set.of("skill-manager-skill", UNIT_AUTHORING_DOCS);
    }

    /**
     * Doc surfaces this repository carries on disk, and can therefore check
     * the contents of.
     *
     * <p>After OUN-6 this is EMPTY: every doc surface lives in another
     * repository, so no test in this one can read any of them, and a check that
     * quietly skipped them would be a green result standing for nothing. What
     * this repository CAN still own is which workflows point where: see
     * {@link #workflowsWithExternalDocs()}, which now pins all of them.
     */
    public static Set<String> inTreeDocSurfaces() {
        // EMPTY after OUN-6. skill-manager-skill/ was the last doc surface this
        // repository carried, and it is installed from its own repository now
        // — as a contained skill of tla-spec-dev, like every other unit.
        //
        // The consequence is stated rather than hidden: NO checker in this
        // repository can read ANY workflow's docs. What it can still own is
        // which workflows point where, and that is what
        // workflowsWithExternalDocs() pins — every one of them, now, to a
        // frozen list. A surface added here again would be readable and
        // checked; until then the honest answer is that content coverage lives
        // in the repositories that hold the content.
        return Set.of();
    }

    /**
     * The workflows whose docs live outside this repository, by id.
     *
     * <p>This is the assertion that keeps the external surface honest. A
     * checker cannot read those docs, so instead it pins WHICH workflows are
     * allowed to point at them: add a sixth and this set no longer matches,
     * and whoever added it has to say so deliberately rather than move a
     * workflow out of coverage by editing one string.
     */
    public static Set<String> workflowsWithExternalDocs() {
        Set<String> out = new java.util.TreeSet<>();
        for (WorkflowMetadata w : WORKFLOWS) {
            for (String surface : w.relatedSkillDocs()) {
                if (!inTreeDocSurfaces().contains(surface)) out.add(w.id());
            }
        }
        return out;
    }

    public static List<CommandMetadata> commands() {
        return COMMANDS;
    }

    public static Set<String> commandPaths() {
        return COMMANDS.stream()
                .map(CommandMetadata::path)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static Map<String, Set<String>> aliasesByCommandPath() {
        Map<String, Set<String>> aliases = new LinkedHashMap<>();
        for (CommandMetadata command : COMMANDS) {
            if (!command.aliases().isEmpty()) {
                aliases.put(command.path(), Set.copyOf(command.aliases()));
            }
        }
        return Map.copyOf(aliases);
    }

    public static List<WorkflowMetadata> workflows() {
        return WORKFLOWS;
    }

    public static Set<String> workflowIds() {
        return WORKFLOWS.stream()
                .map(WorkflowMetadata::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static Map<String, String> workflowCommandLinks() {
        Map<String, String> links = new LinkedHashMap<>();
        for (WorkflowMetadata workflow : WORKFLOWS) {
            links.put(workflow.id(), workflow.commandPath());
        }
        return Map.copyOf(links);
    }

    private static CommandMetadata command(String path, String... aliases) {
        return new CommandMetadata(path, List.of(aliases));
    }

    private static WorkflowMetadata workflow(
            String id, String commandPath, List<String> docs, String example) {
        return new WorkflowMetadata(id, commandPath, List.of(example), docs, true);
    }

    private static List<String> docs(String... docs) {
        return List.of(docs);
    }
}
