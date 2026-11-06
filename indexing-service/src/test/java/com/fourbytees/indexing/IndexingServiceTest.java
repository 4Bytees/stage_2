package com.fourbytees.indexing;

import com.fourbytees.common.constants.SystemConstants;
import com.fourbytees.indexing.config.IndexingConfig;
import com.fourbytees.indexing.repository.SqliteRepository;
import com.fourbytees.indexing.service.IndexingOrchestrator;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.jms.Connection;
import org.apache.activemq.ActiveMQConnectionFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fourbytees.indexing.service.BookClaim;
import com.fourbytees.indexing.service.IndexingResult;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IndexingServiceTest {

    private HazelcastInstance hazelcastMember;
    private BrokerService broker;
    private Connection jmsConnection;

    @BeforeAll
    void setupGlobal() throws Exception {
        broker = new BrokerService();
        broker.setPersistent(false);
        broker.addConnector("vm://localhost");
        broker.start();

        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("vm://localhost");
        jmsConnection = cf.createConnection();
        jmsConnection.start();

        Config hzConfig = new Config();
        hzConfig.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        hazelcastMember = Hazelcast.newHazelcastInstance(hzConfig);
    }

    @AfterAll
    void tearDownGlobal() throws Exception {
        if (jmsConnection != null) jmsConnection.close();
        if (broker != null) broker.stop();
        if (hazelcastMember != null) hazelcastMember.shutdown();
    }

    private void prepareBookFiles(Path datalakeDir, int bookId) throws Exception {
        Path bookDir = datalakeDir.resolve("20261025/11");
        Files.createDirectories(bookDir);

        String header = """
                Title: The Great Adventure
                Author: Mark Twain
                Language: en
                Release Date: October, 1885 [EBook #1234]
                """;
        String body = "The island was full of mystery. Adventure and mystery on the island.";

        Files.writeString(bookDir.resolve(bookId + ".header.txt"), header);
        Files.writeString(bookDir.resolve(bookId + ".body.txt"), body);
        Files.writeString(bookDir.resolve(bookId + ".done"), "READY");
    }

    @Test
    void testIdempotentIndexing(@TempDir Path tempDir) throws Exception {
        Path datalake = tempDir.resolve("datalake");
        Path dbPath = tempDir.resolve("test_idempotent.db");
        int bookId = 42;

        prepareBookFiles(datalake, bookId);

        System.setProperty("DATALAKE_PATH", datalake.toString());
        System.setProperty("SQLITE_DB_PATH", dbPath.toString());
        IndexingConfig config = new IndexingConfig();

        SqliteRepository repository = new SqliteRepository(dbPath.toString());
        IndexingOrchestrator orchestrator = new IndexingOrchestrator(config, repository, hazelcastMember, jmsConnection);

        boolean firstRun = orchestrator.indexBook(bookId, true);
        assertTrue(firstRun, "First indexing run must succeed");

        boolean secondRun = orchestrator.indexBook(bookId, true);
        assertTrue(secondRun, "Second idempotent indexing run must succeed");

        try (java.sql.Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement()) {

            ResultSet rsBooks = stmt.executeQuery("SELECT COUNT(*) FROM books WHERE book_id = " + bookId);
            assertTrue(rsBooks.next());
            assertEquals(1, rsBooks.getInt(1), "The book must appear exactly once in the books table");

            ResultSet rsPostings = stmt.executeQuery("SELECT freq FROM postings WHERE term = 'island' AND book_id = " + bookId);
            assertTrue(rsPostings.next());
            assertEquals(2, rsPostings.getInt(1), "Term frequency must be 2 without duplicate postings");
        }
    }

    @Test
    void testConcurrentReplicasSameBook(@TempDir Path tempDir) throws Exception {
        Path datalake = tempDir.resolve("datalake");
        Path dbPath = tempDir.resolve("test_concurrency.db");
        int bookId = 99;

        prepareBookFiles(datalake, bookId);

        System.setProperty("DATALAKE_PATH", datalake.toString());
        System.setProperty("SQLITE_DB_PATH", dbPath.toString());
        IndexingConfig config = new IndexingConfig();

        SqliteRepository repository = new SqliteRepository(dbPath.toString());
        IndexingOrchestrator orchestrator = new IndexingOrchestrator(config, repository, hazelcastMember, jmsConnection);

        IMap<Integer, String> bookState = hazelcastMember.getMap(SystemConstants.MAP_BOOK_STATE);
        bookState.put(bookId, SystemConstants.STATE_DOWNLOADED);

        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    boolean ok = orchestrator.indexBook(bookId, false);
                    if (ok) {
                        successCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, successCount.get(), "Only one replica must have processed the book");
        assertEquals(1, rejectedCount.get(), "The second replica must have been rejected by the claim");
        assertEquals(SystemConstants.STATE_INDEXED, bookState.get(bookId));
    }
    @Test
void recentClaimReturnsRetryLater(@TempDir Path tempDir) throws Exception {
    Path datalake = tempDir.resolve("datalake");
    Path database = tempDir.resolve("recent_claim.db");
    int bookId = 101;

    System.setProperty("DATALAKE_PATH", datalake.toString());
    System.setProperty("SQLITE_DB_PATH", database.toString());
    System.setProperty("INDEXING_TIMEOUT_MS", "300000");

    IndexingConfig config = new IndexingConfig();
    SqliteRepository repository = new SqliteRepository(database.toString());

    IndexingOrchestrator orchestrator = new IndexingOrchestrator(
            config,
            repository,
            hazelcastMember,
            jmsConnection
    );

    IMap<Integer, String> bookState =
            hazelcastMember.getMap(SystemConstants.MAP_BOOK_STATE);

    IMap<Integer, BookClaim> bookClaims =
            hazelcastMember.getMap(SystemConstants.MAP_BOOK_CLAIMS);

    bookState.put(bookId, SystemConstants.STATE_INDEXING);
    bookClaims.put(
            bookId,
            new BookClaim("active-replica", System.currentTimeMillis())
    );

    IndexingResult result = orchestrator.indexDownloadedBook(bookId);

    assertEquals(
            IndexingResult.RETRY_LATER,
            result,
            "A recent claim must not be taken over."
    );

    assertEquals(
            SystemConstants.STATE_INDEXING,
            bookState.get(bookId)
    );

    assertEquals(
            "active-replica",
            bookClaims.get(bookId).claimantId()
    );
}

@Test
void expiredClaimIsTakenOverAndRemovedAfterSuccess(
        @TempDir Path tempDir
) throws Exception {
    Path datalake = tempDir.resolve("datalake");
    Path database = tempDir.resolve("expired_claim.db");
    int bookId = 102;

    prepareBookFiles(datalake, bookId);

    System.setProperty("DATALAKE_PATH", datalake.toString());
    System.setProperty("SQLITE_DB_PATH", database.toString());
    System.setProperty("INDEXING_TIMEOUT_MS", "100");

    IndexingConfig config = new IndexingConfig();
    SqliteRepository repository = new SqliteRepository(database.toString());

    IndexingOrchestrator orchestrator = new IndexingOrchestrator(
            config,
            repository,
            hazelcastMember,
            jmsConnection
    );

    IMap<Integer, String> bookState =
            hazelcastMember.getMap(SystemConstants.MAP_BOOK_STATE);

    IMap<Integer, BookClaim> bookClaims =
            hazelcastMember.getMap(SystemConstants.MAP_BOOK_CLAIMS);

    bookState.put(bookId, SystemConstants.STATE_INDEXING);
    bookClaims.put(
            bookId,
            new BookClaim(
                    "failed-replica",
                    System.currentTimeMillis() - 1_000
            )
    );

    IndexingResult result = orchestrator.indexDownloadedBook(bookId);

    assertEquals(
            IndexingResult.INDEXED,
            result,
            "An expired claim must be taken over."
    );

    assertEquals(
            SystemConstants.STATE_INDEXED,
            bookState.get(bookId)
    );

    assertNull(
            bookClaims.get(bookId),
            "The successful claimant must remove its claim."
    );

    try (
            java.sql.Connection connection =
                    DriverManager.getConnection("jdbc:sqlite:" + database);

            Statement statement = connection.createStatement();

            ResultSet resultSet = statement.executeQuery(
                    "SELECT COUNT(*) FROM books WHERE book_id = " + bookId
            )
    ) {
        assertTrue(resultSet.next());
        assertEquals(1, resultSet.getInt(1));
    }
}

@Test
void duplicateIndexedBookReturnsAlreadyIndexed(
        @TempDir Path tempDir
) throws Exception {
    Path datalake = tempDir.resolve("datalake");
    Path database = tempDir.resolve("already_indexed.db");
    int bookId = 103;

    System.setProperty("DATALAKE_PATH", datalake.toString());
    System.setProperty("SQLITE_DB_PATH", database.toString());
    System.setProperty("INDEXING_TIMEOUT_MS", "300000");

    IndexingConfig config = new IndexingConfig();
    SqliteRepository repository = new SqliteRepository(database.toString());

    IndexingOrchestrator orchestrator = new IndexingOrchestrator(
            config,
            repository,
            hazelcastMember,
            jmsConnection
    );

    IMap<Integer, String> bookState =
            hazelcastMember.getMap(SystemConstants.MAP_BOOK_STATE);

    IMap<Integer, BookClaim> bookClaims =
            hazelcastMember.getMap(SystemConstants.MAP_BOOK_CLAIMS);

    bookState.put(bookId, SystemConstants.STATE_INDEXED);

    IndexingResult result = orchestrator.indexDownloadedBook(bookId);

    assertEquals(
            IndexingResult.ALREADY_INDEXED,
            result,
            "A duplicate delivery for an indexed book must be acknowledged safely."
    );

    assertEquals(
            SystemConstants.STATE_INDEXED,
            bookState.get(bookId)
    );

    assertNull(bookClaims.get(bookId));
}
}
