package dev.skillmanager.commands;

import dev.skillmanager.util.Fs;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Scaffold a new skill directory containing a {@code SKILL.md} and a
 * fully-annotated {@code skill-manager.toml} that exercises every schema
 * option with {@code TODO} markers and commented-out examples.
 *
 * <p>Default output is {@code ./<name>/}; pass {@code --in <dir>} to write
 * under a different parent.
 */
@Command(name = "create", description = "Scaffold a new skill directory.")
public final class CreateCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Skill name (used as both directory name and [skill].name)")
    String name;

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
        if (!Files.exists(dir)) Fs.ensureDir(dir);

        String effectiveDescription = description == null || description.isBlank()
                ? "TODO: one-sentence description of what this skill helps the agent do. Mention when the agent should use it."
                : description;

        Files.writeString(dir.resolve("SKILL.md"), renderSkillMd(name, effectiveDescription));
        Files.writeString(dir.resolve("skill-manager.toml"), renderToml(name, version, effectiveDescription));

        Log.ok("created skill: %s", dir);
        System.out.println();
        System.out.println("next steps:");
        System.out.println("  1. edit " + dir.resolve("SKILL.md") + " — write the agent-facing body");
        System.out.println("  2. edit " + dir.resolve("skill-manager.toml") + " — uncomment & customize deps");
        System.out.println("  3. skill-manager add " + dir + "  (install locally to test)");
        System.out.println("  4. skill-manager publish " + dir + "  (upload to the registry)");
        return 0;
    }

    private static String renderSkillMd(String name, String description) {
        return """
                ---
                name: %s
                description: %s
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
                # Transitive skill dependencies. Any of:
                #   "skill:<name>"             → registry lookup, latest version
                #   "skill:<name>@<version>"   → registry lookup, pinned
                #   "file:./sub-skill"         → sub-skill directory under this skill
                #   "./../sibling"             → local path (same as file:, no prefix)
                skill_references = []

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
