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
 * One row per published {@code name@version}. Presence of a row is the
 * authority for "this version exists" — the publish path checks it before
 * writing the tarball so re-publishes 409 rather than silently overwriting.
 *
 * <p>Ownership lives in {@link SkillName}; this row only carries per-version
 * metadata. Everything else (description, skill_references) stays in the
 * on-disk metadata.json because it's read in batches during search/list
 * via the existing {@link dev.skillmanager.server.SkillStorage}.
 */
@Entity
@Table(name = "skill_versions", indexes = {
        @Index(name = "idx_skill_versions_name", columnList = "name")
})
public class SkillVersionRow {

    @EmbeddedId
    private Key id;

    @Column(name = "sha256", length = 64, nullable = false)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "published_by", length = 64, nullable = false)
    private String publishedBy;

    public SkillVersionRow() {}

    public SkillVersionRow(String name, String version, String sha256, long sizeBytes, String publishedBy) {
        this.id = new Key(name, version);
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.publishedBy = publishedBy;
        this.publishedAt = Instant.now();
    }

    public Key getId() { return id; }
    public String getName() { return id == null ? null : id.name; }
    public String getVersion() { return id == null ? null : id.version; }
    public String getSha256() { return sha256; }
    public long getSizeBytes() { return sizeBytes; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getPublishedBy() { return publishedBy; }

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
