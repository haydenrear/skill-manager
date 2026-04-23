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
 * Authenticate against the registry and cache the bearer token.
 *
 * <p>Default flow is OAuth2 authorization_code + PKCE through a browser:
 * {@code skill-manager login} opens a browser to {@code /oauth2/authorize}
 * under the {@code skill-manager-cli} public client, binds a loopback
 * HTTP server on 127.0.0.1 to receive the callback code, then exchanges
 * it at {@code /oauth2/token}. Create an account first with
 * {@code skill-manager create-account}.
 *
 * <p>{@code --client-credentials} is an internal, undocumented mode used
 * only by first-party automation (test graphs, CI). It isn't shown in the
 * usage banner. Passing {@code --client-id}/{@code --client-secret} opts
 * into this mode.
 */
@Command(
        name = "login",
        description = "Authenticate against the registry and cache the bearer token.",
        subcommands = {LoginCommand.Logout.class, LoginCommand.Show.class})
public final class LoginCommand implements Callable<Integer> {

    @Option(names = "--port", description = "Loopback port for the OAuth2 callback (default 8765)",
            defaultValue = "8765")
    int callbackPort;

    @Option(names = "--no-browser",
            description = "Don't auto-open the browser; print the authorize URL instead")
    boolean noBrowser;

    // --- hidden client_credentials mode (first-party only) -------------------

    @Option(names = "--client-credentials", hidden = true,
            description = "Opt in to machine auth via client_credentials")
    boolean clientCredentials;

    @Option(names = "--client-id", hidden = true) String clientId;
    @Option(names = "--client-secret", hidden = true) String clientSecret;
    @Option(names = "--scope", hidden = true) String scope;

    @Option(names = "--registry", description = "Override the persisted registry URL") String registryUrl;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        RegistryConfig cfg = RegistryConfig.resolve(store, registryUrl);
        RegistryClient client = new RegistryClient(cfg);

        String token;
        String principal;
        if (clientCredentials || clientId != null) {
            if (clientId == null || clientSecret == null) {
                Log.error("--client-credentials requires --client-id and --client-secret");
                return 2;
            }
            var resp = client.clientCredentialsToken(clientId, clientSecret, scope);
            token = (String) resp.get("access_token");
            principal = clientId;
        } else {
            var resp = client.browserLogin(callbackPort, !noBrowser);
            token = (String) resp.get("access_token");
            principal = "(browser login)";
        }

        if (token == null || token.isBlank()) {
            Log.error("server did not return access_token");
            return 1;
        }
        new AuthStore(store).save(token);
        Log.ok("logged in as %s (token cached at %s)", principal, new AuthStore(store).file());
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
