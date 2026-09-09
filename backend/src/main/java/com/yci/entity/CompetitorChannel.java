package com.yci.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "competitor_channels",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "youtube_channel_id"}))
@Getter
@Setter
public class CompetitorChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "youtube_channel_id", nullable = false, length = 64)
    private String youtubeChannelId;

    @Column(name = "channel_name")
    private String channelName;

    @Column(name = "channel_url", length = 512)
    private String channelUrl;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "last_video_published_at")
    private LocalDateTime lastVideoPublishedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
