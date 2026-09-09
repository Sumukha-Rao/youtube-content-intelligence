package com.yci.repository;

import com.yci.entity.UserSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {
    Optional<UserSettings> findByUserId(Long userId);

    List<UserSettings> findByNotifyEnabledTrue();
}
