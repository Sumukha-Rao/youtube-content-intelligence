package com.yci;

import com.yci.config.AppProperties;
import com.yci.exception.BadRequestException;
import com.yci.service.PromptBuilder;
import com.yci.service.ml.MlClient;
import com.yci.service.websearch.WebSearchService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The prompt is the product here, so its assembly rules are tested directly:
 * what goes in, what must never go in, and the "nothing to work with" guard.
 */
class PromptBuilderTest {

    private PromptBuilder builder() {
        return new PromptBuilder(new AppProperties());
    }

    private MlClient.DemandResponse demand(String label, int requests) {
        return new MlClient.DemandResponse(
                List.of(new MlClient.DemandTopic(label, 9.5, requests, requests,
                        List.of("please make a video on " + label), "requests")),
                List.of(), 500, requests, "sentence-transformers");
    }

    private PromptBuilder.OwnChannel ownChannel() {
        return new PromptBuilder.OwnChannel("My Channel", "Backend tutorials", 48200L,
                List.of(new PromptBuilder.VideoInfo("Docker Basics", 12000L, 400L, 55L,
                        LocalDateTime.now().minusDays(10))),
                demand("Kafka Consumer Groups", 12));
    }

    private PromptBuilder.Competitor competitor() {
        return new PromptBuilder.Competitor("Rival Channel",
                List.of(new PromptBuilder.VideoInfo("Kafka Deep Dive", 90000L, 3000L, 210L,
                        LocalDateTime.now().minusDays(3))),
                demand("Event Sourcing", 7));
    }

    @Test
    void failsWhenBothChannelAndCompetitorsAreMissing() {
        // Spec item 10: an empty prompt must produce a clear error, not a request
        // for the model to invent something.
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> builder().build(null, List.of(), List.of()));
        assertTrue(ex.getMessage().toLowerCase().contains("nothing to generate ideas from"));
    }

    @Test
    void ownChannelOnlyIsEnough() {
        PromptBuilder.BuiltPrompt p = builder().build(ownChannel(), List.of(), List.of());
        assertTrue(p.user().contains("My Channel"));
        assertTrue(p.user().contains("Kafka Consumer Groups"));
        assertTrue(p.user().contains("12 direct requests"));
        assertFalse(p.webSearchUsed());
    }

    @Test
    void competitorsOnlyIsEnough() {
        PromptBuilder.BuiltPrompt p = builder().build(null, List.of(competitor()), List.of());
        assertTrue(p.user().contains("Rival Channel"));
        assertTrue(p.user().contains("Kafka Deep Dive"));
        assertTrue(p.user().contains("Not provided"), "should say the own channel was skipped");
    }

    @Test
    void combinesEverySourceIncludingWebResults() {
        List<WebSearchService.SearchBundle> web = List.of(new WebSearchService.SearchBundle(
                "kafka 2026",
                List.of(new WebSearchService.SearchResult("Kafka 4.0 released", "New rebalance protocol",
                        "https://example.com/kafka"))));

        PromptBuilder.BuiltPrompt p = builder().build(ownChannel(), List.of(competitor()), web);

        assertTrue(p.user().contains("My Channel"));
        assertTrue(p.user().contains("Rival Channel"));
        assertTrue(p.user().contains("Event Sourcing"));
        assertTrue(p.user().contains("Kafka 4.0 released"));
        assertTrue(p.webSearchUsed());
        assertEquals(List.of("kafka 2026"), p.searchQueries());
    }

    @Test
    void truncatesAnOversizedPromptInsteadOfOverflowingTheModel() {
        AppProperties props = new AppProperties();
        props.getLlm().setMaxPromptChars(400);
        PromptBuilder small = new PromptBuilder(props);

        PromptBuilder.BuiltPrompt p = small.build(ownChannel(), List.of(competitor()), List.of());
        assertTrue(p.user().length() < 600);
        assertTrue(p.user().contains("truncated"));
    }

    @Test
    void searchQueriesComeFromTheStrongestDemandTopics() {
        List<String> queries = builder().searchQueriesFor(ownChannel(), List.of(competitor()), 4);
        assertTrue(queries.stream().anyMatch(q -> q.startsWith("Kafka Consumer Groups")));
        assertTrue(queries.stream().anyMatch(q -> q.startsWith("Event Sourcing")));
    }

    @Test
    void searchQueriesSkipSingleWordAndUnrequestedTopics() {
        // A bare "Price" or "Linus" returns cost-of-living listicles and Linux
        // articles that the model would then cite as evidence.
        MlClient.DemandResponse noisy = new MlClient.DemandResponse(
                List.of(
                        new MlClient.DemandTopic("Price", 8.0, 0, 12, List.of(), "mentions"),
                        new MlClient.DemandTopic("Linus", 7.0, 0, 10, List.of(), "mentions"),
                        new MlClient.DemandTopic("Cable Management Guide", 6.0, 3, 3, List.of(), "requests")),
                List.of(), 150, 3, "tfidf-fallback");

        PromptBuilder.OwnChannel channel = new PromptBuilder.OwnChannel(
                "Tech Channel", "gear reviews", 100L, List.of(), noisy);

        List<String> queries = builder().searchQueriesFor(channel, List.of(), 4);

        assertEquals(List.of("Cable Management Guide"), queries);
    }

    @Test
    void explicitlyRequestedTopicsAreSearchedBeforeMerelyMentionedOnes() {
        MlClient.DemandResponse mixed = new MlClient.DemandResponse(
                List.of(
                        new MlClient.DemandTopic("Steam Deck Setup", 20.0, 0, 40, List.of(), "mentions"),
                        new MlClient.DemandTopic("Water Cooling Loop", 3.0, 2, 2, List.of(), "requests")),
                List.of(), 300, 2, "tfidf-fallback");

        PromptBuilder.OwnChannel channel = new PromptBuilder.OwnChannel(
                "Tech Channel", "gear reviews", 100L, List.of(), mixed);

        List<String> queries = builder().searchQueriesFor(channel, List.of(), 4);

        assertEquals("Water Cooling Loop", queries.get(0),
                "an explicit request should outrank a higher-scoring mention");
        assertTrue(queries.contains("Steam Deck Setup"));
    }
}
