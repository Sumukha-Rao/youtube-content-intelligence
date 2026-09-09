package com.yci.service;

import com.yci.dto.Dto;
import com.yci.entity.*;
import com.yci.exception.BadRequestException;
import com.yci.exception.ResourceNotFoundException;
import com.yci.repository.*;
import com.yci.service.ai.LlmClient;
import com.yci.service.credentials.ApiConfig;
import com.yci.service.credentials.ApiConfigService;
import com.yci.service.ml.MlClient;
import com.yci.service.websearch.WebSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The one workflow this app runs (spec items 4, 7, 8, 9, 10):
 *
 *   ingest own channel → analyse its audience demand →
 *   ingest each competitor (video info + top comments) → analyse their demand →
 *   optional web search → build one prompt → ask the model → store the answer.
 *
 * It runs asynchronously because a single pass can take minutes: YouTube
 * ingestion is quota-paced and a local CPU model is slow. The frontend polls
 * {@code GET /api/ideas/runs/{id}} for progress.
 */
@Service
public class IdeaService {

    private static final Logger log = LoggerFactory.getLogger(IdeaService.class);

    /** Demand topics requested from the ML service per channel. */
    private static final int MAX_DEMAND_TOPICS = 12;

    private final IdeaRunRepository runRepo;
    private final ChannelService channelService;
    private final CompetitorService competitorService;
    private final UserSettingsService settingsService;
    private final IngestService ingestService;
    private final VideoRepository videoRepo;
    private final CommentRepository commentRepo;
    private final CompetitorVideoRepository competitorVideoRepo;
    private final CompetitorCommentRepository competitorCommentRepo;
    private final MlClient ml;
    private final WebSearchService webSearch;
    private final PromptBuilder promptBuilder;
    private final LlmClient llm;
    private final ApiConfigService apiConfigService;

    public IdeaService(IdeaRunRepository runRepo, ChannelService channelService,
                       CompetitorService competitorService, UserSettingsService settingsService,
                       IngestService ingestService, VideoRepository videoRepo,
                       CommentRepository commentRepo, CompetitorVideoRepository competitorVideoRepo,
                       CompetitorCommentRepository competitorCommentRepo, MlClient ml,
                       WebSearchService webSearch, PromptBuilder promptBuilder, LlmClient llm,
                       ApiConfigService apiConfigService) {
        this.runRepo = runRepo;
        this.channelService = channelService;
        this.competitorService = competitorService;
        this.settingsService = settingsService;
        this.ingestService = ingestService;
        this.videoRepo = videoRepo;
        this.commentRepo = commentRepo;
        this.competitorVideoRepo = competitorVideoRepo;
        this.competitorCommentRepo = competitorCommentRepo;
        this.ml = ml;
        this.webSearch = webSearch;
        this.promptBuilder = promptBuilder;
        this.llm = llm;
        this.apiConfigService = apiConfigService;
    }

    /** Validate up front, then queue a run. */
    @Transactional
    public IdeaRun createRun(Long userId) {
        boolean hasChannel = channelService.findChannel(userId).isPresent();
        boolean hasCompetitors = !competitorService.entities(userId).isEmpty();
        if (!hasChannel && !hasCompetitors) {
            // Spec item 10: nothing to build a prompt from.
            throw new BadRequestException(
                    "Nothing to work with yet. Add your channel or at least one competitor channel first.");
        }
        if (runRepo.existsByUserIdAndStatusIn(userId, List.of(RunStatus.QUEUED, RunStatus.RUNNING))) {
            throw new BadRequestException("An idea run is already in progress. Give it a moment to finish.");
        }
        // Fail here rather than several minutes of ingestion later.
        ApiConfig api = apiConfigService.forUser(userId);
        if (!api.youtube().isConfigured()) {
            throw new BadRequestException(
                    "No YouTube API key is available. Add your own under Settings → API access.");
        }
        if (!api.llm().isConfigured()) {
            throw new BadRequestException(
                    "No language model is configured. Add a provider URL, key and model under "
                    + "Settings → API access.");
        }
        IdeaRun run = new IdeaRun();
        run.setUserId(userId);
        run.setStatus(RunStatus.QUEUED);
        run.setMessage("Queued");
        return runRepo.save(run);
    }

