package com.yci.repository;

import com.yci.entity.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelRepository extends JpaRepository<Channel, Long> {
    List<Channel> findByUserId(Long userId);
    Optional<Channel> findByUserIdAndYoutubeChannelId(Long userId, String youtubeChannelId);
}
