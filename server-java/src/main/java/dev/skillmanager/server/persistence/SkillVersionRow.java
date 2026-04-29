package dev.skillmanager.server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One row per published {@code name@version}. Two shapes coexist:
 * <ul>
 *   <li><b>github-registered</b> — sha256/sizeBytes null, githubUrl + gitSha + gitRef set.
 *       This is the default path going forward.</li>
 *   <li><b>tarball-uploaded</b> — sha256/sizeBytes set, github fields null. Only reachable
 *       when {@code skill-registry.publish.allow-file-upload=true}.</li>
 * </ul>
 *
 * <p>Ownership lives in {@link SkillName}; this row only carries per-version
 * metadata. Searchable description / skill_references stay in the on-disk
 * metadata.json because they're read in batches during search/list via
 * {@link dev.skillmanager.server.SkillStorage}.
 */
@Entity
@Table(name = "skill_versions", indexes = {
        @Index(name = "idx_skill_versions_name", columnList = "name")
})
public class SkillVersionRow {

    @EmbeddedId
    private Key id;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "published_by", length = 64, nullable = false)
    private String publishedBy;

    @Column(name = "github_url", length = 512)
    private String githubUrl;

    @Column(name = "git_ref", length = 256)
    private String gitRef;

    @Column(name = "git_sha", length = 64)
    private String gitSha;

    public SkillVersionRow() {}

    /** Tarball-uploaded constructor (legacy file-upload backend). */
    public SkillVersionRow(String name, String version, String sha256, long sizeBytes, String publishedBy) {
        this.id = new Key(name, version);
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.publishedBy = publishedBy;
        this.publishedAt = Instant.now();
    }

    /** GitHub-registered constructor — no bytes stored, just the pointer + resolved SHA. */
    public static SkillVersionRow github(String name, String version, String publishedBy,
                                         String githubUrl, String gitRef, String gitSha) {
        SkillVersionRow row = new SkillVersionRow();
        row.id = new Key(name, version);
        row.publishedBy = publishedBy;
        row.publishedAt = Instant.now();
        row.githubUrl = githubUrl;
        row.gitRef = gitRef;
        row.gitSha = gitSha;
        return row;
    }

    public Key getId() { return id; }
    public String getName() { return id == null ? null : id.name; }
    public String getVersion() { return id == null ? null : id.version; }
    public String getSha256() { return sha256; }
    public Long getSizeBytes() { return sizeBytes; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getPublishedBy() { return publishedBy; }
    public String getGithubUrl() { return githubUrl; }
    public String getGitRef() { return gitRef; }
    public String getGitSha() { return gitSha; }

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "name", length = 128, nullable = false)
        private String name;
        @Column(name = "version", length = 64, nullable = false)
        private String version;

        public Key() {}
        public Key(String name, String version) { this.name = name; this.version = version; }

        public String getName() { return name; }
        public String getVersion() { return version; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(name, k.name) && Objects.equals(version, k.version);
        }
        @Override public int hashCode() { return Objects.hash(name, version); }
    }
}
