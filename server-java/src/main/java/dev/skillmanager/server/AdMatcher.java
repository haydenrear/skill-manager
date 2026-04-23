package dev.skillmanager.server;

import dev.skillmanager.registry.dto.Campaign;
import dev.skillmanager.registry.dto.SponsoredPlacement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic match-and-rank for sponsored placements.
 *
 * <p>Rules (MVP):
 * <ol>
 *   <li>Only {@link Campaign#STATUS_ACTIVE} campaigns participate.</li>
 *   <li>A campaign matches a query if ANY keyword is a case-insensitive
 *       substring of the query, OR any category exactly matches the
 *       query (lowercased + trimmed), OR the query is a substring of any
 *       keyword (bidirectional substring match).</li>
 *   <li>Matches are ordered by {@link Campaign#bidCents()} desc, then by
 *       {@link Campaign#id()} for stability.</li>
 *   <li>At most one placement per {@link Campaign#skillName()}.</li>
 *   <li>The target skill must still be published in the registry — otherwise
 *       the placement is silently dropped (no broken ads).</li>
 * </ol>
 */
public final class AdMatcher {

    private final CampaignStorage campaigns;
    private final SkillStorage skills;

    public AdMatcher(CampaignStorage campaigns, SkillStorage skills) {
        this.campaigns = campaigns;
        this.skills = skills;
    }

    public List<SponsoredPlacement> match(String rawQuery, int limit) throws IOException {
        int effectiveLimit = Math.max(0, Math.min(limit, 10));
        if (effectiveLimit == 0) return List.of();

        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase();
        if (query.isEmpty()) return List.of();

        record Hit(Campaign campaign, String reason) {}
        List<Hit> hits = new ArrayList<>();
        for (Campaign c : campaigns.list()) {
            if (!Campaign.STATUS_ACTIVE.equals(c.status())) continue;
            String reason = whyMatched(c, query);
            if (reason != null) hits.add(new Hit(c, reason));
        }

        hits.sort(Comparator
                .comparingLong((Hit h) -> h.campaign().bidCents()).reversed()
                .thenComparing(h -> h.campaign().id()));

        List<SponsoredPlacement> out = new ArrayList<>();
        java.util.HashSet<String> seenSkills = new java.util.HashSet<>();
        for (Hit h : hits) {
            if (out.size() >= effectiveLimit) break;
            String skillName = h.campaign().skillName();
            if (!seenSkills.add(skillName)) continue;
            Optional<dev.skillmanager.registry.dto.SkillSummary> summary = skills.describe(skillName);
            if (summary.isEmpty()) continue; // ad points at a missing skill — drop
            out.add(new SponsoredPlacement(
                    skillName,
                    summary.get().description(),
                    summary.get().latestVersion(),
                    h.campaign().sponsor(),
                    h.campaign().id(),
                    h.reason(),
                    h.campaign().bidCents()));
        }
        return out;
    }

    private static String whyMatched(Campaign c, String query) {
        for (String kw : c.keywords()) {
            if (query.contains(kw) || kw.contains(query)) {
                return "matched keyword: " + kw;
            }
        }
        for (String cat : c.categories()) {
            if (query.equals(cat)) {
                return "matched category: " + cat;
            }
        }
        return null;
    }
}
