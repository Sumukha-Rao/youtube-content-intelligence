package com.yci.service;

import com.yci.config.AppProperties;
import com.yci.exception.BadRequestException;
import com.yci.service.ml.MlClient;
import com.yci.service.websearch.WebSearchService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Assembles the single prompt the model answers (spec item 8):
 *
 *   own-channel audience demand  +  competitor video performance
 *   +  competitor audience demand  +  optional live web context
 *
 * The audience-demand analysis is never shown to the user (spec item 5) — it
 * exists only to give the model evidence. The assembled prompt is stored with the
 * run so it can be inspected via "View full prompt".
 *
 * With neither a channel nor any competitor there is nothing to reason about, so
 * building fails loudly rather than asking the model to invent ideas (item 10).
 */
@Service
public class PromptBuilder {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AppProperties props;

    public PromptBuilder(AppProperties props) {
        this.props = props;
    }

    // ---- inputs ----
    public record VideoInfo(String title, Long views, Long likes, Long comments, LocalDateTime publishedAt) {}

    public record OwnChannel(String name, String description, Long subscribers,
                             List<VideoInfo> videos, MlClient.DemandResponse demand) {}

    public record Competitor(String name, List<VideoInfo> videos, MlClient.DemandResponse demand) {}

    public record BuiltPrompt(String system, String user, boolean webSearchUsed, List<String> searchQueries) {}

    private static final String SYSTEM = """
            You are a YouTube content strategist. You are given evidence gathered from a
            creator's own audience, from competing channels, and (when available) from a
            live web search.

            Rules:
            - Ground every suggestion in the evidence provided. Quote or paraphrase the
              audience comments that justify an idea.
            - Never invent statistics. If you cite a number, it must appear in the evidence.
            - Prefer topics the audience explicitly asked for over topics you merely infer.
            - Avoid topics the creator has clearly already covered, unless the evidence
              shows demand for a deeper or updated take (say so if that is the case).
            - Be specific: "Kafka consumer group rebalancing, hands-on" beats "Kafka basics".

            Answer in Markdown. Suggest 5 to 7 video ideas, ranked strongest first, each
            appearing exactly once. Use this structure and no other:

            ### <number>. <video title>
            - **Why now:** one or two sentences tied to the evidence.
            - **Evidence:** the audience requests / competitor activity / web findings behind it.
            - **Angle:** how to approach it so it stands out.
            - **Outline:** 4-6 bullet points.
            - **Differentiation:** what makes this better than what competitors published.

            Finish with a short "## What to make first" paragraph recommending one idea.

            Do not repeat the ideas in a second list, summary or recap section, and do not
            restate these instructions.
            """;

    /** Uses the server's prompt budget — the shape most callers and the tests want. */
    public BuiltPrompt build(OwnChannel own, List<Competitor> competitors,
                             List<WebSearchService.SearchBundle> webResults) {
        return build(own, competitors, webResults, props.getLlm().getMaxPromptChars());
    }

