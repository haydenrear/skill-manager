package dev.skillmanager.sync;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.model.Skill;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

public final class SkillSync {

    private final SkillStore store;

    public SkillSync(SkillStore store) {
        this.store = store;
    }

    public void sync(Agent agent, List<Skill> skills, boolean useSymlinks) throws IOException {
        Path target = agent.skillsDir();
        Fs.ensureDir(target);
        for (Skill s : skills) {
            Path dst = target.resolve(s.name());
            if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dst)) {
                Fs.deleteRecursive(dst);
            }
            Path src = store.skillDir(s.name());
            if (useSymlinks) {
                try {
                    Files.createSymbolicLink(dst, src);
                } catch (UnsupportedOperationException | IOException e) {
                    Fs.copyRecursive(src, dst);
                }
            } else {
                Fs.copyRecursive(src, dst);
            }
            Log.ok("%s: synced %s", agent.id(), s.name());
        }
    }
}
