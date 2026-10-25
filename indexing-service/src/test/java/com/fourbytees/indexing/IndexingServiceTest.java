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
        assertTrue(firstRun, "La primera indexación debe ser exitosa");

        boolean secondRun = orchestrator.indexBook(bookId, true);
        assertTrue(secondRun, "La segunda indexación (idempotente) debe ser exitosa");

        try (java.sql.Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement()) {

            ResultSet rsBooks = stmt.executeQuery("SELECT COUNT(*) FROM books WHERE book_id = " + bookId);
            assertTrue(rsBooks.next());
            assertEquals(1, rsBooks.getInt(1), "El libro debe aparecer exactamente 1 vez en books");

            ResultSet rsPostings = stmt.executeQuery("SELECT freq FROM postings WHERE term = 'island' AND book_id = " + bookId);
            assertTrue(rsPostings.next());
            assertEquals(2, rsPostings.getInt(1), "La frecuencia del término debe ser 2 sin duplicados");
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

        assertEquals(1, successCount.get(), "Solo una réplica debe haber procesado el libro");
        assertEquals(1, rejectedCount.get(), "La segunda réplica debe haber sido rechazada por el replace");
        assertEquals(SystemConstants.STATE_INDEXED, bookState.get(bookId));
    }
}