package dev.skillmanager.server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One impression — an ad was shown. Anonymous impressions (no bearer token)
 * get {@code viewerLogin = null}; the campaign_id is stable and drives the
 * stats aggregation.
 */
@Entity
@Table(
        name = "impressions",
        indexes = {
                @Index(name = "idx_impression_campaign", columnList = "campaign_id"),
                @Index(name = "idx_impression_viewer", columnList = "viewer_login"),
        })
public class ImpressionRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "campaign_id", nullable = false, length = 32)
    private String campaignId;

    @Column(name = "skill_name", nullable = false, length = 200)
    private String skillName;

    @Column(name = "viewer_login", length = 64)
    private String viewerLogin;

    @Column(name = "query_text", length = 400)
    private String queryText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ImpressionRow() {}

    public ImpressionRow(String campaignId, String skillName, String viewerLogin, String queryText) {
        this.campaignId = campaignId;
        this.skillName = skillName;
        this.viewerLogin = viewerLogin;
        this.queryText = queryText;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCampaignId() { return campaignId; }
    public String getSkillName() { return skillName; }
    public String getViewerLogin() { return viewerLogin; }
    public String getQueryText() { return queryText; }
    public Instant getCreatedAt() { return createdAt; }
}
