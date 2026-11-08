package com.fourbytees.indexing;

import com.fourbytees.indexing.controller.IndexingController;
import com.fourbytees.indexing.service.IndexRebuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies the HTTP status mapping and JSON payloads of {@code POST /index/rebuild}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IndexingControllerTest {

    /** Rebuilder whose outcome is scripted by the test. */
    private static class ScriptedRebuilder extends IndexRebuilder {
        private volatile Outcome outcome;

        interface Outcome {
            RebuildResult run() throws RebuildFailedException;
        }

        ScriptedRebuilder() {
            super(null, null, null, 1);
        }

        @Override
        public RebuildResult rebuild() throws RebuildFailedException {
            return outcome.run();
        }
    }

    private final Gson gson = new Gson();
    private final ScriptedRebuilder rebuilder = new ScriptedRebuilder();
    private final HttpClient client = HttpClient.newHttpClient();
    private Javalin app;

    @BeforeAll
    void setUp() {
        IndexingController controller = new IndexingController(null, null, rebuilder);
        app = Javalin.create(config -> config.http.defaultContentType = "application/json").start(0);
        app.post("/index/rebuild", controller::rebuildAll);
    }

    @AfterAll
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    private HttpResponse<String> postRebuild() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/index/rebuild"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void successfulRebuildReturns200WithCounts() throws Exception {
        rebuilder.outcome = () -> new IndexRebuilder.RebuildResult(3, 1.234);

        HttpResponse<String> response = postRebuild();

        assertEquals(200, response.statusCode());
        JsonObject json = gson.fromJson(response.body(), JsonObject.class);
        assertEquals(2, json.size());
        assertEquals(3, json.get("books_processed").getAsInt());
        assertEquals("1.23s", json.get("elapsed_time").getAsString());
    }

    @Test
    void concurrentRebuildReturns409() throws Exception {
        rebuilder.outcome = () -> {
            throw new IndexRebuilder.RebuildInProgressException();
        };

        HttpResponse<String> response = postRebuild();

        assertEquals(409, response.statusCode());
        JsonObject json = gson.fromJson(response.body(), JsonObject.class);
        assertEquals(1, json.size());
        assertEquals("An index rebuild is already in progress", json.get("error").getAsString());
    }

    @Test
    void failedRebuildReturns500WithoutInternalDetails() throws Exception {
        rebuilder.outcome = () -> {
            throw new IndexRebuilder.RebuildFailedException(
                    "Index rebuild failed", new IllegalStateException("secret /internal/path detail"));
        };

        HttpResponse<String> response = postRebuild();

        assertEquals(500, response.statusCode());
        JsonObject json = gson.fromJson(response.body(), JsonObject.class);
        assertEquals(1, json.size());
        assertEquals("Index rebuild failed. The previous index remains active.", json.get("error").getAsString());
        assertFalse(response.body().contains("secret"));
        assertFalse(response.body().contains("/internal/path"));
    }
}
