package com.fourbytees.indexing;

import com.fourbytees.common.constants.SystemConstants;
import com.fourbytees.common.events.BookDownloaded;
import com.fourbytees.common.factory.JmsClientFactory;
import com.fourbytees.indexing.config.IndexingConfig;
import com.fourbytees.indexing.messaging.BookDownloadedConsumer;
import com.fourbytees.indexing.repository.SqliteRepository;
import com.fourbytees.indexing.service.BookClaim;
import com.fourbytees.indexing.service.IndexingOrchestrator;
import com.google.gson.Gson;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies JMS redelivery, non-acknowledgement on {@code RETRY_LATER}, claim takeover after
 * expiration, and DLQ routing when retries are exhausted.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class JmsRedeliveryTest {

    private static final String BROKER_URL = "vm://redelivery-test-broker";

    private BrokerService broker;
    private HazelcastInstance hazelcast;
    private final Gson gson = new Gson();

    @BeforeAll
    void setUp() throws Exception {
        broker = new BrokerService();
        broker.setBrokerName("redelivery-test-broker");
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.addConnector(BROKER_URL);
        broker.start();

        Config hzConfig = new Config();
        hzConfig.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        hazelcast = Hazelcast.newHazelcastInstance(hzConfig);
    }

    @AfterAll
    void tearDown() throws Exception {
        System.clearProperty("INDEXING_TIMEOUT_MS");
        System.clearProperty("RETRY_LATER_DELAY_MS");
        System.clearProperty("JMS_REDELIVERY_INITIAL_DELAY_MS");
        System.clearProperty("JMS_REDELIVERY_BACKOFF_MULTIPLIER");
        System.clearProperty("MAX_RETRIES");
        if (broker != null) {
            broker.stop();
        }
        if (hazelcast != null) {
            hazelcast.shutdown();
        }
    }

    private void prepareBookFiles(Path datalakeDir, int bookId) throws Exception {
        Path bookDir = datalakeDir.resolve("20261025/11");
        Files.createDirectories(bookDir);
        String header = "Title: Redelivery Book " + bookId + "\n"
                + "Author: Mark Twain\n"
                + "Language: en\n"
                + "Release Date: October, 1885 [EBook #" + bookId + "]\n";
        String body = "The island was full of mystery. Adventure on the island.";
        Files.writeString(bookDir.resolve(bookId + ".header.txt"), header);
        Files.writeString(bookDir.resolve(bookId + ".body.txt"), body);
        Files.writeString(bookDir.resolve(bookId + ".done"), "READY");
    }

    private void sendDownloadedEvent(Connection connection, int bookId, String path) throws Exception {
        try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            Destination queue = session.createQueue(SystemConstants.QUEUE_BOOKS_DOWNLOADED);
            try (MessageProducer producer = session.createProducer(queue)) {
                BookDownloaded event = new BookDownloaded(
                        UUID.randomUUID().toString(),
                        bookId,
                        path,
                        Instant.now().toString()
                );
                producer.send(session.createTextMessage(gson.toJson(event)));
            }
        }
    }

    @Test
    void recentClaimCausesRedeliveryAndProcessedOnceAfterClaimExpires(@TempDir Path tempDir) throws Exception {
        Path datalake = tempDir.resolve("datalake");
        Path database = tempDir.resolve("redelivery.db");
        int bookId = 701;

        prepareBookFiles(datalake, bookId);

        System.setProperty("DATALAKE_PATH", datalake.toString());
        System.setProperty("SQLITE_DB_PATH", database.toString());
        System.setProperty("INDEXING_TIMEOUT_MS", "350");
        System.setProperty("RETRY_LATER_DELAY_MS", "120");
        System.setProperty("JMS_REDELIVERY_INITIAL_DELAY_MS", "50");
        System.setProperty("JMS_REDELIVERY_BACKOFF_MULTIPLIER", "1.5");
        System.setProperty("MAX_RETRIES", "3");

        IndexingConfig config = new IndexingConfig();
        SqliteRepository repository = new SqliteRepository(database.toString());

        IMap<Integer, String> bookState = hazelcast.getMap(SystemConstants.MAP_BOOK_STATE);
        IMap<Integer, BookClaim> bookClaims = hazelcast.getMap(SystemConstants.MAP_BOOK_CLAIMS);

        // Simulate a recent claim held by another replica that will expire in ~350 ms.
        bookState.put(bookId, SystemConstants.STATE_INDEXING);
        bookClaims.put(bookId, new BookClaim("other-replica", System.currentTimeMillis()));

        try (Connection jmsConn = JmsClientFactory.createConnection(
                BROKER_URL,
                config.redeliveryInitialDelayMs,
                config.redeliveryBackoffMultiplier,
                config.redeliveryMaxDelayMs,
                config.redeliveryMaximumRedeliveries)) {
            IndexingOrchestrator orchestrator = new IndexingOrchestrator(config, repository, hazelcast, jmsConn);
            Thread consumerThread = new Thread(
                    new BookDownloadedConsumer(jmsConn, config, orchestrator, hazelcast),
                    "test-jms-consumer"
            );
            consumerThread.setDaemon(true);
            consumerThread.start();

            long sentAtMs = System.currentTimeMillis();
            sendDownloadedEvent(jmsConn, bookId, datalake.toString());

            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline
                    && !SystemConstants.STATE_INDEXED.equals(bookState.get(bookId))) {
                Thread.sleep(50L);
            }

            long elapsedMs = System.currentTimeMillis() - sentAtMs;
            // Allow post-commit BookIndexed publication and JMS client acknowledgement to complete before interrupting receive().
            Thread.sleep(150L);
            consumerThread.interrupt();
            consumerThread.join(1_000L);

            assertEquals(SystemConstants.STATE_INDEXED, bookState.get(bookId),
                    "The book must reach INDEXED after the initial claim expires and the message is redelivered.");
            assertTrue(elapsedMs >= 250L,
                    "Processing must wait until the recent claim expires and redelivery backoff elapses.");
            assertNull(bookClaims.get(bookId), "The claim must be cleared once indexing finishes.");

            try (java.sql.Connection sqlConn = DriverManager.getConnection("jdbc:sqlite:" + database);
                 Statement stmt = sqlConn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM books WHERE book_id = " + bookId)) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "SQLite must contain exactly one copy of the book.");
            }

            // Confirm the message was acknowledged and no duplicate remains on the queue.
            try (Session checkSession = jmsConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
                 MessageConsumer checkConsumer = checkSession.createConsumer(
                         checkSession.createQueue(SystemConstants.QUEUE_BOOKS_DOWNLOADED))) {
                assertNull(checkConsumer.receive(200L), "The queue must be empty after acknowledgement.");
            }
        }
    }

    @Test
    void permanentFailureExhaustsRetriesAndRoutesToDlq(@TempDir Path tempDir) throws Exception {
        Path datalake = tempDir.resolve("datalake");
        Path database = tempDir.resolve("dlq.db");
        int missingBookId = 702;

        System.setProperty("DATALAKE_PATH", datalake.toString());
        System.setProperty("SQLITE_DB_PATH", database.toString());
        System.setProperty("INDEXING_TIMEOUT_MS", "300000");
        System.setProperty("RETRY_LATER_DELAY_MS", "40");
        System.setProperty("JMS_REDELIVERY_INITIAL_DELAY_MS", "40");
        System.setProperty("JMS_REDELIVERY_BACKOFF_MULTIPLIER", "1.0");
        System.setProperty("MAX_RETRIES", "2");

        IndexingConfig config = new IndexingConfig();
        SqliteRepository repository = new SqliteRepository(database.toString());

        IMap<Integer, String> bookState = hazelcast.getMap(SystemConstants.MAP_BOOK_STATE);
        bookState.put(missingBookId, SystemConstants.STATE_DOWNLOADED);

        try (Connection jmsConn = JmsClientFactory.createConnection(
                BROKER_URL,
                config.redeliveryInitialDelayMs,
                config.redeliveryBackoffMultiplier,
                config.redeliveryMaxDelayMs,
                config.redeliveryMaximumRedeliveries)) {
            IndexingOrchestrator orchestrator = new IndexingOrchestrator(config, repository, hazelcast, jmsConn);
            Thread consumerThread = new Thread(
                    new BookDownloadedConsumer(jmsConn, config, orchestrator, hazelcast),
                    "test-dlq-consumer"
            );
            consumerThread.setDaemon(true);
            consumerThread.start();

            sendDownloadedEvent(jmsConn, missingBookId, datalake.toString());

            try (Session dlqSession = jmsConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
                 MessageConsumer dlqConsumer = dlqSession.createConsumer(
                         dlqSession.createQueue(SystemConstants.QUEUE_BOOKS_DLQ))) {
                TextMessage dlqMessage = (TextMessage) dlqConsumer.receive(10_000L);
                Thread.sleep(50L);
                consumerThread.interrupt();
                consumerThread.join(1_000L);

                assertNotNull(dlqMessage, "A DLQ message must be produced when retries are exhausted.");
                assertTrue(dlqMessage.getText().contains("\"book_id\": " + missingBookId));
                assertEquals(SystemConstants.STATE_FAILED, bookState.get(missingBookId));
            }
        }
    }
}
