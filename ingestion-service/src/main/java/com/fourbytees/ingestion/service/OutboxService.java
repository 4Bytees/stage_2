package com.fourbytees.ingestion.service;

import com.fourbytees.common.constants.SystemConstants;
import com.fourbytees.common.events.BookDownloaded;
import com.google.gson.Gson;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.*;
import java.util.List;
import java.util.UUID;

/**
 * Servicio Outbox para la publicación garantizada de eventos BookDownloaded a ActiveMQ
 * y sincronización de estado DOWNLOADED en Hazelcast.
 *
 * <p><b>Garantía de entrega: At-Least-Once.</b>
 * La secuencia de publicación es:
 * <ol>
 *   <li>Enviar mensaje persistente a ActiveMQ (books.downloaded).</li>
 *   <li>Actualizar estado en Hazelcast a DOWNLOADED.</li>
 *   <li>Crear marcador {@code <id>.published} en el datalake.</li>
 * </ol>
 * Si el proceso o nodo muere entre el paso 1/2 y el paso 3, el libro quedará sin marcador
 * {@code <id>.published}. Al rearrancar, {@link #runOutboxCheck()} detectará el libro con
 * {@code <id>.done} y sin {@code <id>.published}, procediendo a republicar el evento.
 * Por este motivo, el consumidor (indexing-service) debe ser estrictamente IDEMPOTENTE
 * (como se garantiza mediante DELETE + INSERT en SQLite y CAS replace en Hazelcast).
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

    public OutboxService(DatalakeStorage storage, Connection jmsConnection, HazelcastInstance hazelcast) {
        this.storage = storage;
        this.jmsConnection = jmsConnection;
        this.hazelcast = hazelcast;
    }

    public void publishBook(int bookId, String path) throws Exception {
        String eventId = UUID.randomUUID().toString();
        BookDownloaded event = new BookDownloaded(eventId, bookId, path);

        if (jmsConnection != null) {
            try (Session session = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Destination destination = session.createQueue(SystemConstants.QUEUE_BOOKS_DOWNLOADED);
                try (MessageProducer producer = session.createProducer(destination)) {
                    producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                    TextMessage msg = session.createTextMessage(gson.toJson(event));
                    producer.send(msg);
                }
            }
        }

        if (hazelcast != null) {
            IMap<Integer, String> bookState = hazelcast.getMap(SystemConstants.MAP_BOOK_STATE);
            bookState.put(bookId, SystemConstants.STATE_DOWNLOADED);
        }

        storage.markBookPublished(bookId);
        log.info("Evento BookDownloaded publicado y confirmado para libro ID: {} (event_id: {})", bookId, eventId);
    }

    public int runOutboxCheck() {
        List<Integer> downloadedBooks = storage.listDownloadedBooks();
        log.info("[OUTBOX] Verificando datalake al arrancar. Libros encontrados con .done: {}", downloadedBooks.size());

        int republishedCount = 0;
        for (Integer bookId : downloadedBooks) {
            if (!storage.isBookPublished(bookId)) {
                String path = storage.getBookDirectoryPath(bookId);
                try {
                    publishBook(bookId, path);
                    republishedCount++;
                    log.info("[OUTBOX] Libro ID: {} republicado con éxito a {}", bookId, SystemConstants.QUEUE_BOOKS_DOWNLOADED);
                } catch (Exception e) {
                    log.error("[OUTBOX] Error republicando evento para libro ID: {}. Se mantendrá pendiente.", bookId, e);
                }
            }
        }
        return republishedCount;
    }
}
