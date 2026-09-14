///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeVerdictsSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * A skill-script install in a child home never writes through a {@code bin/cli}
 * link into its parent (OHV-9, #367, DEF-OHV-011).
 *
 * <h2>The shape, as measured on the operator's root home on 2026-09-14</h2>
 *
 * <p>A test project home's {@code bin/cli/computeq}, {@code helm-deploy} and
 * {@code monitoring} were symlinks to {@code ~/.skill-manager/bin/cli/<tool>} —
 * a sanctioned parent-store mirror. deploy-helm's installer ran in the project
 * home and wrote its launcher with {@code cat >"$launcher"}, which followed the
 * link and rewrote the ROOT's shims. The install reported success.
 *
 * <h2>What this node plants, all under the fixture's scratch root</h2>
 *
 * <ul>
 *   <li>a PARENT home with a real {@code bin/cli/wt-tool} (and a neighbour
 *       entry, so "the parent is unchanged" is about more than one file);</li>
 *   <li>a CHILD home whose {@code bin/cli/wt-tool} is an absolute symlink to the
 *       parent's, and a {@code child-homes/} claim in the parent that makes the
 *       link SANCTIONED — the CDC home's shape, and the one
 *       {@code InstallerRegistry.refuseAForeignDestination} deliberately walks
 *       past;</li>
 *   <li>a unit whose skill-script writes
 *       {@code cat >"$SKILL_MANAGER_HOME/bin/cli/wt-tool"} with this home's
 *       paths expanded into it, as deploy-helm's helper does.</li>
 * </ul>
 *
 * <p>Then it installs the unit into the child with the real CLI, and asserts:
 * every file of the parent home is byte-identical (links compared by target);
 * the child holds its own REAL shim; that shim is token-form (spells no form of
 * the child home, derives it from {@code SKILL_MANAGER_SHIM_HOME}) and names no
 * form of the parent; and it runs the child's tool.
 *
 * <p>Never the operator's homes, never {@code $HOME/.skill-manager}. The
 * scratch directory is deleted in a finally and nothing is published, so the
 * fixpoint and membership laws never see these homes (see
 * {@code HomeVerdictsSupport}). The gateway URL points at an unresolvable
 * remote host, so {@code EnsureGateway} reports it unreachable instead of
 * building a venv: this graph stays Docker- and network-free.
 */
public class ChildInstallWritesOnlyItself {

    static final NodeSpec SPEC = NodeSpec.of("home.verdicts.child.install.writes.only.itself")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(HomeVerdictsSupport.FIXTURE)
            .tags("home", "verdicts", "shim", "write-through", "ohv-9")
            .timeout("600s");

    static final String TOOL = "wt-tool";
    static final String UNIT = "wt-unit";
    static final String PARENT_BYTES = "#!/bin/sh\necho parent-owned\n";
    /** Non-local and unresolvable: EnsureGateway answers "remote gateway unreachable" and moves on. */
    static final String NO_GATEWAY = "http://gateway.home-verdicts.invalid:9";

    public static void main(String[] args) {
        Node.run(args, SPEC, ChildInstallWritesOnlyItself::check);
    }

    private static NodeResult check(NodeContext ctx) {
        String id = SPEC.id();
        String scratchStr = ctx.get(HomeVerdictsSupport.FIXTURE, "scratchRoot").orElse(null);
        if (scratchStr == null) {
            return NodeResult.fail(id, "missing " + HomeVerdictsSupport.FIXTURE + " context (scratchRoot)");
        }
        Path work = Path.of(scratchStr).resolve("child-install-writes-only-itself");
        NodeResult result;
        try {
            HomeVerdictsSupport.deleteRecursively(work);
            Path parent = HomeVerdictsSupport.layOutHome(work.resolve("parent"));
            Path child = HomeVerdictsSupport.layOutHome(work.resolve("child"));

            Path parentShim = parent.resolve("bin/cli").resolve(TOOL);
            Files.writeString(parentShim, PARENT_BYTES, StandardCharsets.UTF_8);
            parentShim.toFile().setExecutable(true);
            Path neighbour = parent.resolve("bin/cli/wt-neighbour");
            Files.writeString(neighbour, "#!/bin/sh\necho neighbour\n", StandardCharsets.UTF_8);
            neighbour.toFile().setExecutable(true);

            Path childShim = child.resolve("bin/cli").resolve(TOOL);
            Files.createSymbolicLink(childShim, parentShim);
            claim(parent, child);
            relaxPolicy(child);
            Path unit = scaffoldUnit(work.resolve("units").resolve(UNIT));

            // CONTROL: the leak shape is really there before the install.
            boolean planted = Files.isSymbolicLink(childShim)
                    && childShim.toRealPath().equals(parentShim.toRealPath());

            Map<String, String> before = snapshot(parent);
            HomeVerdictsSupport.Verdict install = HomeVerdictsSupport.smWithEnv(ctx, "install-into-child",
                    child, Map.of("SKILL_MANAGER_GATEWAY_URL", NO_GATEWAY),
                    "install", unit.toString(), "--yes", "--no-bind-default");
            Map<String, String> after = snapshot(parent);

            List<String> changed = new ArrayList<>();
            for (String k : before.keySet()) {
                if (!before.get(k).equals(after.get(k))) changed.add(k + (after.containsKey(k) ? " (changed)" : " (gone)"));
            }
            for (String k : after.keySet()) {
                if (!before.containsKey(k)) changed.add(k + " (added)");
            }
            boolean parentIdentical = changed.isEmpty();
            boolean parentShimIdentical = PARENT_BYTES.equals(HomeVerdictsSupport.read(parentShim));

            boolean childOwnsShim = Files.isRegularFile(childShim, LinkOption.NOFOLLOW_LINKS);
            String body = childOwnsShim ? HomeVerdictsSupport.read(childShim) : "";
            List<String> childSpellings = HomeVerdictsSupport.spellings(child).stream()
                    .filter(body::contains).toList();
            boolean tokenForm = childOwnsShim && childSpellings.isEmpty()
                    && body.contains("${SKILL_MANAGER_SHIM_HOME}");
            List<String> parentSpellings = HomeVerdictsSupport.spellings(parent).stream()
                    .filter(body::contains).toList();
            boolean namesNoParent = childOwnsShim && parentSpellings.isEmpty();
            String ran = childOwnsShim ? runShim(childShim) : "";
            boolean runsChildTool = ran.contains("child-built");

            List<String> failures = new ArrayList<>();
            if (!planted) failures.add("control: the child's bin/cli/" + TOOL + " was not a link to the parent's shim");
            if (install.exit() != 0) failures.add("install into the child exited " + install.exit() + ": "
                    + HomeVerdictsSupport.head(install.stderr()));
            if (!parentIdentical) failures.add("the parent home changed during the child's install: " + changed);
            if (!childOwnsShim) failures.add("the child's bin/cli/" + TOOL + " is not a real file of its own");
            if (!tokenForm) failures.add("the child's shim is not token-form (spells " + childSpellings + "): " + body);
            if (!namesNoParent) failures.add("the child's shim names the parent home " + parentSpellings + ": " + body);
            if (!runsChildTool) failures.add("running the child's shim printed: " + ran);

            result = (failures.isEmpty() ? NodeResult.pass(id) : NodeResult.fail(id, String.join(" | ", failures)))
                    .process(install.proc())
                    .assertion("control_the_child_link_resolves_to_the_parents_shim", planted)
                    .assertion("install_into_the_child_exits_0", install.exit() == 0)
                    .assertion("the_parents_shim_is_byte_identical", parentShimIdentical)
                    .assertion("every_parent_file_is_byte_identical_after_the_child_install", parentIdentical)
                    .assertion("the_child_holds_its_own_real_shim", childOwnsShim)
                    .assertion("the_child_shim_is_token_form", tokenForm)
                    .assertion("the_child_shim_names_no_form_of_the_parent", namesNoParent)
                    .assertion("the_child_shim_runs_the_childs_tool", runsChildTool)
                    .metric("parent.files.changed", changed.size())
                    .metric("install.exit", install.exit())
                    .log("child shim after install:\n" + body);
        } catch (IOException | RuntimeException e) {
            result = NodeResult.error(id, e);
        } finally {
            try {
                HomeVerdictsSupport.deleteRecursively(work);
            } catch (IOException ignored) {
                // reported below
            }
        }
        return result.assertion("the_scratch_homes_are_deleted", !Files.exists(work, LinkOption.NOFOLLOW_LINKS));
    }

    /** The parent claims the child, so the child's link is a sanctioned mirror. */
    private static void claim(Path parent, Path child) throws IOException {
        Path dir = Files.createDirectories(parent.resolve("child-homes/hv-write-through"));
        Files.writeString(dir.resolve("child-home.json"), """
                {
                  "id" : "hv-write-through",
                  "parentHome" : %s,
                  "childHome" : %s,
                  "units" : [ ],
                  "createdAt" : "2026-09-14T00:00:00Z"
                }
                """.formatted(HomeVerdictsSupport.jsonString(parent.toString()),
                HomeVerdictsSupport.jsonString(child.toString())), StandardCharsets.UTF_8);
    }

    /** The scratch home is not under EnvPrepared's policy root; relax the install gates here. */
    private static void relaxPolicy(Path store) throws IOException {
        Files.writeString(store.resolve("policy.toml"), """
                require_confirmation = false
                [install]
                require_confirmation_for_hooks = false
                require_confirmation_for_mcp = false
                require_confirmation_for_cli_deps = false
                require_confirmation_for_executable_commands = false
                """, StandardCharsets.UTF_8);
    }

    /** A skill whose installer writes its launcher the way deploy-helm's helper does. */
    private static Path scaffoldUnit(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("skill-scripts"));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + UNIT
                + "\ndescription: home-verdicts write-through fixture\n---\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%1$s"
                version = "0.1.0"
                description = "home-verdicts write-through fixture"

                [[cli_dependencies]]
                spec = "skill-script:%2$s"
                on_path = "%2$s"

                [cli_dependencies.install.any]
                script = "install-%2$s.sh"
                binary = "%2$s"
                """.formatted(UNIT, TOOL), StandardCharsets.UTF_8);
        Path installer = dir.resolve("skill-scripts").resolve("install-" + TOOL + ".sh");
        // Unquoted heredoc: $venv expands at write time (the freeze deploy-helm
        // ships), \$@ stays literal. `cat >` is the redirection that follows a link.
        Files.writeString(installer, """
                #!/usr/bin/env bash
                set -euo pipefail
                launcher="${SKILL_MANAGER_HOME:?}/bin/cli/%1$s"
                venv="$SKILL_MANAGER_CACHE_DIR/skill-script-%2$s-%1$s/venv"
                mkdir -p "$venv/bin"
                printf '#!/bin/sh\\necho child-built\\n' > "$venv/bin/%1$s"
                chmod +x "$venv/bin/%1$s"
                cat >"$launcher" <<EOF
                #!/usr/bin/env bash
                exec "$venv/bin/%1$s" "\\$@"
                EOF
                chmod 0755 "$launcher"
                """.formatted(TOOL, UNIT), StandardCharsets.UTF_8);
        installer.toFile().setExecutable(true);
        return dir;
    }

    /** Every entry under {@code home}, never following links: file -> bytes, link -> target. */
    private static Map<String, String> snapshot(Path home) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (var walk = Files.walk(home)) {
            for (Path p : walk.toList()) {
                String rel = home.relativize(p).toString();
                if (Files.isSymbolicLink(p)) {
                    out.put(rel, "link:" + Files.readSymbolicLink(p));
                } else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    out.put(rel, "file:" + java.util.HexFormat.of().formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
                } else {
                    out.put(rel, "dir");
                }
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static String runShim(Path shim) {
        try {
            Process p = new ProcessBuilder("bash", shim.toString()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "timed out";
            }
            return "exit " + p.exitValue() + ": " + out;
        } catch (IOException e) {
            return "could not run: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }
}
