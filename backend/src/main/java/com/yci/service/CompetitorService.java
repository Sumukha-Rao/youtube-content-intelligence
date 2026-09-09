package com.yci.service;

import com.yci.dto.Dto;
import com.yci.entity.CompetitorChannel;
import com.yci.exception.BadRequestException;
import com.yci.exception.ResourceNotFoundException;
import com.yci.repository.CompetitorChannelRepository;
import com.yci.repository.CompetitorCommentRepository;
import com.yci.repository.CompetitorVideoRepository;
import com.yci.service.credentials.ApiConfigService;
import com.yci.service.youtube.YouTubeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Competitor channels the user wants compared against their own. */
@Service
public class CompetitorService {

    private static final Logger log = LoggerFactory.getLogger(CompetitorService.class);

    /** Keeps a single "get ideas" run inside a sane YouTube quota budget. */
    private static final int MAX_COMPETITORS = 10;

    private final CompetitorChannelRepository channelRepo;
    private final CompetitorVideoRepository videoRepo;
    private final CompetitorCommentRepository commentRepo;
    private final ChannelService channelService;
    private final YouTubeService youTube;
    private final ApiConfigService apiConfig;

    public CompetitorService(CompetitorChannelRepository channelRepo, CompetitorVideoRepository videoRepo,
                             CompetitorCommentRepository commentRepo, ChannelService channelService,
                             YouTubeService youTube, ApiConfigService apiConfig) {
        this.channelRepo = channelRepo;
        this.videoRepo = videoRepo;
        this.commentRepo = commentRepo;
        this.channelService = channelService;
        this.youTube = youTube;
        this.apiConfig = apiConfig;
    }

    @Transactional
    public Dto.CompetitorChannelDto add(Long userId, String channelUrlOrId) {
        List<CompetitorChannel> existing = channelRepo.findByUserId(userId);
        if (existing.size() >= MAX_COMPETITORS) {
            throw new BadRequestException("You can track up to " + MAX_COMPETITORS + " competitor channels.");
        }
        YouTubeService.Api yt = youTube.session(apiConfig.forUser(userId).youtube().apiKey());
        String channelId = yt.resolveChannelId(channelUrlOrId);

        channelService.findChannel(userId).ifPresent(own -> {
            if (own.getYoutubeChannelId().equals(channelId)) {
                throw new BadRequestException("That is your own channel — add a different one as a competitor.");
            }
        });
        if (channelRepo.findByUserIdAndYoutubeChannelId(userId, channelId).isPresent()) {
            throw new BadRequestException("That competitor channel is already on your list.");
        }

        YouTubeService.YtChannel details = yt.getChannelDetails(channelId);
        CompetitorChannel cc = new CompetitorChannel();
        cc.setUserId(userId);
        cc.setYoutubeChannelId(channelId);
        cc.setChannelName(details.title());
        cc.setChannelUrl(channelUrlOrId.trim());
        cc = channelRepo.save(cc);
        log.info("Added competitor {} ({}) for user {}", details.title(), channelId, userId);
        return toDto(cc);
    }

    @Transactional(readOnly = true)
    public List<Dto.CompetitorChannelDto> list(Long userId) {
        return channelRepo.findByUserId(userId).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<CompetitorChannel> entities(Long userId) {
        return channelRepo.findByUserId(userId);
    }

    @Transactional
    public void delete(Long userId, Long competitorId) {
        CompetitorChannel cc = channelRepo.findById(competitorId)
                .orElseThrow(() -> new ResourceNotFoundException("Competitor channel not found: " + competitorId));
        if (!cc.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Competitor channel not found: " + competitorId);
        }
        channelRepo.delete(cc);
        log.info("Removed competitor {} for user {}", competitorId, userId);
    }

    public Dto.CompetitorChannelDto toDto(CompetitorChannel cc) {
        return new Dto.CompetitorChannelDto(cc.getId(), cc.getYoutubeChannelId(), cc.getChannelName(),
                cc.getChannelUrl(), cc.getLastCheckedAt(),
                videoRepo.findByCompetitorChannelId(cc.getId()).size(),
                commentRepo.countByCompetitorChannelId(cc.getId()));
    }
}
