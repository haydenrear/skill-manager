package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingSource;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ChildHomeRegistry;
import dev.skillmanager.bindings.ConflictPolicy;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionKind;
import dev.skillmanager.bindings.ProjectionLedger;
import dev.skillmanager.cli.installer.InstallerBackend;
import dev.skillmanager.cli.installer.InstallerRegistry;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.project.PluginMarketplace;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.project.SkillProjectRegistration;
import dev.skillmanager.project.SkillProjectRegistry;
import dev.skillmanager.project.SkillProjectLock;
import dev.skillmanager.project.SkillProjectLockStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The write-time half of T19: a home must be a pure function of
 * {@code SKILL_MANAGER_HOME}, so every self-reference skill-manager
 * persists inside a home is stored as {@code $SKILL_MANAGER_HOME/...} and
 * every external path is stored verbatim.
 */
public final class HomePathsTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("HomePathsTest");

        // ------------------------------------------------ encode / decode

        suite.test("a path inside the home encodes to the token and decodes back", () -> {
            Path home = newDir("home-");
            HomePaths paths = HomePaths.of(home);
            Path inside = home.resolve("skills/acp-cdc-ai-python");

            String encoded = paths.encode(inside);

            assertEquals("$SKILL_MANAGER_HOME/skills/acp-cdc-ai-python", encoded, "encoded form");
            assertEquals(inside, paths.decode(encoded), "round trip");
        });

        suite.test("the home root itself encodes to the bare token", () -> {
            Path home = newDir("home-");
            HomePaths paths = HomePaths.of(home);

            assertEquals("$SKILL_MANAGER_HOME", paths.encode(home), "root encodes bare");
            assertEquals(home, paths.decode("$SKILL_MANAGER_HOME"), "bare token decodes to root");
        });

        suite.test("a path outside the home is stored verbatim", () -> {
            Path home = newDir("home-");
            Path elsewhere = newDir("checkout-").resolve(".skill-manager");
            HomePaths paths = HomePaths.of(home);

            assertEquals(elsewhere.toString(), paths.encode(elsewhere), "external stays absolute");
            assertFalse(HomePaths.isEncoded(paths.encode(elsewhere)), "external is not tokenized");
        });

        suite.test("a sibling whose name merely starts with the home's is not encoded", () -> {
            // String-prefix matching would rewrite this into the home and
            // silently repoint it on the next relocation.
            Path parent = newDir("prefix-");
            Path home = Files.createDirectories(parent.resolve("home"));
            Path sibling = Files.createDirectories(parent.resolve("home-backup"))
                    .resolve("skills/x");

            assertEquals(sibling.toString(), HomePaths.of(home).encode(sibling), "sibling untouched");
        });

        // ------------------------------------- the dereference data-loss bug

        suite.test("a dest that IS a symlink into the home is not treated as a self-reference", () -> {
            // Regression: encode() resolved the candidate through
            // toRealPath(), which follows the final component. A projection's
            // destPath (~/.claude/skills/<unit>) is a symlink into the store
            // and the ledger is written after that link exists, so it
            // resolved inside the home and was stored as
            // $SKILL_MANAGER_HOME/skills/<unit>. unbind then deleted the
            // installed unit instead of the agent symlink.
            Path home = newDir("home-");
            Path agent = newDir("agent-");
            Path unit = Files.createDirectories(home.resolve("skills/alpha"));
            Files.writeString(unit.resolve("SKILL.md"), "---\nname: alpha\n---\n");
            Path dest = Files.createDirectories(agent.resolve("skills")).resolve("alpha");
            Files.createSymbolicLink(dest, unit);

            String encoded = HomePaths.of(home).encode(dest);

            assertFalse(HomePaths.isEncoded(encoded),
                    "a symlink into the home is still an external destination");
            assertEquals(dest.toString(), encoded, "dest stored verbatim");
            assertFalse(HomePaths.of(home).isInsideHome(dest), "dest is not inside the home");
        });

        suite.test("unbind removes the agent symlink, not the installed unit", () -> {
            // The consequence the previous case guards, asserted where it
            // actually hurts: a ledger round trip must not turn destPath into
            // sourcePath, because reverseProjection deletes destPath.
            Path home = newDir("home-");
            Path agent = newDir("agent-");
            SkillStore store = new SkillStore(home);
            store.init();
            Path unit = Files.createDirectories(home.resolve("skills/alpha"));
            Files.writeString(unit.resolve("SKILL.md"), "---\nname: alpha\n---\n");
            Path dest = Files.createDirectories(agent.resolve("skills")).resolve("alpha");
            Files.createSymbolicLink(dest, unit);

            new BindingStore(store).write(ledger(home, dest));
            Projection read = new BindingStore(store).read("alpha")
                    .bindings().get(0).projections().get(0);

            assertEquals(dest, read.destPath(), "destPath round-trips as the agent symlink");
            assertEquals(unit, read.sourcePath(), "sourcePath round-trips as the store unit");
            assertFalse(read.destPath().equals(read.sourcePath()),
                    "unbind would delete the agent link, not the store unit");
        });

        suite.test("a path under a directory symlink into the home stays external", () -> {
            // Same hazard one level up: an intermediate component that links
            // into the home must not drag the whole path in either.
            Path home = newDir("home-");
            Path agent = newDir("agent-");
            Files.createDirectories(home.resolve("skills"));
            Files.createSymbolicLink(agent.resolve("skills"), home.resolve("skills"));

            String encoded = HomePaths.of(home).encode(agent.resolve("skills/alpha"));

            assertFalse(HomePaths.isEncoded(encoded), "path via a linked parent stays external");
        });

        suite.test("decode accepts both the token form and a plain absolute path", () -> {
            Path home = newDir("home-");
            HomePaths paths = HomePaths.of(home);
            Path legacy = home.resolve("skills/legacy");

            assertEquals(legacy, paths.decode("$SKILL_MANAGER_HOME/skills/legacy"), "token form");
            assertEquals(legacy, paths.decode(legacy.toString()), "legacy absolute form");
            assertEquals(legacy, paths.decode("${SKILL_MANAGER_HOME}/skills/legacy"), "braced form");
        });

        suite.test("a token that is only a name prefix is not expanded", () -> {
            Path home = newDir("home-");
            HomePaths paths = HomePaths.of(home);

            assertEquals(Path.of("$SKILL_MANAGER_HOMEX/skills"),
                    paths.decode("$SKILL_MANAGER_HOMEX/skills"), "no partial-token match");
        });

        suite.test("the same record decodes to different roots under different homes", () -> {
            // The pure-function property, stated directly.
            Path a = newDir("home-a-");
            Path b = newDir("home-b-");
            String stored = "$SKILL_MANAGER_HOME/skills/portable";

            assertEquals(a.resolve("skills/portable"), HomePaths.of(a).decode(stored), "under a");
            assertEquals(b.resolve("skills/portable"), HomePaths.of(b).decode(stored), "under b");
        });

        // ----------------------------------------------- projection ledger

        suite.test("a ledger stores its store-side path tokenized and its target absolute", () -> {
            Path home = newDir("home-");
            SkillStore store = new SkillStore(home);
            store.init();
            Path claudeSkills = newDir("claude-").resolve("skills/alpha");

            new BindingStore(store).write(ledger(home, claudeSkills));

            String raw = Files.readString(new BindingStore(store).file("alpha"));
            assertContains(raw, "$SKILL_MANAGER_HOME/skills/alpha", "source path tokenized");
            assertContains(raw, claudeSkills.toString(), "dest path left absolute");
            assertFalse(raw.contains(home.toString()), "no absolute self-reference survives");
        });

        suite.test("a ledger read back resolves its store-side path into the home", () -> {
            Path home = newDir("home-");
            SkillStore store = new SkillStore(home);
            store.init();
            Path claudeSkills = newDir("claude-").resolve("skills/alpha");
            new BindingStore(store).write(ledger(home, claudeSkills));

            Projection p = new BindingStore(store).read("alpha")
                    .bindings().get(0).projections().get(0);

            assertEquals(home.resolve("skills/alpha"), p.sourcePath(), "source resolved");
            assertEquals(claudeSkills, p.destPath(), "dest unchanged");
        });

        suite.test("a ledger written by an older skill-manager still reads", () -> {
            // Compatibility requirement: absolute paths were the only form
            // before this encoding, and existing homes are full of them.
            Path home = newDir("home-");
            SkillStore store = new SkillStore(home);
            store.init();
            Path legacyFile = new BindingStore(store).file("legacy");
            Files.createDirectories(legacyFile.getParent());
            Files.writeString(legacyFile, """
                    {
                      "unitName" : "legacy",
                      "bindings" : [ {
                        "bindingId" : "b1",
                        "unitName" : "legacy",
                        "unitKind" : "SKILL",
                        "targetRoot" : "%s",
                        "conflictPolicy" : "ERROR",
                        "createdAt" : "2026-01-01T00:00:00Z",
                        "source" : "DEFAULT_AGENT",
                        "projections" : [ {
                          "bindingId" : "b1",
                          "sourcePath" : "%s",
                          "destPath" : "/somewhere/else/skills/legacy",
                          "kind" : "SYMLINK"
                        } ]
                      } ]
                    }
                    """.formatted(home.resolve("skills"), home.resolve("skills/legacy")));

            ProjectionLedger read = new BindingStore(store).read("legacy");

            assertEquals(1, read.bindings().size(), "legacy ledger parsed");
            assertEquals(home.resolve("skills/legacy"),
                    read.bindings().get(0).projections().get(0).sourcePath(), "legacy path read");
            assertEquals(Path.of("/somewhere/else/skills/legacy"),
                    read.bindings().get(0).projections().get(0).destPath(), "external path read");
        });

        // -------------------------------------------------- child homes

        suite.test("child-home records tokenize parentHome and keep childHome absolute", () -> {
            Path home = newDir("home-");
            SkillStore store = new SkillStore(home);
            store.init();
            Path checkout = newDir("project-").resolve(".skill-manager");

            new ChildHomeRegistry(store).write(new ChildHomeRegistry.ChildHomeRecord(
                    "project:demo", home.toString(), checkout.toString(),
                    null, List.of("alpha"), "2026-01-01T00:00:00Z"));

            String raw = Files.readString(new ChildHomeRegistry(store).file("project:demo"));
            assertContains(raw, "\"$SKILL_MANAGER_HOME\"", "parentHome tokenized");
            assertContains(raw, checkout.toString(), "childHome left absolute");
            assertFalse(raw.contains("\"" + home + "\""), "no absolute parentHome survives");
        });

        suite.test("a child-home record with a legacy absolute parentHome still reads", () -> {
            Path home = newDir("home-");
            SkillStore store = new SkillStore(home);
            store.init();
            Path file = new ChildHomeRegistry(store).file("project:legacy");
            Files.createDirectories(file.getParent());
            Files.writeString(file, """
                    { "id" : "project:legacy", "parentHome" : "%s",
                      "childHome" : "/elsewhere/.skill-manager", "units" : [ "alpha" ],
                      "createdAt" : "2026-01-01T00:00:00Z" }
                    """.formatted(home));

            ChildHomeRegistry.ChildHomeRecord read =
                    new ChildHomeRegistry(store).read("project:legacy").orElseThrow();

            assertEquals(home.toString(), read.parentHome(), "legacy parentHome read");
            assertEquals("/elsewhere/.skill-manager", read.childHome(), "childHome read");
        });

        // ---------------------------------------------- project registry

        suite.test("a registration whose manifest is the registry's own snapshot tokenizes it", () -> {
            Path home = newDir("home-");
            SkillStore store = new SkillStore(home);
            store.init();
            Path projectDir = newDir("proj-");
            Files.writeString(projectDir.resolve("skill-project.toml"),
                    "[project]\nname = \"demo\"\n");
            SkillProjectRegistry registry = new SkillProjectRegistry(store);
            registry.register(SkillProjectParser.load(projectDir));
            // Re-register from the snapshot, as `project resolve` does — this
            // is the path that puts a home path into registration.toml.
            SkillProject snapshot = registry.loadSnapshot("demo").orElseThrow();
            registry.register(snapshot);

            Path registration = store.projectsDir().resolve("demo/registration.toml");
            String raw = Files.readString(registration);
            assertContains(raw, "manifest_path = \"$SKILL_MANAGER_HOME/projects/demo/skill-project.toml\"",
                    "snapshot manifest tokenized");
            assertContains(raw, "project_root = \"" + projectDir + "\"", "project root absolute");
            SkillProjectRegistration read = registry.read("demo").orElseThrow();
            assertEquals(home.resolve("projects/demo/skill-project.toml"), read.manifestPath(),
                    "manifest path resolved back into the home");
        });

        suite.test("a registration pointing at the project's own manifest stays absolute", () -> {
            Path home = newDir("home-");
            SkillStore store = new SkillStore(home);
            store.init();
            Path projectDir = newDir("proj-");
            Files.writeString(projectDir.resolve("skill-project.toml"),
                    "[project]\nname = \"external\"\n");

            new SkillProjectRegistry(store).register(SkillProjectParser.load(projectDir));

            String raw = Files.readString(store.projectsDir().resolve("external/registration.toml"));
            assertContains(raw, "manifest_path = \"" + projectDir.resolve("skill-project.toml") + "\"",
                    "external manifest kept absolute");
        });

        // -------------------------------------------------------- links

        suite.test("a link between two places in the home is stored relative", () -> {
            Path home = newDir("home-");
            Path link = home.resolve("bin/cli/jinja2");
            Path target = home.resolve("venvs/jinja2-cli/bin/jinja2");

            Path stored = HomeLinks.storedTarget(home, link, target);

            assertFalse(stored.isAbsolute(), "stored relative");
            assertEquals(target, link.getParent().resolve(stored).normalize(), "resolves to target");
        });

        suite.test("a link out of the home stays absolute", () -> {
            Path home = newDir("home-");
            Path link = home.resolve("bin/cli/tofu");
            Path target = Path.of("/opt/homebrew/opt/opentofu/bin/tofu");

            assertEquals(target, HomeLinks.storedTarget(home, link, target), "external target verbatim");
        });

        suite.test("relativizeLinksIn rewrites an absolute shim and leaves an external one", () -> {
            // uv and npm write absolute links into bin/cli and offer no flag
            // to change that, so the fix has to be a normalization pass.
            Path home = newDir("home-");
            SkillStore store = new SkillStore(home);
            store.init();
            Path venvBin = Files.createDirectories(home.resolve("venvs/tool/bin"));
            Path real = Files.writeString(venvBin.resolve("tool"), "#!/bin/sh\n");
            Path external = Files.writeString(newDir("ext-").resolve("other"), "#!/bin/sh\n");
            Files.createSymbolicLink(store.cliBinDir().resolve("tool"), real);
            Files.createSymbolicLink(store.cliBinDir().resolve("other"), external);

            int rewritten = HomeLinks.relativizeShims(store);

            assertEquals(1, rewritten, "only the in-home link rewritten");
            assertFalse(Files.readSymbolicLink(store.cliBinDir().resolve("tool")).isAbsolute(),
                    "in-home shim is relative");
            assertTrue(Files.isRegularFile(store.cliBinDir().resolve("tool")), "relative shim resolves");
            assertEquals(external, Files.readSymbolicLink(store.cliBinDir().resolve("other")),
                    "external shim untouched");
        });

        suite.test("shim normalization reaches nested shim directories", () -> {
            // The real home has bin/cli/.spec-double-compiler/ holding
            // tla2tools.jar; a flat scan misses anything a backend nests.
            Path home = newDir("home-");
            SkillStore store = new SkillStore(home);
            store.init();
            Path payload = Files.createDirectories(home.resolve("venvs/tool/bin"))
                    .resolve("tla2tools.jar");
            Files.writeString(payload, "jar");
            Path nested = Files.createDirectories(store.cliBinDir().resolve(".spec-double-compiler"));
            Files.createSymbolicLink(nested.resolve("tla2tools.jar"), payload);

            int rewritten = HomeLinks.relativizeShims(store);

            assertEquals(1, rewritten, "nested shim rewritten");
            assertFalse(Files.readSymbolicLink(nested.resolve("tla2tools.jar")).isAbsolute(),
                    "nested shim is relative");
            assertTrue(Files.isRegularFile(nested.resolve("tla2tools.jar")),
                    "nested relative shim resolves");
        });

        suite.test("the plugin marketplace links plugins relatively", () -> {
            // regenerate() linked plugin-marketplace/plugins/<n> at an
            // absolute <home>/plugins/<n>, so a plain cp -a of the home kept
            // the copy pointing at the original.
            Path home = newDir("home-");
            SkillStore store = new SkillStore(home);
            store.init();
            Path plugin = Files.createDirectories(home.resolve("plugins/demo/.claude-plugin"));
            Files.writeString(plugin.resolve("plugin.json"), "{ \"name\": \"demo\" }\n");
            new UnitStore(store).write(new InstalledUnit("demo", "0.1.0",
                    InstalledUnit.Kind.UNKNOWN, InstalledUnit.InstallSource.UNKNOWN,
                    null, null, null, UnitStore.nowIso(), null, UnitKind.PLUGIN));

            new PluginMarketplace(store).regenerate();

            Path link = home.resolve("plugin-marketplace/plugins/demo");
            assertFalse(Files.readSymbolicLink(link).isAbsolute(), "marketplace link is relative");
            assertTrue(Files.isDirectory(link), "marketplace link resolves");
        });

        suite.test("a projection backup path in the home is encoded too", () -> {
            // backupOf is a String, so BindingJson's Path serializer never
            // sees it; unbind moves the backup back to whatever it names.
            Path home = newDir("home-");
            SkillStore store = new SkillStore(home);
            store.init();
            Projection backup = new Projection("b1", null, home.resolve("docs/x.md.bak"),
                    ProjectionKind.RENAMED_ORIGINAL_BACKUP, home.resolve("docs/x.md").toString());
            new BindingStore(store).write(new ProjectionLedger("alpha", List.of(
                    new Binding("b1", "alpha", UnitKind.DOC, null, home.resolve("docs"),
                            ConflictPolicy.RENAME_EXISTING, "2026-01-01T00:00:00Z",
                            BindingSource.DEFAULT_AGENT, List.of(backup)))));

            // Assert the field, not a substring: destPath is
            // $SKILL_MANAGER_HOME/docs/x.md.bak, which a prefix match on
            // ".../docs/x.md" would satisfy without backupOf being touched.
            String raw = Files.readString(new BindingStore(store).file("alpha"));
            assertContains(raw, "\"backupOf\" : \"$SKILL_MANAGER_HOME/docs/x.md\"",
                    "backupOf tokenized");
            assertEquals(home.resolve("docs/x.md").toString(),
                    new BindingStore(store).read("alpha").bindings().get(0)
                            .projections().get(0).backupOf(), "backupOf decodes back");
        });

        suite.test("a project lock encodes a target root that points into the home", () -> {
            // target_root and env_root are usually a project checkout or an
            // agent home, i.e. external — but a harness bound inside the
            // store puts a home path here, and the lock writer emitted it raw.
            Path home = newDir("home-");
            SkillStore store = new SkillStore(home);
            store.init();
            Files.createDirectories(store.projectsDir().resolve("demo"));
            SkillProjectLockStore locks = new SkillProjectLockStore(store);

            locks.write(new SkillProjectLock("demo", "skill-project.toml",
                    "2026-01-01T00:00:00Z", List.of(),
                    List.of(new SkillProjectLock.ProjectBinding("b1", "alpha", UnitKind.SKILL,
                            BindingSource.DEFAULT_AGENT,
                            home.resolve("harnesses/instances/i1").toString()))));

            String raw = Files.readString(locks.path("demo"));
            assertContains(raw, "target_root = \"$SKILL_MANAGER_HOME/harnesses/instances/i1\"",
                    "in-home target root tokenized");
            assertEquals(home.resolve("harnesses/instances/i1").toString(),
                    locks.read("demo").orElseThrow().bindings().get(0).targetRoot(),
                    "target root decodes back");
        });

        suite.test("a project lock leaves an external target root absolute", () -> {
            Path home = newDir("home-");
            SkillStore store = new SkillStore(home);
            store.init();
            Files.createDirectories(store.projectsDir().resolve("demo"));
            SkillProjectLockStore locks = new SkillProjectLockStore(store);

            locks.write(new SkillProjectLock("demo", "skill-project.toml",
                    "2026-01-01T00:00:00Z", List.of(),
                    List.of(new SkillProjectLock.ProjectBinding("b1", "alpha", UnitKind.SKILL,
                            BindingSource.DEFAULT_AGENT, "/checkout/project"))));

            assertContains(Files.readString(locks.path("demo")),
                    "target_root = \"/checkout/project\"", "external target root verbatim");
        });

        suite.test("installing a CLI dep normalizes the shim the backend left absolute", () -> {
            // The backends that actually do this (`uv tool install`, `npm -g`)
            // are not in-process, so the fake stands in for exactly the thing
            // they do wrong: an absolute symlink into the home.
            Path home = newDir("home-");
            SkillStore store = new SkillStore(home);
            store.init();
            Path payload = Files.createDirectories(home.resolve("venvs/widget/bin"))
                    .resolve("widget");
            Files.writeString(payload, "#!/bin/sh\n");
            InstallerRegistry registry = new InstallerRegistry();
            registry.register(new AbsoluteLinkBackend(payload));

            registry.installOne(new CliDependency("widget", "absolute-link:widget",
                    null, null, null, false, null), store, "some-skill");

            Path shim = store.cliBinDir().resolve("widget");
            assertFalse(Files.readSymbolicLink(shim).isAbsolute(),
                    "shim relativized after install");
            assertTrue(Files.isRegularFile(shim), "relativized shim still resolves");
        });

        return suite.runAll();
    }

    /** Stands in for uv/npm: lands its artifact as an absolute symlink. */
    private record AbsoluteLinkBackend(Path payload) implements InstallerBackend {
        @Override public String id() { return "absolute-link"; }
        @Override public boolean available() { return true; }
        @Override public dev.skillmanager.cli.installer.InstallOutcome install(
                CliDependency dep, SkillStore store, String skillName)
                throws java.io.IOException {
            Files.createDirectories(store.cliBinDir());
            Files.createSymbolicLink(store.cliBinDir().resolve(dep.name()), payload);
            return dev.skillmanager.cli.installer.InstallOutcome.INSTALLED;
        }
        /**
         * A stand-in with no declaration of its own to hash: the payload is
         * this fixture's path, not anything {@code dep} declares. Says so
         * rather than returning a digest that would be a fact about the test
         * harness — which is what {@code fingerprint} having no default is for.
         */
        @Override public dev.skillmanager.lock.Fingerprint fingerprint(
                CliDependency dep, SkillStore store, String unitName) {
            return dev.skillmanager.lock.Fingerprint.gap(
                    "test fixture backend: the artifact is a fixed payload, not a declared input");
        }
    }

    private static ProjectionLedger ledger(Path home, Path dest) {
        Projection projection = new Projection("b1", home.resolve("skills/alpha"), dest,
                ProjectionKind.SYMLINK, null);
        Binding binding = new Binding("b1", "alpha", UnitKind.SKILL, null,
                dest.getParent(), ConflictPolicy.ERROR, "2026-01-01T00:00:00Z",
                BindingSource.DEFAULT_AGENT, List.of(projection));
        return new ProjectionLedger("alpha", List.of(binding));
    }

    private static Path newDir(String prefix) throws Exception {
        return Files.createTempDirectory(prefix).toRealPath();
    }
}
