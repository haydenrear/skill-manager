package dev.skillmanager.commands;

import dev.skillmanager.registry.RegistryClient;
import dev.skillmanager.registry.RegistryConfig;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Kick off the password-reset flow.
 *
 * <p>Posts to {@code /auth/password-reset/request}. The server emails
 * a time-limited link that lands on an HTML form for picking a new
 * password; this command just starts the conversation.
 *
 * <p>Always reports the same "if registered, email sent" message
 * regardless of whether the address is known — mirrors the server's
 * enumeration-safe response.
 */
@Command(
        name = "reset-password",
        description = "Request a password-reset email for an account.")
public final class ResetPasswordCommand implements Callable<Integer> {

    @Option(names = "--email", required = true, description = "Email address on the account")
    String email;

    @Option(names = "--registry", description = "Override the persisted registry URL") String registryUrl;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        RegistryConfig cfg = RegistryConfig.resolve(store, registryUrl);
        RegistryClient client = new RegistryClient(cfg);

        client.requestPasswordReset(email);
        Log.ok("if %s is registered, a reset link has been emailed; it expires in 30 minutes.", email);
        return 0;
    }
}
