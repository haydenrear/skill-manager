package dev.skillmanager.effects;

public interface ProgramInterpreter {
    <R> R run(Program<R> program);
}