    /** Fire-and-forget entry point used by the API; the frontend polls for progress. */
    @Async
    public void generateAsync(Long runId, Long userId) {
        runGeneration(runId, userId);
    }

    /**
     * Synchronous generation. The scheduled digest calls this directly because it
     * already runs on a background thread and needs the finished result to email.
     */
    public void runGeneration(Long runId, Long userId) {
        IdeaRun run = runRepo.findById(runId).orElseThrow();
        run.setStatus(RunStatus.RUNNING);
        run.setProgress(3);
        run.setMessage("Starting…");
        runRepo.save(run);

        try {
            UserSettings settings = settingsService.getOrCreate(userId);
            // Whatever this user saved under Settings → API access, with the server's
            // environment filling in every field they left blank.
            ApiConfig api = apiConfigService.forSettings(settings);
            String youtubeKey = api.youtube().apiKey();

            // ---- own channel ----
            PromptBuilder.OwnChannel own = null;
            Optional<Channel> channelOpt = channelService.findChannel(userId);
            if (channelOpt.isPresent()) {
                Channel channel = channelOpt.get();
                update(run, 8, "Fetching videos and comments from your channel…");
                ingestService.ingestOwnChannel(channel, settings, youtubeKey);

                update(run, 25, "Working out what your audience is asking for…");
                own = buildOwnChannel(channel, settings);
            }

            // ---- competitors ----
            List<CompetitorChannel> competitorEntities = competitorService.entities(userId);
            List<PromptBuilder.Competitor> competitors = new ArrayList<>();
            if (!competitorEntities.isEmpty()) {
                int span = 40; // progress 30 -> 70
                for (int i = 0; i < competitorEntities.size(); i++) {
                    CompetitorChannel cc = competitorEntities.get(i);
                    int base = 30 + (span * i) / competitorEntities.size();
                    update(run, base, "Fetching " + cc.getChannelName() + "…");
                    ingestService.ingestCompetitor(cc, settings, youtubeKey);

                    update(run, base + span / (2 * competitorEntities.size()),
                            "Analysing " + cc.getChannelName() + "'s audience…");
                    competitors.add(buildCompetitor(cc, settings));
                }
            }

            // ---- web search ----
            List<WebSearchService.SearchBundle> web = List.of();
            if (settings.isWebSearchEnabled() && webSearch.isAvailable(api.webSearch())) {
                update(run, 72, "Searching the web for current context…");
                web = webSearch.searchAll(api.webSearch(),
                        promptBuilder.searchQueriesFor(own, competitors, 4));
            }

            // ---- prompt + model ----
            update(run, 80, "Building the prompt…");
            PromptBuilder.BuiltPrompt prompt = promptBuilder.build(own, competitors, web,
                    api.llm().maxPromptChars());
            run.setPrompt(prompt.system() + "\n\n---\n\n" + prompt.user());
            run.setWebSearchUsed(prompt.webSearchUsed());
            run.setModel(api.llm().model());
            runRepo.save(run);

            update(run, 85, "Asking " + api.llm().model() + " for ideas (this can take a few minutes)…");
            String answer = llm.complete(api.llm(), prompt.system(), prompt.user());

            run.setResult(answer);
            run.setStatus(RunStatus.COMPLETED);
            run.setProgress(100);
            run.setMessage("Done");
            run.setCompletedAt(LocalDateTime.now());
            runRepo.save(run);
            log.info("Idea run {} completed for user {}", runId, userId);

        } catch (Exception e) {
            log.warn("Idea run {} failed: {}", runId, e.getMessage());
            IdeaRun failed = runRepo.findById(runId).orElse(run);
            failed.setStatus(RunStatus.FAILED);
            failed.setMessage("Failed");
            failed.setErrorMessage(e.getMessage());
            failed.setCompletedAt(LocalDateTime.now());
            runRepo.save(failed);
        }
    }

