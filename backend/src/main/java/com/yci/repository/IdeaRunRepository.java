package com.yci.repository;

import com.yci.entity.IdeaRun;
import com.yci.entity.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IdeaRunRepository extends JpaRepository<IdeaRun, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from IdeaRun r where r.id = :id and r.userId = :userId")
    Optional<IdeaRun> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id,
                                  @org.springframework.data.repository.query.Param("userId") Long userId);

    /** A digest must not merge a stale run over a newly cached transcript summary. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update IdeaRun r set r.notified = true where r.id = :id and r.userId = :userId")
    void markNotified(@org.springframework.data.repository.query.Param("id") Long id,
                      @org.springframework.data.repository.query.Param("userId") Long userId);

    List<IdeaRun> findTop20ByUserIdOrderByIdDesc(Long userId);

    Optional<IdeaRun> findFirstByUserIdAndStatusOrderByIdDesc(Long userId, RunStatus status);

    boolean existsByUserIdAndStatusIn(Long userId, List<RunStatus> statuses);

    /** Used at startup to find runs orphaned by a restart. */
    List<IdeaRun> findByStatusIn(List<RunStatus> statuses);
}
