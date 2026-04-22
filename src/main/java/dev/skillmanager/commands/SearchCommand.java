package dev.skillmanager.commands;

import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "search", description = "Search skills in the registry.")
public final class SearchCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Search query (empty = list everything)")
    String query;

    @Option(names = "--limit", defaultValue = "20") int limit;

    @Option(names = "--registry", description = "Registry base URL override") String registryUrl;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        RegistryConfig cfg = RegistryConfig.resolve(store, registryUrl);
        RegistryClient client = new RegistryClient(cfg);

        List<Map<String, Object>> hits = client.search(query == null ? "" : query, limit);
        if (hits.isEmpty()) {
            System.out.println("(no skills matched)");
            return 0;
        }
        System.out.printf("%-28s %-10s %s%n", "NAME", "LATEST", "DESCRIPTION");
        for (Map<String, Object> hit : hits) {
            String name = String.valueOf(hit.getOrDefault("name", ""));
            Object latest = hit.get("latest_version");
            String latestStr = latest == null ? "-" : latest.toString();
            String desc = String.valueOf(hit.getOrDefault("description", ""));
            if (desc.length() > 60) desc = desc.substring(0, 57) + "...";
            System.out.printf("%-28s %-10s %s%n", name, latestStr, desc);
        }
        return 0;
    }
}
