package dev.skillmanager.commands;

import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Manage sponsored-placement campaigns against a registry.
 *
 * <p>No auth — anyone with the registry URL can CRUD campaigns today. Add
 * an auth layer before exposing a production registry.
 */
@Command(
        name = "ads",
        description = "Manage sponsored-placement campaigns on the registry.",
        subcommands = {
                AdsCommand.List.class,
                AdsCommand.Create.class,
                AdsCommand.Delete.class,
        })
public final class AdsCommand implements Runnable {

    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }

    @Command(name = "list", description = "List registered campaigns (ordered by bid).")
    public static final class List implements Callable<Integer> {
        @Option(names = "--registry") String registryUrl;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            RegistryConfig cfg = RegistryConfig.resolve(store, registryUrl);
            var rows = RegistryClient.authenticated(store, cfg).listCampaigns();
            if (rows.isEmpty()) {
                System.out.println("(no campaigns)");
                return 0;
            }
            System.out.printf("%-20s %-14s %-22s %-7s %-8s %s%n",
                    "ID", "SKILL", "SPONSOR", "BID¢", "STATUS", "KEYWORDS");
            for (Map<String, Object> row : rows) {
                System.out.printf("%-20s %-14s %-22s %-7s %-8s %s%n",
                        row.getOrDefault("id", ""),
                        row.getOrDefault("skill_name", ""),
                        row.getOrDefault("sponsor", ""),
                        row.getOrDefault("bid_cents", ""),
                        row.getOrDefault("status", ""),
                        row.get("keywords"));
            }
            return 0;
        }
    }

    @Command(name = "create", description = "Register a new campaign (no auth — see caveat).")
    public static final class Create implements Callable<Integer> {
        @Option(names = "--sponsor", required = true,
                description = "Advertiser name shown next to the sponsored row.")
        String sponsor;

        @Option(names = "--skill", required = true,
                description = "The (already-published) skill to promote.")
        String skillName;

        @Option(names = "--keyword", split = ",",
                description = "Keyword(s) that trigger this placement (repeatable/comma-separated).")
        java.util.List<String> keywords;

        @Option(names = "--category", split = ",",
                description = "Category tag(s). Exact-match signal.")
        java.util.List<String> categories;

        @Option(names = "--bid-cents", defaultValue = "100",
                description = "Integer cents bid used to rank sponsored slots.")
        long bidCents;

        @Option(names = "--daily-budget-cents", defaultValue = "0",
                description = "Daily budget (not enforced in MVP; stored for future use).")
        long dailyBudgetCents;

        @Option(names = "--status", defaultValue = "active",
                description = "active | paused")
        String status;

        @Option(names = "--notes", description = "Free-form notes (e.g. rationale for the bid).")
        String notes;

        @Option(names = "--registry") String registryUrl;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            RegistryConfig cfg = RegistryConfig.resolve(store, registryUrl);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sponsor", sponsor);
            body.put("skill_name", skillName);
            body.put("keywords", keywords == null ? java.util.List.of() : keywords);
            body.put("categories", categories == null ? java.util.List.of() : categories);
            body.put("bid_cents", bidCents);
            body.put("daily_budget_cents", dailyBudgetCents);
            body.put("status", status);
            if (notes != null) body.put("notes", notes);

            var created = RegistryClient.authenticated(store, cfg).createCampaign(body);
            Log.ok("created campaign %s (skill=%s sponsor=%s bid=%s¢)",
                    created.get("id"), created.get("skill_name"),
                    created.get("sponsor"), created.get("bid_cents"));
            return 0;
        }
    }

    @Command(name = "delete", description = "Remove a campaign by id.")
    public static final class Delete implements Callable<Integer> {
        @Parameters(index = "0") String id;
        @Option(names = "--registry") String registryUrl;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            RegistryConfig cfg = RegistryConfig.resolve(store, registryUrl);
            boolean ok = RegistryClient.authenticated(store, cfg).deleteCampaign(id);
            if (ok) Log.ok("deleted campaign %s", id);
            else Log.warn("campaign not found: %s", id);
            return ok ? 0 : 1;
        }
    }
}
