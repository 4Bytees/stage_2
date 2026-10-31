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
        // Garantizamos que el fichero SQLite se crea exclusivamente en el directorio temporal
        Path dbPath = tempDir.resolve("test_search.db");
        initTestDatabase(dbPath.toString());

        DatabaseManager dbManager = new DatabaseManager(dbPath.toString());
        HazelcastConfig hzConfig = new HazelcastConfig(null); // Sin Hazelcast para probar modo degradado
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

            // Libro 1: Mark Twain, en, 1885
            stmt.execute("INSERT INTO books VALUES (1, 'The Great Adventure', 'Mark Twain', 'en', 1885);");
            // Libro 2: Mark Twain, en, 1889
            stmt.execute("INSERT INTO books VALUES (2, 'Another Adventure', 'Mark Twain', 'en', 1889);");
            // Libro 3: Cervantes, es, 1605
            stmt.execute("INSERT INTO books VALUES (3, 'Don Quijote de la Mancha', 'Miguel de Cervantes', 'es', 1605);");
            // Libro 4: Jules Verne, fr, 1874
            stmt.execute("INSERT INTO books VALUES (4, 'The Mysterious Island', 'Jules Verne', 'fr', 1874);");

            // Postings para "adventure":
            // Libro 2 tiene freq=10, Libro 1 tiene freq=5, Libro 4 tiene freq=5
            stmt.execute("INSERT INTO postings VALUES ('adventure', 2, 10, '[1,2,3,4,5,6,7,8,9,10]');");
            stmt.execute("INSERT INTO postings VALUES ('adventure', 1, 5, '[10,20,30,40,50]');");
            stmt.execute("INSERT INTO postings VALUES ('adventure', 4, 5, '[1,5,10,15,20]');");

            // Postings para "island":
            stmt.execute("INSERT INTO postings VALUES ('island', 4, 8, '[1,2]');");
        }
    }

    private JsonObject getJson(String pathAndQuery, int expectedStatus) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + pathAndQuery))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, resp.statusCode(), "Error en ruta " + pathAndQuery + ": " + resp.body());
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
        // Para "adventure":
        // Libro 2: freq = 10 (primer puesto)
        // Libro 1: freq = 5, Libro 4: freq = 5 -> desempate book_id ASC (Libro 1 antes que 4)
        JsonObject json = getJson("/search?q=adventure", 200);
        JsonArray results = json.getAsJsonArray("results");
        assertEquals(2, results.get(0).getAsJsonObject().get("book_id").getAsInt(), "Libro 2 con mayor freq debe ir primero");
        assertEquals(1, results.get(1).getAsJsonObject().get("book_id").getAsInt(), "Libro 1 con menor book_id debe desempatar antes que Libro 4");
        assertEquals(4, results.get(2).getAsJsonObject().get("book_id").getAsInt(), "Libro 4 debe ir tercero");
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
        // "the" es stop word -> 200 con lista vacía
        JsonObject json1 = getJson("/search?q=the", 200);
        assertEquals(0, json1.get("total").getAsInt());
        assertEquals(0, json1.getAsJsonArray("results").size());

        // "and" es stop word -> 200 con lista vacía
        JsonObject json2 = getJson("/search?q=and", 200);
        assertEquals(0, json2.get("total").getAsInt());

        // "hi" tiene < 3 caracteres -> 200 con lista vacía
        JsonObject json3 = getJson("/search?q=hi", 200);
        assertEquals(0, json3.get("total").getAsInt());
    }

    @Test
    void testSearchInvalidParametersReturn400() throws Exception {
        // Falta parámetro q
        getJson("/search", 400);

        // q vacío
        getJson("/search?q=%20", 400);

        // limit < 1
        getJson("/search?q=adventure&limit=0", 400);
        getJson("/search?q=adventure&limit=-1", 400);

        // limit > 100
        getJson("/search?q=adventure&limit=101", 400);
        getJson("/search?q=adventure&limit=1000", 400);

        // limit no entero
        getJson("/search?q=adventure&limit=abc", 400);

        // offset < 0
        getJson("/search?q=adventure&offset=-5", 400);

        // offset no entero
        getJson("/search?q=adventure&offset=xyz", 400);

        // year no entero
        getJson("/search?q=adventure&year=abc", 400);

        // year no 4 dígitos
        getJson("/search?q=adventure&year=999", 400);
        getJson("/search?q=adventure&year=10000", 400);

        // idioma no soportado
        getJson("/search?q=adventure&language=klingon", 400);
    }

    @Test
    void testSearchPagination() throws Exception {
        // Página 1: limit=1, offset=0
        JsonObject p1 = getJson("/search?q=adventure&limit=1&offset=0", 200);
        assertEquals(3, p1.get("total").getAsInt());
        assertEquals(1, p1.getAsJsonArray("results").size());
        assertEquals(2, p1.getAsJsonArray("results").get(0).getAsJsonObject().get("book_id").getAsInt());

        // Página 2: limit=1, offset=1
        JsonObject p2 = getJson("/search?q=adventure&limit=1&offset=1", 200);
        assertEquals(3, p2.get("total").getAsInt());
        assertEquals(1, p2.getAsJsonArray("results").size());
        assertEquals(1, p2.getAsJsonArray("results").get(0).getAsJsonObject().get("book_id").getAsInt());

        // Página 3: limit=1, offset=2
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

    @Test
    void testDatabaseErrorReturns500(@TempDir Path errorDir) throws Exception {
        // Base de datos sin tablas creadas para forzar SQLException en runtime
        Path corruptDb = errorDir.resolve("corrupt.db");
        Files.writeString(corruptDb, ""); // Archivo SQLite vacío sin esquema
        DatabaseManager badDbManager = new DatabaseManager(corruptDb.toString());
        SearchController badController = new SearchController(new HazelcastConfig(null), badDbManager, gson);

        Javalin badApp = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
        }).start(0);

        try {
            badApp.get("/search", badController::handleSearch);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + badApp.port() + "/search?q=adventure"))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            assertEquals(500, resp.statusCode(), "Debe retornar HTTP 500 ante fallo en base de datos");
            JsonObject json = gson.fromJson(resp.body(), JsonObject.class);
            assertEquals("Internal Server Error", json.get("error").getAsString());
            assertTrue(json.get("message").getAsString().contains("Database error occurred"));
        } finally {
            badApp.stop();
        }
    }
}
