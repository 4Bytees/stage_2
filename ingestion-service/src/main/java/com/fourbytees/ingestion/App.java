package com.fourbytees.ingestion;

import com.fourbytees.common.factory.HazelcastClientFactory;
import com.fourbytees.common.factory.JmsClientFactory;
import com.fourbytees.ingestion.controller.IngestionController;
import com.fourbytees.ingestion.service.DatalakeStorage;
import com.fourbytees.ingestion.service.GutenbergDownloader;
import com.fourbytees.ingestion.service.OutboxService;
import com.hazelcast.core.HazelcastInstance;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        int port = Integer.parseInt(getEnvOrDefault("PORT", "7001"));
        String datalakePath = getEnvOrDefault("DATALAKE_PATH", "./datalake");
        String activeMqUrl = getEnvOrDefault("ACTIVEMQ_URL", "tcp://localhost:61616");
        String hzAddress = getEnvOrDefault("HZ_ADDRESS", getEnvOrDefault("HAZELCAST_ADDRESS", "localhost:5701"));
        String hzClusterName = getEnvOrDefault("HZ_CLUSTER_NAME", getEnvOrDefault("HAZELCAST_CLUSTER_NAME", "dev"));

        GutenbergDownloader downloader = new GutenbergDownloader();
        DatalakeStorage storage = new DatalakeStorage(datalakePath);

        Connection jmsConn = null;
        try {
            log.info("Conectando Ingestion Service a ActiveMQ en {}...", activeMqUrl);
            jmsConn = JmsClientFactory.createConnection(activeMqUrl);
        } catch (Exception e) {
            log.warn("Aviso: no se pudo conectar a ActiveMQ ({})", e.getMessage());
        }

        HazelcastInstance hazelcast = null;
        try {
            log.info("Conectando Ingestion Service a Hazelcast en {} (cluster: {})...", hzAddress, hzClusterName);
            hazelcast = HazelcastClientFactory.create(hzAddress, hzClusterName);
        } catch (Exception e) {
            log.warn("Aviso: no se pudo conectar a Hazelcast ({})", e.getMessage());
        }

        OutboxService outboxService = new OutboxService(storage, jmsConn, hazelcast);
        outboxService.runOutboxCheck();

        IngestionController controller = new IngestionController(downloader, storage, outboxService);

        Javalin app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
        }).start(port);

        app.get("/status", controller::getHealth);
        app.post("/ingest/{id}", controller::ingest);
        app.get("/ingest/status/{id}", controller::getStatus);
        app.get("/ingest/list", controller::listBooks);

        log.info("Ingestion Service iniciado exitosamente en el puerto {}", port);
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String prop = System.getProperty(key);
        if (prop != null && !prop.isBlank()) return prop;
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}
