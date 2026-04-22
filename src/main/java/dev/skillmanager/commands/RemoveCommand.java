package dev.skillmanager.commands;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Fs;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "remove", aliases = "rm", description = "Remove an installed skill.")
public final class RemoveCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Skill name")
    String name;

    @Option(names = "--from", description = "Also unlink from the given agent(s)", split = ",")
    List<String> unlink;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        if (!store.contains(name)) {
            Log.warn("skill not found: %s", name);
            return 1;
        }
        store.remove(name);
        Log.ok("removed %s", name);
        if (unlink != null) {
            for (String id : unlink) {
                Agent agent = Agent.byId(id);
                Path link = agent.skillsDir().resolve(name);
                if (Files.exists(link, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(link)) {
                    Fs.deleteRecursive(link);
                    Log.ok("%s: unlinked %s", agent.id(), name);
                }
            }
        }
        return 0;
    }
}
