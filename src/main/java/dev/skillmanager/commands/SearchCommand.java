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

@Command(name = "search", description = "Search skills in the registry. Sponsored slots are rendered separately and can be hidden with --no-ads.")
public final class SearchCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Search query (empty = list everything)")
    String query;

    @Option(names = "--limit", defaultValue = "20") int limit;

    @Option(names = "--registry", description = "Registry URL override") String registryUrl;

    @Option(names = "--no-ads", description = "Hide the sponsored section from this query's response") boolean noAds;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        RegistryConfig cfg = RegistryConfig.resolve(store, registryUrl);
        RegistryClient client = RegistryClient.authenticated(store, cfg);

        RegistryClient.SearchResult result = client.searchWithSponsored(
                query == null ? "" : query, limit, noAds);

        if (result.organic().isEmpty() && result.sponsored().isEmpty()) {
            System.out.println("(no skills matched)");
            return 0;
        }

        if (!result.sponsored().isEmpty()) {
            System.out.println("sponsored");
            System.out.println("─────────");
            printRows(result.sponsored(), true);
            System.out.println();
        }

        if (!result.organic().isEmpty()) {
            System.out.println("organic");
            System.out.println("───────");
            printRows(result.organic(), false);
        }
        return 0;
    }

    private static void printRows(List<Map<String, Object>> rows, boolean sponsored) {
        System.out.printf("%-28s %-10s %s%n", "NAME", "LATEST", sponsored ? "SPONSOR · REASON" : "DESCRIPTION");
        for (Map<String, Object> hit : rows) {
            String name = String.valueOf(hit.getOrDefault("name", ""));
            Object latest = hit.get("latest_version");
            String latestStr = latest == null ? "-" : latest.toString();
            String right;
            if (sponsored) {
                String sponsor = String.valueOf(hit.getOrDefault("sponsor", ""));
                String reason = String.valueOf(hit.getOrDefault("reason", ""));
                right = "[sponsored] " + sponsor + " · " + reason;
            } else {
                String desc = String.valueOf(hit.getOrDefault("description", ""));
                if (desc.length() > 60) desc = desc.substring(0, 57) + "...";
                right = desc;
            }
            System.out.printf("%-28s %-10s %s%n", name, latestStr, right);
        }
    }
}
