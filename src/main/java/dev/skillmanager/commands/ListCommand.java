package dev.skillmanager.commands;

import dev.skillmanager.model.Skill;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "list", aliases = "ls", description = "List installed skills.")
public final class ListCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        var skills = store.listInstalled();
        if (skills.isEmpty()) {
            System.out.println("(no skills installed — use `skill-manager install <source>`)");
            return 0;
        }
        System.out.printf("%-28s %-10s %s%n", "NAME", "VERSION", "DESCRIPTION");
        for (Skill s : skills) {
            String v = s.version() != null ? s.version() : "-";
            String d = s.description() == null ? "" : s.description();
            if (d.length() > 70) d = d.substring(0, 67) + "...";
            System.out.printf("%-28s %-10s %s%n", s.name(), v, d);
        }
        return 0;
    }
}
