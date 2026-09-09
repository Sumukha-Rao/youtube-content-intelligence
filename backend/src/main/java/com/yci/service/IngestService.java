package com.yci.service;

import com.yci.config.AppProperties;
import com.yci.entity.*;
import com.yci.repository.*;
import com.yci.service.youtube.YouTubeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls video metadata and top comments from YouTube, honouring the user's saved
 * retrieval settings (last N videos, or everything from the last N days).
 *
 * Every call runs against the API key that belongs to the user being ingested for
 * ({@code youtubeApiKey}), which is their own key when they saved one in Settings
 * and the server's otherwise.
 *
 * Comment budgets are per *channel*, not per video: the requested total is spread
 * evenly across that channel's videos so one busy video cannot consume the whole
 * budget. Comments are requested in YouTube's "relevance" order, which is what
 * makes them the channel's *top* comments.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final VideoRepository videoRepo;
    private final CommentRepository commentRepo;
    private final CompetitorVideoRepository competitorVideoRepo;
    private final CompetitorCommentRepository competitorCommentRepo;
    private final ChannelRepository channelRepo;
    private final CompetitorChannelRepository competitorChannelRepo;
    private final YouTubeService youTube;
    private final AppProperties props;

    public IngestService(VideoRepository videoRepo, CommentRepository commentRepo,
                         CompetitorVideoRepository competitorVideoRepo,
                         CompetitorCommentRepository competitorCommentRepo,
                         ChannelRepository channelRepo, CompetitorChannelRepository competitorChannelRepo,
                         YouTubeService youTube, AppProperties props) {
        this.videoRepo = videoRepo;
        this.commentRepo = commentRepo;
        this.competitorVideoRepo = competitorVideoRepo;
        this.competitorCommentRepo = competitorCommentRepo;
        this.channelRepo = channelRepo;
        this.competitorChannelRepo = competitorChannelRepo;
        this.youTube = youTube;
        this.props = props;
    }

    public record IngestResult(int videos, int comments) {}

    /** Fetch the videos selected by {@code settings} plus their top comments. */
    @Transactional
    public IngestResult ingestOwnChannel(Channel channel, UserSettings settings, String youtubeApiKey) {
        YouTubeService.Api yt = youTube.session(youtubeApiKey);
        List<YouTubeService.YtVideo> ytVideos =
                fetchVideos(yt, channel.getYoutubeChannelId(), settings);
        if (ytVideos.isEmpty()) {
            log.info("Channel {} returned no videos for the current retrieval settings", channel.getId());
            channel.setLastSyncedAt(LocalDateTime.now());
            channelRepo.save(channel);
            return new IngestResult(0, 0);
        }

        List<Long> videoIds = new ArrayList<>();
        for (YouTubeService.YtVideo yv : ytVideos) {
            Video v = videoRepo.findByChannelIdAndYoutubeVideoId(channel.getId(), yv.videoId())
                    .orElseGet(Video::new);
            v.setChannelId(channel.getId());
            v.setYoutubeVideoId(yv.videoId());
            v.setTitle(yv.title());
            v.setDescription(yv.description());
            v.setPublishedAt(yv.publishedAt());
            v.setDuration(yv.duration());
            v.setViewCount(yv.viewCount());
            v.setLikeCount(yv.likeCount());
            v.setCommentCount(yv.commentCount());
            v.setThumbnailUrl(yv.thumbnailUrl());
            videoIds.add(videoRepo.save(v).getId());
        }

        int budget = settings.getOwnCommentLimit();
        int perVideo = perVideoBudget(budget, ytVideos.size());
        int fetched = 0, stored = 0;

        for (int i = 0; i < ytVideos.size() && fetched < budget; i++) {
            int want = Math.min(perVideo, budget - fetched);
            List<YouTubeService.YtComment> comments = yt.getComments(ytVideos.get(i).videoId(), want);
            fetched += comments.size();
            for (YouTubeService.YtComment yc : comments) {
                if (commentRepo.existsByVideoIdAndYoutubeCommentId(videoIds.get(i), yc.commentId())) continue;
                Comment c = new Comment();
                c.setVideoId(videoIds.get(i));
                c.setYoutubeCommentId(yc.commentId());
                c.setParentCommentId(yc.parentId());
                c.setText(yc.text());
                c.setPublishedAt(yc.publishedAt());
                c.setLikeCount(yc.likeCount());
                commentRepo.save(c);
                stored++;
            }
        }

        channel.setLastSyncedAt(LocalDateTime.now());
        channelRepo.save(channel);
        log.info("Own channel {}: {} videos, {} comments fetched ({} new)",
                channel.getId(), ytVideos.size(), fetched, stored);
        return new IngestResult(ytVideos.size(), fetched);
    }

    /** Same for a competitor: video metadata (no ML) plus its top comments. */
    @Transactional
    public IngestResult ingestCompetitor(CompetitorChannel cc, UserSettings settings, String youtubeApiKey) {
        YouTubeService.Api yt = youTube.session(youtubeApiKey);
        List<YouTubeService.YtVideo> ytVideos = fetchVideos(yt, cc.getYoutubeChannelId(), settings);
        if (ytVideos.isEmpty()) {
            cc.setLastCheckedAt(LocalDateTime.now());
            competitorChannelRepo.save(cc);
            return new IngestResult(0, 0);
        }

        List<Long> videoIds = new ArrayList<>();
        LocalDateTime maxPublished = cc.getLastVideoPublishedAt();
        for (YouTubeService.YtVideo yv : ytVideos) {
            CompetitorVideo v = competitorVideoRepo
                    .findByCompetitorChannelIdAndYoutubeVideoId(cc.getId(), yv.videoId())
                    .orElseGet(CompetitorVideo::new);
            v.setCompetitorChannelId(cc.getId());
            v.setYoutubeVideoId(yv.videoId());
            v.setTitle(yv.title());
            v.setDescription(yv.description());
            v.setPublishedAt(yv.publishedAt());
            v.setDuration(yv.duration());
            v.setViewCount(yv.viewCount());
            v.setLikeCount(yv.likeCount());
            v.setCommentCount(yv.commentCount());
            v.setAnalyzed(true); // video metadata is used as-is; no ML runs on it
            videoIds.add(competitorVideoRepo.save(v).getId());
            if (yv.publishedAt() != null && (maxPublished == null || yv.publishedAt().isAfter(maxPublished))) {
                maxPublished = yv.publishedAt();
            }
        }

        int budget = settings.getCompetitorCommentLimit();
        int perVideo = perVideoBudget(budget, ytVideos.size());
        int fetched = 0;

        for (int i = 0; i < ytVideos.size() && fetched < budget; i++) {
            int want = Math.min(perVideo, budget - fetched);
            List<YouTubeService.YtComment> comments = yt.getComments(ytVideos.get(i).videoId(), want);
            fetched += comments.size();
            for (YouTubeService.YtComment yc : comments) {
                if (competitorCommentRepo
                        .existsByCompetitorVideoIdAndYoutubeCommentId(videoIds.get(i), yc.commentId())) continue;
                CompetitorComment c = new CompetitorComment();
                c.setCompetitorVideoId(videoIds.get(i));
                c.setYoutubeCommentId(yc.commentId());
                c.setText(yc.text());
                c.setPublishedAt(yc.publishedAt());
                c.setLikeCount(yc.likeCount());
                competitorCommentRepo.save(c);
            }
        }

        cc.setLastCheckedAt(LocalDateTime.now());
        cc.setLastVideoPublishedAt(maxPublished);
        competitorChannelRepo.save(cc);
        log.info("Competitor {}: {} videos, {} comments fetched", cc.getId(), ytVideos.size(), fetched);
        return new IngestResult(ytVideos.size(), fetched);
    }

    private List<YouTubeService.YtVideo> fetchVideos(YouTubeService.Api yt, String youtubeChannelId,
                                                     UserSettings settings) {
        if (settings.getRetrievalMode() == RetrievalMode.LAST_N_DAYS) {
            return yt.getChannelVideosSince(youtubeChannelId,
                    LocalDateTime.now().minusDays(settings.getRetrievalValue()));
        }
        return yt.getChannelVideos(youtubeChannelId, settings.getRetrievalValue());
    }

    /** Spread a channel-wide comment budget across its videos, never exceeding the API cap. */
    private int perVideoBudget(int budget, int videoCount) {
        int perVideo = (int) Math.ceil((double) budget / Math.max(1, videoCount));
        return Math.max(1, Math.min(perVideo, props.getYoutube().getMaxCommentsPerVideo()));
    }
}
