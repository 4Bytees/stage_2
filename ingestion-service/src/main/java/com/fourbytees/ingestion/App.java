package com.fourbytees.ingestion;

import com.fourbytees.ingestion.controller.IngestionController;
import com.fourbytees.ingestion.service.DatalakeStorage;
import com.fourbytees.ingestion.service.GutenbergDownloader;
import com.fourbytees.ingestion.service.OutboxService;
import io.javalin.Javalin;

public class App {
    public static void main(String[] args) {
        int port = Integer.parseInt(getEnvOrDefault("PORT", "7001"));
        String datalakePath = getEnvOrDefault("DATALAKE_PATH", "./datalake");

        GutenbergDownloader downloader = new GutenbergDownloader();
        DatalakeStorage storage = new DatalakeStorage(datalakePath);
        OutboxService outboxService = new OutboxService(storage);
        IngestionController controller = new IngestionController(downloader, storage);

        // Ejecutar Outbox al arrancar para republicar si hubiese pendientes
        outboxService.runOutboxCheck();

        Javalin app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
        }).start(port);

        app.get("/status", controller::getHealth);
        app.post("/ingest/{id}", controller::ingest);
        app.get("/ingest/status/{id}", controller::getStatus);
        app.get("/ingest/list", controller::listBooks);

        System.out.println("Ingestion Service iniciado en el puerto " + port);
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}