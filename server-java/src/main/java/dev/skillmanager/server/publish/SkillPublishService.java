package dev.skillmanager.server.publish;

import dev.skillmanager.registry.dto.SkillVersion;
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

    @Transactional
    public SkillVersion publish(String name, String version, byte[] payload, String username) throws IOException {
        if (!Semver.isValid(version)) {
            throw new PublishException.BadVersion("version is not valid semver: " + version);
        }

        SkillName owner = names.findById(name).orElse(null);
        if (owner == null) {
            names.save(new SkillName(name, username));
        } else if (!owner.getOwnerUsername().equals(username)) {
            throw new PublishException.Forbidden(
                    "name '" + name + "' is owned by " + owner.getOwnerUsername());
        }

        SkillVersionRow.Key key = new SkillVersionRow.Key(name, version);
        if (versions.existsById(key)) {
            throw new PublishException.Conflict(name + "@" + version + " already published");
        }

        SkillVersion record = storage.publish(name, version, payload, username);
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
