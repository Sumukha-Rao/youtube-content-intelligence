package com.yci.service;

import com.yci.entity.IdeaRun;
import com.yci.entity.RunStatus;
import com.yci.repository.IdeaRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Fails any run left QUEUED or RUNNING by a previous process.
 *
 * A run lives only in the memory of the thread executing it, so a restart in the
 * middle of one — a redeploy, a reboot, the OOM killer on a 1 GiB box — leaves a
 * row that will never finish. That row is not merely untidy: {@code createRun}
 * refuses to start a new run while the user has one QUEUED or RUNNING, and there
 * is no way to cancel it, so the account is locked out of idea generation for
 * good and its scheduled digests stop with it.
 *
 * This runs just after the web server starts accepting traffic, so a client that
 * polls in the first moments of a boot can still see the old row; a second later
 * it reads FAILED and offers a retry, rather than a spinner that never ends.
 */
@Component
public class RunReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RunReconciler.class);

    private final IdeaRunRepository runRepo;

    public RunReconciler(IdeaRunRepository runRepo) {
        this.runRepo = runRepo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<IdeaRun> orphaned = runRepo.findByStatusIn(List.of(RunStatus.QUEUED, RunStatus.RUNNING));
        if (orphaned.isEmpty()) return;

        for (IdeaRun r : orphaned) {
            r.setStatus(RunStatus.FAILED);
            r.setMessage("Interrupted");
            r.setErrorMessage("The server restarted while this run was in progress. Please try again.");
            r.setCompletedAt(LocalDateTime.now());
        }
        runRepo.saveAll(orphaned);
        log.info("Marked {} interrupted idea run(s) as FAILED after restart", orphaned.size());
    }
}
