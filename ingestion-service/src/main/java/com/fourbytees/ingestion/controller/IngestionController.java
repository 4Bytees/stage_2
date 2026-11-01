package com.fourbytees.ingestion.controller;

import com.fourbytees.ingestion.service.DatalakeStorage;
import com.fourbytees.ingestion.service.GutenbergDownloader;
import com.fourbytees.ingestion.service.OutboxService;
import io.javalin.http.Context;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class IngestionController {
    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);

    private final GutenbergDownloader downloader;
    private final DatalakeStorage storage;
    private final OutboxService outboxService;
    private final Gson gson = new Gson();

    public IngestionController(GutenbergDownloader downloader, DatalakeStorage storage, OutboxService outboxService) {
        this.downloader = downloader;
        this.storage = storage;
        this.outboxService = outboxService;
    }

    public IngestionController(GutenbergDownloader downloader, DatalakeStorage storage) {
        this(downloader, storage, new OutboxService(storage));
    }

    public void ingest(Context ctx) {
        int bookId;
        try {
            bookId = Integer.parseInt(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            ctx.status(400).result(gson.toJson(Map.of("error", "Bad Request", "message", "Invalid book ID format.")));
            return;
        }

        if (storage.isBookDownloaded(bookId)) {
            if (outboxService != null && !storage.isBookPublished(bookId)) {
                try {
                    String existingPath = storage.getBookDirectoryPath(bookId);
                    outboxService.publishBook(bookId, existingPath);
                } catch (Exception ex) {
                    log.warn("Book {} is already downloaded, but outbox publication failed.", bookId, ex);
                }
            }
            ctx.status(200).result(gson.toJson(Map.of("book_id", bookId, "status", "already_downloaded")));
            return;
        }

        try {
            String[] content = downloader.downloadAndSplit(bookId);
            String savedPath = storage.saveBook(bookId, content[0], content[1]);

            if (outboxService != null) {
                try {
                    outboxService.publishBook(bookId, savedPath);
                } catch (Exception ex) {
                    log.error("Book {} was saved with a .done marker, but immediate ActiveMQ publication failed. It will be recovered on the next outbox check.", bookId, ex);
                }
            }

            ctx.status(200).result(gson.toJson(Map.of(
                    "book_id", bookId,
                    "status", "downloaded",
                    "path", savedPath
            )));
        } catch (RuntimeException e) {
            if ("FAILED_404".equals(e.getMessage())) {
                ctx.status(404).result(gson.toJson(Map.of("book_id", bookId, "status", "FAILED_404")));
            } else {
                ctx.status(500).result(gson.toJson(Map.of("error", e.getMessage())));
            }
        } catch (Exception e) {
            ctx.status(500).result(gson.toJson(Map.of("error", e.getMessage())));
        }
    }

    public void getStatus(Context ctx) {
        int bookId;
        try {
            bookId = Integer.parseInt(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            ctx.status(400).result(gson.toJson(Map.of("error", "Bad Request", "message", "Invalid book ID format.")));
            return;
        }
        boolean ready = storage.isBookDownloaded(bookId);
        ctx.status(200).result(gson.toJson(Map.of("book_id", bookId, "status", ready ? "available" : "not_found")));
    }

    public void listBooks(Context ctx) {
        var books = storage.listDownloadedBooks();
        ctx.status(200).result(gson.toJson(Map.of("count", books.size(), "books", books)));
    }

    public void getHealth(Context ctx) {
        ctx.status(200).result(gson.toJson(Map.of("service", "ingestion-service", "status", "running")));
    }
}
