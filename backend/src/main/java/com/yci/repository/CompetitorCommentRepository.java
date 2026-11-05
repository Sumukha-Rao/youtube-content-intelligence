package com.yci.repository;

import com.yci.entity.CompetitorComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CompetitorCommentRepository extends JpaRepository<CompetitorComment, Long> {
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from CompetitorComment c where c.competitorVideoId in (select v.id from CompetitorVideo v where v.competitorChannelId = :channelId)")
    void deleteByChannelId(@org.springframework.data.repository.query.Param("channelId") Long channelId);


    boolean existsByCompetitorVideoIdAndYoutubeCommentId(Long competitorVideoId, String youtubeCommentId);

    java.util.Optional<CompetitorComment> findByCompetitorVideoIdAndYoutubeCommentId(Long videoId, String youtubeCommentId);

    @Query("select c from CompetitorComment c where c.competitorVideoId in " +
           "(select v.id from CompetitorVideo v where v.competitorChannelId = :channelId)")
    List<CompetitorComment> findByCompetitorChannelId(@Param("channelId") Long channelId);

    @Query("select count(c) from CompetitorComment c where c.competitorVideoId in " +
           "(select v.id from CompetitorVideo v where v.competitorChannelId = :channelId)")
    long countByCompetitorChannelId(@Param("channelId") Long channelId);
}
