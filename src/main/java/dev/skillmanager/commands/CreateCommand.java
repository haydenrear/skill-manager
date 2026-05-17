package dev.skillmanager.commands;

import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.ProgramInterpreter;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Scaffold a new skill directory containing a {@code SKILL.md} and a
 * fully-annotated {@code skill-manager.toml} that exercises every schema
 * option with {@code TODO} markers and commented-out examples.
 *
 * <p>Default output is {@code ./<name>/}; pass {@code --in <dir>} to write
 * under a different parent.
 */
@Command(name = "create", description = "Scaffold a new skill or plugin directory.")
public final class CreateCommand implements Callable<Integer> {

    public enum Kind { skill, plugin }

    @Parameters(index = "0", description = "Unit name (used as both directory name and [skill]/[plugin].name)")
    String name;

    @Option(names = "--kind", defaultValue = "skill",
            description = "Kind of unit to scaffold: skill (default) or plugin.")
    Kind kind;

    @Option(names = {"--in", "-i"}, description = "Parent directory (default: current working directory)")
    Path parentDir;

    @Option(names = "--version", defaultValue = "0.1.0",
            description = "Initial [skill].version (default: ${DEFAULT-VALUE})")
    String version;

    @Option(names = "--description", defaultValue = "",
            description = "Initial [skill].description (default: empty TODO placeholder)")
    String description;

    @Option(names = "--force", description = "Overwrite an existing directory")
    boolean force;

    @Option(names = "--dry-run",
            description = "Print the effect that would scaffold the skill without writing anything.")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        Path parent = parentDir != null ? parentDir : Path.of(System.getProperty("user.dir"));
        Path dir = parent.resolve(name);
        if (Files.exists(dir) && !force) {
            if (Files.isDirectory(dir)) {
                Log.error("directory exists: %s (pass --force to overwrite its contents)", dir);
            } else {
                Log.error("path exists and is not a directory: %s", dir);
            }
            return 1;
        }
        String effectiveDescription = description == null || description.isBlank()
                ? "TODO: one-sentence description of what this skill helps the agent do. Mention when the agent should use it."
                : description;

        Map<String, String> files = new LinkedHashMap<>();
        SkillEffect scaffoldEffect;
        if (kind == Kind.plugin) {
            String containedSkill = name + "-skill";
            files.put(".claude-plugin/plugin.json", renderPluginJson(name, version, effectiveDescription));
            files.put("skill-manager-plugin.toml", renderPluginToml(name, version, effectiveDescription));
            files.put("README.md", renderStarterMarkdown(
                    name,
                    "TODO: plugin-level author notes. Describe hooks, commands, agents, and contained skills."));
            files.put("skills/" + containedSkill + "/SKILL.md",
                    renderContainedSkillMd(containedSkill, effectiveDescription, name));
            files.put("skills/" + containedSkill + "/skill-manager.toml",
                    renderToml(containedSkill, version, effectiveDescription));
            files.put("skills/" + containedSkill + "/tools/cli.md", renderCliToolMarkdown());
            files.put("skills/" + containedSkill + "/tools/mcp.md", renderMcpToolMarkdown());
            scaffoldEffect = new SkillEffect.ScaffoldPlugin(dir, name, files);
        } else {
            files.put("SKILL.md", renderSkillMd(name, effectiveDescription));
            files.put("skill-manager.toml", renderToml(name, version, effectiveDescription));
            files.put("tools/cli.md", renderCliToolMarkdown());
            files.put("tools/mcp.md", renderMcpToolMarkdown());
            scaffoldEffect = new SkillEffect.ScaffoldSkill(dir, name, files);
        }

        SkillStore store = SkillStore.defaultStore();
        store.init();
        Program<Integer> program = new Program<>(
                "create-" + UUID.randomUUID(),
                List.of(scaffoldEffect),
                receipts -> 0);
        ProgramInterpreter interp = dryRun ? new DryRunInterpreter() : new LiveInterpreter(store, null);
        interp.run(program);
        if (dryRun) return 0;

