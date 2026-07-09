package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.model.UnitKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket SMVENV-001. Pins the three acceptance assertions of the
 * content-addressed store against the real filesystem layout:
 *
 * <ul>
 *   <li>{@code VenvStoreVersionsAreContentAddressed} — every stored version
 *       is reachable at {@code skills/<name>/<sha>/}.</li>
 *   <li>{@code VenvStoreLatestIsStored} and
 *       {@code VenvStoreLatestUniquePerUnit} — storing the same unit at two
 *       shas leaves both snapshots and exactly one latest pointer.</li>
 *   <li>Removing a unit leaves stored snapshots intact — the store is an
 *       immutable cache, not part of the install lifecycle.</li>
 * </ul>
 */
public final class ContentAddressedStoreTest {

    private static SkillStore storeWithSkill(Path home, String name, String body) throws IOException {
        SkillStore store = new SkillStore(home);
        store.init();
        Path workingCopy = store.skillDir(name);
        Files.createDirectories(workingCopy);
        Files.writeString(workingCopy.resolve(SkillParser.SKILL_FILENAME),
                "---\nname: " + name + "\ndescription: " + body + "\n---\n\n" + body + "\n");
        return store;
    }

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("content-addressed-store-test-");

        return Tests.suite("ContentAddressedStoreTest")
                .test("store add writes content under skills/<name>/<sha>/", () -> {
                    SkillStore store = storeWithSkill(tmp.resolve("h1"), "widget", "first");
                    store.storeUnitVersion("widget", "sha-a");

                    Path snapshot = store.storeVersionDir("widget", "sha-a");
                    assertTrue(Files.isRegularFile(snapshot.resolve(SkillParser.SKILL_FILENAME)),
                            "snapshot holds the unit content");
                    assertEquals(store.skillsDir().resolve("widget").resolve("sha-a"), snapshot,
                            "snapshot path is content-addressed");
                    assertEquals(List.of("sha-a"), store.storedVersions("widget"), "one stored version");
                    assertEquals("sha-a", store.storeLatest("widget").orElse(null), "latest points at the sha");
                })
                .test("two shas leave both snapshots and exactly one latest", () -> {
                    Path home = tmp.resolve("h2");
                    SkillStore store = storeWithSkill(home, "widget", "first");
                    store.storeUnitVersion("widget", "sha-a");

                    // Advance the working copy the way a sync would, then re-store.
                    Files.writeString(store.skillDir("widget").resolve(SkillParser.SKILL_FILENAME),
                            "---\nname: widget\ndescription: second\n---\n\nsecond\n");
                    store.storeUnitVersion("widget", "sha-b");

                    assertEquals(List.of("sha-a", "sha-b"), store.storedVersions("widget"),
                            "both snapshots survive");
                    assertEquals("sha-b", store.storeLatest("widget").orElse(null),
                            "latest moved to the newest sha");
                    assertTrue(Files.readString(store.storeVersionDir("widget", "sha-a")
                                    .resolve(SkillParser.SKILL_FILENAME)).contains("first"),
                            "the older snapshot keeps its original content");
                    assertTrue(Files.readString(store.storeVersionDir("widget", "sha-b")
                                    .resolve(SkillParser.SKILL_FILENAME)).contains("second"),
                            "the newer snapshot holds the advanced content");
                })
                .test("a snapshot records content, not the git clone", () -> {
                    SkillStore store = storeWithSkill(tmp.resolve("h9"), "widget", "first");
                    Path workingCopy = store.skillDir("widget");
                    Files.createDirectories(workingCopy.resolve(".git").resolve("objects"));
                    Files.writeString(workingCopy.resolve(".git").resolve("HEAD"), "ref: refs/heads/main\n");
                    Files.createDirectories(workingCopy.resolve("references"));
                    Files.writeString(workingCopy.resolve("references").resolve("guide.md"), "guide\n");

                    store.storeUnitVersion("widget", "sha-a");
                    Path snapshot = store.storeVersionDir("widget", "sha-a");

                    assertTrue(Files.isRegularFile(snapshot.resolve(SkillParser.SKILL_FILENAME)),
                            "snapshot holds SKILL.md");
                    assertTrue(Files.isRegularFile(snapshot.resolve("references").resolve("guide.md")),
                            "snapshot holds nested content");
                    assertFalse(Files.exists(snapshot.resolve(".git")),
                            "snapshot does not duplicate the git history");
                    assertTrue(Files.isDirectory(workingCopy.resolve(".git")),
                            "the working copy keeps its clone");
                })
                .test("re-storing the same sha refreshes rather than fails", () -> {
                    SkillStore store = storeWithSkill(tmp.resolve("h3"), "widget", "first");
                    store.storeUnitVersion("widget", "sha-a");
                    store.storeUnitVersion("widget", "sha-a");
                    assertEquals(List.of("sha-a"), store.storedVersions("widget"), "still one version");
                })
                .test("remove leaves stored snapshots intact (cache semantics)", () -> {
                    SkillStore store = storeWithSkill(tmp.resolve("h4"), "widget", "first");
                    store.storeUnitVersion("widget", "sha-a");
                    store.remove("widget");

                    assertFalse(store.contains("widget"), "working copy is gone");
                    assertEquals(List.of("sha-a"), store.storedVersions("widget"), "snapshot survives removal");
                    assertEquals("sha-a", store.storeLatest("widget").orElse(null), "latest still names a stored sha");
                })
                .test("remove prunes the slot when nothing was ever stored", () -> {
                    SkillStore store = storeWithSkill(tmp.resolve("h5"), "widget", "first");
                    store.remove("widget");
                    assertFalse(Files.exists(store.storeUnitDir("widget")), "empty slot is pruned");
                })
                .test("removeUnit(PLUGIN) still deletes the whole plugin dir", () -> {
                    SkillStore store = new SkillStore(tmp.resolve("h6"));
                    store.init();
                    Path plugin = store.unitDir("gadget", UnitKind.PLUGIN);
                    Files.createDirectories(plugin);
                    Files.writeString(plugin.resolve("marker"), "x");
                    store.removeUnit("gadget", UnitKind.PLUGIN);
                    assertFalse(Files.exists(plugin), "plugin dir removed outright");
                })
                .test("snapshots are not mistaken for installed units", () -> {
                    SkillStore store = storeWithSkill(tmp.resolve("h7"), "widget", "first");
                    store.storeUnitVersion("widget", "sha-a");
                    store.storeUnitVersion("widget", "sha-b");

                    List<String> names = store.listInstalled().skills().stream().map(s -> s.name()).toList();
                    assertEquals(List.of("widget"), names, "listInstalled sees one unit, not one per sha");
                })
                .test("migration moves a legacy flat skill under latest/ and is idempotent", () -> {
                    Path home = tmp.resolve("h8");
                    SkillStore store = new SkillStore(home);
                    store.init();
                    // Legacy layout: content sits directly in skills/<name>/.
                    Path legacy = store.skillsDir().resolve("widget");
                    Files.createDirectories(legacy.resolve(".git"));
                    Files.writeString(legacy.resolve(SkillParser.SKILL_FILENAME),
                            "---\nname: widget\ndescription: legacy\n---\n\nlegacy\n");

                    assertEquals(1, SkillStore.migrateToContentAddressed(store), "one slot migrated");
                    assertTrue(Files.isRegularFile(store.skillDir("widget").resolve(SkillParser.SKILL_FILENAME)),
                            "content moved under latest/");
                    assertTrue(Files.isDirectory(store.skillDir("widget").resolve(".git")),
                            "the in-place git clone moved with it");
                    assertTrue(store.contains("widget"), "unit still resolves after migration");
                    assertEquals(0, SkillStore.migrateToContentAddressed(store), "second run is a no-op");
                })
                .runAll();
    }
}
