package com.fourbytees.benchmark;

import com.fourbytees.search.controller.BookController;
import com.fourbytees.search.controller.SearchController;
import com.fourbytees.search.db.DatabaseManager;
import com.fourbytees.search.model.Book;
import com.google.gson.Gson;
import io.javalin.Javalin;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatFactory;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * JMH microbenchmarks for Stage 2 SQLite read-only search queries and HTTP search client:
 * unfiltered keyword search, multi-filter keyword search, book lookup by ID,
 * and end-to-end HTTP search request latency over Javalin.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@State(Scope.Benchmark)
public class SearchMicroBenchmark {

    private Path tempDir;
    private DatabaseManager databaseManager;
    private Javalin app;
    private HttpClient httpClient;
    private HttpRequest searchHttpRequest;

    @Setup(Level.Trial)
    public void setUp() throws IOException, SQLException {
        tempDir = Files.createTempDirectory("jmh-search-");
        Path dbFile = tempDir.resolve("metadata.db");
        seedDatabase(dbFile);
        databaseManager = new DatabaseManager(dbFile.toString());

        Gson gson = new Gson();
        SearchController searchController = new SearchController(null, databaseManager, gson);
        BookController bookController = new BookController(databaseManager, gson);
        app = Javalin.create(cfg -> cfg.http.defaultContentType = "application/json").start(0);
        app.get("/search", searchController::handleSearch);
        app.get("/books/{id}", bookController::handleGetBookById);

        httpClient = HttpClient.newHttpClient();
        searchHttpRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/search?q=adventure&language=en&limit=10"))
                .GET()
                .build();
    }

    private void seedDatabase(Path dbFile) throws SQLException {
        String jdbcUrl = "jdbc:sqlite:" + dbFile;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA synchronous=NORMAL;");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS books (
                        book_id INTEGER PRIMARY KEY,
                        title TEXT,
                        author TEXT,
                        language TEXT,
                        year INTEGER
                    );
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS postings (
                        term TEXT,
                        book_id INTEGER,
                        freq INTEGER,
                        positions TEXT,
                        PRIMARY KEY (term, book_id)
                    );
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_postings_term ON postings(term);\n");

            conn.setAutoCommit(false);
            String[] languages = {"en", "en", "en", "es", "fr"};
            String[] authors = {"Mark Twain", "Jane Austen", "Arthur Conan Doyle", "Miguel de Cervantes", "Voltaire"};
            try (PreparedStatement insBook = conn.prepareStatement(
                    "INSERT INTO books (book_id, title, author, language, year) VALUES (?, ?, ?, ?, ?)");
                 PreparedStatement insPost = conn.prepareStatement(
                         "INSERT INTO postings (term, book_id, freq, positions) VALUES (?, ?, ?, ?)")) {
                for (int i = 1; i <= 25; i++) {
                    int bookId = 1000 + i;
                    insBook.setInt(1, bookId);
                    insBook.setString(2, "Benchmark Book " + bookId);
                    insBook.setString(3, authors[i % authors.length]);
                    insBook.setString(4, languages[i % languages.length]);
                    insBook.setInt(5, 1995 + (i % 15));
                    insBook.addBatch();

                    for (int t = 0; t < 200; t++) {
                        String term = (t == 0) ? "adventure" : (t == 1 ? "quijote" : (t == 2 ? "candide" : "term" + t));
                        insPost.setString(1, term);
                        insPost.setInt(2, bookId);
                        insPost.setInt(3, (i * 7 + t) % 50 + 1);
                        insPost.setString(4, "[1,5,12,29]");
                        insPost.addBatch();
                    }
                }
                insBook.executeBatch();
                insPost.executeBatch();
            }
            conn.commit();
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (app != null) {
            app.stop();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    @Benchmark
    public DatabaseManager.SearchResult sqliteSearchUnfiltered() throws SQLException {
        return databaseManager.searchByTerm("adventure", null, null, null, 0, 20);
    }

    @Benchmark
    public DatabaseManager.SearchResult sqliteSearchFiltered() throws SQLException {
        return databaseManager.searchByTerm("adventure", "twain", "en", null, 0, 20);
    }

    @Benchmark
    public Book sqliteGetBookById() throws SQLException {
        return databaseManager.getBookById(1001);
    }

    @Benchmark
    public int httpSearchClientQuery() throws Exception {
        HttpResponse<String> response = httpClient.send(searchHttpRequest, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    public static void main(String[] args) throws Exception {
        Path outDir = args.length > 0 ? Paths.get(args[0]) : Paths.get("results");
        Files.createDirectories(outDir);

        Options jsonOpts = new OptionsBuilder()
                .include(SearchMicroBenchmark.class.getSimpleName())
                .resultFormat(ResultFormatType.JSON)
                .result(outDir.resolve("jmh-search.json").toString())
                .build();
        Collection<RunResult> results = new Runner(jsonOpts).run();

        try (PrintStream csvStream = new PrintStream(
                Files.newOutputStream(outDir.resolve("jmh-search.csv")),
                true,
                StandardCharsets.UTF_8)) {
            ResultFormatFactory.getInstance(ResultFormatType.CSV, csvStream).writeOut(results);
        }
    }
}
