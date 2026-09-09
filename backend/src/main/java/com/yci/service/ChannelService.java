package com.yci.service;

import com.yci.dto.Dto;
import com.yci.entity.Channel;
import com.yci.repository.ChannelRepository;
import com.yci.repository.CommentRepository;
import com.yci.repository.VideoRepository;
import com.yci.service.credentials.ApiConfigService;
import com.yci.service.youtube.YouTubeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The creator's own channel. A user has at most one: setting a different channel
 * replaces the previous one (and its ingested videos/comments cascade away), so
 * one account's ideas are never built from a mixture of two channels.
 */
@Service
public class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

    private final ChannelRepository channelRepo;
    private final VideoRepository videoRepo;
    private final CommentRepository commentRepo;
    private final YouTubeService youTube;
    private final ApiConfigService apiConfig;

    public ChannelService(ChannelRepository channelRepo, VideoRepository videoRepo,
                          CommentRepository commentRepo, YouTubeService youTube,
                          ApiConfigService apiConfig) {
        this.channelRepo = channelRepo;
        this.videoRepo = videoRepo;
        this.commentRepo = commentRepo;
        this.youTube = youTube;
        this.apiConfig = apiConfig;
    }

    /** Resolve a channel id/handle/URL and store it as this user's channel. */
    @Transactional
    public Dto.ChannelDto setChannel(Long userId, String channelUrlOrId) {
        // Resolved with this user's own API key when they saved one.
        YouTubeService.Api yt = youTube.session(apiConfig.forUser(userId).youtube().apiKey());
        String channelId = yt.resolveChannelId(channelUrlOrId);
        YouTubeService.YtChannel details = yt.getChannelDetails(channelId);

        // Drop any previously configured channel that is not this one.
        for (Channel existing : channelRepo.findByUserId(userId)) {
            if (!existing.getYoutubeChannelId().equals(channelId)) {
                log.info("Replacing channel {} with {} for user {}",
                        existing.getYoutubeChannelId(), channelId, userId);
                channelRepo.delete(existing);
            }
        }

        Channel channel = channelRepo.findByUserIdAndYoutubeChannelId(userId, channelId)
                .orElseGet(Channel::new);
        channel.setUserId(userId);
        channel.setYoutubeChannelId(channelId);
        channel.setChannelName(details.title());
        channel.setChannelUrl(channelUrlOrId.trim());
        channel.setDescription(details.description());
        channel.setSubscriberCount(details.subscriberCount());
        channel.setVideoCount(details.videoCount());
        channel = channelRepo.save(channel);
        log.info("Channel set for user {}: {} ({})", userId, details.title(), channelId);
        return toDto(channel);
    }

    @Transactional(readOnly = true)
    public Optional<Channel> findChannel(Long userId) {
        List<Channel> all = channelRepo.findByUserId(userId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    @Transactional(readOnly = true)
    public Dto.ChannelDto getChannelDto(Long userId) {
        return findChannel(userId).map(this::toDto).orElse(null);
    }

    @Transactional
    public void removeChannel(Long userId) {
        channelRepo.findByUserId(userId).forEach(channelRepo::delete);
        log.info("Removed own channel for user {}", userId);
    }

    public Dto.ChannelDto toDto(Channel c) {
        return new Dto.ChannelDto(c.getId(), c.getYoutubeChannelId(), c.getChannelName(), c.getChannelUrl(),
                c.getDescription(), c.getSubscriberCount(), c.getVideoCount(), c.getLastSyncedAt(),
                videoRepo.countByChannelId(c.getId()), commentRepo.countByChannelId(c.getId()));
    }
}
