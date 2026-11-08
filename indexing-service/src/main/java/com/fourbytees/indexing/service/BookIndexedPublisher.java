package com.fourbytees.indexing.service;

import com.fourbytees.common.constants.SystemConstants;
import com.fourbytees.common.events.BookIndexed;
import com.google.gson.Gson;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.Session;
import javax.jms.MessageProducer;
import javax.jms.TextMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Publishes {@link BookIndexed} events after the index write has been committed.
 *
 * <p><b>Delivery guarantee: at-least-once.</b> Before the event is sent it is recorded in a
 * Hazelcast outbox map ({@value #OUTBOX_MAP}, key = {@code event_id}, value = JSON payload). The
 * entry is removed only after the broker accepted the message. Publication is retried a bounded
 * number of times right after the commit; if every attempt fails, the entry stays in the outbox and
 * {@link #flushPending(long)} (run periodically by every indexing replica) delivers it later. The
 * SQLite write is never repeated because retries only re-send the stored payload.</p>
 *
 * <p>The same {@code event_id} is used for every attempt, so a duplicate (for example when the
 * broker accepted a message but the acknowledgement was lost) is identical to the original and
 * consumers can discard it by {@code event_id}. Ordering across books is not guaranteed; consumers
 * should compare {@code index_version}.</p>
 *
 * <p>Remaining gap: if the indexing process dies after the SQLite commit but before the outbox
 * entry is written, no event exists yet. The book then keeps its claim, the claim expires and the
 * redelivered {@code BookDownloaded} message re-indexes the book (an idempotent write) and emits a
 * new event.</p>
 */
public class BookIndexedPublisher {

    private static final Logger log = LoggerFactory.getLogger(BookIndexedPublisher.class);

    /** Hazelcast map that holds events that have been committed but not yet delivered. */
    public static final String OUTBOX_MAP = "book-indexed-outbox";

    /** Sends one serialized event to the broker. */
    @FunctionalInterface
    public interface Sender {
        void send(String payload) throws Exception;
    }

    private final IMap<String, String> outbox;
    private final Sender sender;
    private final int maxAttempts;
    private final long retryDelayMs;
    private final Gson gson = new Gson();

    public BookIndexedPublisher(HazelcastInstance hazelcast, Sender sender, int maxAttempts, long retryDelayMs) {
        this.outbox = hazelcast.getMap(OUTBOX_MAP);
        this.sender = sender;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMs = Math.max(0L, retryDelayMs);
    }

    /** Creates a sender that publishes persistent messages to the {@code books.indexed} queue. */
    public static Sender jmsSender(Connection connection) {
        return payload -> {
            try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Destination destination = session.createQueue(SystemConstants.QUEUE_BOOKS_INDEXED);
                try (MessageProducer producer = session.createProducer(destination)) {
                    TextMessage message = session.createTextMessage(payload);
                    producer.send(message);
                }
            }
        };
    }

    /** Records the event durably (in Hazelcast) so that it survives a failed publication. */
    public void record(BookIndexed event) {
        outbox.put(event.getEvent_id(), gson.toJson(event));
    }

    /**
     * Publishes a recorded event with bounded retries.
     *
     * @return {@code true} if the broker accepted the event; {@code false} if the event was left in
     *         the outbox for {@link #flushPending(long)}
     */
    public boolean publish(BookIndexed event) {
        String payload = gson.toJson(event);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                sender.send(payload);
                outbox.remove(event.getEvent_id());
                return true;
            } catch (Exception exception) {
                log.warn("Attempt {}/{} to publish BookIndexed {} for book {} failed.",
                        attempt, maxAttempts, event.getEvent_id(), event.getBook_id(), exception);

                if (attempt < maxAttempts && !pause(retryDelayMs * attempt)) {
                    break;
                }
            }
        }

        log.error("BookIndexed {} for book {} could not be published. It stays in the outbox and will be retried.",
                event.getEvent_id(), event.getBook_id());
        return false;
    }

    /**
     * Delivers outbox entries that are at least {@code minAgeMs} old.
     *
     * <p>The age check avoids racing with the publisher that has just recorded the event. A
     * per-entry lock keeps replicas from sending the same pending entry at the same time.</p>
     *
     * @return number of events delivered by this call
     */
    public int flushPending(long minAgeMs) {
        int delivered = 0;
        long now = System.currentTimeMillis();

        for (Map.Entry<String, String> entry : new ArrayList<>(outbox.entrySet())) {
            String eventId = entry.getKey();

            if (ageMs(entry.getValue(), now) < minAgeMs) {
                continue;
            }

            if (!outbox.tryLock(eventId)) {
                continue;
            }

            try {
                String payload = outbox.get(eventId);
                if (payload == null) {
                    continue;
                }

                sender.send(payload);
                outbox.remove(eventId);
                delivered++;
                log.info("Delivered pending BookIndexed {} from the outbox.", eventId);
            } catch (Exception exception) {
                log.warn("Pending BookIndexed {} is still undeliverable.", eventId, exception);
            } finally {
                outbox.unlock(eventId);
            }
        }

        return delivered;
    }

    /** Returns the ids of the events that are waiting for delivery. */
    public List<String> pendingEventIds() {
        return new ArrayList<>(outbox.keySet());
    }

    private long ageMs(String payload, long now) {
        try {
            BookIndexed event = gson.fromJson(payload, BookIndexed.class);
            return Math.max(0L, now - Instant.parse(event.getIndexed_at()).toEpochMilli());
        } catch (RuntimeException exception) {
            return Long.MAX_VALUE;
        }
    }

    private boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
