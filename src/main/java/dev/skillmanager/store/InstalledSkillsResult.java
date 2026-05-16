package dev.skillmanager.store;

import dev.skillmanager.model.Skill;

import java.util.List;

/** Result of scanning installed bare skills, including unreadable entries. */
public record InstalledSkillsResult(
        List<Skill> skills,
        List<UnitReadProblem> problems
) {
    public InstalledSkillsResult {
        skills = skills == null ? List.of() : List.copyOf(skills);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }
}
