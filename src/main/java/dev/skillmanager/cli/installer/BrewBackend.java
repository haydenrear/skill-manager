package dev.skillmanager.cli.installer;

import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.lock.Fingerprints;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Log;
import dev.skillmanager.util.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Install via Homebrew. brew owns its own prefix so we can't relocate the
 * package itself, but after install we symlink every binary from
 * {@code $(brew --prefix <pkg>)/bin/*} into our {@code bin/cli} so PATH stays
 * uniform across backends.
 */
public final class BrewBackend implements InstallerBackend {

    @Override public String id() { return "brew"; }

    @Override
    public boolean available() {
        Platform.Os os = Platform.currentOs();
        // The process's own PATH, deliberately: this asks whether `brew` can be
        // run at all, not whether some dep is provisioned. See
        // CliPresence#onProcessPath.
        return (os == Platform.Os.DARWIN || os == Platform.Os.LINUX)
                && CliPresence.onProcessPath("brew") != null;
    }

    @Override
    public InstallOutcome install(CliDependency dep, SkillStore store, String skillName)
            throws IOException {
        // A state, not an event: nothing was done, and this is true on every
        // run forever. The count reaches the console; the name reaches the run
        // log. See InstallOutcome. brew is the backend where the "the system
        // may already provide this" reading of on_path is the common one — jq
        // from the distro, git from Xcode — and CliPresence keeps answering it,
        // from the directories that are not some Skill Manager home's.
        if (alreadyProvided(dep, store)) return InstallOutcome.ALREADY_PRESENT;
        String pkg = dep.packageRef();
        if (pkg == null || pkg.isBlank()) throw new IOException("brew: spec missing package name (brew:<package>)");

        Shell.must(List.of("brew", "install", pkg));

        String prefix = Shell.capture(List.of("brew", "--prefix", pkg));
        if (prefix == null || prefix.isBlank()) {
            Log.warn("cli: brew install %s succeeded but --prefix returned empty", pkg);
            return InstallOutcome.INSTALLED;
        }
        Path brewBin = Path.of(prefix.trim()).resolve("bin");
        if (!Files.isDirectory(brewBin)) {
            Log.warn("cli: no bin/ under %s", brewBin);
            return InstallOutcome.INSTALLED;
        }
        Fs.ensureDir(store.cliBinDir());
        try (Stream<Path> entries = Files.list(brewBin)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (!Files.isExecutable(entry)) continue;
                Path link = store.cliBinDir().resolve(entry.getFileName().toString());
                if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(link)) {
                    Files.delete(link);
                }
                try {
                    Files.createSymbolicLink(link, entry);
                } catch (UnsupportedOperationException | IOException e) {
                    // Fall back to copy on filesystems that reject symlinks.
                    Files.copy(entry, link);
                    Fs.makeExecutable(link);
                }
            }
        }
        Log.ok("cli: installed brew %s; linked bins into %s", pkg, store.cliBinDir());
        return InstallOutcome.INSTALLED;
    }

    /**
     * {@code brew-v1} over the declared formula and, when brew holds it, the
     * version directory brew resolved it to.
     *
     * <p>A brew spec never carries a version — {@code RequestedVersion} records
     * {@code null} for every one of them, by design, because "brew tracks its
     * own formula version". That leaves the declared half of this digest
     * constant forever, so for this backend the resolved half is the only part
     * that can ever move, and it is what makes {@code brew:helm} distinguishable
     * from {@code brew:helm} one {@code brew upgrade} later.
     *
     * <p>It is read from the filesystem — {@code <prefix>/opt/<formula>} is a
     * symlink into {@code Cellar/<formula>/<version>}, and {@code Caskroom/} is
     * the same shape for casks — rather than by running {@code brew --prefix}.
     * A fingerprint is computed once per declared dep on every install, sync and
     * upgrade pass; spawning brew that many times to learn a version that is
     * spelled in a directory name would make a read cost a subprocess, and the
     * subprocess is what would fail on a host where brew is present but slow or
     * locked.
     *
     * <p>When brew is not on this host, or does not hold the formula, the digest
     * covers the declared formula alone and the basis says so. That is the
     * common shape for a formula the machine already provides some other way —
     * {@code brew:claude} on this operator's home — and it is a real limit, not
     * a hidden one: the artifact is outside this home, and its version is not
     * this home's input.
     */
    @Override
    public Fingerprint fingerprint(CliDependency dep, SkillStore store, String unitName) {
        String pkg = dep.packageRef();
        if (pkg == null || pkg.isBlank()) {
            return Fingerprint.gap("brew " + dep.name() + " has no package in its spec "
                    + "(expected brew:<formula>), so nothing declares what would be installed");
        }
        String resolved = resolvedVersion(pkg);
        String digest = Fingerprints.scheme("brew-v1")
                .field("formula", pkg)
                .field("resolved", resolved)
                .hex();
        return resolved != null
                ? Fingerprint.resolved(digest,
                        "declared formula + version " + resolved + " brew resolved it to")
                : Fingerprint.declared(digest,
                        "declared formula only — brew on this host holds no cellar entry for "
                        + pkg + ", so the installed version is not observable; a brew spec "
                        + "declares no version of its own, so this digest cannot move");
    }

    /** {@link #versionInPrefix} against the brew this process can see. */
    private static String resolvedVersion(String formula) {
        Path brew = CliPresence.onProcessPath("brew");
        if (brew == null) return null;
        Path bin = brew.toAbsolutePath().normalize().getParent();          // <prefix>/bin
        Path prefix = bin == null ? null : bin.getParent();
        return prefix == null ? null : versionInPrefix(prefix, formula);
    }

    /**
     * The version segment of {@code <prefix>/opt/<formula>}'s cellar target, or
     * of the single {@code Caskroom/<formula>/<version>} entry for a cask.
     *
     * <p>Split out from {@link #resolvedVersion} so it can be driven against a
     * synthetic prefix. It is the only load-bearing code in this backend's
     * fingerprint — a brew spec declares no version of its own, so this is the
     * ONLY thing that can ever move a {@code brew-v1} digest — and testing it
     * through the process PATH would mean testing whatever brew the host
     * happens to have, which is a fact about the machine and not about this.
     *
     * <p>Returns null on an ambiguous cask (more than one version directory)
     * deliberately: two answers is not an answer, and a
     * {@link Fingerprint.Kind#DECLARED} digest that says so beats a
     * {@link Fingerprint.Kind#RESOLVED} one built on a coin flip.
     */
    static String versionInPrefix(Path prefix, String formula) {
        if (prefix == null || formula == null || formula.isBlank()) return null;
        Path opt = prefix.resolve("opt").resolve(formula);
        if (Files.isSymbolicLink(opt)) {
            try {
                Path leaf = Files.readSymbolicLink(opt).getFileName();
                if (leaf != null && !leaf.toString().isBlank()) return leaf.toString();
            } catch (IOException unreadable) {
                // fall through to the cask layout
            }
        }
        Path cask = prefix.resolve("Caskroom").resolve(formula);
        if (Files.isDirectory(cask)) {
            try (Stream<Path> versions = Files.list(cask)) {
                List<Path> dirs = versions.filter(Files::isDirectory).sorted().toList();
                if (dirs.size() == 1) return dirs.get(0).getFileName().toString();
            } catch (IOException unreadable) {
                return null;
            }
        }
        return null;
    }
}
