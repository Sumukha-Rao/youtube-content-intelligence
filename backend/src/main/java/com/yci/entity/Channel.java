package com.yci.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "channels",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "youtube_channel_id"}))
@Getter
@Setter
public class Channel {

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

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "subscriber_count")
    private Long subscriberCount;

    @Column(name = "video_count")
    private Long videoCount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;
}
