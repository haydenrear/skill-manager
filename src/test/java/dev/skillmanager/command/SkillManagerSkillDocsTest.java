package dev.skillmanager.command;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.cli.CliMetadata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class SkillManagerSkillDocsTest {

    public static int run() throws Exception {
        return Tests.suite("SkillManagerSkillDocsTest")
                // REMOVED at OUN-6. This case read skill-manager-skill/SKILL.md,
                // references/{workflows,projects,cli}.md and skill-manager.toml
                // from disk and asserted on their prose. That tree is installed
                // from its own repository now — as a contained skill of
                // tla-spec-dev — so there is nothing here to read, and pointing
                // the reads at a path that is always absent would fail on the
                // migration rather than on a defect.
                //
                // The assertions are not relocated into a skip: content
                // coverage belongs to the repository that holds the content.
                // What this repository still owns is the CATALOGUE — which
                // workflow points at which surface — and the two cases below
                // pin it exactly.
                .test("bundled skill docs cover modeled CLI workflows", () -> {
                    Map<String, String> docsBySurface = new LinkedHashMap<>();
                    for (String surface : CliMetadata.inTreeDocSurfaces()) {
                        docsBySurface.put(surface, markdownUnder(Path.of(surface)));
                    }

                    for (CliMetadata.WorkflowMetadata workflow : CliMetadata.workflows()) {
                        String helpCommand = helpCommand(workflow.commandPath());
                        for (String surface : workflow.relatedSkillDocs()) {
                            String docs = docsBySurface.get(surface);
                            // An external surface's bytes are in another
                            // repository. Skipping it silently would be a green
                            // result standing for nothing, so the skip is not
                            // silent: the next test names every workflow that
                            // takes this branch and fails if the list changes.
                            if (docs == null) continue;
                            assertContains(docs, workflow.id(),
                                    surface + " documents workflow id " + workflow.id());
                            assertContains(docs, helpCommand,
                                    surface + " routes " + workflow.id() + " to command help");
                        }
                    }
                })
                .test("no doc surface is readable here, and that is asserted not assumed", () -> {
                    // OUN-6 emptied inTreeDocSurfaces(). The loop above then
                    // reads nothing, so it would pass over an EMPTY catalogue
                    // just as happily as over a correct one. This is the case
                    // that stops that: the catalogue is non-empty, and every
                    // pair in it is external.
                    assertTrue(CliMetadata.inTreeDocSurfaces().isEmpty(),
                            "no surface is carried in this repository any more");
                    assertTrue(!CliMetadata.workflows().isEmpty(), "there are still workflows");
                    int pairs = 0;
                    for (CliMetadata.WorkflowMetadata w : CliMetadata.workflows()) {
                        pairs += w.relatedSkillDocs().size();
                    }
                    assertTrue(pairs > 0, "and they still name their doc surfaces");
                    assertTrue(CliMetadata.workflows().size()
                                    == CliMetadata.workflowsWithExternalDocs().size(),
                            "every workflow's docs are external now: "
                                    + CliMetadata.workflowsWithExternalDocs().size() + " of "
                                    + CliMetadata.workflows().size() + ", over " + pairs + " pairs");
                })

                .test("exactly the authoring workflows delegate docs outside this repo", () -> {
                    // SI-18. `unit-authoring` moved into the tla-spec-dev plugin
                    // and out of this repository, taking five workflows' docs
                    // with it. No test here can read those pages; this one pins
                    // WHICH workflows are allowed to point at them, so a sixth
                    // cannot join them by editing one string in CliMetadata and
                    // quietly leaving coverage.
                    // OUN-6 emptied inTreeDocSurfaces(), so EVERY workflow is
                    // external and "which ones delegate outward" stopped
                    // discriminating. The durable pin is which ones name the
                    // AUTHORING surface specifically — that is the claim this
                    // case was really making, and it survives the surface
                    // count going to zero.
                    java.util.Set<String> expected = new java.util.TreeSet<>(java.util.List.of(
                            "author-dependencies",
                            "author-unit",
                            "install-local-unit",
                            "publish-unit",
                            "skill-scripts"));
                    java.util.Set<String> actual = new java.util.TreeSet<>();
                    for (CliMetadata.WorkflowMetadata w : CliMetadata.workflows()) {
                        if (w.relatedSkillDocs().contains(CliMetadata.UNIT_AUTHORING_DOCS)) {
                            actual.add(w.id());
                        }
                    }
                    assertTrue(expected.equals(actual),
                            "workflows documented by " + CliMetadata.UNIT_AUTHORING_DOCS
                                    + ": expected " + expected + " but was " + actual);

                    // Every surface is on the explicit roster. Nothing is
                    // readable after OUN-6, so a typo'd surface would otherwise
                    // land in the external bucket and look deliberate — the
                    // check the readable-surface lookup used to do for free.
                    for (CliMetadata.WorkflowMetadata workflow : CliMetadata.workflows()) {
                        for (String surface : workflow.relatedSkillDocs()) {
                            assertTrue(CliMetadata.knownDocSurfaces().contains(surface),
                                    "known doc surface for " + workflow.id() + ": " + surface);
                        }
                    }
                })
                .runAll();
    }

    private static String markdownUnder(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.joining("\n"));
        }
    }

    private static String helpCommand(String commandPath) {
        if ("skill-manager".equals(commandPath)) {
            return "skill-manager --help";
        }
        return "skill-manager " + commandPath + " --help";
    }
}
