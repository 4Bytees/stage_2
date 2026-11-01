package com.fourbytees.search;

import com.fourbytees.search.config.HazelcastConfig;
import com.fourbytees.search.controller.BookController;
import com.fourbytees.search.controller.SearchController;
import com.fourbytees.search.controller.StatusController;
import com.fourbytees.search.db.DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SearchServiceTest {

    private Javalin app;
    private int port;
    private HttpClient client;
    private final Gson gson = new Gson();

    @BeforeAll
    void setup(@TempDir Path tempDir) throws Exception {
        // Ensure the SQLite database file is created exclusively inside the temporary directory.
        Path dbPath = tempDir.resolve("test_search.db");
        initTestDatabase(dbPath.toString());

        DatabaseManager dbManager = new DatabaseManager(dbPath.toString());
        HazelcastConfig hzConfig = new HazelcastConfig(null); // Without Hazelcast to verify degraded mode.
        SearchController searchController = new SearchController(hzConfig, dbManager, gson);
        BookController bookController = new BookController(dbManager, gson);
        StatusController statusController = new StatusController(hzConfig, dbManager, gson);

        app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
        }).start(0);

        port = app.port();

        app.get("/search", searchController::handleSearch);
        app.get("/books/{id}", bookController::handleGetBookById);
        app.get("/search/filters", bookController::handleGetFilters);
        app.get("/status", statusController::handleStatus);

        client = HttpClient.newHttpClient();
    }

    @AfterAll
    void tearDown() {
        if (app != null) {
            try {
                app.stop();
            } catch (Exception ignored) {}
        }
    }

    private void initTestDatabase(String dbPath) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement()) {

            stmt.execute("PRAGMA journal_mode=WAL;");

            stmt.execute("""
                CREATE TABLE books (
                    book_id INTEGER PRIMARY KEY,
                    title TEXT,
                    author TEXT,
                    language TEXT,
                    year INTEGER
                );
            """);

            stmt.execute("""
                CREATE TABLE postings (
                    term TEXT,
                    book_id INTEGER,
                    freq INTEGER,
                    positions TEXT,
                    PRIMARY KEY (term, book_id)
                );
            """);

            // Book 1: Mark Twain, en, 1885
            stmt.execute("INSERT INTO books VALUES (1, 'The Great Adventure', 'Mark Twain', 'en', 1885);");
            // Book 2: Mark Twain, en, 1889
            stmt.execute("INSERT INTO books VALUES (2, 'Another Adventure', 'Mark Twain', 'en', 1889);");
            // Book 3: Cervantes, es, 1605
            stmt.execute("INSERT INTO books VALUES (3, 'Don Quijote de la Mancha', 'Miguel de Cervantes', 'es', 1605);");
            // Book 4: Jules Verne, fr, 1874
            stmt.execute("INSERT INTO books VALUES (4, 'The Mysterious Island', 'Jules Verne', 'fr', 1874);");

            // Postings for "adventure":
            // Book 2 has freq=10, Book 1 has freq=5, Book 4 has freq=5
            stmt.execute("INSERT INTO postings VALUES ('adventure', 2, 10, '[1,2,3,4,5,6,7,8,9,10]');");
            stmt.execute("INSERT INTO postings VALUES ('adventure', 1, 5, '[10,20,30,40,50]');");
            stmt.execute("INSERT INTO postings VALUES ('adventure', 4, 5, '[1,5,10,15,20]');");

            // Postings for "island":
            stmt.execute("INSERT INTO postings VALUES ('island', 4, 8, '[1,2]');");
        }
    }

    private JsonObject getJson(String pathAndQuery, int expectedStatus) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + pathAndQuery))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, resp.statusCode(), "Unexpected status for route " + pathAndQuery + ": " + resp.body());
        return gson.fromJson(resp.body(), JsonObject.class);
    }

    @Test
    void testSearchSimple() throws Exception {
        JsonObject json = getJson("/search?q=adventure", 200);
        assertEquals("adventure", json.get("query").getAsString());
        assertEquals(3, json.get("total").getAsInt());
        JsonArray results = json.getAsJsonArray("results");
        assertEquals(3, results.size());
    }

    @Test
    void testSearchOrderTfIdfAndTieBreak() throws Exception {
        // For "adventure":
        // Book 2: freq = 10 (first place)
        // Book 1: freq = 5, Book 4: freq = 5 -> tie-break by book_id ASC (Book 1 before Book 4)
        JsonObject json = getJson("/search?q=adventure", 200);
        JsonArray results = json.getAsJsonArray("results");
        assertEquals(2, results.get(0).getAsJsonObject().get("book_id").getAsInt(), "Book 2 with highest freq must rank first");
        assertEquals(1, results.get(1).getAsJsonObject().get("book_id").getAsInt(), "Book 1 with lower book_id must rank before Book 4 on tie");
        assertEquals(4, results.get(2).getAsJsonObject().get("book_id").getAsInt(), "Book 4 must rank third");
    }

    @Test
    void testSearchFilterAuthor() throws Exception {
        JsonObject json = getJson("/search?q=adventure&author=twain", 200);
        assertEquals(2, json.get("total").getAsInt());
        JsonArray results = json.getAsJsonArray("results");
        for (int i = 0; i < results.size(); i++) {
            assertEquals("Mark Twain", results.get(i).getAsJsonObject().get("author").getAsString());
        }
    }

    @Test
    void testSearchFilterLanguage() throws Exception {
        JsonObject json = getJson("/search?q=adventure&language=fr", 200);
        assertEquals(1, json.get("total").getAsInt());
        assertEquals(4, json.getAsJsonArray("results").get(0).getAsJsonObject().get("book_id").getAsInt());
    }

    @Test
    void testSearchFilterYear() throws Exception {
        JsonObject json = getJson("/search?q=adventure&year=1885", 200);
        assertEquals(1, json.get("total").getAsInt());
        assertEquals(1, json.getAsJsonArray("results").get(0).getAsJsonObject().get("book_id").getAsInt());
    }

    @Test
    void testSearchCombinedFilters() throws Exception {
        JsonObject json = getJson("/search?q=adventure&author=twain&language=en&year=1885", 200);
        assertEquals(1, json.get("total").getAsInt());
        assertEquals(1, json.getAsJsonArray("results").get(0).getAsJsonObject().get("book_id").getAsInt());
    }

    @Test
    void testSearchEmptyResults() throws Exception {
        JsonObject json = getJson("/search?q=nonexistentterm", 200);
        assertEquals(0, json.get("total").getAsInt());
        assertEquals(0, json.getAsJsonArray("results").size());
    }

    @Test
    void testSearchStopWordsReturnEmpty200() throws Exception {
        // "the" is a stop word -> 200 with empty list
        JsonObject json1 = getJson("/search?q=the", 200);
        assertEquals(0, json1.get("total").getAsInt());
        assertEquals(0, json1.getAsJsonArray("results").size());

        // "and" is a stop word -> 200 with empty list
        JsonObject json2 = getJson("/search?q=and", 200);
        assertEquals(0, json2.get("total").getAsInt());
        assertEquals(0, json2.getAsJsonArray("results").size());

        // "hi" has fewer than 3 characters -> 200 with empty list
        JsonObject json3 = getJson("/search?q=hi", 200);
        assertEquals(0, json3.get("total").getAsInt());
    }

    @Test
    void testSearchInvalidParametersReturn400() throws Exception {
        // Missing q parameter
        getJson("/search", 400);

        // Blank q
        getJson("/search?q=%20", 400);

        // limit < 1
        getJson("/search?q=adventure&limit=0", 400);
        getJson("/search?q=adventure&limit=-1", 400);

        // limit > 100
        getJson("/search?q=adventure&limit=101", 400);
        getJson("/search?q=adventure&limit=1000", 400);

        // Non-integer limit
        getJson("/search?q=adventure&limit=abc", 400);

        // offset < 0
        getJson("/search?q=adventure&offset=-5", 400);

        // Non-integer offset
        getJson("/search?q=adventure&offset=xyz", 400);

        // Non-integer year
        getJson("/search?q=adventure&year=abc", 400);

        // Non-4-digit year
        getJson("/search?q=adventure&year=999", 400);
        getJson("/search?q=adventure&year=10000", 400);

        // Unsupported language
        getJson("/search?q=adventure&language=klingon", 400);
    }

    @Test
    void testSearchPagination() throws Exception {
        // Page 1: limit=1, offset=0
        JsonObject p1 = getJson("/search?q=adventure&limit=1&offset=0", 200);
        assertEquals(3, p1.get("total").getAsInt());
        assertEquals(1, p1.getAsJsonArray("results").size());
        assertEquals(2, p1.getAsJsonArray("results").get(0).getAsJsonObject().get("book_id").getAsInt());

        // Page 2: limit=1, offset=1
        JsonObject p2 = getJson("/search?q=adventure&limit=1&offset=1", 200);
        assertEquals(3, p2.get("total").getAsInt());
        assertEquals(1, p2.getAsJsonArray("results").size());
        assertEquals(1, p2.getAsJsonArray("results").get(0).getAsJsonObject().get("book_id").getAsInt());

        // Page 3: limit=1, offset=2
        JsonObject p3 = getJson("/search?q=adventure&limit=1&offset=2", 200);
        assertEquals(3, p3.get("total").getAsInt());
        assertEquals(1, p3.getAsJsonArray("results").size());
        assertEquals(4, p3.getAsJsonArray("results").get(0).getAsJsonObject().get("book_id").getAsInt());
    }

    @Test
    void testStatusDegradedWhenHazelcastDisconnected() throws Exception {
        JsonObject status = getJson("/status", 200);
        assertEquals("search-service", status.get("service").getAsString());
        assertEquals("degraded", status.get("status").getAsString());
        assertEquals("disconnected", status.get("hazelcast_status").getAsString());
        assertEquals("read-only-connected", status.get("sqlite_status").getAsString());
    }

    private HttpResponse<String> rawGet(Javalin server, String pathAndQuery) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.port() + pathAndQuery))
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Asserts the exact generic body and that nothing internal leaks to the client. */
    private void assertGenericInternalError(HttpResponse<String> resp, String... forbiddenFragments) {
        assertEquals(500, resp.statusCode(), "A storage or runtime failure must return HTTP 500");
        JsonObject json = gson.fromJson(resp.body(), JsonObject.class);
        assertEquals(1, json.size(), "The 500 body must contain only the 'error' field");
        assertEquals("Internal server error", json.get("error").getAsString());
        for (String fragment : forbiddenFragments) {
            assertFalse(resp.body().contains(fragment), "The response leaks internal detail: " + fragment);
        }
    }

    @Test
    void testDatabaseErrorReturnsGeneric500(@TempDir Path errorDir) throws Exception {
        // A database without tables forces an SQLException at query time.
        Path corruptDb = errorDir.resolve("corrupt.db");
        Files.writeString(corruptDb, "");
        DatabaseManager badDbManager = new DatabaseManager(corruptDb.toString());
        SearchController badSearch = new SearchController(new HazelcastConfig(null), badDbManager, gson);
        BookController badBooks = new BookController(badDbManager, gson);

        Javalin badApp = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
        }).start(0);

        try {
            badApp.get("/search", badSearch::handleSearch);
            badApp.get("/books/{id}", badBooks::handleGetBookById);
            badApp.get("/search/filters", badBooks::handleGetFilters);

            String[] leaks = {"no such table", "SQLITE", "corrupt.db", "SQLException", "Database error"};
            assertGenericInternalError(rawGet(badApp, "/search?q=adventure"), leaks);
            assertGenericInternalError(rawGet(badApp, "/books/1"), leaks);
            assertGenericInternalError(rawGet(badApp, "/search/filters"), leaks);
        } finally {
            badApp.stop();
        }
    }

    @Test
    void testUnexpectedRuntimeErrorReturnsGeneric500() throws Exception {
        DatabaseManager failingManager = new DatabaseManager("unused.db") {
            @Override
            public SearchResult searchByTerm(String rawQuery, String authorFilter, String langFilter,
                                             Integer yearFilter, int offset, int limit) {
                throw new IllegalStateException("secret internal detail /var/lib/internal/path");
            }
        };
        SearchController failingSearch = new SearchController(new HazelcastConfig(null), failingManager, gson);

        Javalin failingApp = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
        }).start(0);

        try {
            failingApp.get("/search", failingSearch::handleSearch);
            assertGenericInternalError(rawGet(failingApp, "/search?q=adventure"),
                    "secret internal detail", "/var/lib/internal/path", "IllegalStateException");
        } finally {
            failingApp.stop();
        }
    }

    @Test
    void testEmptyResultInvalidRequestAndInternalErrorStayDistinct() throws Exception {
        // Valid query without matches: 200 with an empty result list.
        JsonObject empty = getJson("/search?q=nonexistentterm", 200);
        assertEquals(0, empty.getAsJsonArray("results").size());

        // Invalid request: 400 with the documented error and message fields.
        JsonObject invalid = getJson("/search?q=adventure&limit=0", 400);
        assertEquals("Bad Request", invalid.get("error").getAsString());
        assertTrue(invalid.has("message"));
    }
}
