package dev.skillmanager.store;

import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.artifacts.Artifact;
import dev.skillmanager.artifacts.ArtifactIds;
import dev.skillmanager.artifacts.ArtifactIndex;
import dev.skillmanager.artifacts.ArtifactKind;
import dev.skillmanager.artifacts.ArtifactLedger;
import dev.skillmanager.artifacts.ColdArtifactShim;
import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingJson;
import dev.skillmanager.bindings.ChildHomeRegistry;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionLedger;
import dev.skillmanager.launch.LaunchEnv;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.shared.util.Rederivable;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies a Skill Manager home to a new root and proves the copy does not
 * reach back into the original.
 *
 * <p>A home is meant to be a pure function of {@code SKILL_MANAGER_HOME}:
 * point the env var at the copy and forget the original. Everything
 * skill-manager itself writes now satisfies that at write time
 * ({@link HomePaths} for metadata, {@link HomeLinks} for symlinks), so for
 * a home written by current code the clone is a straight copy. Homes that
 * predate that encoding, and artifacts skill-manager does not author, need
 * the fixups below.
 *
 * <h2>What is copied</h2>
 *
 * Everything except {@link #SKIPPED_DIRS} and {@link #SKIPPED_ROOT_FILES}.
 * {@code cache/} is the bulk of a real home (2.9 GB of 5.4 GB) and is
 * re-derivable by definition. {@code logs/}, {@code tmp/} and the root log
 * files are history of what the <em>source</em> home did; copying them
 * would carry thousands of absolute references to the source into the copy
 * for no benefit.
 *
 * <h2>The four classes of path, and what happens to each</h2>
 *
 * <ol>
 *   <li><b>Symlinks</b> — a link target cannot hold {@code $SKILL_MANAGER_HOME};
 *       the kernel resolves the stored bytes literally. Absolute links into
 *       the source home are rewritten <em>relative</em>, which is
 *       root-independent and therefore permanent.</li>
 *   <li><b>{@link Surface#STATE}</b> — skill-manager's own records
 *       ({@code installed/}, {@code child-homes/}, {@code projects/}).
 *       Re-read through the production serde and rewritten in
 *       {@code $SKILL_MANAGER_HOME/...} form. A no-op for a home written by
 *       current code; a one-time migration for an older one.</li>
 *   <li><b>{@link Surface#PROVISIONED}</b> — python virtualenvs, tool trees
 *       and generated shims. A venv console script starts
 *       {@code #!/abs/path/to/venv/bin/python}; a shebang, like a symlink
 *       target, is resolved literally and cannot be made relative. These
 *       are re-anchored by byte substitution on every clone. This is the
 *       one class that genuinely cannot be fixed at write time.</li>
 *   <li><b>{@link Surface#CONTENT}</b> — authored unit content. Spec
 *       {@code .history} records and effect-provider evidence legitimately
 *       mention the absolute path of the home a past run used. Rewriting
 *       them would corrupt append-only records, so they are left exactly as
 *       they are and reported as {@link Report#contentReferences()}.</li>
 * </ol>
 *
 * <h2>Verification</h2>
 *
 * After the copy, {@link #verify} walks the destination looking for any
 * surviving absolute reference to the source home. Anything found in
 * classes 1–3 is a {@link Leak} and fails the clone. Class 4 is counted and
 * reported, and only fails under {@code strict}.
 *
 * <p>It also asks a second, larger question that the first one does not
 * imply: does anything in the copy reach into <em>another</em> Skill Manager
 * home — the operator's live one, a sibling project's? The source home is
 * only one of the homes a copy must stay out of, and scoping the check to it
 * let a link into {@code ~/.skill-manager} pass as independence. See
 * {@link #foreignHomeReachedBy} for the measurement and for why the
 * apparently stronger "nothing may resolve outside the copy" is the wrong
 * rule.
 */
public final class HomeCloner {

    /**
     * Top-level directories a clone does not copy.
     *
     * <p>{@code cache}, {@code tmp} and {@code logs} are transient. The three
     * toolchain roots are skipped for a different and load-bearing reason:
     * <b>installers write into them</b>. {@code PipBackend} points
     * {@code UV_TOOL_DIR} at {@code venvs/}, {@code SkillScriptBackend} hands
     * {@code SKILL_MANAGER_CACHE_DIR} to arbitrary install scripts, and
     * {@code sync --force-scripts} reruns both. Sharing one copy between
     * homes would therefore reintroduce exactly the cross-project mutation
     * this whole mechanism exists to remove, only moved from {@code skills/}
     * to {@code venvs/} — and silently, since a skill-script installer is
     * unbounded user code. Copying them per clone is the alternative, and it
     * is expensive: {@code tools/} is 1.3 GB and {@code npm/} 155 MB on a
     * real home. So a clone carries neither: it skips them and re-provisions
     * from {@code cli-lock.toml}, which is also the only option under which
     * one project installing a CLI dep cannot disturb another.
     *
     * <p>{@code venvs/} is only ~2 MB, so it is skipped for correctness
     * rather than size: a pip/uv console script's shebang is an absolute path
     * to that venv's interpreter, and a shebang is resolved literally by the
     * kernel, so it cannot be tokenized the way stored paths can.
     * Re-provisioning writes a correct one instead of rewriting a file a
     * third-party tool generated.
     *
     * <p><b>{@code pm/} is deliberately NOT here.</b> It holds the bundled
     * {@code node} and {@code uv} — the package managers re-provisioning
     * itself needs. Skipping it would leave a clone unable to rebuild the
     * very toolchains it is missing.
     */
    public static final Set<String> SKIPPED_DIRS =
            Set.of("cache", "tmp", "logs", "venvs", "tools", "npm");

    /**
     * Derived build caches, skipped wherever they appear. These are the
     * same kind of thing as {@code cache/} — regenerated on demand from
     * content the clone does carry — and they are the one place where a
     * source path survives in a form nothing can fix: a {@code .pyc}
     * embeds its {@code co_filename} and a Gradle {@code executionHistory.bin}
     * embeds task input paths, both inside binary formats where a
     * length-changing substitution would corrupt the file. Copying them
     * would guarantee a leak; not copying them costs one recompile.
     *
     * <p><b>The list itself now lives in {@link Rederivable#CACHES}</b>, which
     * is also what {@code ChildHomeMaterializer} reads. It was duplicated
     * before — the cloner skipped {@code .gradle} inside a unit and the
     * reconcile did not — and a name one caller skips and the other does not is
     * a unit that reports {@code conflicted} forever (issue #41). One
     * definition, one place to change it. {@link Rederivable#OUTPUT_ROOTS} is
     * deliberately NOT read here; see that class for why a clone must still
     * carry {@code node_modules/} and an in-unit {@code .venv/}.
     */
    public static final Set<String> SKIPPED_SEGMENTS = Rederivable.CACHES;

    /**
     * The skipped roots a {@code sync --force-scripts} rebuilds. A generated
     * path into one of these that does not exist is a tool that will fail at
     * exec time; a path into {@code logs/} or {@code tmp/} that does not exist
     * is a home nobody has run yet. Only the former is a defect.
     */
    private static final Set<String> PROVISIONABLE_ROOTS =
            Set.of("cache", "venvs", "tools", "npm", "pm");

    /** Root-level files a clone does not copy. */
    public static final Set<String> SKIPPED_ROOT_FILES =
            Set.of("audit.log", "gateway.log", "gateway.pid");

    /**
     * Directories a clone does not copy because their contents are
     * <b>claims of stewardship over directories outside the home</b>, and a
     * copy of a home is not a copy of those relationships.
     *
     * <h2>Why {@code projects/} is here (issue #145 item 3)</h2>
     *
     * <p>A registration records {@code project_root} — a repository elsewhere
     * on this machine — and every operation that reads it is an operation that
     * writes to it. Measured with the CLI: a clone inherited a registration
     * verbatim, {@code project list} in the copy reported the source's project,
     * and {@code project resolve} then materialized a child home plus
     * {@code .claude}, {@code .codex} and {@code .gemini} inside a repository
     * the copy had never been pointed at. That is the issue's "wrote into five
     * unrelated repositories under ~/IdeaProjects".
     *
     * <p>It is also the sharper failure the worktree tier hits.
     * {@code ProjectChildHomeScaffolder.layoutFor} resolves everything from
     * {@code project.projectRoot()}, so a worktree home cloned from a project
     * home resolves its "child home" to the ORIGINAL checkout's
     * {@code .skill-manager} — the very home the worktree exists to stay out
     * of. It also explains a failure message that names the parent home while
     * the command ran in the worktree, which looks like cwd sensitivity and is
     * not.
     *
     * <h2>Why dropping, rather than re-anchoring or tokenizing</h2>
     *
     * <p>Both alternatives were considered and both are guesses.
     *
     * <p><b>Tokenizing {@code project_root}</b> as {@code $SKILL_MANAGER_HOME/..},
     * symmetric with the {@code manifest_path} beside it, is correct only for
     * the self-registration case where the project root IS the home's own root.
     * For the global home registering {@code ~/IdeaProjects/foo} it requires
     * walking up and back down, which is exactly what {@link HomeDescriptor}'s
     * storage mapper refuses to encode because it "would silently repoint an
     * unrelated path at whatever sits at that offset from the copy". The
     * asymmetry between the two fields is not an oversight — it is the one
     * field that names something the home does not own.
     *
     * <p><b>Re-anchoring it</b> to the copy's root assumes the copy should now
     * manage the copy's checkout. Often true, nowhere stated. A worktree home
     * that silently adopts stewardship of the worktree is a better guess than
     * the current one and is still a guess, and the cost of guessing wrong is
     * writes into somebody's working tree.
     *
     * <p>Dropping is the only option with no offset to get wrong. Nothing is
     * lost silently — the clone names every registration it dropped — and
     * re-registering is one command that re-reads the project's OWN manifest
     * rather than a snapshot taken by a different home.
     *
     * <h2>The other two record classes, and why they are filtered rather than
     * dropped wholesale</h2>
     *
     * <p>{@code installed/<unit>.projections.json} and {@code child-homes/}
     * carry claims of the same kind, and leaving them was measured: a scratch
     * home under {@code /private/tmp} listed 18 bindings naming four of the
     * operator's real checkouts, each with live {@code SYMLINK} projection
     * targets inside them, and {@code uninstall --dry-run} in that home refused
     * on child-home claims it had never made. A ledger row is not a stale
     * opinion about where to write — {@code unbind}/{@code uninstall} are
     * ledger-driven by construction, so it is a live instruction to delete
     * something in another checkout.
     *
     * <p>They are not in this set because, unlike a registration, most of what
     * they hold really is a record <em>about this home</em>: a binding whose
     * target is the source home's own root is the copy's own projection once
     * re-anchored, and dropping those would throw away the copy's knowledge of
     * its own footprint. So the rule is per-record and structural — see
     * {@link #insideNewHome} — and every dropped record is named in the report,
     * exactly as a dropped registration is.
     */
    public static final Set<String> DROPPED_STATE_DIRS = Set.of("projects");

    /** Path segments that mark a subtree as machine-provisioned, not authored. */
    private static final Set<String> PROVISIONED_SEGMENTS = Set.of(
            ".venv", "venv", "site-packages", "node_modules", "__pycache__",
            ".tox", ".gradle", ".mypy_cache", ".pytest_cache");

    /** Top-level directories that are entirely machine-provisioned. */
    private static final Set<String> PROVISIONED_ROOTS = Set.of(
            "venvs", "tools", "npm", "pm", "bin", "gateway-data");

    /** Top-level directories holding skill-manager's own records. */
    private static final Set<String> STATE_ROOTS = Set.of(
            "installed", "child-homes", "projects", "plugin-marketplace");

    /**
     * The four unit kinds. Only these hold content authored elsewhere and
     * installed verbatim, so only these are exempt from the leak check.
     * Everything outside this set is skill-manager's own output and is
     * held to the stricter standard — see {@link #classify}.
     */
    private static final Set<String> CONTENT_ROOTS = Set.of(
            "skills", "plugins", "docs", "harnesses");

    /**
     * Generated subtrees that sit inside a content root.
     * {@code harnesses/instances/} holds {@code .harness-instance.json}
     * files whose agent-home fields default to
     * {@code <home>/harnesses/instances/<id>/{claude,codex,gemini}} —
     * skill-manager's own state living under a content root.
     */
    private static final Set<String> STATE_SUBTREES = Set.of("harnesses/instances");

    /** Bytes past which a file is scanned in chunks rather than read whole. */
    private static final int WHOLE_FILE_LIMIT = 8 * 1024 * 1024;

    /**
     * Longest {@code #!} line the kernel will honour, including the
     * {@code #!}. Not a style rule — the kernel truncates silently past
     * this and you get {@code bad interpreter: .../bin/pyt}, a broken tool
     * with no diagnostic pointing at the clone that caused it. XNU's
     * {@code IMG_SHSIZE} is 512 (measured here: 500 bytes execs, 608 does
     * not); Linux's {@code BINPRM_BUF_SIZE} is 256 and was 128 for years.
     * A clone to a deep destination can cross either, so re-anchoring
     * checks the result rather than assuming it fits.
     */
    private static final int SHEBANG_LIMIT = shebangLimit();

    private static int shebangLimit() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin") || os.contains("bsd")) return 512;
        return 256;
    }

    private HomeCloner() {}

    /** Which fixup policy applies to a path, relative to the home root. */
    public enum Surface { STATE, PROVISIONED, CONTENT }

    /** One surviving absolute reference to the source home in the copy. */
    public record Leak(String path, String kind, String detail) {

        /**
         * The one leak kind that is a <em>mention</em> rather than a live path.
         * Named as a constant because two readers have to agree on it: the
         * check that only records it under {@code strict}, and every report
         * that must not fold it in with the kinds that are never acceptable.
         * A live symlink into another home and an append-only history file
         * that quotes a path differ by two orders of magnitude in count and
         * entirely in meaning; a reader that cannot tell them apart reports
         * the loud one and buries the real one. Issue #133.
         */
        public static final String CONTENT_REFERENCE = "CONTENT_REFERENCE";

        /**
         * A record in the copy that names one of the <em>source</em> home's
         * agent directories. Its own kind rather than a {@code FILE_CONTENT}
         * because the remedy differs: a {@code FILE_CONTENT} leak means a path
         * was missed by a re-anchoring pass, while this one means the copy is
         * holding a live instruction to touch another home's agent tree —
         * measured as an {@code uninstall} in a clone deleting three of the
         * source home's global skill links and reporting success. Issue #145.
         */
        public static final String SOURCE_AGENT_HOME = "SOURCE_AGENT_HOME";

        /**
         * A resolved path in the copy that lands in some other home's agent
         * directory — the agent-tree twin of {@code FOREIGN_HOME}.
         */
        public static final String FOREIGN_AGENT_HOME = "FOREIGN_AGENT_HOME";

        /**
         * A path that appears only inside a persisted error MESSAGE.
         *
         * <p>{@code installed/<unit>.json} carries an {@code errors[]} array,
         * and a message is free text: "child home path already exists:
         * /elsewhere/.skill-manager/bin/cli/computeq" holds an absolute path
         * into another home because it is <em>describing</em> one. Nothing
         * resolves through it, no code reads it as a path, and deleting that
         * home would break nothing.
         *
         * <p>The byte scan cannot see the difference, so it filed ten of these
         * under "paths that resolve into another Skill Manager home" — the
         * category reserved for a live {@code SYMLINK_TARGET} — and an operator
         * chasing an isolation problem was sent after a sentence. Same
         * over-broad-oracle class as #143's {@code ~/.claude} finding. Issue
         * #144.
         *
         * <p><b>Narrow on purpose.</b> Only occurrences ENTIRELY accounted for
         * by {@code errors[].message} are downgraded. A home path in
         * {@code origin}, or one extra occurrence anywhere else in the same
         * record, is still {@code FILE_CONTENT}: a live reference field that
         * happens to sit beside diagnostic text must not inherit its tolerance.
         */
        public static final String DIAGNOSTIC_TEXT = "DIAGNOSTIC_TEXT";

        /**
         * True when this is a mention — authored or diagnostic — not a path
         * that resolves.
         *
         * <p>Deliberately an allow-list of the two tolerable kinds rather than
         * a deny-list of the intolerable ones. {@code SOURCE_AGENT_HOME} and
         * {@code FOREIGN_AGENT_HOME} (#145) are live instructions to touch
         * another home's agent tree and are never tolerable; a deny-list would
         * have silently tolerated them the moment they were added.
         */
        public boolean tolerable() {
            return CONTENT_REFERENCE.equals(kind) || DIAGNOSTIC_TEXT.equals(kind);
        }

        @Override
        public String toString() {
            return kind + " " + path + (detail == null || detail.isBlank() ? "" : " (" + detail + ")");
        }
    }

    public record Report(
            Path source,
            Path dest,
            int directories,
            int files,
            int symlinks,
            long bytes,
            int linksRelativized,
            int stateReanchored,
            int provisionedRewritten,
            List<Leak> leaks,
            List<String> contentReferences,
            List<String> danglingLinks,
            List<String> danglingReferences,
            /**
             * Project registrations the copy deliberately did not inherit, by
             * name. Reported rather than merely omitted: dropping them is the
             * right default and it is still a change to what the copy knows,
             * so the operator has to be able to see it and re-register.
             * See {@link HomeCloner#DROPPED_STATE_DIRS}.
             */
            List<String> droppedRegistrations,

            /**
             * Binding records the copy deliberately did not inherit, as
             * {@code <unit>:<bindingId> → <target>}. Same reasoning as
             * {@link #droppedRegistrations}, applied to the half that carries
             * projection targets. See {@link HomeCloner#insideNewHome}.
             */
            List<String> droppedBindings,

            /**
             * {@code child-homes/} records the copy deliberately did not
             * inherit, by id. See {@link HomeCloner#insideNewHome}.
             */
            List<String> droppedChildHomes,

            /**
             * Trees the copy DECLARED instead of carrying, home-relative — the
             * in-unit virtualenvs a lazy home defers. Empty for an eager clone.
             * See {@link HomeCloner#deferrableVirtualenv}.
             */
            List<String> deferredTrees,

            /**
             * Entry points under {@code bin/} the copy replaced with a cold
             * shim, by name. Every one of these was a path that resolved to
             * nothing before this ticket and said so in the kernel's words.
             * See {@link ColdArtifactShim}.
             */
            List<String> coldShims,

            /** Whether this copy declares its artifacts and builds them on demand. */
            boolean lazyArtifacts
    ) {
        /**
         * The shape every caller before ARTI-07 constructs. Kept so that adding
         * three components renamed nothing: an eager clone defers no tree and
         * writes no cold shim, so the defaults are facts rather than
         * placeholders.
         */
        public Report(Path source, Path dest, int directories, int files, int symlinks,
                      long bytes, int linksRelativized, int stateReanchored,
                      int provisionedRewritten, List<Leak> leaks,
                      List<String> contentReferences, List<String> danglingLinks,
                      List<String> danglingReferences, List<String> droppedRegistrations,
                      List<String> droppedBindings, List<String> droppedChildHomes) {
            this(source, dest, directories, files, symlinks, bytes, linksRelativized,
                    stateReanchored, provisionedRewritten, leaks, contentReferences,
                    danglingLinks, danglingReferences, droppedRegistrations, droppedBindings,
                    droppedChildHomes, List.of(), List.of(), false);
        }

        public Report {
            leaks = leaks == null ? List.of() : List.copyOf(leaks);
            contentReferences = contentReferences == null ? List.of() : List.copyOf(contentReferences);
            danglingLinks = danglingLinks == null ? List.of() : List.copyOf(danglingLinks);
            danglingReferences = danglingReferences == null ? List.of() : List.copyOf(danglingReferences);
            droppedRegistrations = droppedRegistrations == null
                    ? List.of() : List.copyOf(droppedRegistrations);
            droppedBindings = droppedBindings == null ? List.of() : List.copyOf(droppedBindings);
            droppedChildHomes = droppedChildHomes == null
                    ? List.of() : List.copyOf(droppedChildHomes);
            deferredTrees = deferredTrees == null ? List.of() : List.copyOf(deferredTrees);
            coldShims = coldShims == null ? List.of() : List.copyOf(coldShims);
        }

        /** True when nothing in the copy still points at the source home. */
        public boolean clean() { return leaks.isEmpty(); }
    }

    public static Report cloneHome(Path source, Path dest) throws IOException {
        return cloneHome(source, dest, false);
    }

    /**
     * Copy {@code source} to {@code dest} and verify the result.
     *
     * @param strict also fail on absolute references inside authored unit
     *               content, which cannot be rewritten without corrupting
     *               append-only records
     */
    public static Report cloneHome(Path source, Path dest, boolean strict) throws IOException {
        // The tier decides it, and the tier test is HomePolicy's, which is
        // skt's `classify_tier` first comparison and not a second notion of it.
        return cloneHome(source, dest, strict,
                HomePolicy.lazyArtifactsDefault(new SkillStore(dest)));
    }

    /**
     * Copy {@code source} to {@code dest}, declaring rather than building the
     * artifacts a lazy copy defers.
     *
     * <h2>What "declares, does not build" means here, precisely</h2>
     *
     * <p>A clone has ALWAYS skipped {@link #SKIPPED_DIRS} and has never said so
     * in a form anything could act on: the copy simply had shims that resolved
     * to nothing, and the first thing to notice was the kernel. Three things
     * change, and only these three:
     *
     * <ol>
     *   <li>the copy gets an {@code artifacts.lock.toml} that <b>names</b> the
     *       artifacts under the skipped roots, so they list as
     *       {@link Artifact.Origin#LEDGER} /
     *       {@code declared-only} instead of vanishing;</li>
     *   <li>every entry point whose backing tree is absent becomes a
     *       {@link ColdArtifactShim} that names the
     *       artifact and the one command that builds it;</li>
     *   <li>when {@code lazyArtifacts}, the copy additionally defers the
     *       <b>virtualenvs inside units</b> — see {@link #deferrableVirtualenv},
     *       which is where the bytes actually are.</li>
     * </ol>
     *
     * <p>(1) and (2) are done for an EAGER copy too. They describe what the
     * clone already did; withholding the description from a home that did not
     * opt into laziness would leave the defect ARTI-01 measured exactly where
     * it was, in the tier that is not even the one being changed.
     *
     * @param lazyArtifacts defer what the copy can rebuild on demand. Default
     *                      is {@link HomePolicy#lazyArtifactsDefault}; the
     *                      resulting decision is written into the copy's
     *                      {@code home.policy.toml} so the home records the
     *                      state it is in rather than re-deriving it later
     */
    public static Report cloneHome(Path source, Path dest, boolean strict, boolean lazyArtifacts)
            throws IOException {
        Path src = source.toAbsolutePath().normalize();
        Path dst = dest.toAbsolutePath().normalize();
        if (!Files.isDirectory(src)) {
            throw new IOException("source home is not a directory: " + src);
        }
        if (src.equals(dst)) {
            throw new IOException("source and destination are the same home: " + src);
        }
        if (dst.startsWith(src) || src.startsWith(dst)) {
            throw new IOException("source and destination homes must not nest: "
                    + src + " vs " + dst);
        }
        if (Files.exists(dst) && !isEmptyDir(dst)) {
            throw new IOException("destination already exists and is not empty: " + dst);
        }

        // Everything from here writes into dst. A partial clone is worse
        // than none: it leaves a populated directory that the next attempt
        // refuses as "not empty", so the operator has to work out by hand
        // what was ours to delete. Roll back to the state we found.
        boolean preexisting = Files.exists(dst);
        Counters counters = new Counters();
        try {
            Files.createDirectories(dst);
            return build(src, dst, strict, counters, lazyArtifacts);
        } catch (IOException | RuntimeException e) {
            discardPartialClone(dst, preexisting);
            throw e;
        }
    }

    private static Report build(Path src, Path dst, boolean strict, Counters counters,
                                boolean lazyArtifacts) throws IOException {
        // Enumerated from the SOURCE before the copy, because the copy is what
        // omits them: after copyTree there is nothing left in the destination
        // to enumerate, and a drop nobody can name is a drop nobody can undo.
        List<String> droppedRegistrations = registrationsIn(src);
        List<String> deferredTrees = new ArrayList<>();
        copyTree(src, dst, counters, lazyArtifacts, deferredTrees);

        List<String> droppedBindings = new ArrayList<>();
        List<String> droppedChildHomes = new ArrayList<>();
        int stateReanchored = reanchorState(src, dst, droppedBindings, droppedChildHomes);
        List<Leak> overflows = new ArrayList<>();
        List<String> danglingReferences = new ArrayList<>();
        int provisionedRewritten = reanchorProvisioned(src, dst, overflows, danglingReferences);
        HomeLinks.relativizeShims(new SkillStore(dst));

        // Declare, then refuse in the copy's own words, then baseline. The
        // order is load-bearing in both directions:
        //
        //  * BEFORE rebaselineDrift, so the copy's baseline describes the bytes
        //    the copy actually holds. A baseline taken before the cold shims
        //    were written would report every one of them as this home's own
        //    drift on the first launch — the exact failure rebaselineDrift's
        //    javadoc exists to keep closed, reintroduced one step later.
        //  * BEFORE verifyRoots, because a cold shim is the ANSWER to an
        //    unresolved reference. Verifying first would report the state that
        //    the next line removes.
        HomePolicy.writeLazyArtifacts(new SkillStore(dst), lazyArtifacts);
        // HIS-10. BEFORE verifyRoots, and for the same reason the two lines
        // below it are: the copy's own clone verdict must be produced by the
        // evidence every LATER reader will use, or `home clone` and
        // `home verify` are two readers of two different homes again. Before
        // rebaselineDrift too, so the baseline describes the bytes the home
        // holds rather than reporting this record as the home's own drift.
        HomeProvenance.recordDescent(src, dst);
        declareArtifacts(src, dst, deferredTrees);
        List<String> coldShims = writeColdShims(dst);
        rebaselineDrift(new SkillStore(dst));

        Verification verification = verifyRoots(src, dst, strict);
        // A shebang that would not fit is a failure the walk below cannot
        // detect — the source path was substituted out, so nothing points
        // at the source any more; the file is simply broken.
        List<Leak> leaks = new ArrayList<>(verification.leaks());
        leaks.addAll(overflows);
        leaks.sort(java.util.Comparator.comparing(Leak::path));
        // The rewrite only sees files it rewrote. The verification walk sees
        // every provisioned file, including shims that already named the
        // destination and so were never touched — the union is what the copy
        // actually holds, and it is what `home verify` will refuse on later.
        for (String unresolved : verification.unresolvedReferences()) {
            if (!danglingReferences.contains(unresolved)) danglingReferences.add(unresolved);
        }
        danglingReferences.sort(String::compareTo);
        return new Report(src, dst,
                counters.directories, counters.files, counters.symlinks, counters.bytes,
                counters.linksRelativized, stateReanchored, provisionedRewritten,
                leaks, verification.contentReferences(), verification.danglingLinks(),
                danglingReferences, droppedRegistrations,
                sorted(droppedBindings), sorted(droppedChildHomes),
                sorted(deferredTrees), sorted(coldShims), lazyArtifacts);
    }

    /** Directory iteration order is not a contract; the report's order is. */
    private static List<String> sorted(List<String> values) {
        List<String> out = new ArrayList<>(values);
        out.sort(String::compareTo);
        return List.copyOf(out);
    }

    /** Registration names in {@code home}, for the report. */
    private static List<String> registrationsIn(Path home) {
        Path dir = home.resolve("projects");
        if (!Files.isDirectory(dir)) return List.of();
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path project : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(project.resolve("registration.toml"))) {
                    names.add(project.getFileName().toString());
                }
            }
        } catch (IOException e) {
            Log.warn("clone: could not list %s: %s", dir, e.getMessage());
        }
        names.sort(String::compareTo);
        return names;
    }

    // --------------------------------------------------- the drift baseline

    /**
     * Give the copy its OWN drift baseline, and drop the source's drift record.
     *
     * <h2>A clone is not drifted, and it was reporting that it was</h2>
     *
     * <p>Measured on this repository. Every home {@code new-change.sh}
     * provisions is a clone, {@code bootstrap-home.sh} baselines it with
     * {@code home drift --record}, and that command exits telling the operator
     * to launch — at which point {@code <wt>/.skill-manager/bin/launch/claude}
     * refused with exit 8 and "6 unit(s) changed", on two independent virgin
     * worktrees against which nothing else had ever run. Every one of the six
     * was a skill carrying a {@code .venv}, and every reported path was a
     * REMOVAL of a file the copy demonstrably still had on disk (1739 entries
     * under {@code skills/acp-cdc-ai-python/scripts/sources/.venv} in both
     * homes).
     *
     * <p>Nothing had moved. The clone copied {@code home.digest.json} verbatim,
     * so the "baseline" the copy was measured against was a statement about the
     * SOURCE at some earlier moment, recorded by some earlier build. In this
     * case it predated {@code walkPlain} learning to skip re-derivable trees
     * (c2d535c), so it enumerated {@code .venv} entries that the current
     * definition of unit content excludes — and the diff between two different
     * definitions of "content" came out as thousands of deletions.
     *
     * <p>The specific cause was that schema drift, but fixing only that would
     * leave the general one standing. <b>An inherited baseline is evidence about
     * a pair of moments in another home's history, and the copy is not part of
     * that history.</b> A source that installs a unit and does not run
     * {@code home drift --record} before being cloned hands every future clone
     * its own unrecorded change to answer for, with the same symptom and no
     * schema change anywhere. This is precisely the argument
     * {@code ChildHomeMaterializer.recordCloneBaselines} already makes for the
     * per-unit reconcile records — "a copied home's inherited records describe a
     * pair it is not part of" — and the home digest was simply left out of it.
     *
     * <h2>Why this does not weaken the gate</h2>
     *
     * <p>The gate exists so that an agent cannot keep acting on a skill that
     * moved underneath it. Nothing has moved underneath anybody in a home that
     * did not exist a moment ago: the copy's content is exactly what it was
     * provisioned with, and no agent has read anything else. The property is
     * preserved exactly where it means something — every later sync, pull,
     * install or {@code home sync} into this copy still diffs against this
     * baseline and still records a pending change, and the tests that assert
     * that are untouched. What is removed is the ability to report, as this
     * home's drift, a change that happened somewhere else before this home
     * existed.
     *
     * <p>The inherited {@code home.drift.json} goes for the same reason, and it
     * is the sharper half: an UNACKNOWLEDGED record in the source would
     * otherwise be inherited by the copy and refuse its first launch over a
     * change that predates it. Deleted rather than acknowledged — an
     * acknowledgement is a receipt saying somebody read this home's change, and
     * writing one for a change this home never had would be a false receipt.
     *
     * <p>Order matters. The record goes first, the baseline second: a crash in
     * between leaves the copy with no gate and a stale baseline, so the next
     * {@code home drift --record} re-derives the difference and gates — the
     * conservative direction. That is the same reasoning, and the same
     * direction, as {@link DriftGate#recordSince}.
     */
    private static void rebaselineDrift(SkillStore copy) throws IOException {
        Files.deleteIfExists(DriftGate.file(copy));
        HomeDigest.compute(copy).write(copy);
    }

    // ------------------------------------------------------------- copy

    private static final class Counters {
        int directories, files, symlinks, linksRelativized;
        long bytes;
    }

    private static void copyTree(Path src, Path dst, Counters counters,
                                 boolean lazyArtifacts, List<String> deferredTrees)
            throws IOException {
        Files.walkFileTree(src, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                String rel = rel(src, dir);
                if (!rel.isEmpty() && isSkipped(rel)) return FileVisitResult.SKIP_SUBTREE;
                if (lazyArtifacts && !rel.isEmpty() && deferrableVirtualenv(dir, rel)) {
                    // Left ABSENT, not created empty. `uv` decides what to do
                    // by looking for `pyvenv.cfg`, so an empty directory with
                    // no marker is a shape it has to recover from, where a
                    // missing one is the shape it creates from scratch every
                    // day. Absent is also the honest probe result: the artifact
                    // is declared and not materialized, and a reader that finds
                    // an empty directory cannot tell that from a broken build.
                    deferredTrees.add(rel.replace(java.io.File.separatorChar, '/'));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // walkFileTree reports a symlinked directory here only when
                // following links, which we do not; a link to a directory
                // arrives at visitFile instead.
                Path target = dst.resolve(rel);
                Files.createDirectories(target);
                if (!rel.isEmpty()) counters.directories++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                String rel = rel(src, file);
                if (isSkipped(rel)) return FileVisitResult.CONTINUE;
                Path target = dst.resolve(rel);
                Files.createDirectories(target.getParent());
                if (Files.isSymbolicLink(file)) {
                    copyLink(src, dst, file, target, counters);
                } else if (attrs.isRegularFile()) {
                    // COPY_ATTRIBUTES is what makes `home clone` cheap enough
                    // to be the normal way a project or worktree tier comes
                    // into being: on APFS it takes the clonefile(2) path and
                    // the copy shares the source's blocks. Measured on this
                    // host — a real clone of a 189 MB home consumes 7.22 MB
                    // (3.8%); `cp -Rc` of the 906 MB operator home consumes
                    // 55 MB against 926 MB for `cp -R`. It is not decoration
                    // beside REPLACE_EXISTING. See Fs#copyRecursive's javadoc
                    // and the home.clone.costs.far.less.than.a.copy node, and
                    // do not measure it with du — du attributes shared blocks
                    // to both files and reported 197.1 MB for 7.14 MB real.
                    Files.copy(file, target,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    counters.files++;
                    counters.bytes += attrs.size();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                Log.warn("clone: could not read %s (%s) — skipped", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ------------------------------------------------- declared, not built

    /**
     * Whether {@code dir} is a virtualenv inside a unit that this copy may
     * declare instead of carrying.
     *
     * <h2>The measurement that put this here</h2>
     *
     * <p>ARTI-01 decomposed a 989.5 MB ticket home and named two units and one
     * runtime as 86% of it. Decomposing those, on the operator's project home:
     * {@code skills/deploy-helm} is 400 MB of which <b>361 MB is
     * {@code .venv}</b>; {@code skills/hyper-experiments-finance} carries
     * another 51 MB and {@code skills/acp-cdc-ai-python} 26 MB, the same shape
     * each time. That is 438 MB of a 989.5 MB home in virtualenvs that no
     * record declares, that {@code uv} rebuilds from a lockfile sitting beside
     * them, and that the clone currently copies AND byte-scans AND re-anchors,
     * because a venv console script's shebang is an absolute interpreter path.
     * The other 274 MB of {@code spec-double-compiler} is {@code specs/.history}
     * and {@code specs/results} — append-only authored records, which is a
     * different kind of thing and is not touched.
     *
     * <h2>Two structural tests, and no naming convention</h2>
     *
     * <p>The membership rule is deliberately NOT
     * {@link Rederivable#OUTPUT_ROOTS}, which that class warns about in as many
     * words: those names — {@code build}, {@code target}, {@code venv} — "are
     * ordinary words used by convention", safe for a reconcile that compares
     * two trees and not safe for a byte-for-byte copy that would simply drop
     * whatever it matched. A unit with an authored {@code build/} directory
     * exists; a unit with an authored {@code pyvenv.cfg} does not.
     *
     * <p>So the test is:
     *
     * <ol>
     *   <li><b>inside a unit</b> — the top segment is one of
     *       {@link #CONTENT_ROOTS}. A virtualenv anywhere else is either
     *       already under a {@link #SKIPPED_DIRS} root or is skill-manager's
     *       own state, and neither is this rule's business;</li>
     *   <li><b>it contains {@code pyvenv.cfg}</b> — the marker every virtualenv
     *       carries, that no authored directory carries, and that names the
     *       interpreter the shebangs point at. Not the directory's NAME.</li>
     * </ol>
     *
     * <p>{@code node_modules} is deliberately absent. {@link Rederivable} records
     * why a clone must carry it — {@code node_modules/<pkg>/build/Release/*.node}
     * is a prebuilt native binary no command rebuilds — and that argument is
     * unchanged. It also does not arise: the operator's home has none inside a
     * unit, so including it would trade a real risk for no measured bytes.
     *
     * <h2>Why deferring this does not move the drift baseline</h2>
     *
     * <p>{@code ChildHomeMaterializer.walkPlain} already excludes
     * {@link Rederivable#isDerived} paths from the digest, which is what
     * {@link #rebaselineDrift}'s javadoc means by "the current definition of
     * unit content excludes them". A venv is therefore not in the digest on
     * either side, so a copy that omits one is byte-identical to a copy that
     * carries one as far as the gate is concerned — and building it later moves
     * nothing either. That is asserted, not assumed.
     */
    static boolean deferrableVirtualenv(Path dir, String rel) {
        String normalized = rel.replace(java.io.File.separatorChar, '/');
        int slash = normalized.indexOf('/');
        if (slash < 0) return false;
        if (!CONTENT_ROOTS.contains(normalized.substring(0, slash))) return false;
        return Files.isRegularFile(dir.resolve("pyvenv.cfg"));
    }

    /**
     * Give the copy a ledger that NAMES what it does not hold.
     *
     * <p>Three sources, merged in one order that encodes one rule — the copy
     * wins on facts, the source is consulted only for existence:
     *
     * <ol>
     *   <li>the SOURCE's artifacts whose outputs land under a root this clone
     *       skips. These are the only facts the copy cannot re-derive for
     *       itself, and restricting the source's contribution to them is what
     *       keeps a dropped registration or a dropped binding from being
     *       resurrected through the ledger — the copy deliberately did not
     *       inherit those, and a row declaring one would be a claim over
     *       another checkout wearing a new file name;</li>
     *   <li>the trees this copy deferred, which no home has ever recorded;</li>
     *   <li>everything the COPY can see for itself, last, so a live fact
     *       overwrites the source's snapshot of the same id.</li>
     * </ol>
     *
     * <h2>The second route into (1), which defeated it</h2>
     *
     * <p>{@code artifacts.lock.toml} is an ordinary file under no skipped root,
     * so the copy carries the SOURCE's ledger verbatim — and
     * {@link ArtifactIndex} is "the home overlaid with what the ledger says it
     * once had". The copy's own index is therefore not "what the copy can see
     * for itself": it is the copy's live facts PLUS every declaration the
     * source ever wrote, arriving through step (3) with the restriction in step
     * (1) bypassed entirely. Measured on the seeded home: the doc-import
     * binding that projects into another checkout is dropped by
     * {@link #remap}, and its projection came back as
     * {@code projection:owner:consumer:page:bind#MANAGED_COPY/target/docs/agents/page.md}
     * in the copy's ledger — the claim over another checkout wearing a new file
     * name, exactly as described above and not actually prevented. It did not
     * show up in the first measurements only because a source home without a
     * ledger has no second route.
     *
     * <p>So a row that reaches the copy's index ONLY through the inherited file
     * ({@link Artifact.Origin#LEDGER}) has to earn its place, and the test is
     * whether it names anything in this home at all. A dropped binding's
     * projections and doc imports land outside the home by definition — that is
     * why the binding was dropped — so they carry no home-scoped output and
     * declare nothing the copy holds or could build. Everything legitimately
     * inherited does carry one: a provisioned tree under {@code cache/} or
     * {@code venvs/}, and a virtualenv a previous clone deferred under
     * {@code skills/}, which is why the test is not "under a skipped root"
     * here — a clone of a clone must keep declaring what its parent deferred.
     *
     * <p>Never fatal. A ledger is an optimisation and a memory
     * ({@link ArtifactLedger}); a home that gets one
     * lists better, and a home that does not still lists. Failing a clone over
     * it would trade a working home for a tidier index.
     */
    private static void declareArtifacts(Path src, Path dst, List<String> deferredTrees) {
        try {
            List<Artifact> rows = new ArrayList<>();
            for (Artifact artifact
                    : ArtifactIndex.of(new SkillStore(src)).artifacts()) {
                if (underSkippedRoot(artifact)) rows.add(artifact);
            }
            for (String tree : deferredTrees) rows.add(deferredTreeArtifact(dst, tree));
            for (Artifact artifact : ArtifactIndex.of(new SkillStore(dst)).artifacts()) {
                if (inheritedClaimOverElsewhere(artifact)) continue;
                rows.add(artifact);
            }
            ArtifactLedger.of(rows).save(new SkillStore(dst));
        } catch (IOException | RuntimeException e) {
            Log.warn("clone: could not record the artifact ledger in %s (%s) — the copy still "
                    + "lists its artifacts, it just cannot name the ones under the roots a "
                    + "clone skips", dst, e.getMessage());
        }
    }

    /**
     * Whether {@code artifact} reached the copy's index only through the
     * source's inherited {@code artifacts.lock.toml} and names nothing in this
     * home.
     *
     * <p>{@link Artifact.Origin#LEDGER} is precisely "declared, and the home
     * cannot see it now"; a row that is also LEDGER-scoped to nothing inside
     * the home is a declaration about somewhere else. See
     * {@link #declareArtifacts}.
     */
    private static boolean inheritedClaimOverElsewhere(Artifact artifact) {
        if (artifact.origin() != Artifact.Origin.LEDGER) return false;
        for (Artifact.Output output : artifact.outputs()) {
            if (output.scope() == Artifact.Scope.HOME) return false;
        }
        return true;
    }

    /** Whether every home-scoped output of {@code artifact} is under a skipped root. */
    private static boolean underSkippedRoot(Artifact artifact) {
        boolean any = false;
        for (Artifact.Output output : artifact.outputs()) {
            if (output.scope() != Artifact.Scope.HOME) continue;
            String path = output.path().replace(java.io.File.separatorChar, '/');
            int slash = path.indexOf('/');
            String top = slash < 0 ? path : path.substring(0, slash);
            if (!SKIPPED_DIRS.contains(top)) return false;
            any = true;
        }
        return any;
    }

    /** One deferred in-unit virtualenv, as the artifact the copy declares. */
    private static Artifact deferredTreeArtifact(Path dst, String rel) {
        String[] segments = rel.split("/");
        // skills/<unit>/... — the unit is the second segment, and a tree that
        // is not under one has no owner rather than a guessed one.
        String owner = segments.length > 1 ? segments[1] : null;
        List<String> inputs = owner == null ? List.of()
                : List.of(ArtifactIds.unitInput(owner));
        return new Artifact(
                ArtifactIds.of(
                        ArtifactKind.PROVISIONED_TREE, rel),
                ArtifactKind.PROVISIONED_TREE,
                owner, inputs,
                List.of(Artifact.Output.inHome(rel,
                        Artifact.Presence.MISSING)),
                null, Map.of(), Map.of(),
                Artifact.Agreement.UNRECORDED,
                Artifact.Origin.LEDGER);
    }

    /**
     * Replace every entry point in the copy whose backing tree is absent with
     * a {@link ColdArtifactShim}.
     *
     * <p>Both shapes, because a home has both and only one of them was ever
     * visible: a SYMLINK into a skipped root, which {@link #copyLink}
     * faithfully recreates pointing at nothing, and a GENERATED WRAPPER whose
     * {@code exec} target {@link #reanchorProvisioned} correctly re-anchors
     * onto a path the copy does not have. The first is
     * {@code Verification.danglingLinks}, the second is
     * {@code unresolvedReferences}, and to whoever typed the command they are
     * the same event.
     *
     * <p>The id in the message comes from the ledger written a moment ago,
     * matched by OUTPUT PATH — the same rule {@code ArtifactBuild.buildableFor}
     * resolves a remedy by, and never by parsing a shim's name back into a lock
     * key, because {@code bin/cli/tofu} comes from {@code brew:opentofu}. An
     * entry point no artifact claims is left exactly as it was: a refusal that
     * named a command nobody can run would be worse than the kernel's, which at
     * least does not mislead.
     *
     * @return the names replaced
     */
    private static List<String> writeColdShims(Path dst) {
        List<String> replaced = new ArrayList<>();
        try {
            Map<String, String> idByOutput = new LinkedHashMap<>();
            for (ArtifactLedger.Row row
                    : ArtifactLedger.load(new SkillStore(dst)).rows()) {
                for (String output : row.outputs()) idByOutput.putIfAbsent(output, row.id());
            }
            for (String dir : SHIM_DIRS) {
                Path shimDir = dst.resolve(dir);
                if (!Files.isDirectory(shimDir)) continue;
                try (var entries = Files.list(shimDir)) {
                    for (Path entry : (Iterable<Path>) entries::iterator) {
                        String rel = dir + entry.getFileName();
                        String why = coldReason(entry, dst);
                        if (why == null) continue;
                        String id = idByOutput.get(rel);
                        if (id == null) continue;
                        ColdArtifactShim.write(entry, id, why);
                        replaced.add(entry.getFileName().toString());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("clone: could not write the cold-artifact entry points in %s (%s) — an "
                    + "entry point whose tree is missing will fail in the kernel's words "
                    + "instead of naming `skill-manager build`", dst, e.getMessage());
        }
        return replaced;
    }

    /**
     * Why {@code entry} cannot run in this copy, or null when it can.
     *
     * <p>Deliberately NOT "is the artifact materialized": this asks the
     * filesystem the same question the kernel is about to ask, so the answer
     * cannot disagree with what the operator sees.
     */
    private static String coldReason(Path entry, Path dst) throws IOException {
        if (ColdArtifactShim.isCold(entry)) return null;
        if (Files.isSymbolicLink(entry)) {
            if (Files.exists(entry)) return null;
            return "it links to " + insideHomeText(Files.readSymbolicLink(entry).toString(), dst)
                    + ", which this home does not have";
        }
        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) return null;
        List<String> missing = missingReferencesIn(entry, dst);
        if (missing.isEmpty()) return null;
        return "it runs out of " + insideHomeText(missing.get(0), dst)
                + ", which this home does not have";
    }

    /**
     * {@code value} with this home's own root replaced by {@code $SKILL_MANAGER_HOME}.
     *
     * <p>The reason a cold shim gives has to name the missing tree — an agent
     * that is told only "not built" cannot tell which of eight tools it just
     * ran. It also has to name it WITHOUT writing an absolute path into the
     * file, because the file is generated content that a further clone would
     * carry verbatim, and an absolute path in generated content is precisely
     * the {@code Surface.PROVISIONED} leak class this class exists to remove.
     * Both at once means the token, which is the same encoding
     * {@link HomePaths} uses for every other stored path.
     */
    private static String insideHomeText(String value, Path dst) {
        String out = value;
        // Every spelling of the root, longest first: replacing /var/x before
        // /private/var/x would leave "/private$SKILL_MANAGER_HOME".
        List<String> spellings = new ArrayList<>(rootSpellings(dst));
        spellings.sort(java.util.Comparator.comparingInt(String::length).reversed());
        for (String root : spellings) out = out.replace(root, "$SKILL_MANAGER_HOME");
        return out;
    }

    /**
     * Recreate a symlink in the copy. A target that pointed into the source
     * home is rewritten relative, which resolves correctly under any root;
     * anything else is reproduced byte for byte, because a link out of the
     * home names a real thing elsewhere on this machine.
     */
    private static void copyLink(Path srcRoot, Path dstRoot, Path link, Path target,
                                 Counters counters) throws IOException {
        Path stored = Files.readSymbolicLink(link);
        HomePaths srcPaths = HomePaths.of(srcRoot);
        Path toWrite = stored;
        if (stored.isAbsolute() && srcPaths.isInsideHome(stored)) {
            // Where that same target lands in the copy, expressed relative to
            // the link's own directory — root-independent from here on.
            Path inCopy = dstRoot.resolve(
                    srcPaths.homeRoot().relativize(stored.toAbsolutePath().normalize()));
            toWrite = target.getParent().relativize(inCopy);
            counters.linksRelativized++;
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.delete(target);
        try {
            Files.createSymbolicLink(target, toWrite);
            counters.symlinks++;
        } catch (UnsupportedOperationException | IOException e) {
            Log.warn("clone: could not recreate symlink %s -> %s: %s", target, toWrite, e.getMessage());
        }
    }

    // ------------------------------------------------- state re-anchoring

    /**
     * Rewrite skill-manager's own records in the copy so their
     * self-references name the copy, in {@code $SKILL_MANAGER_HOME} form.
     * Runs through the production serde rather than editing bytes, so a
     * record it cannot parse is left alone instead of half-rewritten.
     */
    private static int reanchorState(Path srcRoot, Path dstRoot,
                                     List<String> droppedBindings,
                                     List<String> droppedChildHomes) throws IOException {
        int rewritten = 0;
        rewritten += reanchorLedgers(srcRoot, dstRoot, droppedBindings);
        rewritten += reanchorChildHomes(srcRoot, dstRoot, droppedChildHomes);
        rewritten += reanchorRemainingState(srcRoot, dstRoot);
        return rewritten;
    }

    /**
     * Whether {@code path} is something the home rooted at {@code dstRoot} owns
     * — its store, its three agent directories, anything else beside them.
     *
     * <p>The predicate every inherited claim is filtered by, and it is
     * deliberately about the <b>destination</b> rather than about a literal
     * path prefix like {@code /Users}. A record is foreign when it names
     * something outside the home being created, wherever that home happens to
     * be; under a test fixture both homes live in {@code /private/tmp} and a
     * prefix test would call every claim domestic.
     *
     * <p>It runs <em>after</em> {@link #remapPath}, so a record that named part
     * of the source home has already become the corresponding part of the copy
     * and is inside by construction. What is left outside is what the source
     * home held about somewhere else on the machine.
     */
    private static boolean insideNewHome(Path path, Path dstRoot) {
        if (path == null) return true;
        Path abs = path.toAbsolutePath().normalize();
        if (HomePaths.of(dstRoot).isInsideHome(abs)) return true;
        Path root = AgentHomes.homeRootFor(dstRoot);
        return abs.equals(root) || abs.startsWith(root);
    }

    // There was a fourth pass here, rewriting projects/<name>/registration.toml's
    // manifest_path. It is gone because projects/ is no longer copied at all
    // ({@link #DROPPED_STATE_DIRS}), and deleting it rather than leaving it
    // unreachable is deliberate: a projects/ directory that arrives in a copy by
    // some other route — an rsync, a restored backup, an older skill-manager's
    // clone — now FAILS verification as an un-re-anchored STATE file, instead of
    // being quietly rewritten into something that looks repaired and still names
    // another machine's checkout in project_root. That is this class's
    // default-deny rule applied to the one record class that had an exemption
    // from it. Issue #145 item 3.

    /**
     * Catch-all for state files the structured passes above do not model.
     *
     * <p>The structured passes exist to produce the canonical
     * {@code $SKILL_MANAGER_HOME} token form for the records that carry the
     * most self-references. They cannot be exhaustive: {@code project-lock.toml}
     * writes {@code target_root} / {@code env_root} raw,
     * {@code installed/<unit>.json} carries an {@code origin} that is
     * usually a git URL but may be a local path, {@code Projection.backupOf}
     * is a {@code String} and so bypasses the {@code Path} serializer, and
     * {@code harnesses/instances/<id>/.harness-instance.json} holds agent
     * homes that default underneath this very directory. None of those were
     * observed holding a home path in the real home — but each is reachable,
     * and without this pass a single occurrence would be an unrepairable
     * hard failure of the clone.
     *
     * <p>Substitution is safe on this surface in a way it is not on unit
     * content: every file here is generated by skill-manager, so there is
     * no append-only record to corrupt. The result is re-anchored to the
     * destination's absolute path rather than the token — correct, just not
     * relocatable a second time without another clone.
     */
    private static int reanchorRemainingState(Path srcRoot, Path dstRoot) throws IOException {
        byte[] needle = srcRoot.toString().getBytes(StandardCharsets.UTF_8);
        byte[] replacement = dstRoot.toString().getBytes(StandardCharsets.UTF_8);
        int[] rewritten = {0};
        Files.walkFileTree(dstRoot, new SimpleWalker((file, rel) -> {
            if (classify(rel) != Surface.STATE) return;
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) return;
            byte[] content;
            try {
                if (Files.size(file) > WHOLE_FILE_LIMIT) return;
                content = Files.readAllBytes(file);
            } catch (IOException e) {
                return;
            }
            if (indexOf(content, needle, 0) < 0) return;
            if (looksBinary(content)) return;
            try {
                Files.write(file, replaceAll(content, needle, replacement));
                rewritten[0]++;
                // Addressed to whoever maintains the WRITER of that file
                // ("consider encoding it at write time"), not to the caller of
                // this clone — and emitted once per such file, so it scaled
                // with the home while telling the operator nothing they could
                // act on. The count still reaches the console, in the report's
                // `re-anchored: … N records` line; the filenames are in the
                // run log, and under --verbose they are back on the console.
                Log.detail("! clone: re-anchored %s by substitution — it holds a home path no "
                        + "structured writer models; consider encoding it at write time", rel);
            } catch (IOException e) {
                Log.warn("clone: could not re-anchor %s: %s", file, e.getMessage());
            }
        }));
        return rewritten[0];
    }

    private static int reanchorLedgers(Path srcRoot, Path dstRoot, List<String> dropped) {
        Path dir = dstRoot.resolve("installed");
        if (!Files.isDirectory(dir)) return 0;
        int rewritten = 0;
        try (var stream = Files.newDirectoryStream(dir, "*.projections.json")) {
            for (Path f : stream) {
                try {
                    ProjectionLedger ledger = BindingJson.mapperFor(srcRoot)
                            .readValue(f.toFile(), ProjectionLedger.class);
                    ProjectionLedger remapped = remap(ledger, srcRoot, dstRoot, dropped);
                    BindingJson.mapperFor(dstRoot).writerWithDefaultPrettyPrinter()
                            .writeValue(f.toFile(), remapped);
                    rewritten++;
                } catch (IOException e) {
                    Log.warn("clone: could not re-anchor %s: %s", f, e.getMessage());
                }
            }
        } catch (IOException e) {
            Log.warn("clone: could not list %s: %s", dir, e.getMessage());
        }
        return rewritten;
    }

    /**
     * Re-anchor a unit's bindings onto the copy, and drop the ones that are
     * not the copy's to hold.
     *
     * <p>A binding survives when everything it names — its {@code targetRoot}
     * and every projection {@code destPath} — is inside the new home after
     * re-anchoring. A binding that still names a checkout elsewhere on the
     * machine is dropped whole rather than half-kept: a binding minus the
     * projections that reach outside would claim a footprint it can no longer
     * undo, which is worse than not claiming it.
     */
    private static ProjectionLedger remap(ProjectionLedger ledger, Path srcRoot, Path dstRoot,
                                          List<String> dropped) {
        List<Binding> bindings = new ArrayList<>(ledger.bindings().size());
        for (Binding b : ledger.bindings()) {
            List<Projection> projections = new ArrayList<>(b.projections().size());
            boolean foreign = false;
            Path targetRoot = remapPath(b.targetRoot(), srcRoot, dstRoot);
            if (!insideNewHome(targetRoot, dstRoot)) foreign = true;
            for (Projection p : b.projections()) {
                Path dest = remapPath(p.destPath(), srcRoot, dstRoot);
                if (!insideNewHome(dest, dstRoot)) foreign = true;
                projections.add(new Projection(
                        p.bindingId(),
                        remapPath(p.sourcePath(), srcRoot, dstRoot),
                        dest,
                        p.kind(), p.backupOf(), p.boundHash()));
            }
            if (foreign) {
                dropped.add(ledger.unitName() + ":" + b.bindingId()
                        + " → " + (targetRoot == null ? "(no target)" : targetRoot));
                continue;
            }
            bindings.add(new Binding(b.bindingId(), b.unitName(), b.unitKind(), b.subElement(),
                    targetRoot,
                    b.conflictPolicy(), b.createdAt(), b.source(), projections));
        }
        return new ProjectionLedger(ledger.unitName(), bindings);
    }

    /**
     * Re-anchor the {@code child-homes/} records, and drop the ones naming a
     * child home outside the copy.
     *
     * <p>Kept per-record rather than dropped wholesale (contrast
     * {@link #DROPPED_STATE_DIRS}) because a record whose {@code childHome} is
     * the home itself is a record about this home and survives the copy
     * meaningfully. A record naming another checkout does not: the
     * {@code projects/} registration that would let {@code project remove}
     * reach it has already been dropped, so nothing in the copy can act on it
     * except {@link dev.skillmanager.app.RemoveUseCase}, which reads it only to
     * <em>refuse</em> — which is how a brand-new home came to refuse to
     * uninstall a unit it had never asked for, naming two of the operator's
     * repositories as the thing to remove first.
     */
    private static int reanchorChildHomes(Path srcRoot, Path dstRoot, List<String> dropped) {
        Path dir = dstRoot.resolve(ChildHomeRegistry.DIR);
        if (!Files.isDirectory(dir)) return 0;
        ChildHomeRegistry registry = new ChildHomeRegistry(new SkillStore(dstRoot));
        int rewritten = 0;
        try (var stream = Files.list(dir)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                Path f = child.resolve(ChildHomeRegistry.FILENAME);
                if (!Files.isRegularFile(f)) continue;
                try {
                    ChildHomeRegistry.ChildHomeRecord raw = BindingJson.MAPPER
                            .readValue(f.toFile(), ChildHomeRegistry.ChildHomeRecord.class);
                    // parentHome may be token form (already relocatable, and
                    // it resolves against the copy) or a legacy absolute path
                    // into the source, which must be remapped before it is
                    // re-encoded.
                    String parentHome = HomePaths.isEncoded(raw.parentHome())
                            ? HomePaths.of(dstRoot).decodeToString(raw.parentHome())
                            : remapString(raw.parentHome(), srcRoot, dstRoot);
                    String childHome = remapString(raw.childHome(), srcRoot, dstRoot);
                    if (childHome != null && !childHome.isBlank()
                            && !insideNewHome(Path.of(childHome), dstRoot)) {
                        Fs.deleteRecursive(child);
                        dropped.add(raw.id() == null ? child.getFileName().toString() : raw.id());
                        continue;
                    }
                    registry.write(new ChildHomeRegistry.ChildHomeRecord(
                            raw.id(), parentHome, childHome, raw.harnessName(),
                            raw.units(), raw.createdAt()));
                    rewritten++;
                } catch (IOException e) {
                    Log.warn("clone: could not re-anchor %s: %s", f, e.getMessage());
                }
            }
        } catch (IOException e) {
            Log.warn("clone: could not list %s: %s", dir, e.getMessage());
        }
        return rewritten;
    }

    // ------------------------------------------ provisioned re-anchoring

    /**
     * Re-point machine-provisioned artifacts at the copy. A virtualenv
     * console script starts with an absolute shebang naming its own
     * interpreter; that is not something a write-time encoding can fix,
     * because the kernel reads the bytes literally. Substituting the root
     * prefix is the only option, and it is safe here precisely because
     * these files are generated, not authored.
     */
    private static int reanchorProvisioned(Path srcRoot, Path dstRoot, List<Leak> overflows,
                                          List<String> danglingReferences) throws IOException {
        byte[] needle = srcRoot.toString().getBytes(StandardCharsets.UTF_8);
        byte[] replacement = dstRoot.toString().getBytes(StandardCharsets.UTF_8);
        int[] rewritten = {0};
        Files.walkFileTree(dstRoot, new SimpleWalker((file, rel) -> {
            if (classify(rel) != Surface.PROVISIONED) return;
            if (Files.isSymbolicLink(file)) return;
            byte[] content;
            try {
                if (Files.size(file) > WHOLE_FILE_LIMIT) return;
                content = Files.readAllBytes(file);
            } catch (IOException e) {
                return;
            }
            if (indexOf(content, needle, 0) < 0) return;
            if (looksBinary(content)) {
                // Substituting inside a compiled artifact would change its
                // length and corrupt it. Leave it; verify() will report it.
                return;
            }
            byte[] replaced = replaceAll(content, needle, replacement);
            int overflow = shebangOverflow(replaced);
            if (overflow > 0) {
                // Writing this would produce a tool that fails at exec time
                // with a truncated interpreter path, and verify() cannot see
                // it because the source path is gone. Refuse, and say so.
                overflows.add(new Leak(rel, "SHEBANG_TOO_LONG",
                        overflow + " bytes exceeds the " + SHEBANG_LIMIT
                                + "-byte limit; destination root is too deep for this venv"));
                return;
            }
            try {
                Files.write(file, replaced);
                rewritten[0]++;
                for (String missing : missingDestReferences(replaced, dstRoot)) {
                    danglingReferences.add(rel + " -> " + missing);
                }
            } catch (IOException e) {
                Log.warn("clone: could not re-anchor %s: %s", file, e.getMessage());
            }
        }));
        danglingReferences.sort(String::compareTo);
        return rewritten[0];
    }

    /**
     * Paths inside {@code dstRoot} that a re-anchored file names but that do
     * not exist in the copy.
     *
     * <p>This is how a skipped directory shows up in a <em>script body</em>
     * rather than a symlink. {@code bin/cli/computeq} is a generated shell
     * shim whose {@code exec} target is
     * {@code <home>/cache/skill-script-deploy-helm-computeq/venv/bin/computeq};
     * the clone skips {@code cache/}, so the re-anchored path is correct and
     * points at nothing. The dangling-<em>symlink</em> report cannot see it,
     * and without this the clone would hand over three silently broken CLI
     * tools while reporting success.
     *
     * <p>Not a leak: nothing points at the source home, and the remediation
     * is the same one-command re-provision. Copying the targets instead is
     * not an option — the three {@code skill-script} venvs are 530 MB each,
     * 1.6 GB in total, which is most of what skipping {@code cache/} saves.
     *
     * <p>Best-effort by nature: the path is recovered from arbitrary text, so
     * it stops at the first shell/quoting delimiter.
     *
     * <p><b>Restricted to the re-provisionable roots</b> ({@link
     * #PROVISIONABLE_ROOTS}) since #133 made this a gate rather than a
     * warning. {@code logs/} and {@code tmp/} are skipped by the clone too,
     * but a missing log file is the normal state of a home nobody has run
     * yet — failing on it would be a gate that fires on every fresh clone,
     * and a gate that always fires is a gate somebody turns off.
     */
    private static List<String> missingDestReferences(byte[] content, Path dstRoot) {
        List<String> missing = new ArrayList<>();
        for (String candidate : destReferences(content, dstRoot)) {
            if (!Files.exists(Path.of(candidate))) missing.add(candidate);
        }
        return missing;
    }

    /**
     * Every path under a {@link #PROVISIONABLE_ROOTS} root of {@code dstRoot}
     * that {@code content} names — present or missing.
     *
     * <h2>Why the scan and the existence test are now two things</h2>
     *
     * <p>{@link #missingDestReferences} is the CLONE's question ("what did this
     * copy not carry"), and it is a filter over this one. ARTI-05 needs the
     * other half: which provisioned tree a generated shim RUNS OUT OF, in a home
     * where the tree is present and everything is healthy. Reading that off the
     * missing list would produce a dependency graph with edges only in broken
     * homes, which is the one home where the graph is least useful.
     *
     * <p>One scanner, two filters, for the reason {@link #missingReferencesIn}'s
     * javadoc already gives: a gate and a reader that recover "which tree is
     * this" independently will disagree, and nothing will say which is right.
     */
    private static List<String> destReferences(byte[] content, Path dstRoot) {
        String text = new String(content, StandardCharsets.UTF_8);
        String canonical = dstRoot.toAbsolutePath().normalize().toString();
        List<String> found = new ArrayList<>();
        for (String root : rootSpellings(dstRoot)) scanFor(text, root, canonical, found);
        return found;
    }

    /**
     * Every spelling of {@code root} a generated file in this home could hold:
     * the one the caller was given, and the one {@link Path#toRealPath} makes
     * of it.
     *
     * <h2>#206, and why resolving ONCE is not enough</h2>
     *
     * <p>{@link #verifyRoots} resolves the destination for the symlink branch
     * ({@code realOrSame(dstRoot)}) and handed the UNRESOLVED spelling to the
     * provisioned-file branch, and the scan below is a literal
     * {@code text.indexOf}. So a home reached through a symlink was checked
     * against a string its own generated shims do not contain, and the branch
     * reported clean WITHOUT HAVING CHECKED — on the check that is the
     * post-condition of 22 graphs.
     *
     * <p><b>Resolving the root once and handing THAT to every branch would swap
     * the blindness rather than remove it.</b> The clone re-anchors generated
     * files to the destination spelling it was GIVEN, so a home created at
     * {@code /link/home} holds {@code /link/home/cache/…} in its wrappers; a
     * scan for {@code /real/home} alone would miss every one of them. Both
     * spellings are real, either can be in the bytes, so both are scanned and
     * the results merged.
     *
     * <p><b>Do not reach for {@code /var -> /private/var} to test this.</b>
     * Measured at the epic tip: that pair is substring-compatible —
     * {@code /private/var/folders/x} literally CONTAINS {@code /var/folders/x} —
     * so {@code indexOf} finds the reference by accident and a fixture built on
     * a macOS temp path passes before AND after this fix. The defect needs two
     * spellings that are not substrings of one another: a symlinked
     * intermediate directory, a symlinked {@code $HOME} or checkout,
     * {@code /Volumes/…}. See {@code HomeVerifyPathSpellingTest}, which builds
     * exactly that and records the vacuous shape it did not use.
     */
    static List<String> rootSpellings(Path root) {
        Path abs = root.toAbsolutePath().normalize();
        String given = abs.toString();
        String real = realOrSame(abs).toString();
        return given.equals(real) ? List.of(given) : List.of(given, real);
    }

    /**
     * One spelling's pass over {@code text}, appending what it recovers
     * REWRITTEN to {@code canonical}.
     *
     * <p>The rewrite is what keeps this from becoming a second defect. Callers
     * make these strings home-relative against the root they passed in
     * ({@link #referencesIn}'s contract, and what {@code ArtifactBuild} joins
     * on), so a path recovered under the OTHER spelling would relativize into
     * {@code ../../..} nonsense. It also collapses the duplicate that
     * substring-compatible spellings produce — {@code /private/var/x/cache/y}
     * matches a scan for {@code /var/x} as well as one for {@code /private/var/x}
     * — so a home on a macOS temp path still reports ONE unresolved reference
     * and not two.
     */
    private static void scanFor(String text, String root, String canonical, List<String> found) {
        int from = 0;
        while (true) {
            int at = text.indexOf(root, from);
            if (at < 0) break;
            int end = at + root.length();
            while (end < text.length() && "\"'\n\r\t :;,)".indexOf(text.charAt(end)) < 0) end++;
            String candidate = text.substring(at, end);
            from = end;
            // Trim trailing punctuation a path would not end on.
            while (candidate.endsWith(".") || candidate.endsWith("/")) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            if (candidate.length() <= root.length()) continue;
            if (!underProvisionableRoot(candidate, root)) continue;
            String normalized = canonical + candidate.substring(root.length());
            if (!found.contains(normalized)) found.add(normalized);
        }
    }

    /**
     * Whether {@code candidate}'s first home-relative segment names a root a
     * re-provision rebuilds. See {@link #missingDestReferences}.
     */
    private static boolean underProvisionableRoot(String candidate, String root) {
        String rel = candidate.substring(root.length());
        while (rel.startsWith("/")) rel = rel.substring(1);
        int slash = rel.indexOf('/');
        return PROVISIONABLE_ROOTS.contains(slash < 0 ? rel : rel.substring(0, slash));
    }

    /**
     * Length of the leading {@code #!} line if it exceeds
     * {@link #SHEBANG_LIMIT}, else 0. Only the first line matters — the
     * kernel reads no further.
     */
    private static int shebangOverflow(byte[] content) {
        if (content.length < 2 || content[0] != '#' || content[1] != '!') return 0;
        int end = 0;
        while (end < content.length && content[end] != '\n' && content[end] != '\r') end++;
        return end > SHEBANG_LIMIT ? end : 0;
    }

    // ----------------------------------------------------- verification

    /**
     * Everything the check found. {@code contentReferences} is deliberately
     * not folded into {@code leaks} — it is tolerated, not absent — so any
     * caller reporting a result has to decide what to say about it rather
     * than reading an empty leak list as "nothing survives".
     *
     * <h2>Why the split is exposed rather than left to each reporter</h2>
     *
     * <p>The check has always classified a live {@code SYMLINK_TARGET} apart
     * from an authored {@code CONTENT_REFERENCE}. What it did not do was hand
     * that classification to its callers in a usable shape, so every reporter
     * re-derived it — and the one in {@code home verify} did not, printing
     * {@code 169 reference(s)} as a single alphabetically sorted list in which
     * the six paths that actually resolve into another home sat between
     * authored history files 30 and 31. {@link #isolationFailures()} and
     * {@link #toleratedFailures()} are that split, computed once. Issue #133.
     */
    public record Verification(List<Leak> leaks, List<String> contentReferences,
                               List<String> danglingLinks,
                               List<String> unresolvedReferences,
                               List<String> diagnosticReferences,
                               List<String> parentStoreShims,
                               /**
                                * Entry points this home DECLARES and has not
                                * built — the third state, and the reason it is
                                * a list of its own rather than a subset of
                                * {@link #unresolvedReferences}.
                                *
                                * <p>A gate that reports a normal state as a
                                * failure gets turned off. Every clone ships
                                * these by construction, so folding them in
                                * would make {@code home verify} exit 1 on every
                                * fresh worktree, and the next move after that
                                * is not "fix the artifact", it is "stop running
                                * the check". They are reported and never
                                * counted. See
                                * {@link HomePolicy#LAZY_ARTIFACTS_KEY}.
                                */
                               List<String> declaredNotBuilt,
                               /**
                                * HIS-19. The home's own {@code bin/cli/skill-manager}
                                * pins a build that is not there.
                                *
                                * <p>Its own list because the walk above cannot
                                * see it and never could: {@code missingReferencesIn}
                                * only considers paths under THIS home and under a
                                * provisionable root, and a dead pin names a path
                                * outside the home entirely — a Homebrew keg an
                                * upgrade deleted. Nothing else in this record is
                                * about a path that leaves the home, which is
                                * precisely why {@code home verify} returned
                                * <b>exit 0</b> on the root home the 0.24.0 upgrade
                                * broke.
                                *
                                * <p>Read with {@link dev.skillmanager.launch.LauncherShims#danglingPinIn}
                                * — HIS-13's reader and the only one — so
                                * {@code home verify} and {@code home repair}
                                * cannot come to disagree about one file.
                                */
                               List<String> danglingCliPins) {
        public Verification {
            leaks = leaks == null ? List.of() : List.copyOf(leaks);
            contentReferences = contentReferences == null ? List.of() : List.copyOf(contentReferences);
            danglingLinks = danglingLinks == null ? List.of() : List.copyOf(danglingLinks);
            unresolvedReferences = unresolvedReferences == null
                    ? List.of() : List.copyOf(unresolvedReferences);
            diagnosticReferences = diagnosticReferences == null
                    ? List.of() : List.copyOf(diagnosticReferences);
            parentStoreShims = parentStoreShims == null ? List.of() : List.copyOf(parentStoreShims);
            declaredNotBuilt = declaredNotBuilt == null ? List.of() : List.copyOf(declaredNotBuilt);
            danglingCliPins = danglingCliPins == null ? List.of() : List.copyOf(danglingCliPins);
        }

        public Verification(List<Leak> leaks, List<String> contentReferences,
                            List<String> danglingLinks, List<String> unresolvedReferences,
                            List<String> diagnosticReferences, List<String> parentStoreShims,
                            List<String> declaredNotBuilt) {
            this(leaks, contentReferences, danglingLinks, unresolvedReferences,
                    diagnosticReferences, parentStoreShims, declaredNotBuilt, List.of());
        }

        public Verification(List<Leak> leaks, List<String> contentReferences,
                            List<String> danglingLinks, List<String> unresolvedReferences,
                            List<String> diagnosticReferences, List<String> parentStoreShims) {
            this(leaks, contentReferences, danglingLinks, unresolvedReferences,
                    diagnosticReferences, parentStoreShims, List.of(), List.of());
        }

        public Verification(List<Leak> leaks, List<String> contentReferences,
                            List<String> danglingLinks, List<String> unresolvedReferences,
                            List<String> diagnosticReferences) {
            this(leaks, contentReferences, danglingLinks, unresolvedReferences,
                    diagnosticReferences, List.of(), List.of(), List.of());
        }

        public Verification(List<Leak> leaks, List<String> contentReferences,
                            List<String> danglingLinks, List<String> unresolvedReferences) {
            this(leaks, contentReferences, danglingLinks, unresolvedReferences,
                    List.of(), List.of(), List.of(), List.of());
        }

        public boolean clean() { return leaks.isEmpty(); }

        /**
         * The leaks that are never acceptable in any mode: a symlink or a
         * generated path in this home that <em>resolves</em> into another one.
         * Independent of {@code strict}, because there is no reading under
         * which a live path into another home is fine.
         */
        public List<Leak> isolationFailures() {
            return leaks.stream().filter(leak -> !leak.tolerable()).toList();
        }

        /** The authored mentions, promoted to failures only under {@code strict}. */
        public List<Leak> toleratedFailures() {
            return leaks.stream().filter(Leak::tolerable).toList();
        }

        /** True when nothing in this home resolves into any other home. */
        public boolean isolated() { return isolationFailures().isEmpty(); }

        /**
         * Links and generated scripts naming a path in this home that does not
         * exist — the shape a skipped provisioning root leaves behind.
         */
        public List<String> unresolved() {
            List<String> all = new ArrayList<>(danglingLinks);
            all.addAll(unresolvedReferences);
            return List.copyOf(all);
        }
    }

    /**
     * Walk {@code dest} for anything still naming {@code source}. The
     * acceptance criterion for a clone.
     */
    public static Verification verify(Path source, Path dest, boolean strict) throws IOException {
        return verifyRoots(source.toAbsolutePath().normalize(),
                dest.toAbsolutePath().normalize(), strict);
    }

    /**
     * The half of the check that needs only the home being checked.
     *
     * <h2>Why this overload exists</h2>
     *
     * <p>{@code home verify} used to require {@code --against}, and both
     * {@code home clone} and {@code bootstrap-home.sh} print "{@code
     * skill-manager home verify} refuses this home until you do" — naming no
     * arguments, because the thing that refusal is about does not have a
     * second home in it. An agent holding only the home path ran the sentence
     * as printed and got exit 2, {@code Missing required options:
     * '--home=<home>', '--against=<against>'}: a remedy that does not run is
     * not a remedy, and the working spelling was discoverable only from
     * {@code --help}.
     *
     * <p>Two of the three findings never needed a source at all —
     * {@link Verification#unresolved()} (provisioning that never completed,
     * which is exactly what that remedy repairs) and the {@code FOREIGN_HOME}
     * / {@code FOREIGN_AGENT_HOME} leaks (a path in this home that resolves
     * into <em>any</em> other one). Only "no reference back to the source
     * survives" needs {@code --against}, and that one is reported as NOT
     * CHECKED rather than silently skipped, because a check that quietly
     * narrows its scope while keeping its verdict is the defect this whole
     * epic keeps re-finding.
     *
     * <h2>That claim was FALSE for two releases, and is now true with a
     * condition</h2>
     *
     * <p>Issue #227 named this javadoc as a defect, and it was one. The
     * {@code FOREIGN_HOME} half DID need a source: a clone's inherited
     * parent-store shims were sanctioned only when the caller passed
     * {@code --against}, so this overload refused, on its own, homes that the
     * two-argument form accepted. Measured on one cloned home: exit 1 with five
     * {@code FOREIGN_HOME} findings here, exit 0 there.
     *
     * <p>HIS-10 makes it true FOR A HOME THIS PROGRAM CLONED, and not by
     * widening the rule: the copy records its descent
     * ({@link HomeProvenance}) and {@link #sanctionedParentShim} re-derives that
     * chain live, so the sanction is a fact in the home rather than an argument
     * on the command line. For a home this program did not clone — a {@code cp
     * -a} copy, an rsync, a pre-HIS-10 clone — there is nothing in the home to
     * re-derive and {@code --against} is still the only thing that can excuse
     * its inherited shims. Repairing those homes is HIS-13's, and until it
     * lands this sentence carries that condition rather than dropping it.
     */
    public static Verification verify(Path dest, boolean strict) throws IOException {
        return verifyRoots(null, dest.toAbsolutePath().normalize(), strict);
    }

    private static Verification verifyRoots(Path srcRoot, Path dstRoot, boolean strict)
            throws IOException {
        byte[] needle = srcRoot == null
                ? null
                : srcRoot.toString().getBytes(StandardCharsets.UTF_8);
        // The source home's agent directories are part of the source home, and
        // until #145 nothing looked for them. The old check searched for the
        // STORE root only, so a ledger row naming <srcRoot>/../.claude/skills
        // was invisible to it — which is exactly how the clone could report
        // that "nothing in it resolves back to the source" while carrying three
        // live instructions to delete files in the source's agent directories.
        List<byte[]> agentNeedles = new ArrayList<>();
        if (srcRoot != null) {
            for (Path agentDir : AgentHomes.agentDirsUnder(AgentHomes.homeRootFor(srcRoot))) {
                if (dstRoot.startsWith(agentDir)) continue;   // the copy lives there; not a leak
                agentNeedles.add(agentDir.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        HomePaths srcPaths = srcRoot == null ? null : HomePaths.of(srcRoot);
        Path dstReal = realOrSame(dstRoot);
        List<Leak> leaks = new ArrayList<>();
        List<String> contentReferences = new ArrayList<>();
        List<String> dangling = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        List<String> parentShims = new ArrayList<>();
        // Kept only so the walk has somewhere to put the one file it exempts;
        // the descent itself is read from the home by whoever reports it.
        List<String> descentRecords = new ArrayList<>();
        // One answer per foreign home rather than one per shim: `isChildOf`
        // lists two directories, and a child home mirroring twenty CLI deps
        // would otherwise ask the identical question twenty times.
        java.util.Map<Path, Boolean> childOf = new java.util.HashMap<>();
        // The same question asked of the SOURCE, cached the same way and for
        // the same reason. Separate map rather than a composite key: two
        // questions about two homes are two questions.
        java.util.Map<Path, Boolean> srcChildOf = new java.util.HashMap<>();
        String needleText = srcRoot == null ? null : srcRoot.toString();
        Files.walkFileTree(dstRoot, new SimpleWalker((file, rel) -> {
            if (Files.isSymbolicLink(file)) {
                Path target;
                try {
                    target = Files.readSymbolicLink(file);
                } catch (IOException e) {
                    return;
                }
                if (target.isAbsolute() && srcPaths != null && srcPaths.isInsideHome(target)) {
                    leaks.add(new Leak(rel, "SYMLINK_TARGET", target.toString()));
                } else if (!Files.exists(file)) {
                    dangling.add(rel + " -> " + target);
                } else {
                    Path foreign = foreignHomeReachedBy(file, dstReal);
                    if (foreign != null) {
                        if (sanctionedParentShim(rel, file, foreign, dstRoot, srcRoot, childOf, srcChildOf)) {
                            parentShims.add(rel + " -> " + foreign);
                        } else {
                            leaks.add(new Leak(rel, "FOREIGN_HOME",
                                    target + " resolves into the home at " + foreign));
                        }
                    } else {
                        Path foreignAgent = foreignAgentHomeReachedBy(file, dstReal);
                        if (foreignAgent != null) {
                            leaks.add(new Leak(rel, Leak.FOREIGN_AGENT_HOME,
                                    target + " resolves into the agent directory "
                                            + foreignAgent + ", which belongs to another home"));
                        }
                    }
                }
                return;
            }
            if (!Files.isRegularFile(file)) return;
            Surface surface = classify(rel);
            // Provisioning that never completed: a generated script naming a
            // path in THIS home that does not exist. Re-derived from the home
            // on every check rather than remembered from the clone that made
            // it, so it cannot go stale and it clears itself the moment the
            // re-provision runs. Issue #133.
            if (surface == Surface.PROVISIONED) {
                for (String missing : missingReferencesIn(file, dstRoot)) {
                    unresolved.add(rel + " -> " + missing);
                }
            }
            // With no --against there is no needle: the source-reference half
            // of the check is not narrowed here, it is skipped and REPORTED as
            // skipped by the caller.
            boolean namesStore = needle != null && containsBytes(file, needle);
            boolean namesAgentHome = false;
            for (byte[] agentNeedle : agentNeedles) {
                if (containsBytes(file, agentNeedle)) { namesAgentHome = true; break; }
            }
            if (!namesStore && !namesAgentHome) return;
            if (surface == Surface.CONTENT) {
                contentReferences.add(rel);
                if (strict) leaks.add(new Leak(rel, Leak.CONTENT_REFERENCE, "authored unit content"));
            } else if (namesAgentHome) {
                // FIRST, before any downgrade. #144's diagnostic check accounts
                // only for occurrences of the STORE needle, so a record holding
                // both a diagnostic store-mention and a live agent-home
                // reference would satisfy mentionIsOnlyDiagnostic and be
                // downgraded whole — losing the #145 finding entirely. This
                // kind is never tolerable, so it is tested before anything that
                // can tolerate.
                leaks.add(new Leak(rel, Leak.SOURCE_AGENT_HOME,
                        "names an agent directory of the source home (" + surface.name().toLowerCase()
                                + ") — acting on this record would read or delete files "
                                + "in the home this copy was made from"));
            } else if (HomeProvenance.isProvenanceRecord(rel)
                    && HomeProvenance.mentionsOnlyRecordedDescent(file, needleText)) {
                // HIS-10. The ONE file in a copy whose job is to name the home
                // the copy was made from. Refusing it would refuse the evidence
                // the sanction now stands on, and "record your descent, then
                // fail verification for having recorded it" is not a fixpoint.
                //
                // Not a blanket filename exemption: the branch holds only when
                // every raw occurrence of the source-home needle is accounted
                // for by the parsed clonedFrom / parentStores fields, the same
                // byte-counting mentionIsOnlyDiagnostic does. One occurrence
                // anywhere else and this is false and the finding stands.
                //
                // Nor is it silent: `home verify` prints the recorded descent
                // for any home that has one, whether or not this branch fired,
                // and prints each recorded store as re-derived or as a dead
                // claim -- so the exemption is visible in the same output as
                // the verdict it shaped. (`home clone` does NOT print it. An
                // earlier version of this comment said it did; it never has,
                // and the review of #228 was right to check.)
                descentRecords.add(rel);
            } else if (mentionIsOnlyDiagnostic(file, rel, needleText)) {
                // A sentence about another home, not a path into one. See
                // Leak#DIAGNOSTIC_TEXT — and note this is the only branch that
                // can downgrade a STATE finding, and it downgrades nothing it
                // cannot account for byte by byte.
                diagnostics.add(rel);
                if (strict) leaks.add(new Leak(rel, Leak.DIAGNOSTIC_TEXT, "persisted error message"));
            } else {
                leaks.add(new Leak(rel, "FILE_CONTENT", surface.name().toLowerCase()));
            }
        }));
        // The third state, separated LAST so that every rule above it is
        // unchanged: a path that resolves into another home is still a leak
        // whatever the ledger says about it, and only the "names something in
        // THIS home that is not there" findings can be declared rather than
        // broken.
        for (String record : descentRecords) {
            Log.detail("verify: %s names the home this copy descends from — every occurrence "
                    + "accounted for by its recorded clonedFrom/parentStores, so it is a "
                    + "statement about descent and not a path into that home", record);
        }
        List<String> declaredNotBuilt = partitionDeclared(dstRoot, dangling, unresolved);
        List<String> danglingCliPins = danglingCliPinIn(dstRoot);
        leaks.sort(java.util.Comparator.comparing(Leak::path));
        contentReferences.sort(String::compareTo);
        dangling.sort(String::compareTo);
        unresolved.sort(String::compareTo);
        diagnostics.sort(String::compareTo);
        parentShims.sort(String::compareTo);
        declaredNotBuilt.sort(String::compareTo);
        return new Verification(leaks, contentReferences, dangling, unresolved, diagnostics,
                parentShims, declaredNotBuilt, danglingCliPins);
    }

    /**
     * HIS-19. The home's CLI pin, when it names a build that is gone.
     *
     * <p><b>The 0.24.0 incident, as a check.</b> {@code brew upgrade} deleted the
     * keg the operator's root home pinned; the home's front door could only
     * produce exit 127 from that moment on; and {@code home verify} said
     * {@code ✓} and exited 0. It said so honestly, given what it looked at — the
     * walk above finds a missing path only when the path is INSIDE this home and
     * under a provisionable root, and a dead pin is neither. So this is not a
     * widening of the walk, it is the one question the walk structurally cannot
     * ask, asked separately.
     *
     * <p><b>One subject, one reader, no repair.</b> The subject is always
     * {@code bin/cli/skill-manager} and the reader is
     * {@link dev.skillmanager.launch.LauncherShims#danglingPinIn}, which HIS-13's
     * {@code home repair} also uses — so the two commands cannot come to
     * disagree, which is what {@code GOAL-one-home-one-answer} asks of this
     * ticket. Verification stays an observer: it reports, and names
     * {@code home repair --fix} as the thing that acts. An observer that repairs
     * is DEF-067.
     *
     * <p>Empty for a home with no entrypoint, for an entrypoint this program did
     * not generate, and for a pin that is computed rather than literal.
     * "Cannot tell" is never reported as "broken" — the response to this finding
     * is to rewrite a home's front door, so a false positive here is worse than
     * no check at all.
     */
    private static List<String> danglingCliPinIn(Path dstRoot) {
        Path entrypoint;
        try {
            entrypoint = dev.skillmanager.launch.LauncherShims
                    .cliEntrypoint(new SkillStore(dstRoot));
        } catch (RuntimeException notAStore) {
            return List.of();
        }
        Path gone = dev.skillmanager.launch.LauncherShims.danglingPinIn(entrypoint).orElse(null);
        if (gone == null) return List.of();
        return List.of(dstRoot.relativize(entrypoint) + " -> " + gone);
    }

    /**
     * Move the findings this home DECLARES out of the two failure lists.
     *
     * <h2>Three states, and the one that must not be a failure</h2>
     *
     * <ul>
     *   <li><b>materialized</b> — nothing appears here at all;</li>
     *   <li><b>declared, not built</b> — the home's ledger names an artifact
     *       whose output is this path, and the home declares
     *       {@code lazy_artifacts}. Normal, expected, and reported;</li>
     *   <li><b>broken</b> — everything else, unchanged: a path nothing in this
     *       home ever claimed to produce, which is a defect whatever tier the
     *       home is.</li>
     * </ul>
     *
     * <p><b>Both conditions, and neither alone.</b> Without the ledger test
     * this would excuse every unresolved reference in a lazy home, which is
     * "turn the gate off" spelled as a feature. Without the policy test it
     * would excuse them in the operator root, where nothing deferred anything
     * and an unresolved reference means an install broke.
     *
     * <p>Matching is by the finding's LEFT side — the entry point's own
     * home-relative path — against the ledger's output paths. Same join
     * {@code ArtifactBuild.buildableFor} uses to turn a finding into a remedy,
     * so a finding this method excuses is exactly a finding that command can
     * act on.
     */
    private static List<String> partitionDeclared(Path dstRoot, List<String> dangling,
                                                  List<String> unresolved) {
        List<String> declared = new ArrayList<>();
        SkillStore store = new SkillStore(dstRoot);
        try {
            if (!HomePolicy.lazyArtifacts(store)) return declared;
        } catch (IOException e) {
            // An unreadable policy is not a licence to excuse anything.
            return declared;
        }
        Set<String> outputs = new java.util.HashSet<>();
        try {
            for (ArtifactLedger.Row row : ArtifactLedger.load(store).rows()) {
                outputs.addAll(row.outputs());
            }
        } catch (IOException | RuntimeException e) {
            return declared;
        }
        if (outputs.isEmpty()) return declared;
        for (List<String> findings : List.of(dangling, unresolved)) {
            findings.removeIf(finding -> {
                int arrow = finding.indexOf(" -> ");
                String left = (arrow < 0 ? finding : finding.substring(0, arrow))
                        .trim().replace('\\', '/');
                if (!outputs.contains(left)) return false;
                declared.add(finding);
                return true;
            });
        }
        return declared;
    }

    /**
     * The two shim directories a child home is allowed to mirror from its
     * parent store, in the {@code <rel>} spelling {@link SimpleWalker} reports.
     */
    private static final List<String> SHIM_DIRS = List.of("bin/cli/", "bin/mcp/");

    /**
     * The other Skill Manager home {@code link} resolves into and is NOT
     * sanctioned to, or null.
     *
     * <p>The public form of the isolation rule, so the repair asks the gate
     * rather than re-deriving "is this a leak" beside it. {@code CliArtifact}
     * makes the same argument for the same reason: a gate and a repair that
     * derive brokenness independently disagree, and the one that disagrees
     * quietly is the repair.
     *
     * @param rel  this path's location inside {@code homeRoot}, {@code /}- or
     *             platform-separated — it decides whether the sanctioned
     *             child-home shim exception can apply at all
     */
    public static Path unsanctionedForeignHome(String rel, Path link, Path homeRoot) {
        if (rel == null || link == null || homeRoot == null) return null;
        Path root = homeRoot.toAbsolutePath().normalize();
        Path foreign = foreignHomeReachedBy(link, realOrSame(root));
        if (foreign == null) return null;
        // No source here: CliShimPruner asks about ONE standing home, not about
        // a copy, so there is no source whose sanction could be inherited.
        return sanctionedParentShim(rel, link, foreign, root, null,
                new java.util.HashMap<>(), new java.util.HashMap<>())
                ? null : foreign;
    }

    /**
     * True when this link is a child home's <em>sanctioned</em> mirror of an
     * entry in its own parent store, rather than a path leaking into a home
     * that has nothing to do with this one.
     *
     * <h2>Three conditions, all required</h2>
     *
     * <ol>
     *   <li><b>It is a shim entry.</b> {@code bin/cli/<name>} or
     *       {@code bin/mcp/<name>}, one segment deep — the only two places
     *       {@link dev.skillmanager.bindings.ChildHomeMaterializer#mirrorExistingShim}
     *       ever writes. A link anywhere else in the home is not something that
     *       mechanism produced, so nothing about it is sanctioned by this.</li>
     *   <li><b>It names the SAME entry in the other home.</b> The parent's
     *       {@code bin/cli/<name>} and the child's must resolve to one
     *       artifact. A shim pointing at some other file that merely happens to
     *       live in another home is not a mirror of anything.</li>
     *   <li><b>That home is an ancestor of this one.</b>
     *       {@link dev.skillmanager.bindings.ChildHomeLink#isChildOf} — evidence
     *       on disk, from the parent's registry or the child's own
     *       materialization records — or {@link HomeProvenance#sanctions}, the
     *       descent a copy records at clone time. Without one of the two, the
     *       first two conditions describe a shape that a stale copied link has
     *       too.</li>
     * </ol>
     *
     * <p>The result is NOT a tolerated leak. A tolerated leak is fatal under
     * {@code --strict}, and this is not a defect at any strictness: sharing the
     * parent's provisioned toolchain is what a child home is <em>for</em>. See
     * {@code ChildHomeLink} for why the alternative — child homes stop
     * mirroring — was rejected.
     */
    private static boolean sanctionedParentShim(String rel, Path link, Path foreign, Path dstRoot,
                                                Path srcRoot,
                                                java.util.Map<Path, Boolean> childOf,
                                                java.util.Map<Path, Boolean> srcChildOf) {
        String normalized = rel.replace(java.io.File.separatorChar, '/');
        String dir = null;
        for (String candidate : SHIM_DIRS) {
            if (normalized.startsWith(candidate)) { dir = candidate; break; }
        }
        if (dir == null) return false;
        String name = normalized.substring(dir.length());
        if (name.isEmpty() || name.contains("/")) return false;
        Path parentEntry = foreign.resolve(dir + name);
        if (!Files.exists(parentEntry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
        if (!realOrSame(link).equals(realOrSame(parentEntry))) return false;
        // TWO KINDS OF EVIDENCE, BOTH IN THIS HOME, NEITHER ON THE COMMAND LINE.
        //
        // ChildHomeLink is the one-level relation: the parent's registry claims
        // this home, or this home's materialization records name a unit source
        // inside that store. HomeProvenance is the recorded DESCENT: a copy of a
        // child is a grandchild, and this is where it says so. Both are files in
        // this home; neither needs the other home to exist, and neither needs an
        // operator to type anything. Cached together because a home mirroring
        // twenty deps would otherwise ask the identical pair of questions twenty
        // times.
        if (childOf.computeIfAbsent(foreign,
                home -> dev.skillmanager.bindings.ChildHomeLink.isChildOf(dstRoot, home)
                        || HomeProvenance.sanctions(dstRoot, home))) {
            return true;
        }
        // HIS-7. A COPY INHERITS ITS SOURCE'S SANCTION.
        //
        // The check above asks whether the DESTINATION is a child of the home
        // this shim points into. During a clone the destination is a home that
        // DOES NOT EXIST YET: nothing claims it, and it was materialized from
        // the source, not from the home the shim names. So the identical shim
        // that `home verify` sanctions in the source was refused in the copy,
        // `bootstrap-home.sh` failed, and neither `wt new` nor `skt ticket new`
        // could produce a ticket home in this repository at all.
        //
        // The chain is root -> project -> worktree, so a clone of a child is a
        // GRANDCHILD and the one-level question could never have answered yes.
        // Asked of the SOURCE instead, it answers correctly: the project home
        // IS a registered child of root, the shim was legitimately inherited,
        // and copying it changes nothing about whose artifact it is.
        //
        // This is NOT a widening of what counts as sanctioned. The structural
        // tests above still hold -- same bin/cli spelling, same real target,
        // pointing at a home some link in this chain is genuinely a child of.
        // What changed is WHICH home is asked, and a copy that inherits bytes
        // inherits the reason they were allowed.
        //
        // HIS-10 KEPT THIS ARM AND DEMOTED IT. A home cloned by this build
        // carries a HomeProvenance record written before the clone verifies
        // itself, so the branch above answers first and `--against` decides
        // nothing about a copy this program made. What is left here is the home
        // this program did NOT make: a byte copy taken with `cp -a`, an rsync,
        // a clone by an older skill-manager. For those, `--against` still
        // EXPLAINS a sanction that has nothing in the copy to stand on -- and
        // repairing such a home so it stands on its own record is HIS-13's, not
        // this method's. Deleting the arm here would turn every pre-HIS-10 clone
        // red on a command that used to pass, which is a migration, not a fix.
        if (srcRoot == null || srcRoot.equals(dstRoot)) return false;
        return srcChildOf.computeIfAbsent(foreign,
                home -> dev.skillmanager.bindings.ChildHomeLink.isChildOf(srcRoot, home));
    }

    /**
     * True when every mention of {@code needle} in this file sits inside an
     * {@code errors[].message} — diagnostic prose, not a reference.
     *
     * <p>Restricted to {@code installed/<unit>.json} because that is the one
     * record with a free-text field. {@code <unit>.projections.json} is
     * excluded: it is the projection ledger, and every path in it is a live
     * one.
     *
     * <p>The test is a count, not a predicate: the raw occurrences must be
     * fully covered by the parsed message strings. One occurrence anywhere
     * else — an {@code origin} that is a local path, a field a future version
     * adds — leaves this false and the finding stays a hard leak. Anything
     * unreadable or unparseable is likewise false; a downgrade needs positive
     * evidence, and not being able to tell is not that.
     */
    private static boolean mentionIsOnlyDiagnostic(Path file, String rel, String needle) {
        if (!isUnitRecord(rel)) return false;
        try {
            if (Files.size(file) > WHOLE_FILE_LIMIT) return false;
            byte[] raw = Files.readAllBytes(file);
            if (looksBinary(raw)) return false;
            int total = countOccurrences(new String(raw, StandardCharsets.UTF_8), needle);
            if (total == 0) return false;
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            com.fasterxml.jackson.databind.JsonNode errors = root.get("errors");
            if (errors == null || !errors.isArray()) return false;
            int inDiagnostics = 0;
            for (com.fasterxml.jackson.databind.JsonNode error : errors) {
                com.fasterxml.jackson.databind.JsonNode message = error.get("message");
                if (message != null && message.isTextual()) {
                    inDiagnostics += countOccurrences(message.asText(), needle);
                }
            }
            return inDiagnostics >= total;
        } catch (IOException | RuntimeException notAReadableRecord) {
            return false;
        }
    }

    /** {@code installed/<unit>.json}, excluding the projection ledger beside it. */
    private static boolean isUnitRecord(String rel) {
        String normalized = rel.replace(java.io.File.separatorChar, '/');
        return normalized.startsWith("installed/")
                && normalized.endsWith(".json")
                && !normalized.endsWith(".projections.json");
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
             at = haystack.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    /**
     * {@link #missingDestReferences} for a file that has not been read yet:
     * the paths inside {@code dstRoot} that {@code file} names and that do not
     * exist.
     *
     * <h2>Public because the GATE and the REPAIR have to ask one question</h2>
     *
     * <p>This is what {@code home verify} refuses on, and until now nothing on
     * the install side asked it — which is precisely why the remedy verify
     * printed was not a fixpoint for a whole class of tool.
     * {@code SkillScriptBackend} asked {@code Files.isExecutable} instead, and
     * a re-anchored {@code bin/cli/computeq} wrapper is a perfectly fine
     * executable that execs a path the copy does not hold. Measured on one
     * clone: {@code skill-dev} (a symlink) self-healed and {@code computeq} (a
     * wrapper) was skipped forever, same home, same sync.
     *
     * <p>So {@link dev.skillmanager.cli.installer.CliArtifact} calls this
     * rather than growing a second scanner. A gate and a repair that derive
     * "broken" independently will disagree, and the one that disagrees quietly
     * is the repair.
     */
    public static List<String> missingReferencesIn(Path file, Path dstRoot) {
        try {
            if (Files.size(file) > WHOLE_FILE_LIMIT) return List.of();
            byte[] content = Files.readAllBytes(file);
            if (looksBinary(content)) return List.of();
            return missingDestReferences(content, dstRoot);
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Every path under a re-provisionable root of {@code dstRoot} that
     * {@code file} reaches — whether or not it exists.
     *
     * <p>{@link #missingReferencesIn} answers "what is broken"; this answers
     * "what does this file RUN OUT OF", which is the edge ARTI-05 derives the
     * artifact graph from. A generated {@code bin/cli/computeq} wrapper naming
     * {@code <home>/cache/skill-script-deploy-helm-computeq/venv/bin/computeq}
     * declares a dependency on that tree in a healthy home exactly as much as in
     * a broken one; the missing-only list is silent in the healthy case.
     *
     * <h2>Symlinks are read as links, not followed</h2>
     *
     * <p>{@code missingReferencesIn} reads bytes, and reading the bytes of
     * {@code bin/cli/jinja2 -> ../../venvs/jinja2-cli/bin/jinja2} gives the
     * bytes of the PYTHON SCRIPT at the far end, whose own text mentions no
     * home path. The reference is the link target, so the link target is what
     * is read — which is how the two shim shapes a home actually holds (a
     * generated wrapper and a relative symlink) are both covered by one call.
     *
     * <p>Returns absolute path strings, matching {@link #missingReferencesIn}.
     * The caller makes them home-relative, because only the caller knows
     * whether it is allowed to write one down.
     */
    public static List<String> referencesIn(Path file, Path dstRoot) {
        if (file == null || dstRoot == null) return List.of();
        try {
            if (Files.isSymbolicLink(file)) {
                Path target = Files.readSymbolicLink(file);
                Path resolved = (target.isAbsolute() ? target
                        : file.toAbsolutePath().getParent().resolve(target)).normalize();
                // Both spellings, same reason as the byte scan: a link stored
                // with the resolved root is a reference this home holds even
                // when the home was addressed through a symlink, and vice
                // versa. Emitted in the CALLER's spelling. See rootSpellings.
                String canonical = dstRoot.toAbsolutePath().normalize().toString();
                String candidate = resolved.toString();
                for (String root : rootSpellings(dstRoot)) {
                    if (!candidate.startsWith(root) || candidate.length() <= root.length()) continue;
                    if (!underProvisionableRoot(candidate, root)) continue;
                    return List.of(canonical + candidate.substring(root.length()));
                }
                return List.of();
            }
            if (!Files.isRegularFile(file)) return List.of();
            if (Files.size(file) > WHOLE_FILE_LIMIT) return List.of();
            byte[] content = Files.readAllBytes(file);
            if (looksBinary(content)) return List.of();
            return destReferences(content, dstRoot.toAbsolutePath().normalize());
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * The Skill Manager home a link in the copy reaches, or null when it
     * reaches none.
     *
     * <h2>Why "outside the clone" is the wrong question and "into another
     * home" is the right one</h2>
     *
     * <p>The check this joins used to ask only whether a path in the copy still
     * resolved back to {@code source}. That is literally true and operationally
     * useless: measured on real cloned homes,
     * {@code skills/deploy-helm/test_graph/build-logic} pointed at
     * {@code ~/.skill-manager/skills/test-graph/project_sdk_sources/build-logic}
     * — the operator's <em>live</em> home, which was not the home this copy was
     * made from, so the check passed and a Gradle build through the copy wrote
     * into the very home the copy exists to stay out of. Issue #49.
     *
     * <p>The obvious repair — "nothing may resolve outside the clone" — is
     * vacuous in the other direction. A home legitimately contains links to
     * things that are not homes and never will be: a venv's
     * {@code bin/python -> /opt/homebrew/.../python3.14}, a uv-managed
     * interpreter under {@code ~/.local/share/uv}. Measured across the seven
     * onboarded project homes, 14–18 links per home resolve outside it and
     * every one of them is a toolchain binary. A rule that fires on all of
     * those is a rule nobody can keep, and a rule nobody keeps is switched off.
     *
     * <p>So the predicate is structural: does the resolved path lie inside
     * something that <em>is</em> a home? {@link LaunchEnv#looksLikeStoreRoot} is
     * the one definition of that, already shared by the PATH sanitizer and
     * {@link NotAHomeException}; a fourth spelling would eventually disagree
     * about exactly the homes that matter (#24).
     *
     * <p>The resolution is {@link Path#toRealPath}, not a textual reading of
     * the link. One of the measured leaks was {@code sdk/../standard-nodes},
     * a <em>relative</em> target whose parent {@code sdk} was itself the
     * absolute link — invisible to anything that inspects link text, and the
     * same disguised shape that forced the {@code [[vendored]]} validator to
     * compare resolved physical paths.
     */
    // Package-private rather than private: HomeProvenance asks the same
    // question when it records which stores a copy may inherit from, and a
    // second spelling of "is this another home" is #24 all over again.
    static Path foreignHomeReachedBy(Path link, Path dstReal) {
        Path resolved;
        try {
            resolved = link.toRealPath();
        } catch (IOException e) {
            return null;   // dangling; already reported by the caller
        }
        if (resolved.equals(dstReal) || resolved.startsWith(dstReal)) return null;
        for (Path parent = resolved; parent != null; parent = parent.getParent()) {
            if (parent.equals(dstReal) || parent.startsWith(dstReal)) return null;
            if (LaunchEnv.looksLikeStoreRoot(parent)) return parent;
        }
        return null;
    }

    /**
     * The <em>agent</em> directory of another home that a link in the copy
     * reaches, or null when it reaches none.
     *
     * <h2>Why {@link #foreignHomeReachedBy} could not see this</h2>
     *
     * <p>That check asks {@link LaunchEnv#looksLikeStoreRoot} about every
     * ancestor, and {@code ~/.claude} is not a store root: it has no
     * {@code installed/}, no {@code skills/} pair in that sense, no descriptor.
     * So a link into the operator's global {@code ~/.claude/skills} passed a
     * check whose success line said "no path in it reaches any other Skill
     * Manager home" — literally true, because an agent home is not a Skill
     * Manager home, and materially false, because that is the directory every
     * agent session actually loads skills from. Issue #145.
     *
     * <p>The predicate stays structural, like its sibling: a directory is
     * another home's agent directory when it is named {@code .claude},
     * {@code .codex} or {@code .gemini} <em>and</em> its parent holds a Skill
     * Manager store. The second half is what keeps this from firing on any
     * {@code .claude} anywhere — an unrelated project's config directory that
     * no home manages is not this mechanism's business, and a rule that fires
     * on it is a rule somebody switches off.
     */
    private static Path foreignAgentHomeReachedBy(Path link, Path dstReal) {
        Path resolved;
        try {
            resolved = link.toRealPath();
        } catch (IOException e) {
            return null;   // dangling; already reported by the caller
        }
        for (Path parent = resolved; parent != null; parent = parent.getParent()) {
            if (parent.equals(dstReal) || parent.startsWith(dstReal)) return null;
            Path name = parent.getFileName();
            if (name == null) continue;
            if (!AgentHomes.CLAUDE_DIR_NAME.equals(name.toString())
                    && !".codex".equals(name.toString())
                    && !".gemini".equals(name.toString())) {
                continue;
            }
            Path owner = parent.getParent();
            if (owner == null) continue;
            Path store = owner.resolve(AgentHomes.STORE_DIR_NAME);
            if (store.equals(dstReal) || store.startsWith(dstReal)) return null;
            if (LaunchEnv.looksLikeStoreRoot(store)) return parent;
        }
        return null;
    }

    private static Path realOrSame(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    // -------------------------------------------------- classification

    /**
     * Which fixup policy a home-relative path falls under. Directory-name
     * based, because that is the only signal available: nothing marks a
     * file as generated. The classes are disjoint and ordered — a
     * {@code .venv} inside a skill is provisioned even though the skill
     * around it is content.
     *
     * <p><b>Default-deny.</b> {@link Surface#CONTENT} is the one class the
     * leak check tolerates, so it is granted only to paths that are
     * positively known to be installed unit content. An unrecognized
     * top-level directory — a future feature's state, a directory this
     * version has never heard of — classifies as {@link Surface#STATE} and
     * therefore fails the clone if it holds a source-home path. Getting
     * that wrong in the other direction is how isolation leaks silently,
     * which is the failure mode this whole effort keeps hitting.
     */
    public static Surface classify(String rel) {
        String normalized = rel.replace(java.io.File.separatorChar, '/');
        for (String segment : normalized.split("/")) {
            if (PROVISIONED_SEGMENTS.contains(segment)) return Surface.PROVISIONED;
        }
        String top = normalized.contains("/")
                ? normalized.substring(0, normalized.indexOf('/'))
                : normalized;
        if (STATE_ROOTS.contains(top)) return Surface.STATE;
        if (PROVISIONED_ROOTS.contains(top)) return Surface.PROVISIONED;
        // Root-level files are skill-manager's own configuration.
        if (!normalized.contains("/")) return Surface.STATE;
        for (String subtree : STATE_SUBTREES) {
            if (normalized.startsWith(subtree + "/")) return Surface.STATE;
        }
        if (CONTENT_ROOTS.contains(top)) return Surface.CONTENT;
        return Surface.STATE;
    }

    // -------------------------------------------------------- plumbing

    private interface Entry {
        void accept(Path file, String rel) throws IOException;
    }

    /** Walks a tree, reporting files and links (never following links). */
    private static final class SimpleWalker implements FileVisitor<Path> {
        private final Entry entry;
        private Path root;

        SimpleWalker(Entry entry) { this.entry = entry; }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (root == null) root = dir;
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            entry.accept(file, rel(root, file));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            return FileVisitResult.CONTINUE;
        }
    }

    private static boolean isSkipped(String rel) {
        String normalized = rel.replace(java.io.File.separatorChar, '/');
        int slash = normalized.indexOf('/');
        String top = slash < 0 ? normalized : normalized.substring(0, slash);
        if (SKIPPED_DIRS.contains(top)) return true;
        // Not skipped for being transient or re-derivable, like everything
        // above — dropped because a copy of a home does not inherit that home's
        // claims over directories elsewhere on the machine. Issue #145 item 3.
        if (DROPPED_STATE_DIRS.contains(top)) return true;
        // Segment-wise, and the .pyc / .pyo suffix rule with it, both from the
        // one definition shared with the reconcile.
        if (Rederivable.isCache(normalized)) return true;
        return slash < 0 && SKIPPED_ROOT_FILES.contains(normalized);
    }

    private static String rel(Path root, Path path) {
        return root.relativize(path).toString();
    }

    /**
     * Where {@code path} lands in the copy: unchanged when it names something
     * genuinely external, re-rooted when it names part of the source home.
     *
     * <h2>A home is its store AND its agent directories</h2>
     *
     * <p>This used to re-root only paths inside the <em>store</em>, and
     * {@link HomePaths}' own javadoc endorsed the omission: "{@code destPath}
     * points at {@code ~/.claude/skills/<unit>} and stays absolute". That is
     * right for a home being written in place and wrong for a home being
     * copied, and issue #145 is the difference. A home's agent directories sit
     * beside its store at {@code <root>/.claude|.codex|.gemini} — the same
     * "beside the store" span {@code HomeDescriptor}'s storage mapper already
     * encodes, and the same directories {@code LaunchEnv#requireClaudeRedirected}
     * insists a launch stay inside. Leaving them absolute means the copy's
     * ledger describes the <em>source's</em> agent directories.
     *
     * <p>Measured, and this is the half that a corrected resolution rule does
     * <b>not</b> cover: with the write path fixed so that a sync in the copy
     * writes only the copy's own agent dirs, an {@code uninstall} in the copy
     * still walked the inherited rows and <b>deleted the source home's agent
     * links</b> — three of them, reporting {@code ✓ unbound} and exiting 0. The
     * write path re-derives its destinations every run and so heals itself; the
     * removal path is ledger-driven by construction, because the whole point of
     * the ledger is to know the exact footprint to undo. A stale row is
     * therefore not a stale opinion about where to write. It is a live
     * instruction to delete something in another home.
     *
     * <p>The bound is the same one the descriptor accepts: exactly the three
     * agent directories directly beside the store. A path deeper in the
     * operator's home, or an agent directory they placed somewhere else
     * entirely, is external and is left naming what it names.
     */
    private static Path remapPath(Path path, Path srcRoot, Path dstRoot) {
        if (path == null) return null;
        Path abs = path.toAbsolutePath().normalize();
        if (HomePaths.of(srcRoot).isInsideHome(abs)) {
            return dstRoot.resolve(srcRoot.relativize(abs));
        }
        Path srcHomeRoot = AgentHomes.homeRootFor(srcRoot);
        Path dstHomeRoot = AgentHomes.homeRootFor(dstRoot);
        // The home root ITSELF, and only by exact match. A binding's
        // targetRoot for the home's own agent projection is the root, not one
        // of the agent directories under it, so without this the row that
        // OWNS the three re-anchored projections kept naming the source's
        // checkout. Exact match and not startsWith: for the global home the
        // root is `~`, and re-rooting everything under `~` would relocate the
        // operator's entire machine into the copy.
        if (abs.equals(srcHomeRoot)) return dstHomeRoot;
        List<Path> srcAgentDirs = AgentHomes.agentDirsUnder(srcHomeRoot);
        List<Path> dstAgentDirs = AgentHomes.agentDirsUnder(dstHomeRoot);
        for (int i = 0; i < srcAgentDirs.size(); i++) {
            Path srcAgentDir = srcAgentDirs.get(i);
            if (abs.equals(srcAgentDir) || abs.startsWith(srcAgentDir)) {
                return dstAgentDirs.get(i).resolve(srcAgentDir.relativize(abs));
            }
        }
        return path;
    }

    private static String remapString(String value, Path srcRoot, Path dstRoot) {
        if (value == null || value.isBlank()) return value;
        Path remapped = remapPath(Path.of(value), srcRoot, dstRoot);
        return remapped == null ? value : remapped.toString();
    }

    /**
     * Undo a failed clone. Only the directory we created is removed; if it
     * already existed (empty) it is left in place, since we did not make it.
     */
    private static void discardPartialClone(Path dst, boolean preexisting) {
        try {
            if (!Files.exists(dst)) return;
            if (preexisting) {
                try (var entries = Files.list(dst)) {
                    for (Path entry : (Iterable<Path>) entries::iterator) {
                        dev.skillmanager.shared.util.Fs.deleteRecursive(entry);
                    }
                }
            } else {
                dev.skillmanager.shared.util.Fs.deleteRecursive(dst);
            }
        } catch (IOException | RuntimeException cleanup) {
            Log.warn("clone: failed and could not fully clean up %s: %s — remove it before retrying",
                    dst, cleanup.getMessage());
        }
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    /**
     * Whether {@code file} contains {@code needle}. Reads in chunks with an
     * overlap so a match straddling a chunk boundary is still found — a
     * home path is short but a venv {@code RECORD} file is not.
     */
    private static boolean containsBytes(Path file, byte[] needle) {
        try {
            long size = Files.size(file);
            if (size < needle.length) return false;
            if (size <= WHOLE_FILE_LIMIT) {
                return indexOf(Files.readAllBytes(file), needle, 0) >= 0;
            }
            byte[] buffer = new byte[1 << 20];
            int carry = 0;
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer, carry, buffer.length - carry)) > 0) {
                    int available = carry + read;
                    if (indexOf(buffer, needle, 0, available) >= 0) return true;
                    carry = Math.min(needle.length - 1, available);
                    System.arraycopy(buffer, available - carry, buffer, 0, carry);
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        return indexOf(haystack, needle, from, haystack.length);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from, int limit) {
        outer:
        for (int i = from; i <= limit - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] replaceAll(byte[] content, byte[] needle, byte[] replacement) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(content.length);
        int i = 0;
        while (i < content.length) {
            int hit = indexOf(content, needle, i);
            if (hit < 0) {
                out.write(content, i, content.length - i);
                break;
            }
            out.write(content, i, hit - i);
            out.write(replacement, 0, replacement.length);
            i = hit + needle.length;
        }
        return out.toByteArray();
    }

    /**
     * Any NUL anywhere means "do not edit this". Sniffing a prefix is the
     * usual heuristic and it is wrong here: a file with a text header and a
     * binary tail (an archive with a shell preamble, a data blob behind a
     * comment block) would pass the sniff and then be rewritten whole,
     * shifting every offset in the tail. The whole buffer is already in
     * memory, so scanning all of it costs nothing.
     */
    private static boolean looksBinary(byte[] content) {
        for (byte b : content) {
            if (b == 0) return true;
        }
        return false;
    }
}
