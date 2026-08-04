package dev.skillmanager.shared.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

public final class Fs {

    private Fs() {}

    public static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(p)) {
            Files.delete(p);
            return;
        }
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Copy a tree, recreating symlinks rather than dereferencing them.
     *
     * <h2>COPY_ATTRIBUTES is load-bearing for COST, not for tidiness</h2>
     *
     * <p>On APFS, {@code Files.copy} with {@link StandardCopyOption#COPY_ATTRIBUTES}
     * takes the JDK's {@code clonefile(2)} path and the destination shares the
     * source's blocks; without the flag it falls back to a byte-for-byte read
     * and write. Measured on this host against a 64 MB file, on a dedicated
     * APFS volume, by free-space delta: <b>with the flag 0.01 MB, without it
     * 67.11 MB</b>. A real {@code home clone} of a 189 MB home consumes
     * <b>7.22 MB (3.8%)</b>.
     *
     * <p>The whole three-tier home model — a copy per repository and a copy per
     * ticket worktree — is affordable only because of that. Nothing in this
     * codebase said so before: {@code grep -rni 'clonefile|copy-on-write'}
     * across every source file returned <b>zero hits</b>, and no test asserted
     * it. So the flag looked redundant beside {@code REPLACE_EXISTING}, and
     * deleting it turned a three-second 7 MB clone into a nine-hundred-megabyte
     * copy <b>with nothing failing</b>.
     *
     * <p>It is asserted now: {@code test_graph} node
     * {@code home.clone.costs.far.less.than.a.copy} measures free space on a
     * dedicated APFS volume before and after, with an idle negative control and
     * a known-size positive control, and fails if a clone of an N-MB tree
     * consumes anything near N. Deleting the flag from the call below is the
     * node's documented mutation: the cost assertion fails and the digest
     * assertion stays green, which is the proof the cost assertion carries
     * weight rather than riding along on the correctness one.
     *
     * <p>Do not measure this with {@code du}. It attributes shared blocks to
     * both files and cannot see sharing at all — measured 197.1 MB reported
     * against 7.14 MB real.
     *
     * <p>The same flag, for the same reason, is at
     * {@link dev.skillmanager.store.HomeCloner} and at three sites in
     * {@code ChildHomeMaterializer}.
     */
    public static void copyRecursive(Path src, Path dst) throws IOException {
        // Walk with FOLLOW_LINKS = off so symbolic links (e.g. Node's
        // bin/npm → ../lib/node_modules/npm/bin/npm-cli.js) are recreated
        // at the destination rather than dereferenced and copied as files.
        Files.walkFileTree(src, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(d)) {
                    Path target = dst.resolve(src.relativize(d));
                    recreateSymlink(d, target);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path target = dst.resolve(src.relativize(d));
                if (!Files.exists(target)) Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                Path target = dst.resolve(src.relativize(f));
                if (Files.isSymbolicLink(f)) {
                    recreateSymlink(f, target);
                } else {
                    // COPY_ATTRIBUTES is what makes this an APFS clone rather
                    // than a byte copy. It is not decoration — see the method
                    // javadoc, and home.clone.costs.far.less.than.a.copy, whose
                    // mutation is deleting it from exactly this line.
                    Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void recreateSymlink(Path src, Path target) throws IOException {
        Path linkTarget = Files.readSymbolicLink(src);
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(target);
        }
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        try {
            Files.createSymbolicLink(target, linkTarget);
        } catch (UnsupportedOperationException | IOException e) {
            // Filesystem rejected symlinks — fall back to copying the resolved content.
            if (Files.isDirectory(src)) {
                copyRecursive(src, target);
            } else {
                // COPY_ATTRIBUTES: the APFS clone path, same as copyRecursive.
                // This branch dereferences a link, so it is the branch most
                // likely to copy something large (a toolchain binary, a venv);
                // it is also the branch a filesystem without symlink support
                // takes for EVERY entry, where the difference between a clone
                // and a copy is the whole cost of a home.
                Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    public static void ensureDir(Path p) throws IOException {
        if (!Files.exists(p)) Files.createDirectories(p);
    }

    /**
     * {@code path} with every symlink in it resolved, whether or not the leaf
     * exists yet.
     *
     * <h2>Why this is here rather than spelled again at each call site</h2>
     *
     * <p>Because a comparison that can be defeated by a spelling is a
     * comparison that will be, and this codebase has now been defeated by one
     * five times: the {@code [[vendored]]} validator, {@link
     * dev.skillmanager.store.HomePaths}, the clone independence check, the
     * project same-home guard, and — the reason this method exists — the launch
     * PATH sanitizer, where the spelling was a SYMLINK rather than {@code /var}
     * vs {@code /private/var}.
     *
     * <p>{@code LaunchEnv.isForeignHomeBin} walked the lexically normalized
     * path's ancestors, so a foreign home's {@code bin/cli} reached through a
     * symlink had no home-shaped ancestor to find and was invisible to it.
     * Measured: {@code ln -s <foreign>/.skill-manager/bin/cli /tmp/symlinked},
     * then {@code PATH=/tmp/symlinked:… sync} reported "already provided by the
     * system (/tmp/symlinked/hello), outside any Skill Manager home" about a
     * directory that IS a home's {@code bin/cli} — so the install was skipped,
     * the shim stayed dangling, and {@code home verify}'s remedy again could not
     * clear what it named. The same entry survived launch PATH sanitizing, which
     * put a foreign home's tools ahead of the active home's.
     *
     * <p>A plain {@link Path#toRealPath()} throws when the leaf does not exist,
     * which is the normal case for a PATH entry naming a directory nobody
     * created and for a child home on its first resolve. So: resolve the deepest
     * ancestor that does exist and re-append the rest.
     *
     * <p>Two private copies of this predate the method and are deliberately left
     * alone — {@code ProjectChildHomeScaffolder} and
     * {@code ChildHomeMaterializer}. Both are load-bearing for the child-home
     * graphs and neither is implicated in anything here; folding them in is a
     * separate change with its own blast radius.
     */
    public static Path realOrNormalized(Path path) {
        if (path == null) return null;
        Path normalized = path.toAbsolutePath().normalize();
        for (Path existing = normalized; existing != null; existing = existing.getParent()) {
            try {
                Path real = existing.toRealPath();
                Path tail = existing.relativize(normalized);
                return tail.toString().isEmpty() ? real : real.resolve(tail);
            } catch (IOException notThere) {
                // keep walking up
            }
        }
        return normalized;
    }

    public static void makeExecutable(Path p) throws IOException {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException ignored) {
            p.toFile().setExecutable(true, false);
        }
    }
}
