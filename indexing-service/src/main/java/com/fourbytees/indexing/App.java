package com.fourbytees.indexing;

import com.fourbytees.common.factory.HazelcastClientFactory;
import com.fourbytees.common.factory.JmsClientFactory;
import com.fourbytees.indexing.config.IndexingConfig;
import com.fourbytees.indexing.controller.IndexingController;
import com.fourbytees.indexing.messaging.BookDownloadedConsumer;
import com.fourbytees.indexing.repository.SqliteRepository;
import com.fourbytees.indexing.service.IndexRebuilder;
import com.fourbytees.indexing.service.IndexingOrchestrator;
import com.hazelcast.core.HazelcastInstance;
import io.javalin.Javalin;
import javax.jms.Connection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        IndexingConfig config = new IndexingConfig();
        SqliteRepository repository = new SqliteRepository(config.sqliteDbPath);

        log.info("Conectando con Hazelcast en {} (cluster: {})...", config.hazelcastAddress, config.hazelcastClusterName);
        HazelcastInstance hazelcast;
        try {
            hazelcast = HazelcastClientFactory.create(config.hazelcastAddress, config.hazelcastClusterName);
            log.info("Indexing Service conectado exitosamente a Hazelcast.");
        } catch (Exception e) {
            log.error("Fallo crítico conectando con Hazelcast tras timeout de 60s", e);
            throw new RuntimeException("Fallo crítico conectando a Hazelcast en " + config.hazelcastAddress, e);
        }

        log.info("Conectando con ActiveMQ en {}...", config.activeMqUrl);
        Connection jmsConn;
        try {
            jmsConn = JmsClientFactory.createConnection(config.activeMqUrl);
            log.info("Indexing Service conectado exitosamente a ActiveMQ.");
        } catch (Exception e) {
            log.error("Fallo conectando a ActiveMQ en {}", config.activeMqUrl, e);
            throw new RuntimeException("Fallo conectando a ActiveMQ en " + config.activeMqUrl, e);
        }

        IndexingOrchestrator orchestrator = new IndexingOrchestrator(config, repository, hazelcast, jmsConn);
        IndexRebuilder rebuilder = new IndexRebuilder(repository, hazelcast, orchestrator, config.workerThreads);
        IndexingController controller = new IndexingController(orchestrator, repository, rebuilder);

        for (int i = 0; i < config.jmsConsumers; i++) {
            Thread t = new Thread(new BookDownloadedConsumer(jmsConn, config, orchestrator, hazelcast), "jms-consumer-" + i);
            t.setDaemon(true);
            t.start();
        }

        ScheduledExecutorService outboxRelay = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "book-indexed-outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
        outboxRelay.scheduleWithFixedDelay(() -> {
            try {
                orchestrator.flushPendingEvents();
            } catch (RuntimeException e) {
                log.warn("Could not flush pending BookIndexed events.", e);
            }
        }, config.outboxFlushIntervalMs, config.outboxFlushIntervalMs, TimeUnit.MILLISECONDS);

        Javalin app = Javalin.create(cfg -> {
            cfg.http.defaultContentType = "application/json";
        }).start(config.port);

        app.post("/index/update/{id}", controller::updateSingle);
        app.post("/index/rebuild", controller::rebuildAll);
        app.get("/index/status", controller::getStatus);
        app.get("/status", controller::getHealth);

        log.info("Indexing Service iniciado exitosamente en el puerto {}", config.port);
    }
}
