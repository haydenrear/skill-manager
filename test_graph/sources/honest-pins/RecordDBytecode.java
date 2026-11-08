///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HonestPinsKit.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Record shape (d), #400: {@code __pycache__} bytecode is published by
 * {@code unit publish}.
 *
 * <p>Plant: a unit with a {@code tool.py}, published to a local bare remote and
 * installed from it ({@code git+file://}). In the home, an agent edits
 * {@code SKILL.md} and a local test run leaves
 * {@code __pycache__/tool.cpython-312.pyc} behind, untracked. Then
 * {@code unit publish <unit> --direct}.
 *
 * <p>HONEST: the commit that lands on the remote's main does not contain any
 * {@code __pycache__/} path or {@code *.pyc}. At 0.28.2 it does
 * (cdc-agent-substrate-plugin 1dfc3633), and every consumer syncing from that
 * tree then refuses with "extra local changes".
 *
 * <p>CONTROL: the real edit to {@code SKILL.md} IS published — the fix must not
 * get there by publishing nothing.
 *
 * <p>SYNC HALF (HP-6): two consumer homes install the unit from the same
 * remote BEFORE the publish, so the publish leaves both behind. Each runs a
 * local test that leaves untracked {@code __pycache__} bytecode in its copy.
 * Then {@code sync <unit> --git-latest}.
 *
 * <p>HONEST: the consumer with ONLY bytecode is not refused with "extra local
 * changes" — it reaches the published commit, and the bytecode is still on
 * disk (not counted, and not destroyed).
 *
 * <p>CONTROL: the consumer with bytecode AND a real edit to {@code SKILL.md} is
 * still refused, and keeps both its HEAD and its edit — the fix must not get
 * there by blinding the local-change test.
 */
public class RecordDBytecode {
    static final String ID = "honest.record-d";
    static final String UNIT = "hp-record-d-unit";
    static final String EDIT = "AGENT EDIT TO PUBLISH";
    static final String CONSUMER_EDIT = "CONSUMER LOCAL EDIT";
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("honest-pins", "record", "unit-publish", "pycache", "400")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path root;
            Path bare;
            Path home;
            String before;
            try {
                root = HonestPinsKit.scratch(ctx, "record-d");
                bare = root.resolve("remote.git");
                Path seed = root.resolve("seed");
                home = root.resolve("home");
                Files.createDirectories(home);
                HonestPinsKit.git(root, "init", "--quiet", "--bare", "-b", "main", bare.toString());
                HonestPinsKit.writeSkill(seed, UNIT, "0.1.0", "original body");
                Files.writeString(seed.resolve("tool.py"), "print('tool')\n");
                HonestPinsKit.git(seed, "init", "-b", "main", "--quiet");
                HonestPinsKit.commitAll(seed, "initial");
                HonestPinsKit.git(seed, "push", "--quiet", bare.toUri().toString(), "HEAD:refs/heads/main");
                before = HonestPinsKit.gitOut(bare, "rev-parse", "main");
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e).assertion("setup_ok", false);
            }

            Path unitDir = home.resolve("skills").resolve(UNIT);
            ProcessRecord install = HonestPinsKit.sm(ctx, "install", root, home,
                    "install", "git+" + bare.toUri(), "--yes", "--no-bind-default");
            boolean setup = install.exitCode() == 0 && before.equals(HonestPinsKit.head(unitDir));
            try {
                Path skill = unitDir.resolve("SKILL.md");
                Files.writeString(skill, HonestPinsKit.read(skill) + EDIT + "\n");
                Files.createDirectories(unitDir.resolve("__pycache__"));
                Files.write(unitDir.resolve("__pycache__/tool.cpython-312.pyc"),
                        new byte[]{(byte) 0xcb, 0x0d, 0x0d, 0x0a, 0, 0, 0, 0, 'b', 'y', 't', 'e'});
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed planting the edit and bytecode: " + e)
                        .assertion("setup_ok", false);
            }

            // Consumers, installed while the remote is still at `before`.
            Path clean = root.resolve("consumer-bytecode-only");
            Path edited = root.resolve("consumer-bytecode-and-edit");
            Path cleanUnit = clean.resolve("skills").resolve(UNIT);
            Path editedUnit = edited.resolve("skills").resolve(UNIT);
            ProcessRecord installClean;
            ProcessRecord installEdited;
            boolean consumersSetup;
            try {
                Files.createDirectories(clean);
                Files.createDirectories(edited);
                installClean = HonestPinsKit.sm(ctx, "install_consumer_bytecode_only", root, clean,
                        "install", "git+" + bare.toUri(), "--yes", "--no-bind-default");
                installEdited = HonestPinsKit.sm(ctx, "install_consumer_bytecode_and_edit", root, edited,
                        "install", "git+" + bare.toUri(), "--yes", "--no-bind-default");
                consumersSetup = installClean.exitCode() == 0 && installEdited.exitCode() == 0
                        && before.equals(HonestPinsKit.head(cleanUnit))
                        && before.equals(HonestPinsKit.head(editedUnit));
                for (Path u : new Path[]{cleanUnit, editedUnit}) {
                    Files.createDirectories(u.resolve("__pycache__"));
                    Files.write(u.resolve("__pycache__/tool.cpython-312.pyc"),
                            new byte[]{(byte) 0xcb, 0x0d, 0x0d, 0x0a, 0, 0, 0, 0, 'b', 'y', 't', 'e'});
                }
                Path editedSkill = editedUnit.resolve("SKILL.md");
                Files.writeString(editedSkill, HonestPinsKit.read(editedSkill) + CONSUMER_EDIT + "\n");
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed installing the consumers: " + e)
                        .process(install).assertion("setup_ok", false);
            }

            ProcessRecord publish = HonestPinsKit.sm(ctx, "unit_publish", root, home,
                    "unit", "publish", UNIT, "--direct", "--ticket", "hp0", "--json");
            String after;
            String tree;
            String skillOnRemote;
            try {
                after = HonestPinsKit.gitOut(bare, "rev-parse", "main");
                tree = HonestPinsKit.gitOut(bare, "ls-tree", "-r", "--name-only", "main");
                skillOnRemote = HonestPinsKit.gitOut(bare, "show", "main:SKILL.md");
            } catch (Exception e) {
                return NodeResult.fail(ID, "could not read the remote after publish: " + e)
                        .process(install).process(publish)
                        .assertion("setup_ok", setup);
            }
            boolean published = publish.exitCode() == 0 && !after.equals(before);
            boolean controlEditPublished = published && skillOnRemote.contains(EDIT);
            boolean bytecodePublished = tree.contains("__pycache__/") || tree.contains(".pyc");
            boolean shape = published && !bytecodePublished;

            // SYNC HALF.
            ProcessRecord syncClean = HonestPinsKit.sm(ctx, "sync_consumer_bytecode_only", root, clean,
                    "sync", UNIT, "--git-latest", "--skip-mcp", "--yes");
            ProcessRecord syncEdited = HonestPinsKit.sm(ctx, "sync_consumer_bytecode_and_edit", root, edited,
                    "sync", UNIT, "--git-latest", "--skip-mcp", "--yes");
            String cleanHead = HonestPinsKit.head(cleanUnit);
            String editedHead = HonestPinsKit.head(editedUnit);
            String cleanOut = HonestPinsKit.output(ctx, syncClean);
            boolean cleanRefused = cleanOut.contains("extra local changes");
            boolean cleanReached = published && after.equals(cleanHead);
            boolean cleanBytecodeKept = Files.exists(cleanUnit.resolve("__pycache__/tool.cpython-312.pyc"));
            boolean shapeSync = !cleanRefused && cleanReached && cleanBytecodeKept;
            boolean editedRefused = before.equals(editedHead)
                    && HonestPinsKit.output(ctx, syncEdited).contains("extra local changes");
            boolean editedKept = HonestPinsKit.read(editedUnit.resolve("SKILL.md")).contains(CONSUMER_EDIT);
            boolean controlSync = editedRefused && editedKept;

            String detail = "publish rc=" + publish.exitCode() + " moved=" + !after.equals(before)
                    + "; remote tree=" + tree.replace('\n', ',')
                    + "; editPublished=" + controlEditPublished + "; setup=" + setup
                    + "; sync(bytecode only) rc=" + syncClean.exitCode() + " refused=" + cleanRefused
                    + " reached=" + cleanReached + " bytecodeKept=" + cleanBytecodeKept
                    + "; sync(bytecode+edit) rc=" + syncEdited.exitCode() + " refused=" + editedRefused
                    + " editKept=" + editedKept + "; consumersSetup=" + consumersSetup;
            boolean all = setup && consumersSetup && controlEditPublished && shape && shapeSync && controlSync;
            NodeResult result = all ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(install).process(installClean).process(installEdited).process(publish)
                    .process(syncClean).process(syncEdited)
                    .assertion("setup_installed_from_remote", setup)
                    .assertion("setup_consumers_installed_behind", consumersSetup)
                    .assertion("control_real_edit_is_published", controlEditPublished)
                    .assertion("shape_bytecode_not_published", shape)
                    .assertion("shape_bytecode_alone_does_not_refuse_sync", shapeSync)
                    .assertion("control_real_edit_still_refuses_sync", controlSync)
                    .log(detail);
        });
    }
}
