package com.yci.repository;

import com.yci.entity.CompetitorVideo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CompetitorVideoRepository extends JpaRepository<CompetitorVideo, Long> {
    List<CompetitorVideo> findByCompetitorChannelId(Long competitorChannelId);

    /** Scoped to the competitor row — see {@link VideoRepository} for why. */
    Optional<CompetitorVideo> findByCompetitorChannelIdAndYoutubeVideoId(
            Long competitorChannelId, String youtubeVideoId);

    @Query("select v from CompetitorVideo v where v.competitorChannelId in " +
           "(select cc.id from CompetitorChannel cc where cc.userId = :userId)")
    List<CompetitorVideo> findByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
}
