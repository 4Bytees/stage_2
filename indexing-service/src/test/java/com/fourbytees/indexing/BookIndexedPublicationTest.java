package com.fourbytees.indexing;

import com.fourbytees.common.constants.SystemConstants;
import com.fourbytees.common.events.BookIndexed;
import com.fourbytees.indexing.config.IndexingConfig;
import com.fourbytees.indexing.parser.HeaderParser.BookMetadata;
import com.fourbytees.indexing.repository.SqliteRepository;
import com.fourbytees.indexing.service.BookIndexedPublisher;
import com.fourbytees.indexing.service.IndexingOrchestrator;
import com.fourbytees.indexing.service.IndexingResult;
import com.google.gson.Gson;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@code BookIndexed} publication is retried after the index commit without ever
 * repeating the SQLite write.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BookIndexedPublicationTest {

    /** Repository that counts how many times a book index is written. */
    private static class CountingRepository extends SqliteRepository {
        final AtomicInteger writes = new AtomicInteger();

        CountingRepository(String path) {
            super(path);
        }

        @Override
        public void saveBookIndex(BookMetadata meta, Map<String, List<Integer>> postings) throws java.sql.SQLException {
            writes.incrementAndGet();
            super.saveBookIndex(meta, postings);
        }
    }

    private final Gson gson = new Gson();
    private HazelcastInstance hazelcast;
    private IMap<String, String> outbox;

    @BeforeAll
    void setUp() {
        Config hzConfig = new Config();
        hzConfig.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        hazelcast = Hazelcast.newHazelcastInstance(hzConfig);
        outbox = hazelcast.getMap(BookIndexedPublisher.OUTBOX_MAP);
    }

    @AfterAll
    void tearDown() {
        System.clearProperty("OUTBOX_MIN_AGE_MS");
        if (hazelcast != null) {
            hazelcast.shutdown();
        }
    }

    @BeforeEach
    void clearOutbox() {
        outbox.clear();
    }

    private void writeBook(Path datalake, int bookId) throws IOException {
        Path bookDir = datalake.resolve("20261025/11");
        Files.createDirectories(bookDir);
        Files.writeString(bookDir.resolve(bookId + ".header.txt"),
                "Title: Book " + bookId + "\nAuthor: Mark Twain\nLanguage: en\n"
                        + "Release Date: October, 1885 [EBook #" + bookId + "]\n");
        Files.writeString(bookDir.resolve(bookId + ".body.txt"), "The island was full of mystery.");
        Files.writeString(bookDir.resolve(bookId + ".done"), "READY");
    }

    private IndexingConfig config(Path datalake, Path database) {
        System.setProperty("DATALAKE_PATH", datalake.toString());
        System.setProperty("SQLITE_DB_PATH", database.toString());
        System.setProperty("OUTBOX_MIN_AGE_MS", "0");
        return new IndexingConfig();
    }

    private IndexingOrchestrator orchestrator(IndexingConfig config, CountingRepository repository,
                                              BookIndexedPublisher publisher) {
        return IndexingOrchestrator.withPublisher(config, repository, hazelcast, publisher);
    }

    private int bookRows(Path database) throws Exception {
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             java.sql.Statement stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM books")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void publicationFailureIsRecoveredFromOutboxWithoutRepeatingSqliteWrite(@TempDir Path tempDir) throws Exception {
        Path datalake = tempDir.resolve("datalake");
        Path database = tempDir.resolve("db/metadata.db");
        int bookId = 501;
        writeBook(datalake, bookId);

        boolean[] brokerAvailable = {false};
        List<String> delivered = new CopyOnWriteArrayList<>();
        BookIndexedPublisher.Sender sender = payload -> {
            if (!brokerAvailable[0]) {
                throw new IOException("Broker unavailable");
            }
            delivered.add(payload);
        };

        IndexingConfig config = config(datalake, database);
        CountingRepository repository = new CountingRepository(database.toString());
        IndexingOrchestrator orchestrator =
                orchestrator(config, repository, new BookIndexedPublisher(hazelcast, sender, 2, 0));

        IMap<Integer, String> bookState = hazelcast.getMap(SystemConstants.MAP_BOOK_STATE);
        bookState.put(bookId, SystemConstants.STATE_DOWNLOADED);

        // Indexing succeeds even though the broker is down.
        assertEquals(IndexingResult.INDEXED, orchestrator.indexDownloadedBook(bookId));
        assertEquals(SystemConstants.STATE_INDEXED, bookState.get(bookId));
        assertEquals(1, repository.writes.get());
        assertEquals(1, bookRows(database));
        assertTrue(delivered.isEmpty());
        assertEquals(1, outbox.size(), "The undelivered event must stay in the outbox.");

        // The broker recovers: the pending event is delivered and nothing is re-indexed.
        brokerAvailable[0] = true;
        assertEquals(1, orchestrator.flushPendingEvents());

        assertEquals(1, delivered.size());
        BookIndexed event = gson.fromJson(delivered.get(0), BookIndexed.class);
        assertEquals(bookId, event.getBook_id());
        assertTrue(outbox.isEmpty(), "A delivered event must be removed from the outbox.");
        assertEquals(1, repository.writes.get(), "Retrying publication must not repeat the SQLite write.");
        assertEquals(1, bookRows(database));

        // Nothing is left to deliver.
        assertEquals(0, orchestrator.flushPendingEvents());
        assertEquals(1, delivered.size());
    }

    @Test
    void transientBrokerFailureIsRetriedWithinTheBoundedAttempts(@TempDir Path tempDir) throws Exception {
        Path datalake = tempDir.resolve("datalake");
        Path database = tempDir.resolve("db/metadata.db");
        int bookId = 502;
        writeBook(datalake, bookId);

        AtomicInteger calls = new AtomicInteger();
        List<String> delivered = new CopyOnWriteArrayList<>();
        BookIndexedPublisher.Sender sender = payload -> {
            if (calls.incrementAndGet() <= 2) {
                throw new IOException("Transient failure");
            }
            delivered.add(payload);
        };

        CountingRepository repository = new CountingRepository(database.toString());
        IndexingOrchestrator orchestrator = orchestrator(
                config(datalake, database), repository, new BookIndexedPublisher(hazelcast, sender, 3, 0));

        hazelcast.<Integer, String>getMap(SystemConstants.MAP_BOOK_STATE).put(bookId, SystemConstants.STATE_DOWNLOADED);

        assertEquals(IndexingResult.INDEXED, orchestrator.indexDownloadedBook(bookId));

        assertEquals(3, calls.get());
        assertEquals(1, delivered.size());
        assertTrue(outbox.isEmpty());
        assertEquals(1, repository.writes.get());
    }

    @Test
    void publicationAttemptsAreBounded() {
        AtomicInteger calls = new AtomicInteger();
        BookIndexedPublisher publisher = new BookIndexedPublisher(hazelcast, payload -> {
            calls.incrementAndGet();
            throw new IOException("Broker unavailable");
        }, 3, 0);

        BookIndexed event = new BookIndexed("event-bounded", 503, 7L);
        publisher.record(event);

        assertTrue(!publisher.publish(event));
        assertEquals(3, calls.get(), "Exactly the configured number of attempts must be made.");
        assertEquals(List.of("event-bounded"), publisher.pendingEventIds());
    }

    @Test
    void duplicateDeliveriesCarryTheSameEventId() {
        List<String> received = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        // The broker accepts the first message but the acknowledgement is lost, so the publisher retries.
        BookIndexedPublisher publisher = new BookIndexedPublisher(hazelcast, payload -> {
            received.add(payload);
            if (calls.incrementAndGet() == 1) {
                throw new IOException("Acknowledgement lost");
            }
        }, 3, 0);

        BookIndexed event = new BookIndexed("event-duplicate", 504, 9L);
        publisher.record(event);

        assertTrue(publisher.publish(event));

        assertEquals(2, received.size());
        BookIndexed first = gson.fromJson(received.get(0), BookIndexed.class);
        BookIndexed second = gson.fromJson(received.get(1), BookIndexed.class);
        assertEquals(first.getEvent_id(), second.getEvent_id(), "Duplicates must be identifiable by event_id.");
        assertEquals(first.getIndex_version(), second.getIndex_version());
        assertTrue(outbox.isEmpty());
    }
}
