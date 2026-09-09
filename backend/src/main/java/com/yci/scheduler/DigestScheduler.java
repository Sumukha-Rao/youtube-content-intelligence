package com.yci.scheduler;

import com.yci.entity.IdeaRun;
import com.yci.entity.NotifyFrequency;
import com.yci.entity.RunStatus;
import com.yci.entity.UserSettings;
import com.yci.repository.IdeaRunRepository;
import com.yci.repository.UserSettingsRepository;
import com.yci.service.IdeaService;
import com.yci.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Generates and emails a fresh set of ideas for every user who opted in
 * (spec item 11). Runs hourly and picks up only the users actually due, so daily
 * and weekly subscribers share one schedule.
 */
@Component
public class DigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);

    private final UserSettingsRepository settingsRepo;
    private final IdeaRunRepository runRepo;
    private final IdeaService ideaService;
    private final NotificationService notifications;

    public DigestScheduler(UserSettingsRepository settingsRepo, IdeaRunRepository runRepo,
                           IdeaService ideaService, NotificationService notifications) {
        this.settingsRepo = settingsRepo;
        this.runRepo = runRepo;
        this.ideaService = ideaService;
        this.notifications = notifications;
    }

    @Scheduled(cron = "${app.notifications.cron:0 0 * * * *}")
    public void sendDueDigests() {
        for (UserSettings settings : settingsRepo.findByNotifyEnabledTrue()) {
            if (!isDue(settings)) continue;
            try {
                IdeaRun run = ideaService.createRun(settings.getUserId());
                ideaService.runGeneration(run.getId(), settings.getUserId());

                IdeaRun finished = runRepo.findById(run.getId()).orElse(null);
                if (finished == null || finished.getStatus() != RunStatus.COMPLETED) {
                    log.warn("Digest for user {} skipped — generation did not complete", settings.getUserId());
                    continue;
                }
                if (notifications.sendDigest(settings, finished)) {
                    finished.setNotified(true);
                    runRepo.save(finished);
                }
                // Stamp regardless of delivery so a broken mailbox cannot cause a
                // regeneration storm on every hourly tick.
                settings.setLastNotifiedAt(LocalDateTime.now());
                settingsRepo.save(settings);
            } catch (Exception e) {
                log.warn("Digest for user {} failed: {}", settings.getUserId(), e.getMessage());
                settings.setLastNotifiedAt(LocalDateTime.now());
                settingsRepo.save(settings);
            }
        }
    }

    private boolean isDue(UserSettings s) {
        if (s.getLastNotifiedAt() == null) return true;
        LocalDateTime next = s.getNotifyFrequency() == NotifyFrequency.DAILY
                ? s.getLastNotifiedAt().plusDays(1)
                : s.getLastNotifiedAt().plusWeeks(1);
        return LocalDateTime.now().isAfter(next);
    }
}
