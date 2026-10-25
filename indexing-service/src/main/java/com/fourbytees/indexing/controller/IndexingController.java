package com.fourbytees.indexing.controller;

import com.fourbytees.indexing.config.IndexingConfig;
import com.fourbytees.indexing.repository.SqliteRepository;
import com.fourbytees.indexing.service.IndexingOrchestrator;
import com.google.gson.Gson;
import io.javalin.http.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Stream;

public class IndexingController {

    private final IndexingOrchestrator orchestrator;
    private final SqliteRepository repository;
    private final IndexingConfig config;
    private final Gson gson = new Gson();
    private final ExecutorService rebuildExecutor;

    public IndexingController(IndexingOrchestrator orchestrator, SqliteRepository repository, IndexingConfig config) {
        this.orchestrator = orchestrator;
        this.repository = repository;
        this.config = config;
        this.rebuildExecutor = Executors.newFixedThreadPool(config.workerThreads);
    }

    public void updateSingle(Context ctx) {
        int bookId;
        try {
            bookId = Integer.parseInt(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            ctx.status(400).result(gson.toJson(Map.of("error", "Invalid book ID")));
            return;
        }

        try {
            boolean updated = orchestrator.indexBook(bookId, true);
            if (updated) {
                ctx.status(200).result(gson.toJson(Map.of("book_id", bookId, "index", "updated")));
            } else {
                ctx.status(500).result(gson.toJson(Map.of("error", "Could not index book")));
            }
        } catch (Exception e) {
            ctx.status(404).result(gson.toJson(Map.of("error", e.getMessage())));
        }
    }

    public void rebuildAll(Context ctx) {
        long startTime = System.currentTimeMillis();
        try {
            repository.clearAll();
            List<Integer> readyBookIds;

            try (Stream<Path> stream = Files.walk(config.datalakePath, 4)) {
                readyBookIds = stream
                        .filter(p -> p.getFileName().toString().endsWith(".done"))
                        .map(p -> Integer.parseInt(p.getFileName().toString().replace(".done", "")))
                        .toList();
            }

            List<CompletableFuture<Void>> futures = readyBookIds.stream()
                    .map(id -> CompletableFuture.runAsync(() -> {
                        try {
                            orchestrator.indexBook(id, true);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, rebuildExecutor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            double elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0;
            ctx.status(200).result(gson.toJson(Map.of(
                    "books_processed", readyBookIds.size(),
                    "elapsed_time", String.format("%.2fs", elapsedSec)
            )));
        } catch (Exception e) {
            ctx.status(500).result(gson.toJson(Map.of("error", e.getMessage())));
        }
    }

    public void getStatus(Context ctx) {
        ctx.status(200).result(gson.toJson(Map.of(
                "books_indexed", repository.getIndexedBooksCount(),
                "last_update", Instant.now().toString(),
                "index_size_MB", Math.round(repository.getDatabaseSizeMB() * 100.0) / 100.0
        )));
    }

    public void getHealth(Context ctx) {
        ctx.status(200).result(gson.toJson(Map.of(
                "service", "indexing-service",
                "status", "running"
        )));
    }
}