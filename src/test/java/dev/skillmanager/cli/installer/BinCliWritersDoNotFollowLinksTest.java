package dev.skillmanager.cli.installer;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.HomeLinks;
import dev.skillmanager.store.SkillStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * OHV-9 (#367) slice (b): every OTHER writer of {@code bin/cli}, asked the same
 * question as the skill-script backend — handed a child home whose
 * {@code bin/cli/<name>} links into a parent home, does it write the parent?
 *
 * <p>Each case drives the production code path the backend actually uses
 * (npm and brew place their links through {@link ForeignBinLinks#placeLink},
 * tar through {@link ForeignBinLinks#placeCopy}), and asserts the parent's file
 * is byte-identical afterwards. The forked writers (skill-script, uv) cannot be
 * argued about this way and are guarded by detach/restore instead; see
 * {@link SkillScriptWriteThroughTest}.
 */
public final class BinCliWritersDoNotFollowLinksTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("BinCliWritersDoNotFollowLinksTest");

        suite.test("OHV-9 (b): npm/brew placement (placeLink) replaces a link into another home, never writes it", () -> {
            var pair = SkillScriptWriteThroughTest.Pair.make("wt-b-link-");
            Path source = newSource(pair.child(), "npm-entry");

            ForeignBinLinks.placeLink(pair.childShim(), source);

            assertEquals(SkillScriptWriteThroughTest.PARENT_BYTES, Files.readString(pair.parentShim()),
                    "the parent's shim is byte-identical");
            assertTrue(Files.isSymbolicLink(pair.childShim()), "the child's entry is a link again");
            assertEquals(source, Files.readSymbolicLink(pair.childShim()), "to this home's new source");
        });

        suite.test("OHV-9 (b): npm/brew copy fallback (placeCopy, no options) replaces the link, never writes it", () -> {
            var pair = SkillScriptWriteThroughTest.Pair.make("wt-b-copy-");
            Path source = newSource(pair.child(), "brew-entry");

            ForeignBinLinks.placeCopy(source, pair.childShim());

            assertEquals(SkillScriptWriteThroughTest.PARENT_BYTES, Files.readString(pair.parentShim()),
                    "the parent's shim is byte-identical");
            assertTrue(Files.isRegularFile(pair.childShim(), LinkOption.NOFOLLOW_LINKS), "the child holds a real file");
            assertEquals("#!/bin/sh\necho brew-entry\n", Files.readString(pair.childShim()), "with the source's bytes");
        });

        suite.test("OHV-9 (b): tar placement (placeCopy, REPLACE_EXISTING + COPY_ATTRIBUTES) replaces the link, never writes it", () -> {
            var pair = SkillScriptWriteThroughTest.Pair.make("wt-b-tar-");
            Path source = newSource(pair.child(), "tar-entry");

            ForeignBinLinks.placeCopy(source, pair.childShim(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);

            assertEquals(SkillScriptWriteThroughTest.PARENT_BYTES, Files.readString(pair.parentShim()),
                    "the parent's shim is byte-identical");
            assertTrue(Files.isRegularFile(pair.childShim(), LinkOption.NOFOLLOW_LINKS), "the child holds a real file");
        });

        suite.test("OHV-9 (b): InstallerRegistry's post-install relativizeShims leaves a link into another home alone", () -> {
            var pair = SkillScriptWriteThroughTest.Pair.make("wt-b-relativize-");
            Path raw = Files.readSymbolicLink(pair.childShim());

            HomeLinks.relativizeShims(pair.child());

            assertEquals(SkillScriptWriteThroughTest.PARENT_BYTES, Files.readString(pair.parentShim()),
                    "the parent's shim is byte-identical");
            assertEquals(raw, Files.readSymbolicLink(pair.childShim()), "the link is untouched");
        });

        suite.test("OHV-9 (b): InstallerRegistry take-ownership deletes the link and its restore recreates it; the parent is never written", () -> {
            var pair = SkillScriptWriteThroughTest.Pair.make("wt-b-ownership-");
            Path raw = Files.readSymbolicLink(pair.childShim());
            CliDependency dep = SkillScriptWriteThroughTest.dep();

            Path removed = InstallerRegistry.takeOwnershipOfShim(dep, pair.child());
            assertEquals(raw, removed, "take-ownership removed the link and says what it pointed at");
            assertFalse(Files.exists(pair.childShim(), LinkOption.NOFOLLOW_LINKS), "the link is gone");
            InstallerRegistry.restoreForeignShim(dep, pair.child(), removed);

            assertEquals(SkillScriptWriteThroughTest.PARENT_BYTES, Files.readString(pair.parentShim()),
                    "the parent's shim is byte-identical");
            assertEquals(raw, Files.readSymbolicLink(pair.childShim()), "the link is back, exactly");
        });

        suite.test("OHV-9 (a): a DANGLING link into another home is still detached (resolution does not stop at bin/cli)", () -> {
            var pair = SkillScriptWriteThroughTest.Pair.make("wt-b-dangling-");
            Path absentInParent = pair.parent().cliBinDir().resolve("not-yet");
            Path dangling = pair.child().cliBinDir().resolve("not-yet");
            Files.createSymbolicLink(dangling, absentInParent);

            ForeignBinLinks links = ForeignBinLinks.detach(pair.child(), "test");
            try {
                assertEquals(List.of("not-yet", SkillScriptWriteThroughTest.TOOL), links.detachedNames(),
                        "both the live and the dangling foreign link are detached");
                assertFalse(Files.exists(dangling, LinkOption.NOFOLLOW_LINKS), "the dangling link is out of the way");
            } finally {
                links.restore();
            }
            links.requireNoForeignWrite();
            assertEquals(absentInParent, Files.readSymbolicLink(dangling), "and restored exactly");
            assertFalse(Files.exists(absentInParent, LinkOption.NOFOLLOW_LINKS), "nothing was created in the parent");
        });

        suite.test("OHV-9 (b): LauncherShims replaces a bin/cli/skill-manager link into another home instead of writing its pin there", () -> {
            var pair = SkillScriptWriteThroughTest.Pair.make("wt-b-launcher-");
            Path parentPin = pair.parent().cliBinDir().resolve("skill-manager");
            String parentBytes = "#!/usr/bin/env bash\n# parent's own pin\nexit 0\n";
            Files.writeString(parentPin, parentBytes, StandardCharsets.UTF_8);
            Path childPin = pair.child().cliBinDir().resolve("skill-manager");
            Files.createSymbolicLink(childPin, parentPin);
            Path fakeCli = newSource(pair.child(), "fake-cli");

            dev.skillmanager.launch.LauncherShims.write(pair.child(), fakeCli);

            assertEquals(parentBytes, Files.readString(parentPin), "the parent's CLI entrypoint is byte-identical");
            assertTrue(Files.isRegularFile(childPin, LinkOption.NOFOLLOW_LINKS), "the child holds its own entrypoint");
        });

        return suite.runAll();
    }

    private static Path newSource(SkillStore store, String label) throws Exception {
        Path dir = Files.createDirectories(store.root().resolve("npm/" + label + "/bin"));
        Path source = dir.resolve(label);
        Files.writeString(source, "#!/bin/sh\necho " + label + "\n", StandardCharsets.UTF_8);
        source.toFile().setExecutable(true, false);
        return source;
    }
}
