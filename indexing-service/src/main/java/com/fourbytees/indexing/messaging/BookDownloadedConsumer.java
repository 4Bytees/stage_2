package com.fourbytees.indexing.messaging;

import com.fourbytees.common.constants.SystemConstants;
import com.fourbytees.common.events.BookDownloaded;
import com.fourbytees.indexing.config.IndexingConfig;
import com.fourbytees.indexing.service.IndexingOrchestrator;
import com.google.gson.Gson;
import com.hazelcast.core.HazelcastInstance;
import javax.jms.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fourbytees.indexing.service.IndexingResult;

public class BookDownloadedConsumer implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(BookDownloadedConsumer.class);

    private final Connection connection;
    private final IndexingConfig config;
    private final IndexingOrchestrator orchestrator;
    private final HazelcastInstance hazelcast;
    private final Gson gson = new Gson();

    public BookDownloadedConsumer(Connection connection, IndexingConfig config,
                                  IndexingOrchestrator orchestrator, HazelcastInstance hazelcast) {
        this.connection = connection;
        this.config = config;
        this.orchestrator = orchestrator;
        this.hazelcast = hazelcast;
    }

    @Override
    public void run() {
        try {
            Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            Destination queue = session.createQueue(SystemConstants.QUEUE_BOOKS_DOWNLOADED);
            MessageConsumer consumer = session.createConsumer(queue);

            log.info("JMS consumer listening on {}", SystemConstants.QUEUE_BOOKS_DOWNLOADED);

            while (!Thread.currentThread().isInterrupted()) {
                Message message = consumer.receive();
                if (message instanceof TextMessage textMsg) {
                    processMessage(session, textMsg);
                }
            }
        } catch (JMSException e) {
            log.error("JMS consumer exception", e);
        }
    }

    private void processMessage(Session session, TextMessage message) {
        int bookId = -1;
        try {
            BookDownloaded event = gson.fromJson(message.getText(), BookDownloaded.class);
            bookId = event.getBook_id();

            IndexingResult result = orchestrator.indexDownloadedBook(bookId);

            switch (result) {
                case INDEXED -> {
                    message.acknowledge();
                    hazelcast.getMap(SystemConstants.MAP_BOOK_RETRIES).remove(bookId);

                    log.info(
                            "Book {} was indexed and its JMS message was acknowledged.",
                            bookId
                    );
                }

                case ALREADY_INDEXED -> {
                    message.acknowledge();
                    hazelcast.getMap(SystemConstants.MAP_BOOK_RETRIES).remove(bookId);

                    log.info(
                            "Duplicate delivery for already indexed book {} was acknowledged.",
                            bookId
                    );
                }

                case RETRY_LATER -> {
                    log.info(
                            "Book {} is currently claimed by another indexing instance. "
                                    + "The JMS message will be redelivered.",
                            bookId
                    );

                    pauseBeforeRedelivery();
                    session.recover();
                }
            }

        } catch (Exception e) {
            log.error("Failed to index book {}", bookId, e);
            handleFailure(session, message, bookId, e);
        }
    }

    private void handleFailure(Session session, Message message, int bookId, Exception ex) {
        try {
            var retryMap = hazelcast.getMap(SystemConstants.MAP_BOOK_RETRIES);
            int attempts = (Integer) retryMap.getOrDefault(bookId, 0) + 1;
            retryMap.put(bookId, attempts);

            if (attempts >= config.maxRetries) {
                log.warn("Book {} exceeded max retries ({}). Routing to DLQ.", bookId, attempts);
                hazelcast.getMap(SystemConstants.MAP_BOOK_STATE).put(bookId, SystemConstants.STATE_FAILED);

                Destination dlq = session.createQueue(SystemConstants.QUEUE_BOOKS_DLQ);
                try (MessageProducer dlqProducer = session.createProducer(dlq)) {
                    TextMessage dlqMsg = session.createTextMessage(
                            String.format("{\"book_id\": %d, \"error\": \"%s\", \"attempts\": %d}",
                                    bookId, ex.getMessage(), attempts));
                    dlqProducer.send(dlqMsg);
                }
                message.acknowledge();
            } else {
                hazelcast.getMap(SystemConstants.MAP_BOOK_STATE).put(bookId, SystemConstants.STATE_DOWNLOADED);
                pauseBeforeRedelivery();
                session.recover();
            }
        } catch (Exception jmsEx) {
            log.error("Failed to handle DLQ routing for book {}", bookId, jmsEx);
        }
    }

    private void pauseBeforeRedelivery() {
        if (config.retryLaterDelayMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(config.retryLaterDelayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
