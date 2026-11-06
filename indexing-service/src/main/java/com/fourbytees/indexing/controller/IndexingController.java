package com.fourbytees.indexing.controller;

import com.fourbytees.indexing.repository.SqliteRepository;
import com.fourbytees.indexing.service.IndexRebuilder;
import com.fourbytees.indexing.service.IndexingOrchestrator;
import com.google.gson.Gson;
import io.javalin.http.Context;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

public class IndexingController {

    private final IndexingOrchestrator orchestrator;
    private final SqliteRepository repository;
    private final IndexRebuilder rebuilder;
    private final Gson gson = new Gson();

    public IndexingController(IndexingOrchestrator orchestrator, SqliteRepository repository, IndexRebuilder rebuilder) {
        this.orchestrator = orchestrator;
        this.repository = repository;
        this.rebuilder = rebuilder;
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
        try {
            IndexRebuilder.RebuildResult result = rebuilder.rebuild();
            ctx.status(200).result(gson.toJson(Map.of(
                    "books_processed", result.booksProcessed(),
                    "elapsed_time", String.format(Locale.ROOT, "%.2fs", result.elapsedSeconds())
            )));
        } catch (IndexRebuilder.RebuildInProgressException e) {
            ctx.status(409).result(gson.toJson(Map.of("error", "An index rebuild is already in progress")));
        } catch (IndexRebuilder.RebuildFailedException e) {
            ctx.status(500).result(gson.toJson(Map.of("error", "Index rebuild failed. The previous index remains active.")));
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