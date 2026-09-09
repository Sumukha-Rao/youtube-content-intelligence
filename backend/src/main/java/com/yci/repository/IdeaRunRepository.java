package com.yci.repository;

import com.yci.entity.IdeaRun;
import com.yci.entity.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IdeaRunRepository extends JpaRepository<IdeaRun, Long> {
    List<IdeaRun> findTop20ByUserIdOrderByIdDesc(Long userId);

    Optional<IdeaRun> findFirstByUserIdAndStatusOrderByIdDesc(Long userId, RunStatus status);

    boolean existsByUserIdAndStatusIn(Long userId, List<RunStatus> statuses);
}
