package com.fourbytees.ingestion.service;

import com.fourbytees.common.constants.SystemConstants;
import com.fourbytees.common.events.BookDownloaded;
import com.google.gson.Gson;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.util.List;
import java.util.UUID;

/**
 * Outbox service that publishes BookDownloaded events to ActiveMQ and keeps
 * the distributed book state in Hazelcast.
 *
 * <p><strong>Delivery guarantee: at least once.</strong></p>
 *
 * <p>The publication sequence is:</p>
 * <ol>
 *     <li>Set the Hazelcast state to DOWNLOADED.</li>
 *     <li>Send a persistent message to the books.downloaded queue.</li>
 *     <li>Create the {@code <id>.published} marker in the datalake.</li>
 * </ol>
 *
 * <p>If ingestion stops before the published marker is created, the next
 * outbox scan republishes the event. Therefore, indexing must process
 * duplicate deliveries idempotently.</p>
 */
public class OutboxService {
    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final DatalakeStorage storage;
    private final Connection jmsConnection;
    private final HazelcastInstance hazelcast;
    private final Gson gson = new Gson();

    public OutboxService(DatalakeStorage storage) {
        this(storage, null, null);
    }

    public OutboxService(
            DatalakeStorage storage,
            Connection jmsConnection,
            HazelcastInstance hazelcast
    ) {
        this.storage = storage;
        this.jmsConnection = jmsConnection;
        this.hazelcast = hazelcast;
    }

    public void publishBook(int bookId, String path) throws Exception {
        String eventId = UUID.randomUUID().toString();
        BookDownloaded event = new BookDownloaded(eventId, bookId, path);

        if (hazelcast != null) {
            IMap<Integer, String> bookState =
                    hazelcast.getMap(SystemConstants.MAP_BOOK_STATE);

            bookState.put(bookId, SystemConstants.STATE_DOWNLOADED);
        }

        if (jmsConnection != null) {
            try (Session session =
                         jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {

                Destination destination =
                        session.createQueue(SystemConstants.QUEUE_BOOKS_DOWNLOADED);

                try (MessageProducer producer = session.createProducer(destination)) {
                    producer.setDeliveryMode(DeliveryMode.PERSISTENT);

                    TextMessage message =
                            session.createTextMessage(gson.toJson(event));

                    producer.send(message);
                }
            }
        }

        storage.markBookPublished(bookId);

        log.info(
                "BookDownloaded event published and confirmed for book {} (event_id: {})",
                bookId,
                eventId
        );
    }

    public int runOutboxCheck() {
        List<Integer> downloadedBooks = storage.listDownloadedBooks();

        log.info(
                "[OUTBOX] Scanning datalake at startup. Books with a .done marker: {}",
                downloadedBooks.size()
        );

        int republishedCount = 0;

        for (Integer bookId : downloadedBooks) {
            if (storage.isBookPublished(bookId)) {
                continue;
            }

            String path = storage.getBookDirectoryPath(bookId);

            try {
                publishBook(bookId, path);
                republishedCount++;

                log.info(
                        "[OUTBOX] Book {} republished to {}",
                        bookId,
                        SystemConstants.QUEUE_BOOKS_DOWNLOADED
                );
            } catch (Exception exception) {
                log.error(
                        "[OUTBOX] Failed to republish book {}. It remains pending.",
                        bookId,
                        exception
                );
            }
        }

        return republishedCount;
    }
}
