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
    public void testIdempotenciaYAtomicidad(@TempDir Path tempDir) throws IOException {
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

        // 1. Simulación de crash: Guardamos el libro (se crea .header, .body y .done),
        // pero el proceso es "matado" antes de publicar el evento a ActiveMQ y Hazelcast.
        String savedPath = storage.saveBook(bookId, "Title: Crash Test", "Sample body content");
        assertTrue(storage.isBookDownloaded(bookId), "El libro debe estar descargado con .done");
        assertFalse(storage.isBookPublished(bookId), "El libro aún NO debe estar marcado como publicado");
        assertNull(hazelcast.getMap(SystemConstants.MAP_BOOK_STATE).get(bookId), "El estado en Hazelcast aún no debe existir");

        // Preparamos consumidor JMS en la cola books.downloaded
        Session session = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination queue = session.createQueue(SystemConstants.QUEUE_BOOKS_DOWNLOADED);
        MessageConsumer consumer = session.createConsumer(queue);

        // 2. Rearranque: Se inicia un nuevo OutboxService y se ejecuta el check de outbox
        OutboxService outboxRecovery = new OutboxService(storage, jmsConnection, hazelcast);
        int republished = outboxRecovery.runOutboxCheck();
        assertEquals(1, republished, "El outbox debe haber detectado y republicado exactamente 1 libro");

        // 3. Verificamos que el evento llegó a la cola con event_id y book_id correctos
        Message msg = consumer.receive(3000);
        assertNotNull(msg, "Debe recibirse el evento en la cola books.downloaded tras el rearranque");
        assertTrue(msg instanceof TextMessage);
        TextMessage textMsg = (TextMessage) msg;
        BookDownloaded event = gson.fromJson(textMsg.getText(), BookDownloaded.class);
        assertEquals(bookId, event.getBook_id(), "El book_id recibido debe coincidir con el del libro guardado");
        assertNotNull(event.getEvent_id(), "El evento debe contener un event_id no nulo");
        assertFalse(event.getEvent_id().isBlank(), "El event_id no debe estar vacío");
        assertNotNull(event.getDownloaded_at());

        // 4. Verificamos que el libro ahora sí está marcado como publicado en disco y en Hazelcast (DOWNLOADED)
        assertTrue(storage.isBookPublished(bookId), "El libro debe quedar marcado con .published");
        assertEquals(SystemConstants.STATE_DOWNLOADED, hazelcast.getMap(SystemConstants.MAP_BOOK_STATE).get(bookId),
                "El estado en Hazelcast debe ser DOWNLOADED tras la publicación");

        // 5. Idempotencia del Outbox: una segunda ejecución no duplica mensajes en la cola
        int secondCheck = outboxRecovery.runOutboxCheck();
        assertEquals(0, secondCheck, "El outbox no debe republicar libros que ya tienen .published");
        Message duplicateMsg = consumer.receive(500);
        assertNull(duplicateMsg, "No debe haber mensajes duplicados en la cola");

        consumer.close();
        session.close();
    }
}
