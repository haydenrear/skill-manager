package dev.skillmanager.command;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.cli.CliMetadata;
import dev.skillmanager.cli.SkillManagerCli;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class CliMetadataTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("CliMetadataTest");

        suite.test("metadata command catalog matches picocli command tree", () -> {
            CommandTree tree = commandTree();
            assertEquals(tree.commandPaths(), CliMetadata.commandPaths(), "command paths");
            assertEquals(tree.aliasesByPath(), CliMetadata.aliasesByCommandPath(), "command aliases");
        });

        suite.test("workflow metadata links to commands and docs", () -> {
            Set<String> commandPaths = CliMetadata.commandPaths();
            Set<String> workflowIds = CliMetadata.workflowIds();
            Map<String, String> links = CliMetadata.workflowCommandLinks();

            assertEquals(workflowIds, links.keySet(), "workflow link ids");
            for (CliMetadata.WorkflowMetadata workflow : CliMetadata.workflows()) {
                assertTrue(commandPaths.contains(workflow.commandPath()),
                        "workflow command exists: " + workflow.id());
                assertTrue(!workflow.examples().isEmpty(), "workflow has examples: " + workflow.id());
                assertTrue(!workflow.relatedSkillDocs().isEmpty(), "workflow has docs: " + workflow.id());
                assertTrue(workflow.agentContextAvailable(),
                        "workflow has agent context affordance: " + workflow.id());
            }
        });

        suite.test("workflow examples parse against picocli command tree", () -> {
            for (CliMetadata.WorkflowMetadata workflow : CliMetadata.workflows()) {
                for (String example : workflow.examples()) {
                    String[] parts = example.split("\\s+");
                    assertTrue(parts.length > 1, "example has command args: " + example);
                    assertEquals("skill-manager", parts[0], "example root: " + example);
                    new CommandLine(new SkillManagerCli())
                            .parseArgs(Arrays.copyOfRange(parts, 1, parts.length));
                }
            }
        });

        suite.test("every command the CLI prints back at a user actually parses", () -> {
            // The pattern, not the instance. `exec`'s drift refusal told its
            // reader to read the change with "home drift --show", and that
            // option has never existed — it answered "Unknown option: '--show'"
            // with exit 2. (Spelled without backticks here on purpose: the scan
            // reads main sources only today, and a quoted counter-example in a
            // file it might one day also read would fail this test with itself.)
            // That is the second time this epic shipped a
            // remedy that does not resolve (the first was a CLI pin that named
            // nothing), so the fix is a sweep with an oracle rather than one
            // corrected string.
            //
            // A remedy is the one instruction a refusal's reader has. One that
            // does not parse converts a one-command recovery into a hunt through
            // --help, and it fails in exactly the situation where the reader is
            // already stuck — which is why nothing catches it in normal use.
            //
            // Options are checked against the resolved subcommand rather than by
            // handing the whole line to parseArgs: a required positional would
            // make `skill-manager sync <name>` fail for having no <name>, which
            // is not the defect and would make this test noise.
            List<String> commands = backtickedCommands(sourceRoot());
            assertTrue(commands.size() >= 20,
                    "the scan found " + commands.size() + " command(s) — too few to be reading the"
                            + " real sources, and a scan that finds nothing passes vacuously");
            assertTrue(commands.contains("skill-manager home drift --ack"),
                    "the scan reaches the drift refusal's own remedies");

            List<String> broken = new java.util.ArrayList<>();
            for (String command : commands) {
                String problem = whyItWouldNotParse(command);
                if (problem != null) broken.add(command + "  →  " + problem);
            }
            assertEquals(List.of(), broken,
                    "every `skill-manager …` the sources quote names a real subcommand and real"
                            + " options");
        });

        suite.test("lockfile workflow examples point at distinct sync modes", () -> {
            Map<String, CliMetadata.WorkflowMetadata> byId = new LinkedHashMap<>();
            for (CliMetadata.WorkflowMetadata workflow : CliMetadata.workflows()) {
                byId.put(workflow.id(), workflow);
            }

            assertEquals(Set.of("skill-manager sync --refresh"),
                    Set.copyOf(byId.get("refresh-lockfile").examples()),
                    "refresh lockfile example");
            assertEquals(Set.of("skill-manager sync --lock units.lock.toml"),
                    Set.copyOf(byId.get("sync-lockfile").examples()),
                    "sync from lockfile example");
        });

        suite.test("representative modeled workflows are present", () -> {
            assertTrue(CliMetadata.commandPaths().contains("bindings show"),
                    "bindings show in metadata");
            assertEquals(Set.of("ls"), CliMetadata.aliasesByCommandPath().get("list"),
                    "list alias");
            for (String workflow : Set.of(
                    "install-local-unit",
                    "skill-scripts",
                    "project-env",
                    "sync-one-unit",
                    "project-profile-resolve")) {
                assertTrue(CliMetadata.workflowIds().contains(workflow),
                        "workflow present: " + workflow);
            }
        });

        return suite.runAll();
    }

    // ------------------------------------- the remedies the sources quote

    /**
     * {@code src/main/java}, found by walking up from the working directory.
     *
     * <p>Fails loudly when it cannot be found. A scan that silently reads
     * nothing reports zero broken commands, which is the vacuous pass this
     * whole file's sibling tests were written to avoid.
     */
    private static Path sourceRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path candidate = p.resolve("src/main/java/dev/skillmanager");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new AssertionError("cannot find src/main/java/dev/skillmanager from " + dir
                + " — run the suite from the repository root");
    }

    private static final Pattern BACKTICKED =
            Pattern.compile("`(skill-manager[ \\t][^`]*)`");

    /**
     * Every distinct {@code `skill-manager …`} the sources quote, normalized.
     *
     * <p>Java string concatenation and format placeholders are cut out rather
     * than resolved: what this test checks is the SPELLING of subcommands and
     * options, and a value that is interpolated at runtime is not a spelling.
     * Everything from the first {@code "} or {@code %} onwards is dropped, and
     * so is any token that is obviously a placeholder.
     */
    private static List<String> backtickedCommands(Path root) throws Exception {
        Set<String> out = new java.util.TreeSet<>();
        try (var walk = Files.walk(root)) {
            for (Path file : walk.toList()) {
                if (!file.toString().endsWith(".java")) continue;
                for (String line : Files.readAllLines(file)) {
                    Matcher m = BACKTICKED.matcher(line);
                    while (m.find()) {
                        String cleaned = clean(m.group(1));
                        if (cleaned != null) out.add(cleaned);
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    private static String clean(String raw) {
        int cut = raw.length();
        for (String boundary : List.of("\"", "%", "…", "<", "$")) {
            int i = raw.indexOf(boundary);
            if (i >= 0 && i < cut) cut = i;
        }
        String cleaned = raw.substring(0, cut).trim();
        return cleaned.equals("skill-manager") ? null : cleaned;
    }

    /**
     * Why {@code command} would not parse, or null when it would.
     *
     * <p>Walks the real picocli tree: leading words descend into subcommands
     * until one is not a subcommand (a positional), and every {@code -}-prefixed
     * token must be an option the command it lands on declares. Inherited
     * options come along, because picocli copies a {@code ScopeType.INHERIT}
     * option into every subcommand spec.
     */
    private static String whyItWouldNotParse(String command) {
        CommandLine current = new CommandLine(new SkillManagerCli());
        StringBuilder path = new StringBuilder("skill-manager");
        boolean positionalsStarted = false;
        for (String token : command.split("\\s+")) {
            if (token.equals("skill-manager")) continue;
            if (token.startsWith("-")) {
                String flag = token.contains("=") ? token.substring(0, token.indexOf('=')) : token;
                if (current.getCommandSpec().findOption(flag) == null) {
                    return "`" + path + "` has no option " + flag;
                }
                continue;
            }
            if (positionalsStarted) continue;
            CommandLine sub = current.getSubcommands().get(token);
            if (sub == null) {
                // Not a subcommand: from here on the words are arguments, which
                // this test deliberately does not judge.
                positionalsStarted = true;
                continue;
            }
            current = sub;
            path.append(' ').append(token);
        }
        return null;
    }

    private static CommandTree commandTree() {
        CommandLine root = new CommandLine(new SkillManagerCli());
        Set<String> commandPaths = new LinkedHashSet<>();
        Map<String, Set<String>> aliasesByPath = new LinkedHashMap<>();
        Set<CommandLine> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        collect(root, root.getCommandName(), commandPaths, aliasesByPath, seen);
        return new CommandTree(Set.copyOf(commandPaths), Map.copyOf(aliasesByPath));
    }

    private static void collect(CommandLine commandLine, String path, Set<String> commandPaths,
                                Map<String, Set<String>> aliasesByPath, Set<CommandLine> seen) {
        if (!seen.add(commandLine)) return;
        commandPaths.add(path);

        Set<String> aliases = new LinkedHashSet<>(
                Arrays.asList(commandLine.getCommandSpec().aliases()));
        if (!aliases.isEmpty()) aliasesByPath.put(path, Set.copyOf(aliases));

        for (CommandLine child : commandLine.getSubcommands().values()) {
            String childPath = path.equals("skill-manager")
                    ? child.getCommandName()
                    : path + " " + child.getCommandName();
            collect(child, childPath, commandPaths, aliasesByPath, seen);
        }
    }

    private record CommandTree(Set<String> commandPaths, Map<String, Set<String>> aliasesByPath) {}
}
