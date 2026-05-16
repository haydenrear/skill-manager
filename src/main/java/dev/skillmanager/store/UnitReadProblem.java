package dev.skillmanager.store;

import dev.skillmanager.model.UnitKind;

import java.nio.file.Path;

/** One installed unit directory existed but could not be parsed/read. */
public record UnitReadProblem(
        String name,
        UnitKind kind,
        Path path,
        String message
) {}
