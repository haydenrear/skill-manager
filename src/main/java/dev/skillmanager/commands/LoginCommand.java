package dev.skillmanager.commands;

import dev.skillmanager.registry.AuthStore;
import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Authenticates against the registry's embedded authorization server using
 * the {@code client_credentials} grant and caches the resulting JWT on disk.
 *
 * <p>The registry is its own IdP: every caller — CLI, test-graph CI, any
 * future integration — needs a registered OAuth2 client. Pass its
 * {@code client_id} / {@code client_secret} here; the cached token is
 * attached to every subsequent {@code skill-manager} request via the
 * {@code Authorization: Bearer} header.
 */
@Command(
        name = "login",
        description = "Authenticate against the registry and cache the bearer token.",
        subcommands = {LoginCommand.Logout.class, LoginCommand.Show.class})
public final class LoginCommand implements Callable<Integer> {

    @Option(names = "--client-id", required = true, description = "OAuth2 client id registered with the registry")
    String clientId;

    @Option(names = "--client-secret", required = true, description = "OAuth2 client secret")
    String clientSecret;

    @Option(names = "--scope", description = "Space-separated scopes to request; omit for the client's default scopes")
    String scope;

    @Option(names = "--registry", description = "Override the persisted registry URL") String registryUrl;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        RegistryConfig cfg = RegistryConfig.resolve(store, registryUrl);
        RegistryClient client = new RegistryClient(cfg);

        var resp = client.clientCredentialsToken(clientId, clientSecret, scope);
        String token = (String) resp.get("access_token");
        if (token == null || token.isBlank()) {
            Log.error("server did not return access_token; response was: %s", resp);
            return 1;
        }
        new AuthStore(store).save(token);
        Log.ok("logged in as %s (token cached at %s)", clientId, new AuthStore(store).file());
        return 0;
    }

    @Command(name = "logout", description = "Forget the cached bearer token.")
    public static final class Logout implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            boolean deleted = new AuthStore(store).clear();
            Log.ok("logged out (file %s)", deleted ? "removed" : "was already gone");
            return 0;
        }
    }

    @Command(name = "show", description = "Print the cached identity (hits /auth/me).")
    public static final class Show implements Callable<Integer> {
        @Option(names = "--registry") String registryUrl;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            RegistryConfig cfg = RegistryConfig.resolve(store, registryUrl);
            var client = RegistryClient.authenticated(store, cfg);
            try {
                var me = client.me();
                System.out.println("username: " + me.get("username"));
                if (me.get("display_name") != null) System.out.println("display_name: " + me.get("display_name"));
                if (me.get("email") != null) System.out.println("email: " + me.get("email"));
                return 0;
            } catch (Exception e) {
                Log.error("not logged in: %s", e.getMessage());
                return 1;
            }
        }
    }
}
