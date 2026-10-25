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

            log.info("Consumidor JMS escuchando en {}", SystemConstants.QUEUE_BOOKS_DOWNLOADED);

            while (!Thread.currentThread().isInterrupted()) {
                Message message = consumer.receive();
                if (message instanceof TextMessage textMsg) {
                    processMessage(session, textMsg);
                }
            }
        } catch (JMSException e) {
            log.error("Excepción en consumidor JMS", e);
        }
    }

    private void processMessage(Session session, TextMessage message) {
        int bookId = -1;
        try {
            BookDownloaded event = gson.fromJson(message.getText(), BookDownloaded.class);
            bookId = event.getBook_id();

            boolean processed = orchestrator.indexBook(bookId, false);

            if (!processed) {
                message.acknowledge();
                return;
            }

            message.acknowledge();

        } catch (Exception e) {
            log.error("Error indexando libro ID: {}", bookId, e);
            handleFailure(session, message, bookId, e);
        }
    }

    private void handleFailure(Session session, Message message, int bookId, Exception ex) {
        try {
            var retryMap = hazelcast.getMap("book-retries");
            int attempts = (Integer) retryMap.getOrDefault(bookId, 0) + 1;
            retryMap.put(bookId, attempts);

            if (attempts >= config.maxRetries) {
                log.warn("Libro {} superó reintentos ({}). Enviando a DLQ.", bookId, attempts);
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
                session.recover();
            }
        } catch (Exception jmsEx) {
            log.error("Fallo gestionando DLQ para libro {}", bookId, jmsEx);
        }
    }
}