package dev.skillmanager.server.auth.reset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Transactional email for password resets.
 *
 * <p>Delegates to Spring's {@link JavaMailSender} backed by Gmail SMTP
 * ({@code smtp.gmail.com:587}, STARTTLS). Credentials come from
 * {@code SKILL_REGISTRY_MAIL_USERNAME} + {@code CLOUD_COM_LLC_PASSWORD}.
 * If the password env var is blank we skip the actual send and log the
 * message body — lets local dev and test graphs progress without
 * real SMTP credentials.
 *
 * <p>Send failures are swallowed (logged only) so the reset-request
 * endpoint can return its enumeration-defending status regardless of
 * whether mail delivery actually succeeded.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailer;
    private final String fromAddress;
    private final boolean senderConfigured;

    public MailService(
            JavaMailSender mailer,
            @Value("${skill-registry.mail.from:cloudcomllc@gmail.com}") String fromAddress,
            @Value("${spring.mail.password:}") String smtpPassword) {
        this.mailer = mailer;
        this.fromAddress = fromAddress;
        this.senderConfigured = smtpPassword != null && !smtpPassword.isBlank();
    }

    public void send(String to, String subject, String body) {
        if (!senderConfigured) {
            log.info("mail (skipped, SMTP not configured) to={} subject={}", to, subject);
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            mailer.send(msg);
        } catch (Exception e) {
            log.warn("mail send failed to={} subject={} error={}", to, subject, e.toString());
        }
    }
}
