package dev.skillmanager.project;

import dev.skillmanager.model.Coord;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.resolve.Git;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.SubprocessGit;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Resolves project development source checkouts declared in {@code [[libs]]}.
 */
public final class ProjectLibResolver {

    private final SkillStore store;
    private final Git git;

    public ProjectLibResolver(SkillStore store) {
        this(store, new SubprocessGit());
    }

    public ProjectLibResolver(SkillStore store, Git git) {
        this.store = store;
        this.git = git;
    }

    public record Result(
            SkillProjectLock lock,
            List<SkillProjectLock.LibCheckout> libs,
            Path libsDir
    ) {}

    public Result resolve(SkillProject project) throws IOException {
        if (project == null) throw new IllegalArgumentException("project must not be null");
        SkillProjectRegistration registration = new SkillProjectRegistry(store).register(project);
        SkillProjectLockStore locks = new SkillProjectLockStore(store);
        SkillProjectLock previous = locks.read(project.registryName()).orElseGet(() -> new SkillProjectLock(
                project.registryName(),
                project.activeProfile(),
                registration.manifestFile(),
                Instant.now().toString(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        Path libsDir = project.projectRoot().resolve("libs");
        Fs.ensureDir(libsDir);
        ensureGitignored(project.projectRoot());

        List<SkillProjectLock.LibCheckout> rows = new ArrayList<>();
        for (SkillProject.ProjectLib lib : project.libs()) {
            rows.add(resolveOne(libsDir, lib));
        }
        rows.sort(Comparator.comparing(SkillProjectLock.LibCheckout::name, String.CASE_INSENSITIVE_ORDER));

        SkillProjectLock next = new SkillProjectLock(
                previous.projectName(),
                previous.profile(),
                previous.manifestFile(),
                previous.resolvedAt(),
                previous.resolvedUnits(),
                previous.bindings(),
                previous.envs(),
                rows);
        locks.write(next);
        return new Result(next, rows, libsDir);
    }

    private SkillProjectLock.LibCheckout resolveOne(Path libsDir, SkillProject.ProjectLib lib)
            throws IOException {
        Coord.DirectGit coord = directGit(lib);
        String ref = firstNonBlank(lib.ref(), coord.ref());
        Path checkout = checkoutPath(libsDir, lib.name());
        String sha;
        if (Files.exists(checkout)) {
            sha = reconcileExistingCheckout(checkout, coord.url(), ref);
        } else {
            sha = cloneInto(checkout, coord.url(), ref);
        }
        if (sha == null || sha.isBlank()) {
            throw new IOException("could not resolve HEAD for project lib " + lib.name());
        }
        if (lib.sha() != null && !lib.sha().isBlank() && !lib.sha().equals(sha)) {
            throw new IOException("project lib " + lib.name() + " resolved " + sha
                    + " but manifest requested " + lib.sha());
        }
        return new SkillProjectLock.LibCheckout(
                lib.name(),
                lib.source(),
                coord.url(),
                ref,
                lib.sha(),
                sha,
                checkout.toString(),
                Instant.now().toString());
    }

    private String cloneInto(Path checkout, String url, String ref) throws IOException {
        Path parent = checkout.getParent();
        Fs.ensureDir(parent);
        Path tmp = parent.resolve(".tmp-" + checkout.getFileName() + "-" + UUID.randomUUID());
        try {
            Path tree = git.cloneAt(url, ref, tmp);
            String sha = git.headHash(tree);
            if (sha == null || sha.isBlank()) {
                throw new IOException("cloned lib has no readable HEAD: " + tree);
            }
            if (Files.exists(checkout)) {
                throw new IOException("project lib checkout appeared during clone: " + checkout);
            }
            try {
                Files.move(tree, checkout, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tree, checkout);
            }
            return sha;
        } finally {
            Fs.deleteRecursive(tmp);
        }
    }

    private String reconcileExistingCheckout(Path checkout, String url, String ref) throws IOException {
        if (!Files.isDirectory(checkout)) {
            throw new IOException("project lib path exists but is not a directory: " + checkout);
        }
        String sha = git.headHash(checkout);
        if (sha == null || sha.isBlank()) {
            throw new IOException("project lib checkout is not a git repository: " + checkout);
        }
        String origin = GitOps.originUrl(checkout);
        if (origin == null || origin.isBlank()) {
            throw new IOException("project lib checkout " + checkout
                    + " has no origin; refusing to satisfy manifest source " + url);
        }
        if (origin != null && !origin.equals(url)) {
            throw new IOException("project lib checkout " + checkout + " has origin "
                    + origin + " but manifest source resolves to " + url);
        }
        if (ref != null && !ref.isBlank()) {
            if (GitOps.hasWorktreeChanges(checkout)) {
                throw new IOException("project lib checkout has local changes; refusing to switch ref: "
                        + checkout);
            }
            String fetched = GitOps.fetchRef(checkout, "origin", ref);
            if (fetched == null || fetched.isBlank()) {
                throw new IOException("could not fetch ref " + ref + " for project lib checkout " + checkout);
            }
            if (!GitOps.resetHard(checkout, "FETCH_HEAD")) {
                throw new IOException("could not reset project lib checkout to ref " + ref + ": " + checkout);
            }
            sha = git.headHash(checkout);
            if (sha == null || sha.isBlank()) {
                throw new IOException("project lib checkout has no readable HEAD after ref switch: " + checkout);
            }
        }
        return sha;
    }

    private static Path checkoutPath(Path libsDir, String name) throws IOException {
        if (name == null || name.isBlank()
                || name.equals(".")
                || name.equals("..")
                || name.contains("/")
                || name.contains("\\")
                || Path.of(name).isAbsolute()) {
            throw new IOException("project lib name must be a safe single path segment: " + name);
        }
        Path checkout = libsDir.resolve(name).normalize();
        Path normalizedLibs = libsDir.toAbsolutePath().normalize();
        Path normalizedCheckout = checkout.toAbsolutePath().normalize();
        if (!normalizedCheckout.startsWith(normalizedLibs)) {
            throw new IOException("project lib checkout escapes libs directory: " + name);
        }
        return checkout;
    }

    private static Coord.DirectGit directGit(SkillProject.ProjectLib lib) throws IOException {
        Coord coord = Coord.parse(lib.source());
        if (coord instanceof Coord.DirectGit git) return git;
        throw new IOException("project lib " + lib.name()
                + " must use github: or git+ source, got " + lib.source());
    }

    private static void ensureGitignored(Path projectRoot) throws IOException {
        Path gitignore = projectRoot.resolve(".gitignore");
        String existing = Files.isRegularFile(gitignore) ? Files.readString(gitignore) : "";
        boolean alreadyIgnored = existing.lines()
                .map(String::trim)
                .anyMatch(line -> line.equals("libs/") || line.equals("/libs/"));
        if (alreadyIgnored) return;
        String prefix = existing.isBlank() || existing.endsWith("\n") ? existing : existing + "\n";
        Files.writeString(gitignore, prefix + "libs/\n");
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
