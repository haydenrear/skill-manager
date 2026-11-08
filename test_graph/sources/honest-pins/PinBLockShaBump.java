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
 * Pin shape (b), #406: a lock's {@code resolved_sha} is bumped for an
 * installed unit, and {@code sync --lock} must land on it or refuse by name.
 *
 * <p>Plant: a local Git remote with commits A, B, C on {@code main}, each with
 * its own marker. The unit is installed while main is at A ({@code sync
 * --refresh} then writes the lock row with origin/ref/resolved_sha). Main
 * advances to C. The desired lock is the live lock with ref AND resolved_sha
 * changed to B — the one-row edit #406 reports.
 *
 * <p>HONEST: {@code sync --lock desired} either leaves the store HEAD, the
 * installed record, the regenerated lock and the SKILL.md bytes all at B with
 * exit 0, or exits non-zero naming the unit. At 0.28.2 it exits 0 at C — the
 * installed ref's tip — and rewrites the lock to C.
 *
 * <p>CONTROL: {@code sync --lock} with the unedited live lock is a no-op that
 * leaves HEAD at A. That must stay true after the fix.
 */
public class PinBLockShaBump {
    static final String ID = "honest.pin-b";
    static final String UNIT = "hp-pin-b-unit";
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("honest-pins", "pin", "sync-lock", "406")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path root;
            Path upstream;
            Path home;
            String a;
            String b;
            String c;
            ProcessRecord install;
            ProcessRecord refresh;
            try {
                root = HonestPinsKit.scratch(ctx, "pin-b");
                upstream = root.resolve("upstream");
                home = root.resolve("home");
                Files.createDirectories(home);
                HonestPinsKit.writeSkill(upstream, UNIT, "0.1.0", "marker A");
                HonestPinsKit.git(upstream, "init", "-b", "main", "--quiet");
                HonestPinsKit.commitAll(upstream, "A");
                a = HonestPinsKit.gitOut(upstream, "rev-parse", "HEAD");
                install = HonestPinsKit.sm(ctx, "install", root, home,
                        "install", "git+" + upstream.toUri(), "--yes", "--no-bind-default");
                refresh = HonestPinsKit.sm(ctx, "lock_refresh", root, home, "sync", "--refresh");
                HonestPinsKit.writeSkill(upstream, UNIT, "0.1.0", "marker B");
                HonestPinsKit.commitAll(upstream, "B");
                b = HonestPinsKit.gitOut(upstream, "rev-parse", "HEAD");
                HonestPinsKit.writeSkill(upstream, UNIT, "0.1.0", "marker C");
                HonestPinsKit.commitAll(upstream, "C");
                c = HonestPinsKit.gitOut(upstream, "rev-parse", "HEAD");
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e).assertion("setup_ok", false);
            }

            Path unitDir = home.resolve("skills").resolve(UNIT);
            Path lock = home.resolve("units.lock.toml");
            String liveLock = HonestPinsKit.read(lock);
            boolean setupInstalledAtA = install.exitCode() == 0 && a.equals(HonestPinsKit.head(unitDir));
            boolean setupLockPinsA = refresh.exitCode() == 0 && a.equals(HonestPinsKit.lockField(home, UNIT, "resolved_sha"));

            try {
                Files.writeString(root.resolve("control.lock.toml"), liveLock);
                Files.writeString(root.resolve("desired.lock.toml"), liveLock
                        .replace("resolved_sha = \"" + a + "\"", "resolved_sha = \"" + b + "\"")
                        .replace("ref = \"main\"", "ref = \"" + b + "\""));
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed writing locks: " + e).assertion("setup_ok", false);
            }
            boolean setupDesiredNamesB = HonestPinsKit.read(root.resolve("desired.lock.toml"))
                    .contains("resolved_sha = \"" + b + "\"");

            // CONTROL first: the unedited lock is a no-op.
            ProcessRecord control = HonestPinsKit.sm(ctx, "sync_lock_unchanged", root, home,
                    "sync", "--lock", root.resolve("control.lock.toml").toString(), "--skip-mcp", "--yes");
            boolean controlNoop = control.exitCode() == 0 && a.equals(HonestPinsKit.head(unitDir));

            // SHAPE: the bumped lock.
            ProcessRecord bumped = HonestPinsKit.sm(ctx, "sync_lock_bumped", root, home,
                    "sync", "--lock", root.resolve("desired.lock.toml").toString(), "--skip-mcp", "--yes");
            String headAfter = HonestPinsKit.head(unitDir);
            String recordAfter = HonestPinsKit.recordedGitHash(home, UNIT);
            String lockAfter = HonestPinsKit.lockField(home, UNIT, "resolved_sha");
            boolean bytesAtB = HonestPinsKit.read(unitDir.resolve("SKILL.md")).contains("marker B");
            boolean honoured = bumped.exitCode() == 0 && b.equals(headAfter) && b.equals(recordAfter)
                    && b.equals(lockAfter) && bytesAtB;
            boolean refusedByName = bumped.exitCode() != 0
                    && HonestPinsKit.output(ctx, bumped).contains(UNIT);
            boolean falseConvergence = bumped.exitCode() == 0 && !b.equals(headAfter);

            // #434 F2: an annotated tag names a tag OBJECT; the pin is the commit it
            // peels to, in HEAD, the record and the lock alike.
            ProcessRecord tagged;
            try {
                HonestPinsKit.git(upstream, "-c", "user.email=fixture@localhost", "-c", "user.name=fixture",
                        "tag", "-a", "-m", "v1.0", "v1.0", c);
                tagged = HonestPinsKit.sm(ctx, "sync_to_annotated_tag", root, home, "sync", UNIT, "--to", "v1.0");
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed tagging: " + e).assertion("setup_ok", false);
            }
            boolean annotatedTagPeeled = tagged.exitCode() == 0 && c.equals(HonestPinsKit.head(unitDir))
                    && c.equals(HonestPinsKit.recordedGitHash(home, UNIT))
                    && c.equals(HonestPinsKit.lockField(home, UNIT, "resolved_sha"));

            boolean setup = setupInstalledAtA && setupLockPinsA && setupDesiredNamesB;
            boolean shape = (honoured || refusedByName) && annotatedTagPeeled;
            String where = headAfter.equals(c) ? "C (main's tip)" : headAfter.equals(a) ? "A (unchanged)"
                    : headAfter.equals(b) ? "B (the pin)" : headAfter;
            String detail = "A=" + a + " B(pin)=" + b + " C(tip)=" + c
                    + "; sync --lock rc=" + bumped.exitCode() + " HEAD=" + where
                    + " record=" + recordAfter + " lock=" + lockAfter + " bytesAtB=" + bytesAtB
                    + "; sync --to <annotated tag> rc=" + tagged.exitCode() + " peeled=" + annotatedTagPeeled
                    + "; control rc=" + control.exitCode() + " noop=" + controlNoop
                    + "; setup installedAtA=" + setupInstalledAtA + " lockPinsA=" + setupLockPinsA;
            NodeResult result = setup && controlNoop && shape ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(install).process(refresh).process(control).process(bumped).process(tagged)
                    .assertion("setup_installed_at_A", setupInstalledAtA)
                    .assertion("setup_lock_pins_A", setupLockPinsA)
                    .assertion("setup_desired_lock_names_B", setupDesiredNamesB)
                    .assertion("control_unchanged_lock_is_noop", controlNoop)
                    .assertion("shape_lock_pin_honoured_or_refused_by_name", shape)
                    .assertion("shape_no_false_convergence", !falseConvergence)
                    .assertion("shape_annotated_tag_lands_on_its_commit", annotatedTagPeeled)
                    .log(detail);
        });
    }
}
