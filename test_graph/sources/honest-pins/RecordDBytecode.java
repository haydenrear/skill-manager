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
 * <p>The sync-refusal half of #400 ("extra local changes" caused by bytecode)
 * shares the definition HP-6 is asked to introduce; this node measures the
 * publish half, where the false record is created.
 */
public class RecordDBytecode {
    static final String ID = "honest.record-d";
    static final String UNIT = "hp-record-d-unit";
    static final String EDIT = "AGENT EDIT TO PUBLISH";
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

            String detail = "publish rc=" + publish.exitCode() + " moved=" + !after.equals(before)
                    + "; remote tree=" + tree.replace('\n', ',')
                    + "; editPublished=" + controlEditPublished + "; setup=" + setup;
            NodeResult result = setup && controlEditPublished && shape ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(install).process(publish)
                    .assertion("setup_installed_from_remote", setup)
                    .assertion("control_real_edit_is_published", controlEditPublished)
                    .assertion("shape_bytecode_not_published", shape)
                    .log(detail);
        });
    }
}
