package dev.skillmanager.commands;

import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.Console;
import java.util.concurrent.Callable;

/**
 * Self-serve signup against the registry's embedded authorization server.
 * Hits {@code POST /auth/register}; downstream {@code skill-manager login}
 * (browser flow, default) then authenticates that account.
 */
@Command(
        name = "create-account",
        description = "Register a new user on the registry.")
public final class CreateAccountCommand implements Callable<Integer> {

    @Option(names = "--username", required = true, description = "New username (3-64 chars, lowercase, [a-z0-9-])")
    String username;

    @Option(names = "--email", required = true, description = "Contact email")
    String email;

    @Option(names = "--password", description = "Login password (prompted if omitted). Min 10 chars.",
            arity = "0..1", interactive = true)
    char[] password;

    @Option(names = "--display-name", description = "Display name (defaults to username)")
    String displayName;

    @Option(names = "--registry", description = "Override the persisted registry URL") String registryUrl;

    @Override
    public Integer call() throws Exception {
        if (password == null || password.length == 0) {
            Console console = System.console();
            if (console == null) {
                Log.error("no password supplied and no TTY available for prompting");
                return 2;
            }
            password = console.readPassword("password: ");
        }
        if (password.length < 10) {
            Log.error("password must be at least 10 characters");
            return 2;
        }

        SkillStore store = SkillStore.defaultStore();
        store.init();
        RegistryConfig cfg = RegistryConfig.resolve(store, registryUrl);
        RegistryClient client = new RegistryClient(cfg);

        var resp = client.registerAccount(username, email, new String(password), displayName);
        // Clear the password bytes — we never want them lingering in the heap.
        java.util.Arrays.fill(password, '\0');
        Log.ok("registered %s (%s)", resp.get("username"), resp.get("email"));
        Log.info("next step: skill-manager login");
        return 0;
    }
}
