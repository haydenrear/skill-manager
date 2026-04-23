package dev.skillmanager.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.registry.dto.Campaign;
import dev.skillmanager.registry.dto.CreateCampaignRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Filesystem-backed campaign storage at
 * {@code <registry-root>/ads/campaigns/<id>.json}. Thread-safe via the
 * implicit single-writer nature of small JSON files — fine for MVP scale.
 *
 * <p>Intentionally unauthenticated for now; see {@code docs}/commits for
 * the auth TODO.
 */
public final class CampaignStorage {

    private static final SecureRandom RNG = new SecureRandom();

    // Match the SNAKE_CASE property naming policy the rest of the API uses.
    private final ObjectMapper json = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path dir;

    public CampaignStorage(Path registryRoot) throws IOException {
        this.dir = registryRoot.resolve("ads").resolve("campaigns");
        Files.createDirectories(dir);
    }

    public Path dir() { return dir; }

    public Campaign create(CreateCampaignRequest req) {
        validate(req);
        String id = "cmp_" + randomId(12);
        String status = req.status() == null || req.status().isBlank()
                ? Campaign.STATUS_ACTIVE
                : req.status().toLowerCase();
        Campaign c = new Campaign(
                id,
                req.sponsor().trim(),
                req.skillName().trim(),
                normalize(req.keywords()),
                normalize(req.categories()),
                req.bidCents(),
                req.dailyBudgetCents(),
                status,
                System.currentTimeMillis() / 1000.0,
                req.notes() == null ? "" : req.notes().trim()
        );
        write(c);
        return c;
    }

    public Optional<Campaign> get(String id) {
        Path p = dir.resolve(safeId(id) + ".json");
        if (!Files.isRegularFile(p)) return Optional.empty();
        try {
            return Optional.of(json.readValue(p.toFile(), Campaign.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public List<Campaign> list() throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        List<Campaign> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!p.getFileName().toString().endsWith(".json")) continue;
                try {
                    out.add(json.readValue(p.toFile(), Campaign.class));
                } catch (IOException ignored) {
                    // skip corrupt file
                }
            }
        }
        out.sort(Comparator.comparingLong(Campaign::bidCents).reversed()
                .thenComparing(Campaign::id));
        return out;
    }

    public boolean delete(String id) {
        Path p = dir.resolve(safeId(id) + ".json");
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- helpers

    private void write(Campaign c) {
        Path p = dir.resolve(c.id() + ".json");
        try {
            json.writeValue(p.toFile(), c);
        } catch (IOException e) {
            throw new RuntimeException("failed to persist campaign " + c.id(), e);
        }
    }

    private static void validate(CreateCampaignRequest req) {
        if (req.sponsor() == null || req.sponsor().isBlank()) {
            throw new IllegalArgumentException("sponsor is required");
        }
        if (req.skillName() == null || req.skillName().isBlank()) {
            throw new IllegalArgumentException("skill_name is required");
        }
        if (req.bidCents() < 0) {
            throw new IllegalArgumentException("bid_cents must be non-negative");
        }
        if (req.dailyBudgetCents() < 0) {
            throw new IllegalArgumentException("daily_budget_cents must be non-negative");
        }
        boolean hasKw = req.keywords() != null && !req.keywords().isEmpty();
        boolean hasCat = req.categories() != null && !req.categories().isEmpty();
        if (!hasKw && !hasCat) {
            throw new IllegalArgumentException("at least one keyword or category is required");
        }
        for (String kw : req.keywords()) rejectWildcard(kw, "keyword");
        for (String cat : req.categories()) rejectWildcard(cat, "category");
    }

    private static void rejectWildcard(String s, String what) {
        if (s == null) throw new IllegalArgumentException(what + " may not be null");
        String t = s.trim();
        if (t.isEmpty() || t.equals("*") || t.equals("%")) {
            throw new IllegalArgumentException(what + " may not be wildcard or empty: " + s);
        }
    }

    private static List<String> normalize(List<String> xs) {
        List<String> out = new ArrayList<>();
        if (xs == null) return out;
        for (String x : xs) {
            if (x == null) continue;
            String t = x.trim().toLowerCase();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String safeId(String id) {
        if (id == null) return "";
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String randomId(int bytes) {
        byte[] buf = new byte[bytes];
        RNG.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