    private PromptBuilder.OwnChannel buildOwnChannel(Channel channel, UserSettings settings) {
        List<PromptBuilder.VideoInfo> videos = videoRepo.findByChannelId(channel.getId()).stream()
                .sorted(Comparator.comparing(Video::getPublishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(v -> new PromptBuilder.VideoInfo(v.getTitle(), v.getViewCount(), v.getLikeCount(),
                        v.getCommentCount(), v.getPublishedAt()))
                .toList();

        List<Comment> comments = commentRepo.findByChannelId(channel.getId()).stream()
                .sorted(Comparator.comparing(Comment::getLikeCount,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(settings.getOwnCommentLimit())
                .toList();

        MlClient.DemandResponse demand = comments.isEmpty()
                ? MlClient.DemandResponse.empty()
                : ml.demand(comments.stream().map(Comment::getText).toList(),
                            comments.stream().map(c -> c.getLikeCount() == null ? 0L : c.getLikeCount()).toList(),
                            MAX_DEMAND_TOPICS);

        return new PromptBuilder.OwnChannel(channel.getChannelName(), channel.getDescription(),
                channel.getSubscriberCount(), videos, demand);
    }

    private PromptBuilder.Competitor buildCompetitor(CompetitorChannel cc, UserSettings settings) {
        List<PromptBuilder.VideoInfo> videos = competitorVideoRepo.findByCompetitorChannelId(cc.getId()).stream()
                .sorted(Comparator.comparing(CompetitorVideo::getPublishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(v -> new PromptBuilder.VideoInfo(v.getTitle(), v.getViewCount(), v.getLikeCount(),
                        v.getCommentCount(), v.getPublishedAt()))
                .toList();

        List<CompetitorComment> comments = competitorCommentRepo.findByCompetitorChannelId(cc.getId()).stream()
                .sorted(Comparator.comparing(CompetitorComment::getLikeCount,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(settings.getCompetitorCommentLimit())
                .toList();

        MlClient.DemandResponse demand = comments.isEmpty()
                ? MlClient.DemandResponse.empty()
                : ml.demand(comments.stream().map(CompetitorComment::getText).toList(),
                            comments.stream().map(c -> c.getLikeCount() == null ? 0L : c.getLikeCount()).toList(),
                            MAX_DEMAND_TOPICS);

        return new PromptBuilder.Competitor(cc.getChannelName(), videos, demand);
    }

    // ---- reads ----
    @Transactional(readOnly = true)
    public Dto.IdeaRunDto getRun(Long userId, Long runId) {
        IdeaRun r = runRepo.findById(runId)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Idea run not found: " + runId));
        return toDto(r);
    }

    @Transactional(readOnly = true)
    public Dto.IdeaRunDto latest(Long userId) {
        return runRepo.findFirstByUserIdAndStatusOrderByIdDesc(userId, RunStatus.COMPLETED)
                .map(this::toDto).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Dto.IdeaRunDto> history(Long userId) {
        return runRepo.findTop20ByUserIdOrderByIdDesc(userId).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public boolean isGenerating(Long userId) {
        return runRepo.existsByUserIdAndStatusIn(userId, List.of(RunStatus.QUEUED, RunStatus.RUNNING));
    }

    private void update(IdeaRun run, int progress, String message) {
        run.setProgress(progress);
        run.setMessage(message);
        runRepo.save(run);
        log.info("Run {} [{}%]: {}", run.getId(), progress, message);
    }

    public Dto.IdeaRunDto toDto(IdeaRun r) {
        return new Dto.IdeaRunDto(r.getId(), r.getStatus().name(), r.getProgress(), r.getMessage(),
                r.getResult(), r.getPrompt(), r.getModel(), r.isWebSearchUsed(),
                r.getCreatedAt(), r.getCompletedAt(), r.getErrorMessage());
    }
}
