package com.fourbytees.indexing;

import com.fourbytees.indexing.config.IndexingConfig;
import com.fourbytees.indexing.controller.IndexingController;
import com.fourbytees.indexing.messaging.BookDownloadedConsumer;
import com.fourbytees.indexing.messaging.HazelcastClientFactory;
import com.fourbytees.indexing.messaging.JmsClientFactory;
import com.fourbytees.indexing.repository.SqliteRepository;
import com.fourbytees.indexing.service.IndexingOrchestrator;
import com.hazelcast.core.HazelcastInstance;
import io.javalin.Javalin;
import javax.jms.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        IndexingConfig config = new IndexingConfig();
        SqliteRepository repository = new SqliteRepository(config.sqliteDbPath);

        log.info("Conectando con Hazelcast en {}...", config.hazelcastAddress);
        HazelcastInstance hazelcast = HazelcastClientFactory.create(config.hazelcastAddress);

        log.info("Conectando con ActiveMQ en {}...", config.activeMqUrl);
        Connection jmsConn;
        try {
            jmsConn = JmsClientFactory.createConnection(config.activeMqUrl);
        } catch (Exception e) {
            throw new RuntimeException("Fallo conectando a ActiveMQ", e);
        }

        IndexingOrchestrator orchestrator = new IndexingOrchestrator(config, repository, hazelcast, jmsConn);
        IndexingController controller = new IndexingController(orchestrator, repository, config);

        for (int i = 0; i < config.jmsConsumers; i++) {
            Thread t = new Thread(new BookDownloadedConsumer(jmsConn, config, orchestrator, hazelcast), "jms-consumer-" + i);
            t.setDaemon(true);
            t.start();
        }

        Javalin app = Javalin.create(cfg -> {
            cfg.http.defaultContentType = "application/json";
        }).start(config.port);

        app.post("/index/update/{id}", controller::updateSingle);
        app.post("/index/rebuild", controller::rebuildAll);
        app.get("/index/status", controller::getStatus);
        app.get("/status", controller::getHealth);

        log.info("Indexing Service iniciado en el puerto {}", config.port);
    }
}