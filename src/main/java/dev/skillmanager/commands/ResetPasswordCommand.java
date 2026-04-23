package dev.skillmanager.commands;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Placeholder for the self-serve password-reset flow. Until the real
 * token-email-delivery path lands this just prints the address a user
 * should email to get their password reset manually.
 */
@Command(
        name = "reset-password",
        description = "Print instructions for resetting your password.")
public final class ResetPasswordCommand implements Callable<Integer> {

    private static final String CONTACT_EMAIL = "hayden.rear@gmail.com";

    @Override
    public Integer call() {
        System.out.println("To reset your password, email " + CONTACT_EMAIL
                + " with your username. Self-serve reset will land in a future release.");
        return 0;
    }
}
