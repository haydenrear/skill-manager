package dev.skillmanager.store;

import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingJson;
import dev.skillmanager.bindings.ChildHomeRegistry;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionLedger;
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
import java.util.List;
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
 */
public final class HomeCloner {

    /** Top-level directories a clone does not copy. */
    public static final Set<String> SKIPPED_DIRS = Set.of("cache", "tmp", "logs");

    /**
     * Derived build caches, skipped wherever they appear. These are the
     * same kind of thing as {@code cache/} — regenerated on demand from
     * content the clone does carry — and they are the one place where a
     * source path survives in a form nothing can fix: a {@code .pyc}
     * embeds its {@code co_filename} and a Gradle {@code executionHistory.bin}
     * embeds task input paths, both inside binary formats where a
     * length-changing substitution would corrupt the file. Copying them
     * would guarantee a leak; not copying them costs one recompile.
     */
    public static final Set<String> SKIPPED_SEGMENTS = Set.of(
            "__pycache__", ".gradle", ".pytest_cache", ".mypy_cache", ".ruff_cache", ".tox");

    /** Root-level files a clone does not copy. */
    public static final Set<String> SKIPPED_ROOT_FILES =
            Set.of("audit.log", "gateway.log", "gateway.pid");

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
            List<String> danglingReferences
    ) {
        public Report {
            leaks = leaks == null ? List.of() : List.copyOf(leaks);
            contentReferences = contentReferences == null ? List.of() : List.copyOf(contentReferences);
            danglingLinks = danglingLinks == null ? List.of() : List.copyOf(danglingLinks);
            danglingReferences = danglingReferences == null ? List.of() : List.copyOf(danglingReferences);
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
            return build(src, dst, strict, counters);
        } catch (IOException | RuntimeException e) {
            discardPartialClone(dst, preexisting);
            throw e;
        }
    }

    private static Report build(Path src, Path dst, boolean strict, Counters counters)
            throws IOException {
        copyTree(src, dst, counters);

        int stateReanchored = reanchorState(src, dst);
        List<Leak> overflows = new ArrayList<>();
        List<String> danglingReferences = new ArrayList<>();
        int provisionedRewritten = reanchorProvisioned(src, dst, overflows, danglingReferences);
        HomeLinks.relativizeShims(new SkillStore(dst));

        Verification verification = verifyRoots(src, dst, strict);
        // A shebang that would not fit is a failure the walk below cannot
        // detect — the source path was substituted out, so nothing points
        // at the source any more; the file is simply broken.
        List<Leak> leaks = new ArrayList<>(verification.leaks());
        leaks.addAll(overflows);
        leaks.sort(java.util.Comparator.comparing(Leak::path));
        return new Report(src, dst,
                counters.directories, counters.files, counters.symlinks, counters.bytes,
                counters.linksRelativized, stateReanchored, provisionedRewritten,
                leaks, verification.contentReferences(), verification.danglingLinks(),
                danglingReferences);
    }

    // ------------------------------------------------------------- copy

    private static final class Counters {
        int directories, files, symlinks, linksRelativized;
        long bytes;
    }

    private static void copyTree(Path src, Path dst, Counters counters) throws IOException {
        Files.walkFileTree(src, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                String rel = rel(src, dir);
                if (!rel.isEmpty() && isSkipped(rel)) return FileVisitResult.SKIP_SUBTREE;
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
    private static int reanchorState(Path srcRoot, Path dstRoot) throws IOException {
        int rewritten = 0;
        rewritten += reanchorLedgers(srcRoot, dstRoot);
        rewritten += reanchorChildHomes(srcRoot, dstRoot);
        rewritten += reanchorProjectRegistrations(srcRoot, dstRoot);
        rewritten += reanchorRemainingState(srcRoot, dstRoot);
        return rewritten;
    }

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
                Log.warn("clone: re-anchored %s by substitution — it holds a home path no "
                        + "structured writer models; consider encoding it at write time", rel);
            } catch (IOException e) {
                Log.warn("clone: could not re-anchor %s: %s", file, e.getMessage());
            }
        }));
        return rewritten[0];
    }

    private static int reanchorLedgers(Path srcRoot, Path dstRoot) {
        Path dir = dstRoot.resolve("installed");
        if (!Files.isDirectory(dir)) return 0;
        int rewritten = 0;
        try (var stream = Files.newDirectoryStream(dir, "*.projections.json")) {
            for (Path f : stream) {
                try {
                    ProjectionLedger ledger = BindingJson.mapperFor(srcRoot)
                            .readValue(f.toFile(), ProjectionLedger.class);
                    ProjectionLedger remapped = remap(ledger, srcRoot, dstRoot);
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

    private static ProjectionLedger remap(ProjectionLedger ledger, Path srcRoot, Path dstRoot) {
        List<Binding> bindings = new ArrayList<>(ledger.bindings().size());
        for (Binding b : ledger.bindings()) {
            List<Projection> projections = new ArrayList<>(b.projections().size());
            for (Projection p : b.projections()) {
                projections.add(new Projection(
                        p.bindingId(),
                        remapPath(p.sourcePath(), srcRoot, dstRoot),
                        remapPath(p.destPath(), srcRoot, dstRoot),
                        p.kind(), p.backupOf(), p.boundHash()));
            }
            bindings.add(new Binding(b.bindingId(), b.unitName(), b.unitKind(), b.subElement(),
                    remapPath(b.targetRoot(), srcRoot, dstRoot),
                    b.conflictPolicy(), b.createdAt(), b.source(), projections));
        }
        return new ProjectionLedger(ledger.unitName(), bindings);
    }

    private static int reanchorChildHomes(Path srcRoot, Path dstRoot) {
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
                    registry.write(new ChildHomeRegistry.ChildHomeRecord(
                            raw.id(), parentHome, raw.childHome(), raw.harnessName(),
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

    /**
     * {@code registration.toml} is a small fixed template; only its
     * {@code manifest_path} can be a self-reference. Rewriting that one
     * assignment keeps every other recorded field (including
     * {@code project_root}, which is deliberately external) byte-identical.
     */
    private static int reanchorProjectRegistrations(Path srcRoot, Path dstRoot) {
        Path dir = dstRoot.resolve("projects");
        if (!Files.isDirectory(dir)) return 0;
        HomePaths dstPaths = HomePaths.of(dstRoot);
        int rewritten = 0;
        try (var stream = Files.list(dir)) {
            for (Path project : (Iterable<Path>) stream::iterator) {
                Path f = project.resolve("registration.toml");
                if (!Files.isRegularFile(f)) continue;
                try {
                    List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                    boolean changed = false;
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        String value = tomlStringValue(line, "manifest_path");
                        if (value == null) continue;
                        String resolved = HomePaths.isEncoded(value)
                                ? dstPaths.decodeToString(value)
                                : remapString(value, srcRoot, dstRoot);
                        String encoded = dstPaths.encode(resolved);
                        String next = "manifest_path = \"" + encoded.replace("\\", "\\\\")
                                .replace("\"", "\\\"") + "\"";
                        if (!next.equals(line)) {
                            lines.set(i, next);
                            changed = true;
                        }
                    }
                    if (changed) {
                        Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
                        rewritten++;
                    }
                } catch (IOException e) {
                    Log.warn("clone: could not re-anchor %s: %s", f, e.getMessage());
                }
            }
        } catch (IOException e) {
            Log.warn("clone: could not list %s: %s", dir, e.getMessage());
        }
        return rewritten;
    }

    /** The raw value of {@code key = "..."}, or null if this is another line. */
    private static String tomlStringValue(String line, String key) {
        String trimmed = line.trim();
        if (!trimmed.startsWith(key)) return null;
        int eq = trimmed.indexOf('=');
        if (eq < 0 || !trimmed.substring(0, eq).trim().equals(key)) return null;
        String rhs = trimmed.substring(eq + 1).trim();
        if (rhs.length() < 2 || rhs.charAt(0) != '"' || !rhs.endsWith("\"")) return null;
        return rhs.substring(1, rhs.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
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
     * it stops at the first shell/quoting delimiter. It feeds a warning, not
     * a gate.
     */
    private static List<String> missingDestReferences(byte[] content, Path dstRoot) {
        String text = new String(content, StandardCharsets.UTF_8);
        String root = dstRoot.toString();
        List<String> missing = new ArrayList<>();
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
            if (!Files.exists(Path.of(candidate)) && !missing.contains(candidate)) {
                missing.add(candidate);
            }
        }
        return missing;
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
     */
    public record Verification(List<Leak> leaks, List<String> contentReferences,
                               List<String> danglingLinks) {
        public Verification {
            leaks = leaks == null ? List.of() : List.copyOf(leaks);
            contentReferences = contentReferences == null ? List.of() : List.copyOf(contentReferences);
            danglingLinks = danglingLinks == null ? List.of() : List.copyOf(danglingLinks);
        }

        public boolean clean() { return leaks.isEmpty(); }
    }

    /**
     * Walk {@code dest} for anything still naming {@code source}. The
     * acceptance criterion for a clone.
     */
    public static Verification verify(Path source, Path dest, boolean strict) throws IOException {
        return verifyRoots(source.toAbsolutePath().normalize(),
                dest.toAbsolutePath().normalize(), strict);
    }

    private static Verification verifyRoots(Path srcRoot, Path dstRoot, boolean strict)
            throws IOException {
        byte[] needle = srcRoot.toString().getBytes(StandardCharsets.UTF_8);
        HomePaths srcPaths = HomePaths.of(srcRoot);
        List<Leak> leaks = new ArrayList<>();
        List<String> contentReferences = new ArrayList<>();
        List<String> dangling = new ArrayList<>();
        Files.walkFileTree(dstRoot, new SimpleWalker((file, rel) -> {
            if (Files.isSymbolicLink(file)) {
                Path target;
                try {
                    target = Files.readSymbolicLink(file);
                } catch (IOException e) {
                    return;
                }
                if (target.isAbsolute() && srcPaths.isInsideHome(target)) {
                    leaks.add(new Leak(rel, "SYMLINK_TARGET", target.toString()));
                } else if (!Files.exists(file)) {
                    dangling.add(rel + " -> " + target);
                }
                return;
            }
            if (!Files.isRegularFile(file)) return;
            if (!containsBytes(file, needle)) return;
            Surface surface = classify(rel);
            if (surface == Surface.CONTENT) {
                contentReferences.add(rel);
                if (strict) leaks.add(new Leak(rel, "CONTENT_REFERENCE", "authored unit content"));
            } else {
                leaks.add(new Leak(rel, "FILE_CONTENT", surface.name().toLowerCase()));
            }
        }));
        leaks.sort(java.util.Comparator.comparing(Leak::path));
        contentReferences.sort(String::compareTo);
        dangling.sort(String::compareTo);
        return new Verification(leaks, contentReferences, dangling);
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
        for (String segment : normalized.split("/")) {
            if (SKIPPED_SEGMENTS.contains(segment)) return true;
        }
        if (normalized.endsWith(".pyc")) return true;
        return slash < 0 && SKIPPED_ROOT_FILES.contains(normalized);
    }

    private static String rel(Path root, Path path) {
        return root.relativize(path).toString();
    }

    private static Path remapPath(Path path, Path srcRoot, Path dstRoot) {
        if (path == null) return null;
        Path abs = path.toAbsolutePath().normalize();
        if (!HomePaths.of(srcRoot).isInsideHome(abs)) return path;
        return dstRoot.resolve(srcRoot.relativize(abs));
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
