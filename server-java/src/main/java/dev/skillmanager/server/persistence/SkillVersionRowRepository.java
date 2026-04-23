package dev.skillmanager.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillVersionRowRepository extends JpaRepository<SkillVersionRow, SkillVersionRow.Key> {
    List<SkillVersionRow> findByIdName(String name);
}
