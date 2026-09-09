package com.yci.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A video belonging to one user's channel. The uniqueness is per channel row, not
 * global: two users may track the same YouTube channel, and each needs their own
 * copy. A global unique key on youtube_video_id would make the second user's
 * ingest silently reassign the first user's rows.
 */
@Entity
@Table(name = "videos",
        uniqueConstraints = @UniqueConstraint(columnNames = {"channel_id", "youtube_video_id"}))
@Getter
@Setter
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

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

    @Column(name = "thumbnail_url", length = 512)
    private String thumbnailUrl;

    @Column(columnDefinition = "LONGTEXT")
    private String transcript;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
