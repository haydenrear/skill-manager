package dev.skillmanager.server.publish;

import dev.skillmanager.shared.dto.SkillVersion;
import dev.skillmanager.server.SkillStorage;
import dev.skillmanager.server.persistence.SkillName;
import dev.skillmanager.server.persistence.SkillNameRepository;
import dev.skillmanager.server.persistence.SkillVersionRow;
import dev.skillmanager.server.persistence.SkillVersionRowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

/**
 * Publish-path contract enforcement — the one place every new version
 * squeezes through:
 *
 * <ol>
 *   <li>semver 2.0.0 validation → {@link PublishException.BadVersion}</li>
 *   <li>name-ownership check: first publisher claims the name; later
 *       publishes must come from the same user →
 *       {@link PublishException.Forbidden}</li>
 *   <li>immutability: a published {@code name@version} cannot be
 *       overwritten → {@link PublishException.Conflict}</li>
 * </ol>
 *
 * <p>Only after all three pass do we call {@link SkillStorage#publish}
 * to write bytes + metadata and insert the {@link SkillVersionRow}.
 * Deletes route through {@link #deleteSkill} / {@link #deleteVersion} so
 * ownership is checked there too.
 */
@Service
public class SkillPublishService {

    private final SkillStorage storage;
    private final SkillNameRepository names;
    private final SkillVersionRowRepository versions;

    public SkillPublishService(SkillStorage storage,
                               SkillNameRepository names,
                               SkillVersionRowRepository versions) {
        this.storage = storage;
        this.names = names;
        this.versions = versions;
    }

    /**
     * Register a github-hosted skill. Pulls the toml + SKILL.md out of the
     * repo at {@code gitRef}, derives name+version from the toml (defaulting
     * to {@code 0.0.1} if absent), then runs the same ownership/immutability
     * checks as the legacy upload path.
     */
    @Transactional
    public SkillVersion registerFromGithub(String githubUrl, String gitRef, String username) throws IOException {
        GitHubFetcher.SkillMetadata meta;
        try {
            meta = GitHubFetcher.fetch(githubUrl, gitRef);
        } catch (GitHubFetcher.FetchException e) {
            throw new PublishException.BadVersion(e.getMessage());
        }

        String name = meta.name();
        String version = meta.version();
        if (!Semver.isValid(version)) {
            throw new PublishException.BadVersion(
                    "skill-manager.toml [skill].version is not valid semver: " + version);
        }

        SkillName owner = names.findById(name).orElse(null);
        if (owner == null) {
            names.save(new SkillName(name, username, meta.unitKind()));
        } else {
            if (!owner.getOwnerUsername().equals(username)) {
                throw new PublishException.Forbidden(
                        "name '" + name + "' is owned by " + owner.getOwnerUsername());
            }
            if (!owner.getUnitKind().equals(meta.unitKind())) {
                throw new PublishException.Conflict(
                        "name '" + name + "' was first published as a " + owner.getUnitKind()
                                + "; cannot republish as a " + meta.unitKind());
            }
        }

        SkillVersionRow.Key key = new SkillVersionRow.Key(name, version);
        if (versions.existsById(key)) {
            throw new PublishException.Conflict(name + "@" + version + " already published");
        }

        SkillVersion record = storage.registerGithub(
                name, version, meta.description(), meta.skillReferences(),
                username, githubUrl, gitRef, meta.gitSha(), meta.unitKind());
        versions.save(SkillVersionRow.github(name, version, username, githubUrl, gitRef, meta.gitSha()));
        return record;
    }

    @Transactional
    public SkillVersion publish(String name, String version, byte[] payload, String username) throws IOException {
        if (!Semver.isValid(version)) {
            throw new PublishException.BadVersion("version is not valid semver: " + version);
        }

        // Inspect the bundle once up front so the kind is known before we
        // touch SkillName / write bytes. Storage.publish takes the inspected
        // metadata back so it doesn't double-extract.
        SkillStorage.BundleInspection meta = storage.inspect(payload);

        SkillName owner = names.findById(name).orElse(null);
        if (owner == null) {
            names.save(new SkillName(name, username, meta.unitKind()));
        } else {
            if (!owner.getOwnerUsername().equals(username)) {
                throw new PublishException.Forbidden(
                        "name '" + name + "' is owned by " + owner.getOwnerUsername());
            }
            if (!owner.getUnitKind().equals(meta.unitKind())) {
                throw new PublishException.Conflict(
                        "name '" + name + "' was first published as a " + owner.getUnitKind()
                                + "; cannot republish as a " + meta.unitKind());
            }
        }

        SkillVersionRow.Key key = new SkillVersionRow.Key(name, version);
        if (versions.existsById(key)) {
            throw new PublishException.Conflict(name + "@" + version + " already published");
        }

        SkillVersion record = storage.publish(name, version, payload, username, meta);
        versions.save(new SkillVersionRow(
                name, version, record.sha256(), record.sizeBytes(), username));
        return record;
    }

    @Transactional
    public boolean deleteSkill(String name, String username) throws IOException {
        SkillName owner = names.findById(name).orElse(null);
        if (owner == null) return false;
        if (!owner.getOwnerUsername().equals(username)) {
            throw new PublishException.Forbidden(
                    "name '" + name + "' is owned by " + owner.getOwnerUsername());
        }
        versions.findByIdName(name).forEach(versions::delete);
        names.delete(owner);
        return storage.delete(name, null);
    }

    @Transactional
    public boolean deleteVersion(String name, String version, String username) throws IOException {
        SkillName owner = names.findById(name).orElse(null);
        if (owner == null) return false;
        if (!owner.getOwnerUsername().equals(username)) {
            throw new PublishException.Forbidden(
                    "name '" + name + "' is owned by " + owner.getOwnerUsername());
        }
        SkillVersionRow.Key key = new SkillVersionRow.Key(name, version);
        if (!versions.existsById(key)) return false;
        versions.deleteById(key);
        return storage.delete(name, version);
    }
}
