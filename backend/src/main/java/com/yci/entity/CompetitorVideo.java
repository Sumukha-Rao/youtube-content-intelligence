package com.yci.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "competitor_videos",
        uniqueConstraints = @UniqueConstraint(columnNames = {"competitor_channel_id", "youtube_video_id"}))
@Getter
@Setter
public class CompetitorVideo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "competitor_channel_id", nullable = false)
    private Long competitorChannelId;

    @Column(name = "youtube_video_id", nullable = false, length = 32)
    private String youtubeVideoId;

    @Column(length = 512)
    private String title;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String description;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(length = 32)
    private String duration;

    @Column(name = "view_count")
    private Long viewCount;

    @Column(name = "like_count")
    private Long likeCount;

    @Column(name = "comment_count")
    private Long commentCount;

    @Column(columnDefinition = "LONGTEXT")
    private String transcript;

    @Column(nullable = false)
    private boolean analyzed = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
