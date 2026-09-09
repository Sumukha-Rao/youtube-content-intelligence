package com.yci.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A top comment on a competitor's video. Stored separately from {@link Comment}
 * (the creator's own audience) so the two demand signals never get mixed.
 */
@Entity
@Table(name = "competitor_comments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"competitor_video_id", "youtube_comment_id"}))
@Getter
@Setter
public class CompetitorComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "competitor_video_id", nullable = false)
    private Long competitorVideoId;

    @Column(name = "youtube_comment_id", nullable = false, length = 64)
    private String youtubeCommentId;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String text;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "like_count")
    private Long likeCount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
