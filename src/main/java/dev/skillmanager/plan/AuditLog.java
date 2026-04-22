package dev.skillmanager.plan;

import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Append-only log of every consented destructive action. */
public final class AuditLog {

    private static final String FILENAME = "audit.log";

    private final Path file;

    public AuditLog(SkillStore store) throws IOException {
        Fs.ensureDir(store.root());
        this.file = store.root().resolve(FILENAME);
    }

    public void record(String event, String detail) {
        String line = Instant.now().toString() + "\t" + event + "\t" + detail.replace('\n', ' ') + "\n";
        try {
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    public void recordPlan(InstallPlan plan, String verb) {
        for (PlanAction a : plan.actions()) {
            if (a instanceof PlanAction.BlockedByPolicy) continue;
            record(verb, a.severity() + "\t" + a.title());
        }
    }
}
