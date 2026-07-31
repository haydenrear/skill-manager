///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ChildHomeSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Structural half of {@code ChildHomeWritesNeverReachTheParentStore}: every
 * child-home unit directory is its own tree, not the parent store's tree under
 * another name.
 *
 * <p>Deliberately checked with {@code NOFOLLOW_LINKS} and {@code toRealPath}.
 * {@code Files.isRegularFile(child/skills/x/SKILL.md)} — the check this graph
 * exists to replace — is true for a symlink into the parent store and for an
 * independent copy alike, so it asserts reachability and says nothing about
 * independence.
 *
 * <p>The empirical half (an actual write, and the parent store afterwards)
 * lives in {@code child.home.edit.stays.in.child.home}.
 */
public class ChildHomeUnitsIndependent {
    static final NodeSpec SPEC = NodeSpec.of("child.home.units.independent")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("child.home.resolved")
            .tags("child-home", "independence")
            .timeout("120s");

    private static final String[] UNITS = {
            ChildHomeSupport.UNIT_A, ChildHomeSupport.UNIT_B,
            ChildHomeSupport.UNIT_C, ChildHomeSupport.UNIT_D };

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String projectDirRaw = ctx.get("child.home.resolved", "projectDir").orElse(null);
            if (home == null || projectDirRaw == null) {
                return NodeResult.fail("child.home.units.independent", "missing upstream context");
            }
            Path homeDir = Path.of(home);
            Path projectDir = Path.of(projectDirRaw);
            Path parentSkills = homeDir.resolve("skills");

            List<String> notRealDirs = new ArrayList<>();
            List<String> aliasesOfParentStore = new ArrayList<>();
            List<String> notRealFiles = new ArrayList<>();
            List<String> badRecords = new ArrayList<>();
            List<String> misattributedRecords = new ArrayList<>();

            for (String unit : UNITS) {
                Path childUnit = ChildHomeSupport.childUnit(projectDir, unit);
                if (!ChildHomeSupport.isRealDirectory(childUnit)) notRealDirs.add(unit);
                if (ChildHomeSupport.realPathInside(childUnit, parentSkills)) {
                    aliasesOfParentStore.add(unit);
                }
                if (!ChildHomeSupport.isRealFile(childUnit.resolve("SKILL.md"))) notRealFiles.add(unit);

                Path record = ChildHomeSupport.materializationRecord(projectDir, unit);
                String recordText = ChildHomeSupport.read(record);
                if (!Files.isRegularFile(record) || !recordText.contains("\"mode\" : \"COPY\"")) {
                    badRecords.add(unit);
                    continue;
                }
                // The record must name the parent-store unit it came from. The
                // stronger "the record still describes what is on disk" claim is
                // asserted behaviorally by child.home.resolved, whose second
                // resolve holds nothing back only if the first pass's record
                // matches the tree it wrote.
                if (!recordText.contains(
                        "\"source\" : \"" + ChildHomeSupport.parentUnit(homeDir, unit) + "\"")) {
                    misattributedRecords.add(unit);
                }
            }

            boolean unitsAreRealDirectories = notRealDirs.isEmpty();
            boolean unitsAreNotParentStoreAliases = aliasesOfParentStore.isEmpty();
            boolean unitFilesAreRealFiles = notRealFiles.isEmpty();
            boolean recordsSayCopy = badRecords.isEmpty();
            boolean recordsNameTheirSource = misattributedRecords.isEmpty();

            boolean pass = unitsAreRealDirectories && unitsAreNotParentStoreAliases
                    && unitFilesAreRealFiles && recordsSayCopy && recordsNameTheirSource;
            return (pass
                    ? NodeResult.pass("child.home.units.independent")
                    : NodeResult.fail("child.home.units.independent",
                            "notRealDirs=" + notRealDirs
                                    + " aliasesOfParentStore=" + aliasesOfParentStore
                                    + " notRealFiles=" + notRealFiles
                                    + " badRecords=" + badRecords
                                    + " misattributedRecords=" + misattributedRecords))
                    .assertion("child_unit_dirs_are_real_directories_not_symlinks", unitsAreRealDirectories)
                    .assertion("child_unit_dirs_do_not_resolve_into_the_parent_store",
                            unitsAreNotParentStoreAliases)
                    .assertion("child_unit_files_are_real_files_not_symlinks", unitFilesAreRealFiles)
                    .assertion("materialization_records_declare_copy_mode", recordsSayCopy)
                    .assertion("materialization_records_name_their_parent_source", recordsNameTheirSource);
        });
    }
}
