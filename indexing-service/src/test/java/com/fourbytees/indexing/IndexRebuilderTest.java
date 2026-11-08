package com.fourbytees.indexing;

import com.fourbytees.common.constants.SystemConstants;
import com.fourbytees.indexing.config.IndexingConfig;
import com.fourbytees.indexing.parser.HeaderParser;
import com.fourbytees.indexing.repository.SqliteRepository;
import com.fourbytees.indexing.service.IndexRebuilder;
import com.fourbytees.indexing.service.IndexingOrchestrator;
import com.fourbytees.indexing.service.ParsedBook;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IndexRebuilderTest {

    private HazelcastInstance hazelcast;

    @BeforeAll
    void setUp() {
        Config hzConfig = new Config();
        hzConfig.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        hazelcast = Hazelcast.newHazelcastInstance(hzConfig);
    }

    @AfterAll
    void tearDown() {
        if (hazelcast != null) {
            hazelcast.shutdown();
        }
    }

    private void writeBook(Path datalake, int bookId) throws IOException {
        Path bookDir = datalake.resolve("20261025/11");
        Files.createDirectories(bookDir);

        String header = "Title: Book number " + bookId + "\n"
                + "Author: Mark Twain\n"
                + "Language: en\n"
                + "Release Date: October, 1885 [EBook #" + bookId + "]\n";
        String body = "The island was full of mystery. Rebuildterm" + (char) ('a' + bookId % 26) + " appears here.";

        Files.writeString(bookDir.resolve(bookId + ".header.txt"), header);
        Files.writeString(bookDir.resolve(bookId + ".body.txt"), body);
        Files.writeString(bookDir.resolve(bookId + ".done"), "READY");
    }

    private IndexingOrchestrator newOrchestrator(Path datalake, Path database) {
        System.setProperty("DATALAKE_PATH", datalake.toString());
        System.setProperty("SQLITE_DB_PATH", database.toString());
        IndexingConfig config = new IndexingConfig();
        return new IndexingOrchestrator(config, new SqliteRepository(database.toString()), hazelcast, null);
    }

    /** Seeds the active database with stale books that are not present in the datalake. */
    private void seedStaleBooks(SqliteRepository repository, int firstId, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            int id = firstId + i;
            repository.saveBookIndex(
                    new HeaderParser.BookMetadata(id, "Stale " + id, "Old Author", "en", 1900),
                    java.util.Map.of("staleterm", List.of(1, 2, 3))
            );
        }
    }

    private Set<Integer> bookIds(Path database) throws SQLException {
        Set<Integer> ids = new TreeSet<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT book_id FROM books")) {
            while (rs.next()) {
                ids.add(rs.getInt(1));
            }
        }
        return ids;
    }

    private int count(Path database, String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private void assertNoTemporaryFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            List<String> leftovers = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.contains(".rebuild-"))
                    .toList();
            assertTrue(leftovers.isEmpty(), "Temporary rebuild files were left behind: " + leftovers);
        }
    }

    @Test
    void successfulRebuildProducesCompleteReplacementIndex(@TempDir Path tempDir) throws Exception {
        Path datalake = tempDir.resolve("datalake");
        Path database = tempDir.resolve("datamarts/metadata.db");
        for (int id = 1; id <= 8; id++) {
            writeBook(datalake, id);
        }

        IndexingOrchestrator orchestrator = newOrchestrator(datalake, database);
        SqliteRepository repository = new SqliteRepository(database.toString());
        seedStaleBooks(repository, 900, 3);

        IndexRebuilder rebuilder = new IndexRebuilder(repository, hazelcast, orchestrator, 2);
        IndexRebuilder.RebuildResult result = rebuilder.rebuild();

        assertEquals(8, result.booksProcessed());
        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8), bookIds(database));
        assertEquals(1, count(database, "SELECT COUNT(*) FROM postings WHERE term = 'rebuildtermd' AND book_id = 3"));
        assertEquals(0, count(database, "SELECT COUNT(*) FROM postings WHERE term = 'staleterm'"));

        IMap<Integer, String> bookState = hazelcast.getMap(SystemConstants.MAP_BOOK_STATE);
        assertEquals(SystemConstants.STATE_INDEXED, bookState.get(8));

        assertNoTemporaryFiles(database.getParent());
    }

    @Test
    void failedRebuildKeepsPreviousIndexActive(@TempDir Path tempDir) throws Exception {
        Path datalake = tempDir.resolve("datalake");
        Path database = tempDir.resolve("datamarts/metadata.db");
        for (int id = 1; id <= 8; id++) {
            writeBook(datalake, id);
        }

        IndexingOrchestrator orchestrator = newOrchestrator(datalake, database);
        SqliteRepository repository = new SqliteRepository(database.toString());
        seedStaleBooks(repository, 900, 3);

        IndexRebuilder.BookSource failingSource = new IndexRebuilder.BookSource() {
            @Override
            public List<Integer> listReadyBookIds() throws IOException {
                return orchestrator.listReadyBookIds();
            }

            @Override
            public ParsedBook parseBook(int bookId) throws Exception {
                if (bookId == 5) {
                    throw new IOException("Simulated parse failure for book 5");
                }
                return orchestrator.parseBook(bookId);
            }
        };

        IndexRebuilder rebuilder = new IndexRebuilder(repository, hazelcast, failingSource, 2);
        assertThrows(IndexRebuilder.RebuildFailedException.class, rebuilder::rebuild);

        assertEquals(Set.of(900, 901, 902), bookIds(database));
        assertEquals(3, count(database, "SELECT COUNT(*) FROM postings WHERE term = 'staleterm'"));
        assertNoTemporaryFiles(database.getParent());

        // The lock must have been released, so a later rebuild can succeed.
        IndexRebuilder healthy = new IndexRebuilder(repository, hazelcast, orchestrator, 2);
        assertEquals(8, healthy.rebuild().booksProcessed());
    }

    @Test
    void searchNeverObservesEmptyOrPartialIndexDuringRebuild(@TempDir Path tempDir) throws Exception {
        Path datalake = tempDir.resolve("datalake");
        Path database = tempDir.resolve("datamarts/metadata.db");
        int newBooks = 60;
        int oldBooks = 10;
        for (int id = 1; id <= newBooks; id++) {
            writeBook(datalake, id);
        }

        IndexingOrchestrator orchestrator = newOrchestrator(datalake, database);
        SqliteRepository repository = new SqliteRepository(database.toString());
        seedStaleBooks(repository, 900, oldBooks);

        AtomicBoolean stop = new AtomicBoolean(false);
        Set<Integer> observedCounts = new TreeSet<>();
        Set<String> violations = new TreeSet<>();
        long[] reads = {0};

        // Same connection style as the search service: a new read-only connection per query.
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);

        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database, readOnly.toProperties());
                     Statement stmt = conn.createStatement()) {
                    conn.setAutoCommit(false);
                    int books;
                    int postings;
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM books")) {
                        rs.next();
                        books = rs.getInt(1);
                    }
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM postings")) {
                        rs.next();
                        postings = rs.getInt(1);
                    }
                    conn.commit();
                    synchronized (observedCounts) {
                        reads[0]++;
                        observedCounts.add(books);
                        if (books != oldBooks && books != newBooks) {
                            violations.add("partial books=" + books);
                        }
                        if (books > 0 && postings == 0) {
                            violations.add("books without postings");
                        }
                    }
                } catch (SQLException e) {
                    synchronized (observedCounts) {
                        violations.add("read failed: " + e.getMessage());
                    }
                }
            }
        }, "test-search-reader");
        reader.start();

        try {
            IndexRebuilder rebuilder = new IndexRebuilder(repository, hazelcast, orchestrator, 2);
            assertEquals(newBooks, rebuilder.rebuild().booksProcessed());
        } finally {
            stop.set(true);
            reader.join(10_000);
        }

        synchronized (observedCounts) {
            assertTrue(reads[0] > 0, "The reader must have performed at least one read.");
            assertTrue(violations.isEmpty(), "Readers observed an inconsistent index: " + violations);
            assertTrue(observedCounts.contains(oldBooks), "The previous index must have been visible before the swap.");
        }
        assertEquals(newBooks, bookIds(database).size());
    }

    @Test
    void concurrentRebuildRequestsAreRejected(@TempDir Path tempDir) throws Exception {
        Path datalake = tempDir.resolve("datalake");
        Path database = tempDir.resolve("datamarts/metadata.db");
        for (int id = 1; id <= 3; id++) {
            writeBook(datalake, id);
        }

        IndexingOrchestrator orchestrator = newOrchestrator(datalake, database);
        SqliteRepository repository = new SqliteRepository(database.toString());

        CountDownLatch parsing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        IndexRebuilder.BookSource blockingSource = new IndexRebuilder.BookSource() {
            @Override
            public List<Integer> listReadyBookIds() throws IOException {
                return orchestrator.listReadyBookIds();
            }

            @Override
            public ParsedBook parseBook(int bookId) throws Exception {
                parsing.countDown();
                assertTrue(release.await(30, TimeUnit.SECONDS));
                return orchestrator.parseBook(bookId);
            }
        };

        IndexRebuilder first = new IndexRebuilder(repository, hazelcast, blockingSource, 2);
        // A second instance simulates another indexing replica sharing the same coordination layer.
        IndexRebuilder otherReplica = new IndexRebuilder(repository, hazelcast, orchestrator, 2);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<IndexRebuilder.RebuildResult> running = executor.submit(first::rebuild);
            assertTrue(parsing.await(30, TimeUnit.SECONDS));

            assertThrows(IndexRebuilder.RebuildInProgressException.class, first::rebuild);
            assertThrows(IndexRebuilder.RebuildInProgressException.class, otherReplica::rebuild);

            release.countDown();
            IndexRebuilder.RebuildResult result = running.get(60, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(3, result.booksProcessed());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        // After the first rebuild finishes, new requests are accepted again.
        assertEquals(3, otherReplica.rebuild().booksProcessed());
        assertNoTemporaryFiles(database.getParent());
    }

    @Test
    void temporaryDatabaseAndSideFilesAreRemoved(@TempDir Path tempDir) throws Exception {
        Path temporary = tempDir.resolve("scratch.db");
        SqliteRepository scratch = new SqliteRepository(temporary.toString());
        scratch.saveBookIndex(
                new HeaderParser.BookMetadata(1, "T", "A", "en", null),
                java.util.Map.of("term", List.of(1))
        );

        assertTrue(Files.exists(temporary));
        SqliteRepository.deleteDatabaseFiles(temporary.toString());

        try (Stream<Path> files = Files.list(tempDir)) {
            assertFalse(files.findAny().isPresent(), "The database and its WAL/SHM files must be removed.");
        }
    }
}
