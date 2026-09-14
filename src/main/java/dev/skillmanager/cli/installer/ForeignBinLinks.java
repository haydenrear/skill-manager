package dev.skillmanager.cli.installer;

import dev.skillmanager.launch.LaunchEnv;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Takes every {@code bin/cli} link that resolves OUTSIDE this home out of the
 * way of a forked installer, puts back the ones it did not replace, and fails
 * the install if the files those links pointed at changed while it ran.
 *
 * <h2>Why (OHV-9, #367, DEF-OHV-011)</h2>
 *
 * <p>On 2026-09-14 the operator's root home's {@code bin/cli/computeq},
 * {@code helm-deploy} and {@code monitoring} were overwritten. A test project
 * home held those names as symlinks to the root's real shims — a sanctioned
 * parent-store mirror — and deploy-helm's skill-script ran in the project home
 * and wrote its launcher with {@code cat >"$launcher"}. {@code cat >} follows a
 * symlink. The bytes landed in the ROOT, and nothing here noticed:
 * {@code binStamps} stamps links without following them and
 * {@code reportFrozenShims} skips links.
 *
 * <p>No in-JVM check can see what a forked script writes, and no refusal can
 * un-write bytes. So the only way to make the write IMPOSSIBLE is to take the
 * link away before the fork: with the entry gone, {@code cat >} creates this
 * home's own file, which is the file the install was supposed to produce.
 *
 * <h2>The contract</h2>
 *
 * <ol>
 *   <li>{@link #detach}: every entry of {@code bin/cli} that is a symlink whose
 *       chain resolves outside this home's real root is recorded (name, raw
 *       target, resolved target, a stat of the resolved file) and unlinked. A
 *       link resolving INSIDE this home — {@code ../../venvs/x/bin/x} — is left
 *       alone. The link is deleted, never followed.</li>
 *   <li>The caller runs the installer, and calls {@link #restore} in a
 *       {@code finally} so a failing or throwing installer still gets the links
 *       back.</li>
 *   <li>{@link #restore}: for each detached name —
 *     <ul>
 *       <li>absent: the installer did not produce it; the link is recreated with
 *           its exact raw target;</li>
 *       <li>a symlink resolving outside this home again: the installer linked
 *           back out, which is a foreign write — recorded, and the original link
 *           is put back in its place;</li>
 *       <li>anything else (a real file, a link inside this home): the
 *           installer produced this home's own artifact; it stays.</li>
 *     </ul>
 *     Then the resolved target is stat'ed again (size, mtime, file key — its
 *     content is never read); a difference is recorded as a foreign write.</li>
 *   <li>{@link #requireNoForeignWrite}: throws naming the other home and the
 *       path when anything was recorded.</li>
 * </ol>
 *
 * <h2>What this does not cover</h2>
 *
 * <ul>
 *   <li>A write through any path that is not a {@code bin/cli} entry — an
 *       installer that spells the other home's path outright. The stat check
 *       catches that only for the detached targets.</li>
 *   <li>{@code bin/cli} itself being a link: that is refused before the fork by
 *       {@code InstallerRegistry.installOne}'s container check, and re-asserted
 *       here.</li>
 * </ul>
 */
final class ForeignBinLinks {

    /** One detached link. {@code before} is null when the target did not exist. */
    record Detached(String name, Path rawTarget, Path resolved, Path otherHome, Stamp before) {}

    /** What is compared, and nothing else: content is never read. */
    record Stamp(long size, long mtimeNanos, Object fileKey) {}

    private final Path binDir;
    private final Path homeRoot;
    private final String who;
    private final List<Detached> detached;
    private final List<String> foreignWrites = new ArrayList<>();
    private boolean restored;

    private ForeignBinLinks(Path binDir, Path homeRoot, String who, List<Detached> detached) {
        this.binDir = binDir;
        this.homeRoot = homeRoot;
        this.who = who;
        this.detached = detached;
    }

    /**
     * Unlink every {@code bin/cli} entry that resolves outside {@code store}'s
     * home. On any failure to unlink, puts back what was already unlinked and
     * throws: running the installer with a link still in place is the defect.
     */
    static ForeignBinLinks detach(SkillStore store, String who) throws IOException {
        Path bin = store.cliBinDir();
        Path root = store.root();
        List<Detached> out = new ArrayList<>();
        ForeignBinLinks links = new ForeignBinLinks(bin, root, who, out);
        if (!Files.isDirectory(bin, LinkOption.NOFOLLOW_LINKS)) return links;
        dev.skillmanager.store.WriteConfinement.requireContainerInside(bin, root,
                "the bin/cli " + who + " is about to run against");
        Path realHome = Fs.realOrNormalized(root);
        List<Path> entries;
        try (var stream = Files.list(bin)) {
            entries = stream.sorted().toList();
        }
        for (Path entry : entries) {
            if (!Files.isSymbolicLink(entry)) continue;
            Path resolved = resolveChain(entry);
            if (resolved.startsWith(realHome)) continue;
            Path raw = Files.readSymbolicLink(entry);
            Detached d = new Detached(entry.getFileName().toString(), raw, resolved,
                    homeOwning(resolved), stat(resolved));
            try {
                Files.delete(entry);
            } catch (IOException cannot) {
                links.restore();
                throw new IOException(who + ": refusing to run — bin/cli/" + d.name()
                        + " is a link to " + resolved + describeHome(d.otherHome())
                        + " and could not be detached (" + cannot.getMessage()
                        + "); the installer would write through it", cannot);
            }
            out.add(d);
            Log.info("cli: %s — detached bin/cli/%s (a link to %s%s) while it runs, so it cannot "
                    + "write through it", who, d.name(), resolved, describeHome(d.otherHome()));
        }
        return links;
    }

    /** Names detached, in order. For tests and logs. */
    List<String> detachedNames() {
        return detached.stream().map(Detached::name).toList();
    }

    /**
     * Put back every detached link the installer did not replace, and record
     * every foreign write. Never throws; idempotent. Call it in a finally.
     */
    void restore() {
        if (restored) return;
        restored = true;
        Path realHome = Fs.realOrNormalized(homeRoot);
        for (Detached d : detached) {
            Path entry = binDir.resolve(d.name());
            try {
                if (!Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createSymbolicLink(entry, d.rawTarget());
                } else if (Files.isSymbolicLink(entry) && !resolveChain(entry).startsWith(realHome)) {
                    Path now = resolveChain(entry);
                    Path home = homeOwning(now);
                    foreignWrites.add("it re-created bin/cli/" + d.name() + " as a link to " + now
                            + describeHome(home));
                    Files.delete(entry);
                    Files.createSymbolicLink(entry, d.rawTarget());
                }
                // Otherwise the installer produced this home's own entry: keep it.
            } catch (IOException e) {
                Log.warn("cli: %s — could not put back bin/cli/%s -> %s (%s); the tool is missing "
                        + "from this home", who, d.name(), d.rawTarget(), e.getMessage());
            }
            Stamp after = stat(d.resolved());
            if (!Objects.equals(d.before(), after)) {
                foreignWrites.add("the file bin/cli/" + d.name() + " linked to, " + d.resolved()
                        + describeHome(d.otherHome()) + ", changed while it ran ("
                        + describe(d.before()) + " -> " + describe(after) + ")");
            }
        }
    }

    /** Throw when {@link #restore} recorded a write outside this home. */
    void requireNoForeignWrite() throws IOException {
        restore();
        if (foreignWrites.isEmpty()) return;
        throw new IOException(who + " wrote outside the home at " + homeRoot + ": "
                + String.join("; ", foreignWrites)
                + ". The install is refused. bin/cli links into another home are detached while an "
                + "installer runs precisely so it cannot write through them (skill-manager#367).");
    }

    // ------------------------------------------- in-JVM placement (npm, brew, tar)

    /**
     * Make {@code link} a symlink to {@code source}, falling back to a copy on a
     * filesystem that refuses links. The existing entry is DELETED first, and a
     * delete removes a link rather than its target, so an entry that links into
     * another home is replaced here and that home is never written. This is why
     * {@link NpmBackend} and {@link BrewBackend} need no detach: the only thing
     * they write into {@code bin/cli} goes through here (their package managers
     * write their own prefixes, not {@code bin/cli}). Pinned by
     * {@code BinCliWritersDoNotFollowLinksTest}.
     */
    static void placeLink(Path link, Path source) throws IOException {
        Files.deleteIfExists(link);
        try {
            Files.createSymbolicLink(link, source);
        } catch (UnsupportedOperationException | IOException e) {
            placeCopy(source, link);
        }
    }

    /**
     * Copy {@code source} to {@code link}, deleting the entry first so a link
     * into another home is replaced rather than written through. {@link TarBackend}
     * places its binary through here with {@code REPLACE_EXISTING} and
     * {@code COPY_ATTRIBUTES}.
     */
    static void placeCopy(Path source, Path link, java.nio.file.CopyOption... options) throws IOException {
        Files.deleteIfExists(link);
        Files.copy(source, link, options);
        Fs.makeExecutable(link);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Where a link chain ends, fully resolved where it exists and resolved as
     * far as it does where it dangles. Each hop is read, never followed by the
     * filesystem, so a dangling link into another home is still seen as one —
     * {@code Fs.realOrNormalized(entry)} alone would stop at {@code bin/cli} and
     * call it inside.
     */
    static Path resolveChain(Path entry) {
        Path cur = entry.toAbsolutePath().normalize();
        for (int hops = 0; hops < 40 && Files.isSymbolicLink(cur); hops++) {
            try {
                Path target = Files.readSymbolicLink(cur);
                Path parent = Fs.realOrNormalized(cur.getParent());
                cur = parent.resolve(target).normalize();
            } catch (IOException unreadable) {
                break;
            }
        }
        return Fs.realOrNormalized(cur);
    }

    /** The Skill Manager home {@code resolved} lies in, or null. The file need not exist. */
    static Path homeOwning(Path resolved) {
        for (Path p = resolved.getParent(); p != null; p = p.getParent()) {
            if (LaunchEnv.looksLikeStoreRoot(p)) return p;
        }
        return null;
    }

    private static String describeHome(Path home) {
        return home == null ? ", outside this home" : ", in the home at " + home;
    }

    /** Size, mtime, file key of the file itself; null when it does not exist. */
    static Stamp stat(Path path) {
        try {
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return new Stamp(a.size(),
                    a.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS), a.fileKey());
        } catch (NoSuchFileException absent) {
            return null;
        } catch (IOException unreadable) {
            // Unreadable reads as a distinct value, so going from readable to
            // unreadable (or back) is a change rather than a silent pass.
            return new Stamp(-1, -1, "unreadable: " + unreadable.getClass().getSimpleName());
        }
    }

    private static String describe(Stamp s) {
        return s == null ? "absent" : "size " + s.size() + ", mtime " + s.mtimeNanos() + "ns";
    }
}
