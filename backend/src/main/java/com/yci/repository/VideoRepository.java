package com.yci.repository;

import com.yci.entity.Video;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VideoRepository extends JpaRepository<Video, Long> {
    List<Video> findByChannelId(Long channelId);

    /**
     * Scoped to the channel on purpose. Two users may track the same YouTube
     * channel; a global lookup would let the second user's ingest reassign the
     * first user's rows (and drag their comments along).
     */
    Optional<Video> findByChannelIdAndYoutubeVideoId(Long channelId, String youtubeVideoId);

    long countByChannelId(Long channelId);
}
