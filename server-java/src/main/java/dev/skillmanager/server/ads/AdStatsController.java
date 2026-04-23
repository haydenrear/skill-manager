package dev.skillmanager.server.ads;

import dev.skillmanager.registry.dto.Campaign;
import dev.skillmanager.server.CampaignStorage;
import dev.skillmanager.server.persistence.ConversionRepository;
import dev.skillmanager.server.persistence.ImpressionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public stats for a campaign. Returns the two counts advertisers care
 * about — impressions served and conversions attributed — plus the bid
 * so the caller can sanity-check their placement is still in the auction.
 *
 * <p>No PII. No individual impression/conversion rows. Just aggregates.
 */
@RestController
@RequestMapping("/ads")
public class AdStatsController {

    private final CampaignStorage campaigns;
    private final ImpressionRepository impressions;
    private final ConversionRepository conversions;

    public AdStatsController(CampaignStorage campaigns,
                             ImpressionRepository impressions,
                             ConversionRepository conversions) {
        this.campaigns = campaigns;
        this.impressions = impressions;
        this.conversions = conversions;
    }

    @GetMapping("/campaigns/{id}/stats")
    public Map<String, Object> stats(@PathVariable String id) {
        Campaign c = campaigns.get(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "campaign not found: " + id));

        long imps = impressions.countByCampaignId(id);
        long convs = conversions.countByCampaignId(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaign_id", c.id());
        out.put("skill_name", c.skillName());
        out.put("sponsor", c.sponsor());
        out.put("bid_cents", c.bidCents());
        out.put("impressions", imps);
        out.put("conversions", convs);
        out.put("conversion_rate", imps == 0 ? 0.0 : ((double) convs) / imps);
        return out;
    }
}