    /**
     * @param maxPromptChars budget for the assembled user message. It comes from the
     *                       run's resolved LLM configuration, because a user pointing
     *                       at a small local model needs a smaller prompt than one
     *                       pointing at a hosted 128k-context model.
     */
    public BuiltPrompt build(OwnChannel own, List<Competitor> competitors,
                             List<WebSearchService.SearchBundle> webResults, int maxPromptChars) {
        boolean hasOwn = own != null;
        boolean hasCompetitors = competitors != null && !competitors.isEmpty();
        if (!hasOwn && !hasCompetitors) {
            throw new BadRequestException(
                    "There is nothing to generate ideas from. Add your channel, at least one competitor "
                    + "channel, or both — then try again.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Today's date: ").append(LocalDate.now()).append("\n\n");

        if (hasOwn) {
            appendOwnChannel(sb, own);
        } else {
            sb.append("# The creator's own channel\n\n")
              .append("_Not provided — base the ideas on competitor evidence alone, and suggest topics ")
              .append("that would work for a channel entering this space._\n\n");
        }

        if (hasCompetitors) {
            sb.append("# Competitor channels\n\n");
            for (Competitor c : competitors) appendCompetitor(sb, c);
        } else {
            sb.append("# Competitor channels\n\n_None provided._\n\n");
        }

        boolean webUsed = webResults != null && !webResults.isEmpty();
        if (webUsed) appendWebResults(sb, webResults);

        sb.append("# Task\n\n")
          .append("Using only the evidence above, suggest the videos this creator should make next.\n");

        String user = truncate(sb.toString(), maxPromptChars);
        List<String> queries = webUsed
                ? webResults.stream().map(WebSearchService.SearchBundle::query).toList()
                : List.of();
        return new BuiltPrompt(SYSTEM, user, webUsed, queries);
    }

    private void appendOwnChannel(StringBuilder sb, OwnChannel own) {
        sb.append("# The creator's own channel\n\n");
        sb.append("- **Name:** ").append(nz(own.name())).append("\n");
        if (own.subscribers() != null && own.subscribers() > 0) {
            sb.append("- **Subscribers:** ").append(own.subscribers()).append("\n");
        }
        if (own.description() != null && !own.description().isBlank()) {
            sb.append("- **About:** ").append(oneLine(own.description(), 500)).append("\n");
        }
        sb.append("\n");

        if (own.videos() != null && !own.videos().isEmpty()) {
            sb.append("## Videos already published (do not simply repeat these)\n\n");
            appendVideoTable(sb, own.videos(), 25);
        }

        sb.append("## What this channel's audience is asking for\n\n");
        appendDemand(sb, own.demand(), true);
    }

    private void appendCompetitor(StringBuilder sb, Competitor c) {
        sb.append("## ").append(nz(c.name())).append("\n\n");
        if (c.videos() != null && !c.videos().isEmpty()) {
            sb.append("### Their recent videos\n\n");
            appendVideoTable(sb, c.videos(), 15);
        }
        sb.append("### What their audience is asking for\n\n");
        appendDemand(sb, c.demand(), false);
    }

    private void appendVideoTable(StringBuilder sb, List<VideoInfo> videos, int limit) {
        sb.append("| Title | Views | Likes | Comments | Published |\n");
        sb.append("|---|---|---|---|---|\n");
        videos.stream().limit(limit).forEach(v -> sb
                .append("| ").append(oneLine(v.title(), 120))
                .append(" | ").append(nzl(v.views()))
                .append(" | ").append(nzl(v.likes()))
                .append(" | ").append(nzl(v.comments()))
                .append(" | ").append(v.publishedAt() == null ? "-" : v.publishedAt().format(DATE))
                .append(" |\n"));
        sb.append("\n");
    }

    private void appendDemand(StringBuilder sb, MlClient.DemandResponse demand, boolean withExamples) {
        if (demand == null || demand.demand_topics() == null || demand.demand_topics().isEmpty()) {
            sb.append("_No clear requests found in the comments analysed._\n\n");
            return;
        }
        sb.append("Ranked from ").append(demand.comments_analyzed()).append(" comments (")
          .append(demand.requests_found()).append(" explicit requests found):\n\n");

        for (MlClient.DemandTopic t : demand.demand_topics()) {
            sb.append("- **").append(t.label()).append("**");
            if (t.request_count() > 0) {
                sb.append(" — ").append(t.request_count())
                  .append(t.request_count() == 1 ? " direct request" : " direct requests");
            } else {
                sb.append(" — mentioned in ").append(t.mention_count()).append(" comments");
            }
            sb.append("\n");
            if (withExamples && t.examples() != null) {
                t.examples().stream().limit(2).forEach(ex ->
                        sb.append("    - _\"").append(oneLine(ex, 220)).append("\"_\n"));
            }
        }
        sb.append("\n");
    }

    private void appendWebResults(StringBuilder sb, List<WebSearchService.SearchBundle> bundles) {
        sb.append("# Live web context (searched ").append(LocalDate.now()).append(")\n\n");
        for (WebSearchService.SearchBundle b : bundles) {
            sb.append("## Search: \"").append(b.query()).append("\"\n\n");
            for (WebSearchService.SearchResult r : b.results()) {
                sb.append("- **").append(oneLine(r.title(), 160)).append("** — ")
                  .append(oneLine(r.snippet(), 300));
                if (r.url() != null && !r.url().isBlank()) {
                    sb.append(" (").append(oneLine(r.url(), 160)).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
    }

    /**
     * Search queries for the strongest demand topics across every source.
     *
     * Topics the audience explicitly asked for come first; vaguer "people keep
     * mentioning this" topics are only used to fill remaining slots, and single-word
     * ones are skipped entirely. A weak label makes a weak query — searching a bare
     * "Price" or "Linus" returns cost-of-living listicles and Linux articles, which
     * then get fed to the model as if they were evidence.
     */
    public List<String> searchQueriesFor(OwnChannel own, List<Competitor> competitors, int max) {
        List<MlClient.DemandTopic> all = new ArrayList<>();
        if (own != null && own.demand() != null) all.addAll(own.demand().demand_topics());
        if (competitors != null) {
            for (Competitor c : competitors) {
                if (c.demand() != null) all.addAll(c.demand().demand_topics());
            }
        }

        List<String> requested = all.stream()
                .filter(t -> t.request_count() > 0)
                .sorted(Comparator.comparingDouble(MlClient.DemandTopic::demand_score).reversed())
                .map(MlClient.DemandTopic::label)
                .filter(PromptBuilder::isSearchable)
                .distinct()
                .toList();

        List<String> mentioned = all.stream()
                .filter(t -> t.request_count() == 0)
                .sorted(Comparator.comparingDouble(MlClient.DemandTopic::demand_score).reversed())
                .map(MlClient.DemandTopic::label)
                .filter(PromptBuilder::isSearchable)
                .distinct()
                .toList();

        List<String> out = new ArrayList<>(requested);
        for (String m : mentioned) {
            if (out.size() >= max) break;
            if (!out.contains(m)) out.add(m);
        }
        return out.stream().limit(max).toList();
    }

    /** A query needs at least two words to stand a chance of staying on topic. */
    private static boolean isSearchable(String label) {
        return label != null && label.trim().split("\\s+").length >= 2;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n\n_[evidence truncated to fit the model's context window]_\n";
    }

    private static String oneLine(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").replace("|", "/").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static long nzl(Long l) { return l == null ? 0 : l; }
}
