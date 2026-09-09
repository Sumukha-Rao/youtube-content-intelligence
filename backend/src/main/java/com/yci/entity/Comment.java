package com.yci.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "comments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"video_id", "youtube_comment_id"}))
@Getter
@Setter
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    @Column(name = "youtube_comment_id", nullable = false, length = 64)
    private String youtubeCommentId;

    @Column(name = "parent_comment_id", length = 64)
    private String parentCommentId;

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
