///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../../../src/main/java/dev/skillmanager/pm/PackageCaches.java
//SOURCES ../../../src/main/java/dev/skillmanager/agent/AgentHomes.java
//SOURCES ../../../src/main/java/dev/skillmanager/util/Platform.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import dev.skillmanager.pm.PackageCaches;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A home may own its venvs. It may not own the package store they come from.
 *
 * <h2>The two directions, and why one assertion is not enough</h2>
 *
 * <p>Every directory a package manager writes is either a
 * <b>content-addressed store</b> (shareable — entries are named by what they
 * contain, so a write is always an append under a new key) or an <b>install
 * target</b> (not shareable — mutable, and two homes pointed at one are a
 * {@code --force} apart from breaking each other). This node asserts
 * <em>both</em> directions, because each has its own way of regressing and
 * they regress in opposite directions:
 *
 * <ul>
 *   <li><b>Sharing narrows.</b> Something starts redirecting
 *       {@code UV_CACHE_DIR} (or {@code npm_config_cache}, or
 *       {@code PIP_CACHE_DIR}) under {@code $SKILL_MANAGER_HOME} "for
 *       isolation". This is not hypothetical — a per-worktree confinement
 *       layer did exactly that, and every home began carving its own copy out
 *       of a 51 GB store that was already sitting there, correct and
 *       shared.</li>
 *   <li><b>Sharing widens.</b> Somebody reads "share the cache", concludes
 *       that the venv is a cache, and shares {@code UV_TOOL_DIR} too. That
 *       reintroduces exactly the cross-project mutation the split exists to
 *       prevent. Hence
 *       {@code no_install_target_is_smuggled_into_the_shared_block}.</li>
 * </ul>
 *
 * <h2>And the materialization mode, which is the other half of the win</h2>
 *
 * <p>A shared store that every venv copies out of saves download time and
 * nothing else. {@link PackageCaches#linkMode(Path)} must pick a
 * by-reference mode whenever the store and the home sit on one filesystem,
 * and must degrade to {@code copy} when they do not — a mounted cache volume
 * in CI is the normal case, and hardlinking across a mount point does not
 * fail gracefully, it fails.
 *
 * <h2>Anti-vacuity: this node refuses to pass on zero observations</h2>
 *
 * <p>Every assertion here is paired with a check that the thing being
 * asserted about was actually found. A missing backend file, an empty env
 * block, a link-mode probe that could not reach a second filesystem — each is
 * a FAILURE, not a silently-satisfied {@code allMatch} over an empty set. The
 * epic this belongs to has already been misled several times by a zero that
 * meant "could not look" and was read as "looked and found nothing", so the
 * counters below are asserted against explicit floors and reported as metrics
 * whether the node passes or fails.
 *
 * <h2>What the required mutations prove</h2>
 *
 * <p>Each of these was applied to a pristine checkout and run; each failed
 * the one named assertion and no other, with the unmutated control green in
 * the same battery.
 *
 * <ul>
 *   <li>{@code uvCacheDir()} → {@code $SKILL_MANAGER_HOME/cache/uv}, i.e. the
 *       home given a private store →
 *       {@code the_uv_store_is_the_one_uv_itself_would_use} FAILS.</li>
 *   <li>{@code linkMode()} returns {@code "copy"} unconditionally →
 *       {@code materialization_is_by_reference_on_one_filesystem} FAILS while
 *       {@code the_link_mode_still_degrades_to_copy_across_filesystems} stays
 *       GREEN. That asymmetry is the point: the second is the control that
 *       stops the first from being satisfiable by a function that has lost
 *       the ability to say {@code copy} at all.</li>
 *   <li>The {@code PackageCaches} call deleted from {@code SkillScriptBackend}
 *       → {@code every_installer_backend_routes_through_the_shared_block}
 *       FAILS and names the file.</li>
 *   <li>{@code LaunchEnv} additionally sets {@code UV_CACHE_DIR} under the
 *       store — the exact shape of the confinement-layer regression →
 *       {@code only_package_caches_decides_where_a_shared_store_lives}
 *       FAILS. Note that this one is invisible to every other assertion here,
 *       because {@code PackageCaches} itself stays correct throughout.</li>
 *   <li>{@code UV_TOOL_DIR} added to the shared block →
 *       {@code no_install_target_is_smuggled_into_the_shared_block}
 *       FAILS.</li>
 * </ul>
 */
public class SharedPackageCacheIsNotPrivateToTheHome {

    static final NodeSpec SPEC = NodeSpec.of("shared.package.cache.is.not.private.to.the.home")
            .kind(NodeSpec.Kind.ASSERTION)
            .tags("home-clone", "package-cache", "oracle")
            .timeout("180s");

    /** The variables that must name a store shared by every home. */
    private static final List<String> SHARED_KEYS =
            List.of("UV_CACHE_DIR", "npm_config_cache", "PIP_CACHE_DIR");

    /**
     * Variables that name a mutable install root. If one of these ever turns
     * up in the shared block, "share the cache" has been widened into "share
     * the venv".
     */
    private static final Set<String> INSTALL_TARGET_KEYS = Set.of(
            "UV_TOOL_DIR", "UV_TOOL_BIN_DIR", "UV_PROJECT_ENVIRONMENT",
            "VIRTUAL_ENV", "npm_config_prefix", "SKILL_MANAGER_CACHE_DIR");

    /** Every backend that spawns a package manager must route through PackageCaches. */
    private static final List<String> BACKENDS = List.of(
            "src/main/java/dev/skillmanager/cli/installer/PipBackend.java",
            "src/main/java/dev/skillmanager/cli/installer/NpmBackend.java",
            "src/main/java/dev/skillmanager/cli/installer/SkillScriptBackend.java",
            "src/main/java/dev/skillmanager/launch/LaunchEnv.java");

    /** Modes that share blocks with the store. Anything else is a full copy. */
    private static final Set<String> BY_REFERENCE = Set.of("clone", "hardlink");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            List<String> notes = new ArrayList<>();
            Path repo = repoRoot();

            // A home that does not exist yet, exactly as a fresh worktree's
            // does not. Resolution must not depend on it having been created.
            Path home = Path.of(System.getProperty("user.dir"))
                    .resolve("build/pkgcache-" + ctx.runId() + "/.skill-manager");
            Files.createDirectories(home);
            Path venvs = home.resolve("venvs");

            Map<String, String> shared = new LinkedHashMap<>(PackageCaches.sharedEnv(venvs));
            notes.add("sharedEnv=" + shared);

            // ---- anti-vacuity floor -------------------------------------
            // Nothing below means anything if the block came back empty, so
            // this is asserted first and reported as a metric either way.
            int observed = 0;
            for (String key : SHARED_KEYS) {
                String v = shared.get(key);
                if (v != null && !v.isBlank()) observed++;
            }
            boolean everySharedStoreWasResolved = observed == SHARED_KEYS.size();
            boolean aLinkModeWasResolved = shared.get("UV_LINK_MODE") != null
                    && !shared.get("UV_LINK_MODE").isBlank();

            // ---- direction 1: the stores are outside every home ---------
            Path homeRoot = home.toAbsolutePath().normalize();
            List<String> insideAHome = new ArrayList<>();
            for (String key : SHARED_KEYS) {
                String raw = shared.get(key);
                if (raw == null || raw.isBlank()) continue;
                Path p = Path.of(raw).toAbsolutePath().normalize();
                if (!p.isAbsolute() || p.startsWith(homeRoot)) insideAHome.add(key + "=" + p);
            }
            boolean everySharedStoreIsOutsideAnySingleHome =
                    everySharedStoreWasResolved && insideAHome.isEmpty();

            // The uv store specifically must be the one the operator already
            // has 51 GB in — i.e. what uv itself would resolve to. Reproduced
            // here from uv's documented order rather than from the class under
            // test, so agreeing with the class is not the same as agreeing
            // with uv.
            Path expectedUv = expectedUvCacheDir();
            boolean theUvStoreIsTheOneUvItselfWouldUse =
                    expectedUv.equals(Path.of(shared.getOrDefault("UV_CACHE_DIR", "/dev/null"))
                            .toAbsolutePath().normalize());
            notes.add("expectedUvCache=" + expectedUv);

            // ---- direction 2: no install target rode along ---------------
            List<String> smuggled = new ArrayList<>();
            for (String key : shared.keySet()) {
                if (INSTALL_TARGET_KEYS.contains(key)) smuggled.add(key);
            }
            boolean noInstallTargetIsSmuggledIntoTheSharedBlock = smuggled.isEmpty();

            // ---- materialization: by reference on one filesystem ---------
            // The target here is under the operator's own home, so it shares a
            // filesystem with the uv store on any ordinary developer machine.
            Path sameFsTarget = PackageCaches.uvCacheDir().getParent();
            String sameFsMode = PackageCaches.linkMode(sameFsTarget);
            boolean materializationIsByReferenceOnOneFilesystem =
                    BY_REFERENCE.contains(sameFsMode);
            notes.add("linkMode(sameFs=" + sameFsTarget + ")=" + sameFsMode);

            // ---- the control: it can still say copy ----------------------
            // Without this, "the mode is never copy" would also be satisfied
            // by a function that had lost the ability to return copy at all —
            // and that function would hardlink across a mount point in CI.
            Path otherFs = aPathOnAnotherFilesystem();
            String crossFsMode = otherFs == null ? null : PackageCaches.linkMode(otherFs);
            boolean aSecondFilesystemWasFound = otherFs != null;
            boolean theLinkModeStillDegradesToCopyAcrossFilesystems =
                    aSecondFilesystemWasFound && "copy".equals(crossFsMode);
            notes.add("linkMode(otherFs=" + otherFs + ")=" + crossFsMode);

            // ---- exactly one file in production may name these -----------
            // The regression that motivated this node was a SECOND place
            // deciding where the uv cache lives: a per-worktree confinement
            // layer put UV_CACHE_DIR under the worktree, PackageCaches' rule
            // stayed correct, and the two never had to agree. So the rule is
            // not "the shared block is right" — it is "there is one shared
            // block". Any other production file naming one of these variables
            // is a second copy of the rule, and second copies of a rule in
            // this codebase have diverged every single time.
            List<String> secondOpinions = new ArrayList<>();
            int javaFilesScanned = 0;
            Path production = repo.resolve("src/main/java/dev/skillmanager");
            if (Files.isDirectory(production)) {
                try (var walk = Files.walk(production)) {
                    for (Path file : walk.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".java")).toList()) {
                        if (file.getFileName().toString().equals("PackageCaches.java")) continue;
                        javaFilesScanned++;
                        String body = Files.readString(file);
                        for (String key : SHARED_KEYS) {
                            if (body.contains("\"" + key + "\"")) {
                                secondOpinions.add(repo.relativize(file) + " names " + key);
                            }
                        }
                    }
                }
            }
            boolean theProductionTreeWasScanned = javaFilesScanned > 50;
            boolean onlyPackageCachesDecidesWhereASharedStoreLives =
                    theProductionTreeWasScanned && secondOpinions.isEmpty();
            notes.add("javaFilesScanned=" + javaFilesScanned
                    + " secondOpinions=" + secondOpinions);

            // ---- the call sites actually use it --------------------------
            List<String> notRouted = new ArrayList<>();
            int backendsRead = 0;
            for (String rel : BACKENDS) {
                Path file = repo.resolve(rel);
                if (!Files.isRegularFile(file)) {
                    notRouted.add(rel + " (MISSING — renamed or deleted; this check "
                            + "cannot silently stop covering a file)");
                    continue;
                }
                backendsRead++;
                if (!Files.readString(file).contains("PackageCaches")) notRouted.add(rel);
            }
            boolean everyBackendWasRead = backendsRead == BACKENDS.size();
            boolean everyInstallerBackendRoutesThroughTheSharedBlock =
                    everyBackendWasRead && notRouted.isEmpty();

            boolean pass = everySharedStoreWasResolved
                    && aLinkModeWasResolved
                    && everySharedStoreIsOutsideAnySingleHome
                    && theUvStoreIsTheOneUvItselfWouldUse
                    && noInstallTargetIsSmuggledIntoTheSharedBlock
                    && materializationIsByReferenceOnOneFilesystem
                    && theLinkModeStillDegradesToCopyAcrossFilesystems
                    && onlyPackageCachesDecidesWhereASharedStoreLives
                    && everyInstallerBackendRoutesThroughTheSharedBlock;

            String detail = "shared=" + observed + "/" + SHARED_KEYS.size()
                    + " linkMode=" + sameFsMode + " crossFs=" + crossFsMode
                    + " insideAHome=" + insideAHome + " smuggled=" + smuggled
                    + " notRouted=" + notRouted + " secondOpinions=" + secondOpinions;
            for (String note : notes) System.out.println(note);
            System.out.println(detail);

            return (pass ? NodeResult.pass(SPEC.id()) : NodeResult.fail(SPEC.id(), detail))
                    .assertion("every_shared_store_was_resolved_to_a_real_path",
                            everySharedStoreWasResolved)
                    .assertion("a_link_mode_was_resolved", aLinkModeWasResolved)
                    .assertion("every_shared_store_is_outside_any_single_home",
                            everySharedStoreIsOutsideAnySingleHome)
                    .assertion("the_uv_store_is_the_one_uv_itself_would_use",
                            theUvStoreIsTheOneUvItselfWouldUse)
                    .assertion("no_install_target_is_smuggled_into_the_shared_block",
                            noInstallTargetIsSmuggledIntoTheSharedBlock)
                    .assertion("materialization_is_by_reference_on_one_filesystem",
                            materializationIsByReferenceOnOneFilesystem)
                    .assertion("a_second_filesystem_was_found_to_test_the_fallback_against",
                            aSecondFilesystemWasFound)
                    .assertion("the_link_mode_still_degrades_to_copy_across_filesystems",
                            theLinkModeStillDegradesToCopyAcrossFilesystems)
                    .assertion("the_production_tree_was_actually_scanned",
                            theProductionTreeWasScanned)
                    .assertion("only_package_caches_decides_where_a_shared_store_lives",
                            onlyPackageCachesDecidesWhereASharedStoreLives)
                    .assertion("every_backend_file_named_by_this_check_still_exists",
                            everyBackendWasRead)
                    .assertion("every_installer_backend_routes_through_the_shared_block",
                            everyInstallerBackendRoutesThroughTheSharedBlock)
                    .metric("sharedStoresObserved", observed)
                    .metric("backendsRead", backendsRead)
                    .metric("javaFilesScanned", javaFilesScanned)
                    .metric("secondOpinions", secondOpinions.size())
                    .metric("storesInsideAHome", insideAHome.size())
                    .metric("installTargetsSmuggled", smuggled.size())
                    .log(detail);
        });
    }

    // ------------------------------------------------------------- probes

    /**
     * uv's own cache resolution, written out independently of the class under
     * test. If both were derived from one helper, a wrong rule would agree
     * with itself and the assertion would prove nothing.
     */
    private static Path expectedUvCacheDir() {
        String explicit = System.getenv("UV_CACHE_DIR");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit.trim()).toAbsolutePath().normalize();
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg.trim()).resolve("uv").toAbsolutePath().normalize();
        }
        String home = System.getenv("HOME");
        Path base = home != null && !home.isBlank()
                ? Path.of(home)
                : Path.of(System.getProperty("user.home"));
        return base.resolve(".cache").resolve("uv").toAbsolutePath().normalize();
    }

    /**
     * A directory on a filesystem other than the one holding the uv store.
     * {@code /Volumes} and {@code /dev/shm} are the usual candidates; null
     * when the host really has only one, which the caller reports as a failed
     * observation rather than a satisfied assertion.
     */
    private static Path aPathOnAnotherFilesystem() {
        Object uvStore = fileStoreOf(PackageCaches.uvCacheDir());
        for (Path candidate : List.of(Path.of("/Volumes"), Path.of("/dev/shm"),
                Path.of("/System/Volumes/Preboot"), Path.of("/private/var/vm"))) {
            if (!Files.isDirectory(candidate)) continue;
            Object store = fileStoreOf(candidate);
            if (store != null && uvStore != null && !store.equals(uvStore)) return candidate;
        }
        // Last resort: any mount under /Volumes that differs.
        try (var entries = Files.list(Path.of("/Volumes"))) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                Object store = fileStoreOf(entry);
                if (store != null && uvStore != null && !store.equals(uvStore)) return entry;
            }
        } catch (IOException | RuntimeException none) {
            // reported by the caller as "no second filesystem"
        }
        return null;
    }

    private static Object fileStoreOf(Path path) {
        Path probe = path == null ? null : path.toAbsolutePath().normalize();
        while (probe != null) {
            if (Files.exists(probe)) {
                try {
                    return Files.getFileStore(probe);
                } catch (IOException unreadable) {
                    return null;
                }
            }
            probe = probe.getParent();
        }
        return null;
    }

    /** The repository root — this file lives at {@code test_graph/sources/home-clone/}. */
    private static Path repoRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path probe = cwd; probe != null; probe = probe.getParent()) {
            if (Files.isDirectory(probe.resolve("src/main/java/dev/skillmanager"))) return probe;
        }
        return cwd;
    }

    @SuppressWarnings("unused")
    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