        // Renderer already prints "created skill: <dir>" via the
        // SkillScaffolded fact — no second log here.
        System.out.println();
        System.out.println("next steps:");
        if (kind == Kind.plugin) {
            System.out.println("  1. edit " + dir.resolve(".claude-plugin/plugin.json") + " — Claude's plugin manifest");
            System.out.println("  2. edit " + dir.resolve("skill-manager-plugin.toml") + " — plugin-level deps");
            System.out.println("  3. edit " + dir.resolve("skills/" + name + "-skill/SKILL.md") + " — contained skill instructions");
            System.out.println("  4. skill-manager install " + dir + "  (install locally to test)");
            System.out.println("  5. skill-manager publish " + dir + "  (upload to the registry)");
        } else {
            System.out.println("  1. edit " + dir.resolve("SKILL.md") + " — write the agent-facing body");
            System.out.println("  2. edit " + dir.resolve("skill-manager.toml") + " — uncomment & customize deps");
            System.out.println("  3. skill-manager install " + dir + "  (install locally to test)");
            System.out.println("  4. skill-manager publish " + dir + "  (upload to the registry)");
        }
        return 0;
    }

    private static String renderPluginJson(String name, String version, String description) {
        return """
                {
                  "name": "%s",
                  "version": "%s",
                  "description": %s
                }
                """.formatted(name, version, jsonString(description));
    }

    private static String renderPluginToml(String name, String version, String description) {
        return """
                # skill-manager-plugin.toml — tooling-only metadata for this plugin.
                # Sits alongside .claude-plugin/plugin.json (which is Claude's runtime
                # manifest); skill-manager reads this file. The two MUST agree on
                # name + version, or skill-manager will warn at install time.

                # Plugin-level CLI deps. Apply to the whole plugin (rare — most deps
                # live on individual contained skills under skills/<name>/skill-manager.toml).
                # [[cli_dependencies]]
                # spec = "pip:cowsay==6.0"
                # on_path = "cowsay"

                # Plugin-level MCP deps. Same rationale — usually empty; contained
                # skills declare their own.
                # [[mcp_dependencies]]
                # name = "..."

                # References to other skills/plugins. Rarely needed at the plugin
                # level — contained skills carry their own skill_references.
                # references = []

                [plugin]
                name = "%s"
                version = "%s"
                description = %s
                """.formatted(name, version, tomlString(description));
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    private static String renderSkillMd(String name, String description) {
        return """
                ---
                name: %s
                description: %s
                skill-imports: []
                # Example import syntax:
                # skill-imports:
                #   - unit: skill-manager
                #     path: references/skill-imports.md
                #     reason: Explains semantic markdown imports between installed units.
                ---

                # %s

                TODO: short human-friendly intro. What does this skill do? When should the agent reach for it?

                ## When to use this skill

                - TODO: specific situations or user requests that should trigger this skill.
                - TODO: capability areas covered.

                ## Usage

                TODO: concrete instructions for the agent. Recipes, examples, and any safety caveats.
                """.formatted(name, escapeYaml(description), name);
    }

    private static String renderContainedSkillMd(String name, String description, String pluginName) {
        return """
                ---
                name: %s
                description: %s
                skill-imports: []
                # Example import syntax:
                # skill-imports:
                #   - unit: skill-manager
                #     path: references/skill-imports.md
                #     reason: Explains semantic markdown imports between installed units.
                ---

                # %s

                This contained skill ships with `%s`.

                ## When to use this skill

                - TODO: specific situations or user requests that should trigger this contained skill.

                ## Usage

                TODO: concrete instructions for the agent. Keep plugin-level behavior in the plugin README.
                """.formatted(name, escapeYaml(description), name, pluginName);
    }

    private static String renderStarterMarkdown(String title, String body) {
        return """
                ---
                skill-imports: []
                # Example import syntax:
                # skill-imports:
                #   - unit: skill-manager
                #     path: references/skill-imports.md
                #     reason: Explains semantic markdown imports between installed units.
                ---

                # %s

                %s
                """.formatted(title, body);
    }

    private static String renderCliToolMarkdown() {
        return """
                ---
                skill-imports:
                  - unit: skill-manager
                    path: references/cli.md
                    reason: Explains how skill-manager installs and exposes declared CLI tools.
                    section: cli-dependencies
                ---

                # CLI tools

                TODO: describe any CLI tools this unit expects the agent to use.

                Include concrete commands, expected inputs/outputs, and any fallback behavior when the tool is unavailable.
                """;
    }

    private static String renderMcpToolMarkdown() {
        return """
                ---
                skill-imports:
                  - unit: skill-manager
                    path: references/mcp.md
                    reason: Explains how MCP servers are registered and used through the virtual gateway.
                    section: mcp-dependencies
                ---

                # MCP tools

                TODO: describe any MCP tools this unit exposes or expects the agent to use.

                Include server ids, important tool paths, initialization requirements, and verification steps.
                """;
    }

    private static String escapeYaml(String s) {
        // Keep it simple: if the description contains YAML-sensitive chars, quote it.
        if (s.contains(":") || s.contains("#") || s.contains("\"") || s.contains("'")) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return s;
    }

    /**
     * Emits a full-schema TOML with every option documented. Most sections are
     * commented out so a fresh skill is empty-by-default; uncomment what you need.
     */
    private static String renderToml(String name, String version, String description) {
        return """
                # skill-manager.toml — tooling-only metadata for this skill. The agent
                # runtime reads SKILL.md; skill-manager reads this file. Invisible to
                # the agent.
                #
                # TOML scoping gotcha: top-level arrays like `skill_references = [...]`
                # must appear BEFORE the `[skill]` table or they'll silently become keys
                # of that table. Keep `[skill]` at the bottom of the file.

                # ----------------------------------------------------------- skill refs
                # Transitive unit dependencies. Add entries only when install should
                # resolve another unit before this one is usable. Markdown skill-imports
                # are semantic links to installed units and do not require matching
                # TOML references by default.
                # Any of:
                #   "github:owner/repo"        → direct GitHub source
                #   "skill:<name>"             → registry lookup, latest version
                #   "plugin:<name>"            → kind-pinned registry lookup
                #   "doc:<name>"               → doc-repo lookup
                #   "harness:<name>"           → harness lookup
                #   "file:./local-unit"        → local unit directory
                #   "./../sibling"             → local path (same as file:, no prefix)
                skill_references = [
                  # "github:owner/base-unit",
                ]

                # Or use the table form for explicit metadata:
                # [[skill_references]]
                # name = "base-skill"
                # version = "1.2.0"
                #
                # [[skill_references]]
                # path = "../shared-prompts"

                # ------------------------------------------------------- cli dependencies
                # CLI tools this skill needs on PATH. The `spec` prefix picks the backend:
                #   pip:<pkg>[==ver]     platform-independent (bundled uv)
                #   npm:<pkg>[@ver]      platform-independent (bundled node/npm)
                #   brew:<pkg>           macOS/Linux (Homebrew — symlinked into bin/cli)
                #   tar:<name>           download+extract from the install targets below
                #
                # Uncomment the blocks you need. Delete the rest.

                # Example 1: pip package
                # [[cli_dependencies]]
                # spec = "pip:ruff==0.6.9"           # TODO: your package, ideally pinned
                # on_path = "ruff"                    # detect "already installed" by this command
                # # min_version = "0.6.0"             # optional: fail below this version
                # # version_check = "ruff --version"  # optional: cmd to print version string

                # Example 2: npm package
                # [[cli_dependencies]]
                # spec = "npm:typescript@5.4.5"
                # on_path = "tsc"

                # Example 3: Homebrew formula (macOS/Linux only)
                # [[cli_dependencies]]
                # spec = "brew:fd"
                # on_path = "fd"

                # Example 4: tarball (per-platform download + sha256 pin)
                # [[cli_dependencies]]
                # spec = "tar:rg"                     # tar:<name> sets name + backend
                # name = "rg"                         # redundant for tar: but stable
                # on_path = "rg"
                # min_version = "14.0.0"
                # platform_independent = false        # true = use install.any only
                #
                # [cli_dependencies.install.darwin-arm64]
                # url = "https://github.com/BurntSushi/ripgrep/releases/download/14.1.1/ripgrep-14.1.1-aarch64-apple-darwin.tar.gz"
                # archive = "tar.gz"                  # "tar.gz" | "zip" | "raw"
                # binary = "ripgrep-14.1.1-aarch64-apple-darwin/rg"  # path within archive
                # sha256 = "TODO: fill in for integrity + policy.require_hash"
                # # extract = ["ripgrep-.../rg"]       # optional: restrict extraction
                #
                # [cli_dependencies.install.darwin-x64]
                # url = "https://.../ripgrep-14.1.1-x86_64-apple-darwin.tar.gz"
                # archive = "tar.gz"
                # binary = "ripgrep-14.1.1-x86_64-apple-darwin/rg"
                # sha256 = "TODO"
                #
                # [cli_dependencies.install.linux-x64]
                # url = "https://.../ripgrep-14.1.1-x86_64-unknown-linux-musl.tar.gz"
                # archive = "tar.gz"
                # binary = "ripgrep-14.1.1-x86_64-unknown-linux-musl/rg"
                # sha256 = "TODO"
                #
                # [cli_dependencies.install.linux-arm64]
                # url = "https://.../ripgrep-14.1.1-aarch64-unknown-linux-gnu.tar.gz"
                # archive = "tar.gz"
                # binary = "ripgrep-14.1.1-aarch64-unknown-linux-gnu/rg"
                # sha256 = "TODO"
                #
                # # Use "any" when platform_independent = true (e.g. a portable jar):
                # [cli_dependencies.install.any]
                # url = "https://.../tool.jar"
                # archive = "raw"
                # binary = "tool.jar"

                # ------------------------------------------------------- mcp dependencies
                # MCP servers this skill needs. skill-manager registers each with the
                # virtual MCP gateway; agents only ever see the gateway itself. Load
                # type is either `docker` (pulled image) or `binary` (downloaded archive
                # with optional init_script). Uncomment the blocks you need.

                # Example 1: docker-backed MCP server (stdio)
                # [[mcp_dependencies]]
                # name = "example-docker-mcp"            # TODO: unique id on the gateway
                # display_name = "Example Docker MCP"    # optional, human-readable
                # description = "A sample containerized MCP server."
                # idle_timeout_seconds = 1800            # optional: auto-undeploy if unused
                # required_tools = ["do_something"]      # optional: advertised tool paths
                #
                # [mcp_dependencies.load]
                # type = "docker"
                # image = "ghcr.io/publisher/mcp:v1"    # TODO: your image
                # pull = true                            # docker pull at register time
                # # platform = "linux/amd64"             # optional: --platform flag
                # # command = ["custom-entrypoint"]      # optional: override ENTRYPOINT
                # args = ["--stdio"]                     # passed after the image
                # env = { LOG_LEVEL = "info" }           # container env
                # volumes = []                           # e.g. ["/host:/inside:ro"]
                # transport = "stdio"                    # stdio | streamable-http | sse
                # # url = "http://..."                    # required when transport != stdio
                #
                # # Schema the gateway uses to prompt for init values at deploy time.
                # [[mcp_dependencies.init_schema]]
                # name = "api_key"
                # type = "string"                        # string | integer | boolean | ...
                # description = "API key for the service."
                # required = true
                # secret = true                          # redacts in describe output
                # # default = "fallback"                 # optional
                # # enum = ["foo", "bar"]                # optional: fixed value set
                #
                # # Optional: pre-fill init values (useful for non-secret defaults).
                # # [mcp_dependencies.initialization]
                # # environment = "production"

                # Example 2: binary-backed MCP server (downloaded + launched by the gateway)
                # [[mcp_dependencies]]
                # name = "example-binary-mcp"
                # display_name = "Example Binary MCP"
                # description = "A MCP server shipped as a per-platform tarball."
                #
                # [mcp_dependencies.load]
                # type = "binary"
                # # DANGEROUS: runs arbitrary shell at register time. policy.allow_init_scripts
                # # must be true (default: false).
                # # init_script = "chmod +x ./bin/server"
                # bin_path = "bin/server"                # relative to extracted archive root
                # args = ["--stdio"]
                # env = {}
                # transport = "stdio"
                # # url = "..."                           # required when transport != stdio
                #
                # [mcp_dependencies.load.install.darwin-arm64]
                # url = "https://.../server-darwin-arm64.tar.gz"
                # archive = "tar.gz"
                # binary = "bin/server"
                # sha256 = "TODO"
                #
                # [mcp_dependencies.load.install.linux-x64]
                # url = "https://.../server-linux-x64.tar.gz"
                # archive = "tar.gz"
                # binary = "bin/server"
                # sha256 = "TODO"

                # ------------------------------------------------------------------- skill
                # Keep the [skill] header at the bottom — TOML block-scoping will suck any
                # key that follows into this table.
                [skill]
                name = "%s"
                version = "%s"
                description = %s
                """.formatted(name, version, tomlString(description));
    }

    private static String tomlString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
