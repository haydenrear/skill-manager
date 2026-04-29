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
                Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    public static void ensureDir(Path p) throws IOException {
        if (!Files.exists(p)) Files.createDirectories(p);
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
