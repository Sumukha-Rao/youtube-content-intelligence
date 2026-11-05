package com.yci.repository;

import com.yci.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from Comment c where c.videoId in (select v.id from Video v where v.channelId = :channelId)")
    void deleteByChannelId(@org.springframework.data.repository.query.Param("channelId") Long channelId);

    List<Comment> findByVideoId(Long videoId);

    /** Scoped to the video, so the same comment id under another user's copy is fine. */
    boolean existsByVideoIdAndYoutubeCommentId(Long videoId, String youtubeCommentId);

    java.util.Optional<Comment> findByVideoIdAndYoutubeCommentId(Long videoId, String youtubeCommentId);

    @Query("select c from Comment c where c.videoId in " +
           "(select v.id from Video v where v.channelId = :channelId)")
    List<Comment> findByChannelId(@Param("channelId") Long channelId);

    @Query("select count(c) from Comment c where c.videoId in " +
           "(select v.id from Video v where v.channelId = :channelId)")
    long countByChannelId(@Param("channelId") Long channelId);
}
