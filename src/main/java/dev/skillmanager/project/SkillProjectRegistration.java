package dev.skillmanager.project;

import java.nio.file.Path;

public record SkillProjectRegistration(
        String name,
        Path projectRoot,
        Path manifestPath,
        Path registrationDir,
        String registeredAt
) {}
