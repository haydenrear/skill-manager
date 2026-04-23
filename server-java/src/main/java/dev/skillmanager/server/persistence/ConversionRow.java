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
 * One install that the client attributes to a sponsored placement. Clients
 * tag the {@code /skills/{n}/{v}/download} call with {@code ?campaign_id=…}
 * when the install was prompted by an ad they saw, and the server records
 * a row. No campaign_id → no conversion row.
 */
@Entity
@Table(
        name = "conversions",
        indexes = {
                @Index(name = "idx_conversion_campaign", columnList = "campaign_id"),
                @Index(name = "idx_conversion_installer", columnList = "installer_login"),
        })
public class ConversionRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "campaign_id", nullable = false, length = 32)
    private String campaignId;

    @Column(name = "skill_name", nullable = false, length = 200)
    private String skillName;

    @Column(name = "skill_version", nullable = false, length = 64)
    private String skillVersion;

    @Column(name = "installer_login", length = 64)
    private String installerLogin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ConversionRow() {}

    public ConversionRow(String campaignId, String skillName, String skillVersion, String installerLogin) {
        this.campaignId = campaignId;
        this.skillName = skillName;
        this.skillVersion = skillVersion;
        this.installerLogin = installerLogin;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCampaignId() { return campaignId; }
    public String getSkillName() { return skillName; }
    public String getSkillVersion() { return skillVersion; }
    public String getInstallerLogin() { return installerLogin; }
    public Instant getCreatedAt() { return createdAt; }
}
