package com.yci;

import com.sun.net.httpserver.HttpServer;
import com.yci.exception.LlmServiceException;
import com.yci.service.ai.LlmClient;
import com.yci.service.credentials.ApiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LlmClientTest {
    private HttpServer server;
    private LlmClient client;
    private ApiConfig.Llm config;
    private final AtomicInteger attempts = new AtomicInteger();
    private final Set<Integer> connections = ConcurrentHashMap.newKeySet();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new LlmClient(WebClient.builder());
        config = new ApiConfig.Llm("http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", "test-model", 5, 24000, false);
    }

    @AfterEach void stop() { server.stop(0); }

    private void respond(int failures, int status) {
        server.createContext("/chat/completions", exchange -> {
            int attempt = attempts.incrementAndGet();
            connections.add(exchange.getRemoteAddress().getPort());
            exchange.getRequestBody().readAllBytes();
            if (attempt <= failures) {
                exchange.close(); // Provider drops the connection after receiving the request.
                return;
            }
            byte[] body = (status == 200
                    ? "{\"choices\":[{\"message\":{\"content\":\"Recovered answer\"}}]}"
                    : "{\"error\":{\"message\":\"Rejected\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    @Test void droppedConnectionRetriesOnceAndRecovers() {
        respond(1, 200);
        assertEquals("Recovered answer", client.complete(config, "System", "User"));
        assertEquals(2, attempts.get());
        assertEquals(2, connections.size());
    }

    @Test void persistentConnectionFailureStopsAfterTwoAttempts() {
        respond(Integer.MAX_VALUE, 200);
        var error = assertThrows(LlmServiceException.class,
                () -> client.complete(config, "System", "User"));
        assertEquals(2, attempts.get());
        assertTrue(error.getMessage().contains("The model did not respond"));
        assertFalse(error.getMessage().contains("Retries exhausted"));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 402, 403, 404, 429, 500})
    void providerRejectionsAreNotRetried(int status) {
        respond(0, status);
        var error = assertThrows(LlmServiceException.class,
                () -> client.complete(config, "System", "User"));
        assertEquals(1, attempts.get());
        assertTrue(error.getMessage().contains(String.valueOf(status)));
    }

    @Test void slowGenerationTimesOutWithoutStartingAnotherRequest() {
        server.createContext("/chat/completions", exchange -> {
            attempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        var impatient = config.withTimeout(1);
        assertThrows(LlmServiceException.class,
                () -> client.complete(impatient, "System", "User"));
        assertEquals(1, attempts.get());
    }

    @Test void consecutiveCallsUseFreshConnections() {
        respond(0, 200);
        assertEquals("Recovered answer", client.complete(config, "System", "First"));
        assertEquals("Recovered answer", client.complete(config, "System", "Second"));
        assertEquals(2, attempts.get());
        assertEquals(2, connections.size());
    }
}
