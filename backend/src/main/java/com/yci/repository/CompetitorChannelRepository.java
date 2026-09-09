package com.yci.repository;

import com.yci.entity.CompetitorChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompetitorChannelRepository extends JpaRepository<CompetitorChannel, Long> {
    List<CompetitorChannel> findByUserId(Long userId);
    Optional<CompetitorChannel> findByUserIdAndYoutubeChannelId(Long userId, String youtubeChannelId);
}
