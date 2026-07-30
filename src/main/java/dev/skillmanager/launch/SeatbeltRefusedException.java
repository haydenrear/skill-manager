package dev.skillmanager.launch;

import java.io.IOException;

/**
 * A launch asked for the sandbox and could not have it.
 *
 * <h2>Why this is a refusal and not a warning</h2>
 *
 * <p>The whole class of bug this epic keeps finding is "a zero that means
 * <em>could not look</em>, reported as <em>looked and found nothing</em>".
 * Proceeding unsandboxed after {@code SKILL_MANAGER_SANDBOX=1} was asked for is
 * the same shape one layer out: the operator's belief ("this launch cannot
 * touch another home") and the reality ("nothing is stopping it") diverge, with
 * no signal.
 *
 * <p>So the three ways to not get a sandbox all stop the launch:
 *
 * <ul>
 *   <li>{@code /usr/bin/sandbox-exec} is missing or not executable —
 *       {@code sandbox-exec(1)} has said DEPRECATED since 2017 and has no
 *       sanctioned successor, so its removal is a real future;</li>
 *   <li>the home has no {@code launch.sb};</li>
 *   <li>the profile, or a parameter it would be given, fails validation.</li>
 * </ul>
 *
 * <p>The structural mitigation for the deprecation is that the sandbox is
 * <b>opt-in per home</b>. A home that never asked for it launches exactly as it
 * does today, so losing {@code sandbox-exec} costs enforcement and keeps
 * behaviour — the right failure mode. A machine-wide default would have made
 * the same removal brick every launch on the machine.
 */
public final class SeatbeltRefusedException extends IOException {

    /**
     * Exit code for "you asked to be sandboxed and I could not do it".
     *
     * <p>Distinct from every other code {@code exec} returns, so a caller can
     * tell the cases apart: 2 is an argument error, 7 is
     * {@link UnredirectedLaunchException} (the env is wrong), 8 is unread
     * drift, 127 is "no such command on the launch PATH".
     */
    public static final int EXIT_CODE = 11;

    public SeatbeltRefusedException(String message) {
        super(message);
    }
}
