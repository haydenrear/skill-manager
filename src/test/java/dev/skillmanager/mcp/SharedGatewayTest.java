package dev.skillmanager.mcp;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.commands.GatewayCommand;
import dev.skillmanager.commands.HomeCommand;
import dev.skillmanager.store.HomeCloner;
import dev.skillmanager.store.HomeDescriptor;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Shared-gateway discovery: a home either owns its gateway or attaches to
 * one another home runs.
 *
 * <p>The gateway is one process on one port, so N per-worktree homes each
 * running {@code gateway up} do not get N gateways. The two failures this
 * pins down are the ones that actually cost something:
 *
 * <ul>
 *   <li>An attached home starting a gateway — a port collision, or worse a
 *       second gateway on a different port that half the agents never see.</li>
 *   <li>An attached home stopping one — which takes MCP away from every
 *       other home at once, from a command that looks entirely local.</li>
 * </ul>
 *
 * <p>Every command here runs {@code --dry-run} so no test can start or stop
 * a real gateway process even if the guard under test regresses.
 */
public final class SharedGatewayTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("SharedGatewayTest");

        // ------------------------------------------------------- persistence

        suite.test("a home owns its gateway by default", () -> {
            SkillStore store = newStore("gw-default-");
            assertTrue(GatewayConfig.resolve(store, null).owned(),
                    "an unconfigured home owns the default gateway");
            GatewayConfig.persist(store, "http://127.0.0.1:51717");
            assertTrue(GatewayConfig.resolve(store, null).owned(),
                    "and a plain URL write leaves it owning");
        });

        suite.test("attach records the endpoint and drops ownership", () -> {
            SkillStore store = newStore("gw-attach-");
            GatewayConfig attached = GatewayConfig.attach(store, "http://127.0.0.1:52000");

            assertFalse(attached.owned(), "attach returns an unowned config");
            GatewayConfig reread = GatewayConfig.resolve(store, null);
            assertEquals("http://127.0.0.1:52000", reread.baseUrl().toString(),
                    "the shared endpoint persisted");
            assertFalse(reread.owned(), "and attachment survives a re-resolve");
        });

        suite.test("a plain URL write does not silently promote an attached home", () -> {
            // `gateway set`, and EnsureGateway's healthy-start persist, both
            // write the URL. Neither is a statement about ownership, and
            // treating them as one would re-create the collision on the next
            // `gateway up`.
            SkillStore store = newStore("gw-no-promote-");
            GatewayConfig.attach(store, "http://127.0.0.1:52001");
            GatewayConfig.persist(store, "http://127.0.0.1:52002");

            GatewayConfig after = GatewayConfig.resolve(store, null);
            assertEquals("http://127.0.0.1:52002", after.baseUrl().toString(), "URL updated");
            assertFalse(after.owned(), "still attached");
        });

        suite.test("detach takes ownership back", () -> {
            SkillStore store = newStore("gw-detach-");
            GatewayConfig.attach(store, "http://127.0.0.1:52003");
            GatewayConfig owned = GatewayConfig.detach(store);

            assertTrue(owned.owned(), "detach returns an owning config");
            assertEquals("http://127.0.0.1:52003", owned.baseUrl().toString(),
                    "the endpoint is kept — detach is about rights, not routing");
            assertTrue(GatewayConfig.resolve(store, null).owned(), "and it persisted");
        });

        // ------------------------------------------------------------ guards

        suite.test("`gateway up` refuses on an attached home", () -> {
            SkillStore store = newStore("gw-up-refuse-");
            GatewayConfig.attach(store, "http://127.0.0.1:52004");

            Result result = captureErr(() -> new CommandLine(new GatewayCommand.Up(store))
                    .execute("--dry-run", "--no-sync-agents"));

            assertEquals(GatewayCommand.ATTACHED_EXIT, result.rc, "up refused");
            assertContains(result.err, "attached to the shared gateway", "the refusal explains why");
            assertContains(result.err, "http://127.0.0.1:52004", "and names the endpoint");
        });

        suite.test("`gateway down` refuses on an attached home", () -> {
            SkillStore store = newStore("gw-down-refuse-");
            GatewayConfig.attach(store, "http://127.0.0.1:52005");

            Result result = captureErr(() -> new CommandLine(new GatewayCommand.Down(store))
                    .execute("--dry-run"));

            assertEquals(GatewayCommand.ATTACHED_EXIT, result.rc, "down refused");
            assertContains(result.err, "other homes are using", "the shared blast radius is stated");
        });

        suite.test("an owning home is not blocked, and --force overrides attachment", () -> {
            SkillStore owning = newStore("gw-owning-");
            GatewayConfig.persist(owning, "http://127.0.0.1:52006");
            assertEquals(0, new CommandLine(new GatewayCommand.Up(owning))
                            .execute("--dry-run", "--no-sync-agents"),
                    "an owner may bring its gateway up");

            SkillStore attached = newStore("gw-forced-");
            GatewayConfig.attach(attached, "http://127.0.0.1:52007");
            assertEquals(0, new CommandLine(new GatewayCommand.Up(attached))
                            .execute("--dry-run", "--no-sync-agents", "--force"),
                    "--force is the deliberate escape hatch");
        });

        suite.test("detach re-enables `gateway up`", () -> {
            SkillStore store = newStore("gw-detach-up-");
            GatewayConfig.attach(store, "http://127.0.0.1:52008");
            assertEquals(GatewayCommand.ATTACHED_EXIT,
                    new CommandLine(new GatewayCommand.Up(store))
                            .execute("--dry-run", "--no-sync-agents"),
                    "refused while attached");

            assertEquals(0, new CommandLine(new GatewayCommand.Detach(store)).execute(),
                    "detach rc");
            assertEquals(0, new CommandLine(new GatewayCommand.Up(store))
                            .execute("--dry-run", "--no-sync-agents"),
                    "allowed once owned");
        });

        // ------------------------------------------------------------ status

        suite.test("`gateway status` publishes the mode so another home can discover it", () -> {
            SkillStore store = newStore("gw-status-");
            GatewayConfig.persist(store, "http://127.0.0.1:1");

            Result owner = captureOut(() -> new GatewayCommand.Status(store).call());
            assertContains(owner.out, "mode:         owner", "owner mode published");
            assertContains(owner.out, "run `skill-manager gateway up`",
                    "an owner is told to start it");

            GatewayConfig.attach(store, "http://127.0.0.1:1");
            Result attached = captureOut(() -> new GatewayCommand.Status(store).call());
            assertContains(attached.out, "mode:         attached", "attached mode published");
            assertContains(attached.out, "owned:        false", "and the raw flag");
            assertContains(attached.out, "the owning home must run",
                    "an attached home is not told to run a command it will be refused");
        });

        // ------------------------------------------------------------- clone

        suite.test("a cloned home attaches to the source's gateway instead of claiming it", () -> {
            // Without this, the copy inherits gateway.properties verbatim —
            // ownership included — and two homes believe they run the same
            // port. The first `gateway down` in the copy kills the original's.
            SkillStore source = newStore("gw-clone-src-");
            GatewayConfig.persist(source, "http://127.0.0.1:52009");
            assertTrue(GatewayConfig.resolve(source, null).owned(), "source owns it");

            Path dest = Files.createTempDirectory("gw-clone-dst-").resolve("home");
            int rc = new CommandLine(new HomeCommand.CloneCmd())
                    .execute("--from", source.root().toString(), "--to", dest.toString());
            assertEquals(0, rc, "clone rc");

            SkillStore cloned = new SkillStore(dest);
            GatewayConfig copied = GatewayConfig.resolve(cloned, null);
            assertEquals("http://127.0.0.1:52009", copied.baseUrl().toString(),
                    "the copy points at the same gateway");
            assertFalse(copied.owned(), "but it does not claim to own it");
            assertTrue(GatewayConfig.resolve(source, null).owned(),
                    "and the source keeps its ownership");

            HomeDescriptor descriptor = HomeDescriptor.read(dest).orElseThrow();
            assertEquals("http://127.0.0.1:52009", descriptor.gateway().url(),
                    "the descriptor the clone wrote carries the endpoint");
            assertFalse(descriptor.gateway().owned(),
                    "and reports it as shared, so a consumer knows not to manage it");
        });

        suite.test("`home clone --own-gateway` keeps ownership when the operator says so", () -> {
            SkillStore source = newStore("gw-clone-own-src-");
            GatewayConfig.persist(source, "http://127.0.0.1:52010");
            Path dest = Files.createTempDirectory("gw-clone-own-dst-").resolve("home");

            int rc = new CommandLine(new HomeCommand.CloneCmd())
                    .execute("--from", source.root().toString(), "--to", dest.toString(),
                            "--own-gateway");

            assertEquals(0, rc, "clone rc");
            assertTrue(GatewayConfig.resolve(new SkillStore(dest), null).owned(),
                    "explicit opt-in is honoured");
        });

        suite.test("the clone's own verification still passes with the descriptor in it", () -> {
            // The descriptor is written into the copy after verification, so
            // it must itself be relocatable — a re-verify catches it if not.
            SkillStore source = newStore("gw-clone-verify-src-");
            GatewayConfig.persist(source, "http://127.0.0.1:52011");
            Path dest = Files.createTempDirectory("gw-clone-verify-dst-").resolve("home");
            new CommandLine(new HomeCommand.CloneCmd())
                    .execute("--from", source.root().toString(), "--to", dest.toString());

            HomeCloner.Verification verification =
                    HomeCloner.verify(source.root(), dest, false);
            assertTrue(verification.clean(),
                    "descriptor did not introduce a leak: " + verification.leaks());
        });

        return suite.runAll();
    }

    private static SkillStore newStore(String prefix) throws Exception {
        SkillStore store = new SkillStore(
                Files.createTempDirectory(prefix).resolve(".skill-manager"));
        store.init();
        return store;
    }

    private static Result captureOut(ThrowingInt op) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out));
            int rc = op.run();
            return new Result(rc, out.toString(), "");
        } finally {
            System.setOut(original);
        }
    }

    private static Result captureErr(ThrowingInt op) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err));
            int rc = op.run();
            return new Result(rc, "", err.toString());
        } finally {
            System.setErr(original);
        }
    }

    @FunctionalInterface
    private interface ThrowingInt { int run() throws Exception; }

    private record Result(int rc, String out, String err) {}
}
