package com.yci.service;

import com.yci.config.AppProperties;
import com.yci.entity.IdeaRun;
import com.yci.entity.UserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Emails the idea digest.
 *
 * SMTP is optional: with no {@code spring.mail.host} configured, Spring Boot
 * creates no {@link JavaMailSender}, and this service logs and skips instead of
 * failing the scheduled run. That keeps the notification settings usable before
 * mail credentials exist.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final AppProperties props;

    @Value("${spring.mail.host:}")
    private String mailHost;

    public NotificationService(ObjectProvider<JavaMailSender> mailSenderProvider, AppProperties props) {
        this.mailSenderProvider = mailSenderProvider;
        this.props = props;
    }

    public boolean isConfigured() {
        return mailHost != null && !mailHost.isBlank() && mailSenderProvider.getIfAvailable() != null;
    }

    /** Returns true when the message was actually handed to an SMTP server. */
    public boolean sendDigest(UserSettings settings, IdeaRun run) {
        String to = settings.getNotifyEmail();
        if (to == null || to.isBlank()) {
            log.info("User {} has notifications on but no email address — skipping", settings.getUserId());
            return false;
        }
        if (!isConfigured()) {
            log.info("SMTP is not configured (spring.mail.host unset) — digest for {} not sent", to);
            return false;
        }

        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(props.getNotifications().getFrom());
            msg.setTo(to);
            msg.setSubject("Your YouTube video ideas");
            msg.setText(body(run));
            mailSenderProvider.getObject().send(msg);
            log.info("Digest emailed to {} for run {}", to, run.getId());
            return true;
        } catch (Exception e) {
            log.warn("Could not email digest to {}: {}", to, e.getMessage());
            return false;
        }
    }

    private String body(IdeaRun run) {
        StringBuilder sb = new StringBuilder();
        sb.append("Here are your latest video ideas.\n\n");
        sb.append(run.getResult() == null ? "(no ideas were generated)" : run.getResult());
        sb.append("\n\n---\n");
        sb.append("Generated with ").append(run.getModel() == null ? "your model" : run.getModel());
        if (run.isWebSearchUsed()) sb.append(" (with web search)");
        sb.append(".\nOpen the app: ").append(props.getNotifications().getAppUrl()).append("\n");
        return sb.toString();
    }
}
