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
            command("bind"),
            command("bindings"),
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
            command("gateway"),
            command("gateway up"),
            command("gateway down"),
            command("gateway status"),
            command("gateway set"),
            command("harness"),
            command("harness instantiate"),
            command("harness rm"),
            command("harness list"),
            command("harness show"),
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
            command("search"),
            command("show"),
            command("sync"),
            command("unbind"),
            command("uninstall", "un"),
            command("upgrade")
    );

    private static final List<WorkflowMetadata> WORKFLOWS = List.of(
            workflow("account-auth", "login", docs("skill-manager-skill"),
                    "skill-manager login"),
            workflow("ads-manage", "ads", docs("skill-manager-skill"),
                    "skill-manager ads list"),
            workflow("author-dependencies", "create", docs("skill-publisher-skill"),
                    "skill-manager create my-plugin --kind plugin"),
            workflow("author-unit", "create", docs("skill-publisher-skill"),
                    "skill-manager create my-skill"),
            workflow("bind-projection", "bind", docs("skill-manager-skill"),
                    "skill-manager bind docs-team --to ./project"),
            workflow("cli-lock-inspect", "cli", docs("skill-manager-skill"),
                    "skill-manager cli list"),
            workflow("discover-installed-units", "list", docs("skill-manager-skill"),
                    "skill-manager list"),
            workflow("force-skill-scripts", "sync", docs("skill-manager-skill", "skill-dev-skill"),
                    "skill-manager sync acme-skill --force-scripts"),
            workflow("gateway-lifecycle", "gateway", docs("skill-manager-skill"),
                    "skill-manager gateway status"),
            workflow("harness-instantiate", "harness instantiate", docs("skill-manager-skill"),
                    "skill-manager harness instantiate app-harness --id dev"),
            workflow("harness-remove", "harness rm", docs("skill-manager-skill"),
                    "skill-manager harness rm dev"),
            workflow("inspect-unit", "show", docs("skill-manager-skill"),
                    "skill-manager show acme-skill"),
            workflow("install-git-unit", "install", docs("skill-manager-skill"),
                    "skill-manager install github:owner/repo"),
            workflow("install-local-unit", "install",
                    docs("skill-manager-skill", "skill-publisher-skill", "skill-dev-skill"),
                    "skill-manager install file:./my-skill"),
            workflow("install-registry-unit", "install", docs("skill-manager-skill"),
                    "skill-manager install acme-skill"),
            workflow("onboard-default-skills", "onboard", docs("skill-manager-skill"),
                    "skill-manager onboard"),
            workflow("package-manager-bootstrap", "pm", docs("skill-manager-skill"),
                    "skill-manager pm setup"),
            workflow("policy-inspect", "policy", docs("skill-manager-skill"),
                    "skill-manager policy show"),
            workflow("project-env", "env sync", docs("skill-manager-skill", "skill-dev-skill"),
                    "skill-manager env sync --project-dir ."),
            workflow("project-profile-resolve", "project profiles", docs("skill-manager-skill"),
                    "skill-manager project profiles list"),
            workflow("project-register", "project register", docs("skill-manager-skill"),
                    "skill-manager project register"),
            workflow("project-resolve", "project resolve", docs("skill-manager-skill"),
                    "skill-manager project resolve"),
            workflow("publish-unit", "publish", docs("skill-manager-skill", "skill-publisher-skill"),
                    "skill-manager publish ./my-skill"),
            workflow("rebind-projection", "rebind", docs("skill-manager-skill"),
                    "skill-manager rebind binding-id --to ./new-project"),
            workflow("refresh-lockfile", "sync", docs("skill-manager-skill"),
                    "skill-manager sync --refresh"),
            workflow("registry-lifecycle", "registry", docs("skill-manager-skill"),
                    "skill-manager registry status"),
            workflow("remove-installed-unit", "remove", docs("skill-manager-skill"),
                    "skill-manager remove acme-skill"),
            workflow("skill-scripts", "install", docs("skill-publisher-skill"),
                    "skill-manager install file:./skill-with-scripts"),
            workflow("sync-all-units", "sync", docs("skill-manager-skill"),
                    "skill-manager sync"),
            workflow("sync-from-local-source", "sync", docs("skill-manager-skill", "skill-dev-skill"),
                    "skill-manager sync acme-skill --from ./source --yes"),
            workflow("sync-lockfile", "sync", docs("skill-manager-skill"),
                    "skill-manager sync --refresh"),
            workflow("sync-one-unit", "sync", docs("skill-manager-skill"),
                    "skill-manager sync acme-skill"),
            workflow("unbind-projection", "unbind", docs("skill-manager-skill"),
                    "skill-manager unbind binding-id"),
            workflow("upgrade-units", "upgrade", docs("skill-manager-skill"),
                    "skill-manager upgrade acme-skill")
    );

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
