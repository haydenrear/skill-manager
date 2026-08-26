package dev.skillmanager.cli.installer;

import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.lock.Fingerprints;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Archives;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Log;
import dev.skillmanager.util.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Download + extract a tarball/zip/raw binary into {@code bin/cli/<name>}. */
public final class TarBackend implements InstallerBackend {

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();

    @Override public String id() { return "tar"; }

    @Override public boolean available() { return true; }

    @Override
    public InstallOutcome install(CliDependency dep, SkillStore store, String skillName)
            throws IOException {
        // One question, not two. The second check here was already home-scoped
        // — `Files.exists(bin/cli/<name>)` — and it was the correct half; the
        // PATH check above it was not, and being first it decided. Both are now
        // CliPresence, which asks the home-scoped question first and PATH only
        // about directories no home owns.
        if (alreadyProvided(dep, store)) return InstallOutcome.ALREADY_PRESENT;
        Fs.ensureDir(store.cliBinDir());
        Path link = store.cliBinDir().resolve(dep.name());
        CliDependency.InstallTarget target = pickTarget(dep);
        if (target == null || target.url() == null) {
            Log.warn("cli: no install target for %s on %s", dep.name(), Platform.currentKey());
            return InstallOutcome.SKIPPED;
        }

        Log.step("cli: downloading %s from %s", dep.name(), target.url());
        Fs.ensureDir(store.cacheDir());
        Path download = Files.createTempFile(store.cacheDir(), dep.name() + "-", suffix(target));
        try {
            download(target.url(), download);
            if (target.sha256() != null) verifySha256(download, target.sha256());
            Path extractDir = store.cacheDir().resolve("cli-" + dep.name());
            if (Files.exists(extractDir)) Fs.deleteRecursive(extractDir);
            Fs.ensureDir(extractDir);

            Path binary = extractOrCopy(download, extractDir, target);
            if (binary == null) {
                Log.warn("cli: could not locate binary for %s", dep.name());
                return InstallOutcome.SKIPPED;
            }
            Fs.makeExecutable(binary);
            // deleteIfExists rather than `if (Files.exists(link)) delete`,
            // because Files.exists FOLLOWS the link and a dangling shim is now
            // the main reason this backend reaches here at all.
            //
            // BELT AND BRACES, not a repair — do not read a fix into it. The
            // REPLACE_EXISTING below already replaces a dangling link, and
            // reverting this line leaves the whole suite green (measured). It
            // stays because unlink-then-write is the order that reads correctly
            // at a glance, not because the previous spelling lost anything.
            Files.deleteIfExists(link);
            Files.copy(binary, link, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Fs.makeExecutable(link);
            Log.ok("cli: installed %s -> %s", dep.name(), link);
            return InstallOutcome.INSTALLED;
        } finally {
            Files.deleteIfExists(download);
        }
    }

    /**
     * The declared download plus, when this home holds it, the bytes actually
     * installed.
     *
     * <h2>Why {@code tar-v1} could not move, and why that was structural</h2>
     *
     * <p>{@code tar-v1} hashes the platform key that selected the target, the
     * URL, the archive kind, the declared binary name, the extract list and the
     * declared {@code sha256} — and reads <b>nothing from disk</b>. ARTI-04
     * measured the consequence: one identical digest across three different
     * installed {@code bin/cli/rg} payloads. Overwrite the binary, truncate it,
     * let a clone dangle it, and every {@code tar-v1} digest is unchanged. With
     * no {@code sha256} declared — the common case, since {@code
     * policy.require_hash} is opt-in — it is a hash of a URL string and cannot
     * move at all. The class was capped at {@code DECLARED} by construction and
     * no amount of installing would have lifted it.
     *
     * <h2>{@code tar-v2}, and why hashing the OUTPUT is not weaker here</h2>
     *
     * <p>Hashing an artifact's output is normally the weaker, different question
     * this epic grades below {@code CONTENT} — {@code boundHash} and doc-import
     * drift both answer "do the bytes I wrote still look like the bytes I wrote"
     * rather than "did my inputs move". {@code tar} is the one backend where the
     * two coincide, because a tarball's identity <i>is</i> its bytes: the input
     * this dep declares is a URL naming a file, and the output is that same file
     * at {@code bin/cli/<name>}. It is also uniquely cheap — a single file at a
     * path this backend chose, not a tree — and the streaming SHA-256 is already
     * in this class for {@link #verifySha256}.
     *
     * <p><b>The installed digest is its own field in its own scheme and is never
     * folded into the declared one.</b> That is the whole caveat and it is not a
     * formality: an installed-file digest detects <i>local tampering and clone
     * damage</i>, and it cannot detect <i>upstream movement</i> — a maintainer
     * re-publishing different bytes at the same URL is invisible to it exactly
     * as it is to {@code tar-v1}, because nothing here re-fetches. Writing that
     * observation into the declared {@code sha256} field, or letting the
     * {@code tar-v1} value silently start covering disk bytes, would reproduce
     * one level down the conflation this fingerprint exists to prevent. So:
     * a distinct scheme name, a distinct {@code installed} field inside it, and
     * a {@link Fingerprint#basis} that names what each half does and does not
     * see.
     *
     * <h2>Two schemes, two grades, and the fallback is not a failure</h2>
     *
     * <p>Same shape as {@code pip-v1}: the resolved half is present when this
     * home holds the artifact, and absent when it does not. A dep
     * {@link CliPresence} found already provided by the machine writes nothing
     * into {@code bin/cli/}, and a clone that dropped the shim has nothing to
     * read — both fall back to {@code tar-v1} / {@link Fingerprint.Kind#DECLARED}
     * and say which case they are in, rather than reporting a gap for an install
     * that succeeded.
     *
     * <p>The platform key is part of both digests because the artifact genuinely
     * differs per platform. Two homes on the same machine therefore agree; two
     * homes on different architectures deliberately do not, and comparing them
     * is not a question this scheme answers.
     */
    @Override
    public Fingerprint fingerprint(CliDependency dep, SkillStore store, String unitName) {
        Map.Entry<String, CliDependency.InstallTarget> chosen = pickTargetEntry(dep);
        if (chosen == null || chosen.getValue() == null) {
            return Fingerprint.gap("tar " + dep.name() + " declares no install target for "
                    + Platform.currentKey() + " (and no 'any'), so nothing describes what "
                    + "would be downloaded");
        }
        CliDependency.InstallTarget target = chosen.getValue();
        if (target.url() == null || target.url().isBlank()) {
            return Fingerprint.gap("tar " + dep.name() + "'s " + chosen.getKey()
                    + " install target declares no url");
        }

        Path installed = installedFile(dep, store);
        if (installed != null) {
            try {
                String digest = declaredFields(Fingerprints.scheme("tar-v2"), chosen, target)
                        // The single file this backend copied, by the path it
                        // copied it to. Fingerprints.file writes the key, then
                        // every byte, then a terminator — the same encoding
                        // skill-script's tree digest uses, so an empty binary
                        // and a missing one cannot hash alike.
                        .file("installed", "bin/cli/" + dep.name(), installed)
                        .hex();
                return Fingerprint.resolved(digest,
                        "declared url" + (target.sha256() != null ? " + declared sha256" : "")
                                + " + sha256 of the installed bin/cli/" + dep.name()
                                + " (the installed half detects local tampering and clone "
                                + "damage; neither half detects a re-publish of different "
                                + "bytes at the same url)");
            } catch (IOException unreadable) {
                // The file was there a moment ago and could not be read now.
                // Fall through to the declared digest rather than claiming a
                // resolved one over bytes nothing managed to hash.
                Log.detail("cli: tar %s — could not hash the installed binary (%s); "
                        + "recording the declared fingerprint only",
                        dep.name(), unreadable.getMessage());
            }
        }

        String digest = declaredFields(Fingerprints.scheme("tar-v1"), chosen, target).hex();
        // DECLARED, always, on this branch. A declared sha256 names the bytes
        // this dep INTENDS to fetch, which is a strong declaration and still a
        // declaration: nothing on this path hashed what was written to bin/cli,
        // so nothing here can move when the artifact does. Grading it RESOLVED
        // because the input happens to be a hash would be the strongest-looking
        // version of exactly the confusion this field exists to prevent.
        return Fingerprint.declared(digest, (target.sha256() != null
                ? "declared url + declared sha256 of the bytes to be downloaded"
                : "declared url only — no sha256 is declared, so a re-publish of "
                        + "different bytes at the same url is not detectable")
                + " — this home holds no bin/cli/" + dep.name() + " to hash, so the "
                + "installed bytes are not observable here");
    }

    /**
     * The declared half, shared verbatim by both schemes so they cannot drift
     * apart. Only the scheme name and the trailing {@code installed} field
     * differ, which is what makes the two digests comparable as claims about
     * the same declaration.
     */
    private static Fingerprints declaredFields(Fingerprints into,
                                               Map.Entry<String, CliDependency.InstallTarget> chosen,
                                               CliDependency.InstallTarget target) {
        return into
                .field("platform", chosen.getKey())
                .field("url", target.url())
                .field("archive", target.archive())
                .field("binary", target.binary())
                .fields("extract", target.extract())
                .field("sha256", target.sha256());
    }

    /**
     * {@code bin/cli/<name>} when it is a readable regular file in THIS home,
     * else null.
     *
     * <p>{@link Files#isRegularFile} follows links, which is the behaviour
     * wanted: a dangling shim is not a file whose bytes can be hashed, and it
     * must reach the declared branch rather than throw. {@code install} copies
     * rather than links, so the normal case is a plain file.
     */
    private static Path installedFile(CliDependency dep, SkillStore store) {
        if (dep.name() == null || dep.name().isBlank()) return null;
        Path candidate = store.cliBinDir().resolve(dep.name());
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private CliDependency.InstallTarget pickTarget(CliDependency dep) {
        Map.Entry<String, CliDependency.InstallTarget> chosen = pickTargetEntry(dep);
        return chosen == null ? null : chosen.getValue();
    }

    /**
     * {@link #pickTarget} keeping the platform key that selected the target.
     * The key is not recoverable from the value and the fingerprint needs it.
     */
    private Map.Entry<String, CliDependency.InstallTarget> pickTargetEntry(CliDependency dep) {
        // Fetch-then-test rather than containsKey-then-fetch: the install map
        // is a LinkedHashMap and so may hold a null VALUE under a present key,
        // where the old pickTarget returned null and Map.entry would throw.
        CliDependency.InstallTarget any = dep.install().get("any");
        if (dep.platformIndependent() && any != null) return Map.entry("any", any);
        for (var e : dep.install().entrySet()) {
            if ("any".equals(e.getKey())) continue;
            if (e.getValue() == null) continue;
            if (Platform.matches(e.getKey())) return e;
        }
        return any == null ? null : Map.entry("any", any);
    }

    private String suffix(CliDependency.InstallTarget t) {
        return switch (Archives.detect(t.url())) {
            case TAR_GZ -> ".tar.gz";
            case ZIP -> ".zip";
            case RAW -> "";
        };
    }

    private void download(String url, Path dst) throws IOException {
        try {
            HttpResponse<InputStream> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + url);
            }
            try (InputStream in = resp.body()) {
                Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", e);
        }
    }

    private void verifySha256(Path file, String expected) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            String actual = HexFormat.of().formatHex(md.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("SHA-256 mismatch: expected " + expected + " got " + actual);
            }
        } catch (Exception e) {
            throw new IOException("checksum failed", e);
        }
    }

    private Path extractOrCopy(Path download, Path dir, CliDependency.InstallTarget target) throws IOException {
        Archives.Kind kind = target.archive() != null
                ? switch (target.archive().toLowerCase()) {
                    case "tar.gz", "tgz" -> Archives.Kind.TAR_GZ;
                    case "zip" -> Archives.Kind.ZIP;
                    default -> Archives.Kind.RAW;
                }
                : Archives.detect(target.url());

        switch (kind) {
            case TAR_GZ -> Archives.extractTarGz(download, dir);
            case ZIP -> Archives.extractZip(download, dir);
            case RAW -> {
                Path out = dir.resolve(target.binary() != null ? target.binary() : "bin");
                Fs.ensureDir(out.getParent());
                Files.copy(download, out, StandardCopyOption.REPLACE_EXISTING);
                return out;
            }
        }
        if (target.binary() != null) {
            Path explicit = dir.resolve(target.binary());
            if (Files.isRegularFile(explicit)) return explicit;
        }
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).filter(Files::isExecutable).findFirst().orElse(null);
        }
    }
}
