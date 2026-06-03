package dev.skillmanager.project;

import java.nio.file.Path;

public record SkillProjectRegistration(
        String name,
        Path projectRoot,
        Path manifestPath,
        String manifestFile,
        Path registrationDir,
        String registeredAt
) {}
