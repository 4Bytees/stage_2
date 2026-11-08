package com.fourbytees.ingestion;

import com.fourbytees.common.constants.SystemConstants;
import com.fourbytees.common.events.BookDownloaded;
import com.fourbytees.ingestion.service.DatalakeStorage;
import com.fourbytees.ingestion.service.OutboxService;
import com.google.gson.Gson;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import javax.jms.*;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IngestionServiceTest {

    private BrokerService broker;
    private Connection jmsConnection;
    private HazelcastInstance hazelcast;
    private final Gson gson = new Gson();

    @BeforeAll
    void setup() throws Exception {
        broker = new BrokerService();
        broker.setBrokerName("localhost-ingestion");
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.addConnector("vm://localhost-ingestion");
        broker.start();

        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("vm://localhost-ingestion?create=false");
        jmsConnection = cf.createConnection();
        jmsConnection.start();

        Config hzConfig = new Config();
        hzConfig.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        hazelcast = Hazelcast.newHazelcastInstance(hzConfig);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (jmsConnection != null) {
            try { jmsConnection.close(); } catch (Exception ignored) {}
        }
        if (broker != null) {
            try { broker.stop(); } catch (Exception ignored) {}
        }
        if (hazelcast != null) {
            try { hazelcast.shutdown(); } catch (Exception ignored) {}
        }
    }

    @Test
    public void testIdempotencyAndAtomicity(@TempDir Path tempDir) throws IOException {
        DatalakeStorage storage = new DatalakeStorage(tempDir.toString());
        int bookId = 9999;

        String path = storage.saveBook(bookId, "Header Content", "Body Content");
        assertTrue(storage.isBookDownloaded(bookId));

        String pathSecond = storage.saveBook(bookId, "Header Content", "Body Content");
        assertEquals(path, pathSecond);
    }

    @Test
    public void testCrashAfterDoneBeforePublishAndRecovery(@TempDir Path tempDir) throws Exception {
        DatalakeStorage storage = new DatalakeStorage(tempDir.toString());
        int bookId = 12345;

        // 1. Simulate a crash: the book is saved (.header, .body and .done are created),
        // but the process terminates before publishing the event to ActiveMQ and Hazelcast.
        String savedPath = storage.saveBook(bookId, "Title: Crash Test", "Sample body content");
        assertTrue(storage.isBookDownloaded(bookId), "The book must be downloaded with a .done marker");
        assertFalse(storage.isBookPublished(bookId), "The book must not yet be marked as published");
        assertNull(hazelcast.getMap(SystemConstants.MAP_BOOK_STATE).get(bookId), "The state in Hazelcast must not exist yet");

        // Prepare a JMS consumer on the books.downloaded queue.
        Session session = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination queue = session.createQueue(SystemConstants.QUEUE_BOOKS_DOWNLOADED);
        MessageConsumer consumer = session.createConsumer(queue);

        // 2. Restart: a new OutboxService starts and runs the outbox check.
        OutboxService outboxRecovery = new OutboxService(storage, jmsConnection, hazelcast);
        int republished = outboxRecovery.runOutboxCheck();
        assertEquals(1, republished, "The outbox must detect and republish exactly 1 book");

        // 3. Verify that the event arrived on the queue with the expected event_id and book_id.
        Message msg = consumer.receive(3000);
        assertNotNull(msg, "The event must be received on the books.downloaded queue after restart");
        assertTrue(msg instanceof TextMessage);
        TextMessage textMsg = (TextMessage) msg;
        BookDownloaded event = gson.fromJson(textMsg.getText(), BookDownloaded.class);
        assertEquals(bookId, event.getBook_id(), "The received book_id must match the saved book");
        assertNotNull(event.getEvent_id(), "The event must contain a non-null event_id");
        assertFalse(event.getEvent_id().isBlank(), "The event_id must not be blank");
        assertNotNull(event.getDownloaded_at());

        // 4. Verify that the book is now marked as published on disk and in Hazelcast (DOWNLOADED).
        assertTrue(storage.isBookPublished(bookId), "The book must be marked with .published");
        assertEquals(SystemConstants.STATE_DOWNLOADED, hazelcast.getMap(SystemConstants.MAP_BOOK_STATE).get(bookId),
                "The state in Hazelcast must be DOWNLOADED after publication");

        // 5. Outbox idempotency: a second check must not duplicate messages on the queue.
        int secondCheck = outboxRecovery.runOutboxCheck();
        assertEquals(0, secondCheck, "The outbox must not republish books that already have .published");
        Message duplicateMsg = consumer.receive(500);
        assertNull(duplicateMsg, "There must be no duplicate messages on the queue");

        consumer.close();
        session.close();
    }
}
