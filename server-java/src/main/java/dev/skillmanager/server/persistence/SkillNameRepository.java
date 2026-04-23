package dev.skillmanager.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillNameRepository extends JpaRepository<SkillName, String> {
    List<SkillName> findByOwnerUsername(String ownerUsername);
}
