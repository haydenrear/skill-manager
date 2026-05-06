package dev.skillmanager.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.shared.dto.SkillSummary;
import dev.skillmanager.shared.dto.SkillVersion;
import dev.skillmanager.shared.util.Archives;
import dev.skillmanager.shared.util.BundleMetadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Filesystem-backed storage for the skill registry. Identical on-disk layout
 * to the former Python server, so an existing root is reusable:
 * <pre>
 *   &lt;root&gt;/
 *     &lt;skill-name&gt;/
 *       index.json
 *       &lt;version&gt;/
 *         skill.tar.gz
 *         metadata.json
 * </pre>
 */
public final class SkillStorage {

    public static final String PACKAGE_FILE = "skill.tar.gz";
    public static final String INDEX_FILE = "index.json";
    public static final String METADATA_FILE = "metadata.json";

    private final Path root;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public SkillStorage(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
    }

    public Path root() { return root; }

    // -------------------------------------------------------------- queries

    public List<SkillSummary> listAll() throws IOException {
        List<SkillSummary> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> s = Files.list(root)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.comparing(Path::getFileName))::iterator) {
                if (!Files.isDirectory(p)) continue;
                SkillIndexEntry entry = loadIndex(p.getFileName().toString()).orElse(null);
                if (entry != null) out.add(entry.toSummary());
            }
        }
        return out;
    }

    public Optional<SkillSummary> describe(String name) throws IOException {
        return loadIndex(name).map(SkillIndexEntry::toSummary);
    }

    public Optional<SkillVersion> describeVersion(String name, String version) throws IOException {
        Path meta = versionDir(name, version).resolve(METADATA_FILE);
        if (!Files.isRegularFile(meta)) return Optional.empty();
        return Optional.of(json.readValue(meta.toFile(), SkillVersion.class));
    }

    public Optional<String> resolveLatest(String name) throws IOException {
        return loadIndex(name).flatMap(e -> e.versions().isEmpty()
                ? Optional.empty()
                : Optional.of(e.versions().get(e.versions().size() - 1)));
    }

    public Optional<Path> packagePath(String name, String version) {
        Path p = versionDir(name, version).resolve(PACKAGE_FILE);
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    /** Lexical search: substring match on name (weighted) + description. */
    public List<SkillSummary> search(String query, int limit) throws IOException {
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.isEmpty()) {
            List<SkillSummary> all = listAll();
            return all.size() > limit ? all.subList(0, limit) : all;
        }
        record Hit(double score, SkillSummary summary) {}
        List<Hit> hits = new ArrayList<>();
        for (SkillSummary s : listAll()) {
            String nameLc = s.name().toLowerCase();
            String descLc = s.description() == null ? "" : s.description().toLowerCase();
            double score = 0.0;
            if (nameLc.contains(q)) score += 3.0 + (nameLc.startsWith(q) ? 1.0 : 0.0);
            if (descLc.contains(q)) score += 1.0;
            if (score > 0) hits.add(new Hit(score, s));
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits.stream().limit(limit).map(Hit::summary).toList();
    }

    // ------------------------------------------------------------- mutations

    public SkillVersion publish(String name, String version, byte[] payload) throws IOException {
        return publish(name, version, payload, null);
    }

    public SkillVersion publish(String name, String version, byte[] payload, String ownerUsername)
            throws IOException {
        return publish(name, version, payload, ownerUsername, inspect(payload));
    }

    /**
     * Variant taking a pre-computed {@link BundleInspection} — lets the
     * publish service inspect the tarball once (for ownership/kind
     * checks against {@code SkillName}) and pass the result through
     * instead of re-extracting.
     */
    public SkillVersion publish(String name, String version, byte[] payload,
                                String ownerUsername, BundleInspection meta) throws IOException {
        validateName(name);
        validateVersion(version);

        Path vdir = versionDir(name, version);
        Files.createDirectories(vdir);
        Files.write(vdir.resolve(PACKAGE_FILE), payload);

        String digest = sha256(payload);
        SkillVersion record = SkillVersion.tarball(
                name,
                version,
                meta.description(),
                System.currentTimeMillis() / 1000.0,
                digest,
                payload.length,
                List.copyOf(meta.skillReferences()),
                ownerUsername,
                meta.unitKind());
        json.writeValue(vdir.resolve(METADATA_FILE).toFile(), record);
        updateIndex(name, meta.description(), version, meta.unitKind());
        return record;
    }

    /**
     * Register a github-hosted skill version. Writes metadata.json + updates
     * index.json without storing any tarball; install-time fetch goes
     * straight to {@code github_url} at {@code git_sha}.
     */
    public SkillVersion registerGithub(String name, String version, String description,
                                       List<String> skillReferences, String ownerUsername,
                                       String githubUrl, String gitRef, String gitSha,
                                       String unitKind) throws IOException {
        validateName(name);
        validateVersion(version);
        Path vdir = versionDir(name, version);
        Files.createDirectories(vdir);
        String kind = (unitKind == null || unitKind.isBlank()) ? BundleMetadata.UNIT_KIND_SKILL : unitKind;
        SkillVersion record = SkillVersion.github(
                name,
                version,
                description == null ? "" : description,
                System.currentTimeMillis() / 1000.0,
                skillReferences == null ? List.of() : List.copyOf(skillReferences),
                ownerUsername,
                githubUrl,
                gitRef,
                gitSha,
                kind);
        json.writeValue(vdir.resolve(METADATA_FILE).toFile(), record);
        updateIndex(name, record.description(), version, kind);
        return record;
    }

    public boolean delete(String name, String version) throws IOException {
        Path dir = root.resolve(name);
        if (!Files.isDirectory(dir)) return false;
        if (version == null) {
            deleteRecursive(dir);
            return true;
        }
        Path vdir = dir.resolve(version);
        if (!Files.isDirectory(vdir)) return false;
        deleteRecursive(vdir);
        loadIndex(name).ifPresent(idx -> {
            List<String> remaining = new ArrayList<>(idx.versions());
            remaining.remove(version);
            try {
                writeIndex(name, new SkillIndexEntry(idx.name(), idx.description(), remaining, idx.unitKind()));
            } catch (IOException ignored) {}
        });
        return true;
    }

    // ---------------------------------------------------------- internals

    private Path versionDir(String name, String version) {
        return root.resolve(name).resolve(version);
    }

    private Optional<SkillIndexEntry> loadIndex(String name) throws IOException {
        Path p = root.resolve(name).resolve(INDEX_FILE);
        if (!Files.isRegularFile(p)) return Optional.empty();
        return Optional.of(json.readValue(p.toFile(), SkillIndexEntry.class));
    }

    private void writeIndex(String name, SkillIndexEntry entry) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        json.writeValue(dir.resolve(INDEX_FILE).toFile(), entry);
    }

    private void updateIndex(String name, String description, String version, String unitKind) throws IOException {
        SkillIndexEntry entry = loadIndex(name)
                .orElse(new SkillIndexEntry(name, "", new ArrayList<>(), BundleMetadata.UNIT_KIND_SKILL));
        List<String> versions = new ArrayList<>(entry.versions());
        String effectiveDesc = (description == null || description.isBlank()) ? entry.description() : description;
        if (!versions.contains(version)) versions.add(version);
        // Once a name is established as plugin or skill it doesn't change — preserve
        // the existing kind on subsequent version adds (the publish service rejects
        // mismatches at the SkillName layer; this is a defense-in-depth fallback).
        String kind = entry.versions().isEmpty()
                ? (unitKind == null || unitKind.isBlank() ? BundleMetadata.UNIT_KIND_SKILL : unitKind)
                : entry.unitKind();
        writeIndex(name, new SkillIndexEntry(name, effectiveDesc, versions, kind));
    }

    /** Result of extracting + scanning an uploaded bundle prior to writing it. */
    public record BundleInspection(String description, List<String> skillReferences, String unitKind) {}

    public BundleInspection inspect(byte[] payload) throws IOException {
        return inspectTarball(payload);
    }

    private BundleInspection inspectTarball(byte[] payload) throws IOException {
        Path tmp = Files.createTempDirectory("skill-inspect-");
        try {
            // Archives.extractTarGz(InputStream,...) already wraps the stream in a
            // GzipCompressorInputStream — don't double-unwrap here.
            try (var bais = new ByteArrayInputStream(payload)) {
                Archives.extractTarGz(bais, tmp);
            }
            String description = BundleMetadata.findSkillMd(tmp)
                    .map(p -> {
                        try { return BundleMetadata.parseSkillDescription(Files.readString(p)); }
                        catch (IOException e) { return ""; }
                    })
                    .orElse("");
            List<String> refs = BundleMetadata.findSkillToml(tmp)
                    .map(p -> {
                        try { return BundleMetadata.parseSkillReferences(Files.readString(p)); }
                        catch (IOException e) { return List.<String>of(); }
                    })
                    .orElse(List.of());
            return new BundleInspection(description, refs, BundleMetadata.detectUnitKind(tmp));
        } finally {
            deleteRecursive(tmp);
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("empty skill name");
        for (char c : name.toCharArray()) {
            if (c == '/' || c == '\\' || Character.isWhitespace(c)) {
                throw new IllegalArgumentException("invalid skill name: " + name);
            }
        }
    }

    private static void validateVersion(String version) {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("empty version");
        for (char c : version.toCharArray()) {
            if (c == '/' || c == '\\' || Character.isWhitespace(c)) {
                throw new IllegalArgumentException("invalid version: " + version);
            }
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        if (Files.isDirectory(p)) {
            try (Stream<Path> children = Files.list(p)) {
                for (Path child : (Iterable<Path>) children::iterator) deleteRecursive(child);
            }
        }
        Files.delete(p);
    }
}
